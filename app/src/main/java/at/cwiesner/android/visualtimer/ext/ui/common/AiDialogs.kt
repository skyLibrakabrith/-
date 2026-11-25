package at.cwiesner.android.visualtimer.ext.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.llm.AiFeatures
import at.cwiesner.android.visualtimer.ext.llm.AiResult
import at.cwiesner.android.visualtimer.ext.llm.TodoStep
import at.cwiesner.android.visualtimer.ext.settings.AiMode
import kotlinx.coroutines.flow.first
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
    initiallyEmpty: Boolean = false,
    run: suspend (AiFeatures, String) -> AiResult<T>,
    renderPreview: @Composable (T) -> Unit,
    onApply: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ExtApplication).settings
    val ai = remember { AiFeatures(settings) }

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<T?>(null) }
    // AI 关闭时把入口藏起来，而不是让用户点进去再报「未开启」
    var aiOff by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        aiOff = settings.aiMode.first() == AiMode.Off
        if (initiallyEmpty) input = ""
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 420.dp)
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
                            when (val r = run(ai, input)) {
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
            TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") }
        },
    )
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
) {
    AiPromptDialog(
        title = "✨ 拆解成阶段",
        hint = "要完成的一件事，例如：写一份实验报告",
        actionLabel = "拆解",
        run = { ai, text -> ai.split(text) },
        renderPreview = { steps ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${i + 1}. ${s.title}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${s.minutes} 分钟", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    AiPromptDialog(
        title = "🧠 整理念头",
        hint = "每行一个念头",
        actionLabel = "整理",
        run = { ai, text -> ai.compile(text) },
        renderPreview = { groups ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    Text("【${g.name}】", fontSize = 13.sp,
                        style = MaterialTheme.typography.titleSmall)
                    g.items.forEach { it ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("· ${it.title}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${it.minutes} 分", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        onApply = onApply,
        onDismiss = onDismiss,
    )
}
