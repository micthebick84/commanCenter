# 작업(인터뷰·구현) 참조 repo — 멀티 repo 2단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업(task)에 읽기 전용 참조 repo를 최대 3개 붙여, 작업 인터뷰가 옛 repo와 새 repo를 비교해 이식 계획을 세우고 구현이 같은 커밋의 참조를 옆에 두고 대상에만 코드를 옮기게 한다. 질문 대화에서 바로 작업을 등록하는 버튼도 넣는다.

**Architecture:**
- **API**: 참조 목록을 `task.ref_repos`(V33 JSONB)에 두고, 승인 때 인터뷰 세션으로 복사한다. 인터뷰 답변 때 1단계와 같은 규칙으로 바꾸고, 계획 확정 때 커밋이 고정된 참조만 작업으로 되돌려 저장한다. 구현 claim에 고정 커밋을 실어 보낸다.
- **인터뷰 서비스**: 1단계 참조 준비(`ensureRefRepos`)를 INTERVIEW 턴에도 쓰고, kickoff·resume 접두로 알리고, 질문·계획 보고에 상태를 싣는다.
- **워커**: 공유 레포 캐시에서 고정 커밋을 받아 `_refs/task-{id}/{catalogId}`에 detached worktree로 두고, 프롬프트·계획 파일에 알리고, 실행 뒤 기준선과 비교해 변경을 경고한다. 커밋·push는 대상만.
- **화면**: 1단계 칩·추가 폼을 작업 등록·승인·상세·확정·재시도 창에 맥락 문구로 쓰고, 질문 화면에서 `/tasks?fromQuestion={id}`로 등록 창을 채운다.

**Tech Stack:** Spring Boot 3 / Java 21 / JPA·Flyway(PostgreSQL JSONB) / JUnit5·Mockito·AssertJ · Node 24 TS + Claude Agent SDK + vitest · Nuxt 3 + Quasar + vitest(@vue/test-utils)

**Spec:** `docs/superpowers/specs/2026-10-24-task-ref-repos-design.md`

## Global Constraints

**작업 환경**

- **작업 위치**: worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\task-ref-repos`(브랜치 `feature/task-ref-repos`).
  - 본 체크아웃 `C:\Users\mic\NetisMaker\commanCenter`에서는 라이브가 `gradlew bootRun`으로 돈다. 거기서 빌드·테스트·npm 설치를 하지 않는다.
- **TS 테스트 준비**(한 번, PowerShell): worktree의 `netismaker-interview-service\node_modules`와 `frontend\node_modules`를 본 체크아웃의 같은 폴더로 가는 정션으로 만든다.
  - 예: `New-Item -ItemType Junction -Path <wt>\frontend\node_modules -Target C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules`(인터뷰 서비스도 같은 방식).
  - `npm ci`·`npm install`·`npm run build`·하이드레이션 스모크 실행은 금지(CI가 돈다).
  - worktree를 지우기 전에는 반드시 `cmd /c rmdir <wt>\frontend\node_modules`(인터뷰 서비스도)로 정션부터 지운다.

**테스트 명령**

- **백엔드**: worktree 루트의 Git Bash에서 `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.<패키지>.<클래스>"`.
  - 클래스는 FQN으로 쓴다. 와일드카드는 디렉터리 이름으로 확장돼 실패한다.
  - 전체를 돌리면 이 PC는 원래 3건이 실패한다(`AttachmentCleanupJobTest` 1건·`AttachmentStorageTest` 2건, 심볼릭 링크 권한). 회귀가 아니다.
  - Testcontainers 테스트는 skip된다(CI에서 돈다).
- **인터뷰 서비스**: `cd netismaker-interview-service && npx vitest run test/<파일>`. 타입 검사 `npx tsc -p tsconfig.json --noEmit`.
- **프론트**: `cd frontend && npx vitest run <파일>`. 타입 검사 `npx nuxi prepare && npx nuxi typecheck`.

**파일·커밋 규칙**

