# netisMaker TODOS (post-V1)

## V1.1 — 운영 안정성

### [ ] Claude Code 사용량 유휴 갱신 프로브

**What**: 인터뷰 서비스가 유휴일 때도 주기적으로(예: 10분) 초경량 SDK 쿼리(haiku, maxTurns 1)를 돌려 `rate_limit_event`를 받아 사용량 패널을 신선하게 유지한다. 현재는 SDK 턴이 돌 때만 갱신되고 UI가 "N분 전 갱신"으로 정직하게 표기한다.

**Why**: 오래 유휴한 뒤 첫 질문 전에 남은 한도를 정확히 보고 싶을 때. 대신 프로브마다 쿼터를 소모한다(운영 트레이드오프).

**Context**: 스펙 §2 "사용량 신선도"·§9. `config.ts`에 `USAGE_PROBE_INTERVAL_MS`(0=off) 추가, `ClaimLoop` 유휴 틱에서 `RateLimitReporter`로 보고. Java 워커(`claude -p --output-format json`)는 이벤트가 없어 대상이 아니다.

**우선순위**: 낮음. 패널의 "N분 전 갱신" 표기로 운영해 보고 불편이 확인되면 착수.

### [ ] `QuestionService.ask` 커밋 실패 시 첨부 파일 잔재

**What**: 추가 질문에서 파일을 쓴 뒤 트랜잭션 커밋이 실패하면 `question-{sid}/{seq}/`에 행 없는 파일이 남고, 다음 ask가 같은 turn seq를 다시 받아 같은 디렉터리에 겹쳐 쓴다.

**Context**: 세션 디렉터리 안이라 첨부 정리 잡은 세션 종결 후에만 함께 회수한다. 등록(`create`) 경로처럼 실패 시 해당 턴 디렉터리를 정리하거나, 커밋 후 파일을 확정(임시 경로 → rename)하는 방식 검토.

**우선순위**: 낮음.

### [ ] frontend `npm ci`가 npm 10에서 lock 불일치로 실패

**What**: 로컬 npm 10.x(Node 22)로 `frontend`에서 `npm ci`를 하면 `EUSAGE … Missing: crossws@0.4.12 from lock file`로 거부된다. CI(Node 24 / npm 11)에서는 통과한다.

**Context**: `@nuxt/test-utils/node_modules/h3-next`(h3@2 rc 별칭)가 optional peer로 `crossws ^0.4.1`을 선언하는데 호이스팅된 crossws는 0.3.5. lock을 만든 npm 11은 범위 밖 optional peer를 설치하지 않지만 npm 10 arborist는 중첩 0.4.12가 필요하다고 본다. 후보: `package.json` `engines.npm` + `.npmrc engine-strict`로 npm 11+ 명시, 또는 npm 11로 lock 재생성하는 별도 PR. 우회: `npx -y npm@11 ci`.

**상태(2026-10-24)**: Windows 워커 PC는 Node 24.19 / npm 11.17로 올라가 `npm ci --dry-run`이 그대로 통과한다(이 PC에선 해당 없음). npm 10이 남은 머신(맥 스택 등)만 위 우회가 필요하다. `engine-strict`는 npm 10 머신에서 `start-public`의 `npm install`까지 막으니 넣지 않는다.

**우선순위**: 낮음.

### [ ] worktree 삭제를 레포 락 밖으로

**What**: `WorktreeService.discardUnderLock`이 (수GB일 수 있는) worktree 폴더를 레포 락을 잡은 채 지운다.

**Why**: 같은 JVM의 다른 작업은 락을 기다리면 되지만, `repos-dir`을 공유하는 다른 워커 프로세스는 60초 뒤 `GitRepoCache.acquireWithTimeout`이 시간 초과를 내고 `ensureFresh`가 그 작업을 실패시킨다.

