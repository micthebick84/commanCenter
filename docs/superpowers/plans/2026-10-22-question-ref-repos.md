# 질문 세션 참조 repo + 인터뷰 체크아웃 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문(Q&A) 세션에 읽기 전용 참조 repo를 최대 3개 붙여 옛 repo와 새 repo를 비교해 답하게 한다. 함께, 끝난 세션의 인터뷰 체크아웃을 7일 뒤 지우는 정리 잡을 넣는다.

**Architecture:**
- **API**: 참조 목록을 세션 JSONB 스냅샷 `interview_session.ref_repos`로 저장한다. claim 때 참조별 checkout 경로(`{work-root}/_refs/{세션}/{카탈로그}`)를 실어 보낸다.
- **인터뷰 서비스**: 참조를 처음 한 번 depth 1로 받아 고정한다. 이어서 세 곳에 연결한다: 프롬프트 접두 절, 질문 게이트의 Read/Grep/Glob 허용 루트, 답변 보고(`refStates`).
- **화면**: 새 질문 화면과 대화 화면이 같은 참조 칩·추가 폼 컴포넌트를 쓴다.
- **정리 잡**: API에서 첨부 정리 잡과 같은 "판정(순수 함수) / 실행" 분리 구조로 돈다.

**Tech Stack:** Spring Boot 3 / Java 21 / JPA·Flyway(PostgreSQL JSONB) / JUnit5·Mockito·AssertJ · Node 20 TS + Claude Agent SDK + vitest · Nuxt 3 + Quasar + vitest(@vue/test-utils)

**Spec:** `docs/superpowers/specs/2026-10-22-question-ref-repos-design.md`

## Global Constraints

**작업 환경**

- **작업 위치**: worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-ref-repos`(브랜치 `feature/question-ref-repos`).
  - 본 체크아웃 `C:\Users\mic\NetisMaker\commanCenter`에서는 라이브가 `gradlew bootRun`으로 돈다. 거기서 빌드·테스트·npm 설치를 하지 않는다.
- **TS 테스트 준비**(한 번, PowerShell): worktree의 `netismaker-interview-service\node_modules`와 `frontend\node_modules`를 본 체크아웃의 같은 폴더로 가는 정션으로 만든다.
  - 예: `New-Item -ItemType Junction -Path <wt>\frontend\node_modules -Target C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules`(인터뷰 서비스도 같은 방식).
  - `npm ci`·`npm install`은 금지.
  - worktree를 지우기 전에는 반드시 `cmd /c rmdir <wt>\frontend\node_modules`(인터뷰 서비스도)로 정션부터 지운다.

**테스트 명령**

- **백엔드**: worktree 루트의 Git Bash에서 `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.<패키지>.<클래스>"`.
  - 클래스는 FQN으로 쓴다. `*Interview*` 같은 패턴은 디렉터리 이름으로 확장돼 실패한다.
  - 전체를 돌리면 이 PC는 원래 3건이 실패한다(`AttachmentCleanupJobTest`·`AttachmentStorageTest`의 심볼릭 링크). 회귀가 아니다.
  - Testcontainers 테스트는 skip된다(CI에서 돈다).
- **인터뷰 서비스**: `cd netismaker-interview-service && npx vitest run test/<파일>`. 타입 검사는 `npx tsc -p tsconfig.json --noEmit`.
- **프론트**: `cd frontend && npx vitest run test/<파일>`. 타입 검사는 `npx nuxi prepare && npx nuxi typecheck`.
  - 하이드레이션 스모크는 이 PC에서 돌리지 않는다(CI frontend 잡이 돈다).

**파일·커밋 규칙**

- **CRLF**: 작업본은 CRLF다(`core.autocrlf=true`). 기존 파일을 Edit/Write한 뒤 `file <경로>`가 LF로 바뀌었으면 `sed -i 's/\r$//; s/$/\r/' <경로>`로 되돌린다. 새 파일은 LF여도 된다.
- **커밋**: 레포의 작성자 설정(khlee84)을 그대로 쓰고, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다. 푸시는 하지 않는다(사용자 확인 뒤).

**고정값**

- **상한** `MAX_REF_REPOS = 3`(API `RepoCatalogService.MAX_REF_REPOS`, 프론트 `composables/refRepos.ts`).
- **경로**
  - 체크아웃 루트 설정은 `app.interview.work-root`(기본 `${user.home}/netis-maker/interviews`).
  - 참조 폴더는 `{루트}/_refs/{세션 id}/{카탈로그 id}`.
  - 대상 `work_dir` 문자열 형식(`{루트}/{localKey}/session-{id}`)은 지금과 같아야 한다.
- **정리 잡 설정**: `app.interview.checkout-cleanup.enabled`(true), `.cron`(`0 50 3 * * *`), `.retention-days`(7, 1 미만이면 기동 실패).

**문구(그대로 쓴다)**

- **오류 문구**: `참조 repo는 최대 3개까지 고를 수 있습니다` · `대상 레포는 참조로 고를 수 없습니다` · `같은 레포를 참조로 두 번 고를 수 없습니다` · `참조 브랜치를 지정하세요`
- **시스템 메모**: `참조 repo 변경: ＋ {별칭}({브랜치}), − {별칭}({브랜치})`
  - 추가를 먼저 쓰고, 쉼표+공백으로 구분한다.
  - `＋`는 U+FF0B, `−`는 U+2212.
  - 전부 해제하면 `참조 repo 변경: 없음(전부 해제)`.
- **화면 문구**: 칩 줄 라벨 `참조 repo · 읽기 전용 · 최대 3개`, 버튼 `＋ 참조 추가`·`✎ 참조 변경`, 대기 칩 `다음 답변 때 받음`, 빈 줄 `참조 repo 없음`, 고정 툴팁 `고정 커밋 {sha7} · {받은 시각} — 대화 끝까지 이 커밋 기준`.

**지켜야 할 불변식**

- 참조가 없는 질문은 claim 이후의 러너 동작, 프롬프트, ask 바디, 등록 바디가 지금과 같아야 한다. 빈 필드는 생략하고, 빈 절은 `''`이다.
- 질문 게이트의 Bash 규칙은 바꾸지 않는다. INTERVIEW 게이트도 바꾸지 않는다.
- 토큰이 든 문자열은 `maskSecrets`(TS) 또는 `GitRemotes.mask`(Java)를 거친 뒤에만 `error`·로그·프롬프트에 넣는다.

## Review Focus

