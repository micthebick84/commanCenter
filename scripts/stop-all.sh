#!/usr/bin/env bash
#
# netisMaker 전체 스택 중지: .run/*.pid 기준으로 API + 워커 + 인터뷰 서비스 전부 종료.
#
# 사용: ./scripts/stop-all.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"

if [ ! -d "$RUN_DIR" ]; then
  echo "기동된 프로세스 없음 (.run/ 디렉토리 없음)"
  exit 0
fi

stopped=0
for pidf in "$RUN_DIR"/*.pid; do
  [ -e "$pidf" ] || continue
  name="$(basename "$pidf" .pid)"
  pid="$(cat "$pidf" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    # gradle bootRun 은 자식 JVM을 띄우므로 프로세스 그룹째 종료
    kill "$pid" 2>/dev/null || true
    # 자식(JVM)까지 정리: pid를 부모로 갖는 프로세스 종료
    pkill -P "$pid" 2>/dev/null || true
    echo "● $name 중지 (pid $pid)"
    stopped=$((stopped + 1))
  else
    echo "● $name 이미 종료됨"
  fi
  rm -f "$pidf"
done

# gradle 데몬이 남긴 bootRun JVM 잔여물 정리 (pidfile로 못 잡은 경우 대비)
sleep 2
leftover="$(pgrep -f 'NetisMakerApplication' || true)"
if [ -n "$leftover" ]; then
  echo "● 잔여 NetisMakerApplication JVM 정리: $leftover"
  echo "$leftover" | xargs kill 2>/dev/null || true
fi

echo "완료 (${stopped}개 종료)."
