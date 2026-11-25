package at.cwiesner.android.visualtimer.ext.llm

import android.util.Log
import at.cwiesner.android.visualtimer.ext.settings.AiMode
import kotlinx.coroutines.flow.first

/**
 * 把「云端优先、本地回退」这条策略收在一处。
 *
 * 为什么不让调用方自己挑后端：
 * - 三个入口（拆解 / 估时 / 整理）都要同样的回退语义，分散写必然写歪；
 * - 回退**必须只在上一次真的失败时发生**，不能"两个都试一遍再选"——
 *   那会把云端的一次成功也算成要花钱的请求（用户额度）。
 *
 * 失败信息处理原则：
 * - 云端失败 + 本地也失败 → 返回一句**合并**的话，明确告诉用户两边各为什么失败。
 *   只回一句"失败了"，用户没法判断是 key 错了还是没连 Tailscale。
 * - 只有云端失败但本地成功 → 直接用本地结果，**不打扰用户**（但要记日志）。
 */
class LlmRouter(
    private val client: LlmHttpClient = LlmHttpClient(),
) {

    /**
     * 发一次结构化请求。
     *
     * @param system 系统提示词
     * @param user 用户消息
     */
    suspend fun completeJson(
        config: LlmConfig,
        system: String,
        user: String,
    ): AiResult<String> {
        if (config.mode == AiMode.Off) {
            return AiResult.Failed("AI 功能未开启，请在「设置 → AI」里选择模式")
        }

        val timeout = config.timeoutSeconds * 1000

        // ---- 仅本地 ----
        if (config.mode == AiMode.LocalOnly) {
            return callLocal(config, system, user, timeout)
        }

        // ---- 云端优先 ----
        if (config.cloudKey.isBlank()) {
            // 没填 key 就别白跑一次请求，直接走本地，并让用户知道
            Log.i(TAG, "云端未配置 key，直接走本地")
            val local = callLocal(config, system, user, timeout)
            return when (local) {
                is AiResult.Ok -> local
                is AiResult.Failed -> AiResult.Failed(
                    "云端未填 API key，本地也失败：" + local.message,
                    local.cause,
                )
            }
        }

        when (val cloud = callCloud(config, system, user, timeout)) {
            is AiResult.Ok -> return cloud
            is AiResult.Failed -> {
                Log.w(TAG, "云端失败，回退本地：" + cloud.message)
                return when (val local = callLocal(config, system, user, timeout)) {
                    is AiResult.Ok -> local
                    is AiResult.Failed -> AiResult.Failed(
                        "云端和本地都没成功 —— 云端：" + cloud.message + "；本地：" + local.message,
                        local.cause ?: cloud.cause,
                    )
                }
            }
        }
    }

    private suspend fun callCloud(
        config: LlmConfig,
        system: String,
        user: String,
        timeout: Int,
    ): AiResult<String> {
        val url = LlmHttpClient.joinUrl(config.cloudBaseUrl, "chat/completions")
        val body = LlmHttpClient.openAiBody(config.cloudModel, system, user)
        val headers = mapOf("Authorization" to "Bearer " + config.cloudKey)
        return client.postForContent(url, headers, body, timeout)
    }

    private suspend fun callLocal(
        config: LlmConfig,
        system: String,
        user: String,
        timeout: Int,
    ): AiResult<String> {
        val url = LlmHttpClient.joinUrl(config.localBaseUrl, "api/chat")
        val body = LlmHttpClient.ollamaBody(config.localModel, system, user)
        return client.postForContent(url, emptyMap(), body, timeout)
    }

    companion object {
        private const val TAG = "LlmRouter"

        /** 从 DataStore 读当前配置快照。 */
        suspend fun loadConfig(settings: at.cwiesner.android.visualtimer.ext.settings.SettingsRepo): LlmConfig =
            LlmConfig(
                mode = settings.aiMode.first(),
                cloudBaseUrl = settings.aiCloudBaseUrl.first(),
                cloudModel = settings.aiCloudModel.first(),
                cloudKey = settings.aiCloudKey.first(),
                localBaseUrl = settings.aiLocalBaseUrl.first(),
                localModel = settings.aiLocalModel.first(),
                timeoutSeconds = settings.aiTimeoutSeconds.first(),
            )
    }
}

/** 一次调用所需的全部配置。做成值对象，方便在读完后不再碰 DataStore。 */
data class LlmConfig(
    val mode: AiMode,
    val cloudBaseUrl: String,
    val cloudModel: String,
    val cloudKey: String,
    val localBaseUrl: String,
    val localModel: String,
    val timeoutSeconds: Int,
)