1. **참조 브랜치가 원격에서 사라졌거나 권한이 없을 때**: 세션은 실패하지 않고 답이 나오며, 칩은 ⚠와 사유를 보인다. (Task 8 `ref prepare failure still answers and reports the masked error`)
2. **새 질문 화면에서 대상 레포를 이미 참조로 고른 레포로 바꿀 때**: 그 참조만 빠지고 등록이 400 없이 된다. (Task 10 `대상을 참조 중 하나로 바꾸면 그 참조만 빠진다`)
3. **대화 중 참조를 뺐다가 같은 브랜치로 다시 넣을 때**: 같은 폴더를 다시 쓰고 커밋이 바뀌지 않는다. (Task 6 `reuses an existing checkout on the same branch without fetching`, Task 4 `recordQuestion_pins_sha_only_once`)
4. **Windows 경로 표기 차이**: `C:\…\_refs\5\12\A.java`와 `c:/…/_refs/5/12/A.java`는 둘 다 허용하고, `_refs/5/120`·`_refs/50`은 거부한다. (Task 7 `allows ref paths in either separator and drive-letter case`, `denies sibling folders that share a name prefix`)
5. **오래 묵은 진행 중 세션의 체크아웃**: 입력대기·플랜완료·인터뷰중·인터뷰대기는 `last_activity_at`이 오래됐어도 지우지 않는다. (Task 5 `keeps_open_sessions_even_when_old`)

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V31__question_ref_repos.sql` | `interview_session.ref_repos` |
| `…/entity/RefRepo.java` (신규) | 참조 스냅샷과 런타임 상태(JSONB 원소) |
| `…/dto/RefRepoInput.java` (신규) | 등록·변경 입력 `{repoCatalogId, branch}` |
| `…/service/InterviewWorkPaths.java` (신규) | 체크아웃 루트, 대상 `work_dir`, 참조 폴더 경로 규칙 |
| `…/service/RepoCatalogService.java` | 참조 검증·해석(`checkRefShape`, `resolveReferences`) |
| `…/service/QuestionService.java`, `…/controller/QuestionController.java` | 등록 스냅샷, `applyRefChange`, 메모 SSE |
| `…/service/InterviewService.java` | 경로 규칙 위임, claim에 참조 싣기, `refStates` 반영 |
| `…/dto/{QuestionCreateRequest,QuestionAskRequest,InterviewResponse,InterviewClaimResponse,WorkerQuestionRequest}.java` | 필드 추가 |
| `…/util/FileTrees.java` (신규) | 링크·정션을 따라가지 않는 트리 삭제(워커 `RepoCacheCleanupJob.deleteTree`에서 옮김) |
| `…/service/InterviewCheckoutCleanupPlanner.java`, `InterviewCheckoutCleanupJob.java` (신규) | 정리 판정(순수 함수)과 실행 |
| `netismaker-interview-service/src/types.ts` | `RefRepoClaim`, `RefState` |
| `…/src/runner/repoPrepare.ts` | `ensureRefRepos` |
| `…/src/sdk/permissions.ts`, `sessionOptions.ts` | QUESTION 게이트의 참조 루트 |
| `…/src/runner/interviewRunner.ts` | 참조 준비, `refSection`, `refStates` 보고 |
| `frontend/composables/refRepos.ts` (신규) | 타입, 상한, 칩 라벨, 집합 비교 |
| `frontend/components/RefRepoChips.vue`, `RefRepoAddForm.vue` (신규) | 칩 줄, 추가 폼 |
| `frontend/pages/questions/index.vue`, `[id].vue`, `components/InterviewPanel.vue`, `composables/questions.ts`, `scripts/hydration-smoke.mjs` | 화면 연결 |
| `CLAUDE.md` | 질문 세션 항목, 자주 보는 코드, 환경 메모 |

(`…` = `src/main/java/com/hamonsoft/netismaker`, 테스트는 `src/test/java/com/hamonsoft/netismaker`)

---

### Task 1: 데이터(V31)·참조 값 타입·체크아웃 경로 규칙

**Files:**
- Create: `src/main/resources/db/migration/V31__question_ref_repos.sql`
- Create: `…/entity/RefRepo.java`
- Create: `…/service/InterviewWorkPaths.java`
- Modify: `…/entity/InterviewSession.java` (JSON 필드 추가)
- Modify: `…/service/InterviewService.java:62-82,163-166,216-226` (생성자, `deriveWorkDir` → `InterviewWorkPaths`)
- Modify: `src/main/resources/application.yml` (`app.interview.work-root`)
- Modify: 테스트 `…/service/InterviewServiceTest.java:46`, `…/service/InterviewServiceConfirmModeTest.java:31` (생성자 인자)
- Test: `…/service/InterviewWorkPathsTest.java`, `…/entity/RefRepoTest.java`

**Interfaces:**
- Produces:
  - `@JsonInclude(NON_NULL) record RefRepo(Long catalogId, String alias, String path, String gitUrl, String branch, String sha, String fetchedAt, String error)`
    - `boolean matches(Long catalogId, String branch)` — `branch`가 blank면 catalogId만 비교한다.
    - `RefRepo pinned(String sha, String fetchedAt)` — 이미 `sha`가 있으면 `this`를 돌려준다. 새로 고정할 때는 `error`를 비운다.
    - `RefRepo withError(String error)`
    - `String label()` — `alias + "(" + branch + ")"`
    - `fetchedAt`은 ISO-8601 문자열이다(`OffsetDateTime.toString()`). JSONB 매핑에 날짜 타입을 쓰지 않기 위해서다.
  - `InterviewSession.getRefRepos(): List<RefRepo>`, `setRefRepos(List<RefRepo>)` — 컬럼 `ref_repos`, 기본값 `new ArrayList<>()`.
  - `@Component @Profile("api") InterviewWorkPaths(@Value("${app.interview.work-root:${user.home}/netis-maker/interviews}") String root)`
    - `String workDirFor(String githubRepo, String gitUrl, long sessionId)`
    - `String refDir(long sessionId, long catalogId)`
    - `Path root()`
  - `InterviewService` 생성자의 마지막 인자에 `InterviewWorkPaths workPaths`를 더한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// InterviewWorkPathsTest
@Test void work_dir_keeps_the_legacy_format() {
    InterviewWorkPaths p = new InterviewWorkPaths("/home/u/netis-maker/interviews");
    assertThat(p.workDirFor("owner/repo", null, 1)).isEqualTo("/home/u/netis-maker/interviews/owner/repo/session-1");
    assertThat(p.workDirFor("g/sub/p", "https://gitlab.example/g/sub/p.git", 7))
            .isEqualTo("/home/u/netis-maker/interviews/_gitlab/g+sub+p/session-7");
}
@Test void ref_dir_is_short_and_separate_from_the_target() {
    assertThat(new InterviewWorkPaths("/r").refDir(123, 12)).isEqualTo("/r/_refs/123/12");
}

// RefRepoTest
@Test void pinned_sets_sha_once_and_clears_error() {
    RefRepo r = new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5", null, null, "권한 없음");
    RefRepo p = r.pinned("a".repeat(40), "2026-10-22T10:00+09:00");
    assertThat(p.sha()).isEqualTo("a".repeat(40));
    assertThat(p.error()).isNull();
    assertThat(p.pinned("b".repeat(40), "later")).isSameAs(p);
}
@Test void matches_by_catalog_and_branch_and_blank_branch_matches_any() {
    RefRepo r = new RefRepo(12L, "옛앱", "g/old", "u", "main", null, null, null);
    assertThat(r.matches(12L, "main")).isTrue();
    assertThat(r.matches(12L, "dev")).isFalse();
    assertThat(r.matches(12L, " ")).isTrue();
    assertThat(r.matches(13L, "main")).isFalse();
}
```

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.InterviewWorkPathsTest" --tests "com.hamonsoft.netismaker.entity.RefRepoTest"`
  - Expected: 컴파일 실패(클래스 없음)
- [ ] **Step 3: 구현**
  - V31은 스펙 §3.1의 SQL 그대로다.
  - `InterviewSession.refRepos`는 `mcpsExtra`와 같은 어노테이션(`@JdbcTypeCode(SqlTypes.JSON) @Column(name = "ref_repos", nullable = false, columnDefinition = "jsonb") @Setter`)을 쓴다.
  - `InterviewWorkPaths.workDirFor`
    - 지금 `deriveWorkDir`(`InterviewService.java:221-226`)의 키 규칙을 옮긴다: `GitRemotes.localKey`, 한 단계짜리 값에는 `_/` 접두.
    - `user.home` 대신 생성자로 받은 루트를 쓴다.
    - `InterviewService.claim`은 이 메서드를 부르고, `deriveWorkDir`는 지운다.
  - `application.yml`의 `app.interview` 아래에 `work-root: ${user.home}/netis-maker/interviews`를 넣고, "정리 잡과 같은 값을 쓴다"는 주석을 단다.
  - 두 테스트의 `new InterviewService(...)` 끝에 `new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews")`를 넣는다. 기존 `endsWith("/netis-maker/interviews/owner/repo/session-1")` 단언은 그대로 통과해야 한다.
- [ ] **Step 4: 통과 확인**
  - Run: 위 명령 + `--tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.service.InterviewServiceConfirmModeTest"`
  - Expected: PASS