**Context**: 락 안에서는 폴더를 `worktree-root/.trash/{uuid}`로 rename + `worktree prune` + 브랜치 삭제만 하고, 실제 삭제는 락 밖에서 한다(`RepoCacheCleanupJob`의 `.trash`와 같은 방식). rename이 실패하면 제자리 삭제로 폴백. 정리 잡은 이미 루트 직하의 점(.) 항목을 건너뛴다. 지금은 PC당 워커가 하나라 실제 위험이 없다.

**우선순위**: 낮음 (멀티 워커 도입 전 필수).

### [ ] LibreOffice 보안 업데이트 주기 점검

**What**: 첨부 슬라이드 렌더링(2026-10-27)이 라이브 PC의 LibreOffice(soffice headless)로 사용자 업로드 pptx/ppt를 연다. 외부 파일 파서라 취약점 패치가 잦다.

**Context**: 스펙 2026-10-27 §10. 주기(예: 분기 또는 보안 공지 때)로 안정판 업데이트 여부를 확인하는 점검 항목으로 둔다. 업데이트 뒤에는 기동 로그 `슬라이드 렌더링: 켜짐(soffice=…)`과 예시 pptx 렌더링(글자 깨짐·매크로 비실행)을 다시 확인.

**우선순위**: 중간 (설치 직후부터 해당).

### [ ] 원격 링크 이미지 가져오기 차단 검토

**What**: pptx 안의 외부 URL 링크 이미지를 LibreOffice가 변환 중 가져올 수 있고, 지금은 막지 못한다(스펙 §10 남은 위험).

**Context**: 업로드는 사내 인증 사용자만 하므로 위험은 제한적. 차단 후보: soffice 프로필의 링크 갱신 설정을 끄기, 변환 프로세스를 네트워크 차단(방화벽 규칙·별도 사용자) 하에 실행. 효과를 실제 외부 링크 샘플 pptx로 확인한 뒤 결정.

**우선순위**: 낮음.

## 작업 종료 단계 후속 (PR #86 리뷰에서 보류, 2026-10-14)

스펙 `docs/superpowers/specs/2026-10-13-task-close-phase-design.md`. 아래는 구현·리뷰 중 보류한 항목이다. 병합을 막는 문제는 PR #86에서 이미 고쳤다.

### [ ] 재시도로 쓴 종료 노트에 배포 주소·시각이 빠짐

**What**: `WorkerService.recordCloseProgress`의 UNDEPLOYED 단계가 `deployUrl`·`deployedAt`을 비운다. 그래서 그 뒤에 노트 단계가 실패해 재시도하면, 재claim한 `CloseContext`에 배포 정보가 없어 노트의 "배포 주소"가 비게 된다.

**Why**: 가장 흔한 재시도 경로(볼트 경로 문제 → 재시도)가 정확히 이 경우다. 노트가 작업 기록의 원문이 되므로 배포 정보가 빠지면 아쉽다.

**Context**: 배포 메타를 비우는 시점을 CLOSED 보고로 미루거나, `close_*` 쪽에 배포 주소·시각을 따로 남겨 `CloseContext.of`가 그 값을 쓰게 한다. 종료실패 동안 상세 화면에 죽은 접속 URL이 보이지 않도록 화면 쪽 처리도 같이 본다.

**우선순위**: 중간.

### [ ] GitHub 머지 큐: 종료 취소 뒤 늦게 병합되는 PR

**What**: 머지 큐가 있는 레포에서 병합이 대기열에 들어간 채 폴링(5×2초)이 끝나면 MERGED 없이 종료실패가 된다. 이때 관리자가 "종료 취소"를 하면 작업은 배포완료로 돌아가는데, 큐가 나중에 PR을 병합할 수 있다.

**Why**: 작업 상태(배포완료)와 PR 상태(병합됨)가 어긋난다. 다시 종료를 요청하면 이미 병합된 PR을 성공으로 처리하므로 수렴은 한다.

**Context**: 병합 요청을 보낸 사실을 `close_progress`에 `MERGE_REQUESTED`처럼 남기고, 그 표식이 있으면 종료 취소를 막거나 확인창에 경고를 넣는다. 함께: 폴링 중 PR이 CLOSED로 바뀌어도 "병합 대기열/진행 중"이라고 보고하는 문구(`GitHubPrCloser.awaitMerged`)를 상태별로 나눈다.

