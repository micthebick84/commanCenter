# Phase 0 스파이크 결과 (SDK De-risking Findings)

- **날짜**: 2026-06-13
- **환경**: macOS, node v24.14.1, npm 11.11.0, `@anthropic-ai/claude-agent-sdk@0.2.117`, `claude` CLI 2.1.177 (`/Users/micthebick/.local/bin/claude`), superpowers 플러그인 5.1.0
- **요약**: 설계의 load-bearing 가정 4개 중 **핵심 2개(인증·resume)와 스킬 로드를 검증 완료, 모두 PASS**. 장시간 idle(스파이크 1)·동시성(스파이크 3)은 **resume-per-answer 설계가 위험을 제거**하므로 보류.

---

## 스파이크 00b — 구독 인증 (auth model) ✅ PASS / 설계 정정 유발

- **결과**: SDK가 `options.pathToClaudeCodeExecutable`로 로컬 `claude` CLI **구독을 그대로 사용**. `ANTHROPIC_API_KEY` 미설정 상태에서 `apiKeySource: "none"`, 쿼리 정상 성공(`PONG`).
- **`total_cost_usd`(~$0.26)는 shadow 값** — 실제 청구가 아니라 PAYG였다면 들었을 비용 계산치. 구독이 실제 과금을 커버.
- **설계 영향**: 스펙 §9/§15의 "API 키 전용·토큰 과금" 가정 **정정**. 인터뷰 엔진은 기존 netisMaker 워커처럼 **구독 모델**을 탄다. 비용 상한은 "달러"가 아니라 **세션당 turn/쿼터 가드**로 재해석.

## 스파이크 02 — 교차 프로세스 resume ✅ PASS / 제약 확정

- **Test 1 (동일 cwd)**: 프로세스 A가 `session_id` 캡처 후 종료 → 별도 프로세스 B가 `options.resume`로 재개 → 이전 대화(토큰 `BANANA-42`) **정확히 회상**. 동일 session_id 재로드 확인.
- **Test 2 (다른 cwd)**: **하드 실패** — `No conversation found with session ID`. Claude Code 세션 스토어는 `~/.claude/projects/<cwd-해시>/`로 **cwd에 종속**.
- **설계 영향 (제약 확정)**: "턴 사이 워커 반납 + resume" 모델은 동작하나, **resume 워커는 세션 시작 시와 동일한 `cwd`를 써야 함**. 따라서:
  - `interview_session`에 **`work_dir`(레포 체크아웃 경로) 컬럼을 추가**하고 session_id와 함께 저장, 모든 resume 호출에 동일 값 전달.
  - 인터뷰 워커 풀은 **단일 호스트/공유 FS**(이미 §9에 명시)여야 `~/.claude/projects/` 스토어를 공유. 교차 머신 resume은 범위 외(재확인).

## 스파이크 04 — superpowers 스킬 로드 & 핸드오프 ✅ PASS / 보안 설계 정정 유발

- **Check A (discovery)**: `init.skills`에 `superpowers:brainstorming`, `superpowers:writing-plans` 노출. `init.plugins`에 superpowers(`source: superpowers@inline`). `Skill` 툴이 `init.tools`에 존재.
- **Check B (invocation)**: `Skill` 툴이 `{skill:"superpowers:brainstorming"}`로 정상 발화 → 스킬 실행됨(`is_error:false`). 스킬 디스패치 메커니즘 확인.
- **caveat 1 (보안·중요)**: brainstorming이 `allowedTools=['Skill','Read','Grep','Glob']`에 **없던 `Bash`를 실행**. 즉 **스킬 내부 tool 호출은 부모 `allowedTools`로 제약되지 않음**. → **권한 제어는 반드시 `canUseTool` 콜백으로** 해야 함(모든 tool 호출을 가로채 Write 경로 제한·Bash 화이트리스트·레포 수정/푸시 차단). `allowedTools`만으론 불충분.
- **caveat 2 (격리)**: `settingSources:['user','project']`는 사용자의 **모든 플러그인**을 로드. superpowers만 격리하려면 `settingSources`를 빼고 `plugins:[{type:'local',path}]`만 사용(이 경우 `Skill` 툴이 init.tools에 그대로 있는지 확인 필요).
- **caveat 3**: 전이성 rate_limit_event 관찰(블로킹 아님) → 기존 워커 backoff 정책 적용.

## 스파이크 01 (30분 idle) — 보류 (설계상 불필요)

- §9가 **resume-per-answer**(턴 사이 인메모리 세션을 잡지 않음)를 기본으로 택했으므로, "장시간 열린 세션 idle" 경로 자체를 타지 않는다. 긴 대기는 스파이크 02가 검증한 resume로 처리. → **미실행, 설계상 N/A.** (만약 인메모리 홀드 최적화를 도입하면 그때 실측.)

## 스파이크 03 (동시성) — 보류 (용량 튜닝)

- resume-per-answer에서는 턴 사이 인터뷰당 살아있는 프로세스가 없어, 동시성 = (인터뷰 워커 수) × (활성 턴 중 SDK 서브프로세스). 하드 블로커가 아니라 **용량/쿼터 튜닝** 사안이며 Anthropic rate limit(기존 backoff)로 자연 직렬화. → 본 구현 후 부하 테스트로 이관.

---

## 종합 결정 (GO)

4개 핵심 가정 검증 완료 → **GO**. 설계는 다음 정정을 반영해 진행:

1. **인증 = 구독 (CLI 경유)**: `pathToClaudeCodeExecutable` 사용, `ANTHROPIC_API_KEY` 불필요. 토큰 과금 없음(쿼터만 관리).
2. **권한 = `canUseTool` 콜백으로 강제** (allowedTools 불충분). Write→`docs/superpowers/**` 한정, Bash 화이트리스트, 레포 수정/푸시 차단을 canUseTool에서 검사.
3. **resume = cwd 종속** → `interview_session.work_dir`(체크아웃 경로) 저장 + 모든 resume에 동일 cwd 전달. 단일 호스트/공유 FS 필수.
4. **플러그인 격리** = `plugins:[{type:'local',path}]` 사용, `settingSources`는 신중히(전체 플러그인 로드 주의).
5. SDK 버전 **0.2.117**로 plan 03 정렬(plan 03의 `^0.1.0` 수정).

→ 다음: plan 01–04를 교차검토(PLAN-REVIEW) + 위 5개 정정에 맞춰 정렬한 뒤 Phase 1(백엔드 도메인)부터 실행.