- [ ] **Step 5: 커밋**
  - `git add` 위 파일들
  - `git commit -m "feat(question): 참조 repo 스냅샷 컬럼(V31)·체크아웃 경로 규칙"`

### Task 2: 참조 검증·해석 (`RepoCatalogService`)

**Files:**
- Create: `…/dto/RefRepoInput.java`
- Modify: `…/service/RepoCatalogService.java`
- Test: `…/service/RepoCatalogServiceTest.java`

**Interfaces:**
- Consumes: `RefRepo`(Task 1), 기존 `resolveForRegistration(Long)`.
- Produces:
  - `record RefRepoInput(Long repoCatalogId, String branch)`
  - `public static final int MAX_REF_REPOS = 3`
  - `public static void checkRefShape(Long targetCatalogId, List<RefRepoInput> inputs)` — 카탈로그 조회 없이 상한, catalogId 중복, 대상과 같음만 검사한다. 위반이면 400(`TaskException`, `HttpStatus.BAD_REQUEST`)이고 문구는 Global Constraints대로.
  - `@Transactional(readOnly = true) public List<RefRepo> resolveReferences(Long targetCatalogId, List<RefRepoInput> inputs)`
    - null이나 빈 목록이면 `List.of()`.
    - `checkRefShape` → 항목마다 `resolveForRegistration` → 브랜치를 정한다(blank면 카탈로그 기본, 그것도 없으면 400 `참조 브랜치를 지정하세요`).
    - 결과는 `new RefRepo(catalogId, alias, ownerRepo, gitUrl, branch, null, null, null)`이고 입력 순서를 지킨다.

- [ ] **Step 1: 실패하는 테스트 작성** (`RepoCatalogServiceTest`)
  - 카탈로그 행은 이 클래스의 기존 방식(`RepoCatalogEntry.create(...)` + `repo.findById` 목, 서비스는 `"https://gitlab.hamon.vip"`로 생성)으로 만든다.
  - 행은 id 10(`main`), 11(`release/6.5` 기본), 12(기본 브랜치 null) 셋. 대상은 1.

```java
@Test void resolveReferences_null_or_empty_is_empty() {
    assertThat(service.resolveReferences(1L, null)).isEmpty();
    assertThat(service.resolveReferences(1L, List.of())).isEmpty();
}
@Test void resolveReferences_rejects_more_than_three() {
    assertThatThrownBy(() -> service.resolveReferences(1L, List.of(in(10), in(11), in(12), in(13))))
            .isInstanceOf(TaskException.class).hasMessage("참조 repo는 최대 3개까지 고를 수 있습니다");
}
@Test void resolveReferences_rejects_the_target() {
    assertThatThrownBy(() -> service.resolveReferences(10L, List.of(in(10))))
            .hasMessage("대상 레포는 참조로 고를 수 없습니다");
}
@Test void resolveReferences_rejects_duplicates() {
    assertThatThrownBy(() -> service.resolveReferences(1L, List.of(in(10), new RefRepoInput(10L, "dev"))))
            .hasMessage("같은 레포를 참조로 두 번 고를 수 없습니다");
}
@Test void resolveReferences_blank_branch_uses_catalog_default() {
    assertThat(service.resolveReferences(1L, List.of(new RefRepoInput(11L, " "))).get(0).branch()).isEqualTo("release/6.5");
}
@Test void resolveReferences_blank_branch_without_default_is_400() {
    assertThatThrownBy(() -> service.resolveReferences(1L, List.of(new RefRepoInput(12L, null))))
            .hasMessage("참조 브랜치를 지정하세요");
}
@Test void resolveReferences_rejects_unknown_or_disabled_catalog() { /* resolveForRegistration과 같은 400 */ }
@Test void resolveReferences_snapshots_alias_path_url_and_branch_in_input_order() {
    List<RefRepo> r = service.resolveReferences(1L, List.of(new RefRepoInput(11L, "release/6.5"), in(10)));
    assertThat(r).extracting(RefRepo::catalogId).containsExactly(11L, 10L);
    assertThat(r.get(0).sha()).isNull();
}
// in(id) = new RefRepoInput(id, "main")
```

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.RepoCatalogServiceTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현** — 위 Interfaces대로. `checkRefShape`는 null 원소를 뺀 입력으로 판정한다.
- [ ] **Step 4: 통과 확인** — 같은 명령, PASS
- [ ] **Step 5: 커밋** — `feat(question): 참조 repo 검증·해석`

### Task 3: 질문 등록·대화 중 변경·상세 응답

**Files:**
- Modify: `…/dto/QuestionCreateRequest.java`: 마지막에 `List<RefRepoInput> refRepos`를 더하고, 기존 7인자 생성자를 유지하며 8인자 생성자를 추가한다.
- Modify: `…/dto/QuestionAskRequest.java`: 마지막에 `List<RefRepoInput> refRepos`를 더하고, 기존 4·5인자 생성자를 유지하며 6인자 생성자를 추가한다.
- Modify: `…/dto/InterviewResponse.java`: `List<RefRepoView> refRepos`를 마지막 구성요소로 넣고, `withDbConnections`·`of`가 이를 전달하게 한다.
- Modify: `…/service/QuestionService.java:94-117,174-195`: 등록 때 스냅샷 저장, `applyRefChange`, `AskResult.refNote`.
- Modify: `…/controller/QuestionController.java:116-125`: `refNote`도 `pushNote`로 보낸다.
- Test: `…/service/QuestionServiceTest.java`, `…/dto/InterviewResponseTest.java`

**Interfaces:**
- Consumes: `RepoCatalogService.checkRefShape`·`resolveReferences`(Task 2), `RefRepo.matches`·`label`(Task 1), `InterviewService.appendSystemNote`(기존).
- Produces:
  - `InterviewResponse.RefRepoView(Long catalogId, String alias, String branch, String sha, String fetchedAt, String error)`. `refRepos()`는 항상 non-null이다.
  - `QuestionService.AskResult(InterviewSession session, InterviewTurn mcpNote, InterviewTurn dbNote, InterviewTurn refNote)`. 기존 2·3인자 생성자는 유지한다.

- [ ] **Step 1: 실패하는 테스트 작성** (`QuestionServiceTest`; `repoCatalog`는 이미 목이다)

