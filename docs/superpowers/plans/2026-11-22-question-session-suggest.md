# 질문 세션 대화 중 제안(DB 연결 · 참조 레포) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문 세션의 Claude가 답하는 도중 "DB 연결이 필요하다 / 이 레포도 봐야 한다"를 내장 도구로 남기고, 답변 아래 카드의 버튼 한 번으로 붙여 같은 대화를 이어가게 한다.

**Architecture:** API가 claim에 제안 후보를 싣고 → 인터뷰 서비스가 후보가 있을 때만 내장 SDK MCP 서버 `suggest`(도구 2개, 메모리에만 적음)를 붙여 답변 보고에 `suggestions`를 함께 보내고 → API가 검증해 `com.question_suggestion`(V48)에 저장하고 스냅샷·SSE로 화면에 보내고 → 화면 카드가 기존 `ask`(+ `acceptSuggestionIds`)로 붙인다. 붙이는 검증은 전부 기존 `ask` 경로가 한다.

**Tech Stack:** Java 21 · Spring Boot · Flyway · JPA(`ddl-auto: validate`) / Node · TypeScript · `@anthropic-ai/claude-agent-sdk` · zod · vitest / Nuxt 3 · Quasar · vitest.

**Spec:** `docs/superpowers/specs/2026-11-22-question-session-suggest-design.md` — 계획과 어긋나면 스펙이 이긴다.

## Global Constraints

- 작업 폴더는 이 worktree(`.claude/worktrees/question-session-suggest`, 브랜치 `feature/question-session-suggest`)뿐이다. 본 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)에서 빌드·시험·git 변경을 하지 않는다(라이브가 거기서 돈다).
- `npm install`·`npm ci`를 하지 않는다. `frontend/node_modules`와 `netismaker-interview-service/node_modules`는 본 체크아웃의 같은 폴더로 가는 **정션**으로 쓴다(Task 1 Step 1). 정션은 `rm -rf`·`Remove-Item -Recurse`로 지우지 않는다.
- 커밋: 작성자 설정은 저장소 관례(`git -c user.name=… -c user.email=… commit`)를 따르고, 메시지 끝에 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. 푸시하지 않는다.
- 저장소는 **공개**다. 시험·문서·주석에 실제 고객·사이트 이름을 넣지 않는다(`acme/…`, `시험사이트`, `Netis7.0` 같은 일반 이름만).
- 제안할 것이 없는 질문 세션(후보 없음·기능 꺼짐·구버전 API)은 프롬프트·SDK 옵션·보고 바디가 **지금과 바이트까지 같아야** 한다. INTERVIEW 세션은 어떤 경우에도 바뀌지 않는다.
- Claude는 제안만 한다. 연결·참조를 붙이는 코드는 `QuestionService.ask`의 기존 `applyDbChange`·`applyRefChange`뿐이다 — 새로 붙이는 경로를 만들지 않는다.
- 프롬프트·제안 본문에 DB 접속정보(호스트·포트·계정·비밀번호)와 카탈로그 `description`을 싣지 않는다. 나가는 것은 연결의 `id·name·dbType`, 레포의 `id·alias·route_hint`뿐이다.
- 상한(스펙 §5.2): 한 턴에 DB 제안 1개·참조 제안 2개, `reason` 200자, `targets` 5개·각 80자. DB·참조는 세션당 각 3개(`DbConnectionService.MAX_SELECTION`, `RepoCatalogService.MAX_REF_REPOS`).
- 내장 서버 이름 `suggest`, 도구 이름 `suggest_db`·`suggest_ref_repo`(전체 이름 `mcp__suggest__suggest_db`·`mcp__suggest__suggest_ref_repo`).
- 마이그레이션은 `V48__question_suggestion.sql`. 병합 직전 `origin/main`의 최대 번호를 다시 본다.
- 프론트: 작은따옴표·세미콜론 없음, 클래스 이름에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지, `defineProps<T>()`의 T를 `~/`에서 가져오지 않는다(vitest가 `.nuxt` 없이 돈다), 확인창은 `confirmDialog($q)`(이 기능은 확인창 없음).
- Testcontainers 시험(`@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")`)은 이 PC에서 건너뛰고 CI가 돌린다. 세션 행을 만드는 통합 시험의 `requester_id`는 `init-test-schema.sql`에 시드된 `user1`·`user2`·`admin1`이거나, 시험이 `com."user"`에 직접 넣은 사용자여야 한다(FK).

## Review Focus

