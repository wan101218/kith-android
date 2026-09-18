package com.kith.app.data.catalog

import androidx.compose.ui.graphics.Color
import com.kith.app.core.Ids

/**
 * 厂商注册表。
 *
 * 作用有三个：
 *  1. 给模型列表提供「厂商图标」所需的视觉标识 —— 品牌色 + 字母徽记；
 *  2. 给用户配置接入点时提供 OpenAI 兼容的默认 baseUrl，省掉查文档这一步；
 *  3. 把模型目录里五花八门的 vendor 字符串（`OpenAI` / `openai` / `Alibaba` / `Qwen`）
 *     归一化到同一个主体的展示身份上。
 *
 * 关于图标：项目刻意不使用各家商标位图。原因是一来商标授权不清，二来 20 多个图标
 * 风格无法统一，反而破坏界面一致性。改用「品牌色圆角磁贴 + 字母徽记」这一套自洽的
 * 视觉语言，在列表里识别度同样很高，且能覆盖任意新厂商（含用户自定义的）。
 * 少数几家另有手绘的几何矢量标记，见 [hasVectorMark]。
 */
data class VendorInfo(
    val key: String,
    /** 展示名 */
    val name: String,
    val color: Color,
    /** 字母徽记，1–2 个字符 */
    val monogram: String,
    /** OpenAI 兼容的默认接入地址；为空表示需用户自行填写 */
    val baseUrl: String = "",
    /** 控制台 / 申请 Key 的地址 */
    val console: String = "",
    /** 是否国内可直连（用于列表默认排序靠前） */
    val domestic: Boolean = false,
    /** 是否内置手绘几何矢量标记 */
    val hasVectorMark: Boolean = false,
)

object VendorRegistry {

    private val generic = VendorInfo(
        key = "custom",
        name = "自定义",
        color = Color(0xFF7A8291),
        monogram = "··",
    )