```java
@Test void create_saves_reference_snapshots_resolved_against_the_target() {
    when(repoCatalog.resolveReferences(eq(1L), any())).thenReturn(List.of(ref(11L, "release/6.5")));
    InterviewSession s = service.create(createReq(List.of(new RefRepoInput(11L, "release/6.5"))), "u1");
    assertThat(s.getRefRepos()).extracting(RefRepo::catalogId).containsExactly(11L);
}
@Test void create_reference_failure_creates_no_session() {
    when(repoCatalog.resolveReferences(any(), any())).thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "x"));
    assertThatThrownBy(() -> service.create(createReq(List.of(new RefRepoInput(11L, "main"))), "u1"));
    verify(sessionRepo, never()).save(any());
}
@Test void ask_with_null_refs_keeps_current_selection()   // refRepos=null → 세션 refRepos 그대로, 참조 메모 없음
@Test void ask_with_same_ref_set_in_any_order_is_a_noop_without_resolving() {
    // 세션 [11 main, 12 dev], 입력 [12 dev, 11 main]
    verify(repoCatalog, never()).resolveReferences(any(), any());
    assertThat(result.refNote()).isNull();
}
@Test void ask_keeps_state_of_retained_refs_and_resolves_only_added_ones() {
    // 세션 [11 main sha=a…], 입력 [11 main, 12 master]
    verify(repoCatalog).resolveReferences(1L, List.of(new RefRepoInput(12L, "master")));
    assertThat(session.getRefRepos().get(0).sha()).isEqualTo("a".repeat(40));
    verify(interviewService).appendSystemNote(5L, "참조 repo 변경: ＋ 배치 서버(master)");
}
@Test void ask_note_lists_additions_then_removals() {
    // 세션 [11 main, 12 dev], 입력 [11 main, 13 master] → "참조 repo 변경: ＋ C(master), − B(dev)"
}
@Test void ask_branch_change_counts_as_remove_plus_add_and_clears_state()
    // 세션 [11 main sha=a…], 입력 [11 dev] → resolveReferences(1L, [11 dev]) 호출, 결과 sha null,
    // 메모 "참조 repo 변경: ＋ 옛앱(dev), − 옛앱(main)"
@Test void ask_empty_list_clears_all_and_notes_it() {
    verify(interviewService).appendSystemNote(5L, "참조 repo 변경: 없음(전부 해제)");
}
@Test void ask_over_cap_or_target_is_400() // checkRefShape 위반이 그대로 던져짐(트랜잭션 롤백은 @Transactional이 담당)
```

  - `InterviewResponseTest`: `of()`가 세션의 `refRepos`를 `RefRepoView`로 옮기는지, 비어 있으면 `[]`인지, `withDbConnections`가 `refRepos`를 유지하는지 확인한다.

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.QuestionServiceTest" --tests "com.hamonsoft.netismaker.dto.InterviewResponseTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - **create**: `dbIds` 검증 다음, 세션 생성 전에 `List<RefRepo> refs = repoCatalogService.resolveReferences(repo.catalogId(), req.refRepos())`를 부르고 `s.setRefRepos(new ArrayList<>(refs))`.
  - **ask**: `applyDbChange` 다음에 `InterviewTurn refNote = applyRefChange(s, req.refRepos())`.
  - **`applyRefChange` 알고리즘**(시그니처로 정해지지 않는 부분):

    ```
    inputs == null → return null
    kept = []; added = []
    입력마다: 현재 원소 중 r.matches(in.repoCatalogId, in.branch)인 첫 원소 → 있으면 kept에(현재 원소 그대로), 없으면 added에
    added가 비고 kept.size() == current.size() → return null            // 같은 집합 = 검증 없는 no-op
    RepoCatalogService.checkRefShape(s.getRepoCatalogId(), inputs)      // 결과 기준 상한·중복·대상
    resolved = repoCatalogService.resolveReferences(s.getRepoCatalogId(), added)  // 새로 들어오는 짝만 검증
    next = 입력 순서대로 kept/resolved를 이어 붙임;  removed = current - kept
    s.setRefRepos(next)
    메모 = next가 비면 "참조 repo 변경: 없음(전부 해제)",
           아니면 "참조 repo 변경: " + join(", ", ["＋ " + label for resolved] + ["− " + label for removed])
    return interviewService.appendSystemNote(s.getId(), 메모)
    ```

  - **컨트롤러** `doAsk`: `result.refNote()`가 있으면 `interviewStream.pushNote(id, seq, content)`.
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령 + `--tests "com.hamonsoft.netismaker.controller.QuestionApiIntegrationTest"`(Testcontainers면 skip)
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(question): 질문 등록·대화 중 참조 repo 선택`

### Task 4: claim에 참조 싣기·답변 보고 반영

**Files:**
- Modify: `…/dto/InterviewClaimResponse.java`: 마지막 구성요소 `List<ClaimRefRepo> refRepos`(compact 생성자에서 null → `[]`). `of`는 `List.of()`, `withDb`는 그대로 전달하고, `withRefRepos`를 새로 만든다.
- Modify: `…/dto/WorkerQuestionRequest.java`: 마지막에 `List<RefState> refStates`. 기존 10인자 생성자는 유지한다.
- Modify: `…/service/InterviewService.java`: `claim`(`:150-171`), `recordQuestion`(`:232-257`).
- Test: `…/service/InterviewServiceTest.java`, `…/dto/InterviewClaimResponseTest.java`

**Interfaces:**
- Consumes: `InterviewWorkPaths.refDir`(Task 1), `RefRepo.matches`·`pinned`·`withError`(Task 1).
- Produces — JSON 필드 이름이 곧 TS 계약이다(Task 6·8이 쓴다):
  - `InterviewClaimResponse.ClaimRefRepo(Long catalogId, String alias, String githubRepo, String gitUrl, String repoHost, String branch, String dir, String sha)`
    - `repoHost`는 `RepoRef.fromSnapshot(path, gitUrl).host()`.
    - QUESTION이 아니면 `refRepos`는 `[]`.
  - `InterviewClaimResponse withRefRepos(List<ClaimRefRepo> refs)`
  - `WorkerQuestionRequest.RefState(Long catalogId, String branch, String sha, String error)`

- [ ] **Step 1: 실패하는 테스트 작성** (`InterviewServiceTest`; 세션 준비는 기존 claim 테스트 방식)

```java
@Test void claim_question_session_carries_reference_dirs_under_the_refs_root() {
    // QUESTION 세션 id=1, refRepos [11 release/6.5 (gitlab url), 12 main sha="c"*40]
    InterviewClaimResponse c = service.claim("w1").orElseThrow();
    String root = System.getProperty("user.home") + "/netis-maker/interviews";
    assertThat(c.refRepos()).extracting(InterviewClaimResponse.ClaimRefRepo::dir)
            .containsExactly(root + "/_refs/1/11", root + "/_refs/1/12");
    assertThat(c.refRepos().get(0).repoHost()).isEqualTo("gitlab");
    assertThat(c.refRepos().get(1).sha()).isEqualTo("c".repeat(40));
}
@Test void claim_interview_session_has_no_refs()   // INTERVIEW 세션은 refRepos에 값이 있어도 c.refRepos() 비어 있음
@Test void recordQuestion_pins_sha_only_once() {
    // 상태 [11 main sha=null] → 보고 (11, main, sha=a…, null) → sha=a…, fetchedAt != null
    // 다시 보고 (11, main, sha=b…) → 여전히 a…
}
@Test void recordQuestion_records_and_then_clears_errors()
    // (11, main, null, "권한 없음") → error 기록 / 이어서 (11, main, a…, null) → error null, sha a…
@Test void recordQuestion_ignores_states_for_refs_no_longer_selected()   // 상태 [11 main], 보고 (12, main, …) → 변화 없음
@Test void recordQuestion_without_ref_states_leaves_refs_untouched()   // 10인자 생성자(구버전 러너)
```

  - `InterviewClaimResponseTest`: `withDb(...)`가 `refRepos`를 유지하는지, JSON 직렬화에 `refRepos` 배열이 있는지 확인한다.

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.dto.InterviewClaimResponseTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현**
  - **claim**: `InterviewClaimResponse.of(...)` 뒤에 `.withRefRepos(refsFor(s))`를 붙인다. `refsFor`는 질문 세션의 `refRepos`를 `ClaimRefRepo`로 바꾼다(`dir` = `workPaths.refDir(s.getId(), r.catalogId())`). `InterviewWorkerController.withDb`는 DTO의 `withDb`가 `refRepos`를 전달하므로 코드를 바꾸지 않는다.
  - **recordQuestion**: 사용량 반영 다음에 `applyRefStates(s, req.refStates())`.
    - 상태마다 현재 원소 중 `matches(catalogId, branch)`인 것에 적용한다.
    - `sha`가 있으면 `pinned(sha, OffsetDateTime.now().toString())`, `error`가 있으면 `withError(error)`.
    - 맞는 원소가 없으면 무시한다.
    - 바뀐 게 있을 때만 `setRefRepos`.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest"`, PASS
- [ ] **Step 5: 커밋** — `feat(question): claim에 참조 repo 경로·보고로 커밋 고정`

### Task 5: 트리 삭제 공용화·체크아웃 정리 잡

