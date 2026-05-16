#!/usr/bin/env python3
"""PostToolUse hook — Bash 도구의 git commit/push만 한 줄 옵시디언 로깅.

Hook 입력 (stdin JSON): session_id, transcript_path, cwd, hook_event_name,
                       tool_name, tool_input, tool_response

비용 0 — LLM 호출 없음. git commit/push 외 다른 Bash는 즉시 exit 0.
"""
import datetime
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _obsidian import append_to_note, load_project_notes, match_project  # noqa: E402


def main():
    try:
        ev = json.load(sys.stdin)
    except Exception:
        return 0

    if ev.get("tool_name") != "Bash":
        return 0
    cmd = (ev.get("tool_input") or {}).get("command", "")
    cwd = ev.get("cwd", "")
    if not cmd or not cwd:
        return 0

    # git이 (1) 명령 시작, (2) 셸 구분자(`;&|`) 다음, (3) `cd ... && git ...` 패턴에서만 매칭.
    # heredoc/문자열 리터럴 안의 "git commit"은 매칭 안 됨.
    m = re.search(r"(?:^|[;&|]\s*)git\s+(commit|push)\b", cmd)
    if not m:
        return 0
    action = m.group(1)

    project = match_project(cwd, load_project_notes())
    if not project:
        return 0

    today = datetime.date.today().isoformat()
    note = f"{project['note_prefix']}{today}.md"
    ts = datetime.datetime.now().strftime("%H:%M")

    if action == "commit":
        try:
            r = subprocess.run(
                ["git", "-C", cwd, "log", "-1", "--format=%h %s"],
                capture_output=True, text=True, timeout=2,
            )
            info = r.stdout.strip() or "(unknown)"
        except Exception:
            info = "(unknown)"
        entry = f"- {ts} commit `{info}`"
    else:
        # push — stdout에서 SHA range 추출 (a1b2c3..d4e5f6 패턴)
        out = ((ev.get("tool_response") or {}).get("output")
               or (ev.get("tool_response") or {}).get("stdout")
               or "")
        sha = ""
        sm = re.search(r"[a-f0-9]{7,40}\.\.[a-f0-9]{7,40}", out)
        if sm:
            sha = f" `{sm.group(0)}`"
        branch_match = re.search(r"\s(\S+)\s+->\s+(\S+)", out)
        branch = ""
        if branch_match:
            branch = f" → {branch_match.group(2)}"
        entry = f"- {ts} push{sha}{branch}".rstrip()

    append_to_note(note, "\n" + entry + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
