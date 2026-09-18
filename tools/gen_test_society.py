#!/usr/bin/env python3
"""生成一个用于测试的 Kith 社会存档。

产出与 SocietyStore 的磁盘布局完全一致：
  <out>/societies/index.json
  <out>/societies/<societyId>/society.json | characters.json | relations.json
                            /plot.json | stickers.json | logs.jsonl | chats/*.json

这样既可以用 `adb run-as` 直接写进应用私有目录，也可以打包成单文件 JSON 走应用内的导入。
"""
from __future__ import annotations

import json
import time
from pathlib import Path

NOW = int(time.time() * 1000)
DAY = 86_400_000
MIN = 60_000

SOCIETY_ID = "soc_chengnan"

# ── 人物 id ────────────────────────────────────────────────────────────────
LINWAN = "chr_linwan"        # 用户本人
ZHOUYE = "chr_zhouye"
ZHENG = "chr_zhengzhiqiu"
GAOMING = "chr_gaoming"
CHEN = "chr_chensufen"
LUO = "chr_luomeijuan"
SHEN = "chr_shenxiu"


def tag(label: str, hint: str) -> dict:
    return {"label": label, "hint": hint, "builtin": True}


# 标签的 hint 与应用内 TagTemplates 保持一致，避免离线写入的数据和界面里的说明对不上
TAG_HINTS = {
    "暧昧对象": "你们处在还没挑明的阶段。TA 会试探、会嘴硬、会因为你的一句话反复琢磨，但不会直接表白。",
    "朋友": "普通朋友关系。轻松、有分寸，开玩笑会看场合，不过度介入对方的私事。",
    "师长": "年长或有指导关系。会给建议、会批评，语气有分量，不刻意讨好。",
    "上司": "TA 是你的上级。语气克制、以结果为导向，会布置任务也会给压力，不太聊私事。",
    "陌生人": "你们刚认识。礼貌、有距离感，会互相打量，不会一上来就熟络。",
}


def character(**kw) -> dict:
    base = {
        "id": "", "name": "", "alias": "", "gender": "UNKNOWN", "age": "",
        "oneLiner": "", "personality": "", "background": "", "appearance": "",
        "speechStyle": "", "avatarUrl": "", "importance": "SUPPORTING",
        "model": None, "tags": [], "isUser": False, "isGenerated": False,
        "createdAt": NOW - 30 * DAY, "updatedAt": NOW - 2 * DAY,
    }
    base.update(kw)
    return base


def relation(rid, a, b, kind, intensity, note) -> dict:
    return {
        "id": rid, "fromId": a, "toId": b, "kind": kind, "customLabel": "",
        "intensity": intensity, "note": note, "createdAt": NOW - 20 * DAY,
    }


def msg(mid, role, char_id, segments, ts, raw="", tokens_in=0, tokens_out=0, cost=0.0) -> dict:
    return {
        "id": mid, "role": role, "charId": char_id, "segments": segments,
        "raw": raw or "".join(s.get("content", "") for s in segments if s.get("type") == "text"),
        "ts": ts, "status": "DONE", "modelLabel": "",
        "tokensIn": tokens_in, "tokensOut": tokens_out, "costUsd": cost,
        "error": "", "attachment": "",
    }


def t(text: str) -> dict:
    return {"type": "text", "content": text}


def st(emotion: str) -> dict:
    return {"type": "sticker", "emotion": emotion, "stickerId": ""}


def tr(amount: float, to: str, note: str) -> dict:
    return {"type": "transfer", "amount": amount, "to": to, "note": note, "confirmed": False}


# ── 社会设定 ────────────────────────────────────────────────────────────────

