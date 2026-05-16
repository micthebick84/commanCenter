# Claude Code Hooks — 진행상황 자동 옵시디언 기록

`git commit`/`push` 시 자동 한 줄 로깅 + 세션 종료/컨텍스트 압축 시 자동 요약 → 옵시디언 진행상황 노트에 append.

## 무엇을 하나

| 트리거 | 동작 | LLM 비용 |
|---|---|---|
| `PostToolUse` (Bash, git commit/push만) | `- HH:MM commit \`<sha> <msg>\`` 한 줄 append | 없음 |
| `PreCompact` / `SessionEnd` | transcript 마지막 200줄을 `claude -p`로 요약 → 마크다운 섹션 append | 세션당 1~2회 |

같은 세션에서 PreCompact 직후 SessionEnd 더블 fire는 5분 dedup으로 방지.

## 사전 요구사항

- Python 3.8+ (macOS 기본 `python3`로 OK)
- Obsidian Local REST API 플러그인 설치 + API key 발급
- Claude Code CLI (`claude` — `which claude`로 확인) — summarize hook에서 사용
- `~/.claude.json`의 `mcpServers.obsidian-vault` (또는 `obsidian`)에 `OBSIDIAN_API_KEY`, `OBSIDIAN_HOST`, `OBSIDIAN_PORT` env 설정. hook은 이 설정에서 자동 로딩.

## 설치 (4단계)

```bash
# 1. hook 스크립트 복사
mkdir -p ~/.claude/hooks
cp scripts/claude-hooks/_obsidian.py \
   scripts/claude-hooks/log-git-to-obsidian.py \
   scripts/claude-hooks/summarize-to-obsidian.py \
   ~/.claude/hooks/
chmod +x ~/.claude/hooks/*.py

# 2. 프로젝트 → 옵시디언 노트 매핑 작성
cp scripts/claude-hooks/project-notes.example.json ~/.claude/hooks/project-notes.json
# 그 다음 /Users/REPLACE_ME/IdeaProjects/netisMaker 를 본인 경로로 수정
$EDITOR ~/.claude/hooks/project-notes.json

# 3. ~/.claude/settings.json 의 hooks 필드에 settings.snippet.json 블록 병합
#    기존 SessionStart 등 다른 hook이 있으면 보존하면서 추가
$EDITOR ~/.claude/settings.json

# 4. 동작 테스트
echo '{
  "session_id": "test",
  "hook_event_name": "PostToolUse",
  "cwd": "'"$(pwd)"'",
  "tool_name": "Bash",
  "tool_input": { "command": "git commit -m test" },
  "tool_response": { "output": "" }
}' | HOOK_DEBUG=1 python3 ~/.claude/hooks/log-git-to-obsidian.py
# → 옵시디언 노트에 한 줄 append됐는지 확인
```

## 파일

- `_obsidian.py` — 공통 helper (Obsidian REST API 자격증명 자동 로드, append, 프로젝트 매칭)
- `log-git-to-obsidian.py` — PostToolUse hook (LLM 비용 없음)
- `summarize-to-obsidian.py` — PreCompact + SessionEnd hook (`claude -p` 호출)
- `project-notes.example.json` — 프로젝트 → 노트 매핑 템플릿
- `settings.snippet.json` — `~/.claude/settings.json` 병합용 hooks 블록

## 다른 프로젝트 추가

`~/.claude/hooks/project-notes.json`의 `projects` 객체에 한 줄 추가:

```json
"/Users/foo/work/other-proj": { "note_prefix": "Work/other-proj-진행-", "title": "other-proj" }
```

→ 매 commit/push가 `Work/other-proj-진행-YYYY-MM-DD.md`에 자동 기록.

`startswith` 매칭이므로 매핑되지 않은 프로젝트의 cwd는 silent skip — 다른 프로젝트엔 영향 0.

## 트러블슈팅

- **옵시디언에 아무것도 안 들어감**: `HOOK_DEBUG=1` env로 hook 실행 → stderr에 에러 출력
- **옵시디언 API 연결 거부**: Obsidian이 켜져 있고 Local REST API 플러그인이 활성 상태인지 확인. cert 자체서명이라 `NODE_TLS_REJECT_UNAUTHORIZED=0` 권장
- **summarize가 너무 길거나 비용 비싸짐**: `summarize-to-obsidian.py`의 `TRANSCRIPT_TAIL_LINES` (기본 200) 줄이기, 또는 `SessionEnd` hook만 남기고 `PreCompact` 제거
- **heredoc 안의 "git commit" 문자열이 trigger됨**: 정규식이 `(?:^|[;&|]\s*)git\s+(commit|push)\b`로 셸 boundary만 매칭하므로 정상 케이스에선 발생 X. 만약 false positive 보이면 정규식 더 타이트하게 조정 (예: 명령 시작만 매칭 `^git\s+...`)

## 비활성화

`~/.claude/settings.json`의 `hooks` 필드에서 해당 블록만 지움. 스크립트 파일은 남겨도 무해 (다시 사용할 가능성 대비).
