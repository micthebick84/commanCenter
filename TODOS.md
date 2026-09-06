# netisMaker TODOS (post-V1)

## V1.1 — 운영 안정성

### [ ] 레포 캐시 자동 정리 정책

**What**: macOS 운영자 PC의 `~/netis-maker/repos/{owner}/{repo}/`에 누적되는 레포 캐시를 주기적으로 정리하는 자동화.

**Why**: Netis 레포 1개당 수GB. V1 운영 중 다양한 레포 분석 누적되면 운영자 macOS 디스크가 차서 워커 정지 시나리오 발생 가능.

**Pros**:
- 운영 리스크 사전 차단
- 운영자 수동 개입 불필요
- 디스크 모니터링 알림 없이도 안전

**Cons**:
- 정책 결정 필요 (얼마 미사용이면 삭제? 디스크 사용량 임계점?)
- 다시 사용되는 레포는 재clone 시간 발생

**Context**:
- V1 §15 #6에 이미 명시된 deferred 항목
- 후보 정책: ① 30일 미사용 레포 자동 삭제, ② 디스크 사용량 80% 초과 시 LRU 정리, ③ 운영자 수동 트리거
- 워커 데몬 내부 `@Scheduled(cron="0 0 3 * * *")` 새벽 3시 정리 잡 + admin UI 트리거 버튼
- 정리 후 task_analysis에 사용된 레포 캐시 메타데이터 기록 → 통계로 추적

**Depends on / blocked by**:
- V1 운영 데이터 1~3개월 누적 (어떤 레포가 자주 쓰이는지 패턴 보고 정책 결정)
- 운영자 macOS 디스크 가용량 베이스라인 측정

**우선순위**: 중간. V1 디스크 압박이 실제로 발생하면 즉시 작업 착수.

### [ ] 첨부파일 정리 정책

**What**: 작업 등록 시 첨부된 파일이 저장되는 `~/netis-maker/attachments/task-{id}/`를 정리하는 자동화/정책. 현재는 생성 후 자동 삭제가 전혀 없다(인터뷰 `work_dir`와 동일 정책).

**Why**: task가 soft-delete되거나 완료 후 오래 방치돼도 디렉터리는 그대로 남는다. 등록이 반복될수록 운영자 macOS 디스크에 무기한 누적되어, 위 "레포 캐시 자동 정리 정책"과 동일한 디스크 압박 실패 시나리오로 이어질 수 있다.

**Pros**:
- 운영 리스크 사전 차단 (레포 캐시와 같은 이유)
- task soft-delete 시 같이 지우면 사용자 관점에서도 "삭제됐다"는 기대와 일치

**Cons**:
- soft-delete된 task를 나중에 복구/재조회해야 하는 경우가 생기면 첨부도 같이 날아가 복구 불가
- 보존 기한 정책 결정 필요 (며칠/몇 개월? 상태별로 다르게?)

**Context**:
- 제한값은 `application.yml`의 `app.attachment.*`: per-file 20MB, task당 최대 10개, task당 합계 50MB
- 즉 worst case는 task 1건당 50MB가 무기한 누적 — 레포 캐시 항목(레포 1개당 수GB)보다 단위는 작지만 등록 빈도가 훨씬 높아 누적 속도가 다를 수 있음
- 후보안: ① task soft-delete 시 디렉터리 즉시 삭제, ② 보존 기한(예: 90일) 경과 시 배치 삭제, ③ 위 레포 캐시 정리 잡과 통합해 같은 새벽 스케줄러에서 처리

**Depends on / blocked by**:
- soft-delete 이후 첨부 복구가 실제로 필요한 운영 시나리오가 있는지 확인
- 레포 캐시 정리 정책(위 항목)과 동일 스케줄러로 묶을지 별도로 갈지 결정

**우선순위**: 낮음. 파일당/건당 상한이 이미 걸려 있어 레포 캐시보다 급하지 않음. 등록 건수가 누적되며 디스크 압박 조짐이 보이면 착수.

### [ ] 프론트 테스트가 생성물 `.nuxt`에 의존

**What**: `frontend/tsconfig.json`이 Nuxt 빌드 시점 생성물인 `./.nuxt/tsconfig.json`을 extends한다. 이 파일은 `nuxi prepare`(보통 `postinstall`이 실행)가 만들어야 존재한다.

**Why**: 신규 clone, 새 git worktree, 또는 `node_modules`만 캐시하고 `.nuxt`는 캐시하지 않는 CI 잡에서는 `.nuxt/tsconfig.json`이 없다. 이 상태에서 vitest를 돌리면 테스트를 한 건도 수집하기 전에 transform 단계에서 `Cannot find module './.nuxt/tsconfig.json'`로 죽는다. 에러 메시지가 실제 원인(Nuxt prepare 미실행)을 가리키지 않아 원인 파악에 시간이 든다.

