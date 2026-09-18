package com.kith.app.ui.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kith.app.data.settings.GraphLayout
import com.kith.app.domain.Character
import com.kith.app.domain.Importance
import com.kith.app.domain.Relation
import com.kith.app.domain.RelationKind
import com.kith.app.domain.Society
import com.kith.app.ui.common.CharacterAvatar
import com.kith.app.ui.theme.LocalKithColors
import com.kith.app.ui.theme.color
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** 图上一个节点。坐标单位为 dp。 */
data class GraphNode(
    val character: Character,
    val x: Float,
    val y: Float,
    /** 与「你」的层级距离，0 = 用户本人 */
    val depth: Int,
)

data class GraphEdge(
    val relation: Relation,
    val from: GraphNode,
    val to: GraphNode,
)

data class GraphLayoutResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val contentWidth: Float,
    val contentHeight: Float,
    /**
     * 没有任何关系连线的人物 —— 不进画布。
     * 之前把无关系的人全堆到 BFS 最外层，导入 89 个无关系人物后
     * 树状折出二十来行、链式排成九十六节点的长蛇，图直接没法看。
     * 现在他们改在图下方以紧凑网格列出。
     */
    val isolated: List<Character> = emptyList(),
) {
    fun nodeOf(id: String?): GraphNode? = if (id == null) null else nodes.firstOrNull { it.character.id == id }
}

/**
 * 关系图布局计算。
 *
 * 社会关系本质上是一张**图**而不是一棵树（A 是 B 的朋友、B 是 C 的同事、
 * C 又和 A 是邻居），所以「树状图」在这里实现为**按与「你」的距离分层**的
 * 层级图：第 0 层是你，第 1 层是你的直接关系人，第 2 层是他们的关系人，
 * 以此类推。这既保留了「一眼看出谁离你近」的树状直觉，又不会因为环状关系而布局失败。
 *
 * 「链式图」则把所有人按 BFS 顺序串成一条正弦波走线，强调关系的传递性 ——
 * 适合看「A 通过 B 认识 C」这类间接链路。
 */
object RelationGraphLayout {

    /**
     * 尺寸与间距。
     *
     * 这组数值是按「一屏看全」倒推的。以 7 个节点（你在中心 + 6 个直接关系人）为例：
     * 链式布局内容高度 = PADDING*2 + NODE + 6*vGap ≈ 604dp，
     * 在 792dp 的屏上扣掉紧凑头部（约 104dp）后**不需要滚动**就能看全；
     * 调整前是 864dp，必须滚两屏才看得完。树状布局只在横向溢出约 60dp。
     */
    const val NODE_SIZE = 58f
    private const val LEVEL_GAP = 104f
    private const val H_GAP = 12f
    private const val PADDING = 24f

    /** 树状布局里同一层内，一行最多排到多宽（超出就折行）。 */
    private const val MAX_ROW_WIDTH = 320f

    /**
     * 同一层内折行后，行与行之间的间距。
     * 必须给足 88dp：每个节点下面还挂着姓名，行距太小的话
     * 上一行的姓名会贴到下一行的头像上。
     */
    private const val ROW_GAP = 88f

