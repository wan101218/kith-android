# -*- coding: utf-8 -*-
"""模型目录精简（一次性，2026-09-17 用户需求）

需求：
  1. 别搞太多已下架/无法使用的模型 —— 与 OpenRouter 当前在售列表取交集；
  2. 日期后缀全部去掉（0714/0831/0324/08-2024/20260420…）—— 调用只看名字
     不看日期；裸名已存在则丢弃日期条目，裸名不存在则用裸名顶替（元数据沿用）；
  3. 厂商收窄到主流白名单，长尾小厂/自托管变体/实验版删掉；
  4. :free 与 -customtools 变体删除；
  5. 补云舟中转站条目（站点公告确认的模型，vendor=yunzhou 与接入点匹配）。

用法：python tools/trim_model_catalog.py [or_models.json]
输入：app/src/main/assets/model_catalog.json + OpenRouter /api/v1/models 快照
输出：覆写 model_catalog.json（原文件备份为 model_catalog.backup.json）
"""
import json
import re
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app/src/main/assets/model_catalog.json"
OR_SNAPSHOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("D:/or_models.json")

# 主流厂商白名单（OpenRouter author 前缀）
VENDOR_WHITELIST = {
    "openai", "anthropic", "google", "deepseek", "qwen", "z-ai",
    "moonshotai", "minimax", "meta-llama", "meta", "mistralai", "x-ai",
    "bytedance-seed", "bytedance", "tencent", "stepfun", "xiaomi",
    "amazon", "cohere", "microsoft", "nvidia",
}

# 日期后缀：-0731 / -20260420 / -08-2024 / -2507 等
DATE_SUFFIX = re.compile(r"-(?:\d{8}|\d{2}-\d{4}|\d{4})$")

# 直接剔除的变体尾巴（剥离后裸名在集合里才会真的丢）
DROP_TAILS = ("-exp", "-vision-exp", "-customtools", "-preview-preview")


def bare_of(mid: str) -> str:
    """剥日期后缀。deepseek-v4-flash-0731 -> deepseek-v4-flash"""
    return DATE_SUFFIX.sub("", mid)


def main():
    with open(CATALOG, encoding="utf-8") as f:
        cat = json.load(f)
    with open(OR_SNAPSHOT, encoding="utf-8") as f:
        or_alive = {m["id"] for m in json.load(f)["data"]}

    models = cat["models"]
    n0 = len(models)

    # ── 1. 白名单厂商 + OpenRouter 在售 ─────────────────────────────
    kept = [m for m in models
            if m["id"].split("/")[0] in VENDOR_WHITELIST and m["id"] in or_alive]

    # ── 2. 变体清理 + 日期后缀剥除 ──────────────────────────────────
    by_id = {m["id"]: m for m in kept}

    def tail_variants(mid: str) -> str:
        for t in DROP_TAILS:
            if mid.endswith(t):
                return mid[: -len(t)]
        if mid.endswith(":free"):
            return mid.split(":")[0]
        return mid

    out = {}
    dropped = []
    for m in kept:
        mid = m["id"]
        base = tail_variants(mid)
        if base != mid:
            # 付费/正式版本存在 → 丢变体；不存在 → 用原条目顶上正名
            if base in by_id or base in out:
                dropped.append(mid)
                continue
            m = dict(m, id=base)
        if DATE_SUFFIX.search(mid):
            bare = bare_of(mid)
            if bare != mid:
                if bare in by_id or bare in out:
                    dropped.append(mid)
                    continue
                m = dict(m, id=bare)
                dropped.append(f"{mid} → {bare}")
        out[m["id"]] = m

    final = list(out.values())

    # ── 3. 云舟中转站条目（站点公告确认，wire 名剥前缀后即裸名） ────
    def yz(mid, name, ctx, vision):
        return {
            "id": f"yunzhou/{mid}", "name": name, "vendor": "yunzhou",
            "released_ts": 0, "released_iso": "",
            "context_length": ctx,
            "modalities": ["text", "image"] if vision else ["text"],
            "pricing": {"prompt": 0.0, "completion": 0.0},
            "expiration_ts": None, "expiration_iso": None,
            "source_key": "yunzhou-notice", "tier": 1, "url": "",
            "dedup_key": f"yunzhou/{mid}",
        }

    final += [
        yz("deepseek-v4-flash", "DeepSeek V4 Flash（云舟限免）", 131072, False),
        yz("minimax-m2.7-highspeed", "MiniMax M2.7 Highspeed（云舟）", 200000, False),
        yz("gpt5.5", "GPT-5.5（云舟）", 262144, True),
    ]

    # ── 4. 写回 ─────────────────────────────────────────────────────
    shutil.copy(CATALOG, CATALOG.with_suffix(".backup.json"))
    cat["models"] = sorted(final, key=lambda m: m["id"])
    cat["count"] = len(cat["models"])
    cat["generated_at"] = time.strftime("%Y-%m-%d")
    cat["generated_ts"] = int(time.time() * 1000)
    cat["vendor_counts"] = {}
    for m in cat["models"]:
        v = m["id"].split("/")[0]
        cat["vendor_counts"][v] = cat["vendor_counts"].get(v, 0) + 1
    cat["dropped_variants"] = sorted(set(dropped))
    cat["sources"] = ["openrouter-api-2026-09-17", "yunzhou-notice"]

    with open(CATALOG, "w", encoding="utf-8") as f:
        json.dump(cat, f, ensure_ascii=False)

    print(f"原始 {n0} → 精简后 {len(final)}（剔除 {n0 - len(final)}）")
    print("vendor 分布:", cat["vendor_counts"])
    print("日期后缀/变体剔除样例:", sorted(set(dropped))[:12])


if __name__ == "__main__":
    main()
