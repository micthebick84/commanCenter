# 요청 게시판 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 기능·MCP·레포 추가를 요청하고, 관리자가 같은 화면에서 확인·답글·상태 변경을 하는 게시판을 만든다. 레포 추가 요청은 사내 GitLab 후보를 한 번 클릭해 레포 카탈로그에 바로 등록할 수 있게 한다.

**Architecture:**
- 백엔드는 API 프로파일에 다음을 추가한다.
  - `com.request_post` / `com.request_comment` 테이블(V30)과 JPA 엔티티
  - 게시판 서비스 `RequestBoardService`
  - GitLab 프로젝트 조회 클라이언트 `GitLabProjectClient`와 후보 추측·검색 서비스 `RepoRequestService`
  - 컨트롤러 2개
- 레포 등록은 기존 `RepoCatalogService.create`를 그대로 재사용한다.
- 프론트는 Nuxt 페이지 `/requests`, `/requests/[id]`와 드롭다운 컴포넌트 `RepoAddDropdown`을 추가하고, 레이아웃 탭과 배지를 붙인다.

**Tech Stack:** Spring Boot 3 · Spring Data JPA · Flyway · PostgreSQL(`com` 스키마) · `java.net.http` + Jackson · JUnit5/Mockito/AssertJ/Testcontainers · Nuxt 3 + Quasar · vitest

**Spec:** `docs/superpowers/specs/2026-10-19-request-board-design.md`

## Global Constraints

- **작업 위치:** worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\request-board`, 브랜치 `feature/request-board`. 본 체크아웃(라이브 `bootRun`)에서 빌드나 테스트를 돌리지 않는다.
- **커밋 작성자:** `git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit …`. 메시지 끝에는 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
- **마이그레이션:** `V30__request_board.sql`. V29는 PR #95가 쓰므로 비워 둔다. 병합 순서는 #95가 먼저다(스펙 §10).
- **스키마:** `ddl-auto: validate`이므로 엔티티의 컬럼 이름·타입·길이를 스펙 §5 DDL과 정확히 맞춘다.
- **API 빈:** 모두 `@Profile("api")`를 붙인다. 관리자 경로는 `@PreAuthorize("hasAuthority('ROLE_ADMIN')")`, 사용자 경로는 `@PreAuthorize("isAuthenticated()")`.
- **오류 처리:** `TaskException(HttpStatus, 메시지)`를 던지면 `GlobalExceptionHandler`가 `{error, message}`로 응답한다.
- **남의 요청 접근:** 404 `"요청을 찾을 수 없습니다"`. 403이 아니다.
- **상태 값:** DB에는 `접수`/`검토중`/`처리완료`/`반려`가 들어가고, Java·JSON 쪽 이름은 `RECEIVED`/`REVIEWING`/`DONE`/`REJECTED`다. 응답에는 `status`(enum 이름)와 `statusLabel`(한글)을 함께 싣는다(`TaskResponse` 관례).
- **종류 값:** `FEATURE`/`MCP`/`REPO`. 화면 라벨은 `기능 추가`/`MCP 추가`/`REPO 추가`.
- **길이 상한:** 제목 200, 내용 10000, 레포 힌트 500, 답글 5000, 반려 사유 2000.
- **사용자별 열린 요청 상한:** `app.request-board.max-open-per-user`, 기본 20. 초과하면 429 `"처리 중인 요청이 너무 많습니다(최대 20건)"`.
- **목록 상한:** 최근 200건.
- **GitLab 설정:** `app.gitlab.base-url`, `app.gitlab.token`(기존 키). 둘 중 하나라도 비면 503 `"사내 GitLab이 설정되지 않았습니다"`. GitLab 호출이 실패하면 502 `"GitLab 조회에 실패했습니다"`.
- **토큰 노출 금지:** GitLab 토큰 원문은 어떤 예외 메시지, 로그, 응답에도 들어가면 안 된다.
- **프론트 스타일:** 작은따옴표, 세미콜론 없음, `<script setup lang="ts">`. `frontend/` 전체에 prettier를 돌리지 않는다.
- **프론트 렌더 규칙**
  - 템플릿의 관리자 분기는 `useHydrationSafeAuth()`로 한다.
  - 본문과 답글은 `renderMarkdown`으로만 렌더한다.
  - 모바일 분기는 `$q.screen.lt.md`로 한다.
  - `q-dialog` 안 콘텐츠의 루트 요소는 `<div>`다.
  - 확인 창은 `confirmDialog($q, …)`를 쓴다(`window.confirm` 금지).
- **백엔드 테스트 실행**
  - 단위 테스트: `./gradlew test --tests '<클래스>'`
  - 통합 테스트: `RUN_TESTCONTAINERS=true ./gradlew test --tests '<클래스>'`. Docker가 필요하다. 이 PC에서는 원격 Docker이므로, 안 되면 CI에 맡기고 그 사실을 보고한다.
- **프론트 테스트 실행:** `frontend/`에서 `npx vitest run <파일>`. 새 worktree라면 `node_modules`는 본 체크아웃 것을 정션으로 연결한다(메모리 "worktree 정션" 규칙).

## Review Focus

1. **"추가" 후보를 두 번 빠르게 클릭할 때:** 두 번째 요청은 409 `"이미 처리된 요청입니다"`를 받아야 하고, 카탈로그 행은 하나만 생겨야 한다. Task 2의 `complete_repo_on_done_conflicts`(행 락 `findByIdForUpdate` 뒤 재검사)와 Task 5의 `addRepo_on_done_request_conflicts`가 담당한다.
2. **관리자가 자기 요청을 열 때:** `requesterReadAt`과 `adminReadAt`이 둘 다 갱신되어 양쪽 모두 unread가 꺼져야 한다. Task 2의 `admin_viewing_own_request_marks_both_read`가 담당한다.
3. **레포 힌트 URL 변형:** `https://gitlab.hamon.vip/g/p.git`, `…/g/p/-/tree/main`, `g/sub/p` 모두 같은 경로로 단건 조회되어야 한다. Task 5의 `hintPath_*`가 담당한다.
4. **GitLab 미설정:** 후보와 검색은 503이어야 하지만, 게시판 목록·상세·답글은 정상 동작해야 한다. Task 4의 `not_configured_throws_503`과 Task 6 통합 테스트의 `candidates_503_when_gitlab_unconfigured`가 담당한다.
5. **처리완료 뒤 카탈로그 항목이 삭제될 때:** 상세 응답이 `repoCatalogDeleted=true`이고 `repoGitUrl`이 유지되어야 한다. Task 2의 `detail_marks_catalog_deleted`가 담당한다.