    val all: List<VendorInfo> = listOf(
        // ── 国内主流（国内可直连）────────────────────────────────────────────
        VendorInfo(
            "DeepSeek", "深度求索 DeepSeek", Color(0xFF4D6BFE), "DS",
            baseUrl = "https://api.deepseek.com/v1",
            console = "https://platform.deepseek.com", domestic = true, hasVectorMark = true,
        ),
        VendorInfo(
            "Alibaba", "阿里通义 Qwen", Color(0xFF615CED), "Q",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            console = "https://bailian.console.aliyun.com", domestic = true, hasVectorMark = true,
        ),
        VendorInfo(
            "Zhipu", "智谱 GLM", Color(0xFF3859FF), "ZP",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            console = "https://open.bigmodel.cn", domestic = true, hasVectorMark = true,
        ),
        VendorInfo(
            "Moonshot", "月之暗面 Kimi", Color(0xFF1F1F27), "K",
            baseUrl = "https://api.moonshot.cn/v1",
            console = "https://platform.moonshot.cn", domestic = true, hasVectorMark = true,
        ),
        VendorInfo(
            "ByteDance", "字节豆包 Doubao", Color(0xFF1868F0), "DB",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            console = "https://console.volcengine.com/ark", domestic = true,
        ),
        VendorInfo(
            "MiniMax", "MiniMax 海螺", Color(0xFFE8455F), "MM",
            baseUrl = "https://api.minimax.chat/v1",
            console = "https://platform.minimaxi.com", domestic = true,
        ),
        VendorInfo(
            "Tencent", "腾讯混元 Hunyuan", Color(0xFF0052D9), "HY",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            console = "https://console.cloud.tencent.com/hunyuan", domestic = true,
        ),
        VendorInfo(
            "Baidu", "百度文心 ERNIE", Color(0xFF2932E1), "BD",
            baseUrl = "https://qianfan.baidubce.com/v2",
            console = "https://console.bce.baidu.com/qianfan", domestic = true,
        ),
        VendorInfo(
            "Xiaomi", "小米 MiMo", Color(0xFFFF6900), "MI",
            console = "https://xiaomi.com", domestic = true,
        ),
        VendorInfo(
            "StepFun", "阶跃星辰 Step", Color(0xFF16A5A5), "SF",
            baseUrl = "https://api.stepfun.com/v1",
            console = "https://platform.stepfun.com", domestic = true,
        ),
        VendorInfo(
            "01.AI", "零一万物 Yi", Color(0xFF0E4F3C), "01",
            baseUrl = "https://api.lingyiwanwu.com/v1",
            console = "https://platform.lingyiwanwu.com", domestic = true,
        ),
        VendorInfo(
            "SiliconFlow", "硅基流动 SiliconFlow", Color(0xFF6E29F7), "SF",
            baseUrl = "https://api.siliconflow.cn/v1",
            console = "https://cloud.siliconflow.cn", domestic = true,
        ),

        // ── 国际厂商 ────────────────────────────────────────────────────────
        VendorInfo(
            "OpenAI", "OpenAI", Color(0xFF10A37F), "OA",
            baseUrl = "https://api.openai.com/v1",
            console = "https://platform.openai.com", hasVectorMark = true,
        ),
        VendorInfo(
            "Anthropic", "Anthropic Claude", Color(0xFFD97757), "AN",
            baseUrl = "https://api.anthropic.com/v1",
            console = "https://console.anthropic.com", hasVectorMark = true,
        ),
        VendorInfo(
            "Google", "Google Gemini", Color(0xFF4285F4), "G",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            console = "https://aistudio.google.com", hasVectorMark = true,
        ),
        VendorInfo(
            "Meta", "Meta Llama", Color(0xFF0866FF), "∞",
            console = "https://llama.com", hasVectorMark = true,
        ),
        VendorInfo(
            "Mistral", "Mistral AI", Color(0xFFFA520F), "MS",
            baseUrl = "https://api.mistral.ai/v1",
            console = "https://console.mistral.ai", hasVectorMark = true,
        ),
        VendorInfo(
            "xAI", "xAI Grok", Color(0xFF1A1A1A), "X",
            baseUrl = "https://api.x.ai/v1",
            console = "https://console.x.ai", hasVectorMark = true,
        ),
        VendorInfo(
            "NVIDIA", "NVIDIA NIM", Color(0xFF76B900), "NV",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            console = "https://build.nvidia.com",
        ),
        VendorInfo(
            "Microsoft", "Microsoft Azure", Color(0xFF00A4EF), "AZ",
            console = "https://portal.azure.com",
        ),
        VendorInfo(
            "Amazon", "Amazon Bedrock", Color(0xFFFF9900), "AWS",
            console = "https://console.aws.amazon.com/bedrock",
        ),
        VendorInfo(
            "Cohere", "Cohere", Color(0xFF39594D), "CO",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            console = "https://dashboard.cohere.com",
        ),
        VendorInfo(
            "Perplexity", "Perplexity", Color(0xFF20808D), "PP",
            baseUrl = "https://api.perplexity.ai",
            console = "https://www.perplexity.ai/settings/api",
        ),
        VendorInfo(
            "NousResearch", "Nous Research", Color(0xFF6B5BD2), "NR",
            console = "https://portal.nousresearch.com",
        ),

        // ── 聚合与自建 ──────────────────────────────────────────────────────
        VendorInfo(
            "OpenRouter", "OpenRouter（聚合）", Color(0xFF6566F1), "OR",
            baseUrl = "https://openrouter.ai/api/v1",
            console = "https://openrouter.ai/keys", hasVectorMark = true,
        ),
        // 云舟API：new-api 系中转站（cli.999554.xyz），OpenAI 兼容。
        // 当前公益令牌仅开放生图 gpt-image-2（走 /v1/images/generations，
        // 返回图床 URL）；对话模型令牌无权访问。生图预设见 ImageModelPresets，
        // 内置接入点见 AppSettings.BUILTIN_YUNZHOU_*。
        VendorInfo(
            "Yunzhou", "云舟API（中转）", Color(0xFF1677FF), "云",
            baseUrl = "https://cli.999554.xyz/v1",
            console = "https://cli.999554.xyz/", domestic = true,
        ),
        VendorInfo(
            "Groq", "Groq", Color(0xFFF55036), "GQ",
            baseUrl = "https://api.groq.com/openai/v1",
            console = "https://console.groq.com",
        ),
        VendorInfo(
            "Together", "Together AI", Color(0xFF0F6FFF), "TG",
            baseUrl = "https://api.together.xyz/v1",
            console = "https://api.together.ai",
        ),
        VendorInfo(
            "Fireworks", "Fireworks AI", Color(0xFFE4572E), "FW",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            console = "https://fireworks.ai",
        ),
        VendorInfo(
            "Ollama", "Ollama（本地）", Color(0xFF333333), "OL",
            baseUrl = "http://127.0.0.1:11434/v1", console = "https://ollama.com",
        ),
        VendorInfo(
            "LMStudio", "LM Studio（本地）", Color(0xFF5A5AE6), "LM",
            baseUrl = "http://127.0.0.1:1234/v1", console = "https://lmstudio.ai",
        ),
        VendorInfo(
            "vLLM", "vLLM（自建）", Color(0xFFFFB000), "vL",
            baseUrl = "http://127.0.0.1:8000/v1", console = "https://docs.vllm.ai",
        ),
        VendorInfo(
            "NewAPI", "New API / one-api 中转", Color(0xFF4C8BF5), "NA",
            baseUrl = "", console = "",
        ),

        // ── 生图厂商 ────────────────────────────────────────────────────────
        VendorInfo(
            "Seedream", "字节即梦 Seedream", Color(0xFF1868F0), "SD",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            console = "https://console.volcengine.com/ark", domestic = true,
        ),
        VendorInfo(
            "Wanx", "通义万相 Wanx", Color(0xFF615CED), "WX",
            baseUrl = "https://dashscope.aliyuncs.com/api/v1",
            console = "https://bailian.console.aliyun.com", domestic = true,
        ),
        VendorInfo(
            "Kolors", "快手可图 Kolors", Color(0xFFFF4E1F), "KO",
            baseUrl = "https://api.siliconflow.cn/v1", console = "", domestic = true,
        ),
        VendorInfo(
            "CogView", "智谱 CogView", Color(0xFF3859FF), "CG",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            console = "https://open.bigmodel.cn", domestic = true,
        ),
        VendorInfo(
            "Stability", "Stability AI", Color(0xFF8B5CF6), "ST",
            baseUrl = "https://api.stability.ai", console = "https://platform.stability.ai",
        ),
        VendorInfo(
            "BlackForest", "Black Forest Labs (FLUX)", Color(0xFFFFD23F), "BF",
            baseUrl = "https://api.bfl.ai/v1", console = "https://api.bfl.ai",
        ),
        VendorInfo(
            "Ideogram", "Ideogram", Color(0xFFEE4444), "ID",
            baseUrl = "https://api.ideogram.ai", console = "https://ideogram.ai",
        ),
        VendorInfo(
            "Recraft", "Recraft", Color(0xFFF97316), "RC",
            baseUrl = "https://external.api.recraft.ai/v1", console = "https://recraft.ai",
        ),
        VendorInfo(
            "Midjourney", "Midjourney（中转）", Color(0xFF1E1E24), "MJ",
            console = "https://midjourney.com",
        ),
    )