**Pros**:
- CI에서 원인불명 실패로 시간 낭비하는 일을 사전 차단
- 신규 기여자/새 워크트리에서 온보딩 마찰 감소 (지금은 `.nuxt` 심볼릭 링크라는 비공식 우회를 각자 알아내야 함)

**Cons**:
- `.nuxt` 캐싱을 선택하면 CI 캐시 용량/무효화 관리가 하나 더 늘어남
- extends를 그만두고 tsconfig를 직접 명시하면 Nuxt가 관리하는 경로 별칭(alias) 등을 수동으로 동기화해야 할 수 있음

**Context**:
- 이번 `spec/task-attachments` 브랜치 작업 중 리뷰어 2명이 독립적으로 이 문제를 겪었고, 각자 `.nuxt`를 심볼릭 링크로 우회해 진행함
- 복구법: `nuxi prepare` 명시 실행. `postinstall`이 보통 이를 실행하지만, `node_modules`만 복원되고 `npm install`을 다시 안 돌리면 postinstall도 안 돈다
- 후보 해결책: ① CI에서 테스트 전에 `nuxi prepare`를 명시적으로 실행, ② `node_modules`와 함께 `.nuxt`도 캐싱, ③ 생성물을 extends하지 않도록 tsconfig를 직접 명시

**Depends on / blocked by**:
- CI 파이프라인 구성 확정 (어느 방식으로 고정할지는 CI 셋업 작업과 함께 결정)

**우선순위**: 중간. 로컬에서는 매번 걸리는 건 아니라 급하지 않지만, CI를 새로 구축하는 시점에는 반드시 셋업 단계에 포함해야 함 (안 그러면 첫 CI 실행부터 전원 매몰).

### [ ] 질문 대화 중 모델·effort 변경

**What**: 대화 중 입력창 툴바의 모델/추론 단계 픽커를 활성화해 다음 턴부터 다른 모델/effort로 답하게 한다. 현재는 세션 생성 시 고정(읽기 전용 표시).

**Why**: 긴 질문 세션에서 "이 질문만 Haiku로 싸게" 같은 요구가 나올 수 있다. 다만 `InterviewSession.model/effort`가 세션 단위이고, SDK `resume` 세션에서 모델 교체가 컨텍스트/캐시에 미치는 영향이 미검증이다.

**Context**: 스펙 `docs/superpowers/specs/2026-09-05-question-chat-ui-design.md` §9. 서버 `PATCH /api/questions/{id}/model` + `ModelEffortPolicy.validate` + 다음 claim에 반영, 프론트 `QuestionComposer mode="ask"`의 `disabled` 해제.

**우선순위**: 낮음. 요청이 실제로 나오면 착수.

### [ ] Claude Code 사용량 유휴 갱신 프로브

**What**: 인터뷰 서비스가 유휴일 때도 주기적으로(예: 10분) 초경량 SDK 쿼리(haiku, maxTurns 1)를 돌려 `rate_limit_event`를 받아 사용량 패널을 신선하게 유지한다. 현재는 SDK 턴이 돌 때만 갱신되고 UI가 "N분 전 갱신"으로 정직하게 표기한다.

**Why**: 오래 유휴한 뒤 첫 질문 전에 남은 한도를 정확히 보고 싶을 때. 대신 프로브마다 쿼터를 소모한다(운영 트레이드오프).

**Context**: 스펙 §2 "사용량 신선도"·§9. `config.ts`에 `USAGE_PROBE_INTERVAL_MS`(0=off) 추가, `ClaimLoop` 유휴 틱에서 `RateLimitReporter`로 보고. Java 워커(`claude -p --output-format json`)는 이벤트가 없어 대상이 아니다.

**우선순위**: 낮음. 패널의 "N분 전 갱신" 표기로 운영해 보고 불편이 확인되면 착수.

### [ ] 답변 마크다운의 이미지 렌더 차단 검토

**What**: `useMarkdown.ts`가 `html:false`로 원본 HTML 태그는 막지만, `![](https://…)` 형태의 마크다운 이미지 문법은 markdown-it 기본값 그대로 렌더된다.

**Why**: Q&A 에이전트가 레포 파일을 읽어 답변을 구성하므로, 레포 어딘가에 프롬프트 인젝션이 섞여 있으면 외부 이미지 URL 요청을 통해 데이터가 유출되는 채널이 될 수 있다(다만 대상 레포가 관리자 큐레이션 목록이라 실제 발생 확률은 낮다).

**Context**: 스펙 2026-09-05 §2는 markdown-it 기본값을 그대로 수용하기로 했고, 이미지 렌더를 어떻게 할지는 결정이 미뤄져 있다. 후보: `md.disable('image')`로 전면 차단, 또는 same-origin/`data:` URL만 허용하는 렌더 규칙 추가.

**우선순위**: 중간. 실제 악용 확률은 낮지만 결정 없이 계속 미루면 기본값이 그대로 굳어진다.

### [ ] deriveTitle 서로게이트 페어 절단·마크다운 기호 정리

