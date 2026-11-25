package at.cwiesner.android.visualtimer.ext.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.llm.AiFeatures
import at.cwiesner.android.visualtimer.ext.llm.AiResult
import at.cwiesner.android.visualtimer.ext.llm.TodoStep
import at.cwiesner.android.visualtimer.ext.settings.AiMode
import kotlinx.coroutines.launch

/**
 * AI 功能共用的「先问一句话，再拿到结构化结果」对话框。
 *
 * 三个入口（拆解 / 估时 / 整理念头）的交互骨架完全一样：
 * 输入框 → 点按钮 → loading → 成功给预览 / 失败给人话。
 * 所以抽成一个通用的 [AiPromptDialog]，各功能只提供提示语和结果渲染。
 *
 * **关键设计：成功时不直接落库，而是先给用户看一眼**。
 * 模型（尤其本地 4B）输出不一定靠谱，直接写进数据库等事故。
 *
 * @param title 对话框标题
 * @param hint 输入框提示语
 * @param actionLabel 确认按钮文字（如「拆解」）
 * @param run 实际调用；返回 [AiResult]
 * @param renderPreview 成功后的预览渲染
 * @param onApply 用户确认采用的回调
 */
@Composable
fun <T> AiPromptDialog(
    title: String,
    hint: String,
    actionLabel: String,
    run: suspend (AiFeatures, String, AiTuning) -> AiResult<T>,
    renderPreview: @Composable (T) -> Unit,
    onApply: (T) -> Unit,
    onDismiss: () -> Unit,
    /** 是否显示「颗粒度 / 难度 / 背景」这组高级选项。估时这类不需要。 */
    showTuning: Boolean = false,
    /** 输入框预填内容（例如从已有计时器拆解时把它当前的名字带进来）。 */
    initialInput: String = "",
    /**
     * 草稿键。非空时：打开对话框会恢复上次没提交的内容，
     * 取消/返回会把当前输入存起来。
     *
     * 为什么需要：这些输入往往是当场想到的一堆念头，
     * 点「取消」就全没了、要重打一遍非常痛苦。
     */
    draftKey: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ExtApplication).settings
    val ai = remember { AiFeatures(settings) }

    var input by remember { mutableStateOf(initialInput) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<T?>(null) }
    var tuning by remember { mutableStateOf(AiTuning()) }

    // 打开时恢复草稿。
    // 放在 LaunchedEffect 里而不是同步读：DataStore 是磁盘读取，只能 suspend。
    // 首帧 input 是 initialInput（可能为空），读到草稿后会替换 —— 这一帧的闪动可以接受，
    // 因为对话框刚弹出、用户还没开始打字。
    LaunchedEffect(draftKey) {
        if (draftKey == null) return@LaunchedEffect
        val saved = runCatching { settings.draftOf(draftKey) }.getOrDefault("")
        // 只有草稿非空、且用户还没开始输入时才覆盖 —— 避免把用户刚敲的字冲掉
        if (saved.isNotBlank() && input.isBlank()) input = saved
    }

    /**
     * 关闭并保存草稿。
     *
     * 三种关闭路径（点外面、点取消、按返回）都走这里，
     * 保证"没提交的输入"一律能留下来。
     */
    fun dismissWithDraft() {
        if (loading) return
        if (draftKey != null) {
            val text = input
            scope.launch {
                // 已经产生结果说明用户走完了流程，草稿不再需要
                runCatching { settings.setDraft(draftKey, if (result != null) "" else text) }
            }
        }
        onDismiss()
    }

    // 用 collectAsState 而不是 LaunchedEffect+局部 state：
    // 后者首帧是默认值、协程结束后才变真值 —— 会让内容"先少一块再补上"。
    // settings 底层是应用级预热的 StateFlow，这里同步读到缓存。
    val aiMode by settings.aiMode.collectAsState(initial = AiMode.Off)
    val aiOff = aiMode == AiMode.Off

    AlertDialog(
        onDismissRequest = { dismissWithDraft() },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (aiOff) {
                    Text(
                        "AI 功能还没开启。去「设置 → AI」选一个模式后再来。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null; result = null },
                    label = { Text(hint) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (showTuning) {
                    LevelSlider(
                        label = "颗粒度",
                        help = "越高拆得越细（1=3~4 步，5=8~12 步）",
                        value = tuning.granularity,
                        enabled = !loading,
                        onChange = { tuning = tuning.copy(granularity = it) },
                    )
                    LevelSlider(
                        label = "难度",
                        help = "越高估时越保守（含启动摩擦、卡壳、返工）",
                        value = tuning.difficulty,
                        enabled = !loading,
                        onChange = { tuning = tuning.copy(difficulty = it) },
                    )
                    OutlinedTextField(
                        value = tuning.context,
                        onValueChange = { tuning = tuning.copy(context = it) },
                        label = { Text("背景 / 理由（可空）") },
                        minLines = 1,
                        maxLines = 3,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("思考中…", fontSize = 12.sp)
                    }
                }

                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                result?.let { r ->
                    Text("预览（确认后才会写入）：", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    renderPreview(r)
                }
            }
        },
        confirmButton = {
            // 结果已经生成时按钮变成「采用」；改过输入会清掉结果，重新变回原动作
            val hasResult = result != null
            TextButton(
                enabled = !loading && input.isNotBlank() && !aiOff,
                onClick = {
                    if (hasResult) {
                        onApply(result as T)
                    } else {
                        loading = true
                        error = null
                        scope.launch {
                            when (val r = run(ai, input, tuning)) {
                                is AiResult.Ok -> result = r.value
                                is AiResult.Failed -> error = r.message
                            }
                            loading = false
                        }
                    }
                },
            ) { Text(if (hasResult) "采用" else actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = { dismissWithDraft() }, enabled = !loading) { Text("取消") }
        },
    )
}

/**
 * 一次 AI 调用的"旋钮"：颗粒度、难度、背景。
 *
 * 做成不可变 data class 而不是三个独立 state：调用方可以整体传递，
 * 也不会出现"改了颗粒度忘了改难度"这种局部不一致。
 */
data class AiTuning(
    val granularity: Int = 3,
    val difficulty: Int = 3,
    val context: String = "",
)

/**
 * 1–5 的档位选择，用一排小方块表示。
 *
 * 不用 Slider：5 个离散档位用滑块很难点准（手指一滑就跳过一格），
 * 而这里恰恰需要"准确选第 N 档"。点格子没有这个问题。
 */
@Composable
private fun LevelSlider(
    label: String,
    help: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                "  $value",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { i ->
                val on = i <= value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (on) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable(enabled = enabled) { onChange(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$i",
                        fontSize = 11.sp,
                        color = if (on) Color.White else Color.Gray,
                    )
                }
            }
        }
        Text(help, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Magic ToDo 的入口：一段话 → 有序阶段。
 *
 * 复用 [AiPromptDialog]，只负责渲染步骤预览。
 */
@Composable
fun AiSplitDialog(
    onDismiss: () -> Unit,
    onApply: (List<TodoStep>) -> Unit,
    /** 预填内容：从已有计时器拆解时，把它当前名称带进来省一次输入。 */
    initialInput: String = "",
) {
    AiPromptDialog(
        title = "✨ 拆解成阶段",
        hint = "要完成的一件事，例如：写一份实验报告",
        actionLabel = "拆解",
        showTuning = true,
        initialInput = initialInput,
        run = { ai, text, t ->
            ai.split(text, t.granularity, t.difficulty, t.context)
        },
        renderPreview = { steps ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${i + 1}. ${s.title}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${s.minutes} 分钟", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 难度用「▮」重复表示，比数字更快看出哪步吃力
                            s.difficulty?.let { d ->
                                Text(
                                    "难" + "▮".repeat(d.coerceIn(1, 5)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                Text(
                    "合计 ${steps.sumOf { it.minutes }} 分钟",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        onApply = onApply,
        onDismiss = onDismiss,
    )
}

/** Compiler 的入口：一堆碎片念头 → 归类成组。 */
@Composable
fun AiCompileDialog(
    onDismiss: () -> Unit,
    onApply: (List<at.cwiesner.android.visualtimer.ext.llm.TodoGroup>) -> Unit,
    /**
     * 草稿隔离键（通常传当前面板 id）。
     *
     * 按面板分开存，而不是全局一个草稿：用户在 A 面板写了一半的念头，
     * 切到 B 面板再打开对话框，不该看到 A 的内容。
     */
    panelId: Long? = null,
) {
    AiPromptDialog(
        title = "🧠 整理念头",
        hint = "每行一个念头",
        actionLabel = "整理",
        // 没点「整理」就退出时保存输入，下次打开原样恢复
        draftKey = "compile:${panelId ?: 0L}",
        // 这里**不给任何旋钮**：用户此刻手里是一堆混乱念头，
        // 逼他先回答"这堆有多难/拆多细"是过早固化。
        // 难度和背景由模型拆完之后**逐条**给出，用户在下一步的排序界面里改。
        run = { ai, text, _ -> ai.compile(text) },
        renderPreview = { groups ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    Text("【${g.name}】", fontSize = 13.sp,
                        style = MaterialTheme.typography.titleSmall)
                    g.items.forEach { it ->
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("· ${it.title}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                it.difficulty?.let { d ->
                                    Text("难$d", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            it.context?.let { c ->
                                Text(c, fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1)
                            }
                        }
                    }
                }
                Text(
                    "确认后进入排序界面，可在那里调顺序、改难度和背景",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        onApply = onApply,
        onDismiss = onDismiss,
    )
}
