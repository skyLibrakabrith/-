package at.cwiesner.android.visualtimer.ext.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM 调用客户端。
 *
 * **刻意只用 `HttpURLConnection` + 已有的 kotlinx.serialization，不引入 OkHttp** ——
 * 这个项目的依赖一直是刻意精简的（见 AGENTS.md「Conventions」）。
 * 我们只需要发一个 POST 收一段 JSON，`HttpURLConnection` 完全够用。
 *
 * 两个实现都是 OpenAI 兼容形状的近亲：
 * - 云端走 `POST {base}/chat/completions`，`Authorization: Bearer <key>`
 * - 本地 ollama 走 `POST {base}/api/chat`，不需要 key，多一个 `format:"json"`
 */
class LlmHttpClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    /**
     * 发一次 chat 请求，返回模型输出的**文本内容**。
     *
     * @param url 完整 URL
     * @param headers 额外请求头（云端用来带 Authorization）
     * @param body 请求体
     * @param timeoutMillis 连接 + 读取超时
     */
    suspend fun postForContent(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
        timeoutMillis: Int,
    ): AiResult<String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code from $url: ${raw.take(300)}")
                return@withContext AiResult.Failed(describeHttpError(code, raw))
            }

            val content = extractContent(raw)
            if (content.isNullOrBlank()) {
                Log.w(TAG, "空回复 from $url: ${raw.take(300)}")
                return@withContext AiResult.Failed("模型没有返回内容，请重试")
            }
            AiResult.Ok(content)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "超时 $url", e)
            AiResult.Failed("请求超时（${timeoutMillis / 1000}s），检查网络或换更快的后端", e)
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "域名解析失败 $url", e)
            AiResult.Failed("连不上服务器，检查地址是否正确", e)
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "连接被拒绝 $url", e)
            AiResult.Failed("连不上服务器，本地 ollama 是否在运行？Tailscale 是否已连接？", e)
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "TLS 失败 $url", e)
            AiResult.Failed("TLS 握手失败，检查地址与证书", e)
        } catch (e: Exception) {
            Log.e(TAG, "请求失败 $url", e)
            AiResult.Failed("请求失败：" + (e.message ?: e.javaClass.simpleName), e)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 从响应里取文本，两种形状都认：
     * - OpenAI 兼容：`choices[0].message.content`
     * - ollama：`message.content`
     *
     * 这一层是为了"同一个解析逻辑能喂给两个后端"，省得调用方关心是谁回的。
     */
    private fun extractContent(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?: root["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            // 少数兼容端点把结果放在 response 字段
            ?: root["response"]?.jsonPrimitive?.content
    }.getOrNull()

    /** 把 HTTP 错误翻成一句人话。**不要把 raw body 整段塞给用户**，里面可能有服务端细节。 */
    private fun describeHttpError(code: Int, raw: String): String {
        val hint = runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            val e = o["error"]
            when {
                e is JsonObject -> e["message"]?.jsonPrimitive?.content
                e is JsonPrimitive -> e.content
                else -> null
            }
        }.getOrNull()
        return when (code) {
            401, 403 -> "鉴权失败（$code），检查 API key" + (hint?.let { "：$it" } ?: "")
            404 -> "端点不存在（404），检查 base URL / 模型名" + (hint?.let { "：$it" } ?: "")
            429 -> "被限流（429），稍后再试"
            in 500..599 -> "服务端错误（$code），稍后再试"
            else -> "请求失败（$code）" + (hint?.let { "：$it" } ?: "")
        }
    }

    companion object {
        private const val TAG = "LlmHttpClient"

        /** 构造 OpenAI 兼容的请求体。 */
        fun openAiBody(model: String, system: String, user: String): JsonObject = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        },
                    )
                },
            )
        }

        /**
         * 构造 ollama 的请求体。
         *
         * 两个关键参数：
         * - `format = "json"`：强制结构化输出。不设的话模型经常在 JSON 前后加
         *   "好的，以下是..." 这种解释文字，解析直接失败。
         * - `think = false`：qwen3.5 带 thinking。思考 token 会挤占短 JSON 输出的预算，
         *   而且思考内容会干扰 `format=json`。分类/结构化任务一律关掉。
         */
        fun ollamaBody(model: String, system: String, user: String): JsonObject = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("format", "json")
            put("think", false)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        },
                    )
                },
            )
            put(
                "options",
                buildJsonObject {
                    put("temperature", 0.2)
                    // 上下文压小一点：4B 模型在手机/小显存场景下更稳，也让响应更快
                    put("num_ctx", 4096)
                },
            )
        }

        /** 拼 URL，容忍用户把 base 结尾多打/少打一个斜杠。 */
        fun joinUrl(base: String, path: String): String =
            base.trimEnd('/') + "/" + path.trimStart('/')
    }
}