society = {
    "id": SOCIETY_ID,
    "name": "城南夜班",
    "worldSetting": (
        "城南是一片被高架桥切成两半的老城区。桥北是新起的写字楼，桥南还留着九十年代的六层\n"
        "居民楼和一条夜市街。这里的便利店 24 小时亮着灯，是夜班的人、失眠的人、和不想回家的\n"
        "人共同的落脚点。\n\n"
        "生活节奏是现实的：要上班、要交房租、要应付家里催婚的电话。没有超能力，没有天降奇遇，\n"
        "只有人和人在深夜里的几次照面。"
    ),
    "orientation": "BG",
    "plotDirection": (
        "以「你」搬来城南后的日常为起点，慢慢把一个深夜小圈子铺开。\n"
        "情感线走克制路线，靠一次次偶遇和几句没说完的话推进，不搞一见钟情。\n\n"
        "同时埋一条悬疑暗线：每周三来买同一种酒的女人、琴行二楼锁着的门、外卖骑手凌晨三点的\n"
        "无声来电 —— 这些线索最终会指向城南十年前的一桩旧事。\n\n"
        "节奏上前松后紧：前五章以日常和关系建立为主，之后开始收线。"
    ),
    "tropes": ["都市", "慢热", "悬疑", "日常", "群像"],
    "coverSeed": 137,
    "narratorModel": None,
    "defaultCharacterModel": None,
    "imageModel": None,
    "createdAt": NOW - 30 * DAY,
    "updatedAt": NOW - 3 * 60 * MIN,
    "archived": False,
    "version": 1,
}

characters = [
    character(
        id=LINWAN, name="林晚", gender="FEMALE", age="26",
        oneLiner="接稿为生，白天睡觉晚上画画，搬来城南是为了躲开一些事",
        personality=(
            "表面散漫，其实对细节极度敏感。不太主动，但一旦在意就会反复琢磨。\n"
            "失眠，习惯在凌晨出门买水。"
        ),
        background=(
            "美院毕业三年，靠接商业插画维持生活。半年前和相处五年的人分开，把原来的房子退了，\n"
            "搬来城南这间朝北的一居室。行李到现在还没完全拆封。"
        ),
        appearance="清瘦，长发常年随手扎起，眼下一圈淡淡的青色，常穿宽松卫衣",
        speechStyle="话不多，句子短，习惯用省略号收尾",
        importance="LEAD", isUser=True,
    ),
    character(
        id=ZHOUYE, name="周野", alias="小周", gender="MALE", age="24",
        oneLiner="便利店夜班，白天在准备考编，笑起来有虎牙",
        personality=(
            "话密、爱开玩笑，但一涉及自己的事就打岔。记性极好，记得每个常客买什么。\n"
            "看起来没心没肺，其实比谁都清楚这个街区的事。"
        ),
        background=(
            "本地人，父母在桥北开了间小饭馆。大专毕业后一直没找到方向，先在便利店顶着，\n"
            "一边考编一边拖。和周遭所有人都熟，是这个小圈子的连接点。"
        ),
        appearance="个子高，偏瘦，总把工服袖子撸到手肘，左手腕有一道旧疤",
        speechStyle="语速快，爱用反问和玩笑；认真起来句子会突然变短",
        importance="LEAD", isGenerated=False,
        tags=[tag("暧昧对象", TAG_HINTS["暧昧对象"])],
    ),
    character(
        id=ZHENG, name="郑知秋", gender="MALE", age="34",
        oneLiner="琴行老板，话少，只在调音的时候哼歌",
        personality=(
            "克制、有分寸感，不打听别人的私事，但会记住你说过的话。对声音极其敏感。\n"
            "有很重的持续感 —— 一旦决定做什么就不会改。"
        ),
        background=(
            "曾经是职业吉他手，十年前因为一件事退出了圈子，回城南开了这间琴行。\n"
            "二楼一直锁着，从没人上去过。"
        ),
        appearance="短发，戴细框眼镜，手指有常年按弦留下的茧，常穿深色衬衫",
        speechStyle="语速慢，用词简洁，偶尔会用比喻",
        importance="MAJOR",
        tags=[tag("朋友", TAG_HINTS["朋友"])],
    ),
    character(
        id=GAOMING, name="高鸣", gender="MALE", age="29",
        oneLiner="跑夜班单的骑手，认识城南每一栋楼的电梯要等多久",
        personality=(
            "直、讲义气、藏不住事。看起来糙，其实很会照顾人。\n"
            "有个习惯是凌晨三点会接一个从来不说话的来电。"
        ),
        background=(
            "外地来打工的，在城南租了个隔断间。跑了三年单，攒钱想给老家的妹妹交学费。\n"
            "和沈岫似乎认识，但两人都装不认识。"
        ),
        appearance="短寸，晒得偏黑，头盔总挂在车把上，右手小指有点变形",
        speechStyle="大嗓门，爱说「我跟你说」，讲事情喜欢从头讲起",
        importance="MAJOR",
        tags=[tag("朋友", TAG_HINTS["朋友"])],
    ),
    character(
        id=CHEN, name="陈素芬", alias="陈老师", gender="FEMALE", age="61",
        oneLiner="住三楼的退休语文老师，一个人住，养了只叫「阿瞒」的橘猫",
        personality=(
            "爱管事，嘴上嫌弃心里热。说话带着老派教师的习惯，喜欢纠正别人的用词。\n"
            "孤独但嘴硬。"
        ),
        background=(
            "在城南中学教了三十五年语文，丈夫走得早，儿子在桥北上班、一个月回来一次。\n"
            "整栋楼的人都吃过她做的腌笃鲜。"
        ),
        appearance="花白短发，戴老花镜，常穿碎花衬衫，手里总提着菜",
        speechStyle="慢条斯理，喜欢用四字词，教训人的时候会叫你全名",
        importance="SUPPORTING",
        tags=[tag("师长", TAG_HINTS["师长"])],
    ),
    character(
        id=LUO, name="罗美娟", alias="罗姐", gender="FEMALE", age="41",
        oneLiner="便利店店长，白班，永远在算损耗",
        personality="精明、务实、不吃亏。对下属谈不上坏，就是把生意算得很清楚。",
        background=(
            "这家便利店开了六年，她当了五年店长。周野是她招进来的，"
            "两人互相看不太顺眼但都能干活。"
        ),
        appearance="微胖，盘发，常年一件红色马甲",
        speechStyle="短促、直接，喜欢用数字说话",
        importance="MINOR",
        tags=[tag("上司", TAG_HINTS["上司"])],
    ),
    character(
        id=SHEN, name="沈岫", gender="FEMALE", age="30",
        oneLiner="每周三凌晨来买同一种酒，从不多说一句话",
        personality=(
            "安静到有点冷，边界感极强。观察力惊人，你说过的细节她全记得。\n"
            "戒备心重，但对猫和小孩会松下来一点。"
        ),
        background=(
            "每周三固定出现，买的是城南唯一一家还在卖的旧牌子白酒，酒标被人撕掉了。\n"
            "和郑知秋似乎是旧识，和高鸣也有某种联系，但都不承认。"
        ),
        appearance="身形偏瘦，齐肩黑发，穿素色长外套，左手无名指有一圈浅痕",
        speechStyle="句子极短，经常只回答一两个字",
        importance="MAJOR",
        tags=[tag("陌生人", TAG_HINTS["陌生人"])],
    ),
]

