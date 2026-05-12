#!/usr/bin/env bash
# DESIGN §17 스파이크 #1 — claude -p 한국어 분석 일관성 검증
#
# 실행 전 사전 조건:
#   - macOS에서 `claude` CLI 설치 + `claude login` 1회 수행
#   - 분석할 GitHub 레포 URL 준비 (Netis 계열 권장)
#
# 사용법:
#   ./scripts/spike-1-claude-korean.sh <github_url> [<task_title>] [<task_description>]
#
# 예:
#   ./scripts/spike-1-claude-korean.sh https://github.com/<owner>/netis-backend.git \
#     "로그인 버그 수정" \
#     "세션 쿠키 만료 시 흰 화면이 보입니다. 원인 분석 + 수정 계획 부탁."

set -euo pipefail

REPO_URL="${1:-}"
TITLE="${2:-로그인 버그 수정}"
DESC="${3:-세션 쿠키 만료 시 흰 화면 발생. 원인 분석 + 수정 계획 부탁드립니다.}"

if [[ -z "$REPO_URL" ]]; then
  echo "사용법: $0 <github_url> [title] [description]" >&2
  exit 1
fi

if ! command -v claude >/dev/null 2>&1; then
  echo "ERROR: claude CLI가 PATH에 없습니다. 'brew install anthropic-ai/claude/claude' 또는 OSS 설치 후 'claude login' 1회 수행." >&2
  exit 1
fi

WORK_DIR=$(mktemp -d -t netis-spike-XXXXXXXX)
trap 'rm -rf "$WORK_DIR"' EXIT

echo "▶ 작업 디렉토리: $WORK_DIR"
echo "▶ 1단계: 레포 clone (--depth=1)"
git clone --depth=1 "$REPO_URL" "$WORK_DIR/repo" 2>&1 | sed 's/^/    /'

cd "$WORK_DIR/repo"
COMMIT_SHA=$(git rev-parse HEAD)
BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "▶ 2단계: §11 프롬프트 구성"
PROMPT_FILE="$WORK_DIR/prompt.txt"
cat > "$PROMPT_FILE" <<EOF
당신은 코드베이스 분석 어시스턴트입니다. 모든 출력은 반드시 한국어로 작성하세요.

레포: $REPO_URL
브랜치: $BRANCH (현재 커밋: $COMMIT_SHA)
요청자 작업 제목: $TITLE
요청 상세:
$DESC

아래 형식의 마크다운 보고서를 정확히 이 섹션 구조로 출력하세요.
섹션 제목과 번호는 변경하지 마세요 (파서가 사용합니다).

## 1. 요구사항 요약
(2~4문장으로 핵심 정리)

## 2. 영향 범위
- 수정 예상 파일: (경로 목록)
- 추가 예상 파일: (경로 목록)
- 영향 받는 모듈/패키지

## 3. 접근 방법
(1~3개 문단으로 구현 전략 설명)

## 4. Subtask 분해
각 항목은 다음 형식 (반드시 이 구조 유지):
- **제목**: ...
  - 대상 파일: \`path/to/file\`, \`path/to/file2\`
  - 예상 LoC: 약 N줄
  - 위험도: L | M | H
  - 설명: ...

## 5. 위험 요소 및 미해결 질문
- ...
EOF

echo "▶ 3단계: claude -p 실행 (timeout 10분)"
RESULT_FILE="$WORK_DIR/result.md"
TIMING_FILE="$WORK_DIR/timing.txt"

START=$(date +%s)
if ! timeout 600 claude -p "$(cat "$PROMPT_FILE")" > "$RESULT_FILE" 2>&1; then
  EXIT=$?
  END=$(date +%s)
  echo "    ✗ claude 실패 (exit=$EXIT, $((END-START))초)"
  echo "    출력 (앞 50줄):"
  head -50 "$RESULT_FILE" | sed 's/^/      /'
  exit 1
fi
END=$(date +%s)
DURATION=$((END - START))
echo "    ✓ 완료 ($DURATION초)"
echo "$DURATION" > "$TIMING_FILE"

echo
echo "════════════════════════════════════════════════════════════"
echo "  결과 보고서: $RESULT_FILE"
echo "════════════════════════════════════════════════════════════"
echo
head -100 "$RESULT_FILE"
echo
echo "..."
echo

echo "▶ 4단계: 검증 포인트"
PASS=0
FAIL=0

check() {
  if [[ $1 -eq 0 ]]; then
    echo "    ✓ $2"; PASS=$((PASS+1))
  else
    echo "    ✗ $2"; FAIL=$((FAIL+1))
  fi
}

grep -q '^## 1\.' "$RESULT_FILE"; check $? "§1 요구사항 요약 섹션 존재"
grep -q '^## 2\.' "$RESULT_FILE"; check $? "§2 영향 범위 섹션 존재"
grep -q '^## 3\.' "$RESULT_FILE"; check $? "§3 접근 방법 섹션 존재"
grep -q '^## 4\.' "$RESULT_FILE"; check $? "§4 Subtask 분해 섹션 존재"
grep -q '^## 5\.' "$RESULT_FILE"; check $? "§5 위험 요소 섹션 존재"

# 한국어 비율 검증 (간이)
KO=$(LC_ALL=C grep -oE '[가-힣]' "$RESULT_FILE" | wc -l | tr -d ' ')
EN=$(LC_ALL=C grep -oE '[A-Za-z]' "$RESULT_FILE" | wc -l | tr -d ' ')
RATIO=$(awk "BEGIN{ printf \"%.0f\", ($KO / ($KO + $EN + 0.001)) * 100 }")
if [[ "$RATIO" -ge 30 ]]; then
  echo "    ✓ 한국어 비율 ${RATIO}% (KO:$KO / EN:$EN)"
  PASS=$((PASS+1))
else
  echo "    ✗ 한국어 비율 ${RATIO}% 너무 낮음 (KO:$KO / EN:$EN)"
  FAIL=$((FAIL+1))
fi

# Subtask 형식 검증
SUBTASK_COUNT=$(grep -cE '^- \*\*[^*]+\*\*\s*:' "$RESULT_FILE" || true)
if [[ "$SUBTASK_COUNT" -gt 0 ]]; then
  echo "    ✓ Subtask 항목 ${SUBTASK_COUNT}개 추출됨 (- **제목**: ... 형식)"
  PASS=$((PASS+1))
else
  echo "    ⚠ Subtask 항목 0개 — 프롬프트 또는 §11 파서 튜닝 필요"
fi

echo
echo "▶ 종합 (소요시간 ${DURATION}초 / 검증 PASS=${PASS} FAIL=${FAIL})"
echo
echo "결과 파일 보관: cp $RESULT_FILE \$HOME/netis-spike-result.md"
echo "DESIGN.md §13의 'V1 측정 지표 — 평균 분석 시간 ≤ 7분' 기준 적용"
