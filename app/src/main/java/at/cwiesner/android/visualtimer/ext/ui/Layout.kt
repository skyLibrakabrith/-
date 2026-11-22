package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

/**
 * 横屏判定。
 *
 * 用 [LocalConfiguration]，配合 Manifest 里的 `configChanges`，
 * 旋转时**不重建 Activity** —— 界面状态（选中的面板、打开的对话框、
 * 正在编辑的草稿）都不会丢，只是重新排版。
 *
 * 计时类应用旋转是高频操作，重建一次会丢掉正在拖的时长草稿，
 * 体验上不可接受，所以选择自己处理配置变化。
 */
@Composable
fun isLandscape(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/** 横屏时加宽网格列数，让内容铺得开、单格不变形。 */
@Composable
fun adaptiveColumns(base: Int): Int {
    return if (isLandscape()) base + 1 else base
}
