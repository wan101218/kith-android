# -*- coding: utf-8 -*-
"""deepseek 系按实测可用名单裁剪 + 云舟条目修正（一次性）

实测（tools/probe_yunzhou_models.py，2026-09-17）：
  云舟令牌真实可用：deepseek-v4-flash / deepseek-v4-pro（其余全 403 无权）
  DeepSeek 官方接入点（用户已配置）：deepseek-chat / deepseek-reasoner（官方文档端点名）
裁剪：
  - deepseek/ 目录条目只留上述 4 个；v3.2/r1/v3.1/v4.1-flash/terminus 等全删
  - yunzhou/ 条目只留实测可用的 v4-flash / v4-pro；gpt5.5、minimax（403 无权）删除
"""
import json
import shutil
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app/src/main/assets/model_catalog.json"

KEEP_DEEPSEEK = {
    "deepseek/deepseek-v4-flash",     # 云舟限免 + 官方同款，实测可用
    "deepseek/deepseek-v4-pro",       # 云舟实测可用
    "deepseek/deepseek-chat",         # DeepSeek 官方端点名
    "deepseek/deepseek-reasoner",     # DeepSeek 官方端点名（思维）
}
KEEP_YUNZHOU = {"yunzhou/deepseek-v4-flash", "yunzhou/deepseek-v4-pro"}

with open(CATALOG, encoding="utf-8") as f:
    cat = json.load(f)
models = cat["models"]
n0 = len(models)
by_id = {m["id"]: m for m in models}

out, dropped = [], []
for m in models:
    mid = m["id"]
    if mid.startswith("deepseek/"):
        if mid in KEEP_DEEPSEEK:
            out.append(m)
        else:
            dropped.append(mid)
    elif mid.startswith("yunzhou/"):
        if mid in KEEP_YUNZHOU:
            out.append(m)
        else:
            dropped.append(mid)
    else:
        out.append(m)

# 官方 deepseek-chat / deepseek-reasoner 元数据补齐（目录里原本没有 reasoner）
def official(mid, name, ctx, vision=False):
    if mid in by_id:
        return by_id[mid]
    return {
        "id": mid, "name": name, "vendor": "deepseek",
        "released_ts": 0, "released_iso": "",
        "context_length": ctx, "modalities": ["text", "image"] if vision else ["text"],
        "pricing": {"prompt": 0.0, "completion": 0.0},
        "expiration_ts": None, "expiration_iso": None,
        "source_key": "deepseek-official-docs", "tier": 1, "url": "",
        "dedup_key": mid,
    }

have = {m["id"] for m in out}
need = {
    "deepseek/deepseek-chat": ("DeepSeek Chat（官方）", 131072),
    "deepseek/deepseek-reasoner": ("DeepSeek Reasoner（官方思维）", 131072),
}
for mid, (name, ctx_len) in need.items():
    if mid not in have:
        out.append(official(mid, name, ctx_len))

shutil.copy(CATALOG, CATALOG.with_suffix(".backup2.json"))
cat["models"] = sorted(out, key=lambda m: m["id"])
cat["count"] = len(cat["models"])
cat["generated_at"] = time.strftime("%Y-%m-%d")
cat["generated_ts"] = int(time.time() * 1000)
cat["vendor_counts"] = {}
for m in cat["models"]:
    v = m["id"].split("/")[0]
    cat["vendor_counts"][v] = cat["vendor_counts"].get(v, 0) + 1
cat["dropped_variants"] = sorted(set(dropped))
cat["sources"] = ["openrouter-api-2026-09-17", "yunzhou-probe-2026-09-17", "deepseek-official-docs"]

with open(CATALOG, "w", encoding="utf-8") as f:
    json.dump(cat, f, ensure_ascii=False)

print(f"{n0} → {len(cat['models'])}（本轮删 {len(dropped)}）")
print("deepseek 系:", [m['id'] for m in cat['models'] if m['id'].startswith(('deepseek/', 'yunzhou/'))])