    /** 键 → 归一化用的别名表。模型目录里的 vendor 字段写法很杂，需要归并。 */
    private val aliases: Map<String, String> = mapOf(
        "openai" to "OpenAI",
        "anthropic" to "Anthropic",
        "google" to "Google",
        "alphabet" to "Google",
        "alibaba" to "Alibaba",
        "qwen" to "Alibaba",
        "tongyi" to "Alibaba",
        "meta" to "Meta",
        "meta-llama" to "Meta",
        "mistralai" to "Mistral",
        "x-ai" to "xAI",
        "xai" to "xAI",
        "deepseek" to "DeepSeek",
        "zhipu" to "Zhipu",
        "z-ai" to "Zhipu",
        "thudm" to "Zhipu",
        "minimax" to "MiniMax",
        "moonshot" to "Moonshot",
        "moonshotai" to "Moonshot",
        "bytedance" to "ByteDance",
        "doubao" to "ByteDance",
        "volcengine" to "ByteDance",
        "tencent" to "Tencent",
        "hunyuan" to "Tencent",
        "baidu" to "Baidu",
        "ernie" to "Baidu",
        "xiaomi" to "Xiaomi",
        "nvidia" to "NVIDIA",
        "amazon" to "Amazon",
        "aws" to "Amazon",
        "microsoft" to "Microsoft",
        "azure" to "Microsoft",
        "perplexity" to "Perplexity",
        "cohere" to "Cohere",
        "nousresearch" to "NousResearch",
        "nous" to "NousResearch",
        "openrouter" to "OpenRouter",
        "yunzhou" to "Yunzhou",
        "云舟" to "Yunzhou",
        "groq" to "Groq",
        "together" to "Together",
        "togethercomputer" to "Together",
        "fireworks" to "Fireworks",
        "stabilityai" to "Stability",
        "black-forest-labs" to "BlackForest",
        "bfl" to "BlackForest",
        "ideogram-ai" to "Ideogram",
    )

    private val byKey: Map<String, VendorInfo> = all.associateBy { it.key }

    /** 归一化任意 vendor 字符串。识别不出时返回 null，由调用方决定兜底策略。 */
    fun normalize(raw: String?): String? {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty() || key.equals("unknown", true)) return null
        byKey[key]?.let { return it.key }
        aliases[key.lowercase()]?.let { return it }
        // 再试一次把空格/下划线统一后匹配
        val squashed = key.lowercase().replace(Regex("[^a-z0-9]"), "")
        return all.firstOrNull {
            it.key.lowercase().replace(Regex("[^a-z0-9]"), "") == squashed
        }?.key
    }

    /**
     * 取得厂商信息。识别不出时用名称派生一个稳定的颜色与徽记 —— 这样即便是
     * 用户手填的新厂商，也能获得风格一致的图标，而不是掉进一个灰色的兜底块。
     */
    fun of(raw: String?): VendorInfo {
        val normalized = normalize(raw) ?: return derive(raw)
        return byKey[normalized] ?: generic
    }

    private val derivedCache = mutableMapOf<String, VendorInfo>()

    private fun derive(raw: String?): VendorInfo {
        val name = raw?.trim().orEmpty().ifEmpty { return generic }
        return derivedCache.getOrPut(name) {
            val seed = Ids.stableSeed(name)
            val hue = seed % 360
            VendorInfo(
                key = name,
                name = name,
                color = hslToColor(hue.toFloat(), 0.52f, 0.46f),
                monogram = monogramOf(name),
            )
        }
    }

    /** 取 1–2 个字符做徽记：中文取首字，拉丁取前两个字母。 */
    private fun monogramOf(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "··"
        return if (t.first().code > 0x2E80) {
            t.take(1)
        } else {
            val words = t.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
            if (words.size >= 2) {
                (words[0].take(1) + words[1].take(1)).uppercase()
            } else {
                words.firstOrNull()?.take(2)?.uppercase() ?: t.take(2).uppercase()
            }
        }
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
        )
    }
}