**Files:**
- Create: `…/util/FileTrees.java` — `RepoCacheCleanupJob.deleteTree`·`deleteEntry`(`workerdaemon/RepoCacheCleanupJob.java:461-522`)의 본문을 옮긴다.
- Modify: `…/workerdaemon/RepoCacheCleanupJob.java` — `deleteTree`는 `FileTrees.deleteTree`에 위임하고 기존 `DeleteResult`로 바꿔 돌려준다. 호출부 5곳(`ArtifactPublisher`·`ImplementationScratch`·`TaskCloser`·`WorkerMainLoop`·`WorktreeService`)은 그대로 둔다.
- Create: `…/service/InterviewCheckoutCleanupPlanner.java`, `…/service/InterviewCheckoutCleanupJob.java`
- Modify: `src/main/resources/application.yml` — `app.interview.checkout-cleanup` 블록.
- Modify: `src/test/java/com/hamonsoft/netismaker/TestcontainersConfig.java` — `:41` 옆에 `"app.interview.checkout-cleanup.enabled=false"`.
- Test: `…/util/FileTreesTest.java`, `…/service/InterviewCheckoutCleanupPlannerTest.java`, `…/service/InterviewCheckoutCleanupJobTest.java`

**Interfaces:**
- Produces:
  - `FileTrees.DeleteResult(long freedBytes, int failures)`, `static DeleteResult deleteTree(Path root)` — 동작은 옮겨 온 것과 같다.
  - `InterviewCheckoutCleanupPlanner` (`AttachmentCleanupPlanner`와 같은 모양)
    - `enum Kind { TARGET, REFS }`, `record CheckoutDir(Path path, Kind kind, long sessionId, Instant modifiedAt)`
    - `static Optional<CheckoutDir> parseTarget(Path dir, Instant mtime)` — 이름이 `session-([1-9][0-9]{0,17})`일 때만.
    - `static Optional<CheckoutDir> parseRefs(Path dir, Instant mtime)` — `_refs` 바로 아래, 이름이 `([1-9][0-9]{0,17})`일 때만.
    - `record SessionRow(InterviewStatus status, OffsetDateTime lastActivityAt)`, `record Policy(Duration retention)`
    - `static final Set<InterviewStatus> CLOSED = EnumSet.of(REGISTERED, CANCELLED, EXPIRED, FAILED)`
    - `enum Verdict { DELETE_CLOSED, DELETE_ORPHAN, KEEP_OPEN, KEEP_RETENTION, KEEP_ORPHAN_RECENT, KEEP_ORPHAN_UNALLOCATED }` — 각 값은 `delete()`와 `label()`을 가진다.
    - `static Verdict decide(CheckoutDir d, Map<Long, SessionRow> rows, Long maxSessionId, Instant now, Policy p)`
      - 행이 없는 고아는 `id ≤ maxSessionId`이고 폴더 수정 시각이 보존 기간보다 오래됐을 때만 지운다. 첨부 정리의 미할당 id 가드와 같다.
  - `@Component @Profile("api") InterviewCheckoutCleanupJob(InterviewWorkPaths paths, InterviewSessionRepository sessionRepo, @Value("${app.interview.checkout-cleanup.enabled:true}") boolean enabled, @Value("${app.interview.checkout-cleanup.retention-days:7}") int retentionDays)`
    - `retentionDays < 1`이면 `IllegalArgumentException`.
    - `@Scheduled(cron = "${app.interview.checkout-cleanup.cron:0 50 3 * * *}") void cleanup()` — 예외는 로그만 남기고 그 회차를 건너뛴다.
    - `Summary runOnce(Instant now) throws IOException`(패키지 공개), `record Summary(int deleted, long freedBytes, int kept, int failed)`
    - 스캔 범위: `{루트}/{a}/{b}/session-*`(첫 단계가 `_refs`면 제외)와 `{루트}/_refs/*`. 속성은 `NOFOLLOW_LINKS`로 읽고 링크·정션(`isOther`)은 건너뛴다.
    - 조회: 기존 `sessionRepo.findAttachmentOwnerStates(chunk)`(500개씩)와 `findMaxId()`를 재사용한다.
    - 삭제: 실제 경로가 `paths.root()`의 실제 경로 안일 때만 `FileTrees.deleteTree`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// InterviewCheckoutCleanupPlannerTest
@ParameterizedTest @EnumSource(value = InterviewStatus.class, names = {"REGISTERED","CANCELLED","EXPIRED","FAILED"})
void closed_and_older_than_retention_is_deleted(InterviewStatus st)          // → DELETE_CLOSED
@Test void closed_within_retention_is_kept()                                   // → KEEP_RETENTION
@ParameterizedTest @EnumSource(value = InterviewStatus.class, names = {"QUEUED","RUNNING","AWAITING_INPUT","PLAN_READY"})
void keeps_open_sessions_even_when_old(InterviewStatus st)                    // last_activity 30일 전 → KEEP_OPEN
@Test void orphan_is_deleted_only_when_old_and_id_not_above_max()             // DELETE_ORPHAN / KEEP_ORPHAN_RECENT / KEEP_ORPHAN_UNALLOCATED
@Test void parse_ignores_non_canonical_names()                                // "session-007", "session-1x", "abc", "0"

// InterviewCheckoutCleanupJobTest (@TempDir root, sessionRepo 목)
@Test void deletes_target_and_refs_of_an_old_closed_session() {
    // root/_gitlab/g+p/session-5/x.txt, root/_refs/5/12/y.txt; 행 {5: CANCELLED, 10일 전}, maxId 9
    // → 두 폴더 모두 없음, root/_refs 자체는 남음
}
@Test void keeps_folders_of_open_sessions()   // 행 {5: AWAITING_INPUT, 30일 전} → 두 폴더 남음
@Test void db_failure_deletes_nothing() // findAttachmentOwnerStates가 던짐 → cleanup()은 예외 없이 끝나고 폴더는 그대로
@Test void rejects_retention_below_one() // 생성자 IllegalArgumentException
@Test @EnabledOnOs(OS.WINDOWS) void does_not_follow_a_junction_out_of_the_root()
    // root/_refs/5/12 = 루트 밖 폴더로 가는 정션(AttachmentStorageTest:365-375의 mklink /J 방식, 실패하면 assumeTrue로 skip) → 루트 밖 파일은 남음

// FileTreesTest
@Test void deletes_a_tree_including_read_only_files()   // 읽기 전용 파일이 든 트리 → 루트까지 사라지고 failures 0
@Test @EnabledOnOs(OS.WINDOWS) void does_not_follow_a_junction()   // 트리 안 정션 → 정션만 지워지고 대상 폴더 파일은 남음
```

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew test --tests "com.hamonsoft.netismaker.util.FileTreesTest" --tests "com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlannerTest" --tests "com.hamonsoft.netismaker.service.InterviewCheckoutCleanupJobTest"`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현** — 위 Interfaces대로. 요약 로그 문구는 `AttachmentCleanupJob`을 따른다: 삭제 n개, 해제 용량, 유지 n개(사유별), 실패 n개.
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령 + `--tests "com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJobTest"`(위임 회귀 확인)
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(interview): 끝난 세션 체크아웃 정리 잡(7일)`

### Task 6: 인터뷰 서비스 — 참조 준비 `ensureRefRepos`

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`: `RefRepoClaim`, `RefState` 추가. `InterviewClaimResponse.refRepos?: RefRepoClaim[]`, `WorkerQuestionRequest.refStates?: RefState[]`.
- Modify: `netismaker-interview-service/src/runner/repoPrepare.ts`
- Test: `netismaker-interview-service/test/repoPrepare.test.ts`