**What**: `QuestionService.deriveTitle`이 `substring(0, 60)`으로 제목을 자르는데, 이모지 등 서로게이트 페어 경계에서 자르면 lone surrogate가 남을 수 있다. 또한 질문 첫 줄에 있던 `#`/`>`/목록 기호/백틱 같은 마크다운 문법 기호가 제목에 그대로 섞여 들어간다.

**Why**: lone surrogate는 깨진 문자로 렌더되거나 이후 직렬화/전송 과정에서 문제를 일으킬 수 있고, 마크다운 기호가 섞인 제목은 목록/사이드바에서 지저분하게 보인다.

**Context**: 코드포인트 경계를 존중하는 절단(`offsetByCodePoints` 등)으로 교체 검토. 첫 줄 선행 `#`/`>`/`-`/`*`/백틱 등 제거도 함께 검토.

**우선순위**: 낮음. 드물게 발생하고 화면이 깨지는 정도라 급하지 않다.

### [ ] 질문 셸 SSR 하이드레이션 점검

**What**: `pages/questions.vue`가 `$q.screen.gt.sm` 값으로 사이드바(데스크톱)와 서랍(좁은 화면)을 구조적 `v-if`로 나눠 그리는데, SSR 시점에는 실제 화면 폭을 알 수 없어 narrow 쪽 마크업으로 렌더된다.

**Why**: 데스크톱 브라우저 최초 로드 시 SSR 결과(서랍/narrow)와 하이드레이션 후 클라이언트 결과(사이드바/wide)가 달라 하이드레이션 불일치 경고나 화면 깜빡임이 생길 수 있다.

**Context**: 스모크 체크리스트 항목 1·7에서 실제로 재현되는지부터 확인. 재현되면 구조적 `v-if` 대신 `gt-sm`/`lt-md` 가시성 클래스로 두 마크업을 함께 렌더하거나, 사이드바를 `<ClientOnly>`로 감싸는 방안을 검토.

**우선순위**: 중간. 스모크에서 재현되지 않으면 낮음으로 낮춰도 된다.

### [ ] 모바일 사용량 스트립 라벨·신선도

**What**: `ClaudeUsagePanel`의 strip 변형이 쓰는 `shortLabel`이 five_hour 항목/그 외 항목 이항 분기라, 응답에 five_hour가 없으면 라벨이 잘못 표기될 수 있다. 또한 strip에는 갱신 시각을 알려주는 툴팁이 없다.

**Why**: 모바일에서는 strip만 보이므로 라벨 오표기가 그대로 노출된다. 패널(데스크톱)에는 이미 갱신 시각 정보가 있는데 strip에는 없어 신선도를 알 수 없다.

**Context**: R8 결정("캔버스는 strip에 문구 없음")과 균형이 필요하다 — `updatedLabel`을 툴팁으로만 붙이면 캔버스가 요구한 "문구 없음"을 해치지 않으면서 신선도 정보를 줄 수 있다.

**우선순위**: 낮음.

### [ ] 사용량 API 계약 테스트 보강

**What**: `POST /worker/usage/rate-limits`에 `"isUsingOverage": true`를 보내고 `GET /api/usage/claude`가 `usingOverage: true`를 돌려주는지 확인하는 Java 통합 테스트가 없다.

**Why**: Jackson record 바인딩(`isUsingOverage` ↔ `usingOverage` 필드명 매핑)이 리팩터링 중 조용히 깨져도 지금은 잡아낼 테스트가 없다.

**Context**: 기존 사용량 API 통합 테스트에 이 계약을 고정하는 단언 1건만 추가하면 된다.

**우선순위**: 낮음.

### [ ] SSE 재생이 system 노트 턴을 question 이벤트로 보냄

**What**: `InterviewStreamService.subscribe`의 replay가 user 외 모든 턴을 (design 제외) `question` 이벤트로 보내서, system/note 턴("사용자 취소")이 클라이언트 `onQuestion`으로 들어간다. 프론트는 REST 스냅샷 hydrate(2026-09-06, system→note 매핑)가 먼저 seq를 채우고 replay는 seq dedup으로 무시되므로 실제 표시는 정상이지만, 스냅샷 요청이 실패한 경우엔 노트가 AI 말풍선으로 그려지고 `AWAITING_INPUT`으로 잘못 전환될 수 있다.

**Why**: 서버가 역할을 이벤트 타입으로 구분해 보내야 클라이언트가 페이로드(`{seq, content}`)만으로 판단할 수 있다. 지금은 프론트의 hydrate-first 순서에 의존한다.

**Context**: 서버에 `note` 이벤트(`{seq, content}`) 추가 + 프론트 `es.addEventListener('note')` → `pushTurn(system/note)`. Java 변경이라 API 재기동이 필요해 2026-09-06 프론트 핫픽스(모바일 폴리시 PR)에서는 제외했다.

**우선순위**: 낮음.