    fun compute(
        society: Society,
        characters: List<Character>,
        relations: List<Relation>,
        layout: GraphLayout,
    ): GraphLayoutResult {
        if (characters.isEmpty()) {
            return GraphLayoutResult(emptyList(), emptyList(), 0f, 0f)
        }

        // 邻接表（无向：关系对双方都生效）
        val adjacency = HashMap<String, MutableList<Relation>>()
        relations.forEach { r ->
            adjacency.getOrPut(r.fromId) { mutableListOf() }.add(r)
            adjacency.getOrPut(r.toId) { mutableListOf() }.add(r)
        }

        // 只让真正连了关系的人进画布；无关系的走图下方网格
        val linked = characters.filter { adjacency[it.id]?.isNotEmpty() == true }
        val origin = linked.firstOrNull { it.isUser }
            ?: linked.maxByOrNull { adjacency[it.id]?.size ?: 0 }
            ?: return GraphLayoutResult(emptyList(), emptyList(), 0f, 0f, isolated = characters)

        // BFS 分层（只在连通集内进行）
        val depth = HashMap<String, Int>()
        val order = ArrayList<String>()
        depth[origin.id] = 0
        order += origin.id
        val queue = ArrayDeque<String>()
        queue += origin.id
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur] ?: 0
            adjacency[cur]?.forEach { r ->
                val next = if (r.fromId == cur) r.toId else r.fromId
                if (next !in depth) {
                    depth[next] = d + 1
                    order += next
                    queue += next
                }
            }
        }

        // 连通但 BFS 没到的（别的连通分量，比如「郑知秋 ↔ 沈岫」这种
        // 不经过用户的关系对）当作最外层，保留在画布里不丢弃
        val maxDepth = (depth.values.maxOrNull() ?: 0)
        val linkedIds = linked.map { it.id }.toSet()
        characters.forEach { c ->
            if (c.id in linkedIds && c.id !in depth) {
                depth[c.id] = maxDepth + 1
                order += c.id
            }
        }

        val connected = characters.filter { it.id in depth }
        val isolated = characters.filter { it.id !in depth }

        return when (layout) {
            GraphLayout.TREE -> layoutTree(connected, depth, adjacency, origin.id)
                .copy(isolated = isolated)
            GraphLayout.CHAIN -> layoutChain(connected, depth, order, adjacency, origin.id)
                .copy(isolated = isolated)
        }
    }

    // ── 树状：按层级自上而下 ────────────────────────────────────────────────

    /**
     * 树状：按与「你」的层级自上而下，**层内人数多时自动折行**。
     *
     * 为什么必须折行：一层里的人可能很多。实测「城南夜班」这个社会里，除主角外的
     * 6 个人全都是主角的直接关系人 —— 也就是全挤在第 1 层。硬排成一行需要
     * 6×52 + 5×12 = 372dp，已经超出 360dp 的屏宽，最后一个节点会被切在屏幕外。
     *
     * 折行时**尽量均分**：6 个人拆成 3+3 而不是 5+1，视觉重心更稳，
     * 也不会出现一行挤满、另一行只挂一个孤零零的节点。
     */
    private fun layoutTree(
        characters: List<Character>,
        depth: Map<String, Int>,
        adjacency: Map<String, List<Relation>>,
        originId: String,
    ): GraphLayoutResult {
        val levels = characters.groupBy { depth[it.id] ?: 0 }.toSortedMap()

        // 一行最多放几个
        val perRow = ((MAX_ROW_WIDTH + H_GAP) / (NODE_SIZE + H_GAP)).toInt().coerceAtLeast(1)

        // 先把每层切成若干行。层内先排序：与上一层关系越强越靠前
        val rows = ArrayList<Pair<Int, List<Character>>>()
        levels.forEach { (level, list) ->
            val sorted = if (level == 0) list else {
                val parentIds = levels[level - 1]?.map { it.id }.orEmpty().toSet()
                list.sortedWith(
                    compareByDescending<Character> { c ->
                        adjacency[c.id].orEmpty()
                            .filter { (if (it.fromId == c.id) it.toId else it.fromId) in parentIds }
                            .maxOfOrNull { it.intensity } ?: -1
                    }.thenBy { it.name },
                )
            }

            val rowCount = (sorted.size + perRow - 1) / perRow
            if (rowCount <= 1) {
                if (sorted.isNotEmpty()) rows += level to sorted
            } else {
                // 尽量均分：n 个人分到 rowCount 行，前 extra 行多一个
                val base = sorted.size / rowCount
                val extra = sorted.size % rowCount
                var idx = 0
                repeat(rowCount) { r ->
                    val take = base + if (r < extra) 1 else 0
                    rows += level to sorted.subList(idx, idx + take)
                    idx += take
                }
            }
        }

        // 画布宽度取最宽的那一行
        val widest = rows.maxOfOrNull { it.second.size } ?: 1
        val contentWidth =
            widest * NODE_SIZE + (widest - 1).coerceAtLeast(0) * H_GAP + PADDING * 2

        val nodes = ArrayList<GraphNode>(characters.size)
        var y = PADDING + NODE_SIZE / 2f
        var lastLevel = -1
        rows.forEach { (level, items) ->
            if (lastLevel >= 0) {
                y += if (level != lastLevel) LEVEL_GAP else ROW_GAP
            }
            lastLevel = level
            val rowW = items.size * NODE_SIZE + (items.size - 1).coerceAtLeast(0) * H_GAP
            val startX = (contentWidth - rowW) / 2f + NODE_SIZE / 2f
            items.forEachIndexed { i, c ->
                nodes += GraphNode(
                    character = c,
                    x = startX + i * (NODE_SIZE + H_GAP),
                    y = y,
                    depth = level,
                )
            }
        }

        val contentHeight = y + NODE_SIZE / 2f + PADDING
        return GraphLayoutResult(nodes, buildEdges(nodes, adjacency), contentWidth, contentHeight)
    }

    // ── 链式：按 BFS 顺序串成一条波动走线 ───────────────────────────────────

    private fun layoutChain(
        characters: List<Character>,
        depth: Map<String, Int>,
        order: List<String>,
        adjacency: Map<String, List<Relation>>,
        originId: String,
    ): GraphLayoutResult {
        val byId = characters.associateBy { it.id }
        // 起点排最前，其余按 BFS 顺序
        val sequence = (listOf(originId) + order.filter { it != originId })
            .mapNotNull { byId[it] }
            .distinctBy { it.id }

        // 数值配合上面的尺寸一起收紧：7 个节点总高 586dp，一屏能装下
        val amplitude = 64f
        val vGap = 80f
        val contentWidth = amplitude * 2 + NODE_SIZE + PADDING * 2
        val centerX = contentWidth / 2f

        val nodes = sequence.mapIndexed { i, c ->
            // 前半段先向左摆，形成 S 形而不是一直偏向一侧
            val angle = (i * 0.78).toFloat()
            GraphNode(
                character = c,
                x = centerX + (sin(angle) * amplitude),
                y = PADDING + NODE_SIZE / 2f + i * vGap,
                depth = depth[c.id] ?: i,
            )
        }
        val contentHeight = PADDING * 2 + NODE_SIZE + (sequence.size - 1).coerceAtLeast(0) * vGap
        return GraphLayoutResult(nodes, buildEdges(nodes, adjacency), contentWidth, contentHeight)
    }

    /** 把关系映射成边。两端都在图里才画。 */
    private fun buildEdges(
        nodes: List<GraphNode>,
        adjacency: Map<String, List<Relation>>,
    ): List<GraphEdge> {
        val byId = nodes.associateBy { it.character.id }
        val seen = HashSet<String>()
        val edges = ArrayList<GraphEdge>()
        nodes.forEach { n ->
            adjacency[n.character.id].orEmpty().forEach { r ->
                val a = byId[r.fromId] ?: return@forEach
                val b = byId[r.toId] ?: return@forEach
                if (a.character.id == b.character.id) return@forEach
                // 无向去重
                val key = listOf(r.id).joinToString()
                if (!seen.add(key)) return@forEach
                edges += GraphEdge(r, a, b)
            }
        }
        return edges
    }

    /**
     * 关系类型 → 连线颜色。
     *
     * 每种关系一个专属颜色，不再复用主题语义色 —— 之前 16 种关系共用 6 个主题色，
     * 图例里「朋友 / 邻里 / 点头之交」三条挤同一个青色，用户根本分不清哪条线是哪种关系。
     * 色相按轮盘铺开（橙/玫红/紫/蓝/青绿/金/朱红/靛/青/钢蓝/棕/湖绿/黄绿/品红/灰/琥珀），
     * 中等明度在墨黑与米白两种主题下都可读。
     */
    fun edgeColor(kind: RelationKind, theme: com.kith.app.ui.theme.KithSemanticColors): Color =
        when (kind) {
            RelationKind.FAMILY -> Color(0xFFD9A036)        // 亲属 · 赭金
            RelationKind.LOVER -> Color(0xFFF2547D)         // 恋人 · 玫红
            RelationKind.SPOUSE -> Color(0xFFB15CE8)        // 伴侣 · 紫
            RelationKind.FRIEND -> Color(0xFF4C9BE8)        // 朋友 · 蓝
            RelationKind.BEST_FRIEND -> Color(0xFF21B586)   // 挚友 · 青绿
            RelationKind.RIVAL -> Color(0xFFC9A227)         // 对手 · 金
            RelationKind.ENEMY -> Color(0xFFE0453A)         // 宿敌 · 朱红
            RelationKind.COLLEAGUE -> Color(0xFF7986CB)     // 同事 · 靛
            RelationKind.CLASSMATE -> Color(0xFF38BFD8)     // 同学 · 青
            RelationKind.SUPERIOR -> Color(0xFF5C7A99)      // 上司 · 钢蓝
            RelationKind.SUBORDINATE -> Color(0xFFA67F5A)   // 下属 · 棕
            RelationKind.MENTOR -> Color(0xFF4FBDB1)        // 师长 · 湖绿
            RelationKind.STUDENT -> Color(0xFFA2CE5D)       // 学生 · 黄绿
            RelationKind.NEIGHBOR -> Color(0xFFDD7AC8)      // 邻里 · 品红
            RelationKind.ACQUAINTANCE -> Color(0xFF8A93A0)  // 点头之交 · 灰（偏中灰，浅底上 55% 透明度也可见）
            RelationKind.CUSTOM -> Color(0xFFF5B544)        // 自定义 · 琥珀（品牌色）
        }
}

