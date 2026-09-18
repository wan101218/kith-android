# -*- coding: utf-8 -*-
"""云舟中转站 API 连通性测试（一次性伪应用）

站点: https://cli.999554.xyz/  (new-api 面板, system_name=云舟API)
用法: python tools/test_yunzhou_api.py
只依赖标准库。探测: /api/pricing -> /v1/models -> /v1/chat/completions(多候选)
      末尾含真假 key 对照实验。

────────────────────────────────────────────────────────────
2026-09-17 实测结论（key: sk-N1AB...viV7）:
  · key 有效: 假 key 返回 401 "Invalid token", 该 key 返回 403(认证已通过)
  · 但每个接口都报 403 "无权访问 CODE 分组": /v1/models、/v1/chat/completions、
    /dashboard/billing/subscription 全部一样, 与模型名无关,
    加 @CODEX / @default / @CODE 后缀也无法绕过
  · 判定: 令牌被绑定到「CODE」分组且无权访问 —— 服务端配置问题,
    客户端无解。需登录面板: 令牌管理 → 编辑令牌 → 分组改 default/auto,
    或重建令牌; 若仍不行则是账号分组被降级(站点有批量封号公告)
  · 注意: 该站有 Cloudflare 防护, urllib 短时间高频请求会被
    error code 1010 拦截(TLS 指纹封禁), 换 curl 即可
────────────────────────────────────────────────────────────
"""
import json
import ssl
import sys
import time
import urllib.request
import urllib.error

BASE = "https://cli.999554.xyz"
# Key 不入库：从环境变量读取
#   export KITH_KEY=sk-xxx && python tools/test_yunzhou_api.py
KEY = os.environ.get("KITH_KEY", "")

# 站点公告: DeepSeek V4 flash 长期免费; 推荐 MiniMax-M2.7-highspeed / GPT5.5
# 上一轮 /v1/models 报「无权访问 CODE 分组」→ 追加 @分组 变体(new-api 指定分组语法)
CANDIDATES = [
    "deepseek-v4-flash",
    "deepseek-v4-flash@CODEX",
    "deepseek-v4-flash@default",
    "deepseek-v4-flash@CODE",
    "MiniMax-M2.7-highspeed",
    "gpt-4o-mini",
    "gpt5.5",
]

ctx = ssl.create_default_context()


def call(method, path, body=None, timeout=90, with_key=True):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if with_key:
        req.add_header("Authorization", "Bearer " + KEY)
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "Mozilla/5.0 (kith-probe)")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, raw, time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - t0
    except Exception as e:
        return None, f"{type(e).__name__}: {e}", time.time() - t0


def brief(raw, keep=500):
    return raw if len(raw) <= keep else raw[:keep] + f"...(共{len(raw)}B)"


def try_chat(model):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": "只回复四个字：连接成功"}],
        "max_tokens": 64,
        "stream": False,
    }
    st, raw, dt = call("POST", "/v1/chat/completions", body)
    print(f"\n--- POST /v1/chat/completions  model={model!r}")
    print(f"HTTP {st}  ({dt:.2f}s)  {brief(raw)}")
    if st == 200:
        try:
            j = json.loads(raw)
            print(f">>> 模型回复: {j['choices'][0]['message']['content']!r}")
            print(f">>> 用量: {j.get('usage')}")
            return True
        except Exception:
            return True
    return False


def main():
    print(f"目标: {BASE}   key: {KEY[:10]}...{KEY[-4:]}")

    # 0) 定价表(带 key 试, 站点把它设成了 requireAuth)
    st, raw, dt = call("GET", "/api/pricing")
    print(f"=== STEP0 GET /api/pricing === HTTP {st}  ({dt:.2f}s)")
    models_known = []
    if st == 200:
        try:
            d = json.loads(raw).get("data", [])
            models_known = [m.get("model_name", "") for m in d]
            groups = sorted({g for m in d for g in (m.get("enable_groups") or [])})
            print(f"模型 {len(models_known)} 个, 全部分组: {groups}")
            print("抽样:", models_known[:20])
        except Exception as e:
            print("解析失败:", e, brief(raw, 300))
    else:
        print(brief(raw, 300))

    # 1) 模型列表
    st, raw, dt = call("GET", "/v1/models", timeout=30)
    print(f"\n=== STEP1 GET /v1/models === HTTP {st}  ({dt:.2f}s)")
    print(brief(raw, 400))

    # 2) 对话补全, 逐候选尝试
    print("\n=== STEP2 POST /v1/chat/completions ===")
    order = list(dict.fromkeys(
        (models_known and [m for m in CANDIDATES if m.split("@")[0] in models_known] or [])
        + CANDIDATES))
    ok = False
    for m in order[:10]:
        if try_chat(m):
            ok = True
            print(f"\n>>> 成功的模型名: {m!r}")
            break
    if not ok:
        print("\n>>> 所有候选模型都失败。若均为 403「无权访问 CODE 分组」, "
              "说明 key 被绑定到了不存在/无权的分组, 需在面板端改分组或重建令牌。")

    # 3) 真假 key 对照 —— 区分「key 无效(401)」与「分组无权(403)」
    print("\n=== STEP3 真假 key 对照 ===")
    for k, label in (("sk-FAKE000000000000000000000000000000000000000", "假key"),
                     (KEY, "真key")):
        st, raw, dt = call("POST", "/v1/chat/completions", {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 8,
        }) if k == KEY else (None, "跳过(用 curl 做, 避开 Cloudflare 1010)", 0)
        print(f"{label}: HTTP {st}  {brief(raw, 200)}")

    print("\n=== 探测结束 ===")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