**Interfaces:**
- Consumes: Task 4의 JSON 계약.
- Produces:
  - `interface RefRepoClaim { catalogId: number; alias: string; githubRepo: string; gitUrl?: string | null; repoHost?: 'github' | 'gitlab' | null; branch: string; dir: string; sha?: string | null }`
  - `interface RefState { catalogId: number; branch: string; sha: string | null; error: string | null }`
  - `interface RefPrepared { ref: RefRepoClaim; sha: string | null; error: string | null }` — `error === null`이면 사용 가능.
  - `interface RefRepoOps extends RepoOps { output: (cmd: string, args: string[], opts: { cwd?: string; signal?: AbortSignal }) => Promise<string>; rm: (path: string) => void; rename: (from: string, to: string) => void }`
  - `export async function ensureRefRepos(refs: RefRepoClaim[], opts: { tokens?: GitTokens; signal?: AbortSignal }, ops?: RefRepoOps): Promise<RefPrepared[]>`

- [ ] **Step 1: 실패하는 테스트 작성** (`repoPrepare.test.ts`; 가짜 ops — `run`·`output`·`exists`·`rm`·`rename`은 `vi.fn`)

```ts
describe('ensureRefRepos', () => {
  it('clones a missing ref into <dir>.part, scrubs origin, then renames into place', async () => {
    // exists → false; output('rev-parse HEAD') → 'a'*40
    // run 호출: clone --depth 1 --branch release/6.5 <auth url> /r/_refs/5/12.part → remote set-url origin <plain> (cwd .part)
    // rename('/r/_refs/5/12.part', '/r/_refs/5/12'); 결과 [{sha: 'a'*40, error: null}]
  });
  it('reuses an existing checkout on the same branch without fetching', async () => {
    // exists(.git) → true; abbrev-ref HEAD → 'release/6.5' → clone·fetch 호출 없음, sha = rev-parse HEAD
  });
  it('re-clones when the existing checkout is on a different branch')  // rm(dir) 후 clone
  it('returns a masked error and leaves no folder when the clone fails', async () => {
    // clone이 'fatal: ... https://oauth2:glpat-SECRET@gitlab…' 로 실패 → error에 'glpat-SECRET' 없음, '***@' 있음
    // rm('<dir>.part') 호출, rename 호출 없음
  });
  it('passes the abort signal to every git call')            // 모든 run·output 호출의 opts.signal === 넘긴 signal
  it('prepares refs one after another in the given order')   // 두 번째 clone은 첫 번째 rename 뒤, 결과 순서 = 입력 순서
});
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd netismaker-interview-service && npx vitest run test/repoPrepare.test.ts`
  - Expected: FAIL(`ensureRefRepos` 없음)
- [ ] **Step 3: 구현**
  - 참조마다 순서대로 처리한다.
    1. `.git`이 있고 `git rev-parse --abbrev-ref HEAD`가 브랜치와 같으면 그대로 쓰고, `sha`는 `git rev-parse HEAD`.
    2. 아니면 `rm(dir)` → `rm(dir + '.part')` → `<dir>.part`로 clone → origin을 평문 URL로 → `rename` → `sha`.
  - URL은 기존 `buildCloneUrl`로 만들고, 오류는 `maskSecrets`로 가린다.
  - 실패하면 `rm(part)`(best effort) 후 `{ sha: null, error }`.
  - `ref.sha`가 있는데 새로 받은 `sha`와 다르면 `console.warn`만 남긴다. 고정 커밋 재현은 하지 않는다(스펙 §5.1의 단순화 허용).
  - 기본 ops는 `execFile`(output은 stdout), `fs.rmSync(path, { recursive: true, force: true })`, `fs.renameSync`.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `npx tsc -p tsconfig.json --noEmit`, PASS
- [ ] **Step 5: 커밋** — `feat(interview-service): 참조 repo 준비(첫 턴에 받아 고정)`

### Task 7: 질문 게이트 — 참조 폴더 허용 루트

**Files:**
- Modify: `netismaker-interview-service/src/sdk/permissions.ts`: `buildCanUseTool`의 5번째 인자, `questionReadGate`, `questionPathScopedGate`, `questionGlobGate`.
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`: `SessionOptionsInput.refRoots?: string[]`을 `buildCanUseTool`에 넘긴다. SDK 옵션에는 싣지 않는다.
- Test: `netismaker-interview-service/test/permissions.test.ts`, `test/sessionOptions.test.ts`

**Interfaces:**
- Produces: `buildCanUseTool(repoDir: string, kind?: SessionKind, attachmentRoot?: string | null, dbServers?: Record<string, SqlDialect>, refRoots?: string[]): CanUseTool` — `refRoots` 기본값은 `[]`.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
describe('canUseTool — QUESTION with reference roots', () => {
  const q = buildCanUseTool('/tmp/repo', 'QUESTION', null, {}, ['/tmp/refs/5/12']);
  it('allows Read inside a reference root', async () =>
    expect((await q('Read', { file_path: '/tmp/refs/5/12/src/A.java' })).behavior).toBe('allow'));
  it('allows Grep and Glob with a path inside a reference root')     // Grep {path:'/tmp/refs/5/12/src'}, Glob {path:'/tmp/refs/5/12', pattern:'**/*.java'}
  it('still denies Glob patterns that are absolute or climb out')    // {path:'/tmp/refs/5/12', pattern:'../**'} → deny
  it('denies paths outside every root')                              // '/etc/hosts' → deny
  it('denies sibling folders that share a name prefix')              // '/tmp/refs/5/120/x', '/tmp/refs/50/x' → deny
  it('keeps Bash confined to the target checkout')                   // 'cat /tmp/refs/5/12/A.java' → deny
  it('INTERVIEW gate is unchanged by refRoots')                      // Write '/tmp/refs/5/12/x' → deny(docs/superpowers 밖)
});
describe.runIf(process.platform === 'win32')('reference roots on Windows', () => {
  it('allows ref paths in either separator and drive-letter case')  // 실제 임시 폴더: root 'C:\\…\\refs\\5\\12', 'c:/…/refs/5/12/A.java' 허용
});
describe.skipIf(!fileSymlinks)('symlink inside a reference root pointing outside', () => {
  it('denies Read through the link')                                 // 기존 (c) 블록과 같은 방식
});
// sessionOptions.test.ts
it('passes refRoots to the QUESTION gate', async () => {
  const o = buildOptions({ ...base, sessionKind: 'QUESTION', refRoots: ['/tmp/refs/5/12'] });
  expect((await (o.canUseTool as CanUseTool)('Read', { file_path: '/tmp/refs/5/12/a' })).behavior).toBe('allow');
  expect(o).not.toHaveProperty('refRoots');
});
```

