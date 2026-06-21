#!/usr/bin/env bash
#
# netisMaker "공개 스택" 기동 — Cloudflare Tunnel(auth/app.micthebick.dev)로 외부 접속용.
# 서비스가 꺼졌을 때 이 스크립트 하나로 "공개 OAuth 설정 그대로" 복구한다.
#   1) netis-auth   (:9000) — public issuer + forward-headers + CORS(localhost:9000 포함)
#   2) netisMaker API(:8090) — JWKS는 localhost, iss는 공개 도메인으로 검증
#   3) netisMaker 프론트(:3001) — public NUXT_PUBLIC_* (브라우저가 공개 issuer로 리다이렉트)
#
# ※ 터널(cloudflared)은 LaunchAgent로 별도 자동 실행됨 — 이 스크립트는 origin 서비스만 띄운다.
# ※ 워커/interview-service는 띄우지 않음(별도, quota 소비) → 필요하면 ./scripts/start-all.sh.
#
# 사용:   ./scripts/start-public.sh
# 상태:   ./scripts/status.sh          (이미 :9000/:3001/:8090 체크함)
# 중지:   ./scripts/stop-all.sh        (.run/*.pid 기준)
# 로그:   .run/{auth,api,front}.log     PID: .run/{auth,api,front}.pid
#
# 도메인 변경 시: AUTH_ORIGIN / APP_ORIGIN 환경변수로 override 가능.
# 환경값을 다시 강제 적용하려면 먼저 ./scripts/stop-all.sh 후 재실행(이미 떠 있으면 skip 함).
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # = netisMaker
WORKSPACE="$(cd "$ROOT/.." && pwd)"                       # = IdeaProjects
AUTH_DIR="$WORKSPACE/netis-auth"
FRONT_DIR="$ROOT/frontend"
RUN_DIR="$ROOT/.run"
mkdir -p "$RUN_DIR"

# ── 공개 설정 (도메인 바뀌면 여기만) ────────────────────────────
AUTH_ORIGIN="${AUTH_ORIGIN:-https://auth.micthebick.dev}"
APP_ORIGIN="${APP_ORIGIN:-https://app.micthebick.dev}"
GRADLE="./gradlew"

is_alive() { [ -f "$1" ] && kill -0 "$(cat "$1" 2>/dev/null)" 2>/dev/null; }

wait_for_log() {  # logfile pattern label [max_loops=60]
  local log="$1" pat="$2" label="$3" max="${4:-60}" i=0
  until grep -qE "$pat" "$log" 2>/dev/null; do
    sleep 2; i=$((i + 1))
    if [ "$i" -gt "$max" ]; then echo "  ✗ $label: 기동 신호 없음(${max}x2s) — $log 확인"; return 1; fi
  done
}

wait_for_port() {  # port label [max_loops=30]
  local p="$1" label="$2" max="${3:-30}" i=0
  until lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; do
    sleep 2; i=$((i + 1))
    if [ "$i" -gt "$max" ]; then echo "  ✗ $label(:$p): LISTEN 안 됨 — 로그 확인"; return 1; fi
  done
}

echo "netisMaker 공개 스택 기동"
echo "  auth=$AUTH_ORIGIN   app=$APP_ORIGIN"
echo "  AUTH_DIR=$AUTH_DIR"
echo ""

# ── cloudflared 터널 상태 안내 (이 스크립트는 터널을 관리하지 않음) ──
if pgrep -f "cloudflared.*tunnel run" >/dev/null 2>&1; then
  echo "● cloudflared 터널: 실행 중 ✓"
else
  echo "● cloudflared 터널: 미실행 ⚠"
  echo "    (LaunchAgent라 로그인 시 자동 시작. 즉시 띄우려면:"
  echo "     launchctl bootstrap gui/\$(id -u) ~/Library/LaunchAgents/com.cloudflare.cloudflared.plist )"
fi
echo ""

# ── 1) netis-auth (:9000) ──────────────────────────────────────
if is_alive "$RUN_DIR/auth.pid"; then
  echo "● auth 이미 실행 중 (pid $(cat "$RUN_DIR/auth.pid")) — skip"