1. **사용자가 누르지 않았는데 붙는 경로** — 러너가 보고한 `suggestions`만으로 세션의 `db_connection_ids`·`ref_repos`가 바뀌면 안 된다. → Task 4 `제안_보고는_세션의_연결과_참조를_바꾸지_않는다`.
2. **관리자가 남의 세션에서 받아들이기** — 선택지가 보는 사람 기준이면 `ask`가 400을 낸다. 선택지는 요청자 기준이어야 한다. → Task 2 `DB_후보는_요청자_기준이다`, Task 4 `PENDING_DB_제안의_선택지는_요청자_기준이다`.
3. **지난 답변의 제안이 다시 눌리는 것** — 새 사용자 턴이 생기면 남은 `PENDING`은 `SUPERSEDED`. 중복 제출(turnSeq null)에서는 바뀌지 않아야 한다. → Task 5 두 시험.
4. **자리가 꽉 찼을 때** — 2개 붙은 세션에서 카드가 2개를 더 고르게 두면 `ask` 전체가 400. → Task 8 `남은_자리만큼만_체크할_수_있다`.
5. **입력창에 쓰던 글·첨부가 받아들이기와 함께 날아가거나 딸려 가는 것** — → Task 9 `text를_넘기면_입력창을_건드리지_않고_첨부도_보내지_않는다`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V48__question_suggestion.sql` | 제안 표 |
| `entity/QuestionSuggestion.java`, `SuggestionKind.java`, `SuggestionStatus.java` | 행·상태 전이 |
| `repository/QuestionSuggestionRepository.java` | 조회 |
| `service/suggest/QuestionSuggestCandidates.java` | 후보 계산 한 곳(claim·검증·화면 선택지 공용) |
| `service/suggest/QuestionSuggestService.java` | claim 후보, 보고 저장, 뷰, 받아들이기·지나감·닫기 |
| `dto/SuggestionView.java` | 화면용(스냅샷·SSE·dismiss 응답) |
| `dto/InterviewClaimResponse.java`·`WorkerQuestionRequest.java`·`QuestionAskRequest.java`·`InterviewResponse.java`·`McpCatalogDto.java` | 새 칸·예약 이름 |
| `netismaker-interview-service/src/sdk/suggestMcp.ts` | 내장 서버·도구·수집·제안 절 |
| `netismaker-interview-service/src/runner/interviewRunner.ts`·`src/sdk/sessionOptions.ts`·`src/types.ts`·`src/index.ts` | 연결 |
| `frontend/composables/sessionSuggest.ts` | 타입·순수 함수 |
| `frontend/components/chat/SessionSuggestCard.vue` | 카드 |
| `frontend/composables/useInterviewStream.ts`·`components/InterviewPanel.vue`·`pages/questions/[id].vue` | 상태·슬롯·전송 |

Java 패키지 경로는 모두 `src/main/java/com/hamonsoft/netismaker/` 아래, 시험은 `src/test/java/com/hamonsoft/netismaker/` 아래 같은 패키지다.

---

### Task 1: 표·엔티티·저장소 (V48)

**Files:**
- Create: `src/main/resources/db/migration/V48__question_suggestion.sql`
- Create: `entity/QuestionSuggestion.java`, `entity/SuggestionKind.java`, `entity/SuggestionStatus.java`
- Create: `repository/QuestionSuggestionRepository.java`
- Test: `entity/QuestionSuggestionTest.java`, `repository/QuestionSuggestionRepositoryIntegrationTest.java`

**Interfaces:**
- Produces:
  - `enum SuggestionKind { DB, REF_REPO }`, `enum SuggestionStatus { PENDING, ACCEPTED, DISMISSED, SUPERSEDED }` (DB에는 `name()` 문자열)
  - `QuestionSuggestion.db(long sessionId, int turnSeq, Long dbConnectionId, String reason, List<String> targets)` · `QuestionSuggestion.refRepo(long sessionId, int turnSeq, long repoCatalogId, String reason)` — 둘 다 `PENDING`
  - `boolean decide(SuggestionStatus next, String actorId, OffsetDateTime now)` — `PENDING`일 때만 바꾸고 true, 아니면 그대로 두고 false. `next`가 `PENDING`이면 `IllegalArgumentException`
  - getter: `getId() getSessionId() getTurnSeq() getKind() getDbConnectionId() getRepoCatalogId() getReason() getTargets() getStatus() getDecidedAt() getDecidedBy() getCreatedAt()`
  - `QuestionSuggestionRepository extends JpaRepository<QuestionSuggestion, Long>`: `List<QuestionSuggestion> findBySessionIdOrderByIdAsc(Long sessionId)`, `List<QuestionSuggestion> findBySessionIdAndStatusOrderByIdAsc(Long sessionId, SuggestionStatus status)`

- [ ] **Step 1: 시험 실행 환경 만들기(정션)** — PowerShell에서, 이미 있으면 건너뛴다:

```powershell
$wt='C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-session-suggest'; $main='C:\Users\mic\NetisMaker\commanCenter'
foreach ($d in 'frontend','netismaker-interview-service') { if (-not (Test-Path "$wt\$d\node_modules")) { New-Item -ItemType Junction -Path "$wt\$d\node_modules" -Target "$main\$d\node_modules" | Out-Null } }
```

확인: `cd netismaker-interview-service && npx vitest run test/mockupMcp.test.ts` 통과, `cd frontend && npx vitest run composables/questions.spec.ts` 통과(없는 파일이면 `components/chat/ChatBubble.spec.ts`).

- [ ] **Step 2: 실패하는 엔티티 시험 쓰기** — `QuestionSuggestionTest`:

```java
@Test void db_제안은_PENDING으로_만들어지고_대상은_선택이다() {
    QuestionSuggestion s = QuestionSuggestion.db(7L, 4, null, "설정 값이 테이블에 있다", List.of("tb_config"));
    assertThat(s.getKind()).isEqualTo(SuggestionKind.DB);
    assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
    assertThat(s.getDbConnectionId()).isNull();
    assertThat(s.getTargets()).containsExactly("tb_config");
    assertThat(s.getRepoCatalogId()).isNull();
}
@Test void decide는_PENDING에서_한_번만_바뀐다() {
    QuestionSuggestion s = QuestionSuggestion.refRepo(7L, 4, 9L, "호출 대상이 이 레포에 있다");
    OffsetDateTime now = OffsetDateTime.now();
    assertThat(s.decide(SuggestionStatus.ACCEPTED, "user1", now)).isTrue();
    assertThat(s.decide(SuggestionStatus.DISMISSED, "admin1", now.plusMinutes(1))).isFalse();
    assertThat(s.getStatus()).isEqualTo(SuggestionStatus.ACCEPTED);
    assertThat(s.getDecidedBy()).isEqualTo("user1");
    assertThat(s.getDecidedAt()).isEqualTo(now);
}
@Test void decide에_PENDING을_주면_거절한다() {
    assertThatThrownBy(() -> QuestionSuggestion.db(1L, 1, null, "r", List.of())
            .decide(SuggestionStatus.PENDING, "u", OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 3: 실패 확인** — `./gradlew test --no-daemon --tests "com.hamonsoft.netismaker.entity.QuestionSuggestionTest"` → 컴파일 실패(클래스 없음).

- [ ] **Step 4: 마이그레이션 쓰기** — `V48__question_suggestion.sql`:

```sql
-- 질문 세션 대화 중 제안(스펙 2026-11-22 §6.2): 답변 중 Claude가 남긴 DB 연결·참조 레포 제안과 그 결과.
CREATE TABLE com.question_suggestion (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    turn_seq         INT NOT NULL,
    kind             VARCHAR(20) NOT NULL,
    db_connection_id BIGINT REFERENCES com.db_connection(id) ON DELETE SET NULL,
    repo_catalog_id  BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    reason           VARCHAR(200) NOT NULL,
    targets          JSONB NOT NULL DEFAULT '[]',
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_at       TIMESTAMPTZ,
    decided_by       VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_question_suggestion_kind CHECK (kind IN ('DB', 'REF_REPO')),
    CONSTRAINT ck_question_suggestion_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DISMISSED', 'SUPERSEDED'))
);
CREATE INDEX ix_question_suggestion_session ON com.question_suggestion(session_id, id);
```

- [ ] **Step 5: 엔티티·enum·저장소 구현** — `@Table(schema = "com", name = "question_suggestion")`. `targets`의 JSONB 매핑은 `QuestionRouteLog.candidates`가 쓰는 방식을 그대로 따른다(`List<String>`). `kind`·`status`는 `@Enumerated(EnumType.STRING)`.

- [ ] **Step 6: 통합 시험 쓰기(CI 전용)** — `QuestionSuggestionRepositoryIntegrationTest`(`QuestionRouteQueriesIntegrationTest`와 같은 애너테이션·`TestcontainersConfig`). 세션은 `InterviewSession.createQuestion("acme/x", "main", "t", "q", "user1", List.of(), null, null)`로 만든다.

```java
@Test void V48이_적용되고_targets가_jsonb로_왕복된다()   // db(...) 저장 → findById → targets·status·createdAt non-null
@Test void 세션을_지우면_제안도_지워진다()                // jdbc DELETE FROM com.interview_session → count 0
@Test void 카탈로그_행을_지우면_레포_칸만_NULL이_된다()    // refRepo(...) 저장 → repo_catalog 삭제 → 행은 남고 repo_catalog_id NULL
@Test void 상태별_조회는_id_순이다()                      // PENDING 2 + DISMISSED 1 → findBySessionIdAndStatusOrderByIdAsc(PENDING) 2개, 저장 순
```

- [ ] **Step 7: 통과 확인** — `./gradlew test --no-daemon --tests "com.hamonsoft.netismaker.entity.QuestionSuggestionTest"` → 3 passed. 통합 시험은 건너뜀(이 PC), `./gradlew compileTestJava --no-daemon` 성공.

- [ ] **Step 8: Commit** — `git add src/main/resources/db/migration/V48__question_suggestion.sql src/main/java/.../entity/QuestionSuggestion.java …` → `feat: 질문 세션 제안 표(V48)와 엔티티`

---

### Task 2: 후보 계산

**Files:**
- Create: `service/suggest/QuestionSuggestCandidates.java`
- Modify: `service/DbConnectionService.java`(메서드 하나 추가), `service/RepoCatalogService.java`(메서드 하나 추가)
- Test: `service/suggest/QuestionSuggestCandidatesTest.java`(Mockito 단위), `service/RepoCatalogServiceReferenceableTest.java`

**Interfaces:**
- Consumes: `DbConnectionService.isEnabled()`, `DbConnectionDto.Chip(long id, String name, String dbType)`, `RepoCatalogService.listEnabled()`, `InterviewSession.getRepoCatalogId() getRequesterId() getDbConnectionIds() getRefRepos()`, `RefRepo.catalogId()`
- Produces:
  - `DbConnectionService.selectableChips(Long repoCatalogId, String ownerId): List<DbConnectionDto.Chip>` — 기능이 꺼졌거나 `repoCatalogId`가 null이면 빈 목록. 그 레포의 연결 중 `validateSelection`이 받아 줄 것(enabled + (REPO 범위 또는 `isOwnedBy(ownerId)`))만, `list`와 같은 정렬. 접속정보는 읽지 않는다.
  - `RepoCatalogService.isReferenceable(RepoCatalogEntry e): boolean` — `resolveReferences(대상, [new RefRepoInput(e.getId(), null)])`가 통과할 조건과 같다(켜짐, 지원하는 호스트, 기본 브랜치가 비어 있지 않음). 판정을 `resolveReferences`의 코드와 한곳에서 공유할 수 있으면 공유한다.
  - `QuestionSuggestCandidates`(`@Service`):
    - `record RefCandidate(long catalogId, String alias, String hint, String defaultBranch)`
    - `record Candidates(List<DbConnectionDto.Chip> db, List<RefCandidate> refs, int dbSlotsLeft, int refSlotsLeft)` + `boolean isEmpty()`(두 목록 모두 빔)
    - `Candidates forSession(InterviewSession s)` — `dbSlotsLeft = MAX_SELECTION − 붙은 수`(0 미만이면 0), `refSlotsLeft = MAX_REF_REPOS − 붙은 수`. 남은 자리가 0인 종류는 빈 목록. DB = `selectableChips` 중 세션에 안 붙은 것. 참조 = `listEnabled()` 중 대상이 아니고, 안 붙었고, `isReferenceable`인 것. `hint`는 `route_hint`가 비면 null.

- [ ] **Step 1: 실패하는 시험 쓰기** — `QuestionSuggestCandidatesTest`(세션은 `InterviewSession.createQuestion(...)` + setter로 구성, 서비스는 mock):

```java
@Test void DB_후보는_요청자_기준이다()                    // selectableChips(repo, "user1")만 부른다 — verify(db).selectableChips(8L, "user1")
@Test void 이미_붙은_DB_연결은_후보에서_빠진다()           // 세션 dbConnectionIds=[3], selectable=[3,4] → db=[4], dbSlotsLeft=2
@Test void DB가_3개_붙었으면_후보가_비고_selectableChips를_부르지_않는다()
@Test void DB_기능이_꺼졌으면_후보가_빈다()                // isEnabled=false → db 빈 목록
@Test void 참조_후보에서_대상과_붙은_레포와_참조할_수_없는_레포가_빠진다()
        // listEnabled=[대상(8), 붙은(9), 기본 브랜치 없음(10), 정상(11)] → refs=[11]
@Test void 참조_후보의_hint는_route_hint이고_설명은_싣지_않는다()
        // routeHint="시험 사업소, 7.0" → hint 그 값; RefCandidate 레코드에 description 성분이 없다(레코드 성분 이름 목록으로 확인)
@Test void 참조가_3개_붙었으면_참조_후보가_빈다()
@Test void 둘_다_비면_isEmpty다()
```

`RepoCatalogServiceReferenceableTest`: `켜져_있고_기본_브랜치가_있고_지원하는_호스트면_true`, `기본_브랜치가_비면_false`, `지원하지_않는_호스트면_false`, `isReferenceable이_true인_항목은_resolveReferences를_통과한다`(같은 항목으로 `resolveReferences(다른 id, List.of(new RefRepoInput(id, null)))`가 예외 없이 1개를 돌려준다).

- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*QuestionSuggestCandidatesTest" --tests "*RepoCatalogServiceReferenceableTest"` → 컴파일 실패.

- [ ] **Step 3: 구현** — 위 Interfaces대로. `selectableChips`는 `@Transactional(readOnly = true)`.

- [ ] **Step 4: 통과 확인** — 같은 명령 → 전부 통과. 기존 `DbConnectionServiceTest`·`RepoCatalogServiceTest`도 통과.

- [ ] **Step 5: Commit** — `feat: 질문 세션 제안 후보 계산`

---

### Task 3: claim에 후보 싣기

**Files:**
- Create: `service/suggest/QuestionSuggestService.java`(이 작업에서는 `claimCandidates`만)
- Modify: `dto/InterviewClaimResponse.java`, `controller/InterviewWorkerController.java`, `src/main/resources/application.yml`
- Test: `service/suggest/QuestionSuggestServiceClaimTest.java`, `dto/InterviewClaimResponseTest.java`(기존 파일에 추가)

**Interfaces:**
- Consumes: Task 2 `QuestionSuggestCandidates.forSession`, `InterviewTurnRepository.countBySessionIdAndRole(Long, String)`
- Produces:
  - `InterviewClaimResponse`에 마지막 성분 `Suggest suggest`(null 가능) + `withSuggest(Suggest)`; 내부 레코드 `Suggest(List<DbCandidate> dbCandidates, List<RefCandidate> refCandidates)`, `DbCandidate(long id, String name, String dbType)`, `RefCandidate(long catalogId, String alias, String hint)`. 기존 `of(...)`·`with*` 호출부는 `suggest`를 그대로 실어 나른다(null 유지).
  - `QuestionSuggestService.claimCandidates(long sessionId): Optional<InterviewClaimResponse.Suggest>` — `@Transactional(readOnly = true)`
  - 설정 `app.question.suggest.enabled: ${APP_QUESTION_SUGGEST_ENABLED:true}`(`application.yml`의 `question:` 아래)

`claimCandidates`가 빈 값을 돌려주는 경우(이 순서로): 기능 꺼짐 → 세션 없음 → 질문 세션 아님 → 이번 답변 뒤 문답이 남지 않음(`assistant 턴 수 + 1 >= maxQaTurns`, `maxQaTurns`는 `@Value("${app.question.max-qa-turns:10}")`) → `Candidates.isEmpty()`.

- [ ] **Step 1: 실패하는 시험 쓰기** — `QuestionSuggestServiceClaimTest`(Mockito):

```java
@Test void 후보가_있으면_claim용으로_옮긴다()             // Chip(4,"기본 DB","MARIADB") + RefCandidate(11,"Netis7.0","7.0","main") → Suggest(dbCandidates=[(4,"기본 DB","MARIADB")], refCandidates=[(11,"Netis7.0","7.0")])
@Test void claim용_참조_후보에는_기본_브랜치를_싣지_않는다() // InterviewClaimResponse.RefCandidate 성분 = catalogId, alias, hint
@Test void 기능이_꺼져_있으면_빈_값이다()                  // enabled=false → Optional.empty, 저장소를 부르지 않는다
@Test void 질문_세션이_아니면_빈_값이다()
@Test void 이번_답변이_마지막_문답이면_빈_값이다()          // maxQaTurns=10, assistant 턴 9 → empty; 8 → present
@Test void 후보가_없으면_빈_값이다()
```

`InterviewClaimResponseTest`에 추가: `suggest_없이_만든_claim은_suggest가_null이고_with들이_그대로_실어_나른다`(`of(...).withDb(...).withRefRepos(...)` 뒤에도 `suggest()`가 `withSuggest`로 넣은 값), `suggest는_JSON에_dbCandidates와_refCandidates로_나간다`.

- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*QuestionSuggestServiceClaimTest" --tests "*InterviewClaimResponseTest"`

- [ ] **Step 3: 구현** — `InterviewWorkerController.claim`을 `interviewService.claim(workerId).map(this::withDb).map(this::withSuggest)`로. `withSuggest`는 `withDb`와 같은 원칙이다: `RuntimeException`을 잡아 경고 한 줄(`claim 제안 후보 조회 실패 session={}: {예외 클래스 이름}`)만 남기고 `c`를 그대로 돌려준다 — claim 응답을 깨지 않는다.

- [ ] **Step 4: 통과 확인** — 같은 명령 + `./gradlew test --no-daemon --tests "*InterviewWorkerController*"`.

- [ ] **Step 5: Commit** — `feat: 질문 claim에 제안 후보 싣기`

---

### Task 4: 제안 보고 저장 · 화면 뷰 · SSE

**Files:**
- Create: `dto/SuggestionView.java`
- Modify: `dto/WorkerQuestionRequest.java`, `service/suggest/QuestionSuggestService.java`, `service/InterviewService.java`, `service/QuestionService.java`, `dto/InterviewResponse.java`, `service/InterviewStreamService.java`, `controller/InterviewWorkerController.java`
- Test: `service/suggest/QuestionSuggestServiceRecordTest.java`, `dto/WorkerQuestionRequestJsonTest.java`(기존), `service/InterviewServiceTest.java`(기존에 1건), `service/suggest/QuestionSuggestFlowIntegrationTest.java`(CI 전용)

**Interfaces:**
- Consumes: Task 1 엔티티·저장소, Task 2 `Candidates`, `GitRemotes.mask(String)`, `SecretMasks.maskBareTokens(String)`
- Produces:
  - `WorkerQuestionRequest`에 마지막 성분 `List<Suggestion> suggestions`(null 가능) + 내부 레코드 `Suggestion(String kind, Long dbConnectionId, Long repoCatalogId, String reason, List<String> targets)`. 기존 10·11인자 생성자는 남기고 `suggestions = null`로 위임한다.
  - `SuggestionView(long id, int turnSeq, String kind, String status, String reason, List<String> targets, Long dbConnectionId, Long repoCatalogId, String repoAlias, String defaultBranch, List<DbConnectionDto.Chip> dbOptions, int dbSlotsLeft, int refSlotsLeft)` — `dbOptions`는 `PENDING`인 DB 제안에만 채우고 그 밖에는 빈 목록. `repoAlias`·`defaultBranch`는 참조 제안에만(카탈로그 행이 지워졌으면 null).
  - `QuestionSuggestService.record(InterviewSession s, int turnSeq, List<WorkerQuestionRequest.Suggestion> reqs): List<QuestionSuggestion>` — 호출자의 트랜잭션 안에서 돈다. 예외를 밖으로 내지 않는다(잡아서 경고, 빈 목록).
  - `QuestionSuggestService.views(long sessionId): List<SuggestionView>` — `@Transactional(readOnly = true)`, id 순.
  - `QuestionSuggestService.viewsOf(long sessionId, Collection<Long> ids): List<SuggestionView>`
  - `InterviewResponse`에 `List<SuggestionView> suggestions`(항상 non-null, 기본 빈 목록) + `withSuggestions(List<SuggestionView>)`; 기존 `with*`가 그대로 실어 나른다.
  - `InterviewStreamService.pushSuggestion(Long sessionId, SuggestionView v)` — SSE 이벤트 이름 `suggestion`, 페이로드 = `SuggestionView` JSON.
  - `InterviewService`·`QuestionService`에는 `@Autowired(required = false) void setQuestionSuggestService(QuestionSuggestService)`로 주입한다(`setInterviewMockupService` 선례 — 생성자를 바꾸면 기존 단위 시험이 전부 깨진다). null이면 제안 관련 동작을 전부 건너뛴다.

`record`의 정규화 규칙(스펙 §6.2):
1. 세션이 질문이 아니거나 `reqs`가 null·빈 목록이면 빈 목록.
2. `Candidates c = candidates.forSession(s)`를 한 번 계산한다.
3. 원소마다: `reason`을 strip → `GitRemotes.mask` → `SecretMasks.maskBareTokens` → 200자(코드 포인트)로 자른다. 비면 버린다. `kind`가 `DB`·`REF_REPO`가 아니면 버린다.
4. `DB`: `c.db()`가 비었으면 버린다. `dbConnectionId`가 `c.db()`에 없으면 null로 바꿔 받는다. `targets`는 null→빈 목록, 원소 strip·마스킹·80자, 빈 것 제거, 앞 5개. 한 턴에 첫 1개만.
5. `REF_REPO`: `repoCatalogId`가 `c.refs()`에 없으면 버린다. 같은 id 중복은 첫 것만. 앞 2개만(단 `c.refSlotsLeft()`가 1이면 1개).
6. 저장한 행을 돌려준다.

- [ ] **Step 1: 실패하는 시험 쓰기** — `QuestionSuggestServiceRecordTest`(Mockito, 저장소 `save`는 인자를 그대로 돌려주게):

```java
@Test void 후보에_있는_DB_제안을_저장한다()
@Test void DB_제안이_고른_연결이_후보에_없으면_연결_없는_제안으로_받는다()
@Test void DB_후보가_없으면_DB_제안을_버린다()
@Test void 한_턴에_DB_제안은_첫_1개만_받는다()
@Test void 후보에_없는_레포의_참조_제안은_버린다()
@Test void 참조_제안은_2개까지이고_같은_레포는_한_번만_받는다()
@Test void 참조_자리가_1개_남았으면_1개만_받는다()
@Test void 이유가_비면_버리고_200자를_넘으면_자른다()
@Test void 이유와_대상의_토큰_모양_문자열을_가린다()        // "https://oauth2:abc123@host/x" → "://***@" 포함, 원문 토큰 미포함
@Test void 대상은_5개_80자까지다()
@Test void 질문_세션이_아니면_아무것도_저장하지_않는다()
@Test void 후보_계산이_예외를_던져도_예외를_밖으로_내지_않는다()  // candidates.forSession → RuntimeException → 빈 목록, save 호출 없음
@Test void PENDING_DB_제안의_선택지는_요청자_기준이다()      // views: dbOptions == candidates.forSession(s).db(), 슬롯 수 포함
@Test void 결정된_제안의_뷰에는_선택지가_없다()
@Test void 참조_제안의_뷰에는_별칭과_기본_브랜치가_있다()
```

`WorkerQuestionRequestJsonTest`에 추가: `suggestions가_없는_바디는_null로_읽힌다`, `suggestions를_읽는다`(`{"kind":"DB","reason":"r","targets":["t"],"dbConnectionId":4}`).

`InterviewServiceTest`에 추가: `제안_보고는_세션의_연결과_참조를_바꾸지_않는다` — `recordQuestion`에 `suggestions`가 든 요청을 주고, 끝난 뒤 세션의 `getDbConnectionIds()`·`getRefRepos()`가 호출 전과 같고 `suggestService.record(s, turn.getSeq(), req.suggestions())`가 한 번 불렸는지 본다. `suggestions`가 null이면 `record`를 부르지 않는다.

- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*QuestionSuggestServiceRecordTest" --tests "*WorkerQuestionRequestJsonTest" --tests "*InterviewServiceTest"`

- [ ] **Step 3: 구현**
  - `InterviewService.recordQuestion`: `appendTurn`·`bindMockups` 다음에 `if (suggestService != null && s.isQuestion() && req.suggestions() != null && !req.suggestions().isEmpty()) suggestService.record(s, turn.getSeq(), req.suggestions());`
  - `QuestionService.get`: `r.withDbConnections(...)`에 이어 `suggestService == null ? r : r.withSuggestions(suggestService.views(id))`.
  - `InterviewWorkerController.question`: `pushQuestion` 다음, `pushStatus` 앞에 그 턴의 제안을 민다 — `suggestService.views(id)` 중 `turnSeq == turn.getSeq()`인 것마다 `interviewStream.pushSuggestion`. 실패는 잡아서 경고만(답변 전달을 막지 않는다).

- [ ] **Step 4: 통합 시험 쓰기(CI 전용)** — `QuestionSuggestFlowIntegrationTest`(`@SpringBootTest` + `TestcontainersConfig`): 카탈로그 2개(대상·다른 레포, 둘 다 기본 브랜치 `main`)와 `user1`의 질문 세션을 만들고 RUNNING으로 claim한 뒤 `recordQuestion`에 참조 제안 1개를 보고 → `questionService.get(...)`의 `suggestions`에 `PENDING` 1개(별칭·`defaultBranch="main"`·`refSlotsLeft=3`), 세션 `ref_repos`는 빈 그대로.

- [ ] **Step 5: 통과 확인** — Step 2 명령 전부 통과, `./gradlew compileTestJava --no-daemon` 성공.

- [ ] **Step 6: Commit** — `feat: 질문 제안 보고 저장과 화면 전달`

---

### Task 5: 받아들이기 · 지나감 · 닫기 · 예약 이름

**Files:**
- Modify: `dto/QuestionAskRequest.java`, `service/QuestionService.java`, `service/suggest/QuestionSuggestService.java`, `controller/QuestionController.java`, `dto/McpCatalogDto.java`
- Test: `service/suggest/QuestionSuggestServiceDecideTest.java`, `service/QuestionServiceTest.java`(기존에 추가), `dto/McpCatalogDtoValidationTest.java`(없으면 새로), `QuestionSuggestFlowIntegrationTest`(Task 4 파일에 추가)

**Interfaces:**
- Consumes: Task 1 `decide`, Task 4 `viewsOf`·`pushSuggestion`
- Produces:
  - `QuestionAskRequest`에 마지막 성분 `List<Long> acceptSuggestionIds`(null 가능). 기존 4·5·6인자 생성자는 남기고, **7인자(…, refRepos) 생성자를 새 호환 생성자로 추가**한다(`acceptSuggestionIds = null`).
  - `QuestionSuggestService.onAsk(long sessionId, List<Long> acceptIds, String actorId): List<Long>` — 이 세션의 `PENDING` 행 중 `acceptIds`에 있는 것은 `ACCEPTED`, 나머지는 `SUPERSEDED`. 바뀐 행의 id를 돌려준다. 다른 세션의 id·이미 결정된 id는 조용히 무시한다.
  - `QuestionSuggestService.dismiss(long sessionId, long suggestionId, String actorId): SuggestionView` — 그 세션의 행이 아니면 404(`제안을 찾을 수 없습니다`), `PENDING`이 아니면 409(`이미 처리된 제안입니다`).
  - `QuestionService.AskResult`에 `List<Long> changedSuggestionIds`(항상 non-null) — 기존 생성자들은 빈 목록으로 위임.
  - `QuestionService.dismissSuggestion(Long id, Long suggestionId, String actorId, boolean isAdmin): SuggestionView` — `requireViewable`(kind 404 → 소유자·관리자 403) 뒤 `dismiss`.
  - `POST /api/questions/{id}/suggestions/{suggestionId}/dismiss` → `SuggestionView`(200). 커밋 뒤 `pushSuggestion`.
  - `McpCatalogDto`의 `name`에 두 번째 `@Pattern(regexp = "^(?!suggest$).*$", message = "'suggest'는 내장 제안 도구용으로 예약되어 있습니다")`.

`ask` 안의 위치: `applyRefChange` 다음, `writeAttachments` 앞에서 **`outcome.turnSeq() != null`일 때만** `onAsk(id, req.acceptSuggestionIds(), actorId)`를 부른다. 중복 제출(turnSeq null)이면 제안 상태를 건드리지 않는다. 같은 트랜잭션이라 `ask`가 400으로 끝나면 상태 변경도 롤백된다.

- [ ] **Step 1: 실패하는 시험 쓰기** — `QuestionSuggestServiceDecideTest`:

```java
@Test void 받아들인_id는_ACCEPTED_나머지_PENDING은_SUPERSEDED가_된다()   // PENDING 3개(1,2,3), acceptIds=[2] → 2 ACCEPTED, 1·3 SUPERSEDED, 반환 [1,2,3]
@Test void acceptIds가_null이면_남은_PENDING이_모두_SUPERSEDED가_된다()
@Test void 다른_세션의_id와_이미_결정된_id는_무시한다()
@Test void PENDING이_없으면_빈_목록이다()
@Test void 닫기는_PENDING을_DISMISSED로_바꾼다()
@Test void 다른_세션의_제안을_닫으면_404다()
@Test void 이미_결정된_제안을_닫으면_409다()
```

`QuestionServiceTest`에 추가:

```java
@Test void 새_턴이_생긴_ask는_제안을_받아들이고_나머지를_지나감으로_바꾼다()   // outcome.turnSeq()=5 → verify(suggest).onAsk(id, List.of(2L), "user1"); AskResult.changedSuggestionIds 그대로
@Test void 중복_제출인_ask는_제안_상태를_건드리지_않는다()                   // outcome.turnSeq()=null → verify(suggest, never()).onAsk(...)
@Test void 제안_서비스가_없어도_ask는_그대로_동작한다()                     // setter 미호출 → changedSuggestionIds 빈 목록
```

`McpCatalogDtoValidationTest`: `이름이_suggest면_거절한다`, `suggest로_시작하는_다른_이름은_받는다`(`suggest-tools`), `db-로_시작하면_거절한다`(기존 규칙 유지 확인).

통합(CI): `QuestionSuggestFlowIntegrationTest`에 `받아들이면_참조가_붙고_제안이_ACCEPTED가_된다`(Task 4의 상태에서 세션을 AWAITING_INPUT으로 두고 `ask(new QuestionAskRequest("보완해 주세요", null, null, null, null, null, List.of(new RefRepoInput(다른레포, null)), List.of(제안id)))` → 세션 `ref_repos` 1개, 제안 `ACCEPTED`·`decided_by="user1"`), `붙일_수_없으면_ask가_400이고_제안은_PENDING_그대로다`(같은 요청에 존재하지 않는 카탈로그 id → 400, 제안 `PENDING`).

- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*QuestionSuggestServiceDecideTest" --tests "*QuestionServiceTest" --tests "*McpCatalogDtoValidationTest"`

- [ ] **Step 3: 구현** — `QuestionController.doAsk`: 기존 노트 push 뒤에 `result.changedSuggestionIds()`가 비지 않으면 `viewsOf`로 읽어 하나씩 `pushSuggestion`. dismiss 핸들러는 `QuestionController`의 다른 핸들러와 같은 방식으로 `userId`·`isAdmin`을 얻는다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 전부 통과 + `./gradlew test --no-daemon --tests "*QuestionController*" --tests "*McpCatalog*"`.

- [ ] **Step 5: Commit** — `feat: 질문 제안 받아들이기·닫기와 MCP 예약 이름`

---

### Task 6: 인터뷰 서비스 — `suggest` 내장 서버

**Files:**
- Create: `netismaker-interview-service/src/sdk/suggestMcp.ts`
- Modify: `netismaker-interview-service/src/types.ts`
- Test: `netismaker-interview-service/test/suggestMcp.test.ts`

**Interfaces:**
- Consumes: `textResult` from `./dbMcp.js`, `createSdkMcpServer`·`tool` from the SDK(`mockupMcp.ts`와 같은 import)
- Produces (`types.ts`):

```ts
export interface SuggestDbCandidate { id: number; name: string; dbType: string }
export interface SuggestRefCandidate { catalogId: number; alias: string; hint?: string | null }
export interface SuggestCandidates { dbCandidates?: SuggestDbCandidate[]; refCandidates?: SuggestRefCandidate[] }
export type QuestionSuggestionInput =
  | { kind: 'DB'; reason: string; targets?: string[]; dbConnectionId?: number }
  | { kind: 'REF_REPO'; reason: string; repoCatalogId: number }
// InterviewClaimResponse: suggest?: SuggestCandidates | null   (구버전 API는 칸이 없다)
// WorkerQuestionRequest:  suggestions?: QuestionSuggestionInput[]   (없으면 키 생략)
```

- Produces (`suggestMcp.ts`):

```ts
export const SUGGEST_SERVER = 'suggest'
export const SUGGEST_DB_TOOL = 'suggest_db'
export const SUGGEST_REF_TOOL = 'suggest_ref_repo'
export const SUGGEST_REASON_MAX = 200
export const SUGGEST_TARGETS_MAX = 5
export const SUGGEST_TARGET_CHARS = 80
export const SUGGEST_REF_MAX_PER_TURN = 2
export interface PreparedSuggestMcp {
  servers: Record<string, McpSdkServerConfigWithInstance>   // { suggest: … }
  tools: SdkMcpToolDefinition[]                              // 시험·진단용(핸들러 직접 호출)
  collected(): QuestionSuggestionInput[]                     // 이 턴에 받은 제안의 사본(받은 순서)
}
/** 후보가 하나도 없으면(null·undefined·두 목록 모두 빔) null — 호출자는 서버·절·바디 키를 모두 생략한다. */
export function createSuggestMcp(candidates: SuggestCandidates | null | undefined): PreparedSuggestMcp | null
/** 제안 절(끝에 빈 줄 포함). 후보가 없으면 ''. */
export function suggestSection(candidates: SuggestCandidates | null | undefined): string
```

도구 동작:
- `suggest_db` 입력 `{ reason: z.string().min(1), targets: z.array(z.string()).optional(), connectionId: z.number().int().optional() }`. `dbCandidates`가 비지 않을 때만 만든다. `reason`은 trim 뒤 200자(코드 포인트)로 자르고, `targets`는 trim·빈 것 제거·각 80자·앞 5개. `connectionId`가 후보에 없으면 `isError` 결과(`후보에 없는 연결입니다: {id}`)이고 수집하지 않는다. 이미 DB 제안을 받았으면 `isError`(`이번 답변에서는 이미 DB 연결을 제안했습니다`).
- `suggest_ref_repo` 입력 `{ repoCatalogId: z.number().int(), reason: z.string().min(1) }`. `refCandidates`가 비지 않을 때만 만든다. 후보에 없으면 `isError`(`후보에 없는 레포입니다: {id}`). 같은 레포를 다시 부르면 `isError`(`이미 제안한 레포입니다`). 2개를 받은 뒤에는 `isError`(`이번 답변에서 제안할 수 있는 레포는 2개까지입니다`).
- 성공 문구(둘 다): `제안을 남겼습니다 — 사용자가 연결하면 다음 답변부터 쓸 수 있습니다. 지금은 코드로 확인한 범위에서 답을 마무리하세요.`
- 두 도구 모두 `{ alwaysLoad: true }`. 핸들러는 예외를 던지지 않는다.

`suggestSection`의 문구(후보가 있는 종류의 줄만 넣는다):

```
[제안 도구]
코드를 읽다가 아래 경우에 해당하면 도구로 제안을 남길 수 있습니다. 제안은 사용자에게 카드로 보이고, 사용자가 연결해야 다음 답변부터 쓸 수 있습니다.
- suggest_db: 답이 실제 DB의 구조나 저장된 값에 달려 있고, 지금 연결된 DB로는 볼 수 없을 때만 부릅니다. reason에 무엇을 확인하려는지, targets에 보고 싶은 테이블 이름을 적습니다.
- suggest_ref_repo: 답이 아래 목록의 다른 레포 코드에 달려 있다는 근거(호출 대상·공유 모듈 등)를 이 레포의 코드에서 확인했을 때만 부릅니다.
- 추측으로 부르지 않습니다. 한 답변에 종류마다 한 번이면 충분합니다.
- 제안을 남겨도 코드로 확인한 답은 이 답변에서 끝까지 쓰고, 확인하지 못한 부분이 무엇인지 한 문장으로 밝힙니다.
연결할 수 있는 DB: {id} · {name} · {dbType} (줄마다 하나)
함께 볼 수 있는 레포: {catalogId} · {alias}[ · {hint}] (줄마다 하나)

```

- [ ] **Step 1: 실패하는 시험 쓰기** — `test/suggestMcp.test.ts`(도구 핸들러는 `mockupMcp.test.ts`가 하는 방식으로 직접 부른다):

```ts
it('후보가 없으면 null이다')                       // null, undefined, {}, { dbCandidates: [], refCandidates: [] }
it('DB 후보만 있으면 suggest_db만 만든다')          // tools 이름 목록 = ['suggest_db']
it('레포 후보만 있으면 suggest_ref_repo만 만든다')
it('DB 제안을 수집한다')                           // { reason: ' 설정 값 확인 ', targets: [' tb_config ', ''], connectionId: 4 } → collected() = [{ kind:'DB', reason:'설정 값 확인', targets:['tb_config'], dbConnectionId:4 }]
it('연결을 고르지 않은 DB 제안은 dbConnectionId 키가 없다')
it('후보에 없는 연결은 isError이고 수집하지 않는다')
it('DB 제안은 한 답변에 한 번이다')
it('이유는 200자, 대상은 5개·80자로 자른다')        // 이모지 포함 문자열로 코드 포인트 기준 확인
it('레포 제안을 수집한다')
it('후보에 없는 레포는 isError다')
it('같은 레포는 한 번만, 전체는 2개까지다')
it('collected()는 사본을 돌려준다')                 // 돌려받은 배열을 고쳐도 다음 collected()에 영향 없음
it('제안 절은 후보가 있는 종류의 줄만 담는다')       // DB만: 'suggest_db:' 포함, 'suggest_ref_repo:' 미포함, '함께 볼 수 있는 레포' 미포함
it('제안 절에 hint가 없으면 별칭까지만 쓴다')        // '11 · Netis7.0' 줄, 끝에 ' · ' 없음
it('후보가 없으면 제안 절은 빈 문자열이다')
```

- [ ] **Step 2: 실패 확인** — `cd netismaker-interview-service && npx vitest run test/suggestMcp.test.ts` → 모듈 없음.

- [ ] **Step 3: 구현**

- [ ] **Step 4: 통과 확인** — 같은 명령 → 15 passed. `npx tsc -p tsconfig.json --noEmit` 오류 0.

- [ ] **Step 5: Commit** — `feat: 질문 세션 제안 내장 MCP 서버`

---

### Task 7: 인터뷰 서비스 — 질문 턴에 연결

**Files:**
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`, `src/runner/interviewRunner.ts`, `src/index.ts`
- Test: `test/sessionOptions.test.ts`, `test/permissions.test.ts`, `test/interviewRunner.test.ts`(모두 기존 파일에 추가)

**Interfaces:**
- Consumes: Task 6 `createSuggestMcp`·`suggestSection`·`PreparedSuggestMcp`·`SUGGEST_SERVER`
- Produces:
  - `SessionOptionsInput.suggestMcpServers?: Record<string, unknown>` — `buildOptions`의 merge **맨 끝**(`mockupMcpServers` 뒤). 미지정·빈 객체면 결과가 지금과 같다.
  - `RunnerDeps.createSuggestMcp?: (candidates: SuggestCandidates | null | undefined) => PreparedSuggestMcp | null` — 시험용 주입, 기본 `suggestMcp.createSuggestMcp`.
  - `runQuestionTurn`: `const suggest = (this.deps.createSuggestMcp ?? createSuggestMcp)(claim.suggest)` → 프롬프트 접두 `refSection(refs) + dbSection(db) + (suggest ? suggestSection(claim.suggest) : '')`, 옵션 `...(suggest ? { suggestMcpServers: suggest.servers } : {})`, 보고 바디 끝에 `...(suggest && suggest.collected().length > 0 ? { suggestions: suggest.collected() } : {})`.
  - `index.ts`: 기동 때 `SUGGEST_SERVER in mcpsBase`면 `console.warn('[interview-service] ~/.claude.json의 MCP 서버 "suggest"는 제안할 후보가 있는 질문 턴에서 내장 제안 서버에 가려집니다')` 한 줄.
- `permissions.ts`는 **바꾸지 않는다**.

- [ ] **Step 1: 실패하는 시험 쓰기**

`sessionOptions.test.ts`:
```ts
it('suggestMcpServers를 안 주면 옵션이 지금과 같다')               // 같은 입력에 suggestMcpServers: undefined / {} → toEqual(기존 결과) (canUseTool 함수는 비교에서 제외하는 기존 헬퍼 사용)
it('suggestMcpServers는 맨 끝에 합쳐져 같은 이름의 베이스·추가 서버를 이긴다')  // mcpsBase.suggest='base', mcpsExtra에 suggest, suggestMcpServers.suggest=X → mcpServers.suggest === X
it('QUESTION에서 suggest 서버를 붙여도 allowedTools는 빈 배열이다')
```

`permissions.test.ts`:
```ts
it.each(['mcp__suggest__suggest_db', 'mcp__suggest__suggest_ref_repo'])('질문 게이트가 %s를 허용한다', …)   // buildCanUseTool(repoDir, 'QUESTION', null, {}, []) → behavior 'allow'
```

`interviewRunner.test.ts`(질문 턴 묶음에):
```ts
it('claim.suggest가 없으면 프롬프트·옵션·보고 바디가 지금과 같다')
   // 기존 질문 턴 골든 시험의 입력에 suggest 키 없음/null 두 경우 → 프롬프트 문자열·buildOptions 입력·postQuestion 바디가 기존 기대값과 toEqual, 바디에 'suggestions' 키 없음
it('claim.suggest가 있으면 제안 절이 접두 끝에 붙고 서버가 붙는다')
   // 프롬프트가 suggestSection(claim.suggest)를 포함하고 refSection·dbSection 뒤에 온다; query 옵션의 mcpServers에 'suggest'
it('도구가 받은 제안이 보고 바디의 suggestions로 간다')
   // deps.createSuggestMcp 주입 → collected()가 [{kind:'DB',reason:'r'}] → postQuestion 바디.suggestions toEqual 그 값
it('제안을 하나도 받지 않았으면 suggestions 키가 없다')
it('작업 인터뷰(INTERVIEW) 턴은 claim.suggest가 있어도 무시한다')   // runTurn의 INTERVIEW 경로: 옵션에 suggest 서버 없음, 프롬프트 불변
it('추가 질문(resume) 턴에도 제안 절이 접두로 붙는다')
```

- [ ] **Step 2: 실패 확인** — `cd netismaker-interview-service && npx vitest run test/sessionOptions.test.ts test/permissions.test.ts test/interviewRunner.test.ts` → 새 시험 실패(게이트 2건은 이미 통과 — 이름 고정용).

- [ ] **Step 3: 구현** — 위 Interfaces대로. `buildOptions`의 주석("merge 순서")도 새 순서로 고친다.

- [ ] **Step 4: 통과 확인** — `npx vitest run`(전체) 실패 0, `npx tsc -p tsconfig.json --noEmit` 오류 0.

- [ ] **Step 5: Commit** — `feat: 질문 턴에 제안 서버·제안 절·보고 연결`

---

### Task 8: 프론트 — 타입·순수 함수·스트림 상태

**Files:**
- Create: `frontend/composables/sessionSuggest.ts`
- Modify: `frontend/composables/useInterviewStream.ts`
- Test: `frontend/composables/sessionSuggest.spec.ts`, `frontend/composables/useInterviewStream.spec.ts`(기존에 추가)

**Interfaces:**
- Consumes: `RefRepoInput` from `~/composables/refRepos`, `FakeEventSource`(`test/mocks/eventsource`)
- Produces (`sessionSuggest.ts`):

```ts
export type SuggestionKind = 'DB' | 'REF_REPO'
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'DISMISSED' | 'SUPERSEDED'
export interface SuggestDbOption { id: number; name: string; dbType: string }
export interface SessionSuggestion {
  id: number; turnSeq: number; kind: SuggestionKind; status: SuggestionStatus; reason: string
  targets: string[]; dbConnectionId: number | null; repoCatalogId: number | null
  repoAlias: string | null; defaultBranch: string | null
  dbOptions: SuggestDbOption[]; dbSlotsLeft: number; refSlotsLeft: number
}
export interface SuggestPicks { dbConnectionIds: number[]; refs: RefRepoInput[]; suggestionIds: number[] }

export const SUGGEST_FOLLOW_UP = {
  db: '방금 연결한 DB로 확인해서 앞의 답을 보완해 주세요.',
  ref: '방금 추가한 참조 레포를 보고 앞의 답을 보완해 주세요.',
  both: '방금 연결한 DB와 추가한 참조 레포를 보고 앞의 답을 보완해 주세요.',
} as const

export function upsertSuggestion(list: SessionSuggestion[], s: SessionSuggestion): SessionSuggestion[]   // id로 교체, 없으면 끝에 추가(새 배열)
export function pendingForTurn(list: SessionSuggestion[], turnSeq: number): SessionSuggestion[]
export function decidedForTurn(list: SessionSuggestion[], turnSeq: number): SessionSuggestion[]         // PENDING이 아닌 것
export function initialDbPicks(s: SessionSuggestion): number[]
   // dbSlotsLeft가 0이면 []; dbConnectionId가 dbOptions에 있으면 [그 id]; 없고 dbOptions가 1개면 [그 id]; 아니면 []
export function canPickMore(pickedCount: number, slotsLeft: number): boolean                            // pickedCount < slotsLeft
export function followUpText(hasDb: boolean, hasRef: boolean): string                                   // 둘 다 false면 ''
export function decidedLine(s: SessionSuggestion): string
   // `제안: DB 연결 — 연결함|닫음|연결하지 않음`, 참조는 `제안: 참조 레포 {repoAlias ?? '(삭제된 레포)'} — …`
export function mergeDbIds(draft: number[], picked: number[]): number[]                                 // 순서 유지, 중복 제거
export function mergeRefInputs(draft: RefRepoInput[], picked: RefRepoInput[]): RefRepoInput[]           // repoCatalogId 기준, 초안이 이긴다
```

- Produces (`useInterviewStream.ts`): `InterviewSnapshot.suggestions?: SessionSuggestion[]`, 반환값에 `suggestions: Ref<SessionSuggestion[]>`, `hydrate`가 스냅샷의 제안으로 채우고(`upsertSuggestion`), SSE `suggestion` 이벤트가 upsert한다. 페이로드가 객체가 아니거나 `id`가 숫자가 아니면 무시한다.

- [ ] **Step 1: 실패하는 시험 쓰기** — `sessionSuggest.spec.ts`:

```ts
it('upsertSuggestion은 같은 id를 교체하고 새 id는 끝에 더한다 — 원본 배열은 그대로')
it('pendingForTurn·decidedForTurn이 턴과 상태로 가른다')
it('Claude가 고른 연결이 선택지에 있으면 그것을 미리 고른다')
it('고른 연결이 선택지에 없고 선택지가 하나면 그것을 고른다')
it('고른 연결이 없고 선택지가 여럿이면 아무것도 고르지 않는다')
it('DB 자리가 없으면 아무것도 고르지 않는다')
it('남은 자리만큼만 체크할 수 있다')                    // canPickMore(0,1) true, (1,1) false, (0,0) false
it('이어 묻기 문장은 DB·참조·둘 다에 따라 다르다')
it('지난 제안 한 줄은 상태와 종류를 담는다')            // ACCEPTED→연결함, DISMISSED→닫음, SUPERSEDED→연결하지 않음; 별칭 null → (삭제된 레포)
it('mergeDbIds는 초안 뒤에 새 id를 붙이고 중복을 뺀다')
it('mergeRefInputs는 같은 레포면 초안의 브랜치를 유지한다')
```

`useInterviewStream.spec.ts`에 추가: `스냅샷의 suggestions로 채운다`, `suggestion 이벤트가 같은 id를 교체한다`(PENDING → ACCEPTED), `모양이 틀린 suggestion 이벤트는 무시한다`.

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run composables/sessionSuggest.spec.ts composables/useInterviewStream.spec.ts`

- [ ] **Step 3: 구현**

- [ ] **Step 4: 통과 확인** — 같은 명령 → 새 시험 전부 통과, 기존 `useInterviewStream` 시험 그대로 통과.

- [ ] **Step 5: Commit** — `feat: 질문 제안 프론트 타입·상태`

---

### Task 9: 프론트 — 카드 · 패널 슬롯 · 페이지 연결

**Files:**
- Create: `frontend/components/chat/SessionSuggestCard.vue`
- Modify: `frontend/components/InterviewPanel.vue`, `frontend/pages/questions/[id].vue`, `frontend/scripts/hydration-smoke.mjs`
- Test: `frontend/components/chat/SessionSuggestCard.spec.ts`, `frontend/components/InterviewPanel.spec.ts`(기존에 추가), `frontend/test/question-suggest-page.spec.ts`(새로 — 페이지 시험은 기존 질문 대화 페이지 시험 파일의 마운트 방식을 따른다)

**Interfaces:**
- Consumes: Task 8 전부, `DbConnectionPicker`가 쓰는 DB 종류 라벨 함수(`composables/dbConnections.ts` — 카드의 `이름 · 종류` 표기에 같은 함수를 쓴다)
- Produces:
  - `SessionSuggestCard` props(파일 안에 타입을 직접 선언한다 — `~/` import 금지): `suggestions: SessionSuggestion[]`(한 답변의 `PENDING`들), `disabled: boolean`, `busy: boolean`. emits: `accept(picks: SuggestPicks)`, `dismiss(ids: number[])`.
    - data-test: 카드 `suggest-card`, DB 줄 `suggest-db`, DB 체크박스 `suggest-db-opt-{id}`, 참조 줄 `suggest-ref-{repoCatalogId}`(체크박스 포함), 버튼 `suggest-accept`·`suggest-dismiss`, 자리 없음 사유 `suggest-db-full`·`suggest-ref-full`, 선택지 없음 `suggest-db-none`.
    - 문구: DB 제목 `DB 연결이 있으면 더 정확히 답할 수 있습니다`, 참조 제목 `이 레포도 함께 보면 좋습니다: {별칭}`, 버튼 `연결하고 다시 묻기`·`닫기`, 자리 없음 `DB 연결은 3개까지 붙일 수 있습니다`·`참조 레포는 3개까지 붙일 수 있습니다`, 선택지 없음 `붙일 수 있는 연결이 없습니다`.
    - 체크박스의 접근 가능한 이름은 `q-checkbox`의 `label` prop으로 준다(DB: `{이름} · {종류 라벨}`, 참조: `{별칭} 참조로 추가`).
    - `accept`의 `refs`는 `{ repoCatalogId, branch: defaultBranch }`(null이면 그 참조는 체크할 수 없게 끈다). `suggestionIds`는 **체크된 것이 있는 제안**의 id만 담는다.
    - `dismiss`는 카드의 모든 제안 id를 낸다.
  - `InterviewPanel`:
    - `AskExtra`에 `text?: string`, `acceptSuggestionIds?: number[]`. `sendAnswer(extra)`는 `extra.text`가 문자열이면 그 글을 보내고 **`answer`(입력창)를 비우지 않으며 `files`를 보내지 않는다**. `acceptSuggestionIds`가 비지 않은 배열이면 바디에 싣는다(질문 세션만).
    - 새 scoped slot `suggest` — 가장 최근 assistant 턴 말풍선의 `#footer` 안, 목업 카드 아래. slot props: `{ suggestions: SessionSuggestion[] /* 그 턴의 PENDING */, send: (extra: AskExtra) => Promise<boolean>, dismiss: (ids: number[]) => Promise<void>, awaiting: boolean, sending: boolean }`. 그 턴에 `PENDING`이 없으면 슬롯을 그리지 않는다.
    - 지난 제안 한 줄: 턴마다 `decidedForTurn`의 `decidedLine`을 말풍선 `#footer`에 작은 회색 글로(`data-test="suggest-decided"`). 가장 최근 턴이 아닌 턴에 남은 `PENDING`도 이 한 줄(`연결하지 않음`)로만 보인다 — 버튼 없음.
    - `dismiss(ids)`: id마다 `POST {apiBase}/{sessionId}/suggestions/{id}/dismiss` → 응답으로 `upsertSuggestion`. 409는 조용히 넘기고(이미 처리됨) 그 밖의 실패는 `$q.notify` negative.
  - `pages/questions/[id].vue`: `#suggest` 슬롯에 카드를 넣고 `@accept`에서

```ts
async function onAcceptSuggestion(send: SendFn, picks: SuggestPicks) {
  const dbIds = mergeDbIds(pickedDb.value, picks.dbConnectionIds)
  const refInputs = mergeRefInputs(toInputs(pickedRefs.value), picks.refs)
  const ok = await send({
    model: picked.model, effort: picked.effort, mcpCatalogIds: pickedMcp.value,
    ...(dbState.enabled ? { dbConnectionIds: dbIds } : {}),
    ...(picks.refs.length > 0 || refsChanged() ? { refRepos: refInputs } : {}),
    text: followUpText(picks.dbConnectionIds.length > 0, picks.refs.length > 0),
    acceptSuggestionIds: picks.suggestionIds,
  })
  if (ok) { pickedDb.value = dbIds; /* 참조 초안에도 고른 것을 더한다 */ }
}
```
    `files`는 넘기지 않고 `files.value`도 비우지 않는다. `SendFn`의 extra 타입에 `text?`·`acceptSuggestionIds?`를 더하고 `files`를 선택 칸으로 바꾼다. `refsChanged()`는 `onSend`의 기존 판정을 함수로 뽑아 둘이 함께 쓴다.
  - `hydration-smoke.mjs`: `QUESTION` 목에 `suggestions: []`, 그리고 `POST …/suggestions/\d+/dismiss` 목 응답.

- [ ] **Step 1: 실패하는 시험 쓰기**

`SessionSuggestCard.spec.ts`(마운트는 `RepoRouteCard.spec.ts`의 방식):
```ts
it('DB 제안의 이유·보고 싶은 테이블·선택지를 그린다')
it('Claude가 고른 연결이 미리 체크된다')
it('체크된 것이 없으면 연결하고 다시 묻기가 꺼진다')
it('연결하고 다시 묻기는 고른 연결과 그 제안 id를 낸다')          // emitted('accept')[0][0] = { dbConnectionIds:[4], refs:[], suggestionIds:[1] }
it('참조 제안은 기본 브랜치로 낸다')                             // refs: [{ repoCatalogId: 11, branch: 'main' }]
it('DB와 참조를 함께 고르면 둘 다와 두 제안 id를 낸다')
it('체크하지 않은 제안의 id는 내지 않는다')
it('남은 자리만큼만 체크할 수 있다')                             // refSlotsLeft=1, 참조 제안 2개 → 하나 체크하면 나머지 체크박스 disabled + suggest-ref-full
it('DB 자리가 없으면 선택지가 꺼지고 사유가 보인다')
it('선택지가 비었으면 붙일 수 있는 연결이 없습니다를 보인다')
it('기본 브랜치가 없는 참조 제안은 체크할 수 없다')
it('닫기는 카드의 모든 제안 id를 낸다')
it('disabled면 버튼과 체크박스가 모두 꺼진다')
it('체크박스에 접근 가능한 이름이 있다')                         // role="checkbox" 요소의 aria-label
it('카드 안에 Quasar 반응형 헬퍼 이름의 클래스가 없다')           // RepoRouteCard.spec.ts의 같은 시험 — 콜백 인자에 타입을 적는다: (el: Element) … (c: string)
```

`InterviewPanel.spec.ts`:
```ts
it('text를 넘기면 입력창을 건드리지 않고 첨부도 보내지 않는다')
   // 입력창에 '쓰던 글' → send({ text: '고정 문장', files: [파일], acceptSuggestionIds: [1] }) → ask 바디 answer='고정 문장', acceptSuggestionIds=[1], JSON 전송(FormData 아님), 입력창 값 '쓰던 글' 그대로
it('text 없이 보내면 지금처럼 입력창의 글을 보내고 비운다')        // 회귀
it('가장 최근 답변의 PENDING 제안만 suggest 슬롯으로 나간다')
it('지난 답변의 제안은 한 줄로만 보이고 슬롯이 없다')
it('입력 대기가 아니면 슬롯의 awaiting이 false다')
it('dismiss는 제안마다 POST하고 응답으로 상태를 바꾼다')
it('작업 인터뷰(INTERVIEW) 패널에는 제안 슬롯이 없다')
```

`question-suggest-page.spec.ts`:
```ts
it('받아들이면 초안의 DB에 고른 연결을 더해 보내고 초안도 맞춘다')  // pickedDb [3] + picks [4] → 바디 dbConnectionIds [3,4]
it('참조를 받아들이면 refRepos에 더해 보낸다')
it('받아들여도 대기 중인 첨부는 그대로 남는다')
it('전송이 실패하면 초안을 바꾸지 않는다')                         // send → false
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/chat/SessionSuggestCard.spec.ts components/InterviewPanel.spec.ts test/question-suggest-page.spec.ts`

- [ ] **Step 3: 구현** — 카드의 폭·여백은 `RepoRouteCard.vue`를 따른다(좁은 화면은 `@media (max-width: 599px)`로 폭 100%). 색·간격은 기존 카드 변수 그대로, 새 색을 들이지 않는다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 전부 통과 → `npx vitest run`(전체) 실패 0 → **`npx nuxi typecheck` 종료 코드 0**(지난 PR의 CI가 시험 파일의 암시적 any로 실패했다 — 시험 파일도 타입 검사 대상이다).

- [ ] **Step 5: Commit** — `feat: 질문 제안 카드와 대화 화면 연결`

---

### Task 10: 문서 · 전체 확인

**Files:**
- Modify: `CLAUDE.md`(질문 세션 절에 한 묶음, "자주 보는 코드" 표에 한 줄), `docs/superpowers/specs/2026-11-22-question-session-suggest-design.md`(상태 줄만 `구현됨`으로)

- [ ] **Step 1: CLAUDE.md 쓰기** — "질문 레포 추천 카드" 묶음 바로 뒤에 `**질문 세션 대화 중 제안(2026-11-22, 스펙 …)**` 묶음. 담을 것: 무엇을 하는지 두 문장, 후보 계산이 한 곳(`QuestionSuggestCandidates`)이고 요청자 기준이라는 것, Claude는 제안만 하고 붙이는 것은 `ask`뿐이라는 것, 상한, 상태 4개와 전이(받아들임·닫음·지나감), 제안 없는 세션은 바이트까지 같다는 것, 예약 이름 `suggest`, 끄기 `APP_QUESTION_SUGGEST_ENABLED=false` + API 재기동, 배포 순서 API(V48) → 인터뷰 서비스(재기동만) → 프론트(`.output` 재빌드)와 어긋나도 안전한 이유, 배포 뒤 확인(제안이 붙는 빈도·받아들인 비율은 `com.question_suggestion`을 SQL로). 실제 사이트 이름을 쓰지 않는다.

- [ ] **Step 2: 전체 시험**

```bash
./gradlew test --no-daemon                                   # 실패 0 (Testcontainers는 건너뜀)
cd netismaker-interview-service && npx vitest run && npx tsc -p tsconfig.json --noEmit
cd ../frontend && npx vitest run && npx nuxi typecheck
```

세 묶음의 통과·건너뜀 수를 기록한다. `ProcessRunnerTimeoutTest`가 "Failed to close extension context"로 실패하면 단독 재실행으로 확인한다(이 PC의 알려진 간헐 실패).

- [ ] **Step 3: 바이트 불변 확인** — `git diff origin/main --stat -- netismaker-interview-service/test/fixtures` 가 비어 있는지(기존 골든 파일을 고치지 않았는지), `git diff origin/main -- netismaker-interview-service/src/sdk/permissions.ts`가 비어 있는지 본다.

- [ ] **Step 4: Commit** — `docs: 질문 세션 대화 중 제안 — CLAUDE.md`

**이 계획이 하지 않는 것:** 푸시·PR·병합·라이브 배포(사용자가 따로 요청한다), ESLint(worktree 정션에 설정이 없을 수 있다 — 되면 `npm run lint`로 새 경고가 없는지만 본다), 하이드레이션 스모크 실행(CI 몫), 실제 Claude 세션으로 제안이 나오는지 확인(배포 뒤 라이브에서).