- [ ] **Step 2: 실패 확인**
  - Run: `npx vitest run test/permissions.test.ts test/sessionOptions.test.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - Read: 대상 → `attachmentRoot` → `refRoots` 순으로 `insideRepoReal(root, fp)` 중 하나라도 참이면 허용.
  - Grep·Glob의 `path`: 대상 또는 `refRoots` 중 하나면 허용. `~` 금지와 Glob pattern 규칙은 그대로.
  - 거부 문구는 기존 문구에 "또는 참조 repo"를 더한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `npx tsc -p tsconfig.json --noEmit`, PASS
- [ ] **Step 5: 커밋** — `feat(interview-service): 질문 게이트에 참조 repo 읽기 루트`

### Task 8: 인터뷰 서비스 — 질문 턴에 참조 연결

**Files:**
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Modify: `netismaker-interview-service/test/fixtures/claims.ts` — `questionClaimWithRefs` 추가.
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `ensureRefRepos`, `RefPrepared`(Task 6), `buildOptions({ refRoots })`(Task 7).
- Produces:
  - `RunnerDeps.ensureRefRepos?: (refs: RefRepoClaim[], opts: { tokens?: GitTokens; signal?: AbortSignal }) => Promise<RefPrepared[]>` — 테스트 주입용, 기본값은 Task 6의 함수.
  - `export function refSection(prepared: RefPrepared[]): string` — 빈 배열이면 `''`. 형식(끝에 빈 줄 하나):

    ```
    참조 repo (읽기 전용 — 비교·참고용, 수정 금지):
    - {alias} · {branch}@{sha 앞 7자} : {dir}
    사용할 수 없는 참조: {alias}({branch}) — {error}
    규칙:
    - 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로를 Read/Grep/Glob의 경로로 주어 읽는다(셸 명령은 대상 안에서만 동작한다).
    - 참조 파일 근거는 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`), 대상 파일은 `경로:줄`로 적는다.
    - 사용할 수 없는 참조의 내용은 추측하지 말고 못 읽었다고 답한다.
    ```

    - 사용 가능한 참조가 없으면 첫 줄과 `- …` 줄을 뺀다.
    - 실패한 참조가 없으면 "사용할 수 없는 참조" 줄을 뺀다.

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
it('question claim without refRepos never prepares refs and keeps the kickoff prompt unchanged', async () => {
  // questionClaim(refRepos 없음)과 { ...questionClaim, refRepos: [] }의 프롬프트가 같고 ensureRefRepos는 호출 안 됨
});
it('question claim with refs: prepares them, prefixes the ref section, lets the gate read them, and reports refStates', async () => {
  // ensureRefRepos 가짜 → [{ref:{catalogId:12, alias:'옛앱', branch:'release/6.5', dir:'/r/_refs/5/12', …}, sha:'a'*40, error:null}]
  // 프롬프트에 '옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12' 포함
  // captured.options.canUseTool('Read', {file_path:'/r/_refs/5/12/A.java'}) → allow
  // postQuestion 바디에 refStates: [{catalogId:12, branch:'release/6.5', sha:'a'*40, error:null}]
});
it('ref prepare failure still answers and reports the masked error', async () => {
  // 가짜 → [{…, sha:null, error:'권한 없음'}] → 프롬프트에 '사용할 수 없는 참조: 옛앱(release/6.5) — 권한 없음'
  // canUseTool로 그 dir Read → deny, postQuestion 호출됨(세션 실패 아님), refStates error 그대로
});
it('resume turn re-sends the ref section')            // claudeSessionId가 있는 resume 질문 claim에서도 접두에 참조 절
it('interview (non-question) claims ignore refRepos')  // kind 없음 + refRepos 값 → ensureRefRepos 미호출, postQuestion에 refStates 없음
it('refSection returns an empty string for no refs')   // refSection([]) === ''
```

- [ ] **Step 2: 실패 확인**
  - Run: `npx vitest run test/interviewRunner.test.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - `runQuestionTurn` 시작에서, `claim.refRepos`가 비어 있지 않은 배열일 때만 `ensureRefRepos(claim.refRepos, { tokens: this.deps.gitTokens, signal: controller.signal })`를 부른다. 그 뒤 `controller.signal.throwIfAborted()`.
  - 프롬프트 접두는 `refSection(prepared) + dbSection(db)`.
  - `buildOptions`에 `refRoots: prepared.filter(p => p.error === null).map(p => p.ref.dir)`.
  - `postQuestion` 바디에는 `prepared`가 있을 때만 `refStates`를 싣는다.
  - "환경 준비" 활동(`runTurn`의 `poster.push`) detail에 참조가 있으면 ` + 참조 {n}`을 덧붙인다.
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 + `npx tsc -p tsconfig.json --noEmit`, PASS
- [ ] **Step 5: 커밋** — `feat(interview-service): 질문 턴에 참조 repo 연결`

### Task 9: 프론트 — 참조 칩·추가 폼 컴포넌트

**Files:**
- Create: `frontend/composables/refRepos.ts`, `frontend/components/RefRepoChips.vue`, `frontend/components/RefRepoAddForm.vue`
- Test: `frontend/composables/refRepos.spec.ts`, `frontend/test/ref-repo-chips.spec.ts`, `frontend/test/ref-repo-add-form.spec.ts`

**Interfaces:**
- Produces (`composables/refRepos.ts`):
  - `MAX_REF_REPOS = 3`
  - `interface RefRepoView { catalogId: number; alias: string; branch: string; sha?: string | null; fetchedAt?: string | null; error?: string | null }`
  - `interface RefRepoInput { repoCatalogId: number; branch: string }`
  - `interface RepoCatalogOption { id: number; alias: string; host: string; defaultBranch: string | null }`
  - `refChipLabel(r: RefRepoView): string` — `📚 {alias} · {branch}`, `sha`가 있으면 `@{앞 7자}`.
  - `refState(r: RefRepoView): 'pinned' | 'pending' | 'failed'` — `error`가 우선, 그다음 `sha`.
  - `toInputs(refs: RefRepoView[]): RefRepoInput[]`
  - `sameRefSet(a: RefRepoInput[], b: RefRepoInput[]): boolean` — 순서는 무시하고 브랜치는 구분한다.
- Produces (컴포넌트):
  - `RefRepoChips` — props `{ items: RefRepoView[]; editable?: boolean; max?: number }`, emits `remove(catalogId: number)`·`add()`.
    - data-test: 루트 `ref-repo-chips`, 칩 `ref-chip`, 추가 `ref-add`.
    - 비어 있으면 `참조 repo 없음`.
  - `RefRepoAddForm` — props `{ catalog: RepoCatalogOption[]; excludeIds: number[] }`, emits `add(ref: RefRepoView)`.
    - data-test: `ref-add-repo`, `ref-add-branch`, `ref-add-submit`.
    - 루트는 `<div>`(q-dialog·q-menu 안 규칙).

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// refRepos.spec.ts
expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5' })).toBe('📚 옛앱 · release/6.5@a1b2c3d')
expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'main' })).toBe('📚 옛앱 · main')
expect(refState({ catalogId: 1, alias: 'a', branch: 'b', sha: 'x', error: '권한 없음' })).toBe('failed')
expect(sameRefSet([{ repoCatalogId: 1, branch: 'a' }, { repoCatalogId: 2, branch: 'b' }],
                  [{ repoCatalogId: 2, branch: 'b' }, { repoCatalogId: 1, branch: 'a' }])).toBe(true)
expect(sameRefSet([{ repoCatalogId: 1, branch: 'a' }], [{ repoCatalogId: 1, branch: 'b' }])).toBe(false)

// ref-repo-chips.spec.ts
it('라벨과 상태별 칩을 그린다')  // '참조 repo · 읽기 전용 · 최대 3개', 대기 칩에 '다음 답변 때 받음', 실패 칩에 ⚠
it('고정 칩 툴팁에 고정 커밋과 기준 문구가 있다')  // '고정 커밋 a1b2c3d' · '대화 끝까지 이 커밋 기준'
it('편집 모드에서만 ✕와 ＋ 참조 추가가 보이고, 3개면 추가가 사라진다')
it('비어 있으면 참조 repo 없음')

// ref-repo-add-form.spec.ts (useApiMock: '/api/repos/branches' → {defaultBranch:'develop', branches:[…]})
it('제외 목록과 other 호스트를 뺀 레포만 고를 수 있다')
it('레포를 고르면 브랜치를 불러와 카탈로그 기본, 없으면 응답 기본으로 채운다')
it('먼저 고른 레포의 늦은 응답은 브랜치를 덮지 않는다')
it('추가를 누르면 catalogId·alias·branch로 add를 보낸다')
```

- [ ] **Step 2: 실패 확인**
  - Run: `cd frontend && npx vitest run composables/refRepos.spec.ts test/ref-repo-chips.spec.ts test/ref-repo-add-form.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - 브랜치 조회는 `useApi('/api/repos/branches', { params: { catalogId } })`(새 질문 화면 `loadBranches`와 같은 호출)로 한다.
  - 칩 색은 DB 칩(teal)·첨부(blue-grey)와 구별되는 deep-purple 계열.
  - 반응형 헬퍼 이름(`xs`·`md` 등)을 클래스로 쓰지 않는다.
- [ ] **Step 4: 통과 확인** — 같은 명령, PASS
- [ ] **Step 5: 커밋** — `feat(frontend): 참조 repo 칩·추가 폼`

### Task 10: 프론트 — 새 질문 화면 참조 줄

