package com.kith.app.ai.guard

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * 本地内容审核（Qwen3Guard 0.6B）的设备性能门槛。
 *
 * 门槛来自用户设定：
 * - 内存 ≥ 4GB（模型文件约 0.4–0.9GB，加载后运行时占用还要再高一截）
 * - SoC 必须是骁龙 8 Gen1 / 7+ Gen2、天玑 9000、Exynos 2200 **或以上**
 *
 * SoC 识别用 [Build.SOC_MODEL]（API 31+，本项目 minSdk 33 恒可用），
 * 按**型号代号**划档而不是营销名——「骁龙 8 Gen 1」这种叫法各厂商写法不一，
 * 但高通的 SM84xx/85xx/86xx/87xx、联发科的 MT6983+、三星的 S5E9925+
 * 这些代号是稳定可靠的。
 */
data class DeviceCapability(
    val totalRamGB: Double,
    val socModel: String,
    val meetsRam: Boolean,
    val meetsSoc: Boolean,
) {
    val meetsRequirement: Boolean get() = meetsRam && meetsSoc

    /** 给 UI 展示的一行摘要，例如「SM8650 · 12.0GB 内存」。 */
    fun summary(): String = String.format(
        Locale.US,
        "SoC %s · %.1fGB 内存",
        socModel.ifBlank { "未知" },
        totalRamGB,
    )
}

object DeviceCapabilityCheck {

    const val MIN_RAM_GB = 4.0

    /** 帮助文案里的门槛描述，弹窗与设置页共用一套措辞。 */
    const val REQUIREMENT_TEXT =
        "内存 ≥ 4GB，且 SoC 为骁龙 8 Gen1 / 7+ Gen2、天玑 9000、Exynos 2200 或以上"

    fun check(context: Context): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ramGB = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
        val (soc, socOk) = detectSoc()
        return DeviceCapability(
            totalRamGB = ramGB,
            socModel = soc,
            meetsRam = ramGB >= MIN_RAM_GB,
            meetsSoc = socOk,
        )
    }

    /**
     * 识别 SoC 并判断是否达到审核的最低档位。
     *
     * 型号代号规则（不识别到具体颗，只划「够不够」这条线）：
     * - 高通：SM8450（8 Gen1）/ SM8475（8+ Gen1）/ SM7475（7+ Gen2）起的
     *   8 系与 7 系高配都算达标 —— SM84xx–89xx、SM75xx–77xx
     * - 联发科：MT6983（天玑 9000）起 —— MT6983–MT6999
     * - 三星：S5E9925（Exynos 2200）起 —— S5E9925 以上
     */
    private fun detectSoc(): Pair<String, Boolean> {
        val raw = listOf(Build.SOC_MODEL, Build.HARDWARE)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            .orEmpty()
        val m = raw.lowercase(Locale.US)

        val snapdragon = Regex("^sm(8[4-9]\\d{2}|7[5-7]\\d{2})").containsMatchIn(m)
        val dimensity = Regex("^mt69(8[3-9]|9\\d)").containsMatchIn(m)
        val exynos = Regex("^s5e99[2-9]\\d").containsMatchIn(m)

        return raw to (snapdragon || dimensity || exynos)
    }
}
