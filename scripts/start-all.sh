#!/usr/bin/env bash
#
# netisMaker 전체 스택 기동: 백엔드 API 1개 + 워커 N개 (기본 2개).
#
# 사용:
#   ./scripts/start-all.sh           # API + 워커 2개
#   ./scripts/start-all.sh 3         # API + 워커 3개
#   WORKERS=4 ./scripts/start-all.sh # 환경변수로도 지정 가능
#
# 같은 머신에서 워커 N개를 동시에 띄움 (Phase 2.1 병렬 처리).
# 로그: .run/{api,worker-N}.log,  PID: .run/{api,worker-N}.pid
# 중지: ./scripts/stop-all.sh
#
set -euo pipefail

# 프로젝트 루트 (스크립트 위치 기준)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN_DIR="$ROOT/.run"
mkdir -p "$RUN_DIR"

# ── 설정 (환경변수로 override 가능) ─────────────────────────────
WORKERS="${WORKERS:-${1:-2}}"
API_PORT="${API_PORT:-8090}"
API_BASE_URL="${API_BASE_URL:-http://localhost:${API_PORT}}"
WORKER_API_KEY="${WORKER_API_KEY:-dev-only-change-me}"
REPOS_DIR="${REPOS_DIR:-$HOME/netis-maker/repos}"
CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3001}"
CLAUDE_CLI="${CLAUDE_CLI:-$(command -v claude || true)}"

GRADLE="./gradlew"

# 이미 떠 있는지 검사 후 기동하는 헬퍼
is_alive() {  # $1 = pidfile
  local pf="$1"
  [ -f "$pf" ] && kill -0 "$(cat "$pf")" 2>/dev/null
}

wait_for_log() {  # $1 = logfile, $2 = 정규식, $3 = 라벨
  local log="$1" pat="$2" label="$3" i=0
  until grep -qE "$pat" "$log" 2>/dev/null; do
    sleep 2
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
      echo "  ✗ $label: 120초 내 기동 신호 없음 — $log 확인 필요"
      return 1
    fi
  done
}

echo "netisMaker 스택 기동 (워커 ${WORKERS}개)"
echo "  ROOT=$ROOT"
echo "  API_BASE_URL=$API_BASE_URL  REPOS_DIR=$REPOS_DIR"
echo "  CLAUDE_CLI=${CLAUDE_CLI:-(auto-detect)}"
echo ""

# ── 1) 백엔드 API ──────────────────────────────────────────────
if is_alive "$RUN_DIR/api.pid"; then
  echo "● API 이미 실행 중 (pid $(cat "$RUN_DIR/api.pid")) — skip"
else
  echo "● API 기동 중... (:$API_PORT)"
  CORS_ALLOWED_ORIGINS="$CORS_ALLOWED_ORIGINS" \
  nohup "$GRADLE" bootRun -q > "$RUN_DIR/api.log" 2>&1 &
  echo $! > "$RUN_DIR/api.pid"
  if wait_for_log "$RUN_DIR/api.log" 'Started NetisMakerApplication|APPLICATION FAILED|BUILD FAILED' "API"; then
    if grep -qE 'Started NetisMakerApplication' "$RUN_DIR/api.log"; then
      echo "  ✓ API ready (pid $(cat "$RUN_DIR/api.pid"))"
    else
      echo "  ✗ API 기동 실패 — $RUN_DIR/api.log 확인"
      exit 1
    fi
  fi
fi

# ── 2) 워커 N개 ────────────────────────────────────────────────
for n in $(seq 1 "$WORKERS"); do
  wid="mac-worker-$n"
  pidf="$RUN_DIR/worker-$n.pid"
  logf="$RUN_DIR/worker-$n.log"
  if is_alive "$pidf"; then
    echo "● $wid 이미 실행 중 (pid $(cat "$pidf")) — skip"
    continue
  fi
  echo "● $wid 기동 중..."
  # env로 감싸면 ${CLAUDE_CLI:+...} 조건부 확장이 런타임에 env로 파싱돼
  # env-prefix로 정상 인식됨 (셸 직접 prefix는 확장 후 명령어로 오인됨).
  nohup env \
    WORKER_ID="$wid" \
    API_BASE_URL="$API_BASE_URL" \
    WORKER_API_KEY="$WORKER_API_KEY" \
    REPOS_DIR="$REPOS_DIR" \
    ${CLAUDE_CLI:+CLAUDE_CLI="$CLAUDE_CLI"} \
    "$GRADLE" bootRun --args='--spring.profiles.active=worker' -q > "$logf" 2>&1 &
  echo $! > "$pidf"
  if wait_for_log "$logf" 'Started NetisMakerApplication|APPLICATION FAILED|BUILD FAILED' "$wid"; then
    grep -qE 'Started NetisMakerApplication' "$logf" \
      && echo "  ✓ $wid ready (pid $(cat "$pidf"))" \
      || { echo "  ✗ $wid 기동 실패 — $logf 확인"; }
  fi
done

echo ""
echo "완료. 상태 확인: ./scripts/status.sh   중지: ./scripts/stop-all.sh"
