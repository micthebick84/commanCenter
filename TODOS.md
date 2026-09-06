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

### [ ] 하단 내비 "확인 필요" 배지

**What**: 작업 탭 하단 내비(`q-footer`)의 "작업" 탭에 확인 필요 건수 배지를 붙인다.

**Why**: 스펙 `docs/superpowers/specs/2026-09-06-tasks-mobile-redesign-design.md` §4.4에서 "배지(확인 필요 건수)는 후속(YAGNI)"으로 명시적으로 범위에서 뺐다.

**Context**: `frontend/layouts/default.vue`의 푸터 `q-tabs`/`q-route-tab`("작업")에 붙이면 되고, 건수는 이미 있는 `composables/taskStages.ts`의 `sortForMobile`/`attentionGroup`(주의 필요 그룹 판정)을 재사용해 계산할 수 있다.

**우선순위**: 낮음.

### [ ] 전역 레이아웃 SSR 하이드레이션 불일치 (하단 내비/툴바 `$q.screen` 분기)

**What**: 모든 페이지(로그인 페이지 포함)에서 콘솔에 `Hydration completed but contains mismatches`가 1건 난다. 2026-09-06 승인 다이얼로그 작업 중 dev 서버(1280px)에서 원인을 확인했다: `layouts/default.vue`가 `$q.screen.lt.md`로 하단 내비 `q-footer`·툴바 버튼을 구조적 `v-if`로 나눠 그리는데, SSR은 화면 폭을 몰라 좁은 화면(푸터 있음) 마크업으로 렌더하고 클라이언트는 데스크톱(푸터 없음)으로 그려 `<QFooter>`·`<QToolbar>`의 `<QBtn>`·`QPageContainer`의 `padding-bottom`·`QPage`의 `min-height`가 어긋난다.

**Why**: 경고 자체는 무해하지만(프로덕션은 DOM을 고치지 않고 다음 렌더에서 정합), 실제 하이드레이션 문제(예: 질문 셸 항목)가 새로 생겨도 이 상시 경고에 묻혀 알아채기 어렵다. 데스크톱 최초 로드에서 푸터 높이만큼 레이아웃 점프도 생길 수 있다.

**Context**: 기존 항목 "질문 셸 SSR 하이드레이션 점검"과 같은 계열이며 범위가 전역 레이아웃이다. 후보: ① 푸터/툴바 분기를 구조적 `v-if` 대신 `lt-md`/`gt-sm` 가시성 클래스로 두 마크업을 함께 렌더, ② 푸터를 `<ClientOnly>`로 감싸기(SSR에서는 푸터 없음 → 데스크톱 기준 마크업이 되고 모바일은 클라이언트에서 붙음), ③ Quasar SSR `Screen` 플러그인에 UA 힌트로 초기 폭을 주기. `--bottom-nav-height` CSS 변수 계산도 같은 분기에 걸려 있으니 함께 검토.

**우선순위**: 낮음. 기능 영향 없음. 다음에 레이아웃을 만질 때 같이 처리.

### [ ] 모바일 목록 상세 필터 시트

**What**: 모바일 작업 목록(`TaskListMobile`)은 확인 필요/진행 중/완료/취소됨 4개 그룹 칩만 있고, 데스크톱의 `statusFilter`(승인대기/인터뷰중/분석실패 등 세부 상태별 필터) 같은 상세 필터가 없다. 세부 상태로 좁혀 보고 싶을 때 쓸 모바일 전용 필터 시트(바텀시트)를 추가한다.

**Why**: 리뷰 파인딩 11 수정으로 모바일 목록 조회는 데스크톱 `statusFilter` 값을 아예 무시하도록 고쳤다(그룹 칩과 섞여 혼란만 주던 상태였음). 그 결과 지금은 모바일에서 세부 상태로 좁혀 볼 방법이 전혀 없다 — 그룹 칩보다 세밀한 필터가 필요해지면 별도 시트로 제공해야 한다.

**Context**: `frontend/pages/tasks/index.vue`(`statusFilter`, `useTaskPolling` fetcher), `frontend/components/tasks/TaskListMobile.vue`(그룹 칩). 스펙 §9 "유보(후속)" 항목.

**우선순위**: 낮음.

### [ ] 인터뷰 "대화 열기" 전체화면 시트

