package at.cwiesner.android.visualtimer.ext.domain

/**
 * 什么时候该让屏幕保持常亮。
 *
 * ## 为什么抽成纯函数
 *
 * 原来 `MainActivity` 直接订阅 `settings.keepScreenOn`，开关一开就**一直亮**，
 * 不看当前有没有在计时。用户要的是「**计时时**保持常亮」——
 * 在面板页翻设置、看归档时也让屏幕永不熄灭，纯属费电。
 *
 * 判定要考虑三个来源（计时器运行时、序列相位、用户开关），
 * 塞在 `MainActivity` 的协程里既测不了也容易改歪，所以抽出来。
 *
 * @param enabled 用户在设置里的开关
 * @param anyTimerRunning 是否有计时器正在跑
 * @param sequencePhase 序列引擎当前相位
 */
fun shouldKeepScreenOn(
    enabled: Boolean,
    anyTimerRunning: Boolean,
    sequencePhase: StepSequenceEngine.Phase,
): Boolean {
    if (!enabled) return false
    if (anyTimerRunning) return true
    // 序列：正在倒计时、或阶段已结束在等用户点「下一阶段」——
    // 两种都是"这次计时还没结束"，用户随时会回来操作，屏幕不该熄。
    //
    // 暂停态也算：用户按下暂停只是先停一下，过几秒就回来。
    return sequencePhase is StepSequenceEngine.Phase.Running ||
        sequencePhase is StepSequenceEngine.Phase.AwaitingConfirm
}
