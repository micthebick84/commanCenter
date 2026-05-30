#!/usr/bin/env bash
#
# netisMaker 스택 상태 한눈에: PID 생존 + 포트 LISTEN + 워커 heartbeat.
#
# 사용: ./scripts/status.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
API_PORT="${API_PORT:-8090}"

echo "── 프로세스 (.run/*.pid) ──"
if [ -d "$RUN_DIR" ] && ls "$RUN_DIR"/*.pid >/dev/null 2>&1; then
  for pidf in "$RUN_DIR"/*.pid; do
    name="$(basename "$pidf" .pid)"
    pid="$(cat "$pidf" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "  ✓ $name (pid $pid)"
    else
      echo "  ✗ $name (죽음 — pidfile stale)"
    fi
  done
else
  echo "  (기동된 프로세스 없음)"
fi

echo ""
echo "── 포트 LISTEN ──"
for p in "$API_PORT" 3001 9000; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "  ✓ :$p"
  else
    echo "  ✗ :$p"
  fi
done

echo ""
echo "── API health ──"
curl -fs "http://localhost:${API_PORT}/actuator/health" 2>/dev/null && echo "" || echo "  ✗ /actuator/health 응답 없음"