**What**: 모바일 작업 상세의 "대화 열기"(⋮ 메뉴 `bar-more-interview`, 다음 할 일 primary)는 현재 `scrollTo('interview-card')`로 인라인 `InterviewPanel` 카드까지 스크롤만 한다. 스펙 §4.2 원안은 입력창이 하단 액션 바와 겹치지 않도록 전체화면 시트로 여는 것이었다.

**Why**: 인라인 패널은 하단 고정 액션 바(`next-action--bar`)와 겹치거나, 좁은 화면에서 입력 UX가 답답할 수 있다.

**Context**: `frontend/pages/tasks/[id].vue`의 `onAction('open-interview')` → `scrollTo`. 전체화면 시트로 바꾸려면 `InterviewPanel`을 `q-dialog maximized`로 감싸는 래퍼가 필요. 스펙 §9 "유보(후속)" 항목.

**우선순위**: 낮음.

### [ ] 태블릿(600–1023px) 다이얼로그 inline width

**What**: 다이얼로그 공통 규칙(§4.3)이 `:maximized="$q.screen.lt.md"`라 1024px 미만은 폰이든 태블릿이든 전부 풀스크린이 된다. 태블릿 폭(600–1023px)에서는 화면이 넓어 굳이 풀스크린보다 `width: min(Npx, 100vw)` inline 다이얼로그가 나을 수 있다.

**Why**: 태블릿에서 작업 등록·배포 환경변수 같은 짧은 폼까지 전체화면으로 뜨면 상하 여백이 과하게 남아 답답해 보일 수 있다.

**Context**: `frontend/assets/css/main.css`의 `.q-dialog__inner--maximized` 규칙, 각 다이얼로그의 `:maximized="$q.screen.lt.md"` 바인딩. 별도 브레이크포인트(예: `$q.screen.lt.sm`)를 추가로 들여올지, 아니면 현행 단일 브레이크포인트 규칙(CLAUDE.md)을 유지할지부터 결정 필요. 스펙 §9 "유보(후속)" 항목.

**우선순위**: 낮음.

### [ ] `getHistory` Top-N 쿼리 + 동일 `at` 정렬 안정화

**What**: `TaskService.getHistory`(`historyRepo.findByTaskIdOrderByAtDesc(taskId).stream().limit(limit).toList()`)가 DB에서 전체 이력을 다 가져온 뒤 애플리케이션에서 `.limit(200)`으로 자른다. 또한 정렬 키가 `at` 하나뿐이라, 같은 밀리초에 기록된 행이 여럿이면(배치성 상태 전이 등) 상대 순서가 쿼리 실행마다 달라질 수 있다.

**Why**: 이력이 아주 많은 작업에서는 불필요하게 큰 결과셋을 DB에서 애플리케이션으로 옮긴 뒤 버리는 낭비가 생긴다. `at` 동률 정렬 불안정은 `TaskHistoryTimeline`이 "최신순"을 보장한다고 가정하는 프론트(및 테스트)의 전제를 이론상 깨뜨릴 수 있다.

**Context**: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java`의 `getHistory`, `TaskStatusHistoryRepository.findByTaskIdOrderByAtDesc`. `Pageable`/`LIMIT` 기반 쿼리로 바꾸고, 정렬을 `ORDER BY at DESC, id DESC`처럼 2차 키로 안정화하는 방향 검토.

**우선순위**: 낮음.

### [ ] `InterviewHistoryDialog` 767px dead media query 제거

**What**: `frontend/components/InterviewHistoryDialog.vue`에 `@media (max-width: 767px) { .history-dialog-card { ... } }` 블록이 남아 있다. 이 컴포넌트를 포함해 프로젝트 전체가 모바일 분기를 `$q.screen.lt.md`(1024px) 하나로 통일하기로 한 규칙(CLAUDE.md) 이전에 쓰던 브레이크포인트라, 지금은 1023px과 767px 사이 폭에서 두 규칙이 어긋나게 겹치는 죽은/혼동 유발 코드다.

**Why**: 하나로 통일하기로 한 반응형 규칙과 실제 코드가 어긋나 있으면 다음에 이 파일을 만지는 사람이 767px 분기를 실제 분기 규칙으로 착각하기 쉽다.

**Context**: `frontend/components/InterviewHistoryDialog.vue` 136번째 줄 부근. 제거하고 필요하면 `$q.screen.lt.md` 기준 클래스로 대체.

**우선순위**: 낮음.
