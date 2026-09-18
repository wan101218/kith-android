package com.kith.app.data.store

import com.kith.app.core.Ids
import com.kith.app.core.arr
import com.kith.app.core.extractJsonBlock
import com.kith.app.domain.Character
import com.kith.app.domain.CharacterTag
import com.kith.app.domain.Gender
import com.kith.app.domain.Importance
import com.kith.app.domain.toCharacter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 人物文件的导入解析。
 *
 * 支持两种来源，自动识别、不需要用户选格式：
 *
 *  1. **Kith 自家格式** —— 单个人物对象、人物数组、或带 `characters` 字段的存档；
 *  2. **喵咚平台的角色卡** —— 单卡响应（`{"code":200,"data":{...}}`）、
 *     列表响应（`{"data":{"list":[...]}}`）、或裸的卡对象数组。
 *
 * 字段映射（喵咚 → Kith）：
 * ```
 * name        → name
 * gender      → gender
 * tagList     → tags（标签直接沿用，builtin=false）
 * introduction→ personality   人设正文本来就是给模型看的设定
 * openingLine → openingLine   开场白是独立字段，作为空会话的第一条消息
 * ```
 *
 * 这样用户手上的角色卡语料不用改一个字就能进 app。
 */
object CharacterImporter {

    /**
     * 解析一段 JSON，返回 `(人物列表, 跳过数)`。
     * 跳过的原因通常是：缺少名字、或者不是人物对象。
     */
    fun import(raw: String): Pair<List<Character>, Int> {
        val text = (extractJsonBlock(raw) ?: raw.trim()).trim()
        if (text.isEmpty()) error("文件是空的，没有可导入的内容")

        // 数组根也要能吃：用户最常导出的就是「一排人物」的数组文件
        val cards = if (text.startsWith("[")) {
            objectsOf(runCatching { JSONArray(text) }.getOrElse { e ->
                error("这个文件不是合法的 JSON：${e.message ?: "解析失败"}")
            })
        } else {
            val root = runCatching { JSONObject(text) }.getOrElse { e ->
                error("这个文件不是合法的 JSON：${e.message ?: "解析失败"}")
            }
            extractCards(root)
        }
        if (cards.isEmpty()) {
            error(
                "这个 JSON 里找不到人物卡。\n\n" +
                    "支持两种格式：Kith 的人物数组，或喵咚的角色卡" +
                    "（含 name / introduction / openingLine / tagList 字段）。",
            )
        }

        val out = ArrayList<Character>(cards.size)
        var skipped = 0
        cards.forEach { card ->
            mapCard(card)?.let { out += it } ?: run { skipped++ }
        }
        if (out.isEmpty()) error("解析到了 ${cards.size} 个对象，但没有一个是有效的人物卡")
        return out to (cards.size - out.size)
    }

    // ── 抽取 ────────────────────────────────────────────────────────────────

    /**
     * 从任意一层包裹里把人物卡对象挖出来。
     * 依次尝试：`data.list` / `data`（单卡）/ `characters` / `list` / 顶层数组 / 顶层单卡。
     */
    private fun extractCards(root: JSONObject): List<JSONObject> {
        root.optJSONObject("data")?.let { d ->
            d.arr("list").let { a ->
                val list = objectsOf(a)
                if (list.isNotEmpty()) return list
            }
            if (d.has("name")) return listOf(d)
        }
        objectsOf(root.arr("characters")).let { if (it.isNotEmpty()) return it }
        objectsOf(root.arr("list")).let { if (it.isNotEmpty()) return it }
        if (root.has("name")) return listOf(root)
        return emptyList()
    }

    /** 收集数组里所有「看起来像人物卡」的对象。 */
    private fun objectsOf(a: JSONArray): List<JSONObject> =
        (0 until a.length()).mapNotNull { idx ->
            a.optJSONObject(idx)?.takeIf { it.has("name") }
        }

    // ── 映射 ────────────────────────────────────────────────────────────────

    private fun mapCard(c: JSONObject): Character? {
        // 已经是 Kith 格式的直接走自家解码
        if (c.has("personality") && c.has("importance")) {
            return runCatching { c.toCharacter() }.getOrNull()
                ?.copy(id = Ids.new("chr"), updatedAt = System.currentTimeMillis())
        }

        val name = c.optString("name").trim().ifEmpty { return null }
        val intro = c.optString("introduction").trim()
        val opening = c.optString("openingLine").trim()

        val tagNames = c.arr("tagList").let { a ->
            (0 until a.length()).mapNotNull { idx ->
                val t = a.optJSONObject(idx)
                t?.optString("name")?.takeIf { it.isNotEmpty() }
            }
        }

        val now = System.currentTimeMillis()
        return Character(
            id = Ids.new("chr"),
            name = name,
            gender = when (c.optString("gender").uppercase()) {
                "MALE" -> Gender.MALE
                "FEMALE" -> Gender.FEMALE
                else -> Gender.UNKNOWN
            },
            // 一句话简介用标签拼出来 —— 比起空着，至少在关系图和列表里有辨识度
            oneLiner = tagNames.take(4).joinToString(" · ").ifEmpty { "（尚未填写简介）" },
            // 喵咚的 introduction 本身就是给模型看的人设文本，放 personality 正好
            personality = intro,
            openingLine = opening,
            tags = tagNames.map { CharacterTag(label = it, hint = "", builtin = false) },
            importance = Importance.SUPPORTING,
            createdAt = now,
            updatedAt = now,
        )
    }
}