// ── 入场动画 ────────────────────────────────────────────────────────────────
//
// 「加载动画」在这里不是转圈，而是让图**自己组装起来**：
// 节点按布局顺序依次浮现（淡入 + 从 0.72 倍放大），连线紧跟着像被画出来一样
// 从一端生长到另一端。既填掉了首帧的空白，也比一个 spinner 更贴这个产品的调性。
//
// 整段动画只跑**一个** Animatable：每个元素按自己在时间轴上的排队位置，
// 从全局进度里换算出本地进度。比给 N 个元素各起一个动画省得多，
// 也不会出现几十个动画各自调度、互相抢帧的情况。

/** 节点阶段占整段动画的比例，剩下的留给连线。 */
private const val NODE_PHASE = 0.62f

/** 单个节点自身动画所占的时间比例。 */
private const val NODE_ITEM_SPAN = 0.34f

/** 连线从整段动画的这个位置开始出现。 */
private const val EDGE_PHASE_START = 0.58f

/** 单条连线自身动画所占的时间比例。 */
private const val EDGE_ITEM_SPAN = 0.30f

/** 入场动画总时长。900ms 是「看得出在动、又不用等」的区间。 */
private const val ENTER_DURATION_MS = 900

/** 网格 → 画布拖拽进行中的状态。pos 为手指在 window 坐标系里的当前位置。 */
private data class GridDrag(
    val character: Character,
    val pos: Offset,
    /** 起拖点，用来判断用户到底有没有真的拖动（没有就当「一键放上去」） */
    val start: Offset,
)

