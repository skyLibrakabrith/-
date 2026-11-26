package at.cwiesner.android.visualtimer.ext.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 通用「确认删除」对话框。
 *
 * 为什么单独抽出来：序列、面板、组这几处删除都要**先问一句再删** ——
 * 删掉的是一整条记录（序列还连带它的全部阶段），点错的代价远高于点一下"取消"。
 * 各处自己拼一遍 AlertDialog 很容易漏掉「删除文字用红色」这类一致性细节，
 * 所以统一成一个组件；调用方只负责传文案和自己那两处副作用。
 *
 * 看起来有点小题大做，但这个教训是有来历的：此前 SequenceDao.deleteById
 * 已在 DAO 里存在，UI 却从来没有接上入口，用户只能以为「序列不能删」。
 * 把删除流程收敛到一个组件，就不容易再出现"某个列表忘了接删除"。
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    text: String,
    confirmText: String = "删除",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        // 危险操作用红色文字，避免和「确定保存」这种常规主按钮混在一起
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = ExtColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
