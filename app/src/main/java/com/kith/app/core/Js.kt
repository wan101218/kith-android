package com.kith.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json 安全访问扩展。
 *
 * 项目刻意不引入 kotlinx.serialization —— 它的编译器插件版本必须与 Kotlin 严格一致，
 * 在多工程环境下容易因版本错配导致构建失败。org.json 是 Android 平台内置的，
 * 零依赖、零风险，且足以覆盖本项目全部 JSON 需求（模型目录 232KB、OpenAI 兼容
 * 请求/响应、社会存档、日志行）。
 *
 * 命名约定：单字母方法名 = 带默认值的安全读取，读不到一律返回默认值而不是抛异常。
 */

// ── 读取 ────────────────────────────────────────────────────────────────────

// 注意：optString 在部分平台实现上会把 JSON null 转成字面 "null" 字符串透传
// （deepseek 思维模型流式 content:null 实测复现）。s()/so() 里的 != "null"
// 过滤就是这层防线 —— 字面 "null" 一律按缺省处理。

fun JSONObject.s(key: String, def: String = ""): String =
    if (!has(key) || isNull(key)) def
    else optString(key, def).takeIf { it != "null" } ?: def

fun JSONObject.so(key: String): String? =
    if (!has(key) || isNull(key)) null
    else optString(key).takeIf { it.isNotEmpty() && it != "null" }

fun JSONObject.i(key: String, def: Int = 0): Int =
    if (!has(key) || isNull(key)) def else optInt(key, def)

fun JSONObject.l(key: String, def: Long = 0L): Long =
    if (!has(key) || isNull(key)) def else optLong(key, def)

fun JSONObject.d(key: String, def: Double = 0.0): Double =
    if (!has(key) || isNull(key)) def else optDouble(key, def)

fun JSONObject.b(key: String, def: Boolean = false): Boolean =
    if (!has(key) || isNull(key)) def else optBoolean(key, def)

fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

fun JSONObject.strList(key: String): List<String> {
    val a = arr(key)
    return (0 until a.length()).mapNotNull { idx ->
        a.optString(idx).takeIf { it.isNotEmpty() }
    }
}

fun JSONObject.optObj(key: String): JSONObject? = optJSONObject(key)

// ── 构造 ────────────────────────────────────────────────────────────────────

/** 构造 JSONObject，值为 null 的键会被跳过而不是写入 JSON 的 null。 */
fun jObj(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> Unit
            is String -> o.put(k, v)
            is Int -> o.put(k, v)
            is Long -> o.put(k, v)
            is Double -> o.put(k, v)
            is Boolean -> o.put(k, v)
            is JSONObject -> o.put(k, v)
            is JSONArray -> o.put(k, v)
            is List<*> -> o.put(k, JSONArray(v))
            else -> o.put(k, v.toString())
        }
    }
    return o
}

fun JSONArray.toStrList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun jsonArrayOf(items: List<Any?>): JSONArray {
    val a = JSONArray()
    items.forEach { a.put(it) }
    return a
}

/**
 * 容错解析。任何异常都返回兜底值，保证「一条坏记录不会毁掉整个社会存档」。
 */
inline fun <T> safeParse(fallback: T, block: () -> T): T =
    try {
        block()
    } catch (_: Throwable) {
        fallback
    }

/** 把可能带 markdown 围栏的 JSON 文本清洗成纯 JSON。 */
fun stripJsonFence(raw: String): String {
    var t = raw.trim()
    if (t.startsWith("```")) {
        t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        val end = t.lastIndexOf("```")
        if (end >= 0) t = t.substring(0, end)
    }
    return t.trim()
}

/**
 * 从模型回复里抠出第一个平衡的 JSON 对象/数组。
 * 旁白引擎要求模型输出 JSON，但模型常会附带解释文字，这里做一次宽进严出。
 */
fun extractJsonBlock(raw: String): String? {
    val text = stripJsonFence(raw)
    val start = text.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val open = text[start]
    val close = if (open == '{') '}' else ']'
    var depth = 0
    var inStr = false
    var esc = false
    for (idx in start until text.length) {
        val c = text[idx]
        if (inStr) {
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = false
            }
            continue
        }
        when (c) {
            '"' -> inStr = true
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return text.substring(start, idx + 1)
            }
        }
    }
    return null
}