/**
 * 把一个元素按它在 [index] / [count] 里的排队位置，换算成它自己的本地进度（0..1）。
 *
 * 每个元素在时间轴上占 [span] 的长度，起点在 [phase] 区间内均分 ——
 * 于是最后一个元素的动画正好在 [phase] 处收尾，不会拖过整段动画。
 */
private fun stagedProgress(
    global: Float,
    index: Int,
    count: Int,
    phase: Float,
    span: Float,
): Float {
    if (count <= 0) return 1f
    val lastStart = (phase - span).coerceAtLeast(0f)
    val stagger = if (count > 1) lastStart / (count - 1) else 0f
    return ((global - index * stagger) / span).coerceIn(0f, 1f)
}

/**
 * 关系图。
 *
 * 结构上分两层：底下是一张 Canvas 画连线，上面叠一层可点击的头像组件。
 * 之所以不全部画在 Canvas 里，是因为头像需要支持真实的点击、长按与无障碍语义 ——
 * 自己实现命中测试既麻烦又不准。
 *
 * 节点支持**长按拖拽**：按住头像拖动即可把它挪到顺手的位置，
 * 松手后由调用方持久化（[onNodeDrag] 持续回报 dp 位移，[onDragEnded] 在松手时触发）。
 * 位移叠加在自动布局之上，连线实时跟随。
 *
 * 没有关系连线的人物不进画布，在图下方以紧凑网格列出（[GraphLayoutResult.isolated]）。
 * 网格人物支持**长按拖进画布**：松手落在画布上时回调 [onPlaceNode]，
 * 该人物此后作为自由节点渲染在画布上（位置由 positions 持久化）；
 * 双击画布上的这类节点回调 [onUnplaceNode] 放回网格。
 */
