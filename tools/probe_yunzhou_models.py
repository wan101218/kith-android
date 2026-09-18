# -*- coding: utf-8 -*-
"""云舟可用模型探测（一次性）：对候选名单逐个发 1-token 请求"""
import json
import urllib.request
import urllib.error
import ssl

import os

BASE = "https://cli.999554.xyz"
# Key 不入库：从环境变量读取
#   export KITH_KEY=sk-xxx && python tools/probe_yunzhou_models.py
KEY = os.environ.get("KITH_KEY", "")
ctx = ssl.create_default_context()

CANDIDATES = [
    # deepseek
    "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4.1-flash",
    "deepseek-v3.2", "deepseek-v3.1", "deepseek-chat", "deepseek-reasoner",
    "deepseek-r1", "deepseek-v3.1-terminus",
    # gpt / openai
    "gpt5.5", "gpt-5.5", "gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4o",
    "gpt-4o-mini", "o3", "gpt-5-codex",
    # claude
    "claude-opus-4.8", "claude-opus-4.6", "claude-opus-4.5", "claude-sonnet-4.6",
    "claude-sonnet-4.5", "claude-sonnet-5", "claude-haiku-4.5",
    # gemini
    "gemini-3-pro-preview", "gemini-3-flash", "gemini-2.5-pro", "gemini-2.5-flash",
    # 国产
    "MiniMax-M2.7-highspeed", "minimax-m2.7-highspeed", "minimax-m3",
    "kimi-k3", "kimi-k2.7", "kimi-k2.6",
    "glm-5.3", "glm-5.2", "glm-5.1",
    "qwen3.8-max", "qwen3.5-plus", "qwen3.5-397b-a17b",
    "doubao-seed-2.0-pro", "seed-2.0-code",
]


def probe(model):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": "hi"}],
        "max_tokens": 1,
    }).encode()
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=body, method="POST")
    req.add_header("Authorization", "Bearer " + KEY)
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "Mozilla/5.0")
    try:
        with urllib.request.urlopen(req, timeout=25, context=ctx) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


ok, fail = [], {}
for m in CANDIDATES:
    st, body = probe(m)
    if st == 200:
        ok.append(m)
        print(f"  ✓ {m}")
    else:
        try:
            msg = json.loads(body).get("error", {}).get("message", body[:80])
        except Exception:
            msg = body[:80]
        fail[m] = msg
        print(f"  ✗ {m}  [{st}] {msg[:70]}")

print()
print("=== 云舟真实可用名单 ===")
print(json.dumps(ok, ensure_ascii=False))
with open("D:/yz_usable.json", "w", encoding="utf-8") as f:
    json.dump({"ok": ok, "fail": fail}, f, ensure_ascii=False, indent=1)
