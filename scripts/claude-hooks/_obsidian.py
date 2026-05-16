"""공통 — 옵시디언 REST API 자격증명 + append helper.

자격증명은 ~/.claude.json의 mcpServers.obsidian-vault(또는 obsidian).env에서 자동 로딩.
single source of truth.
"""
import json
import os
import ssl
import sys
import urllib.parse
import urllib.request


def load_obsidian_config():
    try:
        with open(os.path.expanduser("~/.claude.json")) as f:
            data = json.load(f)
    except Exception:
        return None
    for name in ("obsidian-vault", "obsidian"):
        srv = data.get("mcpServers", {}).get(name)
        if not srv:
            continue
        env = srv.get("env", {}) or {}
        api_key = env.get("OBSIDIAN_API_KEY")
        if api_key:
            return {
                "api_key": api_key,
                "host": env.get("OBSIDIAN_HOST", "127.0.0.1"),
                "port": env.get("OBSIDIAN_PORT", "27124"),
            }
    return None


def load_project_notes():
    p = os.path.expanduser("~/.claude/hooks/project-notes.json")
    if not os.path.exists(p):
        return {}
    try:
        with open(p) as f:
            return json.load(f).get("projects", {}) or {}
    except Exception:
        return {}


def match_project(cwd, projects):
    if not cwd:
        return None
    # 가장 긴 prefix가 우선 (중첩 worktree 등 고려)
    matches = [(root, meta) for root, meta in projects.items() if cwd.startswith(root)]
    if not matches:
        return None
    matches.sort(key=lambda x: len(x[0]), reverse=True)
    return matches[0][1]


def append_to_note(note_path, markdown):
    """옵시디언 노트 끝에 markdown을 append. 실패 silent (hook은 절대 노이지하게 실패하면 안 됨)."""
    cfg = load_obsidian_config()
    if not cfg:
        return False
    url_path = urllib.parse.quote(note_path)
    url = f"https://{cfg['host']}:{cfg['port']}/vault/{url_path}"
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(
        url,
        data=markdown.encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {cfg['api_key']}",
            "Content-Type": "text/markdown",
        },
    )
    try:
        urllib.request.urlopen(req, context=ctx, timeout=10)
        return True
    except Exception as e:
        if os.environ.get("HOOK_DEBUG"):
            print(f"obsidian append 실패: {e}", file=sys.stderr)
        return False
