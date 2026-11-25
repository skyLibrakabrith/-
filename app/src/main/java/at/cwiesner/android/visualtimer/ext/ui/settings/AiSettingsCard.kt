package at.cwiesner.android.visualtimer.ext.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.settings.AiMode
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 设置页的 AI 分组。
 *
 * **默认折叠**：这一块只在真正要用 AI 时才需要碰，常驻展开会把设置页撑得很长。
 * 也顺带起到"隐藏入口"的作用 —— 不看设置就看不到 AI 相关的选项。
 *
 * key 用 [PasswordVisualTransformation] 遮掉，避免别人瞥一眼屏幕就看到。
 */
@Composable
fun AiSettingsCard(
    settings: SettingsRepo,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val mode by settings.aiMode.collectAsState(initial = AiMode.Off)
    val cloudBase by settings.aiCloudBaseUrl.collectAsState(initial = SettingsRepo.DEFAULT_AI_CLOUD_BASE_URL)
    val cloudModel by settings.aiCloudModel.collectAsState(initial = SettingsRepo.DEFAULT_AI_CLOUD_MODEL)
    val cloudKey by settings.aiCloudKey.collectAsState(initial = "")
    val localBase by settings.aiLocalBaseUrl.collectAsState(initial = SettingsRepo.DEFAULT_AI_LOCAL_BASE_URL)
    val localModel by settings.aiLocalModel.collectAsState(initial = SettingsRepo.DEFAULT_AI_LOCAL_MODEL)
    val timeout by settings.aiTimeoutSeconds.collectAsState(initial = SettingsRepo.DEFAULT_AI_TIMEOUT_SECONDS)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "AI（任务拆解 / 估时 / 整理念头）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ExtColors.TextPrimary,
                    )
                    Text(
                        "当前：" + mode.label,
                        fontSize = 11.sp,
                        color = ExtColors.TextDim,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = ExtColors.TextSecondary,
                )
            }

            // 折叠时不渲染下面的内容。
            // 注意不能写 `return@Card` —— Composable lambda 里提前返回会破坏
            // Compose 的重组位置对应关系，必须用 if 包住。
            if (expanded) {
                AiSettingsBody(
                    settings = settings,
                    scope = scope,
                    toast = toast,
                    mode = mode,
                    cloudBase = cloudBase,
                    cloudModel = cloudModel,
                    cloudKey = cloudKey,
                    localBase = localBase,
                    localModel = localModel,
                    timeout = timeout,
                )
            }
        }
    }
}

@Composable
private fun AiSettingsBody(
    settings: SettingsRepo,
    scope: CoroutineScope,
    toast: (String) -> Unit,
    mode: AiMode,
    cloudBase: String,
    cloudModel: String,
    cloudKey: String,
    localBase: String,
    localModel: String,
    timeout: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "模式",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = ExtColors.TextSecondary,
        )
        AiMode.entries.forEach { m ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            settings.setAiMode(m)
                            toast("AI 模式：" + m.label)
                        }
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RadioButton(
                    selected = mode == m,
                    onClick = {
                        scope.launch {
                            settings.setAiMode(m)
                            toast("AI 模式：" + m.label)
                        }
                    },
                )
                Text(m.label, fontSize = 13.sp, color = ExtColors.TextPrimary)
            }
        }

        if (mode == AiMode.CloudFirst) {
            Text(
                "云端端点（OpenAI 兼容）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.TextSecondary,
            )
            AiSettingField(
                value = cloudBase,
                label = "Base URL",
                onCommit = { scope.launch { settings.setAiCloudBaseUrl(it) } },
            )
            AiSettingField(
                value = cloudModel,
                label = "模型",
                onCommit = { scope.launch { settings.setAiCloudModel(it) } },
            )
            AiSettingField(
                value = cloudKey,
                label = "API key",
                password = true,
                onCommit = { scope.launch { settings.setAiCloudKey(it) } },
            )
            Text(
                "key 只存在本机，不会写进导出备份，也不会出现在日志里。",
                fontSize = 11.sp,
                color = ExtColors.TextDim,
            )
        }

        if (mode != AiMode.Off) {
            Text(
                "本地 ollama",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.TextSecondary,
            )
            AiSettingField(
                value = localBase,
                label = "地址（如 http://100.x.x.x:19931）",
                onCommit = { scope.launch { settings.setAiLocalBaseUrl(it) } },
            )
            AiSettingField(
                value = localModel,
                label = "模型",
                onCommit = { scope.launch { settings.setAiLocalModel(it) } },
            )
            Text(
                "端口不是 11434：Windows 动态端口范围保留到 15000，11434 落在系统排除段里绑不上，" +
                    "所以本机 ollama 实际在 19931。手机需连上 Tailscale 才能访问。",
                fontSize = 11.sp,
                color = ExtColors.TextDim,
            )
            AiSettingField(
                value = timeout.toString(),
                label = "超时（秒）",
                onCommit = {
                    it.toIntOrNull()?.let { n ->
                        scope.launch { settings.setAiTimeoutSeconds(n) }
                    }
                },
            )
        }
    }
}

/**
 * 一个设置输入框。
 *
 * 用**失焦时提交**而不是每次按键都写 DataStore：
 * 每敲一个字就写一次磁盘，既浪费又可能在快速输入时覆盖乱序。
 */
@Composable
private fun AiSettingField(
    value: String,
    label: String,
    password: Boolean = false,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var shown by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (password && !shown) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        trailingIcon = if (password) {
            {
                Text(
                    if (shown) "隐藏" else "显示",
                    fontSize = 11.sp,
                    color = ExtColors.Primary,
                    modifier = Modifier
                        .clickable { shown = !shown }
                        .padding(8.dp),
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            // onFocusChanged 是标准 API：失焦时把值写进 DataStore。
            // 不逐字符写入的原因：每敲一个字写一次磁盘既浪费，
            // 快速输入时还容易出现写入顺序错乱。
            .onFocusChanged { st ->
                if (!st.isFocused) onCommit(text)
            },
    )
}