---

## File Structure

**백엔드** (`src/main/java/com/hamonsoft/netismaker/`)

| 파일 | 책임 |
|---|---|
| `resources/db/migration/V30__request_board.sql` | 스펙 §5 DDL 그대로 |
| `entity/RequestKind.java` | `FEATURE, MCP, REPO` + `label()` |
| `entity/RequestStatus.java`, `entity/RequestStatusConverter.java` | 한글 DB 값 · 전이 규칙 |
| `entity/RequestCommentKind.java` | `COMMENT, SYSTEM` |
| `entity/RequestPost.java`, `entity/RequestComment.java` | 엔티티 |
| `repository/RequestPostRepository.java`, `repository/RequestCommentRepository.java` | 조회 |
| `dto/RequestBoardDto.java` | 요청/응답 record 묶음 |
| `service/RequestBoardService.java` | 게시판 규칙 전부 + 레포 등록 완료 처리(트랜잭션) |
| `service/GitLabProjectClient.java` | GitLab REST 조회 |
| `service/RepoRequestService.java` | 후보 추측 · 검색 · 등록 진입점(트랜잭션 밖 GitLab 호출) |
| `controller/RequestBoardController.java`, `controller/RepoRequestController.java` | HTTP |
| `resources/application.yml` | `app.request-board.max-open-per-user: 20` |

**프론트** (`frontend/`)

| 파일 | 책임 |
|---|---|
| `composables/requestBoard.ts` | 타입 · 라벨 · 색 · 관리자 전이 목록(서버와 동일) |
| `components/requests/RequestFormDialog.vue` | 새 요청 / 수정 다이얼로그 |
| `components/requests/RequestThread.vue` | 스레드 + 답글 입력 |
| `components/requests/RepoAddDropdown.vue` | 후보 · 검색 · 클릭 등록 |
| `pages/requests/index.vue` | 목록(사용자 · 관리자) |
| `pages/requests/[id].vue` | 상세 |
| `layouts/default.vue` | "요청" 탭(데스크톱 · 모바일) + 관리자 배지 |
| `scripts/hydration-smoke.mjs` | 경로 · 목 응답 추가 |

---

### Task 1: 스키마 · 엔티티 · 상태 규칙

**Files:**
- Create: `src/main/resources/db/migration/V30__request_board.sql`, `entity/RequestKind.java`, `entity/RequestStatus.java`, `entity/RequestStatusConverter.java`, `entity/RequestCommentKind.java`, `entity/RequestPost.java`, `entity/RequestComment.java`, `repository/RequestPostRepository.java`, `repository/RequestCommentRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/RequestStatusTest.java`, `src/test/java/com/hamonsoft/netismaker/entity/RequestPostTest.java`

**Interfaces — Produces:**
- `enum RequestKind { FEATURE, MCP, REPO; String label() }`. 라벨은 "기능 추가", "MCP 추가", "REPO 추가". `@Enumerated(EnumType.STRING)`로 저장한다.
- `enum RequestStatus`
  - 값과 DB 문자열: `RECEIVED("접수")`, `REVIEWING("검토중")`, `DONE("처리완료")`, `REJECTED("반려")`
  - 메서드: `String dbValue()`, `static RequestStatus fromDb(String)`, `boolean isOpen()`(RECEIVED·REVIEWING), `boolean canTransitionTo(RequestStatus next)`
  - 전이표(스펙 §5.2):
    - RECEIVED → REVIEWING, DONE, REJECTED
    - REVIEWING → DONE, REJECTED, RECEIVED
    - DONE → REVIEWING
    - REJECTED → REVIEWING
- `RequestStatusConverter`: `@Converter(autoApply = true)`. `TaskStatusConverter`와 같은 모양이다.
- `enum RequestCommentKind { COMMENT, SYSTEM }`, STRING으로 저장
- `RequestPost`
  - `@Table(name="request_post", schema="com")`, Lombok `@Getter @Setter @NoArgsConstructor(access = PROTECTED)`
  - 필드는 DDL 컬럼을 camelCase로 옮긴다.
  - `static RequestPost create(RequestKind kind, String title, String content, String repoHint, String requesterId)`
    - status=RECEIVED
    - 모든 시각=now: createdAt, updatedAt, lastRequesterActivityAt, requesterReadAt
    - 종류가 REPO가 아니면 repoHint=null
  - `boolean isOwnedBy(String userId)`
  - `void touchRequester(OffsetDateTime now)`: lastRequesterActivityAt=now, updatedAt=now, requesterReadAt=now(자기 활동은 읽은 것)
  - `void touchAdmin(OffsetDateTime now)`: lastAdminActivityAt=now, updatedAt=now, adminReadAt=now
  - `void markRead(boolean asRequester, boolean asAdmin, OffsetDateTime now)`
  - `boolean unreadFor(boolean asAdmin)`
    - 관리자: `adminReadAt == null || lastRequesterActivityAt.isAfter(adminReadAt)`
    - 사용자: `lastAdminActivityAt != null && lastAdminActivityAt.isAfter(requesterReadAt)`
- `RequestComment`
  - `@Table(name="request_comment", schema="com")`. `requestId`는 Long 컬럼이다(연관 매핑 없음).
  - `static RequestComment create(Long requestId, RequestCommentKind kind, String authorId, boolean authorAdmin, String body)`
- `RequestPostRepository extends JpaRepository<RequestPost, Long>`
  - `List<RequestPost> findByRequesterIdOrderByUpdatedAtDesc(String requesterId, Limit limit)`
  - `long countByRequesterIdAndStatusIn(String requesterId, Collection<RequestStatus> statuses)`
  - `@Lock(PESSIMISTIC_WRITE) @Query("select p from RequestPost p where p.id = :id") Optional<RequestPost> findByIdForUpdate(Long id)`
  - `@Query` 관리자 목록 `List<RequestPost> findForAdmin(RequestKind kind, Collection<RequestStatus> statuses, boolean includeUnread, Pageable page)`
    - 조건: `(:kind is null or p.kind = :kind) and (p.status in :statuses or (:includeUnread = true and (p.adminReadAt is null or p.lastRequesterActivityAt > p.adminReadAt)))`
    - 정렬: `order by p.updatedAt desc`
  - `@Query` `long countAttention()`: `p.status = RECEIVED or p.adminReadAt is null or p.lastRequesterActivityAt > p.adminReadAt`
  - `long countByStatus(RequestStatus status)`
