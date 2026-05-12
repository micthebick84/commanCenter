#!/usr/bin/env bash
# Testcontainers 기반 통합 테스트 실행 (7개 케이스).
#
# 환경 의존: Docker Desktop의 Docker AI Agent가 socket을 가로채면 실패.
# 사전 조건:
#   1. Docker Desktop > Settings > Beta features > Docker AI Agent → 비활성화
#   2. Docker Desktop 재시작
#   3. `docker info` 정상 응답 확인
#
# Docker AI Agent를 끄지 않은 채로 돌리면 7개 모두 실패 (BadRequest 400).

set -euo pipefail
cd "$(dirname "$0")/.."

echo "▶ Docker 환경 사전 검증"
if ! docker info > /dev/null 2>&1; then
  echo "    ✗ Docker daemon 응답 없음. Docker Desktop이 실행 중인지 확인하세요." >&2
  exit 1
fi

# Docker AI Agent 가로채기 감지: /info에서 Containers 정보가 비어있으면 의심
DOCKER_NCPU=$(docker info --format '{{.NCPU}}' 2>/dev/null || echo 0)
if [[ "$DOCKER_NCPU" == "0" ]]; then
  cat >&2 <<MSG
    ✗ Docker daemon이 빈 /info 응답을 반환합니다.
      Docker AI Agent가 socket을 가로채는 상태로 보입니다.

      해결: Docker Desktop > Settings > Beta features → Docker AI Agent 비활성화
            그리고 Docker Desktop 재시작.

      현재 docker info 요약:
$(docker info 2>&1 | head -10 | sed 's/^/        /')
MSG
  exit 1
fi
echo "    ✓ Docker daemon OK (NCPU=$DOCKER_NCPU)"

echo
echo "▶ Testcontainers 통합 테스트 실행 (7개)"
echo
RUN_TESTCONTAINERS=true ./gradlew test --tests "TaskApiIntegrationTest" 2>&1 | tail -30

echo
echo "▶ 종합"
RESULT_DIR=build/test-results/test
if [[ -f "$RESULT_DIR/TEST-com.hamonsoft.netismaker.controller.TaskApiIntegrationTest.xml" ]]; then
  RESULT="$RESULT_DIR/TEST-com.hamonsoft.netismaker.controller.TaskApiIntegrationTest.xml"
  TOTAL=$(grep -oE 'tests="[0-9]+"' "$RESULT" | head -1 | grep -oE '[0-9]+')
  FAILED=$(grep -oE 'failures="[0-9]+"' "$RESULT" | head -1 | grep -oE '[0-9]+')
  SKIPPED=$(grep -oE 'skipped="[0-9]+"' "$RESULT" | head -1 | grep -oE '[0-9]+')
  PASSED=$((TOTAL - FAILED - SKIPPED))
  echo "    TOTAL=$TOTAL PASSED=$PASSED FAILED=$FAILED SKIPPED=$SKIPPED"
fi
