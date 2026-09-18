package com.kith.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 HTTP 客户端。
 *
 * 只有 OkHttp 一个网络依赖 —— 它足够稳、缓存里已有，且 OkHttp 的连接池与
 * 超时策略对「大模型流式输出」这种长连接场景可控性最好。
 *
 * 超时策略说明：readTimeout 给到 5 分钟。大模型在长上下文下首包可能要等很久，
 * 用默认 10 秒会在切模型时大量误报超时。真正的超时控制交给上层协程的 cancel。
 */
object Http {

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 目录刷新这类小请求用的短超时客户端。 */
    private val quickClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class TextResult(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    sealed interface Failure {
        data class Network(val message: String) : Failure
        data class Http(val code: Int, val body: String) : Failure
    }

    class HttpException(val failure: Failure) : IOException(
        when (failure) {
            is Failure.Network -> failure.message
            is Failure.Http -> "HTTP ${failure.code}: ${failure.body.take(300)}"
        }
    )

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        quick: Boolean = true,
    ): TextResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val engine = if (quick) quickClient else client
        engine.newCall(builder.build()).execute().use { resp ->
            TextResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        quick: Boolean = false,
    ): TextResult = withContext(Dispatchers.IO) {
        val request = buildJsonRequest(url, body, headers)
        val engine = if (quick) quickClient else client
        engine.newCall(request).execute().use { resp ->
            TextResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    private fun buildJsonRequest(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        stream: Boolean = false,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("Content-Type", "application/json")
        if (stream) builder.header("Accept", "text/event-stream")
        headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        return builder.build()
    }

    /**
     * 以 SSE 方式流式读取。产出的是**原始 data 载荷**（已剥掉 `data: ` 前缀），
     * 由调用方决定怎么解析 —— chat completions 与 responses 两种格式的增量
     * 字段位置不同，放在这一层解析会把 HTTP 层和数据格式耦合死。
     *
     * 结尾的 `[DONE]` 哨兵会被自动过滤掉。
     */
    fun streamSse(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        onOpen: () -> Unit = {},
    ): Flow<String> = callbackFlow {
        val request = buildJsonRequest(url, body, headers, stream = true)
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        close(HttpException(Failure.Http(resp.code, err)))
                        return
                    }
                    onOpen()
                    val source = resp.body?.source()
                    if (source == null) {
                        close(IOException("响应体为空"))
                        return
                    }
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            if (payload == "[DONE]") break
                            trySend(payload)
                        }
                        close()
                    } catch (t: Throwable) {
                        close(t)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * 一次性执行的协程包装，供非流式请求使用。
     * 之所以不用 [postJson]，是因为有些调用需要挂到指定 [Call] 上以便取消。
     */
    suspend fun awaitCall(call: Call): TextResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = try {
                        resp.body?.string().orEmpty()
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(TextResult(resp.code, text))
                }
            }
        })
    }
}