- **CRLF**: 작업본은 CRLF다(`core.autocrlf=true`, `.gitattributes` 없음). 기존 파일을 Edit/Write한 뒤 `file <경로>`가 LF로 바뀌었으면 `sed -i 's/\r$//; s/$/\r/' <경로>`로 되돌린다. 새 파일은 LF여도 된다. 프롬프트·문구 문자열은 명시적 `\n`으로 잇는다.
- **커밋**: 레포의 작성자 설정을 그대로 쓰고, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 푸시는 하지 않는다(사용자 확인 뒤).
- **프론트 규칙**(CLAUDE.md): 작은따옴표·세미콜론 없음, Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`)을 클래스로 쓰지 않음, 좁은 화면 분기는 `$q.screen.lt.md` 하나, `q-dialog` 안 루트는 `<div>`, `defineProps<T>()`의 T를 `~/`에서 import하지 않음, `prettier --write`를 frontend 전체에 돌리지 않음, 새 경로·API는 `scripts/hydration-smoke.mjs` 경로 목록·목에 같이 넣음.

**고정값**

- 상한 `MAX_REF_REPOS = 3`(1단계 그대로).
- 워커 참조 폴더 `{worktree-root}/_refs/task-{작업 id}/{카탈로그 id}`. 인터뷰 참조 폴더는 1단계 그대로 `{interview work-root}/_refs/{세션 id}/{카탈로그 id}`.
- 인터뷰 참조 하나당 받기 상한 300000ms(5분). 워커 sha 대안 fetch 깊이 50 → 500(그 이상 없음).
- 마이그레이션 `V33__task_ref_repos.sql`.

**문구(그대로 쓴다)** — 스펙의 문구가 최종. 자주 쓰는 것:

- 시스템 메모: `참조 repo 변경: ＋ {별칭}({브랜치}), − {별칭}({브랜치})` / `참조 repo 변경: 없음(전부 해제)` (`＋` U+FF0B, `−` U+2212, 추가 먼저, `, `로 구분)
- 이력: `참조 repo 조정: ＋ …, − …`(승인) · `구현 참조: {별칭}({브랜치})@{sha7}, …` + ` / 제외(인터뷰에서 받지 못함): {별칭}({브랜치}), …`(확정) · `참조 제외(구현 재시도): {별칭}({브랜치}), …` · `참조 repo 변경 감지(PR에는 포함 안 됨): {경고들}`
- 오류: `작업에 없는 참조는 뺄 수 없습니다` · `답변이 끝난 뒤에 작업으로 등록할 수 있습니다` · 1단계 참조 검증 문구 4개 그대로
- 사유: `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}` · `참조 repo 받기 시간 초과(5분)`
- 워커 프롬프트 절(§6.4)·PR 본문 절(§6.6)·INTERVIEW 규칙 절과 `refsCleared` 줄(§5.2)·칩 문구(§7.1)·확정 화면(§7.5)·재시도 창(§7.5b)·프리필 안내와 브랜치 없음 알림(§7.2)·버튼 `이 대화로 작업 등록`(아이콘 `add_task`)

**지켜야 할 불변식**

- 참조가 없는 작업은 화면 → API 바디(등록·승인·답변·확정·재시도), 인터뷰 kickoff·resume 프롬프트, 인터뷰 서비스 → API 보고 바디(빈 필드는 키째 생략), 구현 프롬프트, PR 본문이 지금과 같다. 워커 ↔ API Java DTO는 빈 목록·null 필드를 실어도 된다.
- 질문 세션(1단계)의 프롬프트·바디·칩 문구는 바뀌지 않는다. 예외: 참조당 5분 상한은 질문에도 적용된다.
- INTERVIEW·QUESTION 권한 게이트와 `sessionOptions`는 바꾸지 않는다.
- 워커는 참조를 커밋·push하지 않는다. 참조 폴더는 대상 worktree 밖이다. 참조에 `ensureFresh`를 쓰지 않고, 공유 캐시의 `refs/remotes/origin/*`·HEAD를 바꾸지 않는다. 레포 락을 중첩하지 않는다. 구현 claude에 `--add-dir`를 주지 않는다.
- 토큰이 든 문자열은 `GitRemotes.mask`(Java)·`maskSecrets`(TS)를 거친 뒤에만 사유·로그·이력·프롬프트에 넣는다.
- 기존 확정·재시도·승인 이력 문구는 바꾸지 않는다(테스트 고정). 새 정보는 별도 이력 행으로.

**추가 고정 문구(초안·교차 점검에서 확정 — 스펙에는 작업 14에서 반영)**

- 확정 때 고정된 참조가 하나도 없을 때: `구현 참조: 없음 / 제외(인터뷰에서 받지 못함): {별칭}({브랜치}), …`
- 워커 준비 실패 사유 꼬리: `고정 커밋 정보가 없거나 올바르지 않습니다` · `참조 브랜치 정보가 없습니다` · `고정 커밋 {sha7}을(를) 찾지 못했습니다 — 브랜치 {브랜치}의 최근 500개 커밋 안에 없음`
- 워커 시간 초과: `시간 초과: 참조 repo 준비에 {초}초를 써 구현 상한({분}분)이 남지 않았습니다`
- 워커 변경 경고: `{별칭}: 파일 {N}개` · `{별칭}: HEAD가 고정 커밋과 다름` · `{별칭}: 참조 폴더가 지워짐` · `{별칭}: 변경 확인 실패` · 실패 사유 꼬리 ` — 참조 repo 변경 감지: {경고들}`
- 프론트 알림(프론트 전용): `질문의 대상 레포를 카탈로그에서 찾을 수 없습니다 — 레포를 골라 주세요` · `카탈로그에 없는 참조는 뺐습니다: {별칭}({브랜치}), …` · `질문을 불러오지 못했습니다` · `레포 목록을 불러오지 못했습니다` · ✎ 툴팁 `인터뷰 중 변경 — 다음 답변부터 적용` · 안내 띠 ✕ aria-label `출처 연결 빼기` · 재시도 체크박스 `이 참조 빼고 재시도`

**요청 바디의 참조 필드 규칙**

- 등록: 비면 키 생략. 승인·INTERVIEW 답변: 서버 값과 다를 때만 싣고, 전부 해제면 `[]`. 재시도: 고른 것이 있을 때만 `removeRefCatalogIds`.
- `refWarnings` 원소의 가림·500자 자르기는 `WorkerResultRequest.masked()`가 맡는다 — `masked()`는 워커의 `ResultReporter.reportTerminal`·`DeadLetterReplayJob`이 전송 직전에 부른다(`WorkerMainLoop`은 자르지 않는다). API는 `masked()`를 부르지 않고, 이력 행 전체를 `GitRemotes.mask` → 500자로 한 번 더 자르는 것(작업 5 Step 3)이 API 쪽 방어다. 고정 커밋으로 인정하는 sha는 40자·64자 16진수뿐이다(API `applyRefStates`와 워커 `isFullSha`가 같은 정의).

**실행 순서**

- 작업은 1 → 14 순서로 하나씩 한다. 여러 작업이 같은 파일을 고친다(`InterviewService`·`TaskService`·`TaskController`·`tasks/index.vue`·`[id].vue`·`refRepos.ts`·`hydration-smoke.mjs`). 병렬로 맡기지 않는다.
- 줄 번호는 기준 커밋 `a5a36d1` 기준이다. 앞 작업이 들어가면 어긋나므로 메서드·심볼 이름으로 찾는다.
- 워커 작업 6·7은 작업 1(`Task.setRefRepos`)·작업 5(`TaskRefRepo`, `WorkerResultRequest` 25인자)에 기댄다. 인터뷰 서비스 작업 9는 작업 4의 claim·plan 필드에 기댄다.

## Review Focus

1. **토큰 없는 비공개 참조(401)** — 워커가 자격증명 창·입력 대기 없이 바로 준비 실패해야 한다(작업 6 `a_401_remote_fails_fast_without_prompting`).
2. **두 작업이 같은 참조 repo를 동시에 쓸 때** — 각자 폴더를 갖고, 한쪽 정리가 다른 쪽 worktree·메타를 건드리지 않아야 한다(작업 6 `two_tasks_hold_separate_worktrees_of_the_same_ref`).
3. **시간 초과로 끊긴 인터뷰 참조를 다음 턴에 다시 받을 때** — `.part` 찌꺼기와 상관없이 받아져야 한다(작업 9 `a reference cut by the time limit is fetched cleanly on the next turn`).
4. **질문 제목·첨부 이름에 줄바꿈·마크다운 기호가 있을 때** — 대화 전문의 머리·턴 구조가 깨지지 않아야 한다(작업 3 `title_and_attachment_names_stay_on_one_line`).
5. **재시도에서 참조를 모두 뺐을 때** — 참조 없는 구현이 되고 참조 지원을 알리지 않은 워커도 claim할 수 있어야 한다(작업 5 `removing_every_ref_makes_the_task_claimable_by_old_workers`).

## 파일 구조 (새 파일)

- API: `src/main/resources/db/migration/V33__task_ref_repos.sql`, `…/service/RefRepoChanges.java`(참조 변경 짝 맞춤·검증·메모 — 질문·승인·인터뷰 답변 공용), `…/service/QuestionTranscript.java`(대화 전문 렌더러)
- 워커: `…/workerdaemon/RefRepoPrompts.java`(프롬프트·계획 파일 머리·PR 본문 절·실패 문구)
- 프론트: `frontend/components/tasks/TaskRefChangeMenu.vue`(작업 상세 인터뷰 중 참조 변경 메뉴)
- 그 밖은 기존 파일 수정(각 작업의 Files).

---

### Task 1: 데이터(V33)·작업 참조 필드·조회 응답

**Files:**
- Create: `src/main/resources/db/migration/V33__task_ref_repos.sql`
- Modify: `…/entity/Task.java:79-83` 다음 (`dbConnectionIds` 뒤에 두 필드)
- Modify: `…/dto/TaskResponse.java:16-63`(구성요소), `:193-231`(`withTotalCost`), `:237-246`(`redactSecretValues`), `:248-324`(`build`)
- Modify: `…/dto/InterviewResponse.java:124` (주석 "INTERVIEW 세션은 항상 []" 삭제 — 이번 단계부터 INTERVIEW도 참조를 가진다)
- Modify: `…/service/InterviewService.java:621-625` 다음 (`latestSessionRefRepos`)
- Modify: `…/controller/TaskController.java:114-124` (`get`)
- Test: `…/dto/TaskResponseRefReposTest.java`(신규), `…/controller/TaskControllerRefReposTest.java`(신규), `…/repository/TaskRefReposIntegrationTest.java`(신규, `RUN_TESTCONTAINERS`)

**Interfaces:**
- Consumes: 1단계 `entity/RefRepo`, `InterviewResponse.RefRepoView`(같은 `dto` 패키지라 package-private `RefRepoView.of`를 그대로 쓴다).
- Produces:
  - `Task.getRefRepos(): List<RefRepo>` / `setRefRepos(List<RefRepo>)` — `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "ref_repos", nullable = false, columnDefinition = "jsonb") @Setter`, 기본값 `new ArrayList<>()`(V28 `dbConnectionIds`와 같은 모양)
  - `Task.getSourceQuestionId(): Long` / `setSourceQuestionId(Long)` — `@Column(name = "source_question_id") @Setter`
  - `TaskResponse` 36·37번째 구성요소 `List<InterviewResponse.RefRepoView> refRepos`(항상 non-null), `Long sourceQuestionId`
  - `TaskResponse withRefRepos(List<RefRepo> refs)` — refRepos만 바꾼 사본(null → `[]`)
  - `@Transactional(readOnly = true) Optional<List<RefRepo>> InterviewService.latestSessionRefRepos(Long taskId)` — `findTopByTaskIdOrderByCreatedAtDesc`의 세션 참조 사본(세션 값 null → `[]`)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// TaskResponseRefReposTest — Task.create(...) + id 7
@Test void task_refs_and_source_question_are_mapped_and_absent_values_are_empty_and_null() {
    assertThat(TaskResponse.of(t, null).refRepos()).isNotNull().isEmpty();
    assertThat(TaskResponse.of(t, null).sourceQuestionId()).isNull();
    t.setRefRepos(new ArrayList<>(List.of(new RefRepo(11L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5", SHA, "2026-10-24T10:00+09:00", null))));
    t.setSourceQuestionId(9L);
    TaskResponse r = TaskResponse.of(t, null);
    assertThat(r.refRepos()).containsExactly(new InterviewResponse.RefRepoView(11L, "옛앱", "release/6.5", SHA, "2026-10-24T10:00+09:00", null));
    assertThat(r.sourceQuestionId()).isEqualTo(9L);
}
@Test void withTotalCost_redact_keep_refs_and_withRefRepos_replaces_only_refs() {
    // TaskResponse.withTotalCost(r, ONE).refRepos() == r.refRepos(); r.redactSecretValues().sourceQuestionId() == 9L
    // r.withRefRepos(List.of(ref.withError("권한 없음"))).refRepos().get(0).error() == "권한 없음", id·title·sourceQuestionId 동일
}

// TaskControllerRefReposTest — TaskControllerSecretRedactionTest와 같은 직접 생성(목 TaskService·InterviewService)
@ParameterizedTest @EnumSource(value = TaskStatus.class, names = {"INTERVIEWING", "INTERVIEW_INPUT", "INTERVIEW_REVIEW"})
void detail_in_an_interview_stage_shows_the_running_session_refs(TaskStatus st) {
    task.setStatus(st);   // 작업 refs = [11 sha 없음]
    when(interviewService.latestSessionRefRepos(5L)).thenReturn(Optional.of(List.of(pinned11)));
    assertThat(controller.get(5L, user).refRepos()).extracting(InterviewResponse.RefRepoView::sha).containsExactly(SHA);
}
@Test void detail_outside_the_interview_stage_shows_task_refs_without_reading_sessions() {
    task.setStatus(TaskStatus.APPROVED);
    assertThat(controller.get(5L, user).refRepos()).hasSize(1);
    verify(interviewService, never()).latestSessionRefRepos(any());
}
@Test void list_shows_task_refs_only()   // 인터뷰 단계여도 목록은 latestSessionRefRepos를 부르지 않는다

// TaskRefReposIntegrationTest — TaskPreviousDeployIntegrationTest와 같은 구성
@Test void 작업_참조와_출처_질문이_JSONB로_왕복한다() {
    Task t = taskService.create(new TaskCreateRequest(1L, "main", "참조", "설명"), "user1");
    t.setRefRepos(new ArrayList<>(List.of(ref)));     // sha·fetchedAt·error 모두 채운 원소
    t.setSourceQuestionId(77L);
    taskRepo.saveAndFlush(t);
    Task back = taskRepo.findById(t.getId()).orElseThrow();
    assertThat(back.getRefRepos()).containsExactly(ref);
    assertThat(back.getSourceQuestionId()).isEqualTo(77L);
}
@Test void 새_작업은_빈_배열과_null이다()
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.dto.TaskResponseRefReposTest" --tests "com.hamonsoft.netismaker.controller.TaskControllerRefReposTest"`
  - Expected: 컴파일 실패(필드·메서드 없음)
- [ ] **Step 3: 구현**
  - V33은 스펙 §3.1의 SQL 두 줄 그대로(FK 없음). 머리 주석: `-- 작업 참조 repo (스펙 2026-10-24 §3.1). source_question_id는 기록용 — interview_session.task_id와 순환이라 FK를 걸지 않는다.`
  - `TaskResponse.build`: `t.getRefRepos() == null ? List.of() : t.getRefRepos().stream().map(RefRepoView::of).toList()`, `t.getSourceQuestionId()`. `withTotalCost`·`redactSecretValues`는 두 값을 그대로 넘긴다.
  - `TaskController.get`: `ofWithUsage` 결과를 `r`로 받고, `private static final Set<TaskStatus> INTERVIEW_STAGE = EnumSet.of(INTERVIEWING, INTERVIEW_INPUT, INTERVIEW_REVIEW)`에 들면 `r = interviewService.latestSessionRefRepos(id).map(r::withRefRepos).orElse(r)` 뒤 `forViewer`. 목록·다른 엔드포인트는 작업 참조만(세션 조회를 늘리지 않음).
  - 깨질 기존 테스트 없음 — `new TaskResponse(` 호출은 `TaskResponse.java` 안 3곳뿐이다.
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.dto.TaskResponseRedactTest" --tests "com.hamonsoft.netismaker.controller.TaskControllerSecretRedactionTest" --tests "com.hamonsoft.netismaker.repository.TaskRefReposIntegrationTest"`(Docker 없으면 skip)
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(task-refs): 작업 참조 repo 컬럼(V33)·조회 응답`

### Task 2: 참조 변경 도우미 추출·등록·승인

**Files:**
- Create: `…/service/RefRepoChanges.java`
- Modify: `…/service/QuestionService.java:279-331` (`applyRefChange`는 도우미 호출 + 노트 남기기만, 안 쓰게 된 import `Iterator`·`LinkedHashSet`·`Stream` 정리)
- Modify: `…/dto/TaskCreateRequest.java:12-25` (구성요소 6개 + 4인자 호환 생성자 — 호출 30여 곳 보호)
- Modify: `…/dto/ApproveRequest.java:11-21` (5번째 `refRepos` + 4인자 호환, 3인자 유지)
- Modify: `…/service/TaskService.java:114-136` (`create`), `:316-337` (`startInterview`)
- Modify: `…/service/InterviewService.java:114-127` (`createForTask` 복사)
- Modify: `…/controller/TaskController.java:165-166` (승인 바디에 `@Valid`)
- Test: `…/service/RefRepoChangesTest.java`(신규), `…/service/TaskServiceApproveRefsTest.java`(신규), `…/service/TaskServiceRepoCatalogTest.java`, `…/service/InterviewServiceTest.java`, `…/dto/RefRepoInputValidationTest.java`, `…/controller/TaskControllerApproveValidTest.java`(신규), 회귀 `…/service/QuestionServiceTest.java`

**Interfaces:**
- Consumes: `RepoCatalogService.checkRefShape`·`resolveReferences`, `RefRepo.matches`·`label`, Task 1 `Task.refRepos`.
- Produces (Task 4도 쓴다):
  - `public final class RefRepoChanges`
    - `public static final String NOTE_PREFIX = "참조 repo 변경: "`, `public static final String CLEARED_NOTE = "참조 repo 변경: 없음(전부 해제)"`
    - `public record Change(List<RefRepo> next, List<RefRepo> added, List<RefRepo> removed)` — next는 입력 순서, 유지 원소는 기존 인스턴스 그대로(sha·fetchedAt·error 포함)
    - `public static Change diff(Long targetCatalogId, List<RefRepo> current, List<RefRepoInput> inputs, RepoCatalogService catalog)` — `null` = 바꿀 것 없음(inputs null 또는 같은 집합). 400은 `checkRefShape`·`resolveReferences`가 그대로 던진다.
    - `public static String systemNote(Change c)` — next가 비면 `CLEARED_NOTE`, 아니면 `NOTE_PREFIX + delta`
    - `public static String approvalHistory(Change c)` — `"참조 repo 조정: " + delta`(전부 뺀 경우도 `− …` 나열)
    - delta = added 순 `＋ {label}` 다음 removed 순 `− {label}`, `, `로 잇는다(`＋` U+FF0B, `−` U+2212)
  - `TaskCreateRequest(@NotNull Long repoCatalogId, @Size(max=255) String githubBranch, @NotBlank @Size(max=500) String title, @NotBlank String description, @Valid List<RefRepoInput> refRepos, Long sourceQuestionId)` + 4인자. `sourceQuestionId` 처리는 Task 3.
  - `ApproveRequest(List<Long> mcpCatalogIds, String model, String effort, List<Long> dbConnectionIds, @Valid List<RefRepoInput> refRepos)` + 4·3인자.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// RefRepoChangesTest — catalog = mock(RepoCatalogService.class), 대상 1L. QuestionServiceTest의 참조 케이스를 도우미 단위로 옮긴다.
@Test void null_inputs_or_the_same_set_in_any_order_is_no_change_without_resolving() {
    assertThat(RefRepoChanges.diff(1L, List.of(a11, b12), null, catalog)).isNull();
    assertThat(RefRepoChanges.diff(1L, List.of(a11, b12), List.of(in(12, "dev"), in(11, " main ")), catalog)).isNull();
    verifyNoInteractions(catalog);
}
@Test void retained_refs_keep_their_pin_and_only_added_ones_are_resolved() {
    when(catalog.resolveReferences(1L, List.of(new RefRepoInput(12L, "master")))).thenReturn(List.of(c12));
    Change c = RefRepoChanges.diff(1L, List.of(a11Pinned), List.of(in(11, "main"), in(12, "master")), catalog);
    assertThat(c.next()).containsExactly(a11Pinned, c12);
    assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: ＋ 배치 서버(master)");
}
@Test void branch_change_is_remove_plus_add()            // systemNote "참조 repo 변경: ＋ 옛앱(dev), − 옛앱(main)"
@Test void empty_list_clears_everything() {
    Change c = RefRepoChanges.diff(1L, List.of(a11, b12), List.of(), catalog);
    assertThat(RefRepoChanges.systemNote(c)).isEqualTo("참조 repo 변경: 없음(전부 해제)");
    assertThat(RefRepoChanges.approvalHistory(c)).isEqualTo("참조 repo 조정: − 옛앱(main), − 공통 UI(dev)");
}
@Test void approval_history_lists_additions_then_removals()  // "참조 repo 조정: ＋ C(master), − B(dev)"
@Test void shape_is_checked_on_the_result_set()   // 4개·[A,A]·대상과 같음 → 1단계 400 문구 그대로

// TaskServiceRepoCatalogTest
@Test void create_saves_reference_snapshots_resolved_against_the_target()   // resolveReferences(eq(7L), any()) → t.getRefRepos() == [ref11]
@Test void create_reference_rejection_saves_no_task()                        // 400 → verify(taskRepo, never()).save(any())

// TaskServiceApproveRefsTest — TaskServiceApproveDbTest 목 구성 + RepoCatalogService 목 + historyRepo 캡처
@Test void null_refs_keep_the_registered_refs_and_add_no_history_row()
@Test void changed_refs_are_set_before_the_session_and_logged_after_the_approval_row() {
    // task refs [11 main], 요청 refRepos [11 main, 12 master]
    InOrder o = inOrder(repoCatalog, interviewService);
    o.verify(repoCatalog).resolveReferences(9L, List.of(new RefRepoInput(12L, "master")));
    o.verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList());
    assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작", "참조 repo 조정: ＋ 배치 서버(master)");
}
@Test void a_rejected_ref_is_400_and_no_session()     // createForTask never, 상태 AWAITING_APPROVAL
@Test void legacy_four_arg_request_keeps_refs()

// InterviewServiceTest
@Test void createForTask_copies_the_task_refs_to_the_new_session()   // sessionRepo.save 캡처 → 같은 원소, 다른 리스트 인스턴스
// RefRepoInputValidationTest — 256자 브랜치: TaskCreateRequest·ApproveRequest 모두 경로 "refRepos[0].branch"
// TaskControllerApproveValidTest
@Test void approve_body_is_validated() throws Exception {
    Method m = TaskController.class.getMethod("approve", Long.class, ApproveRequest.class, JwtAuthenticationToken.class);
    assertThat(m.getParameters()[1].isAnnotationPresent(jakarta.validation.Valid.class)).isTrue();
}
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.RefRepoChangesTest" --tests "com.hamonsoft.netismaker.service.TaskServiceApproveRefsTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - `diff`는 `QuestionService.java:295-331`의 짝 맞춤을 옮긴다(입력 strip → slots → `kept` Set 크기를 slots·current와 비교해 같은 집합 판정 → `checkRefShape(target, wanted)` → `catalog.resolveReferences(target, added)` → 입력 순서로 이어 붙임). `applyRefChange`는 `diff` → null이면 null → `s.setRefRepos(new ArrayList<>(c.next()))` → `interviewService.appendSystemNote(s.getId(), systemNote(c))`. QuestionServiceTest의 `resolveReferences`·`appendSystemNote` 정확-인자 verify가 그대로 통과해야 한다.
  - `TaskService.create`: `resolveForRegistration` 바로 뒤 `List<RefRepo> refs = repoCatalogService.resolveReferences(repo.catalogId(), req.refRepos())`, 저장 전 `t.setRefRepos(new ArrayList<>(refs))`.
  - `startInterview`: DB 검증(`:322-323`) 다음 `Change refChange = RefRepoChanges.diff(t.getRepoCatalogId(), t.getRefRepos(), req == null ? null : req.refRepos(), repoCatalogService)` — 새로 들어온 것만 검증(등록 뒤 비활성된 기존 참조로 400이 나지 않게). 다른 setter와 함께 `refChange != null`이면 `t.setRefRepos(new ArrayList<>(refChange.next()))` → `createForTask`. 기존 승인 이력 행 다음 `TaskStatusHistory.log(t.getId(), INTERVIEWING, INTERVIEWING, "user", adminId, RefRepoChanges.approvalHistory(refChange))`.
  - `createForTask`: `s.setRefRepos(new ArrayList<>(t.getRefRepos() == null ? List.of() : t.getRefRepos()))`(DB 연결 복사 다음, 시그니처 그대로).
  - 승인 컨트롤러: `@Valid @RequestBody(required = false) ApproveRequest body`(바디 생략은 지금처럼 허용).
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.service.QuestionServiceTest" --tests "com.hamonsoft.netismaker.service.TaskServiceRepoCatalogTest" --tests "com.hamonsoft.netismaker.service.TaskServiceApproveDbTest" --tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.dto.RefRepoInputValidationTest" --tests "com.hamonsoft.netismaker.controller.TaskControllerApproveValidTest"`
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(task-refs): 참조 변경 도우미 분리·작업 등록·승인 때 참조 고르기`

### Task 3: 질문에서 작업 등록 — 출처 기록·대화 전문 첨부

**Files:**
- Create: `…/service/QuestionTranscript.java` (정적 렌더러)
- Modify: `…/service/InterviewService.java:670-675` 다음 (`questionTranscript`)
- Modify: `…/service/TaskService.java:109-170` (등록 흐름: 내부 `register` + 대화 전문 쓰기)
- Modify: `…/controller/TaskController.java:73-95` (등록자의 관리자 여부 전달)
- Test: `…/service/QuestionTranscriptTest.java`(신규), `…/service/InterviewServiceTest.java`, `…/service/TaskServiceSourceQuestionTest.java`(신규), `…/controller/TaskAttachmentApiIntegrationTest.java`(Testcontainers)

**Interfaces:**
- Consumes: Task 1 `Task.sourceQuestionId`, Task 2 `TaskCreateRequest.sourceQuestionId`, 기존 `AttachmentStorage.relativePath`·`writeText`·`deleteQuietly`·`sanitize`, `TaskAttachment.create`, `QuestionAttachmentRepository.findBySessionIdOrderByIdAsc`.
- Produces:
  - `final class QuestionTranscript` — `static final String CONTENT_TYPE = "text/markdown"`, `static String fileName(long questionId)` → `"question-" + id + "-대화.md"`, `static String render(InterviewSession q, List<InterviewTurn> turns, List<QuestionAttachment> attachments)`
  - `@Transactional(readOnly = true) public String InterviewService.questionTranscript(Long questionId, String viewerId, boolean isAdmin)` — `requireKind(id, QUESTION)`(없음·질문 아님 404) → `requireOwner`(403) → 상태 QUEUED·RUNNING이면 409 `답변이 끝난 뒤에 작업으로 등록할 수 있습니다` → 턴·첨부를 읽어 `render`
  - `TaskService.create(TaskCreateRequest req, String requesterId, String requesterEmail, boolean requesterIsAdmin)`, `create(TaskCreateRequest req, List<MultipartFile> files, String requesterId, String requesterEmail, boolean requesterIsAdmin)` — 기존 오버로드는 `false`로 위임(테스트 호출 그대로)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// QuestionTranscriptTest — 질문 9 "결재선 비교", 대상 Netis7.0·main, description "옛앱 결재선과 비교해 줘"
@Test void header_has_title_target_and_refs_with_pinned_commit_or_not_received() {
    String md = QuestionTranscript.render(q, List.of(), List.of());
    assertThat(md).startsWith("# 질문 #9 대화 전문 — 결재선 비교\n")
            .contains("- 대상: Netis7.0 · main")
            .contains("  - 옛앱 · release/6.5@aaaaaaa")
            .contains("  - 공통 UI · main — 받지 못함");
}
@Test void no_refs_reads_none()                       // "- 참조 repo(질문 당시): 없음"
@Test void first_question_comes_from_the_session_description_not_a_turn()  // "## 첫 질문\n\n옛앱 결재선과 비교해 줘"
@Test void turns_follow_in_order_with_role_labels() {
    // 턴 [assistant 0 "답", user 1 "추가?", system 2 "참조 repo 변경: ＋ X(main)", assistant 3 "답2"]
    assertThat(md.indexOf("### 답변 (턴 0)")).isLessThan(md.indexOf("### 추가 질문 (턴 1)"));
    assertThat(md).contains("> 시스템 메모: 참조 repo 변경: ＋ X(main)");
}
@Test void attachments_are_listed_by_name_only()      // 킥오프 "첨부: spec.pdf", 턴 1 "첨부: shot.png", storedPath 문자열 없음
@Test void file_name_is_question_id_dialog_md()       // fileName(9) == "question-9-대화.md"
@Test void title_and_attachment_names_stay_on_one_line()   // Review Focus 4
    // 제목 "비교\n# 가짜 머리", 첨부 "a\nb.pdf" → 첫 줄 "# 질문 #9 대화 전문 — 비교 # 가짜 머리", "첨부: a b.pdf", 줄 머리가 "# "인 줄은 첫 줄뿐

// InterviewServiceTest
@Test void questionTranscript_rejects_missing_or_interview_sessions_with_404()
@Test void questionTranscript_rejects_other_users_with_403_but_admin_may_read()
@ParameterizedTest @EnumSource(value = InterviewStatus.class, names = {"QUEUED", "RUNNING"})
void questionTranscript_while_answering_is_409(InterviewStatus st)   // hasMessage("답변이 끝난 뒤에 작업으로 등록할 수 있습니다")
@Test void questionTranscript_of_an_answered_or_closed_question_renders()   // AWAITING_INPUT·CANCELLED

// TaskServiceSourceQuestionTest — TaskServiceRepoCatalogTest와 같은 목 구성(taskRepo.save가 id 1을 준다)
@Test void json_registration_records_the_source_and_attaches_the_transcript() {
    when(interviewService.questionTranscript(9L, "user1", false)).thenReturn("# 전문 한글");
    when(attachmentStorage.relativePath(1L, 1, "question-9-대화.md")).thenReturn("task-1/1-question-9-대화.md");
    Task t = service.create(req(9L), "user1", null, false);
    assertThat(t.getSourceQuestionId()).isEqualTo(9L);
    verify(attachmentStorage).writeText("task-1/1-question-9-대화.md", "# 전문 한글");
    // TaskAttachment 캡처: originalFilename "question-9-대화.md", contentType "text/markdown",
    // sizeBytes == "# 전문 한글".getBytes(UTF_8).length, uploadedBy "user1"
}
@Test void multipart_registration_puts_the_transcript_after_the_user_files()   // 파일 2개 → relativePath(1L, 3, "question-9-대화.md")
@Test void a_forbidden_or_busy_question_saves_no_task()   // questionTranscript가 403/409 → verify(taskRepo, never()).save(any())
@Test void a_failed_transcript_write_cleans_up_and_rethrows()   // writeText 예외 → deleteQuietly(rel) 후 같은 예외
@Test void registration_without_a_source_reads_no_question()    // verify(interviewService, never()).questionTranscript(any(), any(), anyBoolean())
```

  - `TaskAttachmentApiIntegrationTest`: `질문에서_이어진_등록은_대화_전문을_마지막_첨부로_남긴다`(user1의 AWAITING_INPUT 질문 + multipart 파일 1개 + meta `sourceQuestionId` → 상세 `attachments[1].fileName == "question-{id}-대화.md"`, `sourceQuestionId`), `남의_질문은_403_답변_중이면_409이고_작업이_생기지_않는다`.

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.QuestionTranscriptTest" --tests "com.hamonsoft.netismaker.service.TaskServiceSourceQuestionTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - 렌더 형식(테스트가 고정하는 부분): `# 질문 #{id} 대화 전문 — {제목}` → 빈 줄 → `- 대상: {별칭} · {브랜치}`(별칭 null이면 `githubRepo`) → `- 참조 repo(질문 당시):` 다음 줄마다 `  - {별칭} · {브랜치}@{sha7}` 또는 `  - {별칭} · {브랜치} — 받지 못함`(없으면 같은 줄에 `없음`) → `## 첫 질문`(description) + 킥오프 첨부 `첨부: a, b` → `## 대화` 아래 턴 순서대로 assistant `### 답변 (턴 {seq})`, user `### 추가 질문 (턴 {seq})`(+ 그 턴 첨부 줄), system `> 시스템 메모: {content}`. 첨부는 `originalFilename`만. 시각 표기는 넣지 않는다(테스트 결정성). 제목·별칭·브랜치·첨부 이름처럼 한 줄에 들어가는 값은 줄바꿈(`\r`·`\n`)을 공백으로 바꾼다(턴 본문은 그대로).
  - `TaskService`: 내부 `private record Registration(Task task, String transcript)`와 `register(req, requesterId, email, isAdmin)` — 순서 429 → `resolveForRegistration` → `resolveReferences` → (`sourceQuestionId` 있으면) `interviewService.questionTranscript(...)` → `Task.create` … `setSourceQuestionId` → save → 이력. 권한·409가 저장 전에 난다.
  - JSON `create(…, isAdmin)`: transcript가 있으면 순번 1로 쓴다. multipart: 사용자 파일 1..N 다음 N+1(사용자 10개 한도와 별개 — `validate`는 사용자 파일만). 둘 다 기존 multipart 방식: `relativePath` → `attachmentRepo.save(TaskAttachment.create(id, AttachmentStorage.sanitize(name), rel, CONTENT_TYPE, bytes, requesterId))` → `written.add(rel)` → `writeText`; 예외면 `written.forEach(deleteQuietly)` 후 rethrow(트랜잭션 롤백).
  - 컨트롤러: `taskService.create(req, userId, AuthContext.email(auth), AuthContext.isAdmin(auth))`, multipart도 같은 5인자.
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.service.TaskServiceAttachmentTest" --tests "com.hamonsoft.netismaker.service.TaskServiceAttachmentRollbackTest" --tests "com.hamonsoft.netismaker.controller.TaskAttachmentApiIntegrationTest"`
  - Expected: PASS(통합 테스트는 Docker 없으면 skip)
- [ ] **Step 5: 커밋** — `feat(task-refs): 질문에서 작업 등록 — 출처 기록·대화 전문 첨부`

### Task 4: 작업 인터뷰 — 답변 중 참조 변경·claim·계획 보고·확정

**Files:**
- Modify: `…/dto/AnswerRequest.java:6-9` (3번째 `refRepos` + 2인자 호환 — `QuestionAskRequest.toAnswerRequest`가 쓴다)
- Modify: `…/dto/InterviewClaimResponse.java:18-169` (25번째 `boolean refsCleared`, `of`는 false, `withDb`·`withRefRepos`가 전달, `withRefsCleared` 신설)
- Modify: `…/dto/WorkerPlanRequest.java:6-16` (10번째 `refStates` + 9인자 호환)
- Modify: `…/service/InterviewService.java` — setter(생성자 `:64-86` 다음), `claim :163-184`, `refsFor :186-199`, `recordPlan :338-362`, 답변 래퍼(`submitAnswer :426-448` 다음), `confirm :538-590`
- Modify: `…/controller/InterviewController.java:56-65`
- Test: `…/service/InterviewServiceTest.java`(`:711` 뒤집기 + 추가), `…/service/InterviewServiceConfirmModeTest.java`, `…/dto/InterviewClaimResponseTest.java`, `…/dto/WorkerPlanRequestJsonTest.java`(신규), `…/controller/InterviewControllerAnswerRefsTest.java`(신규), `…/dto/RefRepoInputValidationTest.java`, `…/controller/InterviewApiIntegrationTest.java`(Testcontainers)

**Interfaces:**
- Consumes: Task 2 `RefRepoChanges.diff`·`systemNote`·`NOTE_PREFIX`·`CLEARED_NOTE`, 기존 `applyRefStates`·`InterviewWorkPaths.refDir`·`RefRepo.withError`·`label`.
- Produces (인터뷰 서비스 Task 9·프론트 Task 13이 쓴다):
  - `AnswerRequest(@NotBlank String answer, Integer replyToSeq, @Valid List<RefRepoInput> refRepos)`
  - `InterviewClaimResponse.refsCleared()`(JSON `refsCleared`), `InterviewClaimResponse withRefsCleared(boolean)`
  - `WorkerPlanRequest(…9개…, List<WorkerQuestionRequest.RefState> refStates)`
  - `public record InterviewService.AnswerResult(AnswerOutcome outcome, InterviewTurn refNote)`
  - `@Transactional public AnswerResult submitAnswerWithRefs(Long sessionId, String adminId, AnswerRequest req)`
  - `@Autowired void setRepoCatalogService(RepoCatalogService s)` — 생성자를 바꾸지 않는다(`new InterviewService(` 테스트 4곳 보호, `WorkerService.setEvents` 선례)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// InterviewServiceTest — 기존 claim_interview_session_has_no_refs(:711)를 대체
@Test void claim_interview_session_carries_reference_dirs() {
    InterviewSession s = session(1L, QUEUED); s.setRefRepos(new ArrayList<>(List.of(ref(11L, "main", null, null, null))));
    InterviewClaimResponse c = service.claim("w1").orElseThrow();
    assertThat(c.refRepos()).extracting(InterviewClaimResponse.ClaimRefRepo::dir).containsExactly(root + "/_refs/1/11");
    assertThat(c.refsCleared()).isFalse();
}
@Test void claim_interview_marks_refs_cleared_only_when_the_last_ref_note_cleared_them() {
    // refs [] + 턴 [note "참조 repo 변경: ＋ 옛앱(main)", note "참조 repo 변경: 없음(전부 해제)"] → true
    // refs [] + 참조 메모 없음 → false;  refs [] + 마지막 참조 메모가 "참조 repo 변경: ＋ …" → false
    // QUESTION 세션은 같은 턴이어도 false
}
@Test void recordPlan_pins_reported_ref_states()          // refs [11 main] + refStates (11, main, SHA_A, null) → sha SHA_A
@Test void recordPlan_without_ref_states_leaves_refs_untouched()   // 9인자 생성자
@Test void applyRefStates_pins_only_a_full_hex_sha()
    // sha "abc"·"--upload-pack=x"·39자 → 고정하지 않음(sha null 유지, error도 안 바꿈), 40자·64자 16진수 → 고정 (question·plan 보고 모두)
@Test void submitAnswerWithRefs_without_refs_is_a_plain_answer()   // refNote null, verifyNoInteractions(repoCatalog)
@Test void submitAnswerWithRefs_changes_refs_and_appends_the_note() {
    // AWAITING_INPUT INTERVIEW 세션 repoCatalogId 1, refs [11 main sha=a…], 입력 [11 main, 12 master]
    AnswerResult r = service.submitAnswerWithRefs(1L, "admin", new AnswerRequest("답", 3, List.of(in(11, "main"), in(12, "master"))));
    assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 배치 서버(master)");
    assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
    assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
}
@Test void submitAnswerWithRefs_rejection_propagates_and_adds_no_note()   // 4개 → 400, 시스템 노트 저장 없음

// InterviewServiceConfirmModeTest
@Test void confirm_copies_only_pinned_refs_without_errors_and_logs_them() {
    // 세션 refs [옛앱 release/6.5 sha=a… error="마지막 턴 실패", 공통 UI main sha=null]
    service.confirm(25L, "admin", false, ImplementationMode.DIRECT);
    assertThat(task.getRefRepos()).containsExactly(옛앱Pinned.withError(null));
    assertThat(lastHistoryReason()).isEqualTo("구현 참조: 옛앱(release/6.5)@aaaaaaa / 제외(인터뷰에서 받지 못함): 공통 UI(main)");
}
@Test void confirm_with_no_pinned_ref_logs_none()          // "구현 참조: 없음 / 제외(인터뷰에서 받지 못함): 공통 UI(main)", task refs []
@Test void confirm_without_session_refs_empties_task_refs_and_adds_no_row()   // 마지막 이력은 "플랜 확정 → …" 그대로

// InterviewClaimResponseTest — withDb·withRefRepos가 refsCleared를 유지, JSON에 "refsCleared":true
// WorkerPlanRequestJsonTest — refStates JSON 왕복, refStates 없는 바디는 null
// InterviewControllerAnswerRefsTest — 컨트롤러 직접 생성(목 InterviewService·InterviewStreamService)
@Test void answer_pushes_queued_then_the_ref_note() {
    InOrder o = inOrder(stream);
    o.verify(stream).pushStatus(1L, InterviewStatus.QUEUED);
    o.verify(stream).pushNote(1L, 7, "참조 repo 변경: ＋ 배치 서버(master)");
}
@Test void answer_without_a_ref_change_pushes_no_note()
// RefRepoInputValidationTest — AnswerRequest 256자 브랜치 → "refRepos[0].branch"
// InterviewApiIntegrationTest
@Test void 답변의_참조가_거절되면_답변_턴까지_롤백된다()   // refRepos [대상과 같은 카탈로그 1] → 400, 세션 AWAITING_INPUT, user answer 턴 없음
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.service.InterviewServiceConfirmModeTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - `refsFor`: `!s.isQuestion() ||` 조건을 빼고 javadoc을 "질문·인터뷰 세션 공통"으로 고친다. 경로 규칙은 1단계 그대로.
  - `refsCleared(s, turns)`(private): INTERVIEW이고 refs가 비었고, role `system`·kind `note`·content가 `NOTE_PREFIX`로 시작하는 마지막 턴의 content가 `CLEARED_NOTE`일 때만 true. claim은 이미 읽은 `turns`를 쓴다(쿼리 추가 없음): `.withRefRepos(refsFor(s)).withRefsCleared(refsCleared(s, turns))`.
  - `recordPlan`: `addUsage` 다음 `applyRefStates(s, req.refStates())`.
  - `applyRefStates`: sha 고정 분기에 형식 검사를 더한다 — `^[0-9a-fA-F]{40}$` 또는 `{64}`일 때만 `pinned`. 아니면 그 상태를 무시한다(워커 `isFullSha`와 같은 정의 — 화면이 '고정'으로 보여 준 참조가 구현에서 형식 오류로 실패하지 않게).
  - `submitAnswerWithRefs`: `submitAnswer(sessionId, adminId, true, req)` → `RefRepoChanges.diff(s.getRepoCatalogId(), s.getRefRepos(), req.refRepos(), repoCatalogService)` → 바뀌면 `setRefRepos` + `appendSystemNote(systemNote)`. 한 트랜잭션이라 400이면 답변 턴·재큐까지 롤백(1단계 `ask`와 같은 순서·같은 중복 제출 규칙).
  - `confirm`: 기존 확정 이력 행(`:573-575`, 문구 불변) 다음 — 세션 refs 중 sha 있는 것만 `r.withError(null)`로 `t.setRefRepos(new ArrayList<>(…))`(세션이 비면 `[]`로 덮는다). 세션 refs가 있었으면 `TaskStatusHistory.log(t.getId(), to, to, "user", adminId, …)`: `구현 참조: {label}@{sha7}, …`(없으면 `없음`) + 뺀 것이 있으면 ` / 제외(인터뷰에서 받지 못함): {label}, …`. 확정은 막지 않는다.
  - 컨트롤러 `answer`: `submitAnswerWithRefs` → `pushStatus(QUEUED)` → `refNote`가 있으면 `pushNote(id, seq, content)` → `getResponse`.
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.dto.InterviewClaimResponseTest" --tests "com.hamonsoft.netismaker.dto.WorkerPlanRequestJsonTest" --tests "com.hamonsoft.netismaker.controller.InterviewControllerAnswerRefsTest" --tests "com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest" --tests "com.hamonsoft.netismaker.dto.RefRepoInputValidationTest" --tests "com.hamonsoft.netismaker.controller.InterviewApiIntegrationTest"`
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(task-refs): 작업 인터뷰 참조 — 답변 중 변경·claim·계획 보고·확정 고정`

### Task 5: 구현 claim·결과·재시도 — 참조 싣기·구버전 워커 막기·경고 이력·빼기

**Files:**
- Modify: `…/dto/WorkerTaskResponse.java:23-140`(구성요소·호환 생성자·`withDbMcp`), `:165-178`(`forImplementation`)
- Modify: `…/repository/TaskRepository.java:124` 다음 (네이티브 claim 쿼리 + default 래퍼)
- Modify: `…/service/WorkerService.java:148-152`(claim), `:268-286`(지각 정합), `:448-455`(마지막 이력)
- Modify: `…/controller/WorkerController.java:57-62`
- Modify: `…/dto/WorkerResultRequest.java:23-117`
- Modify: `…/dto/ImplementationRetryRequest.java:4`, `…/service/TaskService.java:472-496`, `…/controller/TaskController.java:204-214`
- Test: `…/dto/WorkerTaskResponseRefReposTest.java`(신규), `…/dto/WorkerResultRequestJsonTest.java`, `…/service/WorkerServiceRefReposTest.java`(신규), `…/controller/WorkerControllerDbMcpTest.java`(수정), `…/service/TaskServiceImplementationRetryTest.java`, `…/repository/TaskClaimRefSupportIntegrationTest.java`(신규, Testcontainers)

**Interfaces:**
- Consumes: Task 1 `Task.refRepos`, `RefRepo.label`, `GitRemotes.mask`.
- Produces (워커 Task 6·7이 쓴다):
  - `record WorkerTaskResponse.TaskRefRepo(Long catalogId, String alias, String githubRepo, String gitUrl, String repoHost, String branch, String sha)`, 31번째 `List<TaskRefRepo> refRepos`(compact 생성자: null → `List.of()`), 30인자 호환 생성자, `forImplementation`에서만 채움(githubRepo = `path`, repoHost = `RepoRef.fromSnapshot(path, gitUrl).host()`, dir 없음)
  - `WorkerResultRequest` 25번째 `List<String> refWarnings`(null 유지), 24인자 호환, `withImplementationAttempt`·`masked()`(원소마다 `GitRemotes.mask` 뒤 500자 — 서로게이트 반쪽 버림, 원소당 상한은 여기 한 곳이 맡는다) 반영, 새 `WorkerResultRequest withRefWarnings(List<String> warnings)`
  - `ResponseEntity<WorkerTaskResponse> nextTask(@RequestParam String workerId, @RequestParam(required = false) String supports)`, `static boolean supportsRefRepos(String supports)` — 쉼표로 나눠 trim, `refRepos`와 정확히 같은 토큰
  - `WorkerService.claimNextTask(String workerId, boolean supportsRefRepos)` — 1인자는 `true`로 위임(기존 단위 테스트 호환, 컨트롤러는 항상 2인자)
  - `default List<Task> TaskRepository.findClaimableExcludingRefImplementationsForUpdateSkipLocked()`
  - `ImplementationRetryRequest(String implementationMode, List<Long> removeRefCatalogIds)` + 1인자, `TaskService.retryImplementation(Long taskId, String adminId, String rawMode, List<Long> removeRefCatalogIds)` + 3인자 위임

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// WorkerTaskResponseRefReposTest
@Test void implementation_claim_carries_pinned_refs_without_dirs() {
    t.setRefRepos(List.of(new RefRepo(11L, "옛앱", "g/old", "https://gitlab.hamon.vip/g/old.git", "release/6.5", SHA, "t", null)));
    WorkerTaskResponse r = WorkerTaskResponse.forImplementation(t, null, null, "# 플랜");
    assertThat(r.refRepos()).containsExactly(new WorkerTaskResponse.TaskRefRepo(11L, "옛앱", "g/old",
            "https://gitlab.hamon.vip/g/old.git", "gitlab", "release/6.5", SHA));
    assertThat(r.withDbMcp(new DbMcpGrant(null, List.of(), List.of())).refRepos()).isEqualTo(r.refRepos());
    assertThat(lenient.readValue(lenient.writeValueAsString(r), WorkerTaskResponse.class).refRepos()).isEqualTo(r.refRepos());
}
@Test void other_kinds_old_payloads_and_the_30_arg_constructor_have_an_empty_list()  // forDeploy·forDesign·forClose·forAnalysis·옛 JSON

// WorkerResultRequestJsonTest
@Test void ref_warnings_survive_json_masking_and_the_attempt_copy()  // withRefWarnings → withImplementationAttempt(2) 뒤에도 유지, 토큰 URL 가림, 옛 JSON은 null
@Test void masked_cuts_each_warning_to_500_after_masking()          // 600자 원소 → 500자(서로게이트 반쪽 버림), 토큰은 가린 뒤 자름

// WorkerServiceRefReposTest — 기존 WorkerService 단위 테스트 구성
@Test void a_worker_without_ref_support_claims_through_the_filtered_queue() {
    service.claimNextTask("w1", false);
    verify(taskRepo).findClaimableExcludingRefImplementationsForUpdateSkipLocked();
    verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any());
}
@Test void a_ref_aware_worker_and_the_one_arg_call_use_the_full_queue()
@Test void pr_created_with_ref_warnings_logs_a_second_same_status_row() {
    // IMPLEMENTING → PR_CREATED, withRefWarnings(List.of("옛앱: 파일 3개", "공통 UI: HEAD가 고정 커밋과 다름"))
    assertThat(reasons()).containsExactly("PR 생성: https://github.com/a/b/pull/7",
            "참조 repo 변경 감지(PR에는 포함 안 됨): 옛앱: 파일 3개, 공통 UI: HEAD가 고정 커밋과 다름");
    // 둘째 행 from = to = PR_CREATED
}
@Test void late_pr_reconciliation_also_logs_the_warning_row()      // IMPLEMENTATION_FAILED → PR_CREATED 분기
@Test void null_empty_or_blank_warnings_add_no_row()
@Test void the_warning_row_is_masked_then_cut_to_500()
@Test void implementation_failed_reports_add_no_warning_row()

// WorkerControllerDbMcpTest — 스텁 claimNextTask("w1", false), 호출 nextTask("w1", null)로 바꾼다
@Test void supports_parsing()   // "refRepos"·" x , refRepos" true / null·""·"refrepos"·"refReposX" false
@Test void next_task_passes_ref_support_through()   // nextTask("w1", "refRepos") → verify(workerService).claimNextTask("w1", true)

// TaskServiceImplementationRetryTest
@Test void retry_removes_the_chosen_refs_keeps_the_rest_pinned_and_logs_them() {
    // refs [11 옛앱 release/6.5 sha=a, 12 공통 UI main sha=b]
    service.retryImplementation(42L, "admin", null, List.of(11L));
    assertThat(t.getRefRepos()).extracting(RefRepo::sha).containsExactly("b".repeat(40));
    assertThat(reasons()).containsExactly("구현 재시도 (2회째, 구현 방식: 리뷰, 이전 브랜치: netismaker/task-42-t)",
            "참조 제외(구현 재시도): 옛앱(release/6.5)");
}
@Test void removing_a_ref_the_task_does_not_have_is_400_before_any_change()   // "작업에 없는 참조는 뺄 수 없습니다", 시도 1, 상태·이력 그대로
@Test void a_null_or_empty_removal_list_is_the_plain_retry()                  // 이력 1행

// TaskClaimRefSupportIntegrationTest (RUN_TESTCONTAINERS)
@Test void 참조_지원을_알리지_않은_워커는_참조_있는_구현대기를_건너뛴다()   // 오래된 APPROVED(refs) + 새 PENDING → false면 PENDING, true면 APPROVED(refRepos 1개)
@Test void 참조가_있어도_디자인_배포_종료_claim은_막지_않는다()            // DESIGN_PENDING(refs)도 false로 claim
@Test void removing_every_ref_makes_the_task_claimable_by_old_workers()   // Review Focus 5 — retryImplementation(…, [전부]) 뒤 claimNextTask(w, false)가 그 작업을 가져가고 refRepos == []
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseRefReposTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceRefReposTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - 네이티브 쿼리(JPQL로 jsonb 비교를 못 하므로 `findRecentlyDeployedWithEnv`의 `<> CAST('[]' AS jsonb)` 선례를 따른다). 상태 파라미터는 JPQL 후보 6개(`:115-120`)의 `dbValue()`, `approved` = `TaskStatus.APPROVED.dbValue()` — default 래퍼가 채운다:

```sql
SELECT t.* FROM com.task t
WHERE t.status IN (:claimable) AND t.deleted_at IS NULL
  AND NOT (t.status = :approved AND t.ref_repos <> CAST('[]' AS jsonb))
ORDER BY t.created_at ASC
LIMIT 1
FOR UPDATE SKIP LOCKED
```

  - 경고 이력: 공백 아닌 원소를 strip해 `, `로 잇고 접두 `참조 repo 변경 감지(PR에는 포함 안 됨): `를 붙여 `GitRemotes.mask` → 500자 자르기(가린 뒤 자름, 서로게이트 반쪽 버림 — `InterviewService.storedRefError` 방식). `TaskStatusHistory.log(id, PR_CREATED, PR_CREATED, "worker", workerId, …)`. 일반 경로는 `:454` 행 다음(상태가 PR_CREATED일 때만), 지각 정합은 `:281-283` 행 다음. 이전 시도 보고(`:257-263`)·IMPLEMENTATION_FAILED·배포중지 복귀에는 남기지 않는다.
  - `retryImplementation`: 상태 가드 다음·`prepareImplementationRequeue` 전에 null 아닌 id를 모아 모두 `t.getRefRepos()`의 catalogId에 있는지 확인(아니면 400). 기존 재시도 행(문구 불변) 다음 `t.setRefRepos(남은 것)` + `TaskStatusHistory.log(id, APPROVED, APPROVED, "user", adminId, "참조 제외(구현 재시도): " + 뺀 label들(작업 순서))`. 남은 원소의 sha는 그대로.
  - 컨트롤러: `nextTask`는 `workerService.claimNextTask(workerId, supportsRefRepos(supports))`, 재시도는 `body.removeRefCatalogIds()`를 넘긴다.
  - 깨질 기존 테스트: `WorkerControllerDbMcpTest` 스텁 3개·호출 3개(2인자로). `ImplementationModeFlowIntegrationTest`·`TaskDesignFlowIntegrationTest`·`CloseArtifactFlowIntegrationTest`는 `supports` 없이 next-task를 부르므로 새 네이티브 쿼리의 회귀 검사가 된다.
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseImplementationTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestJsonTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestMaskTest" --tests "com.hamonsoft.netismaker.controller.WorkerControllerDbMcpTest" --tests "com.hamonsoft.netismaker.service.TaskServiceImplementationRetryTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationAttemptTest" --tests "com.hamonsoft.netismaker.repository.TaskClaimRefSupportIntegrationTest" --tests "com.hamonsoft.netismaker.controller.ImplementationModeFlowIntegrationTest"`
  - Expected: PASS(`a_payload_without_credentials_is_value_equal_after_masking` 포함 — refWarnings null은 null로 남는다)
- [ ] **Step 5: 커밋** — `feat(task-refs): 구현 claim 참조·구버전 워커 막기·참조 경고 이력·재시도 때 참조 빼기`
### Task 6: 워커 git 계층 — 무프롬프트 참조 git·고정 커밋 준비·참조 worktree

**Files:**
- Modify: `…/workerdaemon/ProcessRunner.java` — `:50-55` 옆에 env 제거 오버로드, `:85-91` `execute`에 `removeEnv` 인자(기존 `run`·`runStreaming`은 `Set.of()`를 넘긴다).
- Modify: `…/workerdaemon/GitRepoCache.java` — `:144-188`(인자 조립) 뒤에 참조용 상수·인자, `:134`(`doEnsureFresh`) 뒤에 `prepareRefCommitUnderLock`. `ensureFresh`·`fetchOnly`·`withRepoLock`은 바꾸지 않는다.
- Modify: `…/workerdaemon/WorktreeService.java` — `:234-236` 옆 `refWorktreeDir`, `:291`(`discardUnderLock`) 뒤에 참조 생성·검사·삭제, `:371` 옆 `RefWorktree`.
- Test(신규): `…/workerdaemon/ProcessRunnerEnvTest.java`, `GitRepoCacheRefTest.java`, `WorktreeServiceRefTest.java`

**Interfaces:**
- Consumes: Task 5 `WorkerTaskResponse.TaskRefRepo(Long catalogId, String alias, String githubRepo, String gitUrl, String repoHost, String branch, String sha)`. 워커의 `repoRefOf`는 `repoHost`가 `"github"`·`"gitlab"`이면 `new RepoRef(repoHost, githubRepo, gitUrl)`, 비었으면 `RepoRef.fromSnapshot(githubRepo, gitUrl)`(API가 `repoHost`를 같은 `fromSnapshot`으로 채우므로 운영 동작은 같다). 테스트의 file:// 픽스처(`repoHost "github"`)도 그래서 기존 캐시 키 `acme/widgets`를 쓴다 — 참조 캐시 키는 항상 `GitRemotes.localKey(WorktreeService.repoRefOf(ref))`로 구한다.
- Produces:
  - `ProcessRunner.run(File dir, List<String> cmd, Map<String,String> extraEnv, Set<String> removeEnv, long timeoutSeconds)`(public static), `static void applyEnv(Map<String,String> env, Map<String,String> extraEnv, Set<String> removeEnv)` — 이름을 대소문자 무시로 지운 뒤 `extraEnv`를 넣는다(인터뷰 서비스 `noPromptGitEnv`와 같은 규칙).
  - `GitRepoCache`
    - `static final List<String> REF_GIT_PREFIX = List.of("git", "-c", "credential.helper=", "-c", "core.askPass=")`, `static final Map<String,String> REF_GIT_ENV = Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "never")`, `static final Set<String> REF_GIT_ENV_REMOVED = Set.of("GIT_ASKPASS", "SSH_ASKPASS")`
    - `static final String REF_NAMESPACE = "refs/netis-refs/"`, `static final List<Integer> REF_FALLBACK_DEPTHS = List.of(50, 500)`
    - `static List<String> refGit(String... args)`, `static ProcessRunner.Result runRefGit(File dir, List<String> args, long timeoutSeconds)` — 참조 git 호출의 단일 통로(앞 인자 + env).
    - `static List<String> refCloneArgs(String authUrl, String branch, String dirName)`, `static List<String> refFetchShaArgs(String authUrl, String sha)` = `fetch --no-tags --depth=1 <url> <sha>`, `static List<String> refFetchBranchArgs(String authUrl, String branch, int depth)` = `fetch --no-tags --depth=N <url> +refs/heads/B:refs/netis-refs/B`
    - `static boolean isFullSha(String s)` — 40자 또는 64자 16진수만.
    - `public File prepareRefCommitUnderLock(RepoRef ref, String branch, String sha) throws IOException, InterruptedException` — 호출자가 그 캐시의 레포 락을 쥐고 있어야 한다. 캐시 디렉터리를 돌려준다.
  - `WorktreeService`
    - `static final String REFS_DIR = "_refs"`, `static final String REF_PUSH_URL_BLOCKED = "netis-read-only-ref://push-disabled"`
    - `public record RefWorktree(long catalogId, String alias, String branch, String sha, String repoKey, File dir, List<String> baseline)`
    - `public static RepoRef repoRefOf(WorkerTaskResponse.TaskRefRepo ref)`, `public Path refWorktreeDir(long taskId, long catalogId)` → `{worktree-root}/_refs/task-{id}/{catalogId}`
    - `public RefWorktree createRefWorktree(long taskId, WorkerTaskResponse.TaskRefRepo ref) throws IOException, InterruptedException`
    - `public Optional<String> refChangeWarning(RefWorktree ref)`(예외 없음), `static Optional<String> changeWarning(String alias, List<String> baseline, List<String> now, String pinnedSha, String head)`
    - `public void discardRefWorktree(long taskId, WorkerTaskResponse.TaskRefRepo ref)` — best-effort. 대상용 `discard(repoKey, kind, id)`와 별개(`WorktreeImmediateDiscardTest`가 그 시그니처를 `never()`로 본다).
    - `public DiscardResult discardRefUnderLock(Path refDir, Path cacheDir) throws IOException, InterruptedException` — `cacheDir`가 null이면 git 없이 폴더만(Task 8).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// ProcessRunnerEnvTest — 자식은 ProcessRunnerTimeoutTest처럼 JDK java로 띄우는 단일 파일 소스(인자로 받은 이름의 env 출력, 없으면 "null")
@Test void apply_env_removes_names_case_insensitively_then_adds_extras()
    // {GIT_ASKPASS:a, ssh_askpass:b, PATH:p} − {GIT_ASKPASS, SSH_ASKPASS} + {GIT_TERMINAL_PROMPT:0} → {PATH:p, GIT_TERMINAL_PROMPT:0}
@Test void a_removed_parent_variable_never_reaches_the_child()
    // 부모 env에서 PATH·SystemRoot·JAVA*가 아닌 이름 하나(assumeTrue) → removeEnv에 소문자로 → 자식 출력 "null"
@Test void the_existing_overloads_keep_the_parent_environment()      // 같은 이름 → 부모 값 그대로

// GitRepoCacheRefTest — 실제 git, file:// bare(GitRepoCacheTest의 props·git 도우미 복사).
// 호출은 cache.withRepoLock(key, () -> cache.prepareRefCommitUnderLock(ref, "main", sha))
@Test void clones_a_missing_cache_with_a_plain_origin()      // 캐시 생김, remote get-url origin == 평문, .git/config에 "oauth2:" 없음
@Test void a_commit_already_in_the_cache_needs_no_fetch()     // sha = 캐시 HEAD → .git/FETCH_HEAD 없음
@Test void fetches_a_missing_commit_by_sha_without_moving_origin_or_head()
    // 캐시 C1 → 원격에 C2 → sha=C2 → cat-file 성공, refs/remotes/origin/main == C1, HEAD == C1
@Test void falls_back_to_the_ref_namespace_when_sha_wants_are_refused()
    // 캐시 config protocol.version=0, 원격 uploadpack.allow{Tip,Reachable,Any}SHA1InWant=false, 원격 C2·C3, sha=C2(광고 안 된 커밋)
    // → 성공, refs/netis-refs/main == C3, origin/main·HEAD 불변
@Test void a_commit_found_nowhere_fails_with_a_clear_reason_and_stays_shallow()
    // sha="0123456789abcdef0123456789abcdef01234567" → IOException 메시지에 "고정 커밋 0123456"·"최근 500개", is-shallow-repository == "true"
@Test void rejects_a_malformed_sha_before_running_git()       // null·""·"--upload-pack=x"·39자 → IOException, 캐시 폴더 안 생김
@Test void ref_git_never_uses_a_helper_askpass_or_prompt()
    // refCloneArgs·refFetchShaArgs·refFetchBranchArgs가 REF_GIT_PREFIX로 시작, refFetchBranchArgs(u,"release/6.5",50)에
    // "--depth=50"·"+refs/heads/release/6.5:refs/netis-refs/release/6.5", REF_FALLBACK_DEPTHS == [50, 500], REF_GIT_ENV·REF_GIT_ENV_REMOVED 값
@Test void failure_message_never_contains_the_token()  // https://gitlab.invalid.example/g/p.git, 캐시 없음 → 메시지에 "glpat-" 없음, 프롬프트 없이 끝남
@Test void a_401_remote_fails_fast_without_prompting()   // Review Focus 1
    // 로컬 HttpServer가 모든 요청에 401 + WWW-Authenticate → prepareRefCommitUnderLock이 20초 안에 IOException
    // (메시지에 "terminal prompts disabled" 또는 "Authentication failed"), 실패 뒤 {repos}/{refKey} 폴더(특히 .git)가 남지 않는다
    // (git clone이 실패 시 스스로 지운다 — 다음 준비가 다시 clone 경로를 탄다). refKey = GitRemotes.localKey(repoRefOf(ref))

// WorktreeServiceRefTest — 실제 git; ref = new TaskRefRepo(12L, "옛앱", "acme/widgets", bareUri, "github", "main", sha)
@Test void creates_a_detached_ref_worktree_at_the_pinned_commit_outside_target_trees()
    // dir == worktreeRoot/_refs/task-7/12, rev-parse HEAD == sha, symbolic-ref -q HEAD 실패, baseline 빈 목록
@Test void only_the_ref_worktree_gets_the_blocked_push_url()
    // ref dir: config --get remote.origin.pushurl == REF_PUSH_URL_BLOCKED; 같은 캐시(RefWorktree.repoKey)에서
    // repos.ensureFresh(repoRefOf(ref), "main") → worktrees.create(cacheDir, refKey, "main", …)로 만든 대상 worktree와 캐시 본체: 값 없음(exit 1)
    // ref dir에서 git push origin HEAD:refs/heads/x → 실패, 원격에 x 없음
@Test void holds_one_repo_lock_at_a_time()   // withRepoLock을 감싼 GitRepoCache 하위 클래스로 깊이 기록 → 최대 1, 키 == [refKey]
@Test void recreates_over_a_leftover_folder() // 같은 폴더에 쓰레기 파일 → 다시 만들어지고 쓰레기 없음
@Test void rejects_a_missing_sha_before_taking_the_lock()   // sha null → IOException("고정 커밋 정보…"), {repos}/{refKey}.lock 없음
@Test void reports_only_changes_made_after_the_baseline()
    // 손대지 않음 → empty; new.txt + 무시 파일 build/x(원격 .gitignore에 build/) → "옛앱: 파일 2개"; 그 안에서 커밋 → "옛앱: HEAD가 고정 커밋과 다름"
@Test void change_warning_compares_with_the_baseline()  // 순수: 기준선 [" M crlf.txt"]·지금 같음 → empty, 지금이 2줄 더 → "옛앱: 파일 2개"
@Test void two_tasks_hold_separate_worktrees_of_the_same_ref()   // Review Focus 2
    // createRefWorktree(7, ref)·(8, ref) → 두 폴더 모두 HEAD == sha, worktree list에 둘 다;
    // discardRefWorktree(7, ref) 뒤 _refs/task-8/12는 그대로이고 그 안에서 git status가 성공
@Test void discards_the_ref_worktree_its_metadata_and_the_empty_task_folder()
    // discardRefWorktree(7, ref) → 폴더·_refs/task-7 없음, worktree list에 없음, 캐시 HEAD 불변; 두 번째 호출도 예외 없음
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerEnvTest" --tests "com.hamonsoft.netismaker.workerdaemon.GitRepoCacheRefTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceRefTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - `execute`: `pb.environment()`에 `applyEnv(env, extraEnv, removeEnv)`. 지우기 먼저, 넣기 나중.
  - `prepareRefCommitUnderLock` 순서(모든 git은 `runRefGit` — 네트워크 명령 600초, 로컬 명령 120초):
    1. `isFullSha(sha)` 아님 → `IOException("고정 커밋 정보가 없거나 올바르지 않습니다")`, 브랜치 blank → `IOException("참조 브랜치 정보가 없습니다")`. git을 부르기 전.
    2. 캐시 `.git`이 없으면 `refCloneArgs`(인증 URL) 뒤 `scrubOrigin`, 있으면 `scrubOrigin`만.
    3. `cat-file -e {sha}^{commit}`이 0이면 끝. 아니면 `refFetchShaArgs` → 다시 확인.
    4. `REF_FALLBACK_DEPTHS` 순서로 `refFetchBranchArgs` → 매번 확인. 깊이 제한 없는 fetch·`--unshallow`는 하지 않는다.
    5. 끝까지 없으면: 마지막 fetch가 실패였으면 그 출력(가림)으로 `IOException("git failed (…): …")`, fetch는 됐으면 `IOException("고정 커밋 {sha7}을(를) 찾지 못했습니다 — 브랜치 {branch}의 최근 500개 커밋 안에 없음")`.
  - `createRefWorktree`: sha·브랜치 검사(같은 문구, 락 전) → `repos.withRepoLock(refKey, …)` **한 번** 안에서 `prepareRefCommitUnderLock` → 폴더가 있으면 `worktree remove --force`(실패 무시) + `deleteLeftover` → `worktree add --force --detach {dir} {sha}` → 캐시에 `config extensions.worktreeConfig true` → dir에서 `config --worktree remote.origin.pushurl {REF_PUSH_URL_BLOCKED}` → 기준선 = `status --porcelain --untracked-files=all --ignored=matching`의 비지 않은 줄. 이것들도 `runRefGit`(체크아웃 필터의 자격증명 요청도 묻지 않게). 실패는 가린 출력의 `IOException`.
  - `changeWarning`: HEAD ≠ sha → `{별칭}: HEAD가 고정 커밋과 다름`(파일 수보다 우선). 아니면 기준선에 없는 줄 수 N>0 → `{별칭}: 파일 {N}개`. 둘 다 아니면 empty.
  - `refChangeWarning`: 락 없이(그 worktree 전용 index만 건드림) status + `rev-parse HEAD`. 폴더 없음 → `{별칭}: 참조 폴더가 지워짐`, git 실패 → `{별칭}: 변경 확인 실패`.
  - `discardRefWorktree`: 폴더가 없으면 락 없이 끝. 있으면 `withRepoLock(refKey)` 안에서 `discardRefUnderLock(dir, {repos-dir}/{refKey})`, 락 밖에서 `_refs/task-{id}`가 비었으면 지운다. 예외는 경고 로그만.
  - `discardRefUnderLock`: `cacheDir`가 있고 `.git` 디렉터리면 `isGitLocked` → `GIT_LOCKED`, `worktree remove --force`(실패 무시) → 남은 폴더 `RepoCacheCleanupJob.deleteTree`(실패 n건이면 `IOException`) → `worktree prune`. `cacheDir` null 또는 `.git` 없음 → 잠금 검사 없이 폴더만. 브랜치 삭제는 없다(detached).
- [ ] **Step 4: 통과 확인**
  - Run: Step 2 명령 + `--tests "com.hamonsoft.netismaker.workerdaemon.GitRepoCacheTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerTimeoutTest" --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerMaskTest" --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerStreamingTest"`
  - Expected: PASS. 기존 테스트는 오버로드만 늘어 깨지지 않는다.
- [ ] **Step 5: 커밋** — `feat(worker): 참조 repo 고정 커밋 준비·참조 worktree(무프롬프트 git)`

### Task 7: 워커 구현 흐름에 참조 연결 (`WorkerMainLoop`)

**Files:**
- Create: `…/workerdaemon/RefRepoPrompts.java` — 참조 절 빌더(프롬프트·계획 파일 머리·PR 본문 절·실패 문구).
- Modify: `…/workerdaemon/WorkerMainLoop.java` — `:240-399` `processImplementation`, `:746-786` `renderPrBody`, 클래스 주석 `:36-44`.
- Modify: `…/workerdaemon/ImplementationPrompts.java` — `:40-47` `planSection` 5인자, `:61-78` `sddPlanSection`.
- Modify: `…/workerdaemon/WorkerHttpClient.java:79-90` — `supports` 쿼리.
- Test: `RefRepoPromptsTest.java`·`WorkerMainLoopRefReposTest.java`·`WorkerHttpClientNextTaskTest.java`(신규), `ImplementationPromptsTest.java`·`WorkerMainLoopImplementationModeTest.java`(추가)

**Interfaces:**
- Consumes: Task 6 `WorktreeService.createRefWorktree`·`refChangeWarning`·`discardRefWorktree`·`RefWorktree`. Task 5 `WorkerTaskResponse.refRepos()`(`List<TaskRefRepo>`, null 없음), `WorkerResultRequest` 정규 생성자(`…, envTemplate, Integer implementationAttempt, List<String> refWarnings`). 테스트 픽스처는 Task 1 `Task.setRefRepos(List<RefRepo>)` + Task 5 `forImplementation`.
- Produces:
  - `RefRepoPrompts`(final, 패키지 전용)
    - `static String promptSection(List<WorktreeService.RefWorktree> refs)` — 비면 `""`. 아니면 `"\n\n"` + 스펙 §6.4 코드 블록 그대로(참조 줄 `- {별칭} · {브랜치}@{sha7} : {dir.getAbsolutePath()}`, 끝 `\n`).
    - `static String planFileHeader(List<WorktreeService.RefWorktree> refs)` — 비면 `""`, 아니면 같은 블록(앞 `\n\n` 없이) + `"\n"`.
    - `static String prSection(List<WorkerTaskResponse.TaskRefRepo> refs, List<String> warnings)` — 비면 `""`, 아니면 §6.6 블록 + 빈 줄. 경고 줄은 있을 때만, 경고는 `", "`로 잇는다.
    - `static String failureSuffix(List<String> warnings)` — 비면 `""`, 아니면 `" — 참조 repo 변경 감지: " + join(", ")`.
    - `static String prepFailureReason(WorkerTaskResponse.TaskRefRepo ref, Throwable e)` — `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}`. 사유는 `GitRemotes.mask`한 메시지의 마지막 `fatal:`/`error:` 줄(앞뒤 공백 제거), 없으면 첫 줄, 300자 상한.
    - `static String sha7(String sha)`
  - `ImplementationPrompts.SDD_REF_LINE = "- 서브에이전트(구현·리뷰)에게 일을 맡길 때 계획 파일 머리의 \"참조 repo\" 절을 그대로 함께 넘긴다.\n"`, `static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha, boolean designWritten, boolean refsInPlanFile)` — 4인자는 `false`로 위임(`ImplementationPromptsTest` 그대로).
  - `WorkerMainLoop`: `static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha, String modeLine, String reviewNotes, List<String> refWarnings)`(5인자는 `List.of()`로 위임), `static List<WorkerTaskResponse.TaskRefRepo> refsOf(WorkerTaskResponse task)`(null → 빈 목록), 패키지 필드 `LongSupplier nanoClock = System::nanoTime`(테스트 시계 — 생성자는 그대로).
  - `WorkerHttpClient.SUPPORTS = "refRepos"`; `nextTask()` 시그니처 그대로(`WorkerMainLoopStartupTest` 목 유지), 요청 `/worker/next-task?workerId={id}&supports=refRepos`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// RefRepoPromptsTest
@Test void prompt_section_is_the_spec_block_after_two_newlines()   // 참조 1개 → §6.4 텍스트 블록과 정확히 같음, 빈 목록 → ""
@Test void plan_header_adds_no_task_headings()   // countTaskHeadings(header + "### Task 1: A\n### Task 2: B") == 2, header.startsWith("## 참조 repo (읽기 전용)\n")
@Test void pr_section_shows_pins_and_warnings_only_when_present()   // 경고 없음·있음 두 모양 정확 비교, 빈 refs → ""
@Test void prep_failure_reason_prefers_the_last_fatal_line_and_masks()
    // "git failed (128): Cloning…\nfatal: could not read Username for 'https://oauth2:glpat-X@g.example/p.git': terminal prompts disabled"
    // → "참조 repo 준비 실패: 옛앱(release/6.5) — fatal: could not read Username for 'https://***@…'…", "glpat-X" 없음; fatal 없으면 첫 줄; 300자 상한
@Test void failure_suffix_joins_warnings()   // [] → "", ["옛앱: 파일 2개","둘째: HEAD가 고정 커밋과 다름"] → " — 참조 repo 변경 감지: 옛앱: 파일 2개, 둘째: HEAD가 고정 커밋과 다름"

// ImplementationPromptsTest (추가)
@Test void sdd_section_carries_the_ref_line_only_with_refs()   // planSection(task(PLAN), SDD, "abc1234", false, true) contains SDD_REF_LINE; 4인자·REVIEW·DIRECT엔 없음

// WorkerMainLoopImplementationModeTest (추가)
@Test void the_pr_body_puts_the_ref_section_after_the_header_and_inside_the_cap()
    // 참조 1개 + 경고 ["옛앱: 파일 2개"] + 분석 100,000자 → length ≤ 60,000,
    // "- **소요시간**: 12초\n\n## 참조 repo (읽기 전용)\n- 옛앱 · release/6.5@aaaaaaa\n> ⚠ 참조 repo 변경 감지(이 PR에는 포함 안 됨): 옛앱: 파일 2개\n\n## 사전 분석\n" 포함

// WorkerHttpClientNextTaskTest (HttpServer, WorkerHttpClientArtifactTest 방식, 204 응답)
@Test void next_task_tells_the_api_it_supports_ref_repos()   // "GET /worker/next-task?workerId=w1&supports=refRepos", 결과 Optional.empty()

// WorkerMainLoopRefReposTest — 목 구성은 WorkerMainLoopImplementationModeTest와 같다. 참조 12 "옛앱"(release/6.5, sha a*40)·13 "둘째"(release/9, sha b*40),
// createRefWorktree 응답 rw12·rw13(dir = tmp/refs/12·13)
@Test void refs_are_prepared_in_order_between_the_target_fetch_and_the_target_worktree()
    // InOrder: ensureFresh → createRefWorktree(7, ref12) → createRefWorktree(7, ref13) → create(…6인자) → claude.exec → reportTerminal
    //          → discard("acme/widgets", TASK, 7) → discardRefWorktree(7, ref12) → discardRefWorktree(7, ref13)
    // 프롬프트 endsWith(RefRepoPrompts.promptSection(List.of(rw12, rw13))), PR 본문에 참조 절, reported().refWarnings() == null
    // ExecOptions.extraArgs에 "--add-dir"도 참조 경로도 없다(스펙 §1.2·§6.7 — DIRECT·REVIEW 모두)
@Test void a_ref_prep_failure_reports_before_claude_and_cleans_up()
    // ref13 → IOException("git failed (128): …\nfatal: Remote branch release/9 not found in upstream origin")
    // → IMPLEMENTATION_FAILED, failureReason == "참조 repo 준비 실패: 둘째(release/9) — fatal: Remote branch release/9 not found in upstream origin",
    //   implementationAttempt 유지, never create(6인자), verifyNoInteractions(claude), discardRefWorktree(7, ref12)·(7, ref13), never discard(anyString(), any(), anyLong())
@Test void ref_prep_time_comes_out_of_the_claude_limit()   // REVIEW 80분, createRefWorktree 응답이 nanoClock을 10분 진행 → exec timeout == 70분
@Test void no_time_left_fails_as_a_timeout_without_a_target_worktree()   // 90분 진행 → failureReason이 "시간 초과"로 시작, claude·create 없음, 참조 정리
@Test void the_plan_file_starts_with_the_ref_section_and_keeps_its_task_count()
    // SDD + 플러그인, 계획 "### 작업 1: A\n### 작업 2: B" → exec 시점 계획 파일이 "## 참조 repo (읽기 전용)\n"로 시작,
    // countTaskHeadings(파일) == 2, extraArgs == ["--plugin-dir", …](SDD 유지), 프롬프트에 SDD_REF_LINE
@Test void ref_changes_go_to_the_pr_body_and_the_result()   // refChangeWarning(rw12) → "옛앱: 파일 3개" → PR 본문 경고 줄, refWarnings == ["옛앱: 파일 3개"]
@Test void a_failed_run_still_checks_refs_and_appends_the_warning()
    // claude exit 1 + refChangeWarning(rw13) → "둘째: HEAD가 고정 커밋과 다름" → failureReason.endsWith(" — 참조 repo 변경 감지: 둘째: HEAD가 고정 커밋과 다름"), 참조 정리
@Test void refs_are_discarded_when_an_unexpected_exception_escapes()   // ensureWorkerExcludes 예외 → "처리 중 예외" 보고 + discardRefWorktree 두 번
@ParameterizedTest @EnumSource(ImplementationMode.class)   // DIRECT·REVIEW·SDD(플러그인 있음·계획 있음)
void a_task_without_refs_never_touches_the_ref_methods(ImplementationMode mode)
    // 참조 없는 claim → never createRefWorktree·refChangeWarning·discardRefWorktree, refWarnings null, PR 본문에 "## 참조 repo" 없음,
    // (a) 프롬프트에 ImplementationPrompts.SDD_REF_LINE 없음
    // (b) exec 시점 계획 파일 내용 == planText(SDD면 normalizeTaskHeadings 결과)와 바이트 같음
    // (c) 프롬프트 == renderImplementationPrompt(… ImplementationPrompts.planSection 4인자 …) + DbMcpWorkerSupport.promptSection(task.dbMcp())
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.RefRepoPromptsTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopRefReposTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerHttpClientNextTaskTest" --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationPromptsTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopImplementationModeTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - `processImplementation` 순서:
    1. 대상 `ensureFresh` — 지금 그대로.
    2. `refs = refsOf(task)`. 비었으면 `ready = List.of()`, 준비 시간 0, 새 메서드를 부르지 않는다.
    3. 있으면 입력 순서대로 `attempted`에 넣고 `worktrees.createRefWorktree(task.id(), ref)`. 참조마다 걸린 시간을 로그로 남긴다(`참조 repo 준비: task={} {}({})@{} {}ms`). 예외가 나면 `attempted` 전부 `discardRefWorktree` → `safePostImplementationFailure(id, attempt, RefRepoPrompts.prepFailureReason(ref, e), null, null, null)` → return. 대상 worktree는 만들지 않는다.
    4. 준비 시간 = `nanoClock` 차이. `budget = implementationTimeoutOf(task, props.implementationTimeout()).minus(준비 시간)`. 0 이하이면 참조를 정리하고 `시간 초과: 참조 repo 준비에 {초}초를 써 구현 상한({분}분)이 남지 않았습니다`로 실패 보고.
    5. 기존 2~7단계(`:255-398`) 전체를 `try { … } finally { attempted마다 discardRefWorktree }`로 감싼다. 모든 return·예외 경로가 정리되고, 바깥 `pollAndProcess` catch보다 먼저 돈다. 대상 `discard`는 지금 자리 그대로.
  - 계획 scratch(`:290`): 파일 내용 = `RefRepoPrompts.planFileHeader(ready) + planText`. `countTaskHeadings`에는 지금처럼 `planText`만 넘긴다.
  - 프롬프트(`:315-318`): `… + DbMcpWorkerSupport.promptSection(task.dbMcp()) + RefRepoPrompts.promptSection(ready)`. `planSection(…, designWritten, !ready.isEmpty())`. `fillImplementationPrompt`는 그대로라 `endsWith(DEPLOY_ENV_INSTRUCTION)` 테스트가 유지된다.
  - claude 상한(`:324`): `budget`.
  - claude가 끝나면(예외·비정상 종료·성공 모두) 경고를 한 번 모은다: `ready`마다 `worktrees.refChangeWarning(rw)`의 값. 이후 모든 실패 사유(claude 예외·exit≠0·commit/push·PR) 맨 끝(`fallbackNote` 뒤)에 `RefRepoPrompts.failureSuffix(warnings)`를 붙인다.
  - `renderPrBody`: 머리 다음·`## 사전 분석` 앞에 `prSection(refsOf(task), refWarnings)`. `room`에서 그 길이를 뺀다. 참조가 없으면 `""`라 본문은 바이트 그대로.
  - 성공 보고(`:385-394`): 정규 생성자로 `implementationAttempt = attempt`, `refWarnings = warnings.isEmpty() ? null : List.copyOf(warnings)`. 실패 보고에는 `refWarnings`를 싣지 않는다(사유에 붙는다). 원소 자르기는 하지 않는다 — 전송 직전 `ResultReporter`의 `masked()`가 가리고 자른다.
  - `sddPlanSection`: `refsInPlanFile`이면 마지막 줄 뒤에 `SDD_REF_LINE`.
  - `nextTask()`: `.queryParam("supports", SUPPORTS)`.
- [ ] **Step 4: 통과 확인**
  - Run: Step 2 명령 + `--tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDeployEnvTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopStartupTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest"`
  - Expected: PASS. 기존 테스트는 참조가 없는 claim(23·30인자 생성자 → Task 5가 `List.of()`로 정규화)이라 새 메서드를 부르지 않는다. PR 본문 상한 테스트의 `overhead`도 그대로다.
- [ ] **Step 5: 커밋** — `feat(worker): 구현에 참조 repo 연결(준비·프롬프트·변경 검사·PR 본문·정리)`

### Task 8: worktree 정리 잡 — `_refs/task-{id}/{catalogId}` 판정·삭제

**Files:**
- Modify: `…/workerdaemon/WorktreeCleanupJob.java` — `:154-169` `cleanUnder`(대상 스캔에서 `_refs` 제외 + `_refs` 스캔), `:171-194` 옆에 `cleanRef`, 클래스 주석 `:31-48`.
- Modify: `…/workerdaemon/WorktreeCleanupPlanner.java` — `:52` 뒤에 `refSourceKey`.
- Test: `…/workerdaemon/WorktreeCleanupJobTest.java`(추가 — `setUp`의 지역 `ref`를 필드로), `WorktreeCleanupPlannerTest.java`(추가)

**Interfaces:**
- Consumes: Task 6 `WorktreeService.REFS_DIR`·`createRefWorktree`(테스트)·`discardRefUnderLock(Path, Path)`, 기존 `GitRepoCache.tryWithRepoLock`·`WorktreeKind.parse`·`WorktreeCleanupPlanner.judge`·`lastActivity`.
- Produces:
  - `static Optional<String> WorktreeCleanupPlanner.refSourceKey(Path refDir, Path reposRootReal)` — 참조 폴더의 `.git` 파일(gitdir)로 찾은 원본 캐시의 localKey. 못 믿으면 empty.
  - `WorktreeCleanupJob.Result.outcomes`에 참조 폴더도 같은 `Outcome`으로 들어간다. 생성자·요약 로그 문구는 그대로.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// WorktreeCleanupPlannerTest (추가, @TempDir)
@Test void ref_source_key_follows_the_gitdir_into_the_repos_dir()
    // .git = "gitdir: {repos}/acme/widgets/.git/worktrees/12" → "acme/widgets"; 상대 경로 gitdir도 같음
@Test void ref_source_key_is_empty_when_it_cannot_be_trusted()
    // .git 없음 / 내용 "garbage" / gitdir이 repos 밖 / 캐시에 .git 디렉터리 없음 / .git/worktrees 아래가 아님 / 3단계 경로 / reposRootReal null → empty

// WorktreeCleanupJobTest (추가). 참조 worktree = worktrees.createRefWorktree(id,
//   new WorkerTaskResponse.TaskRefRepo(12L, "옛앱", "acme/widgets", ref.gitUrl(), "github", "main", headSha)); 나이는 기존 age()
//   repoHost "github"이라 repoRefOf가 기존 setUp 캐시 키 `acme/widgets`(= key)를 쓴다(작업 6 Interfaces) — BUSY·worktree list·HEAD 검사는 이 키의 캐시에서
@Test void removes_old_ref_worktrees_through_their_source_cache()
    // task-5 참조 30일 → REMOVED, 폴더·_refs/task-5 없음, 캐시 worktree list에 없음, tmp/repos/_refs 없음(가짜 락 키 없음)
@Test void keeps_ref_worktrees_of_active_or_recent_tasks()  // task-6 진행 중 30일 → ACTIVE, task-7 3일 → TOO_NEW, 둘 다 남음
@Test void a_busy_source_cache_skips_the_ref_this_round()   // 다른 스레드가 withRepoLock(key) → BUSY, 남음
@Test void deletes_only_the_folder_when_the_source_cache_cannot_be_found()
    // _refs/task-9/3(.git 없음)·/4(.git = "gitdir: " + tmp/nowhere/.git/worktrees/4)·/5(.git = "garbage") 30일 → 모두 REMOVED, 폴더 없음,
    // tmp/repos 아래 *.lock은 acme/widgets.lock뿐, tmp/repos/_refs 없음
@Test void keeps_cacheless_ref_folders_of_active_or_recent_tasks()   // 같은 모양, 진행 중 → ACTIVE, 3일 → TOO_NEW
@Test void ignores_unrecognized_names_under_refs()
    // _refs/notes, _refs/task-01/1, _refs/design-5/1, _refs/task-5/x (30일) → outcomes에 없음, 모두 남음
@Test void the_target_scan_does_not_enter_refs()
    // 대상 task-1(8일)과 참조 _refs/task-1/12(8일)가 함께 있음 → 두 경로 모두 REMOVED, 대상 결과 키는 worktreeRoot/acme/widgets/task-1 그대로
```

- [ ] **Step 2: 실패 확인**
  - Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPlannerTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupJobTest"`
  - Expected: 컴파일 실패(`refSourceKey` 없음)
- [ ] **Step 3: 구현**
  - `cleanUnder`: root 바로 아래 `a`가 `_refs`(`WorktreeService.REFS_DIR`)면 대상 스캔에서 건너뛴다. 대상 스캔 뒤 `root/_refs`가 plain dir이면 `_refs` 스캔.
  - `_refs` 스캔: 바로 아래 plain dir 중 `WorktreeKind.parse(name)`이 `TASK`인 것(`task-{id}`)만, 그 아래 plain dir 중 이름이 `[1-9][0-9]{0,17}`(카탈로그 id)인 것만 후보. 링크·정션은 기존 `plainDirs`가 거른다. 하나를 못 읽으면 기존 `plainDirsOrSkip`처럼 그 하위만 건너뛴다.
  - 판정은 대상과 같다: `judge(taskId, active, lastActivity(refDir), now, retentionDays)`. REMOVE가 아니면 그 결과(ACTIVE·TOO_NEW·UNKNOWN).
  - REMOVE면 `reposRootReal`(= `props.reposDir()`의 실경로, 없거나 비었으면 null)로 `refSourceKey`:
    - 있으면 `repos.tryWithRepoLock(key, () -> { 재판정; worktrees.discardRefUnderLock(refDir, reposRootReal.resolve(key)) })` → REMOVED·ABSENT는 REMOVED, GIT_LOCKED, 락을 못 잡으면 BUSY.
    - 없으면 락·git 없이 `worktrees.discardRefUnderLock(refDir, null)` → REMOVED. 로그 `참조 worktree 원본 캐시를 찾지 못해 폴더만 지움: {}`. 캐시 쪽 메타는 다음 `worktree prune`·캐시 정리가 처리한다.
    - 예외 → FAILED(경고 로그).
  - `task-{id}` 아래 처리가 끝나면 비었을 때만 지운다(`Files.deleteIfExists`, 비어 있지 않음·실패는 무시).
  - `tryWithRepoLock`에는 `refSourceKey`가 준 실제 localKey만 넘긴다 — `_refs/…` 같은 키로 락을 잡지 않는다(`{repos-dir}/_refs/….lock`이 생긴다).
  - `refSourceKey` 규칙:
    1. `refDir/.git`이 링크가 아닌 일반 파일이고 첫 줄이 `gitdir:`로 시작한다.
    2. 경로가 상대면 `refDir` 기준으로 풀고 정규화한다.
    3. 부모 이름이 `worktrees`, 그 부모 이름이 `.git`이면 그 위가 캐시다.
    4. 캐시 실경로가 `reposRootReal` 안이고, 상대 경로가 정확히 2단계이며, `캐시/.git`이 링크가 아닌 디렉터리여야 한다.
    5. 두 이름을 `/`로 이어 돌려준다. IO 오류·형식 오류는 모두 empty.
- [ ] **Step 4: 통과 확인**
  - Run: Step 2 명령 + `--tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPropertiesTest" --tests "com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJobTest"`
  - Expected: PASS. 기존 정리 테스트는 `_refs`가 없어 결과가 같다.
- [ ] **Step 5: 커밋** — `feat(worker): worktree 정리 잡이 남은 참조 폴더(_refs/task-N)를 지움`
### Task 9: 인터뷰 서비스 — 작업 인터뷰 참조 준비·프롬프트·보고, 참조당 5분 상한

**Files:**
- Modify: `netismaker-interview-service/src/types.ts:59-74`(`RefRepoClaim` 주석: "질문 세션의" → "질문·작업 인터뷰의"), `:149-153`(`refRepos` 주석 "QUESTION만" 고침) 다음에 `refsCleared?: boolean`, `:207-219` `WorkerPlanRequest`에 `refStates?: RefState[]`
- Modify: `netismaker-interview-service/src/runner/repoPrepare.ts:225`(상수 추가 자리), `:250-304`(`ensureRefRepos`·`prepareRefRepo`)
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts:24-59`(`RunnerDeps`), `:100-122`(`promptFor`), `:200-236`(`refSection`·`claimRefRepos`), `:395-611`(`runTurn`), `:617-687`(`runQuestionTurn`)
- Modify: `netismaker-interview-service/src/config.ts:14-15·77`, `src/index.ts:27`, `.env.example:24` 다음(`INTERVIEW_REF_TIMEOUT_MS=300000` + 주석: 참조 하나당 받기 상한, 넘으면 그 참조만 ⚠)
- Modify: `netismaker-interview-service/test/fixtures/claims.ts` — `interviewClaimWithRefs = { ...freshClaim, kind: 'INTERVIEW', refRepos: [{ catalogId: 12, alias: '옛앱', githubRepo: 'acme/legacy', gitUrl: 'https://github.com/acme/legacy.git', repoHost: 'github', branch: 'release/6.5', dir: '/r/_refs/42/12', sha: null }] }`
- Test: `test/repoPrepare.test.ts`, `test/interviewRunner.test.ts`, `test/config.test.ts`
- 바꾸지 않음: `api/javaClient.ts`(바디를 그대로 직렬화), `sdk/permissions.ts`·`sdk/sessionOptions.ts`(INTERVIEW에는 `refRoots`를 넘기지 않는다 — Read/Grep/Glob 사전 승인, "INTERVIEW 그대로" 테스트 유지)

**Interfaces:**
- Consumes(Task 4): INTERVIEW claim JSON의 `refRepos`(`ClaimRefRepo`, dir = `{interview work-root}/_refs/{세션 id}/{카탈로그 id}` — 기존 `isRefDir` 검사 그대로 통과)·`refsCleared`(boolean), `POST …/plan`의 `refStates`.
- Produces:
  - `types.ts`: `InterviewClaimResponse.refsCleared?: boolean`(구버전 API는 필드 없음 → `=== true`로만 판정), `WorkerPlanRequest.refStates?: RefState[]`
  - `repoPrepare.ts`: `ensureRefRepos(refs: RefRepoClaim[], opts: { tokens?: GitTokens; signal?: AbortSignal; timeoutMs?: number }, ops?: RefRepoOps): Promise<RefPrepared[]>` · `export const DEFAULT_REF_TIMEOUT_MS = 300_000` · `export function refTimeoutReason(timeoutMs: number): string` → `참조 repo 받기 시간 초과(${분}분)`(분 = `Math.max(1, Math.round(ms / 60_000))`, 기본값이면 고정 문구 `참조 repo 받기 시간 초과(5분)`)
  - `interviewRunner.ts`: `RunnerDeps.refTimeoutMs?: number`, `RunnerDeps.ensureRefRepos`의 opts에 `timeoutMs?: number` · `export function interviewRefSection(prepared: RefPrepared[]): string` · `export const REFS_CLEARED_LINE` · `export function interviewRefPrefix(prepared: RefPrepared[], refsCleared: boolean): string`(prepared가 있으면 `interviewRefSection`, 없고 `refsCleared`면 `REFS_CLEARED_LINE`, 그 밖에 `''`)
  - `config.ts`: `Config.refTimeoutMs: number` ← `posNum(env, 'INTERVIEW_REF_TIMEOUT_MS', 300_000)`

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// repoPrepare.test.ts — describe('per-reference time limit'), vi.useFakeTimers(); 느린 clone은 받은 signal이 abort되면 AbortError로 거부(execFile처럼)
it('fails only the slow reference with the timeout reason, removes its .part, and goes on with the next')
  // ensureRefRepos([ref12, ref13], { timeoutMs: 300_000 }, ops); advanceTimersByTimeAsync(300_000)
  // → [{ ref: ref12, sha: null, error: '참조 repo 받기 시간 초과(5분)' }, { ref: ref13, sha: SHA_A, error: null }], rm('/r/_refs/5/12.part')
it('restarts the limit for each reference')            // 각 200초 걸리는 참조 두 개 → 둘 다 성공
it('a turn abort is not reported as a timeout')        // 턴 signal abort → error는 abort 사유, 시간 초과 문구 아님
it('leaves no timer behind')                           // 끝난 뒤 vi.getTimerCount() === 0 (성공·실패·시간 초과 모두)
it('a reference cut by the time limit is fetched cleanly on the next turn')   // Review Focus 3
  // 첫 호출: 시간 초과(.part 남은 것처럼 exists('/r/_refs/5/12.part') true) → 둘째 호출: rm(dir)·rm(part) 뒤 clone → sha, error null
it('formats the minutes from timeoutMs')               // refTimeoutReason(600_000) === '참조 repo 받기 시간 초과(10분)'
// 기존 'passes the abort signal to every git call'(:490)은 timeoutMs 없이 부르므로 signal 동일성 그대로 통과해야 한다
// :964 wire 계약 컴파일 검사에 `refsCleared: false`, `WorkerPlanRequest['refStates']`를 더한다

// interviewRunner.test.ts — 기존 describe('참조 repo …') 안에 describe('작업 인터뷰 (스펙 2026-10-24 §5)') (capturing·preparer·runnerWith 재사용)
it('INTERVIEW claim with refs: ensureRepo → ensureRefRepos → createDbMcp → SDK, with tokens, the turn signal and timeoutMs')
  // toHaveBeenCalledWith(interviewClaimWithRefs.refRepos, { tokens, signal: expect.any(AbortSignal), timeoutMs: 300_000 })
it('kickoff: INTERVIEW ref section → DB section → existing kickoff')
  // prompt === interviewRefSection(prepared) + (refRepos만 뺀 같은 claim·같은 DB의 kickoff 프롬프트 = dbSection + 기존 kickoff)
  // 질문용 규칙 줄('Read/Grep/Glob의 경로로 주어') 없음
it('resume: ref section + answer only — no DB section even with a DB attached')
  // prompt === interviewRefSection(prepared) + resumeClaim.lastAnswer
it('no refs: kickoff/resume prompts and question/plan bodies are byte-identical')
  // 먼저(promptFor를 고치기 전, a5a36d1 코드로) freshClaim kickoff 출력을 KICKOFF_GOLDEN 리터럴로 테스트에 고정한다.
  // 필드 없음 · refRepos: [] · refsCleared: false 세 claim × (DB 없음 → toBe(KICKOFF_GOLDEN), DB 있음 → toBe(dbSection(db) + KICKOFF_GOLDEN)),
  // resume은 DB 유무와 상관없이 toBe(lastAnswer). ensureRefRepos 미호출, postQuestion·postPlan 바디에 'refStates' 키 없음(JSON 동일)
it('refsCleared: one line instead of the section on every turn, nothing prepared or reported')
  // resume: prompt === REFS_CLEARED_LINE + lastAnswer; fresh+DB: REFS_CLEARED_LINE + dbSection + kickoff
it('failed or timed-out reference: the turn goes on, unavailable line in the prompt, error in refStates, no fail')
it('postPlan carries refStates when references were prepared')   // planCompleteStream → postPlan 바디 refStates
it('force-finish reformat and handoff splice prompts carry no ref section, but the report still has refStates')
it('a preparer that throws (contract breach) does not fail the turn')  // 모든 참조가 { sha: null, error } — fail 미호출
it('a turn aborted while preparing references stops there for INTERVIEW too')  // SDK·postPlan·postQuestion 미호출
it('per-reference limit comes from deps.refTimeoutMs; NaN/0 fall back to 300_000 (QUESTION too)')
// describe('interviewRefSection'): 사용 가능만 / 실패만(머리말·`- …` 줄 없음) / 섞임 — 스펙 §5.2 코드 블록 바이트 그대로, 끝 '\n\n'
// REFS_CLEARED_LINE === '참조 repo: 없음(대화 중 모두 뺐다) — 앞서 본 참조는 더 쓰지 않는다. 계획은 대상 repo만으로 세운다.\n\n'

// config.test.ts
it('defaults refTimeoutMs=5min, honors INTERVIEW_REF_TIMEOUT_MS, falls back on 30m/0/-5 with a warning')
```

- 깨지는 기존 테스트(같은 커밋에서 고침): `interviewRunner.test.ts:852`(opts에 `timeoutMs: 300_000` 추가), `:924-941` "interview (non-question) claims ignore refRepos"(→ kind 없음·INTERVIEW 모두 참조를 준비하고 `refStates`를 보고하는 테스트로 뒤집음), `:1043`(인터뷰 claim의 "환경 준비" detail → `'acme/widgets + 참조 1'`). 질문용 `refSection` 테스트(:1117-1217)는 한 글자도 바꾸지 않는다.

- [ ] **Step 2: 실패 확인**
  - Run: `cd netismaker-interview-service && npx vitest run test/repoPrepare.test.ts test/interviewRunner.test.ts test/config.test.ts`
  - Expected: FAIL(`interviewRefSection`·`refTimeoutReason` 없음, 인터뷰 claim에서 `ensureRefRepos` 미호출)
- [ ] **Step 3: 구현**
  - `ensureRefRepos`: `timeoutMs`가 없으면 지금과 같다(턴 signal을 그대로 넘김). 있으면 참조마다 자체 `AbortController`를 만들어 턴 signal에 묶고(engines `>=20`이라 20.3+의 `AbortSignal.any`에 기대지 않는다) `timeoutMs` 타이머를 건다. 그 signal을 git 호출·`renameWithRetry`에 넘긴다. 실패 처리는 기존 catch(`.part` 삭제)를 그대로 타고, 타이머가 끊었고 턴 signal은 살아 있을 때만 사유를 `refTimeoutReason(timeoutMs)`로 바꾼다. 참조 하나가 끝나면 타이머 해제·리스너 제거.
  - `refSection`은 내부 `buildRefSection(prepared, rules)`로 옮기고 질문 규칙 배열을 넘긴다(출력 바이트 그대로). `interviewRefSection`은 같은 함수에 스펙 §5.2 INTERVIEW 규칙 5줄을 넘긴다.
  - `claimRefRepos`: `kind === 'QUESTION'` 조건을 뺀다(보낼지는 API `refsFor`가 정함) — 주석도 고침.
  - private `prepareRefs(claim, controller): Promise<RefPrepared[]>`(공통 도우미): 참조가 없으면 `[]`. 있으면 `ensureRefRepos(refs, { tokens, signal, timeoutMs })` → `throwIfAborted()` → `logRefFailures`. 던지면 abort는 다시 던지고, 그 밖에는 모든 참조를 `{ sha: null, error: maskSecrets(메시지) || REF_PREPARE_FALLBACK_REASON }`로 바꾼다(§1.2). `timeoutMs`는 `turnTimeoutMs`처럼 유한 양수만 받고 아니면 `DEFAULT_REF_TIMEOUT_MS`.
  - `runTurn`: `ensureRepo` → `throwIfAborted`(:438) 바로 다음에 `prepareRefs`를 한 번 부르고, 질문이면 `runQuestionTurn(claim, controller, meter, poster, refs)`로 넘긴다(:628-636의 준비 코드는 지운다). INTERVIEW는 이어서 DB MCP(:448).
  - `promptFor(claim, prefixes: { everyTurn?: string; kickoffOnly?: string } = {})`: fresh = `everyTurn + kickoffOnly + 기존 kickoff`, resume = `everyTurn + (lastAnswer ?? '')`. 호출(:471)은 `{ everyTurn: interviewRefPrefix(refs, claim.refsCleared === true), kickoffOnly: dbSection(db) }`. 강제 마무리(:470)·handoff(:495)·near-miss(:543) splice에는 붙이지 않는다.
  - INTERVIEW `postPlan`(:581)·`postQuestion`(:595) 바디 끝에 `...(refs.length > 0 ? { refStates: refStatesOf(refs) } : {})`. `buildOptions`에는 `refRoots`를 넘기지 않는다.
  - `config.ts`·`index.ts`(`refTimeoutMs: cfg.refTimeoutMs`)·`.env.example` 반영.
- [ ] **Step 4: 통과 확인**
  - Run: `cd netismaker-interview-service && npx vitest run && npx tsc -p tsconfig.json --noEmit`
  - Expected: PASS(전체 — `permissions.test.ts`·`sessionOptions.test.ts`의 INTERVIEW 테스트, 질문 참조 테스트 모두 그대로)
- [ ] **Step 5: 커밋** — `feat(interview-service): 작업 인터뷰 참조 repo 준비·프롬프트·보고, 참조당 받기 5분 상한`
### Task 10: 프론트 — 칩 맥락 문구·작업 등록 창 참조 줄

**Files:**
- Modify: `frontend/composables/refRepos.ts` (파일 끝 84줄 뒤: `RefChipContext`·`refChipCopy`)
- Modify: `frontend/components/RefRepoChips.vue:20-31`(props), `:40-44`(`pinnedTip`), `:84-93`(대기 안내·실패 툴팁 마지막 줄)
- Modify: `frontend/pages/tasks/index.vue:1-11`(import), `:210-215`(`draft`), `:247-262`(`onRepoSelected`), `:336-345`(`openCreate`), `:356-362`(`submit` meta), `:660-686`(템플릿 — 브랜치 선택과 제목 사이)
- Modify: `frontend/test/setup.ts:35-53`(전역 `useRoute` 기본값)
- Test: `frontend/composables/refRepos.spec.ts`, `frontend/test/ref-repo-chips.spec.ts`, `frontend/test/tasks-form-refs.spec.ts`(신규 — `tasks-form-repo-select.spec.ts`의 `PageWrapper`·mount 방식)

**Interfaces:**
- Consumes: 1단계 `RefRepoView`·`toInputs`·`RefRepoChips`·`RefRepoAddForm`(그대로), 등록 바디 `refRepos?`(Task 2).
- Produces (`composables/refRepos.ts`):
  - `type RefChipContext = 'question' | 'task-setup' | 'task-interview' | 'task-fixed'`
  - `refChipCopy(context: RefChipContext): { pendingNote: string | null; pinnedBasis: string; failedNote: string | null }`
- Produces: `RefRepoChips` props `+ context?: RefChipContext`(기본 `'question'` — 1단계 문구·테스트 그대로).
- Produces (`pages/tasks/index.vue`): `draft.refRepos: RefRepoView[]`, `refAddOpen`, `removeRef(catalogId: number | null)`, 브랜치 셀렉트 `data-test="branch-select"`, 칩 줄 래퍼 `data-test="task-form-refs"`.
- Produces (`test/setup.ts`): 전역 `useRoute: () => ({ path: '/', params: {}, query: {} })`. 파일마다 모듈 단계에서 `useRoute`를 넣는 spec(`task-detail-*`, `questions-*`, `login-page`, `layout-default`, `hydration-auth-gate` 등)은 그대로 덮어쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// refRepos.spec.ts
describe('refChipCopy', () => {
  it('question(기본)은 1단계 문구 그대로', () => {
    expect(refChipCopy('question')).toEqual({ pendingNote: '다음 답변 때 받음', pinnedBasis: '대화 끝까지 이 커밋 기준',
      failedNote: '이 참조 없이 답했습니다 — 다음 질문을 보낼 때 다시 받아 봅니다' })
  })
  it('task-setup: 대기 칩 "인터뷰 때 받음", 실패 줄 없음')        // { '인터뷰 때 받음', '구현까지 이 커밋 기준', null }
  it('task-interview: 다음 답변 때 받음 · 구현까지 · 진행 문구')     // failedNote '이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다'
  it('task-fixed: 대기 안내·실패 줄 없음')                          // { null, '구현까지 이 커밋 기준', null }
})
// ref-repo-chips.spec.ts — tooltipOf(item, context?)로 넓힌다(기존 호출은 인자 하나 그대로)
it('task-setup 대기 칩 안내는 "인터뷰 때 받음"이고 "다음 답변"은 없다')
it('task-interview 고정 칩 툴팁은 "— 구현까지 이 커밋 기준"으로 끝난다')
it('task-interview 실패 칩 툴팁 마지막 줄은 "이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다"')
it('task-fixed 대기 칩에는 안내가 없다')                          // [data-state="pending"] 안 .ref-chip-note 없음
// tasks-form-refs.spec.ts
it('참조를 고르면 등록 바디(JSON)에 refRepos가 실린다', async () => {
  // vm.draft 4필드 + vm.draft.refRepos.push({ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }) → vm.submit()
  // body toEqual { repoCatalogId: 1, githubBranch: 'main', title, description, refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }] }
})
it('파일을 함께 보내도 meta JSON에 refRepos가 실린다')
it('골랐다가 모두 빼면 바디에 refRepos 키가 없다')                // not.toHaveProperty('refRepos')
it('대상을 참조 중 하나로 바꾸면 그 참조만 빠진다')                // refRepos [2, 3] → vm.onRepoSelected(2) → catalogId [3]
it('칩 줄은 브랜치 선택 바로 아래(제목 위)에 있고 대기 칩은 "인터뷰 때 받음"')
it('＋ 참조 추가는 인라인 폼을 펼치고(후보에서 대상·고른 것 제외), 추가하면 칩이 생기고 접힌다')
it('창을 닫았다 다시 열면 참조가 비워진다')
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd frontend && npx vitest run composables/refRepos.spec.ts test/ref-repo-chips.spec.ts test/tasks-form-refs.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - 문구 표(스펙 §7.1 — 표에서 '—'인 칸은 그릴 일이 없으므로 `null`, 그 줄을 그리지 않는다):

    | context | pendingNote | pinnedBasis | failedNote |
    |---|---|---|---|
    | question | 다음 답변 때 받음 | 대화 끝까지 이 커밋 기준 | 이 참조 없이 답했습니다 — 다음 질문을 보낼 때 다시 받아 봅니다 |
    | task-setup | 인터뷰 때 받음 | 구현까지 이 커밋 기준 | null |
    | task-interview | 다음 답변 때 받음 | 구현까지 이 커밋 기준 | 이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다 |
    | task-fixed | null | 구현까지 이 커밋 기준 | null |
  - `RefRepoChips`: `copy = computed(() => refChipCopy(props.context))`. `pinnedTip` = `고정 커밋 {7자}[ · {받은 시각}] — {pinnedBasis}`. 대기 안내 `<span v-if="copy.pendingNote">`, 실패 툴팁 마지막 줄 `v-if="copy.failedNote"`. 타입 import는 지금처럼 상대 경로.
  - `tasks/index.vue`: 새 질문 화면(`pages/questions/index.vue:165-182`)과 같은 모양으로 `refAddOpen`·`refExcludeIds`(대상 + 고른 것)·`toggleRefAdd`·`addRef`(push 후 접기)·`removeRef`를 둔다. `onRepoSelected` 첫머리에서 `removeRef(catalogId)`. `openCreate`는 `draft.refRepos = []`, `refAddOpen = false`.
  - `submit` meta: `...(draft.refRepos.length > 0 ? { refRepos: toInputs(draft.refRepos) } : {})` — 정확-바디 테스트 `tasks-form-repo-select.spec.ts:84-111`·`tasks-form-attachments.spec.ts:110-115`는 수정 없이 통과해야 한다.
  - 템플릿: 브랜치 q-select(`data-test="branch-select"` 추가) 다음 `<div data-test="task-form-refs">`에 `RefRepoChips :items="draft.refRepos" editable context="task-setup"` + `v-if="refAddOpen"`인 `.ref-add-panel` 안 `RefRepoAddForm :catalog="repoCatalog" :exclude-ids="refExcludeIds"`. `.ref-add-panel` 스타일은 `pages/questions/index.vue:400-410` 그대로. `clickSubmitButton`(마지막 '작업 등록' 버튼)·`findComponent(QFile)`이 그대로 맞도록 버튼·QFile을 더 넣지 않는다.
  - `test/setup.ts`: 위 `Object.assign(globalThis, …)`에 `useRoute` 기본값.
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령 + `npx vitest run test/tasks-form-repo-select.spec.ts test/tasks-form-attachments.spec.ts test/questions-index.spec.ts test/questions-detail.spec.ts test/hydration-auth-gate.spec.ts`
  - Expected: PASS(기존 테스트 수정 없음)
- [ ] **Step 5: 커밋** — `feat(frontend): 작업 등록 창 참조 repo 줄·칩 맥락 문구`

### Task 11: 프론트 — 질문 대화로 작업 등록(`fromQuestion` 프리필)

**Files:**
- Modify: `frontend/composables/questions.ts` (`QuestionDetail`(32-63) 뒤에 `TaskPrefill`·`taskPrefillFromQuestion`)
- Modify: `frontend/pages/tasks/index.vue` (`route`·`source`, `onRepoSelected`(247-262)에 선호 브랜치, `openCreate`(336-345) async, `submit` meta, 마운트 뒤 프리필, 다이얼로그 맨 위 안내 띠(633-636 뒤))
- Modify: `frontend/pages/questions/[id].vue` (헤더 `q-space`(247) 뒤·`close-session`(249-260) 앞 버튼, 스크립트 `goToTask`)
- Modify: `frontend/scripts/hydration-smoke.mjs` (`AUTH_PATHS`(33-45), `QUESTION`(67-79), `REPOS`(80), `apiResponse`(94-112))
- Test: `frontend/composables/questions.spec.ts`, `frontend/test/tasks-form-from-question.spec.ts`(신규), `frontend/test/questions-detail.spec.ts`

**Interfaces:**
- Consumes: Task 10(`draft.refRepos`·`removeRef`·`RefRepoChips` context), 등록 바디 `sourceQuestionId?`·409/403/404(Task 3), `GET /api/questions/{id}`(1단계 — `title`·`githubBranch`·`repoCatalogId`·`refRepos`·`statusName`).
- Produces (`composables/questions.ts`):
  - `interface TaskPrefill { repoCatalogId: number | null; branch: string; title: string; refRepos: RefRepoView[]; droppedRefs: RefRepoView[]; targetMissing: boolean }`
  - `taskPrefillFromQuestion(q: QuestionDetail, catalog: RepoCatalogOption[]): TaskPrefill` — 대상은 카탈로그에 있고 `host !== 'other'`일 때만, 참조는 같은 조건 + 대상과 다른 것만 `{catalogId, alias, branch}`로(sha·fetchedAt·error 제거), 나머지는 `droppedRefs`.
- Produces (`tasks/index.vue`): `source: Ref<{ id: number; title: string } | null>`, `onRepoSelected(catalogId: number | null, preferredBranch?: string)`, `openCreate(): Promise<void>`(카탈로그 로드까지 기다림), data-test `source-question-banner`·`source-question-clear`.
- Produces (`questions/[id].vue`): 버튼 `data-test="to-task"`.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// questions.spec.ts — taskPrefillFromQuestion
it('대상·브랜치·제목을 옮기고 참조는 커밋·상태를 지운 대기 값으로')  // refRepos toEqual [{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }]
it('카탈로그에 없거나 other 호스트인 참조는 droppedRefs로')
it('질문에 repoCatalogId가 없거나 카탈로그에 없으면 repoCatalogId null + targetMissing')
// tasks-form-from-question.spec.ts — let routeQuery; Object.assign(globalThis, { useRoute: () => ({ path: '/tasks', params: {}, query: routeQuery }), navigateTo: navigateToMock })
it('fromQuestion이 없으면 질문을 읽지 않고 창도 열지 않으며 navigateTo도 안 부른다')
it('마운트 뒤 질문을 읽어 창을 열고 대상·참조·제목을 채우고 작업 상세는 비우며 쿼리를 뺀다', async () => {
  // draft.repoCatalogId 1, draft.title = 질문 제목, draft.description '', 칩 data-state 'pending' + '인터뷰 때 받음'
  // navigateToMock toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
})
it('브랜치는 목록이 온 뒤 질문 브랜치로 — 카탈로그 기본(main)이 덮지 않는다')  // 지연 응답 전 '' → 뒤 'release/7.0'
it.each(['목록에 없음', '조회 실패'])('질문 브랜치를 못 쓰면 비우고 알린다 — %s')  // .q-notification에 '질문의 브랜치 release/7.0를 찾을 수 없습니다 — 브랜치를 골라 주세요'
it('카탈로그에 없는 참조는 빼고 알린다')            // '카탈로그에 없는 참조는 뺐습니다: 인증 서버(develop)'
it.each(['repoCatalogId 없음', '카탈로그에 없음'])('대상을 비우고 알린다 — %s')  // '질문의 대상 레포를 카탈로그에서 찾을 수 없습니다 — 레포를 골라 주세요'
it('안내 띠 문구와 등록 바디(JSON·multipart meta)에 sourceQuestionId·refRepos')  // '질문 #3 "옛앱 결재선 비교"에서 이어짐 — 대화 전문이 첨부됩니다'
it('안내 띠 ✕는 출처만 뺀다 — 채운 값은 그대로, 바디에 sourceQuestionId 없음')
it('질문을 못 읽으면(403) 서버 메시지로 알리고 창을 열지 않으며 쿼리는 뺀다')
it('작업 등록 버튼으로 다시 열면 출처·참조가 비워진다')
// questions-detail.spec.ts
it('이 대화로 작업 등록은 세션 종료 앞, 아이콘 add_task, 누르면 /tasks?fromQuestion=3')  // detailWith({ repoCatalogId: 1 })
it('답변 중(RUNNING)이거나 repoCatalogId가 없으면 막힌다')
it('끝난 질문(CANCELLED)에도 보인다')
it('좁은 화면은 라벨 없이 아이콘 + aria-label "이 대화로 작업 등록"')
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd frontend && npx vitest run composables/questions.spec.ts test/tasks-form-from-question.spec.ts test/questions-detail.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - `tasks/index.vue` 프리필(`onMounted`, 서버 렌더에서 안 함). 순서가 결정이다:
    1. `route.query?.fromQuestion`(옵셔널 체이닝 — `hydration-auth-gate.spec.ts:29`는 `query` 없는 route를 준다)이 없으면 아무것도 안 한다. 있으면 아래 전부를 `try/finally`로 감싸고 `finally`에서 `navigateTo({ path: '/tasks', query: {} }, { replace: true })`(새로고침해도 다시 안 열림). 양의 정수가 아니면 바로 finally.
    2. `GET /api/questions/{id}` 실패 → negative `e.data.message ?? '질문을 불러오지 못했습니다'`, 창을 열지 않는다.
    3. `await openCreate()`(초기화 + 카탈로그) → `taskPrefillFromQuestion` → `draft.title`·`draft.refRepos`, `source = { id, title }`. `description`은 비운다.
    4. 대상이 있으면 `draft.repoCatalogId` 설정 후 `onRepoSelected(id, p.branch)`. 브랜치는 `loadBranches` 뒤 `.then`(카탈로그 기본 덮어쓰기 자리)에서 적용: 목록에 있으면 그 값, 없거나 조회 실패면 `''` + warning 알림(고정 문구).
    5. `targetMissing`·`droppedRefs`면 warning 알림(위 테스트 문구).
  - `openCreate`는 `source = null`도 초기화하고 `await loadRepoCatalog()`로 끝난다(버튼 바인딩은 그대로).
  - `submit` meta: `...(source.value ? { sourceQuestionId: source.value.id } : {})`.
  - 안내 띠: 다이얼로그 제목 섹션 아래 `q-banner`(deep-purple-1) + `#action`에 `q-btn icon="close" aria-label="출처 연결 빼기"` → `source = null`.
  - 질문 화면 버튼: `outline dense no-caps color="primary" icon="add_task"`, `:label="$q.screen.lt.md ? undefined : '이 대화로 작업 등록'"`, `aria-label`, `:disable="!detail?.repoCatalogId || ['QUEUED','RUNNING'].includes(statusName ?? '')"`, `isTerminal`과 무관하게 항상 그린다. 클릭은 스크립트 함수 `goToTask()`가 `navigateTo(`/tasks?fromQuestion=${sessionId}`)`(템플릿에서 `navigateTo` 직접 호출 금지 — `tasks/index.vue:201-203` 주석).
  - 스모크: `AUTH_PATHS`에 `'/tasks?fromQuestion=3'`, `QUESTION.repoCatalogId: 1`, `REPOS`에 2 `옛앱`·3 `공통 UI`(gitlab) 추가(5 `인증 서버`는 빼서 "카탈로그에 없는 참조" 경로도 지나게), `apiResponse`에 `'/api/repos/branches'` → `{ repo: 'acme/widgets', defaultBranch: 'main', branches: [{ name: 'main', sha: 'abc123' }], fetchedAt: NOW }`.
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령 + `npx vitest run test/tasks-form-refs.spec.ts test/tasks-form-repo-select.spec.ts test/tasks-form-attachments.spec.ts test/tasks-index-mobile.spec.ts test/hydration-auth-gate.spec.ts` + `node --check scripts/hydration-smoke.mjs` + `npx nuxi prepare && npx nuxi typecheck`
  - Expected: PASS(하이드레이션 스모크 실행은 CI frontend 잡)
- [ ] **Step 5: 커밋** — `feat(frontend): 질문 대화로 작업 등록(fromQuestion 프리필)`

### Task 12: 프론트 — 승인 다이얼로그 참조 조정·구현 재시도 참조 빼기

**Files:**
- Modify: `frontend/composables/refRepos.ts` (`prepFailedRefIds`)
- Modify: `frontend/components/tasks/ApproveTaskSummary.vue:7-20` (`SummaryTask.refRepos?`)
- Modify: `frontend/components/ApproveDialog.vue:14-29`(import), `:47-64`(상태), `:126-166`(`init`), `:180-187`(승인 바디), `:264-277`(템플릿 — `approve-db` 다음, `usage-slot` 앞)
- Modify: `frontend/components/tasks/ImplementationRetryDialog.vue:7`(props), `:17-26`(열 때 초기화), `:32-35`(바디), `:59-61` 뒤(참조 영역)
- Modify: `frontend/pages/tasks/[id].vue:101-137`(`TaskResponse.refRepos?`), `:1242-1247`(재시도 창 바인딩)
- Test: `frontend/components/ApproveDialog.spec.ts`(`routeApi`(66-90)에 `repoCatalog` 옵션), `frontend/test/task-detail-implementation-retry.spec.ts`, `frontend/composables/refRepos.spec.ts`

**Interfaces:**
- Consumes: Task 10(`RefRepoChips` context), 승인 바디 `refRepos?`(Task 2 — null=유지, []=전부 해제), 재시도 바디 `removeRefCatalogIds?`(Task 5), 준비 실패 사유 `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}`(Task 7).
- Produces:
  - `prepFailedRefIds(reason: string | null | undefined, refs: RefRepoView[]): number[]` — 사유가 `참조 repo 준비 실패: {alias}({branch})`로 시작하는 참조의 catalogId.
  - `SummaryTask.refRepos?: RefRepoView[]`, `[id].vue` `TaskResponse.refRepos?: RefRepoView[]`(타입 import는 상대 경로 `../../composables/refRepos` — `defineProps` 타입 규칙).
  - `ImplementationRetryDialog` props `+ refRepos?: RefRepoView[]; failureReason?: string | null`. data-test `impl-retry-refs`, 참조마다 `impl-retry-ref-{catalogId}`(q-checkbox).
  - `ApproveDialog` data-test `approve-refs`.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// refRepos.spec.ts
it('prepFailedRefIds: 사유의 별칭(브랜치)와 맞는 참조만')   // '참조 repo 준비 실패: 옛앱(release/6.5) — fatal: …' → [2]
it('prepFailedRefIds: 브랜치가 다르거나 다른 사유·null이면 []')
// ApproveDialog.spec.ts — mountDialog({ repoCatalogId: 1, refRepos: [OLD] }), OLD = { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: null, fetchedAt: null, error: null }
it('작업 참조로 칩 줄을 채우고("인터뷰 때 받음") DB 아래·사용량 위에 둔다')
it('참조를 안 바꾸면 승인 바디는 지금과 같다')            // approveCall()[1] toEqual { method: 'POST', body: { model, effort, mcpCatalogIds: [] } }
it('빼면 남은 목록, 전부 빼면 []를 싣는다')               // body.refRepos toEqual [] (생략이 아니다)
it('＋ 참조 추가를 처음 누를 때만 /api/repo-catalog를 부른다 — 열 때는 0회, 두 번째 토글도 1회')
it('추가 폼 후보는 대상·이미 고른 참조를 뺀다, 추가하면 바디에 실린다')
it('닫았다 다시 열면 작업 참조로 다시 채운다')
it('repoCatalogId 없는 작업은 참조 줄이 없다')
// task-detail-implementation-retry.spec.ts — 참조 2개(옛앱 2·공통 UI 3, 둘 다 sha 있음)
it('참조가 있으면 목록·"이 참조 빼고 재시도"(기본 해제)·안내 문구, 고르지 않으면 바디는 { implementationMode }만')
it('사유가 참조 준비 실패면 그 참조가 미리 선택되고 바디에 removeRefCatalogIds: [2]')
it('참조가 없으면 참조 영역이 없다')                      // 기존 failed 픽스처(refRepos 없음)
// 기존 '관리자: … 재시도 API'(정확 바디 { implementationMode: 'REVIEW' })는 수정 없이 통과
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd frontend && npx vitest run composables/refRepos.spec.ts components/ApproveDialog.spec.ts test/task-detail-implementation-retry.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - `ApproveDialog`: `refs`(편집 초안), `refAddOpen`, `refCatalog`, 한 번만 부르는 `loadRefCatalogOnce()`(실패면 negative `e.data.message ?? '레포 목록을 불러오지 못했습니다'`, 다음에 다시). `init()`이 `refs = [...(task.refRepos ?? [])]`, `refAddOpen = false`(재승인 때도 작업 값 — 지난 세션 참조로 프리필하지 않는다).
  - 승인 바디: `!sameRefSet(toInputs(refs), toInputs(task.refRepos ?? []))`일 때만 `refRepos: toInputs(refs)` — 기존 정확-바디 테스트(`ApproveDialog.spec.ts:127-140, 142-168, 170-198, 215-228, 250-267`) 그대로.
  - 템플릿: `v-if="task.repoCatalogId"`인 `approve-refs` 블록에 `RefRepoChips editable context="task-setup"` + 인라인 `RefRepoAddForm`(제외: 대상 + 고른 것, `.ref-add-panel`). `ApproveTaskSummary`에는 칩 줄을 넣지 않는다(스펙 §7.3). 모바일 전체화면도 같은 순서.
  - 재시도 창: 열 때 `removeIds = prepFailedRefIds(failureReason, refRepos ?? [])`. 참조마다 `refChipLabel(r)` 칩 + `q-checkbox v-model="removeIds" :val="r.catalogId" label="이 참조 빼고 재시도"`, 아래 캡션 `뺀 참조는 이번 구현과 이후 재시도에서 쓰지 않습니다.`. 바디는 고른 것이 있을 때만 `removeRefCatalogIds`(작업 참조 순서로).
  - `[id].vue`: `<ImplementationRetryDialog … :ref-repos="task.refRepos ?? []" :failure-reason="task.failureReason" />`.
  - 이 두 창은 열 때 API 호출을 늘리지 않는다(`task-detail-*` spec은 `useApiMock.mockResolvedValue(task)`로 모든 호출에 객체를 준다).
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령 + `npx vitest run test/task-detail-approve.spec.ts components/tasks/ApproveTaskSummary.spec.ts`
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(frontend): 승인 다이얼로그 참조 조정·구현 재시도 참조 빼기`

### Task 13: 프론트 — 작업 상세 참조 줄·인터뷰 중 참조 변경·확정 화면

**Files:**
- Create: `frontend/components/tasks/TaskRefChangeMenu.vue`
- Modify: `frontend/composables/refRepos.ts` (`taskRefContext`·`splitConfirmRefs`)
- Modify: `frontend/pages/tasks/[id].vue:101-137`(`sourceQuestionId?`), 스크립트(`isInterviewPhase`(250-252) 근처: 초안·시드·`answerRefs`·`interviewLive`), `:724-735`(모바일 인터뷰 카드), `:790-796`(레포 옆 출처 링크), `:837` 뒤(DB 칩 다음 참조 줄), `:919-924`(데스크톱 패널)
- Modify: `frontend/components/tasks/TaskDetailMobile.vue:11-36`(`DetailTask`), `:230-243`(정보 영역)
- Modify: `frontend/components/InterviewPanel.vue:15-26`(props), `:187-207`(INTERVIEW 답변 `refRepos`), `:519` 앞(확정 화면 참조)
- Modify: `frontend/scripts/hydration-smoke.mjs:58-61`(작업 1 목)
- Test: `frontend/test/task-detail-refs.spec.ts`(신규), `frontend/components/InterviewPanel.spec.ts`, `frontend/composables/refRepos.spec.ts`

**Interfaces:**
- Consumes: Task 10(context), Task 12(`TaskResponse.refRepos?`), `TaskResponse.refRepos`(인터뷰 단계면 진행 중 세션 참조·상태)·`sourceQuestionId`(Task 1), INTERVIEW 답변 `refRepos?`(Task 4).
- Produces (`composables/refRepos.ts`):
  - `taskRefContext(status: string): RefChipContext` — `AWAITING_APPROVAL` → `task-setup`, `INTERVIEWING|INTERVIEW_INPUT|INTERVIEW_REVIEW` → `task-interview`, 그 밖 → `task-fixed`.
  - `splitConfirmRefs(refs: RefRepoView[]): { pinned: RefRepoView[]; stale: RefRepoView[]; dropped: RefRepoView[] }` — pinned = sha 있는 것(error를 null로), stale = sha와 error 둘 다, dropped = sha 없음(서버 §4.6 복사 규칙과 같다).
- Produces: `TaskRefChangeMenu` props `{ modelValue: RefRepoView[]; serverRefs: RefRepoView[]; targetCatalogId: number | null; disable?: boolean }`, emits `update:modelValue`. 버튼 `data-test="ref-change"`(`✎ 참조 변경`, aria-label `참조 변경`, 툴팁 `인터뷰 중 변경 — 다음 답변부터 적용`).
- Produces: `InterviewPanel` props `+ answerRefRepos?: RefRepoInput[] | null`(INTERVIEW 전용, 기본 입력창 전송에 싣는 값), `+ sessionRefRepos?: RefRepoView[]`(확정 화면 분류용). data-test `confirm-refs`·`confirm-ref-stale`·`confirm-ref-dropped`.
- Produces: data-test `task-ref-chips`(데스크톱), `source-question-link`, `mobile-interview-refs`, `info-refs`·`info-source-question`(모바일 정보).

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// refRepos.spec.ts
it('taskRefContext: 승인대기 task-setup · 인터뷰 3상태 task-interview · 그 밖 task-fixed')
it('splitConfirmRefs: sha+error는 pinned(error null)와 stale 양쪽, sha 없음은 dropped')
// InterviewPanel.spec.ts
// 뒤집기: 'INTERVIEW 세션의 answer 바디는 send({refRepos})가 와도 refRepos를 싣지 않는다'(605-633) →
it('INTERVIEW answer 바디도 send({refRepos})를 싣는다 — model/effort는 여전히 안 싣는다')
it('answerRefRepos가 배열이면 기본 입력창 전송에 싣고, null이면 바디는 { answer, replyToSeq } 그대로')
it('확정 화면: 구현에 쓸 참조 (이 커밋으로 고정) — 고정 칩 2개 · stale/dropped 경고 줄, readonly에도 보인다', async () => {
  // PLAN_READY 스냅샷 + sessionRefRepos [PINNED, {…PINNED2, error: 'x'}, NO_SHA]
  // confirm-ref-stale: '⚠ 옛앱2: 마지막 턴에는 받지 못했습니다 — 고정 커밋으로 구현합니다'
  // confirm-ref-dropped: '⚠ 인증 서버(develop) — 인터뷰에서 받지 못해 구현에 넘기지 않습니다'
})
it('sessionRefRepos가 비면 confirm-refs가 없고 /confirm 바디는 그대로')
// task-detail-refs.spec.ts — useRoute params id 42, stubs router-link·DesignReviewCard·NuxtLink({ props: ['to'], template: '<a :href="to"><slot /></a>' })
it.each([['AWAITING_APPROVAL', '인터뷰 때 받음'], ['INTERVIEW_INPUT', '다음 답변 때 받음']])('%s 대기 칩 문구')
it('참조 칩 줄은 DB 칩 줄 다음, 참조가 없으면 숨김 — 인터뷰 단계 관리자만 "참조 repo 없음" + ✎')
it('요청자는 인터뷰 중에도 ✎가 없다, 관리자는 패널 상태가 입력대기일 때만 열린다')
it('참조를 바꿔 기본 입력창으로 답하면 /api/interviews/99/answer 바디에 refRepos, 안 바꾸면 키 없음')
it('초안은 세션마다 한 번 — 폴링(refresh)에도 유지, interviewSessionId가 바뀌면 다시 시드')
it('✎ 메뉴를 처음 열 때만 /api/repo-catalog — 페이지를 열 때는 0회')
it('✎ 메뉴의 추가 폼 후보에 대상 레포가 없고, 메뉴 칩은 서버 상태(@sha7·⚠)를 입힌다')
it('출처가 있으면 레포 옆 "↩ 질문 #3에서 이어짐" → /questions/3')
it('모바일: 정보 영역 읽기 전용 칩 줄·출처, 관리자 인터뷰 카드에 ✎')
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd frontend && npx vitest run composables/refRepos.spec.ts components/InterviewPanel.spec.ts test/task-detail-refs.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - `TaskRefChangeMenu`: `pages/questions/[id].vue:104-147, 264-287`과 같은 동작 — 메뉴 칩 = 초안 구성에 서버 상태를 입힌 것(`draftChips`), 편집 칩 줄 context `task-interview`, 인라인 추가 폼(제외: 대상 + 초안), 카탈로그는 메뉴 `@show`에서 처음 한 번(실패면 알림 후 다음에 재시도), `@hide`에 폼 접기. 루트·메뉴 내용은 `<div>`.
  - `[id].vue`: `pickedRefs`, `refsSeededFor: number | null`. `task`가 인터뷰 단계이고 `interviewSessionId !== refsSeededFor`일 때만 `pickedRefs = [...(task.refRepos ?? [])]`로 시드(낡은 초안이 재승인 때 뺀 참조를 되살리지 않게). `answerRefs` = 시드된 세션이고 `!sameRefSet(toInputs(pickedRefs), toInputs(task.refRepos ?? []))`일 때만 `toInputs(pickedRefs)`, 아니면 `null`. `interviewLive`는 두 `InterviewPanel`의 `@status`로 받는다.
  - `canChangeRefs = auth.isAdmin && isInterviewPhase && !!task.interviewSessionId`, `showRefRow = (task.refRepos?.length ?? 0) > 0 || canChangeRefs`. 데스크톱 `task-ref-chips` 섹션: `RefRepoChips :items="task.refRepos ?? []" :context="taskRefContext(task.status)"` + `canChangeRefs`일 때 `<TaskRefChangeMenu v-model="pickedRefs" :server-refs="task.refRepos ?? []" :target-catalog-id="task.repoCatalogId ?? null" :disable="interviewLive !== 'AWAITING_INPUT'" />`. 모바일 인터뷰 카드(`canChangeRefs`일 때만) `mobile-interview-refs`에 같은 줄(같은 props 네 개). `data-test="ref-change"`는 한 번에 하나만 마운트된다(`$q.screen.lt.md` 분기).
  - 두 `InterviewPanel`(724-735·919-924)에 `:answer-ref-repos="answerRefs" :session-ref-repos="task.refRepos ?? []" @status="interviewLive = $event"`.
  - 출처 링크: `<NuxtLink v-if="task.sourceQuestionId" :to="`/questions/${task.sourceQuestionId}`">↩ 질문 #{id}에서 이어짐</NuxtLink>`(권한 없음 안내는 질문 화면 몫).
  - `TaskDetailMobile`: `DetailTask`에 `refRepos?`·`sourceQuestionId?`(상대 경로 타입 import), 정보 영역에 참조가 있을 때만 `info-refs`(읽기 전용, `taskRefContext(task.status)`)와 `info-source-question` 링크.
  - `InterviewPanel.sendAnswer`: INTERVIEW면 `extra?.refRepos ?? props.answerRefRepos`가 배열일 때만 `body.refRepos`(QUESTION 분기는 그대로 extra만). 확정 화면: `v-if="!readonly"` 블록(519) 바로 앞, `plan && sessionRefRepos?.length`일 때 `confirm-refs` — 제목 `구현에 쓸 참조 (이 커밋으로 고정)`, pinned가 있으면 `RefRepoChips context="task-fixed"`, 그 아래 stale·dropped 경고 줄(위 문구). 확정 바디는 그대로.
  - 스모크: 작업 1에 `refRepos`(고정·대기·실패 각 1, `QUESTION.refRepos`와 같은 값)와 `sourceQuestionId: 3`.
- [ ] **Step 4: 통과 확인**
  - Run: `npx vitest run`(전체 — `InterviewPanel`·`RefRepoChips`·`[id].vue`를 여러 spec이 마운트) + `node --check scripts/hydration-smoke.mjs` + `npx nuxi prepare && npx nuxi typecheck`
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(frontend): 작업 상세 참조 줄·인터뷰 중 참조 변경·확정 화면 구현 참조`
### Task 14: 문서·스펙 정정·전체 검증

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-24-task-ref-repos-design.md`
- Modify: `netismaker-interview-service/.env.example`(작업 9에서 이미 했으면 확인만)

**Interfaces:**
- Consumes: 작업 1~13의 결과(이름·설정·문구).

- [ ] **Step 1: CLAUDE.md 갱신**
  - "핵심 설계 제약"의 질문 세션 항목 끝(1단계 참조 repo 요약 다음)에 "작업 참조 repo(2026-10-24, 스펙 `docs/superpowers/specs/2026-10-24-task-ref-repos-design.md`)" 요약을 4~6줄로 붙인다.
    - 등록 때 요청자, 승인 때 관리자 조정, 인터뷰 답변 때 관리자 변경(1단계 규칙). 계획 확정 때 커밋이 고정된 참조만 `task.ref_repos`(V33)로. 구현·재시도는 같은 커밋, 재시도 창에서 빼기만.
    - 워커: 공유 레포 캐시에서 고정 커밋을 받아 `{worktree-root}/_refs/task-{id}/{카탈로그 id}`에 detached worktree(`origin/*`·캐시 HEAD 불변, 락 비중첩, 무프롬프트 git, worktree 전용 pushurl 무효화). 준비 실패면 claude 전에 구현실패. 실행 뒤 기준선 대비 변경은 PR 본문·이력에 경고만. 구현 claude에 `--add-dir`를 주지 않는 이유(참조 repo의 skills·agents가 붙음).
    - 구버전 워커는 `GET /worker/next-task`에 `supports=refRepos`가 없어 참조 있는 구현을 받지 못한다.
    - 인터뷰 서비스: INTERVIEW도 참조를 준비해 kickoff·resume 접두로 알리고 `question`·`plan` 보고에 `refStates`. 참조 하나당 받기 상한 `INTERVIEW_REF_TIMEOUT_MS`(기본 300000, 질문 세션 포함).
    - 질문 대화 화면 "이 대화로 작업 등록" → `/tasks?fromQuestion={id}`, 서버가 대화 전문 `question-{id}-대화.md`를 첨부하고 `task.source_question_id`를 기록.
    - 배포 순서: 인터뷰 서비스·워커(진행 중 구현이 없을 때 재기동) → API(V33) → 프론트(`.output` 재빌드).
  - "자주 보는 코드" 표에 행 1개: 작업 참조 repo — API `service/RefRepoChanges`·`TaskService`(등록·승인·재시도)·`InterviewService`(`submitAnswerWithRefs`·`refsFor`·`confirm`)·`service/QuestionTranscript`·`WorkerService`(claim 가드), 워커 `GitRepoCache.prepareRefCommitUnderLock`·`WorktreeService.createRefWorktree`·`RefRepoPrompts`·`WorktreeCleanupJob`(`_refs`), 인터뷰 서비스 `interviewRefSection`·`ensureRefRepos`(timeoutMs), 프론트 `TaskRefChangeMenu`·`ApproveDialog`·`ImplementationRetryDialog`·`pages/tasks/index.vue`(`fromQuestion`).
  - "환경 메모"의 worktree 보존 항목에 한 줄: 참조 worktree `_refs/task-{id}/{카탈로그 id}`는 작업 끝에 항상 지우고, 남은 것은 `WorktreeCleanupJob`이 같은 7일·진행 중 보호 규칙으로 지운다(원본 캐시를 못 찾으면 폴더만).
- [ ] **Step 2: 스펙 정정**(결정은 바꾸지 않는다)
  - §4.7: `GET /worker/tasks/next` → `GET /worker/next-task?workerId=…&supports=refRepos`.
  - §4.6: 고정된 참조가 없을 때 `구현 참조: 없음 / 제외(인터뷰에서 받지 못함): …`.
  - §6.6·§6.3: 계획의 "추가 고정 문구"(워커 경고 4종·시간 초과·준비 실패 꼬리)를 적는다.
  - §7.2·§7.4: 프론트 알림 문구를 적는다.
  - §10 첫 줄: "claim·결과 바디가 바이트 그대로" → "화면·인터뷰 서비스 바디는 바이트 그대로, 워커 ↔ API Java DTO는 값이 같으면 됨(빈 목록·null 허용 — §1.2)".
  - §5.4 알려진 한계에 두 줄: 참조 3개 × 5분 + 대상 clone이 턴 상한 30분에 닿으면 턴이 실패할 수 있음 / 인터뷰 중 참조 폴더가 사라져 다시 받으면 브랜치 최신을 받아 인터뷰가 본 커밋과 고정 커밋이 다를 수 있음(1단계 단순화, 경고 로그만).
  - 상태 줄 끝에 ` · 구현됨`.
- [ ] **Step 3: 전체 검증**
  - 백엔드: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test` → 이 PC의 원래 실패 3건 외에는 PASS(10분 넘게 걸릴 수 있으니 시간 상한을 넉넉히, 또는 백그라운드)
  - 인터뷰 서비스: `cd netismaker-interview-service && npx vitest run && npx tsc -p tsconfig.json --noEmit` → PASS
  - 프론트: `cd frontend && npx vitest run && npx nuxi prepare && npx nuxi typecheck` → PASS, `node --check scripts/hydration-smoke.mjs`
  - 건수(전체·실패·skip)를 보고서에 적는다. 원래 실패 3건 말고 다른 실패가 있으면 코드를 고치지 말고 보고한다.
- [ ] **Step 4: 커밋** — `docs: 작업 참조 repo 운영 메모·스펙 정정`

---

병합 뒤(이 계획 밖): 푸시 → PR → CI 3개(Testcontainers·하이드레이션 스모크가 여기서 처음 돈다) → 라이브 반영. 반영 순서는 인터뷰 서비스·워커 → API(V33) → 프론트이고, 확인할 항목은 스펙 §10 "라이브 확인"이다(사내 GitLab의 `fetch --depth=1 <sha>` 허용 여부 포함).
