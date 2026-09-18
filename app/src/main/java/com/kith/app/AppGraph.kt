package com.kith.app

import android.content.Context
import com.kith.app.ai.ImageGenClient
import com.kith.app.ai.LlmClient
import com.kith.app.ai.VisionBridge
import com.kith.app.ai.guard.ContentGuard
import com.kith.app.ai.engine.CharacterAgent
import com.kith.app.ai.engine.Money
import com.kith.app.ai.engine.NarratorEngine
import com.kith.app.data.catalog.ModelCatalog
import com.kith.app.data.pick.ModelPickBus
import com.kith.app.data.settings.AppSettings
import com.kith.app.data.sticker.StickerLibrary
import com.kith.app.data.store.SocietyStore

/**
 * 全局依赖容器。
 *
 * 手写而非用 Hilt/Koin：本项目的依赖图是扁平的、单向的
 * （settings → catalog → store → engines），没有需要动态注入的场景。
 * 手写容器少一层注解处理器，构建更快，也更容易在阅读时把整条链路看懂。
 */
class AppGraph(context: Context) {

    val settings: AppSettings = AppSettings(context)
    val catalog: ModelCatalog = ModelCatalog(context)
    val store: SocietyStore = SocietyStore(context)
    val stickers: StickerLibrary = StickerLibrary()

    /** 「选模型」页面的请求/结果总线，跨导航回传选择结果。 */
    val pickBus: ModelPickBus = ModelPickBus()

    val llm: LlmClient = LlmClient(catalogNames = {
        // 内置目录全部模型 id + 剥前缀裸名。目录按「接入点实测/官方文档」维护，
        // 发送前的防呆校验对这些名字放行（例：DeepSeek 官方对 deepseek-v4-flash
        // 保留别名兼容，但 /models 只列规范名）。
        catalog.current().models.flatMap { m ->
            val bare = m.id.substringAfter('/', "")
            listOfNotNull(m.id, bare.takeIf { it.isNotEmpty() })
        }.toSet()
    })
    val vision: VisionBridge = VisionBridge()
    val imageGen: ImageGenClient = ImageGenClient(store)

    val narrator: NarratorEngine = NarratorEngine(llm, catalog)
    val agent: CharacterAgent = CharacterAgent(llm, vision, catalog)

    /** 本地内容审核（Qwen3Guard 0.6B）。默认关闭；模型文件由用户在设置里导入。 */
    val guard: ContentGuard = ContentGuard(context)

    init {
        // 金额显示统一走人民币。汇率不在 Money 里写死，而是每次现读设置 ——
        // 这样用户在设置页改完汇率，无需重启，所有界面立刻跟着变。
        Money.rateProvider = { settings.current.usdToCnyRate }
    }
}

/** 便捷取用点。 */
val kithGraph: AppGraph get() = KithApp.instance.graph
