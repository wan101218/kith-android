package com.kith.app.ai.engine

import com.kith.app.domain.Character
import com.kith.app.domain.Importance
import com.kith.app.domain.ModelTier
import com.kith.app.domain.Relation

/**
 * 人物重要性评分。
 *
 * 这是「旁白自动为 NPC 分配模型」这条链路的判据中枢。设计原则：
 *
 * **不能只信模型给的分。** 模型有很强的「礼貌性通胀」倾向 —— 你让它给一个人物
 * 打分，它很容易觉得「这个角色也挺重要的」然后给 70 分。一个社会里如果人人都
 * 是 70 分，那用旗舰模型的成本会迅速失控。
 *
 * 因此最终分数由两部分融合：
 *  - **模型判断**（0-100）：它理解剧情语义，知道谁在叙事上有位置；
 *  - **结构信号**：关系网里的连接度、用户是否亲手打了标签。这些是客观的、
 *    模型无法虚报的 —— 一个人如果被用户标成「恋人」，TA 的重要性是确定的。
 *
 * 两者加权后取较大值，保证「模型低估但结构上明显重要」的人不会被压下去。
 */
object ImportanceScorer {

    /** 模型判断的权重。结构信号权重更高，用于纠偏。 */
    private const val MODEL_WEIGHT = 0.55
    private const val STRUCTURE_WEIGHT = 0.60

    /**
     * 融合后的最终分数。
     *
     * @param proposedByModel 旁白给出的 importanceScore
     * @param relations 这个人物在关系网里的所有连线
     * @param taggedByUser 用户是否亲手给 TA 打了关系标签
     * @param plotMentions 最近剧情节点里被提及的次数
     */
    fun finalScore(
        proposedByModel: Int,
        relations: List<Relation>,
        taggedByUser: Boolean = false,
        plotMentions: Int = 0,
    ): Int {
        val model = proposedByModel.coerceIn(0, 100)

        // 结构分：连接数 + 连接强度 + 用户标签 + 剧情提及
        var structure = 0.0

        // 每有一条关系加 11 分，最多 4 条起效（一个人关系网超过 4 条就不再线性加权）
        structure += minOf(relations.size, 4) * 11.0

        // 强关系（亲密度 >= 70）额外加分，最多 2 条起效
        val strong = relations.count { it.intensity >= 70 }
        structure += minOf(strong, 2) * 9.0

        // 用户亲手打的标签是强信号：用户在意的人就是重要的人
        if (taggedByUser) structure += 22.0

        // 剧情里被反复提及说明在叙事中有位置
        structure += minOf(plotMentions, 3) * 6.0

        // 只挂了熟人关系的边缘人物，结构分应该压下去
        if (relations.all { it.kind == com.kith.app.domain.RelationKind.ACQUAINTANCE }) {
            structure *= 0.7
        }

        val blended = model * MODEL_WEIGHT + structure.coerceAtMost(100.0) * STRUCTURE_WEIGHT
        return blended.coerceIn(0.0, 100.0).toInt()
    }

    /** 分数 → 重要性等级。 */
    fun importanceOf(score: Int): Importance = Importance.ofScore(score)

    /**
     * 决定这个人物该用哪个档位的模型。
     *
     * 用户显式指定的档位优先（[explicit]），否则按重要性推导。
     * 这样既满足「自动分配」，也保留用户手动的余地。
     */
    fun tierOf(importance: Importance, explicit: ModelTier? = null): ModelTier =
        explicit ?: importance.tier

    /**
     * 旁白生成 NPC 时的默认重要性。
     * 旁白没给分（或给得不合理）时的兜底：默认按「配角」处理，宁低不高。
     */
    const val DEFAULT_PROPOSED = 45

    /**
     * 旁白给新 NPC 分配模型时，需要读图能力吗？
     * 只有用户可能给 TA 发图的角色（即可对话角色）才需要。
     */
    fun needsVision(character: Character): Boolean = character.importance.chatEnabled
}
