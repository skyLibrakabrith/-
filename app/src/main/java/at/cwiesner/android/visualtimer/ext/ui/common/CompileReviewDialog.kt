package at.cwiesner.android.visualtimer.ext.ui.common

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.llm.AiFeatures
import at.cwiesner.android.visualtimer.ext.llm.AiResult
import at.cwiesner.android.visualtimer.ext.llm.EstimateItem
import at.cwiesner.android.visualtimer.ext.llm.TodoGroup
import at.cwiesner.android.visualtimer.ext.ui.sequences.StepDraft
import kotlinx.coroutines.launch

/**
 * Compiler 的第 2、3 步：**排序 → 估时 → 交付**。
 *
 * 为什么不把这两步塞进 [AiPromptDialog]：那个对话框的交互是"输入→出结果→采用"，
 * 而这里中间要插入用户编辑（调顺序、改难度、改背景），是另一种交互形态。
 *
 * 流程：
 * 1. 进来时把分组**拍平**成一条有序列表（按模型给的组内顺序拼起来）
 * 2. 用户上下移动、微调难度、改背景
 * 3. 点「估时并生成」→ 调 [AiFeatures.estimateList] 对**当前顺序**估时
 * 4. 成功则回传带时长的 [StepDraft] 列表，由调用方建序列
 *
 * 为什么要分开估：顺序一变时长就变（先查资料再写 vs 先写再补），
 * 所以必须在用户排完之后估，不能沿用拆解时给的。
 */
@Composable
fun CompileReviewDialog(
    groups: List<TodoGroup>,
    onDismiss: () -> Unit,
    onConfirm: (List<StepDraft>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ExtApplication).settings
    val ai = remember { AiFeatures(settings) }

    // 拍平顺序 = 组的顺序 × 组内顺序。组名记下来留着显示，但序列本身是线性的一串。
    var items by remember {
        mutableStateOf(
            groups.flatMap { g -> g.items.map { Triple(g.name, it.title, it) } },
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ---- 网络慢时的「只是提醒」----
    //
    // 需求原话：「网络卡的时候 AI 可能没办法在 20s 内把数据传到设备，
    // 这个时候直接截停不合适，应该是给个提示」。
    //
    // 所以这里**不中止请求**，只在超过阈值后多显示一行提示；
    // 真正的 HTTP 读取超时被挪到了 10 分钟的硬上限（见 LlmHttpClient）。
    // 用户想停就点「取消」—— 取消**在加载中也必须可用**，否则就被锁死了。
    val slowHintSeconds by settings.aiTimeoutSeconds
        .collectAsState(initial = SettingsRepo.DEFAULT_AI_TIMEOUT_SECONDS)
    var slow by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(loading, slowHintSeconds) {
        slow = false
        if (loading) {
            delay(slowHintSeconds * 1000L)
            slow = true
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("调整顺序") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "把顺序调成你实际要做的样子，点「估时并生成」会自动给每一步算时长。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                items.forEachIndexed { index, entry ->
                    val (groupName, title, step) = entry
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. $title",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = index > 0 && !loading,
                                onClick = {
                                    items = items.toMutableList().also {
                                        val t = it[index]; it[index] = it[index - 1]; it[index - 1] = t
                                    }
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                enabled = index < items.lastIndex && !loading,
                                onClick = {
                                    items = items.toMutableList().also {
                                        val t = it[index]; it[index] = it[index + 1]; it[index + 1] = t
                                    }
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp))
                            }
                        }

                        Text(
                            "来自「$groupName」",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // 难度：5 个小方块，点哪算哪（和 AiPromptDialog 里的 LevelSlider 一致）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text("难度", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            (1..5).forEach { i ->
                                val on = step.difficulty != null && i <= step.difficulty
                                Box(
                                    modifier = Modifier
                                        .size(width = 18.dp, height = 12.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                        )
                                        .clickable(enabled = !loading) {
                                            // 再点同一档等于取消标注
                                            val newDiff = if (step.difficulty == i) null else i
                                            items = items.toMutableList().also {
                                                it[index] = Triple(groupName, title, step.copy(difficulty = newDiff))
                                            }
                                        },
                                )
                            }
                        }

                        // 背景：默认收起，点开才显示输入框（列表里太长会喧宾夺主）
                        var editingCtx by remember(entry) { mutableStateOf(false) }
                        val ctxText = step.context.orEmpty()
                        if (editingCtx) {
                            OutlinedTextField(
                                value = ctxText,
                                onValueChange = { v ->
                                    items = items.toMutableList().also {
                                        it[index] = Triple(
                                            groupName, title,
                                            step.copy(context = v.ifBlank { null }),
                                        )
                                    }
                                },
                                label = { Text("背景 / 理由", fontSize = 10.sp) },
                                singleLine = true,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        } else {
                            Text(
                                if (ctxText.isBlank()) "＋ 加背景" else "背景：$ctxText",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(enabled = !loading) { editingCtx = true }
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (slow) "网络较慢，还在等模型估时…（可以继续等，也可以取消）"
                            else "正在按当前顺序估时…",
                            fontSize = 11.sp,
                        )
                    }
                }

                error?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && items.isNotEmpty(),
                onClick = {
                    loading = true
                    error = null
                    requestJob = scope.launch {
                        val payload = items.map { (_, title, step) ->
                            EstimateItem(
                                title = title,
                                difficulty = step.difficulty,
                                context = step.context,
                            )
                        }
                        when (val r = ai.estimateList(payload)) {
                            is AiResult.Ok -> {
                                val drafts = items.mapIndexed { i, entry ->
                                    val (_, title, step) = entry
                                    StepDraft(
                                        label = title,
                                        // 至少 1 分钟，避免模型给 0
                                        durationMillis = (r.value.getOrNull(i) ?: 5).coerceAtLeast(1) * 60_000L,
                                        difficulty = step.difficulty,
                                        context = step.context,
                                    )
                                }
                                onConfirm(drafts)
                            }
                            is AiResult.Failed -> {
                                error = r.message
                                loading = false
                            }
                        }
                    }
                },
            ) { Text("估时并生成") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // 同上：加载中也要能取消，否则网络慢时只能干等或强行退出
                    if (loading) {
                        requestJob?.cancel()
                        loading = false
                    }
                    onDismiss()
                },
            ) { Text("取消") }
        },
    )
}