**우선순위**: 낮음 (머지 큐 레포가 생기면).

### [ ] 종료 워커 견고성 잔손질

**What**: 리뷰에서 나온 작은 항목들.
- `GitHubPrCloser`: draft PR은 `mergeStateStatus`가 DRAFT라 DIRTY(충돌) 사전 검사가 걸리지 않는다(병합 시도 → 원복 → 원문 사유로 처리되어 해는 없음). 분류에 쓰는 `mergeStateStatus`가 draft 해제 전 값이라 낡았다. 미사용 지역변수 `undoMsg`, 쓰지 않는 `throws`. `gh` 실행 람다가 `GitHubPrCreator`와 중복. API 경로에 브랜치 이름을 인코딩하지 않음(현재 `netismaker/task-N-…` ASCII라 무해).
- `GitLabMrCloser`: `draft `/`draft - ` 형태의 제목 접두어를 못 벗김(지금은 "draft 해제 실패"로 명확히 거부). draft 해제 PUT의 200 응답 본문을 못 읽으면 원복하지 않음. 브랜치 삭제 403을 보호 브랜치일 때도 "토큰 scope"로 안내. `CLOSE_MERGE_METHOD=rebase`를 GitLab에서 merge로 조용히 처리(기동 경고 로그 권장).
- `TaskCloser`: NOTE_WRITTEN 진행 보고가 실패해 재시도하면 claude를 한 번 더 돌린다(이중 과금, 드묾). `close-stale-threshold-minutes`(60 — 산출물 꺼내기·업로드 각 10분 상한을 반영해 30에서 올림)가 노트 타임아웃+gh 타임아웃+산출물 꺼내기·업로드 상한 합보다 커야 하는데 검증이 없다 — `CLOSE_NOTE_TIMEOUT`·`CLOSE_ARTIFACT_COPY_TIMEOUT`·`CLOSE_ARTIFACT_UPLOAD_TIMEOUT`을 늘리면 실행 중 재큐될 수 있다.
- `CloseNoteWriter`: `relativePath`를 원래 폴더 문자열로 조립(`vault.relativize` 권장), 비원자적 쓰기(임시 파일 + `ATOMIC_MOVE`), 제목의 제어문자가 `InvalidPathException`으로 샘. `CloseNotePrompt`: 사용자 입력 절을 구분자로 감싸지 않음, 비용이 null이면 `$null` 표기.

**우선순위**: 낮음.

### [ ] 종료 단계 테스트 보강

**What**: 동작은 맞지만 테스트로 고정되지 않은 경우들.
- API: `TaskCloseServiceTest` — withdraw의 비소유자 403, approve/reject/withdraw의 상태 409, notFound, `statusBeforeReview`의 예외 경로(요청 불가 상태·파싱 불가·null), reject 사유가 이력에 남는지, prNumber null·headBranch 공백. `TaskServiceDeployTest` 삭제 가드가 예외 타입만 단언. `TaskCloseController` 비관리자 응답의 비밀 env 마스킹. 통합 테스트에 withdraw·retry·빈 사유 400·배포중단됨 진입. `closeViewOf`의 "기록 있음" 분기. `WorkerServiceCloseTest`의 길이 가드 400·CLOSING 중 허용되지 않는 결과 상태.
- 워커: `containsSubsequence` 대신 `containsSequence("--tools", "")`, `run()`/`execWithoutTools` 프로세스 수준 테스트, Binder 수준 잘못된 병합 방식, `GitHubPrCloser` 잘못된 method, `GitLabMrCloser` 인터럽트 경로, `CloseNoteWriter` 펜스 제거·형제 접두 폴더·절대 폴더.
- 프론트: 배포중단됨·작업종료·관리자 겸 등록자 조합의 `nextAction`, 좁은 화면 반려(`bar-more-close-reject`)와 반려 다이얼로그 흐름, 종료 요청 중복 클릭 가드.