relations = [
    relation("rel_01", LINWAN, ZHOUYE, "FRIEND", 42,
             "便利店里认识的，他是这栋楼里唯一会主动跟她搭话的人"),
    relation("rel_02", LINWAN, ZHENG, "NEIGHBOR", 34,
             "琴行就在楼下，第一次见面他递了把伞，两个人一句话都没说"),
    relation("rel_03", LINWAN, GAOMING, "FRIEND", 38, "凌晨的订单总是他送"),
    relation("rel_04", LINWAN, CHEN, "NEIGHBOR", 30,
             "三楼的老师，第一次见面就被问了三次有没有对象"),
    relation("rel_05", LINWAN, LUO, "ACQUAINTANCE", 18, "买烟的时候会聊两句"),
    relation("rel_06", LINWAN, SHEN, "ACQUAINTANCE", 15,
             "连续两周的周三都遇到，但没说过一句话"),
    relation("rel_07", LUO, ZHOUYE, "SUPERIOR", 40,
             "店长和夜班店员，互相看不太顺眼，但活都干得利索"),
    relation("rel_08", ZHOUYE, GAOMING, "FRIEND", 58,
             "都常在深夜出现在便利店，熟得像老友"),
    relation("rel_09", ZHENG, CHEN, "NEIGHBOR", 52, "十几年的老街坊，她常给他送汤"),
    relation("rel_10", ZHENG, SHEN, "ACQUAINTANCE", 45,
             "似乎早就认识，但两人都装作不认识"),
]

