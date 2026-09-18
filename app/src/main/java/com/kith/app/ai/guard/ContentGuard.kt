package com.kith.app.ai.guard

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 审核结论。 */
sealed interface GuardVerdict {
    /** 通过。 */
    data object Ok : GuardVerdict

    /** 未通过：给出命中的类别与模型原文。 */
    data class Unsafe(val categories: String, val raw: String) : GuardVerdict

    /** 审核本身没跑起来（模型缺失 / 格式不对 / 推理异常）。内容按通过处理，但调用方应提示。 */
    data class Error(val message: String) : GuardVerdict
}

/** 模型下载状态。 */
sealed interface GuardDownloadState {
    data object Idle : GuardDownloadState

    /** 下载中：单位 MB。total 为 -1 表示服务器没给总长。 */
    data class Downloading(val downloadedMB: Long, val totalMB: Long) : GuardDownloadState

    /** 下载完成。 */
    data class Done(val file: File, val sizeMB: Long) : GuardDownloadState

    data class Failed(val message: String) : GuardDownloadState
}

/**
 * 本地内容审核引擎（Qwen3Guard 0.6B）。
 *
 * 两级检查：
 * 1. [sanityCheck] —— 不需要模型就能跑的最低层次检查：空输出、过短、
 *    退化的重复内容。这是「旁白没有出错」的兜底。
 * 2. [modelCheck] —— Qwen3Guard 的安全分类（Safe / Unsafe + 类别），
 *    需要 [Context.filesDir]/models/guard/ 下有模型文件（.task/.bin）。
 *
 * 模型文件由用户在设置里导入（应用不内置模型包）。
 * 推理用 MediaPipe GenAI 的 LlmInference，AAR 自带预编译原生库。
 */
class ContentGuard(private val context: Context) {

    private val mutex = Mutex()

    /** 已加载的 MediaPipe 引擎与其对应文件路径（文件变了就重建）。 */
    private var engine: LlmInference? = null
    private var enginePath: String? = null

    // ── 模型文件管理 ────────────────────────────────────────────────────────

    fun modelDir(): File = File(context.filesDir, "models/guard")

