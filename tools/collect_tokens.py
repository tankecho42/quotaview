#!/usr/bin/env python3
"""
QuotaView token 明细采集器
汇总本机 Codex + Claude Code (GLM) 的真实 token 用量, 输出 breakdown JSON
供 QuotaView App 费用模拟使用。

用法:
  python3 collect_tokens.py              # 全部历史
  python3 collect_tokens.py --days 7     # 近7天
输出: stdout JSON {"codex": {...}, "glm": {...}, "_meta": {...}}
"""
import json, glob, os, sys, argparse
from datetime import datetime, timedelta, timezone

HOME = os.path.expanduser("~")

def ts_of(d, default=0.0):
    t = d.get("timestamp")
    if isinstance(t, str):
        try:
            return datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp()
        except Exception:
            return default
    return default

def collect_codex(since_ts):
    files = glob.glob(f"{HOME}/.codex/sessions/**/*.jsonl", recursive=True)
    inp = cached = outp = reas = 0
    sessions = 0
    for f in files:
        if os.path.getmtime(f) < since_ts - 86400 * 2:  # 粗过滤
            continue
        last = None
        try:
            for line in open(f, errors="ignore"):
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                if d.get("type") == "event_msg" and d.get("payload", {}).get("type") == "token_count":
                    last = d["payload"].get("info", {}).get("total_token_usage")
        except Exception:
            continue
        if last:
            sessions += 1
            raw_in = last.get("input_tokens", 0)
            cached += last.get("cached_input_tokens", 0)
            # OpenAI 惯例: input_tokens 含 cached, 统一为非缓存口径
            inp += max(0, raw_in - last.get("cached_input_tokens", 0))
            outp += last.get("output_tokens", 0)
            reas += last.get("reasoning_output_tokens", 0)
    return {"input": inp, "cacheRead": cached, "output": outp, "reasoning": reas, "sessions": sessions}

def collect_claude(since_ts):
    files = glob.glob(f"{HOME}/.claude/projects/*/*.jsonl", recursive=True)
    inp = cached = cc = outp = 0
    events = 0
    for f in files:
        if os.path.getmtime(f) < since_ts - 86400 * 2:
            continue
        try:
            for line in open(f, errors="ignore"):
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                if ts_of(d) < since_ts:
                    continue
                u = (d.get("message") or {}).get("usage")
                if u:
                    events += 1
                    inp += u.get("input_tokens", 0)
                    cached += u.get("cache_read_input_tokens", 0)
                    cc += u.get("cache_creation_input_tokens", 0)
                    outp += u.get("output_tokens", 0)
        except Exception:
            continue
    return {"input": inp, "cacheRead": cached, "cacheCreation": cc, "output": outp, "messageEvents": events}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=0, help="0=全部")
    args = ap.parse_args()
    since = 0 if args.days == 0 else (datetime.now(timezone.utc) - timedelta(days=args.days)).timestamp()

    codex = collect_codex(since)
    claude = collect_claude(since)

    # App 端 TokenBreakdown 字段: input / cacheRead / output
    out = {
        "codex": {"input": codex["input"], "cacheRead": codex["cacheRead"], "output": codex["output"] + codex["reasoning"]},
        "glm": {"input": claude["input"], "cacheRead": claude["cacheRead"], "output": claude["output"]},
        "_meta": {
            "codex_sessions": codex["sessions"],
            "claude_usage_events": claude["messageEvents"],
            "claude_cache_creation": claude["cacheCreation"],
            "days": args.days,
            "generated": datetime.now(timezone.utc).isoformat(),
        },
    }
    print(json.dumps(out, ensure_ascii=False, indent=1))

if __name__ == "__main__":
    main()