@Composable
fun RelationGraph(
    layoutResult: GraphLayoutResult,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    /** 用户拖出来的节点位移（dp，叠加在自动布局坐标上）。 */
    positions: Map<String, Offset> = emptyMap(),
    /** 拖拽中的增量回报（dp）。 */
    onNodeDrag: (id: String, delta: Offset) -> Unit = { _, _ -> },
    /** 一次拖拽结束（松手或取消），调用方在此持久化。 */
    onDragEnded: () -> Unit = {},
    /** 网格人物被拖进画布放下（dp 坐标为节点中心，内容坐标系）。 */
    onPlaceNode: (id: String, center: Offset) -> Unit = { _, _ -> },
    /** 画布上的「已放置」网格人物被双击，放回下方网格。 */
    onUnplaceNode: (id: String) -> Unit = {},
    onNodeClick: (Character) -> Unit,
) {
    val density = LocalDensity.current
    val colors = LocalKithColors.current

    // 图例只列这张图里真实出现过的关系类型，空类型不占位置
    val legendItems = remember(layoutResult) {
        layoutResult.edges.map { it.relation.kind }.distinct().map { kind ->
            kind.label to RelationGraphLayout.edgeColor(kind, colors)
        }
    }

    // 已拖进画布的无连线人物由下方「自由节点」区块单独渲染（带双击放回），
    // 布局结果里没有他们的坐标，位置完全由 positions 决定。

    // 拖拽手势的回调经 rememberUpdatedState 取最新值，避免 pointerInput 捕获旧闭包
    val currentOnDrag by rememberUpdatedState(onNodeDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnded)
    val currentOnPlace by rememberUpdatedState(onPlaceNode)
    val currentOnUnplace by rememberUpdatedState(onUnplaceNode)
    var draggingId by remember { mutableStateOf<String?>(null) }

    // ── 网格人物拖进画布 ──
    // 从网格长按起拖后，人物以「幽灵头像」跟随手指；松手落在画布区域则放置。
    // 坐标全部走 window 系（boundsInWindow），滚动偏移已经含在里面，不用再叠加。
    var canvasBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var rootBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var gridDrag by remember { mutableStateOf<GridDrag?>(null) }

    // 入场动画的统一时间轴。布局一变（切树状 / 链式、增删人物）就重放一遍。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(layoutResult) {
        enter.snapTo(0f)
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(ENTER_DURATION_MS, easing = LinearEasing),
        )
    }
    val progress = enter.value

    // 连线的排队步长：让最后一条线正好在动画结束时画完
    val edgeStagger = if (layoutResult.edges.size > 1) {
        ((1f - EDGE_ITEM_SPAN - EDGE_PHASE_START) / (layoutResult.edges.size - 1))
            .coerceAtLeast(0f)
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInWindow() },
    ) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        val contentW = with(density) { layoutResult.contentWidth.dp.toPx() }
        val contentH = with(density) { layoutResult.contentHeight.dp.toPx() }

        // 内容比视口大就允许双向滚动，并把内容居中
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val padX = ((viewportW - contentW) / 2f).coerceAtLeast(0f)
        val padY = ((viewportH - contentH) / 2f).coerceAtLeast(0f)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll),
            ) {
            Box(
                Modifier
                    .width(with(density) { contentW.toDp() } + with(density) { padX.toDp() } * 2)
                    .height(with(density) { contentH.toDp() } + with(density) { padY.toDp() } * 2)
                    .onGloballyPositioned { canvasBounds = it.boundsInWindow() },
            ) {
                // ── 连线层 ──
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    val nodeR = with(density) { (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx() }

                    // 节点实际坐标 = 自动布局 + 用户拖拽位移
                    fun px(n: GraphNode): Pair<Float, Float> {
                        val p = positions[n.character.id]
                        val x = with(density) { (n.x + (p?.x ?: 0f)).dp.toPx() } + padX
                        val y = with(density) { (n.y + (p?.y ?: 0f)).dp.toPx() } + padY
                        return x to y
                    }

                    layoutResult.edges.forEachIndexed { index, e ->
                        // 每条线按自己的排队位置算进度；还没轮到的直接跳过，不浪费绘制
                        val t = (
                            (progress - (EDGE_PHASE_START + index * edgeStagger)) / EDGE_ITEM_SPAN
                            ).coerceIn(0f, 1f)
                        if (t <= 0f) return@forEachIndexed
                        val eased = FastOutSlowInEasing.transform(t)

                        val (x1, y1) = px(e.from)
                        val (x2, y2) = px(e.to)

                        val color = RelationGraphLayout.edgeColor(e.relation.kind, colors)
                        // 亲密度 → 线宽，1..100 映射到 1.2..4.2dp
                        val w = (1.2f + e.relation.intensity / 100f * 3.0f)
                            .let { with(density) { it.dp.toPx() } }

                        // 从圆周到圆周，避免线穿过头像
                        val dx = x2 - x1
                        val dy = y2 - y1
                        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
                        val ux = dx / len
                        val uy = dy / len
                        val sx = x1 + ux * nodeR
                        val sy = y1 + uy * nodeR
                        val ex = x2 - ux * nodeR
                        val ey = y2 - uy * nodeR

                        // 略微弯曲，避免多条线完全重合时糊成一团
                        val bend = if (e.relation.kind.symmetric) 0.10f else 0.06f
                        val cx = (sx + ex) / 2f - uy * len * bend
                        val cy = (sy + ey) / 2f + ux * len * bend

                        // 把二次贝塞尔按参数 eased 截断，得到「正在被画出来」的效果。
                        // 用 de Casteljau 取子曲线：控制点各按 eased 向终点收缩一级。
                        val ax = sx + (cx - sx) * eased
                        val ay = sy + (cy - sy) * eased
                        val bx = cx + (ex - cx) * eased
                        val by = cy + (ey - cy) * eased
                        val endX = ax + (bx - ax) * eased
                        val endY = ay + (by - ay) * eased

                        val path = Path().apply {
                            moveTo(sx, sy)
                            quadraticBezierTo(ax, ay, endX, endY)
                        }
                        drawPath(
                            path = path,
                            color = color.copy(alpha = 0.55f * eased),
                            style = Stroke(width = w, cap = StrokeCap.Round),
                        )
                    }

                    // ── 选中节点的连线标注 ──
                    // 平时满屏都是字会淹掉图，所以只在选中某人时，
                    // 才把 TA 的每条连线在曲线中点标上关系名。
                    if (selectedId != null) {
                        val textPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.WHITE
                            textSize = with(density) { 10.sp.toPx() }
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        val bgPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.argb(222, 16, 18, 24)
                        }
                        layoutResult.edges
                            .filter {
                                it.from.character.id == selectedId ||
                                    it.to.character.id == selectedId
                            }
                            .forEach { e ->
                                val nodeR = with(density) { (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx() }
                                val (x1, y1) = px(e.from)
                                val (x2, y2) = px(e.to)

                                val dx = x2 - x1
                                val dy = y2 - y1
                                val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
                                val ux = dx / len
                                val uy = dy / len
                                val sx = x1 + ux * nodeR
                                val sy = y1 + uy * nodeR
                                val ex = x2 - ux * nodeR
                                val ey = y2 - uy * nodeR
                                val bend = if (e.relation.kind.symmetric) 0.10f else 0.06f
                                val cx = (sx + ex) / 2f - uy * len * bend
                                val cy = (sy + ey) / 2f + ux * len * bend
                                // 二次贝塞尔在 t=0.5 处的点 = (P0 + 2P1 + P2) / 4
                                val mx = (sx + 2 * cx + ex) / 4f
                                val my = (sy + 2 * cy + ey) / 4f

                                val text = e.relation.label
                                val tw = textPaint.measureText(text)
                                drawContext.canvas.nativeCanvas.apply {
                                    drawRoundRect(
                                        mx - tw / 2f - 10f, my - 22f,
                                        mx + tw / 2f + 10f, my + 12f,
                                        22f, 22f, bgPaint,
                                    )
                                    drawText(text, mx, my + 4f, textPaint)
                                }
                            }
                    }
                }

                // ── 节点层 ──
                layoutResult.nodes.forEachIndexed { index, node ->
                    val isSelected = node.character.id == selectedId
                    val isUser = node.character.isUser
                    val isDragging = draggingId == node.character.id
                    // 这个节点的入场进度：依次浮现，同时从 0.72 倍放大回原尺寸
                    val ent = FastOutSlowInEasing.transform(
                        stagedProgress(
                            global = progress,
                            index = index,
                            count = layoutResult.nodes.size,
                            phase = NODE_PHASE,
                            span = NODE_ITEM_SPAN,
                        ),
                    )
                    // 自动布局 + 用户拖拽位移
                    val pos = positions[node.character.id]
                    Column(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    with(density) { (node.x + (pos?.x ?: 0f)).dp.toPx() - (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx() + padX }.roundToInt(),
                                    with(density) { (node.y + (pos?.y ?: 0f)).dp.toPx() - (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx() + padY }.roundToInt(),
                                )
                            }
                            .width(with(density) { RelationGraphLayout.NODE_SIZE.dp })
                            .graphicsLayer {
                                alpha = ent
                                // 拖拽中的节点略放大，给出「拿起来了」的反馈
                                val s = (0.72f + 0.28f * ent) * if (isDragging) 1.12f else 1f
                                scaleX = s
                                scaleY = s
                                // 以头像为中心缩放 —— 头像下面还挂着姓名，
                                // 默认以整个 Column 中心缩放会让姓名先飘出来
                                transformOrigin = TransformOrigin(0.5f, 0.38f)
                            }
                            .pointerInput(node.character.id) {
                                detectTapGestures(
                                    onTap = { onNodeClick(node.character) },
                                )
                            }
                            .pointerInput(node.character.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = node.character.id
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dp = with(density) {
                                            Offset(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                                        }
                                        currentOnDrag(node.character.id, dp)
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        currentDragEnd()
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        currentDragEnd()
                                    },
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CharacterAvatar(
                                name = node.character.name,
                                size = RelationGraphLayout.NODE_SIZE.dp,
                                avatarUrl = node.character.avatarUrl,
                                importance = node.character.importance,
                                isUser = isUser,
                                modifier = if (isSelected) Modifier.alpha(1f) else Modifier,
                            )
                            // 用户与重要人物顶一个小标记，一眼区分出「能聊天的人」
                            if (isUser || node.character.importance == Importance.LEAD) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 2.dp, y = 2.dp)
                                        .background(
                                            if (isUser) colors.selfNode else node.character.importance.color(),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                        )
                                        .width(12.dp)
                                        .height(12.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = node.character.name,
                            fontSize = 11.sp,
                            fontWeight = if (isUser) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.width(with(density) { (RelationGraphLayout.NODE_SIZE + 18f).dp }),
                        )
                    }
                }

                // ── 已从下方网格拖进画布的无关系人物 ──
                // 布局计算没给这些人物坐标（他们没连线），所以渲染成「自由节点」：
                // 位置完全由 positions 决定，拖拽/选中行为与普通节点一致。
                // 双击放回下方网格。
                layoutResult.isolated.forEach { c ->
                    val pos = positions[c.id] ?: return@forEach
                    Column(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    with(density) {
                                        pos.x.dp.toPx() + padX -
                                            (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx()
                                    }.roundToInt(),
                                    with(density) {
                                        pos.y.dp.toPx() + padY -
                                            (RelationGraphLayout.NODE_SIZE / 2f).dp.toPx()
                                    }.roundToInt(),
                                )
                            }
                            .width(with(density) { RelationGraphLayout.NODE_SIZE.dp })
                            .pointerInput(c.id) {
                                detectTapGestures(
                                    onTap = { onNodeClick(c) },
                                    onDoubleTap = { currentOnUnplace(c.id) },
                                )
                            }
                            .pointerInput(c.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingId = c.id },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dp = with(density) {
                                            Offset(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                                        }
                                        currentOnDrag(c.id, dp)
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        currentDragEnd()
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        currentDragEnd()
                                    },
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CharacterAvatar(
                            name = c.name,
                            size = RelationGraphLayout.NODE_SIZE.dp,
                            avatarUrl = c.avatarUrl,
                            importance = c.importance,
                            isUser = c.isUser,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = c.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.width(with(density) { (RelationGraphLayout.NODE_SIZE + 18f).dp }),
                        )
                    }
                }

                // ── 图例：解释每种颜色的连线代表什么关系 ──
                var legendOpen by remember { mutableStateOf(true) }
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(6.dp),
                ) {
                    Row(
                        Modifier
                            .clickable { legendOpen = !legendOpen }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (legendOpen) "▾ 图例" else "▸ 图例",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (legendOpen) {
                        legendItems.forEach { (label, color) ->
                            Row(
                                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 线段样块，和画布上的连线视觉一致
                                Box(
                                    Modifier
                                        .width(18.dp)
                                        .height(3.dp)
                                        .background(color, CircleShape),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            }

            // ── 未连线人物网格 ──
            // 这些人和社会里谁都没有关系，画进图里只会是一盘散沙。
            // 以紧凑头像网格列在图下方，点按同样能看资料、发消息。
            // 长按头像拖到上方画布里松手，即可把 TA 钉进画布（双击画布里的
            // 这类头像可放回网格）。
            val gridChars = layoutResult.isolated.filterNot { positions.containsKey(it.id) }
            if (gridChars.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "未连线人物 · ${gridChars.size}（长按头像可拖到图上）",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    gridChars.chunked(5).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowItems.forEach { c ->
                                var itemBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { itemBounds = it.boundsInWindow() }
                                        .pointerInput(c.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { start ->
                                                    val b = itemBounds ?: return@detectDragGesturesAfterLongPress
                                                    val p = b.topLeft + start
                                                    gridDrag = GridDrag(c, p, p)
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    gridDrag?.let { gridDrag = it.copy(pos = it.pos + amount) }
                                                },
                                                onDragEnd = {
                                                    val d = gridDrag
                                                    gridDrag = null
                                                    val cb = canvasBounds
                                                    if (d != null && cb != null) {
                                                        val pxToDp = { v: Float -> v / density.density }
                                                        val moved = pxToDp(
                                                            (d.pos - d.start).getDistance(),
                                                        )
                                                        // 容错一：松手点略微偏出画布也算命中
                                                        // （画布边缘附近手指容易抖出去）
                                                        val slack = 32f * density.density
                                                        val hit = cb.inflate(slack).contains(d.pos)
                                                        // 容错二：长按后几乎没移动手指就松手 ——
                                                        // 多半是「我想把 TA 放上去」而不是取消，
                                                        // 这时直接落到画布里，别让操作石沉大海。
                                                        if (hit || moved < 24f) {
                                                            val px = if (hit) d.pos.x else cb.center.x
                                                            val py = if (hit) d.pos.y else cb.center.y
                                                            // 画布 Box 内的 px → 内容 dp 坐标（去掉居中留白）
                                                            currentOnPlace(
                                                                d.character.id,
                                                                Offset(
                                                                    pxToDp(px - cb.left) - pxToDp(padX),
                                                                    pxToDp(py - cb.top) - pxToDp(padY),
                                                                ),
                                                            )
                                                        }
                                                    }
                                                },
                                                onDragCancel = { gridDrag = null },
                                            )
                                        },
                                ) {
                                    CharacterAvatar(
                                        name = c.name,
                                        size = 40.dp,
                                        avatarUrl = c.avatarUrl,
                                        importance = c.importance,
                                        isUser = c.isUser,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = c.name,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            // 不满一行时补空位，保持格子对齐
                            repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // ── 拖拽幽灵：从网格往画布拖时的跟手预览 ──
        gridDrag?.let { d ->
            val rb = rootBounds
            if (rb != null) {
                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (d.pos.x - rb.left).roundToInt() - 22.dp.toPx().roundToInt(),
                                (d.pos.y - rb.top).roundToInt() - 22.dp.toPx().roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            alpha = 0.92f
                            scaleX = 1.1f
                            scaleY = 1.1f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CharacterAvatar(
                        name = d.character.name,
                        size = 44.dp,
                        avatarUrl = d.character.avatarUrl,
                        importance = d.character.importance,
                        isUser = d.character.isUser,
                    )
                }
            }
        }
    }
}
