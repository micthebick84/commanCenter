#!/usr/bin/env python3
"""PreCompact + SessionEnd hook — transcript 마지막을 claude -p로 요약 → 옵시디언 append.

Hook 입력 (stdin JSON): session_id, transcript_path, cwd, hook_event_name

비용: 세션당 1~2회 claude 호출. 짧은 세션이라 transcript 200줄 + 짧은 출력으로 제한.

전략:
  · 같은 hook을 PreCompact (긴 세션 중간) + SessionEnd (정상 종료) 둘 다에 묶음
  · 중복 방지: 마지막 호출 시각을 ~/.claude/hooks/.last-summary-{session_id}에 기록,
    300초 이내면 skip (PreCompact 직후 SessionEnd 같은 케이스)
  · 옵시디언 노트는 startswith 매칭된 첫 프로젝트만
"""
import datetime
import json
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _obsidian import append_to_note, load_project_notes, match_project  # noqa: E402

TRANSCRIPT_TAIL_LINES = 200
CLAUDE_TIMEOUT = 120  # seconds
DEDUP_WINDOW_SECONDS = 300


def find_claude_bin():
    cands = []
    which = shutil.which("claude")
    if which:
        cands.append(which)
    home = os.path.expanduser("~")
    cands.extend([
        f"{home}/.local/bin/claude",
        f"{home}/.claude/local/claude",
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
        "/usr/bin/claude",
    ])
    for p in cands:
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return None


def dedup_check(session_id, event):
    if not session_id:
        return False
    marker = os.path.expanduser(f"~/.claude/hooks/.last-summary-{session_id}")
    now = time.time()
    if os.path.exists(marker):
        try:
            last = os.path.getmtime(marker)
            if now - last < DEDUP_WINDOW_SECONDS:
                return True
        except Exception:
            pass
    try:
        with open(marker, "w") as f:
            f.write(f"{event} {now}\n")
    except Exception:
        pass
    return False


def main():
    try:
        ev = json.load(sys.stdin)
    except Exception:
        return 0

    transcript = ev.get("transcript_path", "")
    cwd = ev.get("cwd", "")
    event = ev.get("hook_event_name", "")
    session_id = ev.get("session_id", "")
    short_sid = (session_id or "unknown")[:8]

    if not transcript or not os.path.exists(transcript):
        return 0

    project = match_project(cwd, load_project_notes())
    if not project:
        return 0

    if dedup_check(session_id, event):
        return 0

    # transcript 마지막 N줄 (NDJSON)
    try:
        with open(transcript) as f:
            lines = [ln.strip() for ln in f if ln.strip()]
    except Exception:
        return 0
    if not lines:
        return 0
    lines = lines[-TRANSCRIPT_TAIL_LINES:]

    today = datetime.date.today().isoformat()
    note = f"{project['note_prefix']}{today}.md"
    ts = datetime.datetime.now().strftime("%H:%M")
    title = project.get("title", "")

    prompt = (
        f"다음은 Claude Code 세션의 transcript(NDJSON, 최근 {len(lines)}줄)입니다. "
        f"'{title}' 프로젝트의 옵시디언 진행상황 노트에 추가할 한국어 마크다운 섹션을 출력하세요.\n\n"
        f"형식:\n"
        f"### Session {ts} — {event} ({short_sid})\n"
        f"\n"
        f"- 사용자 요청 핵심을 1~2줄로\n"
        f"- 변경/커밋/배포 사항 (커밋 SHA, 파일명 포함)\n"
        f"- 검증/테스트 결과\n"
        f"- 미해결 항목 또는 다음 작업 후보\n"
        f"\n"
        f"규칙:\n"
        f"- 마크다운 본문만 출력 (코드 펜스 ``` 없이). 옵시디언에 그대로 append됨.\n"
        f"- 5~15 bullet, 군더더기 없이.\n"
        f"- 이미 노트에 있는 내용은 반복하지 말고 이 세션 고유 작업만.\n"
        f"- 영어 응답 금지, 한국어로.\n\n"
        f"Transcript:\n" + "\n".join(lines)
    )

    claude_bin = find_claude_bin()
    if not claude_bin:
        return 0

    try:
        r = subprocess.run(
            [claude_bin, "-p"],
            input=prompt,
            capture_output=True,
            text=True,
            timeout=CLAUDE_TIMEOUT,
        )
        summary = (r.stdout or "").strip()
    except Exception as e:
        if os.environ.get("HOOK_DEBUG"):
            print(f"claude exec 실패: {e}", file=sys.stderr)
        return 0

    if not summary:
        return 0

    append_to_note(note, "\n\n" + summary + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