else
  echo "● auth 기동 중... (:9000)"
  (
    cd "$AUTH_DIR" || { echo "  ✗ $AUTH_DIR 없음"; exit 1; }
    nohup env \
      AUTH_ISSUER_URI="$AUTH_ORIGIN" \
      SERVER_FORWARD_HEADERS_STRATEGY=framework \
      CORS_ALLOWED_ORIGINS="$APP_ORIGIN,http://localhost:9000,http://localhost:3001,http://localhost:3000,http://localhost:8080" \
      "$GRADLE" bootRun -q > "$RUN_DIR/auth.log" 2>&1 &
    echo $! > "$RUN_DIR/auth.pid"
  )
  if wait_for_log "$RUN_DIR/auth.log" 'Started .*Application|APPLICATION FAILED|BUILD FAILED' "auth" 90; then
    grep -qE 'Started .*Application' "$RUN_DIR/auth.log" \
      && echo "  ✓ auth ready (pid $(cat "$RUN_DIR/auth.pid"))" \
      || echo "  ✗ auth 기동 실패 — $RUN_DIR/auth.log 확인"
  fi
fi

# ── 2) netisMaker API (:8090) ──────────────────────────────────
if is_alive "$RUN_DIR/api.pid"; then
  echo "● API 이미 실행 중 (pid $(cat "$RUN_DIR/api.pid")) — skip"
else
  echo "● API 기동 중... (:8090)"
  (
    cd "$ROOT" || exit 1
    nohup env \
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="http://localhost:9000/oauth2/jwks" \
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$AUTH_ORIGIN" \
      CORS_ALLOWED_ORIGINS="$APP_ORIGIN,http://localhost:3001,http://localhost:3000" \
      "$GRADLE" bootRun -q > "$RUN_DIR/api.log" 2>&1 &
    echo $! > "$RUN_DIR/api.pid"
  )
fi

# ── 3) netisMaker 프론트 (:3001) — 프로덕션 빌드(Nitro 서버) ────
# dev 서버(npm run dev) 대신 프로덕션 빌드로 띄운다 → CDN 친화적 자산 MIME + HMR/hydration 노이즈 제거.
# 프록시(/api,/oauth2)는 nuxt.config의 nitro.routeRules가 담당(devProxy는 prod에서 무효).
# 프론트 코드 변경분을 반영하려면 .output을 지우거나 `cd frontend && NUXT_IGNORE_LOCK=1 npm run build` 후 재기동.
if is_alive "$RUN_DIR/front.pid"; then
  echo "● front 이미 실행 중 (pid $(cat "$RUN_DIR/front.pid")) — skip"
else
  echo "● front 기동 중... (:3001, prod build)"
  (
    cd "$FRONT_DIR" || { echo "  ✗ $FRONT_DIR 없음"; exit 1; }
    if [ ! -f .output/server/index.mjs ]; then
      echo "  · .output 없음 → 빌드 (npm run build)"
      NUXT_IGNORE_LOCK=1 npm run build || { echo "  ✗ 프론트 빌드 실패 — $RUN_DIR/front.log 확인"; exit 1; }
    fi
    nohup env \
      PORT=3001 HOST=0.0.0.0 \
      NUXT_PUBLIC_AUTH_ISSUER="$AUTH_ORIGIN" \
      NUXT_PUBLIC_REDIRECT_URI="$APP_ORIGIN/oauth/callback" \
      NUXT_PUBLIC_CLIENT_ID=netis-maker-spa \
      node .output/server/index.mjs > "$RUN_DIR/front.log" 2>&1 &
    echo $! > "$RUN_DIR/front.pid"
  )
fi

# ── 준비 대기 (API는 로그, 프론트는 포트) ──────────────────────
echo ""
echo "● 준비 대기..."
if wait_for_log "$RUN_DIR/api.log" 'Started .*Application|APPLICATION FAILED|BUILD FAILED' "API" 90; then
  grep -qE 'Started .*Application' "$RUN_DIR/api.log" \
    && echo "  ✓ API ready (:8090)" \
    || echo "  ✗ API 기동 실패 — $RUN_DIR/api.log 확인"
fi
wait_for_port 3001 "front" 45 && echo "  ✓ front ready (:3001)"

echo ""
echo "✅ 완료 — 공개 주소: $APP_ORIGIN   (로그인: admin / password123)"
echo "   상태: ./scripts/status.sh   중지: ./scripts/stop-all.sh"
echo "   ※ 이전에 깨진 화면을 본 브라우저는 캐시 1회 비우기(이전 max-age 잔존)."