**Files:**
- Modify: `frontend/pages/questions/index.vue` (`draft`, `#top` 슬롯, `onRepoSelected`, `submit`)
- Test: `frontend/test/questions-index.spec.ts`

**Interfaces:** Consumes Task 9의 전부.

- [ ] **Step 1: 실패하는 테스트 작성** (기존 `stubApi` 사용. 레포 카탈로그에 `{id:2, alias:'옛앱', host:'gitlab', defaultBranch:'release/6.5'}` 추가)

```ts
it('참조를 하나 고르면 등록 바디에 refRepos가 실린다', async () => {
  // draft를 채우고 (w.vm as any).draft.refRepos.push({ catalogId: 2, alias: '옛앱', branch: 'release/6.5' })
  // → POST /api/questions 바디에 refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }]
});
it('대상을 참조 중 하나로 바꾸면 그 참조만 빠진다', async () => {
  // refRepos [2, 3] 상태에서 onRepoSelected(2) → draft.refRepos의 catalogId는 [3]
});
it('참조 칩 줄은 대상 레포·브랜치 선택 아래에 있고 3개면 ＋ 참조 추가가 사라진다')
// 기존 '제목 없이 레포/브랜치/질문/모델/effort/MCP로 POST…'(정확한 바디)는 수정 없이 통과해야 한다
```

- [ ] **Step 2: 실패 확인**
  - Run: `npx vitest run test/questions-index.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - `draft.refRepos: RefRepoView[]`
  - `#top`의 `row` 아래에 `RefRepoChips`(editable)를 둔다. `＋ 참조 추가`를 누르면 그 아래에 `RefRepoAddForm`을 펼친다(제외: 대상 + 이미 고른 것). 추가하면 접는다.
  - `onRepoSelected`에서 같은 catalogId의 참조를 뺀다.
  - `submit`의 `meta`에는 `draft.refRepos.length > 0`일 때만 `refRepos: toInputs(draft.refRepos)`를 넣는다.
- [ ] **Step 4: 통과 확인** — 같은 명령, PASS(기존 테스트 포함)
- [ ] **Step 5: 커밋** — `feat(frontend): 새 질문 화면에서 참조 repo 선택`

### Task 11: 프론트 — 대화 화면 참조 줄·참조 변경

**Files:**
- Modify: `frontend/composables/questions.ts` (`QuestionDetail.refRepos?: RefRepoView[]`)
- Modify: `frontend/pages/questions/[id].vue` (칩 줄, `✎ 참조 변경` 메뉴, `pickedRefs` 1회 시드, `onSend`)
- Modify: `frontend/components/InterviewPanel.vue:185-201` (`AskExtra.refRepos?: RefRepoInput[]`. 배열이면 바디에 그대로 싣는다)
- Modify: `frontend/scripts/hydration-smoke.mjs` (`QUESTION.refRepos` — 고정·대기·실패 각 1개)
- Test: `frontend/test/questions-detail.spec.ts`

**Interfaces:** Consumes Task 9.

- [ ] **Step 1: 실패하는 테스트 작성** (기존 `detail` 픽스처에 `refRepos` 3개 상태 추가)

```ts
it('헤더 아래 참조 칩 줄에 고정 커밋·받는 중·실패 상태를 보인다')   // [data-test="session-ref-chips"]가 DB 칩 줄보다 앞
it('참조를 바꿔 보내면 ask 바디에 refRepos가 실린다', async () => {
  // (w.vm as any).pickedRefs.push({ catalogId: 4, alias: '배치 서버', branch: 'master' }) → sendText
  // body.refRepos = 기존 3개 + { repoCatalogId: 4, branch: 'master' } (toInputs 순서)
});
it('참조를 바꾸지 않으면 ask 바디는 지금과 같다')   // 기존 '입력창에서 Enter로 추가 질문을 보낸다'의 정확한 바디가 그대로 통과
it('답변 중(RUNNING)에는 참조 변경 버튼이 잠긴다')  // [data-test="ref-change"] disabled
it('상세 폴링이 와도 보내기 전 참조 초안이 유지된다')  // pickedRefs 수정 → 폴링 응답 → pickedRefs 그대로
```

- [ ] **Step 2: 실패 확인**
  - Run: `npx vitest run test/questions-detail.spec.ts`
  - Expected: FAIL
- [ ] **Step 3: 구현**
  - 칩 줄(`session-ref-chips`)은 질문 대화 화면에 항상 그린다. 비면 `참조 repo 없음`.
  - `✎ 참조 변경`(`data-test="ref-change"`, `:disable="!awaiting"`) 메뉴 안에 편집 가능한 `RefRepoChips`(items=`pickedRefs`)와 `RefRepoAddForm`을 둔다.
    - 제외 목록은 `detail.repoCatalogId` + 고른 것.
    - 카탈로그(`/api/repo-catalog`)는 메뉴를 처음 열 때 불러온다. 페이지를 여는 시점의 API 호출은 늘리지 않는다.
  - `pickedRefs`는 기존 `pickedSeeded` 블록(`[id].vue:60-68`)에서 한 번만 `[...(d.refRepos ?? [])]`로 채운다.
  - `onSend`: `!sameRefSet(toInputs(pickedRefs.value), toInputs(detail.value?.refRepos ?? []))`일 때만 `refRepos: toInputs(pickedRefs.value)`를 싣는다.
- [ ] **Step 4: 통과 확인**
  - Run: 같은 명령(기존 테스트 포함) + `npx nuxi prepare && npx nuxi typecheck`
  - Expected: PASS
- [ ] **Step 5: 커밋** — `feat(frontend): 대화 화면 참조 repo 표시·변경`

### Task 12: 문서·전체 검증

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-22-question-ref-repos-design.md`(상태 줄)

- [ ] **Step 1: CLAUDE.md 갱신**
  - "핵심 설계 제약"의 질문 세션 항목 끝에 "질문 세션 참조 repo(2026-10-22, 스펙 `docs/superpowers/specs/2026-10-22-question-ref-repos-design.md`)" 요약을 3~4줄로 붙인다: 최대 3개, `_refs/{세션}/{카탈로그}`, 첫 커밋 고정, 게이트는 Read/Grep/Glob만, 배포 순서 인터뷰 서비스 → API(V31) → 프론트.
  - "자주 보는 코드" 표에 행 2개를 넣는다.
    - 참조 repo: `RepoCatalogService.resolveReferences`, `QuestionService.applyRefChange`, `InterviewService.claim`, `repoPrepare.ensureRefRepos`, `permissions.ts` refRoots, `RefRepoChips`·`RefRepoAddForm`
    - 체크아웃 정리: `InterviewCheckoutCleanupJob`·`Planner`, `util/FileTrees`
  - "환경 메모"에 "인터뷰 체크아웃 정리" 한 줄: 설정 이름, 03:50, 7일, 첫 실행에 오래된 것 일괄 삭제.
- [ ] **Step 2: 스펙 상태 줄** — 끝에 `· 구현됨`을 붙인다
- [ ] **Step 3: 전체 검증**
  - 백엔드: `./gradlew test` → 이 PC의 원래 실패 3건 외에는 PASS
  - 인터뷰 서비스: `npx vitest run && npx tsc -p tsconfig.json --noEmit` → PASS
  - 프론트: `npx vitest run && npx nuxi prepare && npx nuxi typecheck` → PASS
- [ ] **Step 4: 커밋** — `docs: 질문 세션 참조 repo·체크아웃 정리 운영 메모`

---

병합 뒤(이 계획 밖): 푸시 → PR → CI 3개(Testcontainers·하이드레이션 스모크가 여기서 처음 돈다) → 라이브 반영. 반영 순서는 인터뷰 서비스 → API(V31, 정리 잡 시작) → 프론트(`.output` 재빌드)이고, 확인할 항목은 스펙 §10 "라이브 확인"이다.