plot = {
    "act": 1,
    "title": "深夜的便利店",
    "summary": (
        "林晚搬来城南三周，行李只拆了一半。她在凌晨的便利店里认识了夜班店员周野，"
        "在楼下琴行门口接过郑知秋递来的伞，被三楼的陈老师拦住问了三遍有没有对象。"
        "生活正在慢慢铺开，而她没说出口的那件事还压在原处。"
    ),
    "mood": "疏离但温热",
    "hooks": [
        "沈岫每周三买的那瓶酒，酒标是被人撕掉的",
        "郑知秋的琴行二楼一直锁着，从没人上去过",
        "高鸣每天凌晨三点会接一个从来不说话的来电",
        "林晚搬来城南之前到底发生了什么，她一句都没提",
    ],
    "beats": [
        {"ts": NOW - 26 * DAY, "title": "搬进朝北的一居室",
         "detail": "行李只拆了一半，画架先立起来了", "actorId": LINWAN, "automatic": False},
        {"ts": NOW - 20 * DAY, "title": "第一次在便利店遇到周野",
         "detail": "他记住了她买的那款水，第二次直接递了过来", "actorId": ZHOUYE, "automatic": True},
        {"ts": NOW - 14 * DAY, "title": "郑知秋在楼下递了把伞",
         "detail": "那天下大雨，两个人一句话都没说", "actorId": ZHENG, "automatic": True},
        {"ts": NOW - 9 * DAY, "title": "陈素芬在楼道里拦住了她",
         "detail": "问了三遍有没有对象，最后塞给她一碗腌笃鲜", "actorId": CHEN, "automatic": True},
        {"ts": NOW - 3 * DAY, "title": "周三凌晨，第二次遇到沈岫",
         "detail": "她买的还是那瓶撕了酒标的酒，结账时看了林晚一眼", "actorId": SHEN, "automatic": True},
    ],
    "updatedAt": NOW - 3 * DAY,
}

# ── 预置会话：把四种消息片段都摆出来 ──────────────────────────────────────

chats = {
    ZHOUYE: [
        msg("m_z1", "USER", ZHOUYE, [t("刚搬来，睡不着")], NOW - 2 * DAY - 40 * MIN),
        msg("m_z2", "CHARACTER", ZHOUYE, [
            t("城南晚上就这样"), st("笑哭"), t("不过你运气不错"), t("夜班是我，随时来"),
        ], NOW - 2 * DAY - 39 * MIN),
        msg("m_z3", "USER", ZHOUYE, [t("…谢谢")], NOW - 2 * DAY - 36 * MIN),
        msg("m_z4", "CHARACTER", ZHOUYE, [
            st("狗头"), t("客气什么"), t("对了，热柜里那个关东煮别买，罗姐进了一批过期的"),
            t("我请你喝罐热的吧"), tr(5.20, "林晚", "请你喝罐热的"),
        ], NOW - 2 * DAY - 35 * MIN),
    ],
    ZHENG: [
        msg("m_g1", "USER", ZHENG, [t("楼下的琴声是你弹的吗")], NOW - 6 * DAY - 3 * MIN),
        msg("m_g2", "CHARACTER", ZHENG, [
            t("嗯"), t("隔音不好，吵到你了"), t("下次白天弹"),
        ], NOW - 6 * DAY - 2 * MIN),
        msg("m_g3", "USER", ZHENG, [t("没有，挺好听的。我画画的时候一直在放你那首")], NOW - 6 * DAY - 1 * MIN),
        msg("m_g4", "CHARACTER", ZHENG, [
            t("那首是我十年前的曲子"), t("很久没弹了，手生"),
            st("思考"), t("你要是喜欢，周三下午过来，那个点店里没人"),
        ], NOW - 6 * DAY),
    ],
    CHEN: [
        msg("m_c1", "USER", CHEN, [t("陈老师，昨天那碗腌笃鲜太咸了")], NOW - 8 * DAY - 5 * MIN),
        msg("m_c2", "CHARACTER", CHEN, [
            t("咸？"), t("我放了三年金华火腿"), st("哼"),
            t("林晚，你这个年纪味觉就开始退化了"),
            t("明天给你煮清淡点，你自己上楼端"),
        ], NOW - 8 * DAY - 4 * MIN),
    ],
}

# ── 日志 ────────────────────────────────────────────────────────────────────