- `RequestCommentRepository extends JpaRepository<RequestComment, Long>`
  - `List<RequestComment> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId)`
  - `boolean existsByRequestIdAndKindAndAuthorAdminTrue(Long requestId, RequestCommentKind kind)`

- [ ] **Step 1: 실패하는 테스트 작성**
  - `RequestStatusTest`
    - `db_values_round_trip`: 네 값 모두 `fromDb(s.dbValue()) == s`이고 `"검토중"`이 REVIEWING이다.
    - `transition_table`: 위 전이표의 허용 쌍은 모두 true, 그 밖의 쌍은 모두 false. 같은 상태끼리는 false다(같은 상태 요청은 서비스가 no-op으로 처리).
  - `RequestPostTest`
    - `create_drops_hint_for_non_repo`
    - `requester_unread_after_admin_activity`: touchAdmin 뒤 `unreadFor(false)`가 true이고, `markRead(true,false,later)` 뒤 false다.
    - `admin_unread_for_new_post`: 생성 직후 `unreadFor(true)`가 true다.
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests 'com.hamonsoft.netismaker.entity.Request*'`를 돌리면 컴파일 오류로 FAIL이 나야 한다.
- [ ] **Step 3: V30 SQL(스펙 §5 그대로, 주석 한국어)과 위 enum·엔티티·리포지토리를 구현한다.**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat: 요청 게시판 스키마(V30)와 엔티티`

---

### Task 2: 게시판 서비스 규칙

