package com.kith.app.domain

/**
 * 内置模板。
 *
 * 产品要求「剧情走向可以有一些比如 bl gl bg bz 等模板提供使用」，同时人物标签
 * 也要有模板可选。这里把三类模板集中放在一起，方便后续扩充。
 */

/** 剧情走向模板：一套世界观 + 走向描述 + 题材标签的预设组合。 */
data class PlotTemplate(
    val orientation: PlotOrientation,
    val title: String,
    val subtitle: String,
    val worldSetting: String,
    val plotDirection: String,
    val tropes: List<String>,
)

object PlotTemplates {

    val all: List<PlotTemplate> = listOf(
        PlotTemplate(
            orientation = PlotOrientation.BG,
            title = "都市情感",
            subtitle = "异性向 · 现代都市",
            worldSetting = """
                一座常住人口两千万的现代都市，高楼与旧巷并存。
                这里的人靠手机维系大部分关系，也因此在最拥挤的地方感到最孤独。
                社会默认按 2020 年代的现实规则运转：有工作压力、有房租、有家庭期待。
            """.trimIndent(),
            plotDirection = """
                以男女主角之间的情感推进为主轴。从相识、试探、误会，到确认关系，
                中间穿插职场与家庭的现实阻力。节奏上先慢后快，重视日常细节与心理变化，
                避免一眼看穿的套路。
            """.trimIndent(),
            tropes = listOf("都市", "慢热", "现实向"),
        ),
        PlotTemplate(
            orientation = PlotOrientation.BL,
            title = "耽美向",
            subtitle = "男性之间 · 情感主轴",
            worldSetting = """
                现代社会背景，人物关系密集，社交圈重叠度高。
                同性情感在这里既不是猎奇也不是禁忌宣传，而是被当作一种需要认真对待的
                真实情感处境来描写：有隐忍、有试探，也有坦然和公开。
            """.trimIndent(),
            plotDirection = """
                两位男性主要角色之间的情感线为主轴。重视「克制与越界」之间的张力，
                用行动和细节推动，而不是靠直白表白。配角要有自己的生活，不做工具人。
            """.trimIndent(),
            tropes = listOf("现代", "强强", "细水长流"),
        ),
        PlotTemplate(
            orientation = PlotOrientation.GL,
            title = "百合向",
            subtitle = "女性之间 · 情感主轴",
            worldSetting = """
                现代社会背景，注重人物内心的细腻层次与彼此之间的理解成本。
                女性角色之间既有亲密也有竞争，关系是复杂的、会磨损也会修复的。
            """.trimIndent(),
            plotDirection = """
                两位女性主要角色之间的情感线为主轴。强调「被看见」与「彼此支撑」，
                情感推进藏在日常对话与共同经历里，避免刻意制造的狗血冲突。
            """.trimIndent(),
            tropes = listOf("现代", "治愈", "细腻"),
        ),
        PlotTemplate(
            orientation = PlotOrientation.BZ,
            title = "群像无 CP",
            subtitle = "非情感线 · 多线并进",
            worldSetting = """
                一个自洽的封闭环境：可以是一栋写字楼、一个剧组、一支乐队、一座小镇。
                所有人都在为各自的目标准备着什么，彼此的目标时而一致时而冲突。
            """.trimIndent(),
            plotDirection = """
                不设恋爱主线，以群像叙事推进：多条人物线并行交织，
                冲突来自利益、立场和信息差。每一章都要有明确的事件推进，
                让读者看到人物因为选择而改变处境。
            """.trimIndent(),
            tropes = listOf("群像", "事业", "多线"),
        ),
        PlotTemplate(
            orientation = PlotOrientation.CUSTOM,
            title = "从空白开始",
            subtitle = "完全自定义",
            worldSetting = "",
            plotDirection = "",
            tropes = emptyList(),
        ),
    )

    fun byOrientation(o: PlotOrientation): PlotTemplate =
        all.firstOrNull { it.orientation == o } ?: all.last()

    /** 可勾选的题材标签建议。 */
    val tropeSuggestions: List<String> = listOf(
        "都市", "校园", "古代", "民国", "科幻", "奇幻", "武侠", "末法",
        "悬疑", "犯罪", "治愈", "虐心", "甜宠", "强强", "救赎", "双向暗恋",
        "破镜重圆", "先婚后爱", "群像", "事业", "慢热", "日常",
    )
}

/**
 * 人物标签模板。
 *
 * 标签是「用户对某个角色的定位」，会直接写进那个角色的 system prompt，
 * 决定 TA 怎么称呼你、保持什么距离、主动到什么程度。所以每个标签都配了
 * 一条 [hint]，也就是给模型的执行说明。
 */
data class TagTemplate(
    val label: String,
    val hint: String,
)

object TagTemplates {

    val all: List<TagTemplate> = listOf(
        TagTemplate("恋人", "TA 是你的恋人。称呼可以亲密，会主动撒娇或吃醋，会关心你的行程，会表达想念。涉及其他人时会有占有欲。"),
        TagTemplate("暧昧对象", "你们处在还没挑明的阶段。TA 会试探、会嘴硬、会因为你的一句话反复琢磨，但不会直接表白。"),
        TagTemplate("配偶", "你们已经共同生活。对话务实、熟悉，带着长期相处才有的默契和偶尔的疲惫，不必刻意客气。"),
        TagTemplate("挚友", "无话不谈的老朋友。可以随便怼、随便开玩笑，也能在正事上直接说难听的实话。"),
        TagTemplate("朋友", "普通朋友关系。轻松、有分寸，开玩笑会看场合，不过度介入对方的私事。"),
        TagTemplate("家人", "亲属关系。会关心、会唠叨、会叮嘱吃饭穿衣，不太用网络梗，语气更稳。"),
        TagTemplate("同事", "工作关系。保持职业礼貌但有共事默契，话题多围绕项目和日程，私下交情有限。"),
        TagTemplate("上司", "TA 是你的上级。语气克制、以结果为导向，会布置任务也会给压力，不太聊私事。"),
        TagTemplate("下属", "TA 是你的下级。会汇报、会请示，尊重你但也有自己的小算盘和难处。"),
        TagTemplate("对手", "表面客气、暗地较劲。话里常有试探和机锋，认可你的能力但不承认。"),
        TagTemplate("宿敌", "立场对立。对话带火药味，但彼此了解极深，不会低级的互相谩骂。"),
        TagTemplate("师长", "年长或有指导关系。会给建议、会批评，语气有分量，不刻意讨好。"),
        TagTemplate("学生", "TA 是你的学生或后辈。会有请教和依赖，也会有自己的叛逆和想法。"),
        TagTemplate("暗恋者", "TA 暗恋你但没说破。会格外在意你的反应、会为你做小事、会在你提到别人时沉默一下。"),
        TagTemplate("陌生人", "你们刚认识。礼貌、有距离感，会互相打量，不会一上来就熟络。"),
    )

    fun hintOf(label: String): String = all.firstOrNull { it.label == label }?.hint.orEmpty()

    fun asTags(labels: List<String>): List<CharacterTag> = labels.map { label ->
        val t = all.firstOrNull { it.label == label }
        CharacterTag(
            label = label,
            hint = t?.hint ?: "",
            builtin = t != null,
        )
    }
}