logs = [
    {"id": "lg_01", "ts": NOW - 20 * DAY, "kind": "NARRATOR", "actor": "旁白",
     "title": "推进了剧情", "detail": "林晚第一次在便利店遇到周野。他记住了她要的那款水。",
     "modelLabel": "", "tokensIn": 1840, "tokensOut": 420, "costUsd": 0.0021, "refMsgId": None},
    {"id": "lg_02", "ts": NOW - 14 * DAY, "kind": "NARRATOR", "actor": "旁白",
     "title": "引入了新人物「沈岫」", "detail": "每周三凌晨来买同一种酒的女人。\n"
     "重要性判断依据：与郑知秋存在旧识关系，且承载主线伏笔\n拟定档位：标准\n分配模型：需在设置中配置",
     "modelLabel": "", "tokensIn": 2210, "tokensOut": 880, "costUsd": 0.0048, "refMsgId": None},
    {"id": "lg_03", "ts": NOW - 9 * DAY, "kind": "NARRATOR", "actor": "旁白",
     "title": "新人物「陈素芬」没有接上任何关系",
     "detail": "旁白没有为 TA 指定有效的关系，这个人当时在关系图上是孤立的。后已手动补线。",
     "modelLabel": "", "tokensIn": 1980, "tokensOut": 610, "costUsd": 0.0032, "refMsgId": None},
    {"id": "lg_04", "ts": NOW - 8 * DAY - 5 * MIN, "kind": "USER", "actor": "林晚",
     "title": "发了一条消息", "detail": "内容：陈老师，昨天那碗腌笃鲜太咸了",
     "modelLabel": "", "tokensIn": 0, "tokensOut": 0, "costUsd": 0.0, "refMsgId": "m_c1"},
    {"id": "lg_05", "ts": NOW - 8 * DAY - 4 * MIN, "kind": "CHARACTER", "actor": "陈素芬",
     "title": "回复了消息",
     "detail": "模型：未配置\n发送：陈老师，昨天那碗腌笃鲜太咸了\n回复：咸？我放了三年金华火腿 <sticker emotion=\"哼\" /> 林晚，你这个年纪味觉就开始退化了",
     "modelLabel": "", "tokensIn": 1120, "tokensOut": 96, "costUsd": 0.0011, "refMsgId": "m_c2"},
    {"id": "lg_06", "ts": NOW - 3 * DAY, "kind": "NARRATOR", "actor": "旁白",
     "title": "推进了剧情", "detail": "周三凌晨，林晚第二次遇到沈岫。她买的还是那瓶撕了酒标的酒。",
     "modelLabel": "", "tokensIn": 2440, "tokensOut": 520, "costUsd": 0.0039, "refMsgId": None},
]

# ── 写盘 ────────────────────────────────────────────────────────────────────


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out = root / "docs" / "testdata" / "societies"
    soc_dir = out / SOCIETY_ID
    (soc_dir / "chats").mkdir(parents=True, exist_ok=True)

    def dump(path: Path, obj) -> None:
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")

    dump(soc_dir / "society.json", society)
    dump(soc_dir / "characters.json", characters)
    dump(soc_dir / "relations.json", relations)
    dump(soc_dir / "plot.json", plot)
    dump(soc_dir / "stickers.json", [])

    for char_id, msgs in chats.items():
        dump(soc_dir / "chats" / f"{char_id}.json", msgs)

    (soc_dir / "logs.jsonl").write_text(
        "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in logs), encoding="utf-8",
    )

    # 索引：列表页只读它，字段必须齐
    index = [{
        "id": SOCIETY_ID,
        "name": society["name"],
        "orientationCode": "bg",
        "orientationLabel": "异性向",
        "characterCount": len(characters),
        "relationCount": len(relations),
        "updatedAt": society["updatedAt"],
        "coverSeed": society["coverSeed"],
        "archived": False,
    }]
    dump(out / "index.json", index)

    total = sum(1 for _ in out.rglob("*") if _.is_file())
    print(f"已生成 {total} 个文件 -> {out}")
    print(f"  人物 {len(characters)} · 关系 {len(relations)} · "
          f"会话 {len(chats)} 条 · 日志 {len(logs)} 条")

    # 同时导出一份单文件存档，方便走应用内「导入」
    archive = {
        "schema": "kith.society/1",
        "exportedAt": NOW,
        "appVersion": "Kith 0.1.0",
        "society": society,
        "characters": characters,
        "relations": relations,
        "plot": plot,
        "stickers": [],
        "chats": chats,
        "logs": logs,
    }
    single = root / "docs" / "testdata" / "城南夜班.kith.json"
    dump(single, archive)
    print(f"  单文件存档 -> {single}")


if __name__ == "__main__":
    main()
