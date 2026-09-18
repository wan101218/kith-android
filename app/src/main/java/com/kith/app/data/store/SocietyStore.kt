package com.kith.app.data.store

import android.content.Context
import com.kith.app.core.Ids
import com.kith.app.core.arr
import com.kith.app.core.extractJsonBlock
import com.kith.app.core.obj
import com.kith.app.domain.Character
import com.kith.app.domain.ChatMessage
import com.kith.app.domain.ChatThread
import com.kith.app.domain.LogEntry
import com.kith.app.domain.MsgRole
import com.kith.app.domain.MsgStatus
import com.kith.app.domain.PlotState
import com.kith.app.domain.Relation
import com.kith.app.domain.Society
import com.kith.app.domain.Sticker
import com.kith.app.domain.toCharacterList
import com.kith.app.domain.toJson
import com.kith.app.domain.toJsonArray
import com.kith.app.domain.toLogEntry
import com.kith.app.domain.toMessageList
import com.kith.app.domain.toPlotState
import com.kith.app.domain.toRelationList
import com.kith.app.domain.toSociety
import com.kith.app.domain.toStickerList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个社会的完整内存视图。
 * 关系图、聊天、旁白全都基于它工作。
 */
data class SocietyBundle(
    val society: Society,
    val characters: List<Character> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val plot: PlotState = PlotState(),
    val stickers: List<Sticker> = emptyList(),
) {
    fun character(id: String?): Character? =
        if (id == null) null else characters.firstOrNull { it.id == id }

    /** 用户本人（isUser）扮演的角色。 */
    val userCharacter: Character? get() = characters.firstOrNull { it.isUser }

    /** 与某人直接相连的所有关系。 */
    fun relationsOf(charId: String): List<Relation> =
        relations.filter { it.fromId == charId || it.toId == charId }

    /** 关系图上的连通分量数量，用于状态栏展示「孤立人物」提示。 */
    fun isolatedCount(): Int {
        val linked = relations.flatMap { listOf(it.fromId, it.toId) }.toSet()
        return characters.count { it.id !in linked && !it.isUser }
    }
}

/** 社会列表页用的轻量摘要，避免为了画列表把所有社会的全部数据都读进内存。 */
data class SocietySummary(
    val id: String,
    val name: String,
    val orientationCode: String,
    val orientationLabel: String,
    val characterCount: Int,
    val relationCount: Int,
    val updatedAt: Long,
    val coverSeed: Int,
    val archived: Boolean,
    /** 自定义封面图（media/ 下的文件名），空 = 程序化渐变 */
    val coverImage: String = "",
)

/**
 * 社会仓储 —— 每个社会一个独立文件夹。
 *
 * 目录布局：
 * ```
 * filesDir/societies/
 *   index.json                   社会索引（列表页快速加载）
 *   {societyId}/
 *     society.json               社会设定、名称、世界观、剧情走向、模型配置
 *     characters.json            人物
 *     relations.json             关系链
 *     plot.json                  当前剧情状态与已发生的剧情节点
 *     chats/{characterId}.json   与每个角色的独立会话
 *     logs.jsonl                 追加式全量日志（旁白 + 所有模型行为）
 *     stickers.json              本社会表情包库
 *     media/                     头像、聊天图片、表情包文件
 * ```
 *
 * 为什么要「一个社会一个文件夹」而不放一张大表：
 *  1. 产品硬性要求社会之间完全隔离，物理隔离是最不容易出错的实现；
 *  2. 导出/导入天然就是「打包/解包一个目录」，不需要写迁移逻辑；
 *  3. 单个社会损坏不会波及其他社会；
 *  4. 用户可以直接在文件管理器里备份某个社会。
 *
 * 写入一律走 [writeAtomic]（先写 .tmp 再 rename），避免写到一半被系统杀掉
 * 导致存档半截 —— 对用户来说，丢失一个精心配置的社会是不可接受的。
 */
class SocietyStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "societies")

    fun societyDir(societyId: String): File = File(root, societyId)

    fun mediaDir(societyId: String): File =
        File(societyDir(societyId), "media").apply { mkdirs() }

    /** 保存一张图片到社会自己的 media 目录，返回文件路径。 */
    fun saveMedia(societyId: String, bytes: ByteArray, ext: String = "png"): File {
        val dir = mediaDir(societyId)
        val f = File(dir, "img_${System.currentTimeMillis()}_${Ids.stableSeed(bytes.size.toString())}.$ext")
        f.writeBytes(bytes)
        return f
    }

    // ── 索引 ────────────────────────────────────────────────────────────────

    private val indexFile: File get() = File(root, "index.json")

    suspend fun list(): List<SocietySummary> = withContext(Dispatchers.IO) {
        val raw = if (indexFile.exists()) indexFile.readText() else null
        if (raw.isNullOrBlank()) return@withContext emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return@withContext emptyList()
        val list = (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            runCatching {
                SocietySummary(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    orientationCode = o.optString("orientationCode", "bg"),
                    orientationLabel = o.optString("orientationLabel"),
                    characterCount = o.optInt("characterCount"),
                    relationCount = o.optInt("relationCount"),
                    updatedAt = o.optLong("updatedAt"),
                    coverSeed = o.optInt("coverSeed"),
                    archived = o.optBoolean("archived"),
                    coverImage = o.optString("coverImage"),
                )
            }.getOrNull()
        }
        // 索引与磁盘实际的目录取交集，防止用户手工删了目录后列表里还挂着幽灵条目
        list.filter { societyDir(it.id).exists() }
            .sortedByDescending { it.updatedAt }
    }

    private suspend fun refreshIndex(summary: SocietySummary, remove: Boolean = false) {
        withContext(Dispatchers.IO) {
            val existing = list().toMutableList()
            existing.removeAll { it.id == summary.id }
            if (!remove) existing.add(summary)
            val arr = JSONArray()
            existing.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("orientationCode", s.orientationCode)
                        .put("orientationLabel", s.orientationLabel)
                        .put("characterCount", s.characterCount)
                        .put("relationCount", s.relationCount)
                        .put("updatedAt", s.updatedAt)
                        .put("coverSeed", s.coverSeed)
                        .put("coverImage", s.coverImage)
                        .put("archived", s.archived),
                )
            }
            root.mkdirs()
            writeAtomic(indexFile, arr.toString())
        }
    }

    // ── 读写 ────────────────────────────────────────────────────────────────

    suspend fun load(societyId: String): SocietyBundle? = withContext(Dispatchers.IO) {
        val dir = societyDir(societyId)
        if (!dir.exists()) return@withContext null
        val society = readJson(File(dir, "society.json"))
            ?.let { runCatching { it.toSociety() }.getOrNull() }
            ?: return@withContext null

        SocietyBundle(
            society = society,
            characters = readArray(File(dir, "characters.json")).toCharacterList(),
            relations = readArray(File(dir, "relations.json")).toRelationList(),
            plot = readJson(File(dir, "plot.json"))
                ?.let { runCatching { it.toPlotState() }.getOrNull() } ?: PlotState(),
            stickers = readArray(File(dir, "stickers.json")).toStickerList(),
        )
    }

    suspend fun saveSociety(society: Society) = withContext(Dispatchers.IO) {
        val dir = societyDir(society.id).apply { mkdirs() }
        writeAtomic(File(dir, "society.json"), society.toJson().toString())
        refreshIndex(
            SocietySummary(
                id = society.id,
                name = society.name,
                orientationCode = society.orientation.code,
                orientationLabel = society.orientation.label,
                characterCount = readArray(File(dir, "characters.json")).length(),
                relationCount = readArray(File(dir, "relations.json")).length(),
                updatedAt = society.updatedAt,
                coverSeed = society.coverSeed,
                archived = society.archived,
                coverImage = society.coverImage,
            ),
        )
    }

    suspend fun saveCharacters(societyId: String, characters: List<Character>) =
        withContext(Dispatchers.IO) {
            val dir = societyDir(societyId).apply { mkdirs() }
            writeAtomic(
                File(dir, "characters.json"),
                characters.toJsonArray { it.toJson() }.toString(),
            )
            bumpIndex(societyId)
        }

    suspend fun saveRelations(societyId: String, relations: List<Relation>) =
        withContext(Dispatchers.IO) {
            val dir = societyDir(societyId).apply { mkdirs() }
            writeAtomic(
                File(dir, "relations.json"),
                relations.toJsonArray { it.toJson() }.toString(),
            )
            bumpIndex(societyId)
        }

    suspend fun savePlot(societyId: String, plot: PlotState) = withContext(Dispatchers.IO) {
        val dir = societyDir(societyId).apply { mkdirs() }
        writeAtomic(File(dir, "plot.json"), plot.toJson().toString())
    }

    suspend fun saveStickers(societyId: String, stickers: List<Sticker>) =
        withContext(Dispatchers.IO) {
            val dir = societyDir(societyId).apply { mkdirs() }
            writeAtomic(
                File(dir, "stickers.json"),
                stickers.toJsonArray { it.toJson() }.toString(),
            )
        }

    // ── 关系图的节点位置（layout.json，用户长按拖拽的产物）──────────────────

    /**
     * 读用户手动拖出来的节点位置。没有文件或字段缺失时返回空表 ——
     * 这是可选数据，缺了就回退到自动布局，不报错。
     */
    suspend fun loadNodePositions(societyId: String): Map<String, List<Float>> =
        withContext(Dispatchers.IO) {
            val dir = societyDir(societyId)
            val root = readJson(File(dir, "layout.json")) ?: return@withContext emptyMap()
            val nodes = root.optJSONObject("nodes") ?: return@withContext emptyMap()
            buildMap {
                nodes.keys().forEach { id ->
                    val p = nodes.optJSONArray(id) ?: return@forEach
                    if (p.length() >= 2) {
                        put(id, listOf(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
                    }
                }
            }
        }

    /** 保存节点位置。只在拖拽松手时写一次，不跟手（拖动过程只改内存）。 */
    suspend fun saveNodePositions(societyId: String, positions: Map<String, List<Float>>) =
        withContext(Dispatchers.IO) {
            val dir = societyDir(societyId).apply { mkdirs() }
            val nodes = JSONObject()
            positions.forEach { (id, xy) ->
                if (xy.size >= 2) nodes.put(id, JSONArray().put(xy[0]).put(xy[1]))
            }
            writeAtomic(File(dir, "layout.json"), JSONObject().put("nodes", nodes).toString())
        }

    /** 更新索引里的计数与时间戳。 */
    private suspend fun bumpIndex(societyId: String) {
        val dir = societyDir(societyId)
        val society = readJson(File(dir, "society.json"))
            ?.let { runCatching { it.toSociety() }.getOrNull() } ?: return
        refreshIndex(
            SocietySummary(
                id = society.id,
                name = society.name,
                orientationCode = society.orientation.code,
                orientationLabel = society.orientation.label,
                characterCount = readArray(File(dir, "characters.json")).length(),
                relationCount = readArray(File(dir, "relations.json")).length(),
                updatedAt = System.currentTimeMillis(),
                coverSeed = society.coverSeed,
                archived = society.archived,
                coverImage = society.coverImage,
            ),
        )
    }

    // ── 会话（每个角色一条，彼此闭塞）────────────────────────────────────────

    private fun chatFile(societyId: String, charId: String): File =
        File(File(societyDir(societyId), "chats"), "${Ids.safeFileName(charId)}.json")

    suspend fun loadThread(societyId: String, charId: String): ChatThread =
        withContext(Dispatchers.IO) {
            val arr = readArray(chatFile(societyId, charId))
            val messages = arr.toMessageList()
            // 历史污染清洗：渠道故障/思维模型曾把字面 "null"（常是 nullnullnull
            // 连串）落库。这类 AI 消息既显示为 null 气泡，又会作为历史喂回模型
            // 造成连锁污染 —— 读取时直接剔除并回写，一次清洗永久生效。
            val cleaned = messages.filter { m ->
                !(m.role == MsgRole.CHARACTER && m.status == MsgStatus.DONE &&
                    (m.raw.contains("nullnull", ignoreCase = true) ||
                        m.raw.trim().equals("null", ignoreCase = true) ||
                        m.raw.isBlank()))
            }
            if (cleaned.size != messages.size) {
                writeAtomic(
                    chatFile(societyId, charId),
                    cleaned.toJsonArray { it.toJson() }.toString(),
                )
            }
            ChatThread(charId = charId, messages = cleaned, updatedAt = 0L)
        }

    suspend fun saveThread(societyId: String, thread: ChatThread) = withContext(Dispatchers.IO) {
        val dir = File(societyDir(societyId), "chats").apply { mkdirs() }
        writeAtomic(
            File(dir, "${Ids.safeFileName(thread.charId)}.json"),
            thread.messages.toJsonArray { it.toJson() }.toString(),
        )
    }

    suspend fun appendMessage(societyId: String, charId: String, message: ChatMessage) =
        withContext(Dispatchers.IO) {
            val thread = loadThread(societyId, charId)
            saveThread(societyId, thread.copy(messages = thread.messages + message, updatedAt = message.ts))
        }

    /** 列出该社会下已经有会话的角色 id。 */
    suspend fun chatCharacterIds(societyId: String): Set<String> = withContext(Dispatchers.IO) {
        File(societyDir(societyId), "chats").listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.takeIf { n -> n.isNotEmpty() } }
            ?.toSet()
            ?: emptySet()
    }

    // ── 日志（追加式）────────────────────────────────────────────────────────

    private fun logFile(societyId: String): File = File(societyDir(societyId), "logs.jsonl")

    suspend fun appendLog(societyId: String, entry: LogEntry) = withContext(Dispatchers.IO) {
        val dir = societyDir(societyId).apply { mkdirs() }
        runCatching {
            File(dir, "logs.jsonl").appendText(entry.toJson().toString() + "\n")
        }
    }

    suspend fun appendLogs(societyId: String, entries: List<LogEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val dir = societyDir(societyId).apply { mkdirs() }
        runCatching {
            File(dir, "logs.jsonl").appendText(
                entries.joinToString("") { it.toJson().toString() + "\n" },
            )
        }
    }

    /**
     * 读取日志。文件是流式追加的，可能很大，这里只解析末尾 [limit] 行。
     * 倒序返回（最新在前）。
     */
    suspend fun readLogs(societyId: String, limit: Int = 300): List<LogEntry> =
        withContext(Dispatchers.IO) {
            val f = logFile(societyId)
            if (!f.exists()) return@withContext emptyList()
            val lines = f.readLines()
            lines.takeLast(limit)
                .mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { JSONObject(line).toLogEntry() }.getOrNull()
                }
                .reversed()
        }

    // ── 删除 ────────────────────────────────────────────────────────────────

    /**
     * 删除一个社会。
     * 注意：[refreshIndex] 只移除索引条目，磁盘目录由调用方确认后删除。
     * 这里会真的删除目录，因此 UI 侧必须先做二次确认。
     */
    suspend fun delete(societyId: String) = withContext(Dispatchers.IO) {
        val dir = societyDir(societyId)
        val stub = SocietySummary(societyId, "", "bg", "", 0, 0, 0L, 0, false)
        refreshIndex(stub, remove = true)
        dir.deleteRecursively()
    }

    // ── 导出 / 导入 ──────────────────────────────────────────────────────────

    /**
     * 导出一个社会的完整存档。
     *
     * 格式为单个 JSON，包含：社会设定、人物、关系、剧情、所有会话、表情包、日志。
     * 这是产品明确要求的「支持导出该社会，包括社会设定、人物、当前走向」。
     */
    suspend fun exportArchive(societyId: String): String? = withContext(Dispatchers.IO) {
        val bundle = load(societyId) ?: return@withContext null
        val dir = societyDir(societyId)

        val chats = JSONObject()
        File(dir, "chats").listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { f ->
                val msgs = readArray(f).toMessageList()
                if (msgs.isNotEmpty()) {
                    chats.put(f.nameWithoutExtension, msgs.toJsonArray { it.toJson() })
                }
            }

        val logs = JSONArray()
        logFile(societyId).takeIf { it.exists() }?.readLines()?.forEach { line ->
            if (line.isNotBlank()) {
                runCatching { logs.put(JSONObject(line)) }
            }
        }

        val archive = JSONObject()
            .put("schema", ARCHIVE_SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("appVersion", "Kith 0.1.0")
            .put("society", bundle.society.toJson())
            .put("characters", bundle.characters.toJsonArray { it.toJson() })
            .put("relations", bundle.relations.toJsonArray { it.toJson() })
            .put("plot", bundle.plot.toJson())
            .put("stickers", bundle.stickers.toJsonArray { it.toJson() })
            .put("chats", chats)
            .put("logs", logs)

        // 自定义封面图内嵌进存档（base64）—— 封面文件在 media/ 里，
        // 纯 JSON 存档不带它的话，导入后封面就丢了
        bundle.society.coverImage.takeIf { it.isNotEmpty() }?.let { name ->
            val f = File(mediaDir(societyId), name)
            if (f.exists()) {
                archive.put(
                    "coverImageB64",
                    android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP),
                )
            }
        }

        return@withContext archive.toString(2)
    }

    /**
     * 把「用户以为能导入的东西」归一成标准存档结构。
     *
     * 实测最容易踩的两个坑：
     *  1. 选了 `index.json` —— 那是**社会列表索引**，是个数组。原来的代码会直接
     *     抛一句 JSON 解析异常，用户完全不知道发生了什么。
     *  2. 选了社会目录里的 `society.json` —— 它本身是完全合法的社会设定，
     *     只是没有存档的外层包装（缺 characters / relations / chats 等）。
     *
     * 所以这里做三件事：拦住数组、把裸的社会设定对象包成存档、报错时指名道姓
     * 告诉用户该选什么文件。
     */
    private fun normalizeArchive(raw: String): JSONObject {
        val text = (extractJsonBlock(raw) ?: raw.trim()).trim()
        if (text.isEmpty()) error("文件是空的，没有可导入的内容")

        if (text.startsWith("[")) {
            error(
                "这是一份「社会列表索引」，不是可以导入的存档。\n\n" +
                    "要导入的应该是「导出社会」时生成的 *.kith.json 文件——" +
                    "它的顶层包含 society、characters、relations、chats 等字段。",
            )
        }

        val obj = runCatching { JSONObject(text) }.getOrElse { e ->
            error("这个文件不是合法的 JSON：${e.message ?: "解析失败"}")
        }

        if (obj.optJSONObject("society") != null) return obj

        // 裸的社会设定对象：等价于直接把 society.json 丢进来。
        // 判定条件是「像一份社会设定」，而不是「像一份存档」。
        val looksLikeSociety =
            obj.optString("id").isNotEmpty() ||
                (obj.has("name") && obj.has("worldSetting"))

        if (looksLikeSociety) {
            return JSONObject()
                .put("society", obj)
                .put("characters", JSONArray())
                .put("relations", JSONArray())
                .put("plot", JSONObject())
                .put("stickers", JSONArray())
                .put("chats", JSONObject())
                .put("logs", JSONArray())
        }

        error(
            "这个 JSON 里找不到社会数据。\n\n" +
                "可导入的是「导出社会」生成的存档文件（顶层含 society / characters / relations）。" +
                "如果你手上是社会目录里的某个文件，请改用导出的 *.kith.json。",
        )
    }

    /**
     * 导入一个社会存档。
     *
     * 关键点：**总是分配新的 societyId**，所有内部引用（人物 id、关系端点、
     * 会话归属）同步重映射。这样重复导入同一个存档不会互相踩，也能把别人分享的
     * 社会安全地并进自己的列表。
     */
    suspend fun importArchive(raw: String): Result<Society> = runCatching {
        val root = normalizeArchive(raw)
        val societyObj = root.obj("society")

        withContext(Dispatchers.IO) {
            val newId = Ids.new("soc")
            val dir = societyDir(newId).apply { mkdirs() }

            val placeholder = societyObj.toSociety()
            val idMap = HashMap<String, String>()
            placeholder.charactersIdsForRemap(root).forEach { old ->
                idMap[old] = Ids.new("chr")
            }

            val society = placeholder.copy(
                id = newId,
                name = placeholder.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )

            // 还原内嵌的封面图：存档里的 coverImage 是**旧社会** media/ 的文件名，
            // 在新社会里重新落一份文件并指向它
            var coverImage = society.coverImage
            val coverB64 = root.optString("coverImageB64")
            if (coverB64.isNotEmpty()) {
                runCatching {
                    val bytes = android.util.Base64.decode(coverB64, android.util.Base64.NO_WRAP)
                    if (bytes.isNotEmpty()) {
                        val ext = coverImage.substringAfterLast('.', "jpg")
                        coverImage = saveMedia(newId, bytes, ext).name
                    }
                }
            }
            val societyWithCover = society.copy(coverImage = coverImage)

            val characters = root.arr("characters").toCharacterList().map { c ->
                c.copy(id = idMap[c.id] ?: c.id)
            }
            val relations = root.arr("relations").toRelationList().mapNotNull { r ->
                val from = idMap[r.fromId] ?: return@mapNotNull null
                val to = idMap[r.toId] ?: return@mapNotNull null
                r.copy(id = Ids.new("rel"), fromId = from, toId = to)
            }
            val plot = root.optJSONObject("plot")?.toPlotState() ?: PlotState()
            val stickers = root.arr("stickers").toStickerList()

            writeAtomic(File(dir, "society.json"), societyWithCover.toJson().toString())
            writeAtomic(File(dir, "characters.json"), characters.toJsonArray { it.toJson() }.toString())
            writeAtomic(File(dir, "relations.json"), relations.toJsonArray { it.toJson() }.toString())
            writeAtomic(File(dir, "plot.json"), plot.toJson().toString())
            writeAtomic(File(dir, "stickers.json"), stickers.toJsonArray { it.toJson() }.toString())

            // 会话
            root.optJSONObject("chats")?.let { chats ->
                val chatDir = File(dir, "chats").apply { mkdirs() }
                chats.keys().forEach { oldCharId ->
                    val newCharId = idMap[oldCharId] ?: return@forEach
                    val msgs = chats.arr(oldCharId).toMessageList().map { m ->
                        m.copy(id = Ids.new("msg"), charId = m.charId?.let { idMap[it] ?: it })
                    }
                    writeAtomic(
                        File(chatDir, "${Ids.safeFileName(newCharId)}.json"),
                        msgs.toJsonArray { it.toJson() }.toString(),
                    )
                }
            }

            // 日志
            root.optJSONArray("logs")?.let { logs ->
                val sb = StringBuilder()
                for (idx in 0 until logs.length()) {
                    val o = logs.optJSONObject(idx) ?: continue
                    val actor = o.optString("actor")
                    val remapped = JSONObject(o.toString())
                    if (actor.isNotEmpty() && idMap.containsKey(actor)) {
                        remapped.put("actor", idMap[actor])
                    }
                    sb.append(remapped).append('\n')
                }
                if (sb.isNotEmpty()) writeAtomic(logFile(newId), sb.toString())
            }

            refreshIndex(
                SocietySummary(
                    id = newId,
                    name = societyWithCover.name,
                    orientationCode = societyWithCover.orientation.code,
                    orientationLabel = societyWithCover.orientation.label,
                    characterCount = characters.size,
                    relationCount = relations.size,
                    updatedAt = societyWithCover.updatedAt,
                    coverSeed = societyWithCover.coverSeed,
                    archived = societyWithCover.archived,
                    coverImage = societyWithCover.coverImage,
                ),
            )
            societyWithCover
        }
    }

    private fun Society.charactersIdsForRemap(root: JSONObject): List<String> {
        val arr = root.optJSONArray("characters") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
    }

    // ── 底层 IO ──────────────────────────────────────────────────────────────

    /** 先写临时文件再 rename，避免半截写入损坏存档。 */
    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 失败（跨卷或占用）时退回直接覆盖，至少保证数据落盘
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun readJson(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun readArray(file: File): JSONArray {
        if (!file.exists()) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrNull() ?: JSONArray()
    }

    companion object {
        const val ARCHIVE_SCHEMA = "kith.society/1"
    }
}
