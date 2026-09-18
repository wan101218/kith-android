package com.kith.app.data.catalog

/**
 * 文生图模型预设。
 *
 * 为什么单独维护而不是从 model_catalog.json 里筛：
 * `modelwatch` 抓的是 OpenRouter，而 OpenRouter 的 `modalities` 字段描述的是
 * **输入**模态（能不能读图），不是能不能生图。用它筛生图模型会把一堆视觉理解的
 * LLM 误当成画图模型。因此生图这边用一份人工维护的预设表，覆盖国内外常用服务。
 *
 * 每个预设都给出 OpenAI 兼容的 `images/generations` 形状参数，[ImageGenClient]
 * 按 [style] 走对应的适配分支。
 */
data class ImageModelPreset(
    val modelId: String,
    val name: String,
    val vendorKey: String,
    val vendor: VendorInfo,
    /** 覆盖默认接入地址；为空则用 vendor 的默认地址 */
    val baseUrl: String = "",
    /** 价格提示，直接展示给用户 */
    val priceHint: String,
    /** 是否可免费使用 */
    val free: Boolean = false,
    /** 支持的比例，给 UI 做选项 */
    val ratios: List<String> = listOf("1:1", "3:4", "4:3", "16:9", "9:16"),
    /** 参数风格适配 */
    val style: ImageApiStyle = ImageApiStyle.OPENAI,
    /** 生成结果返回的是 URL 还是 base64 */
    val returnsBase64: Boolean = false,
    val note: String = "",
)

enum class ImageApiStyle {
    /** 标准 OpenAI `/images/generations` */
    OPENAI,

    /** 智谱 GLM：同样路径，但 size 用 "1024x1024" 且无 response_format */
    ZHIPU,

    /** 通义万相异步任务：先提交再轮询 */
    DASHSCOPE_ASYNC,

    /** Stability / BFL / Ideogram 等非标准接口 */
    VENDOR_NATIVE,
}

object ImageModelPresets {

