package com.kith.app.ai

import com.kith.app.data.catalog.ImageApiStyle
import com.kith.app.data.catalog.ImageModelPresets
import com.kith.app.data.store.SocietyStore
import com.kith.app.domain.AiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 生成结果：本地落盘路径 + 原始远程地址。 */
data class GeneratedImage(
    val localPath: String,
    val remoteUrl: String = "",
) {
    /** 送给模型/渲染统一用本地路径，避免图床链接过期。 */
    fun best(): String = localPath.ifEmpty { remoteUrl }
}

/**
 * 文生图客户端。
 *
 * 生图这块没法像对话那样统一 —— 各家接口形态差异太大，因此按
 * [ImageApiStyle] 分三条实现路径：
 *
 *  - `OPENAI`：标准 `/images/generations`，覆盖 OpenAI、硅基流动、Google 兼容层、
 *    Recraft 等大多数；
 *  - `ZHIPU`：同为 `/images/generations`，但参数更窄，且不认 `response_format`；
 *  - `DASHSCOPE_ASYNC`：通义万相是**异步任务**模型，先提交拿 task_id 再轮询，
 *    单次调用要几秒到几十秒。
 *
 * 生成结果统一下载到社会自己的 `media/` 目录，这样：
 *  1. 导出社会时图片一起打包，不会因为图床链接失效而丢图；
 *  2. 纯本地路径可以直接转 base64 喂给视觉模型。
 */
class ImageGenClient(private val store: SocietyStore) {

