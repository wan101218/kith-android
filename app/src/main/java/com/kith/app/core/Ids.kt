package com.kith.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object Ids {

    private val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    /** 生成形如 `chr_m3k9x2_a7f1` 的短 id，可按前缀区分实体类型。 */
    fun new(prefix: String): String {
        val t = System.currentTimeMillis().toString(36)
        val r = (1..4).map { ALPHABET.random() }.joinToString("")
        return "${prefix}_${t}_$r"
    }

    /** 由字符串派生稳定整数，用于给头像、封面等生成固定的随机配色。 */
    fun stableSeed(input: String): Int {
        var h = 2166136261u
        for (c in input) {
            h = h xor c.code.toUInt()
            h *= 16777619u
        }
        return (h and 0x7FFFFFFFu).toInt()
    }

    /** 取姓名首字（中文取首字，拉丁取首字母）用于头像文字。 */
    fun initials(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "?"
        val first = t.first()
        return if (first.code > 0x2E80) first.toString()
        else t.split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }
            .joinToString("").uppercase(Locale.ROOT)
    }

    /** 文件名安全化：去掉路径分隔符与不合法字符。 */
    fun safeFileName(raw: String, fallback: String = "item"): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        return cleaned.ifEmpty { fallback }.take(64)
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val DATE_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun clock(ts: Long): String = TIME_FMT.format(Date(ts))
    fun shortDateTime(ts: Long): String = DATE_FMT.format(Date(ts))
    fun fullDateTime(ts: Long): String = FULL_FMT.format(Date(ts))

    /** 「3 分钟前」这类相对时间，用于列表页。 */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - ts).coerceAtLeast(0)
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> shortDateTime(ts)
        }
    }
}

/** 线性同余伪随机，用于「同一 seed 必定得到同一结果」的图形布局抖动。 */
class SeededRandom(seed: Int) {
    private val rnd = Random(seed)
    fun nextFloat(): Float = rnd.nextFloat()
    fun nextInt(bound: Int): Int = rnd.nextInt(bound)
    fun jitter(base: Float, amount: Float): Float = base + (rnd.nextFloat() - 0.5f) * 2f * amount
}
