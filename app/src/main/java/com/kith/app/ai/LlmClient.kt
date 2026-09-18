package com.kith.app.ai

import com.kith.app.core.i
import com.kith.app.core.obj
import com.kith.app.domain.AiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** 发往模型的一条消息。images 里是 data URI 或公网 URL。 */
data class ChatTurn(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
) {
    companion object {
        fun system(text: String) = ChatTurn("system", text)
        fun user(text: String, images: List<String> = emptyList()) = ChatTurn("user", text, images)
        fun assistant(text: String) = ChatTurn("assistant", text)
    }
}

sealed interface LlmEvent {
    data object Started : LlmEvent

    /** 增量文本。 */
    data class Delta(val text: String) : LlmEvent

    /** 拿到了真实用量；流式下有些厂商不返回，由上层估算补齐。 */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : LlmEvent

    data class Finished(val reason: String) : LlmEvent
}

data class LlmResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val modelId: String,
    /** 非空表示失败，内容已转成给用户看的中文提示 */
    val error: String = "",
    val estimated: Boolean = false,
) {
    val ok: Boolean get() = error.isEmpty()
}

/** 鉴权方式。各家的头不一样，这里显式建模而不是靠 if-else 散落各处。 */
enum class AuthStyle {
    /** `Authorization: Bearer xxx`，绝大多数 OpenAI 兼容服务 */
    BEARER,

    /** `x-api-key: xxx`，Anthropic 原生兼容层 */
    X_API_KEY,

    /** `?key=xxx` 查询参数，Google 原生 */
    QUERY_KEY,
}

/**
 * OpenAI 兼容的对话客户端。
 *
 * 只实现 `/chat/completions` 这一条路径 —— 因为本项目要支持几十家厂商，
 * 唯有 OpenAI 兼容协议是最大公约数（国内 DeepSeek / 智谱 / 通义 / 月之暗面 /
 * 豆包方舟，国外 OpenAI / Groq / Together / OpenRouter / xAI 全部兼容）。
 * 少量非兼容服务（Stability、BFL 等）只用于生图，走 ImageGenClient 的适配分支。
 */
