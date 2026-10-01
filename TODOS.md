# netisMaker TODOS (post-V1)

## V1.1 — 운영 안정성

### [ ] worktree 보존 정책 (구현 성공·배포 worktree 정리)

**What**: `~/netis-maker/worktrees/{localKey}/{task-N|deploy-N}`는 PR 생성 성공 후에도, 배포 후에도 지우는 곳이 없다(`WorktreeService` "Phase 1은 보존"; 디자인 worktree만 수확 후 remove). PR/MR 병합·종료, task 삭제, 배포 중지 후 N일 지난 worktree를 캐시 락 안에서 `git worktree remove --force`로 정리한다.

**Why**: 2026-09-30 도입한 레포 캐시 정리 잡(`RepoCacheCleanupJob`)은 살아 있는 worktree가 붙은 캐시를 절대 지우지 않는다 → 구현·배포를 한 번이라도 거친 캐시(운영 캐시 대부분)는 회수되지 않아 "레포당 수GB 누적"이 실질적으로 풀리지 않는다. worktree 자체도 체크아웃 크기만큼 디스크를 쓴다.

**Context**:
- 정리 잡 회차 로그 `레포 캐시 보존(worktree N개가 사용 중): {localKey} ← {경로…}`에 캐시를 붙잡고 있는 worktree가 나온다 — 운영 중 실제 규모를 먼저 확인.
- 배포 worktree는 재배포·`DeployReconcileJob`이 다시 쓰므로 컨테이너가 없어진 뒤에만. 구현 실패 보존분(디버그용)은 별도 보존 기한.
- PR/MR 상태는 워커가 모른다 → API 조회(claim/heartbeat 계열 확장) 필요. 레포 캐시 정리와 같은 새벽 스케줄에 묶는 안.

**우선순위**: 중간. 레포 캐시 정리의 실효가 이 항목에 달려 있다.

### [ ] Claude Code 사용량 유휴 갱신 프로브

**What**: 인터뷰 서비스가 유휴일 때도 주기적으로(예: 10분) 초경량 SDK 쿼리(haiku, maxTurns 1)를 돌려 `rate_limit_event`를 받아 사용량 패널을 신선하게 유지한다. 현재는 SDK 턴이 돌 때만 갱신되고 UI가 "N분 전 갱신"으로 정직하게 표기한다.

**Why**: 오래 유휴한 뒤 첫 질문 전에 남은 한도를 정확히 보고 싶을 때. 대신 프로브마다 쿼터를 소모한다(운영 트레이드오프).

**Context**: 스펙 §2 "사용량 신선도"·§9. `config.ts`에 `USAGE_PROBE_INTERVAL_MS`(0=off) 추가, `ClaimLoop` 유휴 틱에서 `RateLimitReporter`로 보고. Java 워커(`claude -p --output-format json`)는 이벤트가 없어 대상이 아니다.

**우선순위**: 낮음. 패널의 "N분 전 갱신" 표기로 운영해 보고 불편이 확인되면 착수.

### [ ] deriveTitle 서로게이트 페어 절단·마크다운 기호 정리

**What**: `QuestionService.deriveTitle`이 `substring(0, 60)`으로 제목을 자르는데, 이모지 등 서로게이트 페어 경계에서 자르면 lone surrogate가 남을 수 있다. 또한 질문 첫 줄에 있던 `#`/`>`/목록 기호/백틱 같은 마크다운 문법 기호가 제목에 그대로 섞여 들어간다.

**Why**: lone surrogate는 깨진 문자로 렌더되거나 이후 직렬화/전송 과정에서 문제를 일으킬 수 있고, 마크다운 기호가 섞인 제목은 목록/사이드바에서 지저분하게 보인다.

**Context**: 코드포인트 경계를 존중하는 절단(`offsetByCodePoints` 등)으로 교체 검토. 첫 줄 선행 `#`/`>`/`-`/`*`/백틱 등 제거도 함께 검토.

**우선순위**: 낮음. 드물게 발생하고 화면이 깨지는 정도라 급하지 않다.

### [ ] 모바일 사용량 스트립 라벨·신선도

**What**: `ClaudeUsagePanel`의 strip 변형이 쓰는 `shortLabel`이 five_hour 항목/그 외 항목 이항 분기라, 응답에 five_hour가 없으면 라벨이 잘못 표기될 수 있다. 또한 strip에는 갱신 시각을 알려주는 툴팁이 없다.

**Why**: 모바일에서는 strip만 보이므로 라벨 오표기가 그대로 노출된다. 패널(데스크톱)에는 이미 갱신 시각 정보가 있는데 strip에는 없어 신선도를 알 수 없다.

**Context**: R8 결정("캔버스는 strip에 문구 없음")과 균형이 필요하다 — `updatedLabel`을 툴팁으로만 붙이면 캔버스가 요구한 "문구 없음"을 해치지 않으면서 신선도 정보를 줄 수 있다.

**우선순위**: 낮음.

### [ ] 하단 내비 "확인 필요" 배지

**What**: 작업 탭 하단 내비(`q-footer`)의 "작업" 탭에 확인 필요 건수 배지를 붙인다.