    /**
     * 生成图片。
     *
     * @param societyId 用于决定图片落盘的目录
     * @param endpoint 用户配置的接入点（含 baseUrl 与 key）
     * @param modelId 生图模型 id
     * @param prompt 提示词
     * @param ratio 比例，如 "3:4"
     */
    suspend fun generate(
        societyId: String,
        endpoint: AiEndpoint,
        modelId: String,
        prompt: String,
        ratio: String = "1:1",
        style: String = "",
    ): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        runCatching {
            require(prompt.isNotBlank()) { "提示词为空" }
            require(endpoint.apiKey.isNotBlank()) { "没有配置生图模型的 API Key" }

            val preset = ImageModelPresets.byModelId(modelId)
            val apiStyle = preset?.style ?: ImageApiStyle.OPENAI
            val base = (preset?.baseUrl?.takeIf { it.isNotEmpty() } ?: endpoint.baseUrl)
                .trim()
                .trimEnd('/')

            val fullPrompt = buildPrompt(prompt, style)

            val remote = when (apiStyle) {
                ImageApiStyle.OPENAI -> callOpenAi(base, endpoint, modelId, fullPrompt, ratio, preset?.returnsBase64 == true)
                ImageApiStyle.ZHIPU -> callZhipu(base, endpoint, modelId, fullPrompt)
                ImageApiStyle.DASHSCOPE_ASYNC -> callDashScopeAsync(base, endpoint, modelId, fullPrompt, ratio)
                ImageApiStyle.VENDOR_NATIVE -> error(
                    "「$modelId」用的是厂商私有接口，Kith 暂未内置适配。" +
                        "可以在设置里换用 OpenAI 兼容的生图模型（如硅基流动 FLUX、智谱 CogView）。",
                )
            }

            val local = persist(societyId, remote)
            GeneratedImage(localPath = local, remoteUrl = if (remote.startsWith("http")) remote else "")
        }
    }

    private fun buildPrompt(prompt: String, style: String): String =
        if (style.isBlank()) prompt else "$prompt，$style"

    // ── OpenAI 兼容 ─────────────────────────────────────────────────────────

    private suspend fun callOpenAi(
        base: String,
        endpoint: AiEndpoint,
        modelId: String,
        prompt: String,
        ratio: String,
        returnsBase64: Boolean,
    ): String {
        val body = JSONObject()
            .put("model", modelId)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", ImageModelPresets.sizeFor(ratio))
        if (!returnsBase64) body.put("response_format", "url")

        val resp = Http.postJson(
            "$base/images/generations",
            body,
            mapOf("Authorization" to "Bearer ${endpoint.apiKey.trim()}"),
        )
        if (!resp.ok) error(httpError(resp.code, resp.body))

        val data = JSONObject(resp.body).optJSONArray("data")
            ?: error("生图返回格式异常：${resp.body.take(200)}")
        val first = data.optJSONObject(0) ?: error("生图没有返回任何图片")

        first.optString("url").takeIf { it.isNotEmpty() }?.let { return it }
        first.optString("b64_json").takeIf { it.isNotEmpty() }?.let {
            return writeBase64(it)
        }
        error("生图返回里既没有 url 也没有 b64_json")
    }

    // ── 智谱 ────────────────────────────────────────────────────────────────

    private suspend fun callZhipu(
        base: String,
        endpoint: AiEndpoint,
        modelId: String,
        prompt: String,
    ): String {
        val body = JSONObject()
            .put("model", modelId)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", "1024x1024")

        val resp = Http.postJson(
            "$base/images/generations",
            body,
            mapOf("Authorization" to "Bearer ${endpoint.apiKey.trim()}"),
        )
        if (!resp.ok) error(httpError(resp.code, resp.body))
        return JSONObject(resp.body).optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotEmpty() }
            ?: error("智谱生图没有返回图片地址")
    }

    // ── 通义万相（异步任务）──────────────────────────────────────────────────

    private suspend fun callDashScopeAsync(
        base: String,
        endpoint: AiEndpoint,
        modelId: String,
        prompt: String,
        ratio: String,
    ): String {
        val auth = mapOf("Authorization" to "Bearer ${endpoint.apiKey.trim()}")
        // 万相用 `1024*1024` 这种星号写法，和 OpenAI 的 `1024x1024` 不同
        val size = ImageModelPresets.sizeFor(ratio).replace("x", "*")

        val submitBody = JSONObject()
            .put("model", modelId)
            .put("input", JSONObject().put("prompt", prompt))
            .put(
                "parameters",
                JSONObject().put("size", size).put("n", 1),
            )

        val submit = Http.postJson(
            "$base/services/aigc/text2image/image-synthesis",
            submitBody,
            auth + mapOf("X-DashScope-Async" to "enable"),
        )
        if (!submit.ok) error(httpError(submit.code, submit.body))

        val taskId = JSONObject(submit.body)
            .optJSONObject("output")
            ?.optString("task_id")
            ?.takeIf { it.isNotEmpty() }
            ?: error("万相没有返回 task_id：${submit.body.take(200)}")

        // 轮询。万相通常 5-20 秒完成，给 90 秒上限。
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val poll = Http.getText("$base/tasks/$taskId", auth, quick = false)
            if (!poll.ok) continue
            val out = JSONObject(poll.body).optJSONObject("output") ?: continue
            when (out.optString("task_status")) {
                "SUCCEEDED" -> {
                    val results = out.optJSONArray("results")
                    val url = results?.optJSONObject(0)?.optString("url").orEmpty()
                    if (url.isNotEmpty()) return url
                    error("万相任务成功但没有返回图片地址")
                }
                "FAILED", "CANCELED" -> error(
                    "万相生成失败：${out.optString("message").ifEmpty { out.optString("code") }}",
                )
                // PENDING / RUNNING 继续等
            }
        }
        error("万相生成超时（超过 90 秒）")
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    /** base64 图片先落到临时文件再走统一下载流程。 */
    private fun writeBase64(b64: String): String {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val f = java.io.File(store.mediaDir("_tmp"), "gen_${System.currentTimeMillis()}.png")
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** 把图片保存到社会目录。已是本地文件则直接搬过来。 */
    private fun persist(societyId: String, src: String): String {
        if (!src.startsWith("http")) {
            val f = java.io.File(src)
            if (!f.exists()) return src
            val target = store.saveMedia(societyId, f.readBytes(), f.extension.ifEmpty { "png" })
            f.delete()
            return target.absolutePath
        }
        return try {
            val request = okhttp3.Request.Builder().url(src).get().build()
            Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return src
                    val bytes = resp.body?.bytes() ?: return src
                    // 有些厂商返回 webp/jpeg，按魔数判断扩展名，别一律当 png 存
                    val ext = sniffExt(bytes)
                    store.saveMedia(societyId, bytes, ext).absolutePath
                }
        } catch (_: Throwable) {
            // 下载失败就退回远程地址 —— 至少还能显示，比彻底失败好
            src
        }
    }

    private fun sniffExt(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 12 && String(bytes, 0, 4) == "RIFF" -> "webp"
        else -> "png"
    }

    private fun httpError(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().ifEmpty { raw.take(200) }
        val head = when (code) {
            401 -> "生图密钥无效（401）"
            402, 429 -> "生图额度不足或请求过频（$code）"
            404 -> "生图模型不存在（404），确认模型名与接入地址"
            else -> "生图请求失败（$code）"
        }
        return if (detail.isEmpty()) head else "$head：$detail"
    }
}
