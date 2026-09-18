package com.kith.app.ai

import android.util.Base64
import com.kith.app.data.settings.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 视觉桥接。
 *
 * 移植自 WANAIChat 的 `VisionMcpService`（智谱 GLM-4V 桥接层）。
 *
 * ## 为什么需要它
 * 项目里 371 个模型中有 192 个支持读图，剩下 148 个是纯文本模型 ——
 * 包括很常用的默认选项。用户往聊天里发一张图，纯文本模型是看不见的，
 * 会答非所问甚至报错。桥接层的作用就是：**纯文本模型 + 一次视觉转述 = 能看图**。
 *
 * ## 工作方式
 * ```
 * 用户发图
 *   → 角色的模型支持读图？ → 直接把图塞进多模态消息
 *   → 不支持？            → 先调 GLM-4V 拿到一段文字描述
 *                            → 把描述以「[用户发来一张图：…]」的形式注入对话
 * ```
 * 这样对用户是完全透明的：无论选哪个模型，发图都有人接得住。
 *
 * 默认使用智谱的 `glm-4v-flash`（免费额度），也可在设置里换成自己的 Key 或模型。
 */
class VisionBridge {

    /**
     * 描述一张图。
     *
     * @param image 本地文件路径、http(s) URL 或已是 data URI
     * @param question 想从图里读什么
     * @return 成功时返回描述文本；失败时 [Result.failure] 携带中文原因
     */
    suspend fun describe(
        settings: SettingsSnapshot,
        image: String,
        question: String = DEFAULT_QUESTION,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.visionEnabled) { "视觉桥接未启用" }
            val key = settings.visionApiKey.trim()
            require(key.isNotEmpty()) { "没有配置视觉模型的 API Key" }

            val url = settings.visionBaseUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject()
                .put("model", settings.visionModel.ifEmpty { SettingsSnapshot.DEFAULT_VISION_MODEL })
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", question))
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", normalize(image))),
                                ),
                        ),
                ))
                .put("temperature", 0.3)
                .put("max_tokens", 900)

            val resp = Http.postJson(
                url = url,
                body = body,
                headers = mapOf("Authorization" to "Bearer $key"),
            )

            if (!resp.ok) {
                error(friendlyHttp(resp.code, resp.body))
            }
            val root = JSONObject(resp.body)
            root.optJSONObject("error")?.let {
                error(it.optString("message").ifEmpty { "视觉模型返回错误" })
            }
            val text = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            require(text.isNotEmpty()) { "视觉模型没有返回内容" }
            text.trim()
        }
    }

    /**
     * 同时看两张图并做对比。
     * 用于「我这张自拍和 TA 的照片像不像」这类玩法。
     */
    suspend fun compare(
        settings: SettingsSnapshot,
        imageA: String,
        imageB: String,
        question: String = "分别描述这两张图，然后指出它们的相似与不同之处。",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.visionEnabled) { "视觉桥接未启用" }
            val key = settings.visionApiKey.trim()
            require(key.isNotEmpty()) { "没有配置视觉模型的 API Key" }

            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", question))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", normalize(imageA))),
                )
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", normalize(imageB))),
                )

            val url = settings.visionBaseUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject()
                .put("model", settings.visionModel.ifEmpty { SettingsSnapshot.DEFAULT_VISION_MODEL })
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .put("temperature", 0.3)
                .put("max_tokens", 1200)

            val resp = Http.postJson(url, body, mapOf("Authorization" to "Bearer $key"))
            if (!resp.ok) error(friendlyHttp(resp.code, resp.body))
            val text = JSONObject(resp.body).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            require(text.isNotEmpty()) { "视觉模型没有返回内容" }
            text.trim()
        }
    }

    /**
     * 生成注入到对话里的转述文本。
     * 明确告诉角色模型「这是别人转述给你的」，避免它以为自己在看图。
     */
    fun asInjection(description: String, senderName: String): String =
        "[$senderName 发来了一张图片。你看不到图本身，但有人帮你描述了它的内容：$description]" +
            " 请像真的看到了一样自然地回应，不要提及「描述」「转述」这类字眼。"

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 本地路径转 data URI；URL 原样返回。 */
    private fun normalize(input: String): String {
        if (input.startsWith("http") || input.startsWith("data:")) return input
        val f = File(input)
        require(f.exists()) { "找不到图片文件：$input" }
        // 智谱对单图有体积限制，超过 4MB 直接拒绝，这里先在本地挡掉并给出明确原因
        require(f.length() <= 4L * 1024 * 1024) {
            "图片超过 4MB，视觉模型无法处理，请压缩后重试"
        }
        val mime = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    private fun friendlyHttp(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().ifEmpty { raw.take(200) }
        val head = when (code) {
            401 -> "视觉模型密钥无效（401），可在设置里换成你自己的 Key"
            429 -> "视觉模型额度用尽或请求过频（429），可在设置里换成你自己的 Key"
            else -> "视觉模型调用失败（$code）"
        }
        return if (detail.isEmpty()) head else "$head：$detail"
    }

    companion object {
        const val DEFAULT_QUESTION =
            "请详细描述这张图片：画面主体、场景、人物外观与表情、色调氛围。" +
                "如果图中有文字，请原样读出。用中文回答，控制在 200 字以内。"

        /** 给聊天场景用的提问：更关注「对角色而言值得回应什么」。 */
        const val CHAT_QUESTION =
            "这张图里有什么？请描述主体、场景、人物外观情绪和氛围，以及任何文字。" +
                "如果看起来是自拍、照片或截图，说明它大致是什么类型的画面。用中文，150 字以内。"
    }
}