**Why**: 스펙 `docs/superpowers/specs/2026-09-06-tasks-mobile-redesign-design.md` §4.4에서 "배지(확인 필요 건수)는 후속(YAGNI)"으로 명시적으로 범위에서 뺐다.

**Context**: `frontend/layouts/default.vue`의 푸터 `q-tabs`/`q-route-tab`("작업")에 붙이면 되고, 건수는 이미 있는 `composables/taskStages.ts`의 `sortForMobile`/`attentionGroup`(주의 필요 그룹 판정)을 재사용해 계산할 수 있다.

**우선순위**: 낮음.

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

### [ ] `InterviewHistoryDialog` 767px dead media query 제거

**What**: `frontend/components/InterviewHistoryDialog.vue`에 `@media (max-width: 767px) { .history-dialog-card { ... } }` 블록이 남아 있다. 이 컴포넌트를 포함해 프로젝트 전체가 모바일 분기를 `$q.screen.lt.md`(1024px) 하나로 통일하기로 한 규칙(CLAUDE.md) 이전에 쓰던 브레이크포인트라, 지금은 1023px과 767px 사이 폭에서 두 규칙이 어긋나게 겹치는 죽은/혼동 유발 코드다.

**Why**: 하나로 통일하기로 한 반응형 규칙과 실제 코드가 어긋나 있으면 다음에 이 파일을 만지는 사람이 767px 분기를 실제 분기 규칙으로 착각하기 쉽다.

**Context**: `frontend/components/InterviewHistoryDialog.vue` 136번째 줄 부근. 제거하고 필요하면 `$q.screen.lt.md` 기준 클래스로 대체.

**우선순위**: 낮음.

### [ ] 하이드레이션 회귀 스모크를 저장소/CI에 편입

**What**: 2026-09-30 SSR 정리 때 쓴 Playwright 스크립트(프로덕션 빌드 + 1280/820/390px × 로그인/비로그인 × 경로별 콘솔 `Hydration completed but contains mismatches` 0건, SSR 마커와 하이드레이션 후 DOM 비교)는 저장소 밖에만 있다.

**Why**: vitest는 SSR도 Quasar CSS도 없어 하이드레이션 불일치를 못 잡는다. 인증·화면 폭 분기를 새로 넣으면 조용히 재발한다(CLAUDE.md 프론트 스타일 "SSR 하이드레이션" 항목).

**Context**: API는 `page.route`로 목킹(`/api/` pathname만 — `**/api/**` 글롭은 `/_nuxt/…` 모듈까지 잡음). 로그인 상태는 localStorage 토큰 주입. CI frontend 잡의 `npm run build` 뒤에 붙이면 추가 빌드가 없다. 참고 한계: 쿠키 없이 이미 로그인된 기존 사용자의 배포 직후 첫 데스크톱 로드는 한 번 모바일→데스크톱 전환된다(그 로드에서 쿠키가 남아 이후 없음).

**우선순위**: 낮음~중간.

### [ ] 정리된 질문 첨부 칩 비활성 표시

**What**: 첨부 정리 잡이 종료 90일 지난 질문의 파일을 지운 뒤에도 대화 화면은 첨부 칩을 그대로 보여 주고, 클릭하면 410 안내가 알림으로 뜬다. 칩을 비활성(보존 기간 만료) 표시로 바꾼다.

**Context**: `InterviewResponse`(또는 첨부 DTO)에 파일 존재 플래그 추가 → 프론트 칩 분기. DB 첨부 행은 감사용으로 남아 있다(`AttachmentCleanupJob`).

**우선순위**: 낮음.

### [ ] `QuestionService.ask` 커밋 실패 시 첨부 파일 잔재

**What**: 추가 질문에서 파일을 쓴 뒤 트랜잭션 커밋이 실패하면 `question-{sid}/{seq}/`에 행 없는 파일이 남고, 다음 ask가 같은 turn seq를 다시 받아 같은 디렉터리에 겹쳐 쓴다.

**Context**: 세션 디렉터리 안이라 첨부 정리 잡은 세션 종결 후에만 함께 회수한다. 등록(`create`) 경로처럼 실패 시 해당 턴 디렉터리를 정리하거나, 커밋 후 파일을 확정(임시 경로 → rename)하는 방식 검토.

**우선순위**: 낮음.

### [ ] frontend `npm ci`가 npm 10에서 lock 불일치로 실패

**What**: 로컬 npm 10.x(Node 22)로 `frontend`에서 `npm ci`를 하면 `EUSAGE … Missing: crossws@0.4.12 from lock file`로 거부된다. CI(Node 24 / npm 11)에서는 통과한다.

**Context**: `@nuxt/test-utils/node_modules/h3-next`(h3@2 rc 별칭)가 optional peer로 `crossws ^0.4.1`을 선언하는데 호이스팅된 crossws는 0.3.5. lock을 만든 npm 11은 범위 밖 optional peer를 설치하지 않지만 npm 10 arborist는 중첩 0.4.12가 필요하다고 본다. 후보: `package.json` `engines.npm` + `.npmrc engine-strict`로 npm 11+ 명시, 또는 npm 11로 lock 재생성하는 별도 PR. 우회: `npx -y npm@11 ci`.