class LlmClient(
    /** 内置目录里全部模型 id 及其剥前缀裸名 —— 这些是实测维护过的，防呆不拦。 */
    private val catalogNames: suspend () -> Set<String> = { emptySet() },
) {

    /** 已拉取的接入点模型名缓存（endpointId → 裸名列表）。发送前校验用。 */
    private val endpointModelsCache = HashMap<String, List<String>>()

    /**
     * 发送前的模型名防呆校验。
     *
     * 若已知道接入点真实支持的模型列表（GET /models 拉取成功过），而当前
     * 模型名不在其中 —— 直接挡下并给出**可操作的**错误信息，而不是让用户
     * 面对服务端那句生硬的 400。列表未知时不拦（交给服务端判）。
     */
    private suspend fun rejectIfModelUnknown(
        endpoint: AiEndpoint,
        wireId: String,
    ): String? {
        // 目录模型一律放行：内置目录本身按「接入点实测/官方文档」维护
        // （例如 DeepSeek 官方对 deepseek-v4-flash 保留别名兼容，但 /models
        // 只列规范名 —— 若在这里硬校验会误杀实测可用的目录模型）。
        if (wireId in catalogNames()) return null
        val cached = endpointModelsCache[endpoint.id]
        val known = cached ?: run {
            // 首次遇到该接入点：尝试拉一次真实列表（失败则放行，不打扰）
            val fetched = runCatching {
                withTimeout(8000) { fetchEndpointModels(endpoint).getOrNull() }
            }.getOrNull()
            fetched?.let { endpointModelsCache[endpoint.id] = it }
            fetched
        }
        if (known.isNullOrEmpty() || wireId in known) return null
        return "模型「$wireId」不被该接入点支持。它只支持：${known.joinToString("、")}。" +
            "请到模型选择页最顶上「此接入点支持的模型」区重新选择。"
    }

    /** 非流式补全。旁白、重要性判断、视觉描述这类「要完整结果」的调用走这里。 */
    suspend fun complete(
        endpoint: AiEndpoint,
        modelId: String,
        turns: List<ChatTurn>,
        temperature: Double = 0.85,
        maxTokens: Int = 2048,
        jsonMode: Boolean = false,
        /** null = 不传参数（服务端默认）；DeepSeek V4 系默认开思考，JSON 场景自动关闭 */
        thinkingEnabled: Boolean? = null,
    ): LlmResult {
        val wireId = wireModelId(endpoint, modelId)
        rejectIfModelUnknown(endpoint, wireId)?.let {
            return LlmResult("", 0, 0, wireId, it)
        }
        val think = thinkingEnabled ?: if (jsonMode) false else null
        val body = buildBody(
            wireId, turns, temperature, maxTokens,
            stream = false, jsonMode = jsonMode, thinkingEnabled = think,
        )
        val url = chatUrl(endpoint)
        return try {
            val resp = Http.postJson(url, body, authHeaders(endpoint, modelId))
            parseCompletion(resp.code, resp.body, wireId)
        } catch (e: Throwable) {
            LlmResult("", 0, 0, wireId, friendlyError(e))
        }
    }

    /**
     * 流式补全。
     *
     * 刻意**不发送 `stream_options.include_usage`**：部分厂商（如通义兼容模式）
     * 会因为这个不认识的字段直接返回 400。改为「有就用、没有就由上层估算」——
     * 主流厂商（DeepSeek、OpenAI、智谱）本来就会在最后一个 chunk 里带上 usage。
     */
    fun streamChat(
        endpoint: AiEndpoint,
        modelId: String,
        turns: List<ChatTurn>,
        temperature: Double = 0.9,
        maxTokens: Int = 2048,
    ): Flow<LlmEvent> = flow {
        val wireId = wireModelId(endpoint, modelId)
        rejectIfModelUnknown(endpoint, wireId)?.let { msg ->
            throw IllegalStateException(msg)
        }
        val body = buildBody(wireId, turns, temperature, maxTokens, stream = true)
        val url = chatUrl(endpoint)
        emit(LlmEvent.Started)

        var finish = ""
        Http.streamSse(url, body, authHeaders(endpoint, modelId)).collect { payload ->
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return@collect

            // 有些厂商把错误塞在流里（HTTP 仍是 200），这里主动抛出去
            chunk.optJSONObject("error")?.let { err ->
                throw IllegalStateException(
                    err.optString("message").ifEmpty { "模型在流中返回了错误" },
                )
            }

            chunk.optJSONObject("usage")?.let { u ->
                val inTok = u.optInt("prompt_tokens", u.optInt("input_tokens", 0))
                val outTok = u.optInt("completion_tokens", u.optInt("output_tokens", 0))
                if (inTok > 0 || outTok > 0) emit(LlmEvent.Usage(inTok, outTok))
            }

            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@collect
            choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let {
                finish = it
            }
            val delta = choice.optJSONObject("delta") ?: return@collect
            // 思维链模型会先吐 reasoning_content，这里刻意丢弃，不显示给用户。
            //
            // 注意：content 在思维链阶段是 JSON null。不能直接用 optString ——
            // 平台实现会把它转成字面 "null" 字符串透传（deepseek-v4-pro 实测
            // 每个 chunk 拼一个 null，整条回复变成 nullnullnull…正文）。
            // 这里显式做类型判断：只有 String 才是正文，JSON null 一律跳过。
            val rawContent = delta.opt("content")
            val piece = (rawContent as? String)?.takeIf { it != "null" } ?: ""
            if (piece.isNotEmpty()) emit(LlmEvent.Delta(piece))
        }
        emit(LlmEvent.Finished(finish.ifEmpty { "stop" }))
    }

    // ── 请求构造 ────────────────────────────────────────────────────────────

    private fun chatUrl(endpoint: AiEndpoint): String {
        val base = endpoint.baseUrl.trim().trimEnd('/')
        // 允许用户直接粘贴完整的 endpoint 地址，避免重复拼接
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /**
     * 把目录里的模型 id 适配成该接入点真正认识的名字。
     *
     * 内置目录的 id 是 OpenRouter 命名空间的 `vendor/model`（如
     * `deepseek/deepseek-v4-flash-0731`）。**只有 OpenRouter 接入点认这种全名**，
     * 其它厂商（DeepSeek 官方、智谱、通义…）只认裸名，收到带前缀的 id 会直接
     * 400：「The supported API model names are …, but you passed deepseek/xxx」。
     * 所以发请求前统一剥前缀 —— 目录 id 照存不动（价格等信息仍按目录算）。
     */
    private fun wireModelId(endpoint: AiEndpoint, modelId: String): String {
        val vendor = endpoint.vendor.trim().lowercase()
        if (vendor == "openrouter") return modelId
        val slash = modelId.indexOf('/')
        return if (slash > 0) modelId.substring(slash + 1) else modelId
    }

    /**
     * 拉取一个接入点真实支持的模型名列表（OpenAI 兼容的 GET /models）。
     *
     * 用于选模型页置顶展示「此接入点支持的模型」—— 内置目录是 OpenRouter 语料，
     * 模型名与任意第三方接入点大概率对不上，真实列表才是能直接用的。
     * 不支持 /models 的服务会失败，调用方静默隐藏该区域即可。
     */
    suspend fun fetchEndpointModels(endpoint: AiEndpoint): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = endpoint.baseUrl.trim().trimEnd('/')
                // 用户可能粘的是完整 chat/completions 地址，倒推服务根
                val root = if (base.endsWith("/chat/completions")) {
                    base.removeSuffix("/chat/completions")
                } else base
                val url = "$root/models"
                val resp = Http.getText(url, authHeaders(endpoint, ""))
                if (resp.code != 200) error("HTTP ${resp.code}")
                val data = JSONObject(resp.body).optJSONArray("data")
                    ?: error("响应里没有 models 列表")
                buildList {
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotEmpty()) add(id)
                    }
                }.sorted()
            }
        }

    private fun buildBody(
        modelId: String,
        turns: List<ChatTurn>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        jsonMode: Boolean = false,
        thinkingEnabled: Boolean? = null,
    ): JSONObject {
        val messages = JSONArray()
        turns.forEach { t ->
            val msg = JSONObject().put("role", t.role)
            if (t.images.isEmpty()) {
                msg.put("content", t.content)
            } else {
                val parts = JSONArray()
                if (t.content.isNotEmpty()) {
                    parts.put(JSONObject().put("type", "text").put("text", t.content))
                }
                t.images.forEach { img ->
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", normalizeImage(img))),
                    )
                }
                msg.put("content", parts)
            }
            messages.put(msg)
        }

        return JSONObject()
            .put("model", modelId)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", stream)
            .apply {
                if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
                // DeepSeek V4 系思维模型默认开思考（正文延迟、token 贵一截），
                // 且非流式思考阶段正文为空。需要直接拿正文的场景显式关闭。
                // 文档：api-docs.deepseek.com/zh-cn/guides/thinking_mode
                if (thinkingEnabled != null) {
                    put(
                        "thinking",
                        JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"),
                    )
                }
            }
    }

    /** 本地文件路径要转成 data URI 才能发给远端模型。 */
    private fun normalizeImage(input: String): String {
        if (input.startsWith("http") || input.startsWith("data:")) return input
        val f = java.io.File(input)
        if (!f.exists()) return input
        val bytes = f.readBytes()
        val mime = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun authHeaders(endpoint: AiEndpoint, modelId: String): Map<String, String> {
        val key = endpoint.apiKey.trim()
        if (key.isEmpty()) return emptyMap()
        return when (authStyleOf(endpoint)) {
            AuthStyle.BEARER -> mapOf("Authorization" to "Bearer $key")
            AuthStyle.X_API_KEY -> mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
            )
            AuthStyle.QUERY_KEY -> mapOf("x-goog-api-key" to key)
        }
    }

    private fun authStyleOf(endpoint: AiEndpoint): AuthStyle = when (endpoint.vendor) {
        "Anthropic" -> AuthStyle.X_API_KEY
        else -> AuthStyle.BEARER
    }

    // ── 响应解析 ────────────────────────────────────────────────────────────

    private fun parseCompletion(code: Int, raw: String, modelId: String): LlmResult {
        if (code !in 200..299) {
            return LlmResult("", 0, 0, modelId, httpErrorMessage(code, raw))
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return LlmResult("", 0, 0, modelId, "返回内容不是合法 JSON：${raw.take(200)}")

        root.optJSONObject("error")?.let {
            return LlmResult("", 0, 0, modelId, it.optString("message", "模型返回错误"))
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        // 显式类型判断（原因同 streamChat）：思维模型的 message.content 在
        // 只有思考内容时是 JSON null，optString 会把它变成字面 "null" 透传。
        val text = (message?.opt("content") as? String).orEmpty()
        val usage = root.obj("usage")
        val inTok = usage.i("prompt_tokens", usage.i("input_tokens", 0))
        val outTok = usage.i("completion_tokens", usage.i("output_tokens", 0))

        // 渠道故障时有些中转会把字面 "null" 当正文吐回来 —— 按错误处理，
        // 绝不能让 "null" 落进对话历史（历史一旦被污染，模型会跟着学样继续输出 null）
        if (text.trim().equals("null", ignoreCase = true)) {
            return LlmResult("", 0, 0, modelId, "模型返回了空内容（null），通常是该渠道故障：请换一个模型或稍后重试")
        }
        if (text.isEmpty() && choice == null) {
            return LlmResult("", 0, 0, modelId, "模型没有返回任何内容")
        }
        if (text.isEmpty() && message?.has("reasoning_content") == true) {
            return LlmResult("", 0, 0, modelId, "该思维模型只返回了思考过程、没有正文内容，请换非思维模型或调大 max_tokens")
        }
        return LlmResult(text, inTok, outTok, modelId)
    }

    // ── 错误信息 ────────────────────────────────────────────────────────────

    private fun httpErrorMessage(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().ifEmpty { raw.take(200) }

        val head = when (code) {
            400 -> "请求被拒绝（400）"
            401 -> "密钥无效或未填写（401）"
            402 -> "账户余额不足（402）"
            403 -> "没有访问该模型的权限（403）"
            404 -> "模型不存在或地址不对（404）"
            422 -> "参数不合法（422）"
            429 -> "请求过于频繁，稍后再试（429）"
            in 500..599 -> "服务端错误（$code），通常是厂商临时故障"
            else -> "请求失败（$code）"
        }
        return if (detail.isEmpty()) head else "$head：$detail"
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is Http.HttpException -> when (val f = e.failure) {
            is Http.Failure.Http -> httpErrorMessage(f.code, f.body)
            is Http.Failure.Network -> "网络不通：${f.message}"
        }
        is java.net.UnknownHostException -> "无法解析域名，检查网络或接入地址"
        is java.net.SocketTimeoutException -> "请求超时，可能是上下文太长或厂商响应慢"
        is javax.net.ssl.SSLException -> "HTTPS 握手失败，检查接入地址是否正确"
        else -> e.message ?: e.javaClass.simpleName
    }
}