**Files:**
- Create: `dto/RequestBoardDto.java`, `service/RequestBoardService.java`
- Modify: `src/main/resources/application.yml`(`app.request-board.max-open-per-user: 20`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/RequestBoardServiceTest.java`(Mockito, `RepoCatalogServiceTest` 관례)

**Interfaces — Consumes:** Task 1의 전부, `RepoCatalogService`, `RepoCatalogRepository`

**Interfaces — Produces:** `RequestBoardDto`의 record 목록

| record | 필드 |
|---|---|
| `CreateRequest` | `@NotNull RequestKind kind`, `@NotBlank @Size(max=200) String title`, `@NotBlank @Size(max=10000) String content`, `@Size(max=500) String repoHint` |
| `UpdateRequest` | `@NotBlank @Size(max=200) String title`, `@NotBlank @Size(max=10000) String content`, `@Size(max=500) String repoHint` |
| `CommentRequest` | `@NotBlank @Size(max=5000) String body` |
| `StatusChangeRequest` | `@NotNull RequestStatus status`, `@Size(max=2000) String reason` |
| `Summary` | `long received`, `long attention` |
| `ListItem` | `Long id`, `RequestKind kind`, `String kindLabel`, `String title`, `RequestStatus status`, `String statusLabel`, `String requesterId`, `boolean unread`, `OffsetDateTime createdAt`, `OffsetDateTime updatedAt` |
| `CommentView` | `Long id`, `RequestCommentKind kind`, `String authorId`, `boolean authorAdmin`, `String body`, `OffsetDateTime createdAt` |
| `Detail` | `Long id`, `RequestKind kind`, `String kindLabel`, `String title`, `String content`, `String repoHint`, `RequestStatus status`, `String statusLabel`, `String requesterId`, `Long repoCatalogId`, `String repoAlias`, `String repoGitUrl`, `boolean repoCatalogDeleted`, `boolean editable`, `OffsetDateTime createdAt`, `OffsetDateTime updatedAt`, `List<CommentView> comments` |
| `RepoCandidate` | `long id`, `String name`, `String pathWithNamespace`, `String defaultBranch`, `String description`, `boolean registered` (Task 5용) |

`Detail` 필드 규칙:
- `editable` = 열람자가 요청자 본인이고 상태가 RECEIVED
- `repoCatalogDeleted` = `repoGitUrl != null && repoCatalogId == null`
- `repoAlias`는 `repoCatalogId`로 카탈로그를 조회해 채운다.

`RequestBoardService` 메서드:
- `ListItem create(CreateRequest, String userId)`
  - 열린 요청이 상한 이상이면 429.
  - 저장한 뒤 SYSTEM 기록은 남기지 않는다.
- `List<ListItem> listMine(String userId)`: `Limit.of(200)`
- `List<ListItem> listForAdmin(RequestKind kind, Set<RequestStatus> statuses, boolean includeUnread)`
  - statuses가 비어 있으면 RECEIVED와 REVIEWING을 쓴다.
  - `PageRequest.of(0, 200)`
- `Detail getAndMarkRead(Long id, String viewerId, boolean admin)`
  - 본인이 아니고 관리자도 아니면 404.
  - `markRead(owner, admin, now)`
- `Detail update(Long id, UpdateRequest, String userId)`
  - 본인이 아니면 404, RECEIVED가 아니면 409 `"접수 상태에서만 수정할 수 있습니다"`.
  - 종류가 REPO가 아니면 힌트는 null.
  - `touchRequester`
- `void cancel(Long id, String userId)`
  - 본인이 아니면 404, RECEIVED가 아니면 409.
  - 관리자 COMMENT가 있으면 409 `"관리자 답글이 있는 요청은 취소할 수 없습니다"`.
  - 통과하면 `deleteById`(답글은 CASCADE).
- `CommentView addComment(Long id, CommentRequest, String userId, boolean admin)`
  - 본인이 아니고 관리자도 아니면 404.
  - 관리자이면 `touchAdmin`, 아니면 `touchRequester`. 관리자가 자기 요청에 다는 경우는 두 개 다 호출한다.
- `Detail changeStatus(Long id, StatusChangeRequest, String adminId)`
  - 없으면 404.
  - 같은 상태면 no-op으로 현재 Detail을 돌려준다.
  - `!canTransitionTo`면 409 `"바꿀 수 없는 상태입니다: <현재> → <다음>"`.
  - REJECTED인데 reason이 비어 있으면 400 `"반려 사유를 입력하세요"`.
  - SYSTEM 기록 본문은 `"관리자(<adminId>)가 상태를 <라벨>로 변경"`이고, 반려라면 뒤에 `" — 사유: <reason>"`을 붙인다.
  - `touchAdmin`
- `Summary summary()`: `countByStatus(RECEIVED)`, `countAttention()`
- `@Transactional(readOnly = true) void assertOpenRepoRequest(Long id)`
  - `findById`로 확인만 한다(락 없음). 없으면 404, REPO가 아니면 409 `"REPO 추가 요청이 아닙니다"`, `!isOpen`이면 409 `"이미 처리된 요청입니다"`.
  - Task 5가 GitLab 호출 전에 빠르게 실패시키려고 쓴다. 락 쿼리는 트랜잭션 밖에서 부를 수 없으므로 이 메서드는 `findByIdForUpdate`를 쓰지 않는다.
- `@Transactional Detail completeRepoRequest(Long id, GitLabProjectClient.GitLabProject project, String gitUrl, String adminId)`
  - Task 5가 부른다. 시그니처는 여기서 확정하고 구현도 이 Task에서 한다. `GitLabProject` record는 Task 4가 만들므로, 이 Task에서는 그 record를 먼저 `service/GitLabProjectClient.java`에 **record만** 선언해 두고 Task 4가 클래스를 채운다.
  - 처리 순서:
    1. `findByIdForUpdate`로 행 락을 잡고 `assertOpenRepoRequest`와 같은 검사를 다시 한다(두 번 클릭 경합은 여기서 409).
    2. `RepoUrlParser.parse(gitUrl, gitlabBaseUrl).canonicalUrl()`로 `findByGitUrl`을 조회한다. 있으면 그 항목에 연결만 한다.
    3. 없으면 별칭을 고른다. `project.name()` → 충돌하면 `project.pathWithNamespace()` → 그것도 충돌하면 409 `"이미 존재하는 별칭 — 레포 카탈로그 화면에서 등록하세요"`.
    4. `repoCatalogService.create(new RepoCatalogDto.UpsertRequest(alias, gitUrl, project.defaultBranch(), "요청 #" + id + "에서 등록", true), adminId)`
    5. 요청에 `repoCatalogId`와 `repoGitUrl`(정식 URL)을 기록하고 status를 DONE으로 바꾼다.
    6. SYSTEM 기록 `"관리자(<adminId>)가 레포 카탈로그에 <alias> (<path>) 등록 — 처리완료"`. 연결만 했다면 "등록" 대신 "연결"을 쓴다.
    7. `touchAdmin`
  - `gitlabBaseUrl`은 생성자 `@Value("${app.gitlab.base-url:}")`로 받는다.

- [ ] **Step 1: 실패하는 테스트 작성** (`RequestBoardServiceTest`, 리포지토리는 mock)
  - `create_rejects_when_open_limit_reached`: `countByRequesterIdAndStatusIn`가 20이면 TOO_MANY_REQUESTS
  - `get_other_users_request_is_404`: 소유자 user2, 열람 user1(비관리자) → NOT_FOUND
  - `admin_viewing_own_request_marks_both_read`: 관리자 admin1이 자기 요청을 열면 requesterReadAt과 adminReadAt이 모두 now 이후다.
  - `update_only_in_received`: REVIEWING → CONFLICT
  - `cancel_blocked_by_admin_comment`: `existsBy…AuthorAdminTrue` → true면 CONFLICT, false면 `deleteById` 호출
  - `change_status_same_is_noop`: RECEIVED→RECEIVED면 SYSTEM 기록을 저장하지 않는다.
  - `change_status_illegal_conflicts`: DONE→RECEIVED → CONFLICT
  - `reject_requires_reason`: reason `" "` → BAD_REQUEST
  - `reject_writes_system_comment_with_reason`: 저장된 SYSTEM 본문이 `"관리자(admin1)가 상태를 반려로 변경 — 사유: 중복"`
  - `admin_comment_makes_requester_unread`: addComment(admin) 뒤 해당 post의 `unreadFor(false)`가 true
  - `detail_marks_catalog_deleted`: repoGitUrl 있음, repoCatalogId null → `repoCatalogDeleted` true, `repoAlias` null
  - `complete_repo_creates_catalog_and_closes`: findByGitUrl empty, findByAlias(name) empty → `create` 호출 인자의 alias=name, description=`"요청 #7에서 등록"`. 이어서 status DONE, repoCatalogId 기록.
  - `complete_repo_alias_falls_back_to_path`: findByAlias(name) present, findByAlias(path) empty → alias=path
  - `complete_repo_links_existing_url`: findByGitUrl present → `create`를 호출하지 않고, 연결 문구로 SYSTEM 기록
  - `complete_repo_on_non_repo_conflicts`: kind FEATURE → CONFLICT
  - `complete_repo_on_done_conflicts`: `findByIdForUpdate`가 DONE 요청을 주면 CONFLICT `"이미 처리된 요청입니다"`이고 `create` 호출 0회(두 번 클릭 경합)
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests '*RequestBoardServiceTest'`가 FAIL이어야 한다.
- [ ] **Step 3: 위 시그니처대로 `RequestBoardDto`, `RequestBoardService`, `GitLabProjectClient.GitLabProject` record(빈 껍데기 클래스 안에 선언)와 yml 키를 구현한다.**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat: 요청 게시판 서비스 규칙(상태·읽음·취소·레포 등록 완료)`

---

### Task 3: 게시판 API와 권한 경계

**Files:**
- Create: `controller/RequestBoardController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/RequestBoardApiIntegrationTest.java`(Testcontainers)

**Interfaces — Consumes:** Task 2의 `RequestBoardService`, `RequestBoardDto.*`

**Interfaces — Produces (HTTP):**

| 메서드·경로 | 권한 | 동작 |
|---|---|---|
| `GET /api/requests?all=&kind=&status=&includeUnread=` | 인증 | 관리자이고 `all=true`이면 `listForAdmin(kind, status들, includeUnread 기본 true)`, 그 밖에는 `listMine`. 비관리자가 보낸 `all=true`는 무시한다. |
| `POST /api/requests` | 인증 | 201 `ListItem` |
| `GET /api/requests/{id}` | 인증 | `Detail` |
| `PUT /api/requests/{id}` | 인증 | `Detail` |
| `DELETE /api/requests/{id}` | 인증 | 204 |
| `POST /api/requests/{id}/comments` | 인증 | 201 `CommentView` |
| `POST /api/admin/requests/{id}/status` | 관리자 | `Detail` |
| `GET /api/admin/requests/summary` | 관리자 | `Summary` |

- [ ] **Step 1: 실패하는 테스트 작성**
  - JWT는 `DbConnectionApiIntegrationTest`처럼 **`username` 클레임**으로 사용자를 구분한다. 시드 사용자는 user1, user2, admin1이다.
  - `user_cannot_see_others_request`: user1이 만든 요청을 user2가 GET, PUT, comments, DELETE → 모두 404
  - `non_admin_forbidden_on_admin_paths`: user1으로 `/api/admin/requests/summary`, `/api/admin/requests/{id}/status` → 403
  - `user_all_param_is_ignored`: user2가 `GET /api/requests?all=true` → user1 요청이 없다.
  - `admin_lists_all_with_filters`: user1의 FEATURE와 REPO 요청이 있을 때 admin1이 `?all=true&kind=REPO` → REPO 요청 하나만 보인다.
  - `flow_comment_unread_read`: user1 생성 → admin1 답글 → user1 목록에서 `unread=true` → user1 상세 GET → 목록에서 `unread=false`
  - `summary_counts_attention_for_reply_on_done`: admin1이 DONE으로 변경(attention 0, 관리자 활동이 읽음 처리) → user1 답글 → summary `attention=1`, `received=0`
  - `validation_400_on_blank_title`
  - 이 클래스가 컨텍스트를 띄우면서 V30 `validate`도 함께 검증된다.
- [ ] **Step 2: 실행해서 실패 확인.** `RUN_TESTCONTAINERS=true ./gradlew test --tests '*RequestBoardApiIntegrationTest'`가 FAIL이어야 한다. Docker를 쓸 수 없으면 컴파일 확인(`./gradlew compileTestJava`)만 하고 보고서에 적는다.
- [ ] **Step 3: 컨트롤러 구현.** `AuthContext.requireUserId` / `isAdmin`을 쓰고, `@Valid`로 요청을 검증한다. 클래스 Javadoc에 엔드포인트 표를 넣는다(`RepoCatalogController` 관례).
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat: 요청 게시판 API`

---

### Task 4: GitLab 프로젝트 조회 클라이언트

**Files:**
- Modify (Task 2에서 만든 껍데기): `service/GitLabProjectClient.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/GitLabProjectClientTest.java`(`com.sun.net.httpserver.HttpServer`, `GitLabMrCloserTest` 관례)

**Interfaces — Produces:**
- `@Component @Profile("api") class GitLabProjectClient`
- 생성자: `(@Value("${app.gitlab.base-url:}") String baseUrl, @Value("${app.gitlab.token:}") String token, ObjectMapper om)`
- `record GitLabProject(long id, String name, String pathWithNamespace, String defaultBranch, String description)`
- `boolean configured()`
- `List<GitLabProject> search(String q, int limit)`
  - `GET {base}/api/v4/projects?search=<URL인코딩>&simple=true&archived=false&order_by=last_activity_at&per_page=<limit>`
- `Optional<GitLabProject> findByPath(String fullPath)`
  - `GET {base}/api/v4/projects/<URLEncoder(fullPath)>`
  - 404이면 `Optional.empty()`
- `Optional<GitLabProject> findById(long id)`
  - `GET {base}/api/v4/projects/{id}`
  - 404이면 empty
- 요청 규칙
  - 모든 요청에 헤더 `PRIVATE-TOKEN`을 싣는다. 타임아웃은 연결과 요청 모두 10초다.
  - `configured()`가 false이면 HTTP를 부르지 않고 `TaskException(SERVICE_UNAVAILABLE, "사내 GitLab이 설정되지 않았습니다")`
  - 그 밖의 비정상 응답이나 I/O 오류는 `TaskException(BAD_GATEWAY, "GitLab 조회에 실패했습니다")`
  - 로그에는 `상태코드 + 경로`만 남기고, 응답 본문은 남기지 않는다.
- `String redact(String s)`(package-private): s 안의 토큰 원문을 `***`로 치환한다. 로그 문자열은 모두 이 함수를 거친다.
- base URL 끝의 `/`는 잘라 낸다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `search_parses_and_sends_token`: 목 서버가 `[{"id":5,"name":"netis-collector","path_with_namespace":"netis/netis-collector","default_branch":"main","description":"수집기"}]`를 돌려준다. 결과 필드가 일치해야 하고, 받은 헤더 `PRIVATE-TOKEN=tok-123`, 쿼리에 `search=collector`와 `per_page=5`가 있어야 한다.
  - `find_by_path_encodes_slashes`: 요청 경로가 `/api/v4/projects/g%2Fsub%2Fp`
  - `find_by_path_404_is_empty`
  - `server_error_maps_to_502_without_token`: 500 응답에 본문 `"bad tok-123"` → 예외 상태 BAD_GATEWAY이고 메시지에 `tok-123`이 없다.
  - `not_configured_throws_503`: token이 `""` → SERVICE_UNAVAILABLE, 목 서버 호출 수 0
  - `redact_masks_token`
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests '*GitLabProjectClientTest'`가 FAIL이어야 한다.
- [ ] **Step 3: 구현.** `java.net.http.HttpClient`와 `ObjectMapper.readTree`로 snake_case 필드를 읽는다.
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat: 사내 GitLab 프로젝트 조회 클라이언트`

---

### Task 5: 레포 후보 추측 · 검색 · 클릭 등록 서비스

**Files:**
- Create: `service/RepoRequestService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoRequestServiceTest.java`

**Interfaces — Consumes:**
- `GitLabProjectClient`(Task 4)
- `RequestBoardService.assertOpenRepoRequest` / `completeRepoRequest`(Task 2)
- `RepoCatalogRepository.findByGitUrl`
- `RepoUrlParser`

**Interfaces — Produces:**
- `List<RequestBoardDto.RepoCandidate> candidates(Long requestId)`: 최대 5개. 요청이 REPO가 아니면 409.
- `List<RequestBoardDto.RepoCandidate> search(String q)`: trim 뒤 2자 미만이면 400 `"검색어를 2자 이상 입력하세요"`. 최대 10개.
- `RequestBoardDto.Detail addRepo(Long requestId, long gitlabProjectId, String adminId)`
  - **트랜잭션 없음.**
  - 순서:
    1. `requestBoardService.assertOpenRepoRequest`로 빠르게 실패시킨다.
    2. `client.findById`. 없으면 404 `"GitLab 프로젝트를 찾을 수 없습니다"`.
    3. `gitUrl = gitUrlFor(path)`
    4. `requestBoardService.completeRepoRequest(...)`
- `String gitUrlFor(String pathWithNamespace)`: `stripTrailingSlash(gitlabBaseUrl) + "/" + path + ".git"`
- `static List<String> searchTerms(String hint, String title, String content)`
  - 정규식 `[A-Za-z0-9][A-Za-z0-9._-]{2,}`로 힌트 → 제목 → 내용 순서로 토큰을 뽑는다.
  - 소문자 기준으로 중복을 없애고, 불용어를 뺀 뒤 최대 3개를 돌려준다.
  - 불용어: `repo, repository, git, gitlab, github, http, https, www, com, the, and, for, please`
- `static Optional<String> hintPath(String hint, String gitlabBaseUrl)`
  - 힌트가 사내 GitLab URL이면 base 호스트 뒤 경로를 꺼낸다. `.git`과 `/-/…` 뒤쪽은 잘라 낸다.
  - URL이 아니고 공백 없이 `/`를 포함하면 그대로(앞뒤 `/`, `.git` 제거) 쓴다.
  - 그 밖에는 empty.
- 후보 조립
  1. `hintPath`로 `findByPath`(404면 무시)
  2. 각 term마다 `search(term, 5)`
  3. id로 중복 제거하고, 순서를 유지한 채 5개로 자른다.
  4. `registered = repoCatalogRepository.findByGitUrl(RepoUrlParser.parse(gitUrlFor(path), base).canonicalUrl()).isPresent()`
  - term도 없고 hintPath도 empty이면 GitLab을 부르지 않고 빈 목록을 돌려준다. 이때도 `configured()`가 false면 먼저 503이다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `searchTerms_extracts_ascii_tokens_skips_stopwords`: hint `"collector repo"`, title `"넷이스 수집기 추가"`, content `"gitlab netis-agent 도"` → `["collector", "netis-agent"]`
  - `searchTerms_korean_only_is_empty`
  - `hintPath_full_url`: `"https://gitlab.hamon.vip/g/p.git"` + base `"https://gitlab.hamon.vip"` → `"g/p"`
  - `hintPath_tree_suffix`: `"https://gitlab.hamon.vip/g/sub/p/-/tree/main"` → `"g/sub/p"`
  - `hintPath_bare_path`: `"g/sub/p"` → `"g/sub/p"`
  - `hintPath_plain_word_empty`: `"collector"` → empty
  - `candidates_put_hint_first_dedupe_limit5`: findByPath가 id 1을 주고 search가 [1,2,3,4,5,6]을 주면 결과 id는 [1,2,3,4,5]
  - `candidates_marks_registered`: id 2의 정식 URL이 카탈로그에 있으면 `registered=true`
  - `candidates_korean_only_does_not_call_gitlab`: client의 search와 findByPath 호출이 0회
  - `search_short_query_400`
  - `addRepo_builds_url_from_base_not_gitlab_response`: completeRepoRequest에 넘어간 gitUrl이 `"https://gitlab.hamon.vip/netis/netis-collector.git"`
  - `addRepo_on_done_request_conflicts`: assertOpenRepoRequest가 CONFLICT를 던지면 client.findById 호출 0회이고 예외가 그대로 전파된다.
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests '*RepoRequestServiceTest'`가 FAIL이어야 한다.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat: 레포 추가 요청 후보 추측·검색·클릭 등록`

---

### Task 6: 레포 요청 관리자 API

**Files:**
- Create: `controller/RepoRequestController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/RepoRequestApiIntegrationTest.java`(Testcontainers)

**Interfaces — Produces (HTTP, 전부 관리자):**
- `GET /api/admin/requests/{id}/repo-candidates` → `List<RepoCandidate>`
- `GET /api/admin/gitlab-projects?search=` → `List<RepoCandidate>`
- `POST /api/admin/requests/{id}/add-repo`, 본문 `{"gitlabProjectId": 123}`(record `AddRepoRequest(@NotNull Long gitlabProjectId)`를 `RequestBoardDto`에 추가) → `Detail`

- [ ] **Step 1: 실패하는 테스트 작성**
  - `@MockitoBean GitLabProjectClient`로 GitLab을 대체한다.
  - `non_admin_forbidden`: user1이 세 경로를 부르면 모두 403
  - `candidates_503_when_gitlab_unconfigured`: mock이 503을 던질 때, 후보 경로는 503 + `message`이고 같은 요청의 `GET /api/requests/{id}`는 200
  - `add_repo_end_to_end`: user1이 REPO 요청 생성 → mock `findById(5)`가 `netis/netis-collector` → admin1 add-repo
    - 응답 `status=DONE`, `repoAlias="netis-collector"`
    - `GET /api/admin/repo-catalog`에 그 항목이 있다.
    - 다시 add-repo → 409
  - `@TestPropertySource(properties = "app.gitlab.base-url=https://gitlab.hamon.vip")`로 base URL을 준다. `RepoCatalogService`와 `RequestBoardService`가 같은 키를 읽는다.
- [ ] **Step 2: 실행해서 실패 확인.** `RUN_TESTCONTAINERS=true ./gradlew test --tests '*RepoRequestApiIntegrationTest'`가 FAIL이어야 한다(Docker가 없으면 Task 3과 같이 처리).
- [ ] **Step 3: 컨트롤러 구현**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령 + `./gradlew test`(단위 전체)가 PASS여야 한다.
- [ ] **Step 5: 커밋.** `feat: 레포 추가 요청 관리자 API`

---

### Task 7: 프론트 — 공용 타입 · 목록 페이지 · 작성 다이얼로그

**Files:**
- Create: `frontend/composables/requestBoard.ts`, `frontend/components/requests/RequestFormDialog.vue`, `frontend/pages/requests/index.vue`
- Test: `frontend/composables/requestBoard.spec.ts`, `frontend/test/requests-list.spec.ts`

**Interfaces — Produces:**
- `requestBoard.ts`
  - 타입
    - `RequestKind = 'FEATURE' | 'MCP' | 'REPO'`
    - `RequestStatus = 'RECEIVED' | 'REVIEWING' | 'DONE' | 'REJECTED'`
    - `RequestListItem`, `RequestDetail`, `RequestComment`, `RepoCandidate`: 백엔드 record와 같은 필드 이름
  - `KIND_OPTIONS: { value: RequestKind, label: string }[]`(라벨은 Global Constraints)
  - `STATUS_COLOR: Record<RequestStatus, string>`: RECEIVED `grey`, REVIEWING `blue`, DONE `positive`, REJECTED `negative`
  - `adminTransitions(s: RequestStatus): RequestStatus[]`: Task 1 전이표와 같다.
- `RequestFormDialog.vue`
  - props: `modelValue: boolean`, `initial?: RequestDetail`
  - emits: `update:modelValue`, `saved(item)`
  - 동작: initial이 없으면 `POST /api/requests`, 있으면 `PUT /api/requests/{id}`(종류 선택 잠금)
  - 레포 힌트 입력은 종류가 REPO일 때만 보인다. 안내 문구는 `"알면 적어 주세요. 몰라도 괜찮아요."`
  - data-test: `req-kind`, `req-title`, `req-content`, `req-hint`, `req-submit`
- `pages/requests/index.vue`
  - 사용자: `GET /api/requests`
  - 관리자(`useHydrationSafeAuth().isAdmin`)
    - "전체 보기" 토글(기본 켬)이 켜져 있으면 `GET /api/requests?all=true&kind=…&status=…&includeUnread=…`
    - 종류 select와 상태 다중 select(기본 RECEIVED, REVIEWING), "안 읽은 건 포함" 토글(기본 켬)
  - 데스크톱 `q-table`, 모바일 카드
  - 각 줄 표시: 종류 칩, 제목, 상태 칩, 요청자(관리자만), `updatedAt`, unread 점(`data-test="unread-dot"`)
  - 줄을 클릭하면 `navigateTo('/requests/' + id)`
  - REPO 종류이고 열린 상태인 줄의 액션 칸 자리 `data-test="repo-add-slot"`는 Task 9에서 채운다.
  - "새 요청" 버튼 `data-test="open-create"`

- [ ] **Step 1: 실패하는 테스트 작성**
  - `requestBoard.spec.ts`
    - `adminTransitions('DONE')`이 `['REVIEWING']`
    - `adminTransitions('RECEIVED')`가 `['REVIEWING','DONE','REJECTED']`
  - `requests-list.spec.ts`(`test/repo-catalog-admin.spec.ts`의 QLayout 래퍼와 `useApiMock` 패턴. `useHydrationSafeAuth`와 `navigateTo`는 `test/layout-default.spec.ts`·`test/hydration-auth-gate.spec.ts`처럼 전역 주입)
    - `user_lists_own`: `useApiMock`이 `/api/requests`로 호출되고, 제목이 보이며, unread 항목에만 `unread-dot`이 있다.
    - `admin_default_query`: `isAdmin=true`이면 첫 호출 URL에 `all=true`, `status=RECEIVED`, `status=REVIEWING`, `includeUnread=true`가 들어 있다.
    - `create_posts_repo_hint_only_for_repo`: 종류 FEATURE로 작성하면 POST 본문에 `repoHint`가 없거나 null이고, REPO로 작성하면 입력값이 들어간다.
- [ ] **Step 2: 실행해서 실패 확인.** `npx vitest run composables/requestBoard.spec.ts test/requests-list.spec.ts`가 FAIL이어야 한다.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat(frontend): 요청 목록과 작성 다이얼로그`

---

### Task 8: 프론트 — 상세 페이지 · 스레드 · 상태 변경

**Files:**
- Create: `frontend/components/requests/RequestThread.vue`, `frontend/pages/requests/[id].vue`
- Test: `frontend/test/request-detail.spec.ts`

**Interfaces — Consumes:** Task 7의 `requestBoard.ts`, `RequestFormDialog`

**Interfaces — Produces:**
- `RequestThread.vue`
  - props: `comments: RequestComment[]`, `requestId: number`
  - emits: `posted(comment)`
  - 동작
    - COMMENT는 말풍선으로 그리고 `renderMarkdown`으로 렌더한다. `authorAdmin`이면 "관리자" 칩을 붙인다.
    - SYSTEM은 회색 한 줄 `data-test="system-line"`
    - 입력창 `data-test="reply-input"`과 전송 `data-test="reply-send"`는 `POST /api/requests/{id}/comments`를 부른다.
- `pages/requests/[id].vue`
  - `GET /api/requests/{id}`로 불러온다. 404면 "요청을 찾을 수 없습니다" 빈 상태를 보여 준다.
  - 헤더와 본문(`renderMarkdown`)을 그린다.
  - REPO 처리완료 표시: `repoAlias`가 있으면 `"등록된 레포: <alias> (<repoGitUrl>)"`, `repoCatalogDeleted`이면 `"(카탈로그에서 삭제됨)"`을 덧붙인다.
  - `editable`이면 "수정"(`RequestFormDialog`에 initial 전달)과 "취소"를 보여 준다.
    - 취소는 `confirmDialog($q, '이 요청을 취소할까요? 되돌릴 수 없습니다.', { ok: '요청 취소' })`로 확인한 뒤 `DELETE`하고 `/requests`로 이동한다.
  - 관리자에게는 상태 메뉴 `data-test="status-menu"`를 보여 준다. 항목은 `adminTransitions(status)`이고, 각 항목은 `data-test="status-<STATUS>"`다.
    - REJECTED를 고르면 사유 다이얼로그(`q-input data-test="reject-reason"`, 빈 값이면 확인 버튼 비활성)를 띄운다.
    - 확정하면 `POST /api/admin/requests/{id}/status`를 부르고 응답 Detail로 화면을 교체한다.
  - REPO 요청이고 열린 상태인 관리자 화면에는 `data-test="repo-add-slot"`(Task 9에서 채움)을 둔다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `renders_thread_and_system_lines`
  - `reply_posts_and_appends`: 전송하면 `useApiMock`이 `('/api/requests/3/comments', { method:'POST', body:{ body:'고마워요' } })`로 호출되고, 목록에 그 답글이 추가된다.
  - `editable_shows_edit_and_cancel`(`editable=false`이면 둘 다 없음)
  - `admin_reject_requires_reason`: reason이 빈 값이면 확인 버튼이 disabled이고, 입력하면 POST 본문이 `{ status:'REJECTED', reason:'중복' }`
  - `shows_deleted_catalog_note`
- [ ] **Step 2: 실행해서 실패 확인.** `npx vitest run test/request-detail.spec.ts`가 FAIL이어야 한다.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령으로 PASS가 나와야 한다.
- [ ] **Step 5: 커밋.** `feat(frontend): 요청 상세·답글·상태 변경`

---

### Task 9: 프론트 — RepoAddDropdown과 연결

**Files:**
- Create: `frontend/components/requests/RepoAddDropdown.vue`
- Modify: `frontend/pages/requests/index.vue`, `frontend/pages/requests/[id].vue`(각 `repo-add-slot` 자리)
- Test: `frontend/components/requests/RepoAddDropdown.spec.ts`

**Interfaces — Produces:**
- `RepoAddDropdown.vue`
  - props: `requestId: number`
  - emits: `added(detail: RequestDetail)`
- 열기
  - `q-btn-dropdown`(label "추가", dense, `@click.stop`)이 `@before-show` 때 `GET /api/admin/requests/{id}/repo-candidates`를 부른다.
- 후보 줄 `data-test="cand-<id>"`
  - 표시: `pathWithNamespace` 굵게, 기본 브랜치, 설명 한 줄. `registered`이면 "등록됨" 칩.
- 후보가 0개
  - 문구 `"추측한 후보가 없어요. 검색해 주세요."`, 검색창에 포커스
- 검색창 `data-test="cand-search"`
  - 300ms 디바운스, 2자 이상일 때 `GET /api/admin/gitlab-projects?search=<q>`
  - 결과가 후보 영역을 대신한다.
- 항목 클릭
  - 확인 없이 `POST /api/admin/requests/{id}/add-repo` + `{ gitlabProjectId }`
  - 진행 중에는 모든 항목을 비활성화한다.
  - 성공하면 `$q.notify({ type:'positive', message: '<repoAlias> 등록됨' })`를 띄우고 `added`를 emit한다.
  - 실패하면 negative 알림 `e?.data?.message ?? '레포 등록 실패'`
- 503이면 드롭다운 안에 서버 메시지만 보여 준다(검색창도 숨김).
- 목록과 상세에서 연결
  - 목록 페이지는 `added`를 받으면 목록을 다시 읽는다.
  - 상세 페이지는 `added`로 받은 detail로 교체한다.

- [ ] **Step 1: 실패하는 테스트 작성** (타이머는 `vi.useFakeTimers()`)
  - `loads_candidates_on_open`
  - `empty_candidates_shows_search_hint`
  - `search_debounced_and_min_two_chars`: `'c'` 입력 + 300ms → 호출 없음, `'co'` 입력 + 300ms → `gitlab-projects?search=co` 호출
  - `click_adds_and_emits`: POST 본문 `{ gitlabProjectId: 5 }`, `added` emit
  - `unconfigured_shows_message_only`: 후보 호출이 `{ statusCode:503, data:{ message:'사내 GitLab이 설정되지 않았습니다' } }`로 reject → 그 문구가 보이고 `cand-search`가 없다.
- [ ] **Step 2: 실행해서 실패 확인.** `npx vitest run components/requests/RepoAddDropdown.spec.ts`가 FAIL이어야 한다.
- [ ] **Step 3: 구현하고 목록·상세 자리에 연결한다.**
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령 + Task 7·8 spec이 PASS여야 한다.
- [ ] **Step 5: 커밋.** `feat(frontend): 레포 추가 드롭다운`

---

### Task 10: 내비게이션 · 배지 · 하이드레이션 스모크 · 문서

**Files:**
- Modify: `frontend/layouts/default.vue`, `frontend/test/layout-default.spec.ts`, `frontend/scripts/hydration-smoke.mjs`, `CLAUDE.md`(자주 보는 코드 표)

**Interfaces — Consumes:** `GET /api/admin/requests/summary`(Task 3)

- 데스크톱 탭: "질문" 뒤에 `<q-route-tab to="/requests" label="요청">`을 넣는다. 관리자이고 `attention > 0`이면 `q-badge`(`data-test="requests-badge"`)를 붙인다.
- 모바일 하단 바: "질문" 뒤에 `to="/requests" icon="campaign" label="요청"`을 넣는다(배지 같음).
- 배지 값 조회
  - 레이아웃 `onMounted`와 `watch(() => route.path)`에서 관리자일 때만 `useApi('/api/admin/requests/summary')`를 부른다.
  - 실패나 undefined 응답은 무시하고 0으로 둔다. 기존 레이아웃 테스트는 목이 undefined를 돌려준다.
- `hydration-smoke.mjs`
  - 경로 목록에 `/requests`, `/requests/1`
  - `apiResponse`에 `/api/requests` → `[REQUEST]`, `/^\/api\/requests\/\d+$/` → `REQUEST_DETAIL`, `/api/admin/requests/summary` → `{ received: 1, attention: 1 }`. 각 상수는 Task 2 record 필드에 맞춘 고정 객체다.
  - `ANON_PATHS`에도 `/requests`를 추가한다(비로그인 리다이렉트 확인).
- `CLAUDE.md` "자주 보는 코드" 표에 다음 한 줄을 추가한다.
  - `| 요청 게시판(기능·MCP·레포 추가 요청 · 레포 후보 클릭 등록) | service/RequestBoardService.java, service/RepoRequestService.java, service/GitLabProjectClient.java, controller/RequestBoardController.java, controller/RepoRequestController.java, 마이그레이션 V30__request_board.sql, frontend/pages/requests/*, frontend/components/requests/* |`

- [ ] **Step 1: 실패하는 테스트 작성** (`layout-default.spec.ts`)
  - `shows_requests_tab_for_users`: 비관리자 넓은 화면에 "요청" 탭
  - `admin_badge_shows_attention`: `useApiMock`이 `{ received: 0, attention: 2 }`를 돌려주면 `requests-badge` 텍스트가 `2`
  - `badge_hidden_when_zero`
- [ ] **Step 2: 실행해서 실패 확인.** `npx vitest run test/layout-default.spec.ts`가 FAIL이어야 한다.
- [ ] **Step 3: 구현(레이아웃, 스모크 스크립트, CLAUDE.md)**
- [ ] **Step 4: 전체 확인**
  - `npx vitest run`: 전체 PASS
  - `./gradlew test`: 단위 전체 PASS
  - 하이드레이션 스모크(`npm run build && npm run test:hydration`)는 본 체크아웃 node_modules에 `playwright-core`가 없으므로 CI frontend 잡에 맡긴다. 보고서에 그렇게 적는다.
- [ ] **Step 5: 커밋.** `feat: 요청 탭·관리자 배지, 하이드레이션 스모크·문서 반영`