    val all: List<ImageModelPreset> = listOf(
        // ── 免费可用 ────────────────────────────────────────────────────────
        ImageModelPreset(
            modelId = "black-forest-labs/FLUX.1-schnell",
            name = "FLUX.1 schnell",
            vendorKey = "SiliconFlow",
            vendor = VendorRegistry.of("SiliconFlow"),
            priceHint = "免费",
            free = true,
            note = "硅基流动提供的免费 FLUX 快速版，出图块、质量够用，适合批量生成 NPC 头像",
        ),
        ImageModelPreset(
            modelId = "gpt-image-2",
            name = "GPT Image 2（云舟）",
            vendorKey = "Yunzhou",
            vendor = VendorRegistry.of("Yunzhou"),
            priceHint = "按云舟站点计价",
            note = "云舟中转站的生图通道，内置接入点开箱即用；返回图床 URL，" +
                "由应用自动转存到社会媒体目录",
        ),
        ImageModelPreset(
            modelId = "cogview-3-flash",
            name = "CogView-3-Flash",
            vendorKey = "CogView",
            vendor = VendorRegistry.of("CogView"),
            priceHint = "免费",
            free = true,
            style = ImageApiStyle.ZHIPU,
            note = "智谱免费生图，中文提示词理解好",
        ),

        // ── 国内主流 ────────────────────────────────────────────────────────
        ImageModelPreset(
            modelId = "doubao-seedream-3-0-t2i-250415",
            name = "即梦 Seedream 3.0",
            vendorKey = "Seedream",
            vendor = VendorRegistry.of("Seedream"),
            priceHint = "约 ¥0.26 / 张",
            ratios = listOf("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3"),
            note = "中文写实与国风表现最好的一档，豆包同源",
        ),
        ImageModelPreset(
            modelId = "wanx2.1-t2i-turbo",
            name = "通义万相 2.1 Turbo",
            vendorKey = "Wanx",
            vendor = VendorRegistry.of("Wanx"),
            priceHint = "约 ¥0.14 / 张",
            style = ImageApiStyle.DASHSCOPE_ASYNC,
            note = "便宜、稳定，适合给路人 NPC 批量出图",
        ),
        ImageModelPreset(
            modelId = "cogview-4",
            name = "CogView-4",
            vendorKey = "CogView",
            vendor = VendorRegistry.of("CogView"),
            priceHint = "约 ¥0.1 / 张",
            style = ImageApiStyle.ZHIPU,
            note = "中文语义准确，指令跟随好",
        ),
        ImageModelPreset(
            modelId = "Kwai-Kolors/Kolors",
            name = "可图 Kolors",
            vendorKey = "Kolors",
            vendor = VendorRegistry.of("Kolors"),
            priceHint = "约 ¥0.1 / 张",
            note = "人像质感好，画角色立绘比较稳",
        ),

        // ── 国际主流 ────────────────────────────────────────────────────────
        ImageModelPreset(
            modelId = "gpt-image-1",
            name = "GPT Image 1",
            vendorKey = "OpenAI",
            vendor = VendorRegistry.of("OpenAI"),
            priceHint = "约 $0.04 / 张起",
            returnsBase64 = true,
            note = "指令跟随最强，适合精确描述的角色外观",
        ),
        ImageModelPreset(
            modelId = "dall-e-3",
            name = "DALL·E 3",
            vendorKey = "OpenAI",
            vendor = VendorRegistry.of("OpenAI"),
            priceHint = "约 $0.04 / 张",
            ratios = listOf("1:1", "16:9", "9:16"),
        ),
        ImageModelPreset(
            modelId = "flux-pro-1.1",
            name = "FLUX 1.1 Pro",
            vendorKey = "BlackForest",
            vendor = VendorRegistry.of("BlackForest"),
            priceHint = "约 $0.04 / 张",
            style = ImageApiStyle.VENDOR_NATIVE,
            note = "写实细节与光影最好的一档",
        ),
        ImageModelPreset(
            modelId = "dall-e-3-hd",
            name = "Stable Image Core",
            vendorKey = "Stability",
            vendor = VendorRegistry.of("Stability"),
            priceHint = "约 $0.03 / 张",
            style = ImageApiStyle.VENDOR_NATIVE,
        ),
        ImageModelPreset(
            modelId = "V_2",
            name = "Ideogram V2",
            vendorKey = "Ideogram",
            vendor = VendorRegistry.of("Ideogram"),
            priceHint = "约 $0.08 / 张",
            style = ImageApiStyle.VENDOR_NATIVE,
            note = "图内文字渲染最准，适合带字的场景图",
        ),
        ImageModelPreset(
            modelId = "recraftv3",
            name = "Recraft V3",
            vendorKey = "Recraft",
            vendor = VendorRegistry.of("Recraft"),
            priceHint = "约 $0.04 / 张",
            style = ImageApiStyle.VENDOR_NATIVE,
        ),
        ImageModelPreset(
            modelId = "imagen-4.0-generate-001",
            name = "Imagen 4",
            vendorKey = "Google",
            vendor = VendorRegistry.of("Google"),
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            priceHint = "约 $0.04 / 张",
        ),
        ImageModelPreset(
            modelId = "sd3.5-large",
            name = "Stable Diffusion 3.5 Large",
            vendorKey = "Stability",
            vendor = VendorRegistry.of("Stability"),
            priceHint = "约 $0.065 / 张",
            style = ImageApiStyle.VENDOR_NATIVE,
        ),
        ImageModelPreset(
            modelId = "midjourney",
            name = "Midjourney（需中转）",
            vendorKey = "Midjourney",
            vendor = VendorRegistry.of("Midjourney"),
            priceHint = "按中转商计价",
            style = ImageApiStyle.VENDOR_NATIVE,
            note = "官方无公开 API，需填入第三方中转地址",
        ),
    )

    fun byModelId(modelId: String): ImageModelPreset? = all.firstOrNull { it.modelId == modelId }

    val freeOnes: List<ImageModelPreset> get() = all.filter { it.free }

    /** 比例 → 常见尺寸映射，供 OpenAI 风格接口使用。 */
    fun sizeFor(ratio: String, longEdge: Int = 1024): String {
        val (w, h) = when (ratio) {
            "1:1" -> 1 to 1
            "3:4" -> 3 to 4
            "4:3" -> 4 to 3
            "16:9" -> 16 to 9
            "9:16" -> 9 to 16
            "3:2" -> 3 to 2
            "2:3" -> 2 to 3
            else -> 1 to 1
        }
        return if (w >= h) {
            "$longEdge" + "x" + (longEdge * h / w)
        } else {
            (longEdge * w / h).toString() + "x" + "$longEdge"
        }
    }
}