**우선순위**: 낮음.

## 완료

### [x] 레포 캐시 자동 정리 정책 (2026-09-30)
워커 `RepoCacheCleanupJob`이 매일 03:00 `unused-days`(기본 30)일 넘게 안 쓴 캐시를 지운다(`netis-maker.worker.repo-cache-cleanup.*`, env `REPO_CACHE_CLEANUP_ENABLED/CRON/UNUSED_DAYS`). 후보 정책 중 ①(N일 미사용)만 구현. ②(디스크 임계 LRU)·③(관리자 UI 트리거 — 워커에 HTTP 포트가 없어 폴링 프로토콜 확장 필요)·task_analysis 메타데이터 기록은 미포함. ⚠️ 구현 성공·배포 worktree가 붙은 캐시는 회수되지 않는다 → 위 "worktree 보존 정책". 상세는 CLAUDE.md 환경 메모.

### [x] 첨부파일 정리 정책 (2026-09-30)
api `AttachmentCleanupJob`(매일 03:30, `app.attachment.cleanup.*`): `task-{id}/`는 soft-delete 30일 경과 시에만, `question-{sid}/`는 종결(취소·만료·실패) + `last_activity_at` 90일 경과 시에만, 고아(등록 롤백 잔재)는 id ≤ MAX(id)이고 mtime 24시간 경과 시에만 삭제. DB 첨부 행은 감사용으로 남기고, 질문 첨부 다운로드는 파일이 없으면 410. soft-delete 복구 경로는 코드에 없음(수동 DB 복구 여유 = 30일). 레포 캐시 정리와 스케줄러는 분리, 인터뷰 `work_dir` 정리는 미포함.

### [x] 프론트 테스트가 생성물 `.nuxt`에 의존 (2026-09-30)
`vitest.config.ts`가 `esbuild.tsconfigRaw`(문자열, 값은 `test/esbuildTsconfig.ts`)로 tsconfig.json 조회를 건너뛰어 `.nuxt` 없이도 전체 스위트가 돈다. `test/esbuild-tsconfig.spec.ts`가 실제 설정으로 띄운 Vite의 .ts·TS SFC 트랜스폼과 `.nuxt` 체인 값 일치를 가드. CI는 원래 `npm ci`(postinstall=`nuxi prepare`)로 안전했다.

### [x] 답변 마크다운의 이미지 렌더 차단 검토 (2026-09-30)
`useMarkdown`의 image 규칙이 `<img>`를 내보내지 않는다 — 외부·상대·동일 출처 URL은 `[이미지] alt` 새 탭 링크, data:image는 링크 없는 표식, 링크 안 이미지는 표식+alt만. 참조식·엔티티 인코딩도 같은 토큰이라 함께 막힘. v-html 4곳 모두 이 렌더러 하나를 쓴다. 스펙 2026-09-05 §2 개정.

### [x] 질문 셸 SSR 하이드레이션 점검 / 전역 레이아웃 SSR 하이드레이션 불일치 (2026-09-30)
재현해 보니 `$q.screen` 분기는 불일치가 아니라(nuxt-quasar-ui가 하이드레이션까지 xs로 고정) 데스크톱 첫 로드의 모바일→데스크톱 점프였고, 실제 불일치 원인은 ① 인증 상태(SSR은 항상 비로그인) ② 첫 로드 미들웨어의 SPA 리다이렉트였다. `useHydrationSafeAuth` 게이트, 첫 로드 리다이렉트의 문서 이동화, 쿠키 `netis-maker-screen` 화면 폭 힌트로 dev·프로덕션 빌드 모두 불일치 0건(45케이스 + 리다이렉트 20케이스). 규칙은 CLAUDE.md 프론트 스타일 "SSR 하이드레이션".

### [x] 사용량 API 계약 테스트 보강 (2026-09-30)
`UsageApiIntegrationTest.isUsingOverage_report_is_returned_as_usingOverage`.

### [x] SSE 재생이 system 노트 턴을 question 이벤트로 보냄 (2026-09-13 해결, 2026-09-30 테스트 고정)
코드는 2026-09-13 질문 MCP·첨부 작업에서 이미 해결(`InterviewStreamService.replayEventName`, 프론트 `note` 리스너). 이번에 스트림 본문의 `event:note` 와이어 계약(`InterviewApiIntegrationTest`)과 스냅샷 실패 시 노트 칩 렌더·입력 대기 미전환(`InterviewPanel.spec.ts`)을 테스트로 고정.

### [x] `getHistory` Top-N 쿼리 + 동일 `at` 정렬 안정화 (2026-09-30)
`findByTaskIdOrderByAtDescIdDesc(taskId, Limit)` → `order by at desc, id desc fetch first ? rows only`. `TaskServiceHistoryTest`가 동률 순서와 DB 레벨 Top-N(Hibernate 엔티티 로드 수)을 고정.