**우선순위**: 낮음.

## 완료

### [x] UI 잔손질 묶음 (2026-10-24, `fix/ui-polish`)
모바일 사용량 strip 항목별 짧은 라벨(`LIMIT_SHORT_LABELS`) + 갱신 시각 툴팁 · 하단 내비 "작업" 확인 필요 배지(lt.md에서만 `/api/tasks` 50건을 `attentionGroup`으로 셈 — 관리자는 전체, 그 외 내 작업) · 모바일 목록 상세 필터 시트(받은 목록 안의 세부 상태, 그룹 칩과 함께 걸림) · ⋮ 시트가 id로 최신 작업을 그림(10-08 관찰: 배포 중지 직후 삭제가 회색으로 남음) · 모바일 "대화 열기" = maximized 시트(인라인 카드엔 열기 버튼만) · `InterviewHistoryDialog` 767px 쿼리 제거 · 관리자에게 배포중단됨 작업의 보조 "종료 요청" · 데스크톱 보드 카드 진행바가 작업종료를 전부 완료로 · `nextAction` fall-through 제거·테스트 제목·`RejectCloseRequest` 메시지·`forViewer` 중복·`TaskResponse` FQN · 질문 첨부 `expired`(종료된 질문 + 파일 없음) 비활성 칩 · `deriveTitle` 코드포인트 절단·마크다운 기호 제거 · 다이얼로그는 폰(<600px)에서만 전체화면, 태블릿은 일반 너비(`useFullscreenDialog`, 사용자 결정).

### [x] 하이드레이션 회귀 스모크를 저장소/CI에 편입 (2026-10-08)

`frontend/scripts/hydration-smoke.mjs`(`npm run test:hydration`) + CI frontend 잡 "Hydration smoke" 스텝. 원래 스크립트는 남아 있지 않아 새로 썼다 — 1280/820/390px × 로그인/비로그인 × 폭 힌트 쿠키 없음/일치 × 경로(비로그인 3, 로그인 9) = 72조합, 콘솔 하이드레이션 경고·잡히지 않은 예외 0건. 브라우저는 설치된 Chrome(`playwright-core`, 다운로드 없음). 일부러 넣은 불일치(`/tasks/1`)를 6조합 모두 잡아 실패(exit 1)하는 것을 확인. 당초 안의 "SSR 마커와 하이드레이션 후 DOM 비교"는 넣지 않았다 — 프로덕션 Vue가 내는 불일치 경고로 충분하고, 하이드레이션 뒤 정상적인 폭 전환과 구분하기 어렵다. 한계(쿠키 없는 기존 로그인 사용자의 배포 직후 첫 데스크톱 로드 1회 전환)는 그대로.

### [x] worktree 보존 정책 (2026-10-02)
성공한 작업의 worktree는 작업 끝에 바로 정리, 실패분은 `WorktreeCleanupJob`이 7일 뒤 정리(진행 중은 API로 보호, fail-closed). PR 병합 상태 추적은 하지 않는다 — 성공분은 원격 브랜치·이미지에 이미 남아 있어서. 스펙 `docs/superpowers/specs/2026-10-02-worktree-retention-design.md`.

### [x] 레포 캐시 자동 정리 정책 (2026-09-30)
워커 `RepoCacheCleanupJob`이 매일 03:00 `unused-days`(기본 30)일 넘게 안 쓴 캐시를 지운다(`netis-maker.worker.repo-cache-cleanup.*`, env `REPO_CACHE_CLEANUP_ENABLED/CRON/UNUSED_DAYS`). 후보 정책 중 ①(N일 미사용)만 구현. ②(디스크 임계 LRU)·③(관리자 UI 트리거 — 워커에 HTTP 포트가 없어 폴링 프로토콜 확장 필요)·task_analysis 메타데이터 기록은 미포함. 구현 성공·배포 worktree가 붙은 캐시 미회수 문제는 위 "worktree 보존 정책"으로 해소. 상세는 CLAUDE.md 환경 메모.

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