    /** 当前模型文件：优先固定名 model.task，否则取目录里第一个可识别的模型。 */
    fun modelFile(): File? {
        val dir = modelDir()
        if (!dir.exists()) return null
        File(dir, "model.task").takeIf { it.length() > 0 }?.let { return it }
        File(dir, "model.gguf").takeIf { it.length() > 0 }?.let { return it }
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("task", "bin", "gguf") }
            ?.maxByOrNull { it.lastModified() }
    }

    companion object {
        /**
         * 应用内下载源。
         *
         * Qwen3Guard-Gen-0.6B 的社区 GGUF 量化（mradermacher 制作，apache-2.0）。
         * MediaPipe tasks-genai 0.10.14+ 直接支持加载 GGUF，无需再转换。
         * **国内默认走 hf-mirror 镜像**（huggingface.co 直连在境内基本不可达）。
         */
        val DOWNLOAD_SOURCES: List<Pair<String, String>> = listOf(
            "国内镜像 · Q4_K_M（462MB，推荐）" to
                "https://hf-mirror.com/mradermacher/Qwen3Guard-Gen-0.6B-GGUF/resolve/main/Qwen3Guard-Gen-0.6B.Q4_K_M.gguf",
            "国内镜像 · Q8_0（767MB，质量更高）" to
                "https://hf-mirror.com/mradermacher/Qwen3Guard-Gen-0.6B-GGUF/resolve/main/Qwen3Guard-Gen-0.6B.Q8_0.gguf",
            "HF 直连 · Q4_K_M（462MB）" to
                "https://huggingface.co/mradermacher/Qwen3Guard-Gen-0.6B-GGUF/resolve/main/Qwen3Guard-Gen-0.6B.Q4_K_M.gguf",
        )

        /** 下载完成的最小合法体积。低于它几乎可以断定拿到的是错误页而不是模型。 */
        private const val MIN_VALID_BYTES = 300L * 1024 * 1024
    }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var downloadCall: okhttp3.Call? = null

    fun cancelDownload() {
        downloadCall?.cancel()
    }

    /**
     * 流式下载模型文件到应用私有目录。
     *
     * - 先落 `.part` 临时文件，全部下完并通过体积校验后才改名 `model.gguf`
     *   —— 半截文件绝不冒充可用模型；
     * - 下载前做磁盘空间预检（需要文件体积 + 100MB 余量）；
     * - 每 8MB 上报一次进度，不刷爆 Flow。
     */
    fun downloadModel(url: String): Flow<GuardDownloadState> = flow {
        val dir = modelDir().apply { mkdirs() }
        val part = File(dir, "model.gguf.part")
        part.delete()

        val request = Request.Builder().url(url).build()
        val call = downloadClient.newCall(request)
        downloadCall = call
        emit(GuardDownloadState.Downloading(0, -1))

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(GuardDownloadState.Failed("下载源返回 HTTP ${resp.code}"))
                    return@flow
                }
                val body = resp.body ?: run {
                    emit(GuardDownloadState.Failed("下载源返回空内容"))
                    return@flow
                }
                val total = body.contentLength()
                if (total in 1 until MIN_VALID_BYTES) {
                    emit(GuardDownloadState.Failed("源文件只有 ${total / 1048576}MB，可能已失效"))
                    return@flow
                }

                // 磁盘空间预检：文件体积 + 100MB 余量
                val need = (if (total > 0) total else 800L * 1024 * 1024) + 100L * 1024 * 1024
                val available = StatFs(context.filesDir.absolutePath).availableBytes
                if (available < need) {
                    emit(
                        GuardDownloadState.Failed(
                            "存储空间不足：需要约 %.1fGB，可用 %.1fGB"
                                .format(need / 1e9, available / 1e9),
                        ),
                    )
                    return@flow
                }

                var downloaded = 0L
                var lastReported = -1L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (call.isCanceled()) {
                                part.delete()
                                emit(GuardDownloadState.Failed("已取消"))
                                return@flow
                            }
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            downloaded += n
                            val mb = downloaded / 1048576
                            if (mb != lastReported) {
                                lastReported = mb
                                emit(
                                    GuardDownloadState.Downloading(
                                        mb,
                                        if (total > 0) total / 1048576 else -1,
                                    ),
                                )
                            }
                        }
                    }
                }

                if (downloaded < MIN_VALID_BYTES) {
                    part.delete()
                    emit(GuardDownloadState.Failed("下载不完整（仅 ${downloaded / 1048576}MB）"))
                    return@flow
                }

                val dst = File(dir, "model.gguf")
                dst.delete()
                if (!part.renameTo(dst)) {
                    emit(GuardDownloadState.Failed("文件落盘失败"))
                    return@flow
                }
                // 换了新模型，旧的引擎实例必须重建
                mutex.withLock {
                    engine?.close()
                    engine = null
                    enginePath = null
                }
                emit(GuardDownloadState.Done(dst, downloaded / 1048576))
            }
        } catch (e: okhttp3.internal.http2.StreamResetException) {
            part.delete()
            emit(GuardDownloadState.Failed("连接被重置，换个源重试"))
        } catch (e: java.io.IOException) {
            part.delete()
            if (call.isCanceled()) {
                emit(GuardDownloadState.Failed("已取消"))
            } else {
                emit(GuardDownloadState.Failed("网络错误：${e.message ?: "未知"}"))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 从文件选择器导入模型，成功返回落地的文件。 */
    suspend fun importModel(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            Result.failure(IllegalStateException("读取不到所选文件"))
        } else {
            importBytes(bytes).onFailure {
                // 半截文件不留着，免得下次加载引擎时撞上
                modelDir().resolve("model.task").takeIf { f -> f.length() < 1_000_000 }?.delete()
            }
        }
    }

    /** 从内存字节导入模型（资源库管理页用）。 */
    suspend fun importBytes(bytes: ByteArray): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = modelDir().apply { mkdirs() }
            val dst = File(dir, "model.task")
            dst.writeBytes(bytes)
            if (dst.length() < 1_000_000) {
                dst.delete()
                error("文件太小了，不像一个 0.6B 的模型（应约 400–900MB）")
            }
            // 换了新模型，旧的引擎实例必须重建
            mutex.withLock {
                engine?.close()
                engine = null
                enginePath = null
            }
            dst
        }
    }

    /** 释放推理引擎占用的内存（设置里关掉审核时调用）。 */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            enginePath = null
        }
    }

    // ── 检查 ────────────────────────────────────────────────────────────────

    /**
     * 完整检查：最低层次体检在前（廉价、必过的问题先拦），
     * 有模型文件时再跑 Qwen3Guard 安全分类。模型不可用不阻塞内容 ——
     * 项目约定：宁可保留并提示，绝不静默丢弃。
     */
    suspend fun check(text: String): GuardVerdict {
        sanityCheck(text)?.let { return it }
        return modelCheck(text)
    }

    /** 最低层次检查：null 表示通过。 */
    fun sanityCheck(text: String): GuardVerdict? {
        val t = text.trim()
        if (t.isEmpty()) return GuardVerdict.Error("旁白没有输出任何内容")
        if (t.length < 20) {
            return GuardVerdict.Error("旁白输出过短（${t.length} 字），疑似生成失败")
        }
        // 退化检测：同一行重复刷屏（温度失控时会出现）
        val lines = t.lines().filter { it.isNotBlank() }
        if (lines.size >= 4 && lines.distinct().size == 1) {
            return GuardVerdict.Error("旁白输出为同一行反复重复，疑似生成异常")
        }
        // 协议标签没闭合（半截 <transfer>、<sticker>）说明流被截断
        listOf("<transfer>", "<sticker>").forEach { tag ->
            val opens = t.split(tag).size - 1
            val closes = t.split(tag.replace("<", "</")).size - 1
            if (opens > closes) {
                return GuardVerdict.Error("旁白输出里的 $tag 标签没有闭合，疑似流被截断")
            }
        }
        return null
    }

    /** Qwen3Guard 安全分类。没有模型文件时直接放行并说明原因。 */
    suspend fun modelCheck(text: String): GuardVerdict = withContext(Dispatchers.Default) {
        val file = modelFile()
            ?: return@withContext GuardVerdict.Error("未导入模型文件（设置里可导入 Qwen3Guard 0.6B）")

        val llm = ensureLoaded(file.absolutePath)
            ?: return@withContext GuardVerdict.Error("模型加载失败，请检查模型格式（需要 .task/.bin）")

        val response = runCatching {
            llm.generateResponse(safetyPrompt(text))
        }.getOrElse {
            return@withContext GuardVerdict.Error("推理异常：${it.message ?: "未知错误"}")
        }

        parseVerdict(response)
    }

    private suspend fun ensureLoaded(path: String): LlmInference? =
        mutex.withLock {
            engine?.takeIf { enginePath == path }?.let { return it }
            runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    // 审核结论只有 Safe/Unsafe 一行，长输出纯属浪费内存。
                    // 采样参数在新版 API 里挪到了 Session 层；分类任务用默认贪心即可。
                    .setMaxTokens(48)
                    .build()
                LlmInference.createFromOptions(context, options)
            }.getOrNull()?.also {
                engine?.close()
                engine = it
                enginePath = path
            }
        }

    // ── Qwen3Guard 的提示词 ─────────────────────────────────────────────────

    /**
     * Qwen3Guard 官方安全审核模板（ Safety Prompt ）：
     * 模型在 assistant 开头接续后输出 `Safe` 或 `Unsafe` + 类别行。
     */
    private fun safetyPrompt(content: String): String {
        val clipped = if (content.length > 2000) content.take(2000) + "…" else content
        return buildString {
            append("<|im_start|>user\n")
            append("You are a content safety moderator. Determine whether the following ")
            append("content is safe or unsafe for a roleplay fiction app.\n")
            append("<Content>\n$clipped\n</Content>\n")
            append("<|im_end|>\n")
            append("<|im_start|>assistant\nSafety:\n")
        }
    }

    /** 解析模型输出。宽容解析：大小写、全半角、前后缀都不管。 */
    private fun parseVerdict(raw: String): GuardVerdict {
        val lower = raw.lowercase()
        return when {
            "unsafe" in lower -> {
                // 类别行形如 "Categories: Violent, Sexual" 或直接跟在 Unsafe 后面
                val categories = Regex("categor\\w*\\s*[:：]\\s*(.+)")
                    .find(raw)?.groupValues
                    ?.getOrNull(1)?.trim().orEmpty()
                GuardVerdict.Unsafe(categories.ifBlank { "未给出类别" }, raw.trim())
            }
            "safe" in lower -> GuardVerdict.Ok
            else -> GuardVerdict.Error("模型输出无法解析：${raw.take(80)}")
        }
    }
}
