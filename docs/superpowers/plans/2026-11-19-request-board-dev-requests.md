# 요청 게시판 개발 요청 · 사이트 추가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 요청 게시판에 hive 이슈 등록형 "개발 요청"을 넣고(전원 공개·첨부·작업으로 등록), REPO 추가를 "사이트 추가"로 바꾸며, 기능 추가·MCP 추가의 새 등록을 막는다.

**Architecture:** 기존 `com.request_post`에 칸을 더하고 `RequestKind.DEV`만 공개 규칙을 달리한다. 첨부는 기존 `AttachmentStorage`에 `request-{id}` 소유 폴더를 더한다. 작업 연결은 질문 → 작업(`sourceQuestionId`·`fromQuestion`) 선례를 그대로 따라 `sourceRequestId`·`fromRequest`로 만든다.

**Tech Stack:** Spring Boot(API 프로파일)·JPA·Flyway·JUnit5/Mockito·Testcontainers(CI), Nuxt 3 + Quasar·vitest.

**Spec:** `docs/superpowers/specs/2026-11-19-request-board-dev-requests-design.md` — 실행자는 이 계획과 함께 읽는다.

## Global Constraints

- 작업 위치: worktree `.claude/worktrees/request-board-dev-requests`(브랜치 `feat/request-board-dev-requests`). 라이브 체크아웃 파일을 직접 고치지 않는다.
- 마이그레이션은 `V46__request_board_dev_requests.sql`. PR 병합 직전 `origin/main`의 가장 큰 번호를 다시 보고 겹치면 번호를 올린다.
- DB 종류 값 `REPO`는 바꾸지 않는다. 라벨만 "사이트 추가". `FEATURE`·`MCP`는 enum에 남긴다.
- 전원 공개는 `kind == DEV`에만 적용한다. 그 밖의 종류는 지금처럼 본인·관리자만, 아니면 404(`요청을 찾을 수 없습니다`).
- 첨부 한도는 `AttachmentStorage.validate` 그대로(파일 10개·파일당 20MB). 새 한도를 만들지 않는다.
- 워커·인터뷰 서비스 코드는 건드리지 않는다. 새 비밀·env 없음.
- 프론트: 작은따옴표·세미콜론 없음. `npm run lint-prettier`를 폴더 전체에 돌리지 않는다. 클래스명에 `xs sm md lg xl` 등 Quasar 반응형 헬퍼 이름 금지. `q-dialog` 루트는 `<div>`. 모바일 분기는 `$q.screen.lt.md`. 확인창은 `confirmDialog($q)`(window.confirm 금지).
- 백엔드 시험: `./gradlew test --no-daemon --tests '<패턴>'`. Testcontainers 시험은 이 PC에서 건너뛰고 CI가 돌린다. `gradlew --stop` 금지.
- 프론트 시험: `cd frontend && npx vitest run <파일>`.
- 커밋 메시지는 한국어 `feat:`/`test:`/`docs:` 앞머리, 끝에 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- 요청자·답글 작성자 표시는 지금처럼 `requesterId`/`authorId`(로그인 id)를 쓴다. 이름 조회를 새로 만들지 않는다(스펙 §12의 구현자 몫 — 이렇게 정함).

## Review Focus

1. 개발 요청이 아닌 글(사이트 추가·예전 기능/MCP)이 `scope=dev` 목록·상세·답글·첨부 내려받기 어느 경로로도 남에게 보이지 않는다 → Task 3·4 시험.
2. 같은 요청에 `작업으로 등록`을 두 번(두 관리자 동시 포함) 눌러도 작업은 하나만 생긴다 → Task 6 시험(행 락 + 409).
3. 작업 등록이 중간에 실패하면(첨부 복사 실패 등) 요청은 처리완료로 바뀌지 않고 복사한 파일도 남지 않는다 → Task 6 시험.
4. 요청 첨부가 이미 정리돼 디스크에 없을 때 작업 등록은 그 첨부만 빼고 성공한다 → Task 6 시험.
5. 마감일이 과거이거나 대상 레포가 비활성·삭제된 카탈로그 id일 때: 과거 마감일은 허용(지난 건 등록 가능), 없는/비활성 레포는 400 → Task 2 시험.

---

### Task 1: 스키마 · enum · 엔티티

**Files:**
- Create: `src/main/resources/db/migration/V46__request_board_dev_requests.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/RequestIssueType.java`, `RequestPriority.java`, `RequestAttachment.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/RequestAttachmentRepository.java`
- Modify: `entity/RequestKind.java`, `entity/RequestPost.java`, `entity/Task.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/RequestKindTest.java`(신규), `src/test/java/com/hamonsoft/netismaker/migration/RequestBoardDevMigrationTest.java`(Testcontainers, 기존 `TaskTypeMigration*` 시험의 틀을 따른다)

**Interfaces — Produces:**
- `RequestKind { DEV("개발 요청"), REPO("사이트 추가"), FEATURE("기능 추가"), MCP("MCP 추가") }` + `boolean creatable()`(DEV·REPO만 true) + `boolean publicToAll()`(DEV만 true)
- `RequestIssueType { BUG("버그"), DEV("개발요청"), INQUIRY("기능문의") }` + `label()`
- `RequestPriority { HIGH("높음"), NORMAL("보통"), LOW("낮음") }` + `label()`
- `RequestPost` 새 getter/setter: `issueType`, `priority`, `dueDate`(`LocalDate`), `targetRepoCatalogId`, `taskId`
- `RequestAttachment.create(Long requestId, int ordinal, String originalName, String storedPath, String mimeType, long sizeBytes)`; getter `id, requestId, ordinal, originalName, storedPath, mimeType, sizeBytes, createdAt`
- `RequestAttachmentRepository`: `List<RequestAttachment> findByRequestIdOrderByOrdinalAsc(Long)`, `long countByRequestId(Long)`, `Optional<RequestAttachment> findByIdAndRequestId(Long, Long)`
- `Task.getSourceRequestId()/setSourceRequestId(Long)`

- [ ] **Step 1: enum 시험 작성** — `RequestKindTest`: `DEV.label()=="개발 요청"`, `REPO.label()=="사이트 추가"`, `creatable()`은 DEV·REPO만 true, `publicToAll()`은 DEV만 true.
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests '*RequestKindTest'` → 컴파일 실패.
- [ ] **Step 3: V46 작성**

```sql
ALTER TABLE com.request_post
    ADD COLUMN issue_type VARCHAR(20),
    ADD COLUMN priority VARCHAR(10),
    ADD COLUMN due_date DATE,
    ADD COLUMN target_repo_catalog_id BIGINT,
    ADD COLUMN task_id BIGINT;
ALTER TABLE com.request_post
    ADD CONSTRAINT ck_request_post_issue_type CHECK (issue_type IS NULL OR issue_type IN ('BUG','DEV','INQUIRY')),
    ADD CONSTRAINT ck_request_post_priority CHECK (priority IS NULL OR priority IN ('HIGH','NORMAL','LOW'));

CREATE TABLE com.request_attachment (
    id            BIGSERIAL PRIMARY KEY,
    request_id    BIGINT NOT NULL REFERENCES com.request_post(id) ON DELETE CASCADE,
    ordinal       INT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_path   TEXT NOT NULL,
    mime_type     VARCHAR(255),
    size_bytes    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_request_attachment_request ON com.request_attachment(request_id);

ALTER TABLE com.task ADD COLUMN source_request_id BIGINT;
```

`request_post.kind`는 V30에서 CHECK 없는 `VARCHAR(16)`이라 `DEV`를 그대로 담는다(확인함). `target_repo_catalog_id`에는 기존 `repo_catalog_id`와 같게 `REFERENCES com.repo_catalog(id) ON DELETE SET NULL`을 건다. `task_id`·`task.source_request_id`에는 FK를 걸지 않는다(스펙 §5.4).

- [ ] **Step 4: enum·엔티티·저장소 구현** — 위 Interfaces대로. `RequestPost`의 새 칸은 enum을 `@Enumerated(EnumType.STRING)`으로.
- [ ] **Step 5: 마이그레이션 시험 작성** — V46 뒤 `request_post`에 다섯 칸, `request_attachment` 테이블, `task.source_request_id`가 있고 `issue_type='X'` INSERT가 CHECK로 거절되는지.
- [ ] **Step 6: 통과 확인** — `./gradlew test --no-daemon --tests '*RequestKindTest'` PASS, `./gradlew compileTestJava --no-daemon` 성공.
- [ ] **Step 7: 커밋** — `feat: 요청 게시판 개발 요청 스키마(V46)·enum`

---

### Task 2: 개발 요청 등록·수정 검증

**Files:**
- Modify: `dto/RequestBoardDto.java`, `service/RequestBoardService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RequestBoardServiceTest.java`

**Interfaces — Produces:**
- `CreateRequest(kind, title, content, repoHint, RequestIssueType issueType, RequestPriority priority, LocalDate dueDate, Long targetRepoCatalogId)` — 기존 4인자 생성자를 호환용으로 남긴다.
- `UpdateRequest(title, content, repoHint, issueType, priority, dueDate, targetRepoCatalogId, List<Long> removeAttachmentIds)` — 기존 3인자 생성자 유지.
- 서비스 동작(메시지는 정확히 이 문구):
  - `kind`가 `creatable()`이 아니면 400 `더 이상 받지 않는 요청 종류입니다`
  - DEV인데 `issueType` 없음 → 400 `유형을 선택하세요`
  - DEV의 `priority` 없음 → `NORMAL`
  - `targetRepoCatalogId`가 있으면 `RepoCatalogService.resolveForRegistration(id)`로 확인(없는·비활성이면 그 메서드의 400 그대로)
  - DEV가 아니면 `issueType·priority·dueDate·targetRepoCatalogId`를 null로 저장, DEV면 `repoHint`를 null로 저장
  - 수정: DEV는 네 칸을 바꿀 수 있고 `issueType`은 필수 유지. 종류는 못 바꾼다.

- [ ] **Step 1: 시험 추가** — `create_FEATURE는_400`, `create_MCP는_400`, `create_DEV_유형없으면_400`, `create_DEV_우선순위_기본_NORMAL`, `create_DEV_없는_레포면_400`, `create_DEV_과거_마감일_허용`, `create_REPO는_개발요청_칸을_버린다`, `update_DEV_칸_변경`.
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests '*RequestBoardServiceTest'`
- [ ] **Step 3: 구현** — `RequestPost.create`에 DEV용 정적 팩터리 `createDev(title, content, issueType, priority, dueDate, targetRepoCatalogId, requesterId)`를 더한다.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat: 개발 요청 등록·수정 검증`

---

### Task 3: 공개 규칙 · 목록 · 답글 · 읽음

**Files:**
- Modify: `repository/RequestPostRepository.java`, `repository/RequestCommentRepository.java`, `service/RequestBoardService.java`, `controller/RequestBoardController.java`, `dto/RequestBoardDto.java`
- Test: `RequestBoardServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/RequestBoardControllerTest.java`(있으면 추가, 없으면 서비스 시험으로 충분)

**Interfaces — Produces:**
- `GET /api/requests?scope=dev[&issueType=BUG][&status=…]` → `service.listDev(RequestIssueType issueType, Set<RequestStatus> statuses, String viewerId)`. `statuses`가 비면 전체 상태. 정렬·상한은 지금 목록과 같다(최신 활동순, `LIST_LIMIT`).
- 저장소: `findDev(kind=DEV, issueType, statuses, anyStatus, Pageable)`; `RequestCommentRepository.existsByRequestIdAndKindAndAuthorIdNot(Long, RequestCommentKind, String)`, `countByRequestIdAndKind(Long, RequestCommentKind)`.
- `ListItem`에 추가: `issueType, issueTypeLabel, priority, priorityLabel, dueDate, targetRepoAlias, attachmentCount, commentCount, taskId, taskStatusLabel`.
- `Detail`에 추가: 위와 같은 칸 + `targetRepoCatalogId`, `List<AttachmentView> attachments`(`id, name, sizeBytes, expired`), `boolean cancellable`, `boolean convertible`.
  - `editable` = 작성자 && 접수(지금과 같음)
  - `cancellable` = 작성자 && 접수 && `taskId == null` && (DEV면 작성자 아닌 답글 없음 / 그 밖이면 관리자 답글 없음)
  - `convertible` = 보는 사람이 관리자 && DEV && 상태 열림 && `taskId == null` (`toDetail`에 `viewerAdmin` 인자를 더한다)
- 규칙:
  - `findVisible`: `post.getKind().publicToAll()`이면 누구나, 아니면 지금 규칙.
  - `getAndMarkRead`: 읽음 갱신은 작성자·관리자일 때만(지금 `markRead` 인자 그대로면 이미 그렇다 — 시험으로 고정).
  - `addComment`: 작성자도 관리자도 아닌 사용자의 답글은 `touchAdmin(now)`과 `touchRequester(now)`를 둘 다 부른다.
  - `cancel`: DEV는 `existsByRequestIdAndKindAndAuthorIdNot(id, COMMENT, userId)`이면 409 `다른 사람의 답글이 있는 요청은 취소할 수 없습니다`.
  - 작성자 본인의 `listMine` 항목 `unread` 계산은 지금 그대로.
  - `scope=dev` 항목의 `unread`: 보는 사람이 작성자면 작성자 기준, 관리자면 관리자 기준, 그 밖은 false.
- 연결 작업 표시(`taskId`·`taskStatusLabel`)는 `TaskRepository.findById`로 읽고 없거나 소프트 삭제면 `taskStatusLabel = "삭제됨"`. 목록은 id 묶음을 한 번에 조회한다.

- [ ] **Step 1: 시험 추가**
  - `DEV_상세는_남도_본다`, `REPO_상세는_남에게_404`, `FEATURE_상세는_남에게_404`
  - `listDev는_DEV만_돌려준다`(REPO·FEATURE 행이 섞여 있어도), `listDev_issueType_필터`
  - `DEV_답글은_남도_단다`, `REPO_답글은_남에게_404`
  - `제3자_답글은_작성자와_관리자_모두_unread`
  - `제3자_열람은_읽음을_바꾸지_않는다`
  - `DEV_남의_답글이_있으면_취소_409`, `DEV_자기_답글만_있으면_취소_가능`
  - `convertible은_관리자에게만_true`
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew test --no-daemon --tests '*RequestBoard*'`
- [ ] **Step 5: 커밋** — `feat: 개발 요청 전원 공개·목록·답글 규칙`

---

### Task 4: 요청 첨부(올리기·수정·내려받기)

**Files:**
- Modify: `service/AttachmentStorage.java`, `service/RequestBoardService.java`, `controller/RequestBoardController.java`
- Test: `RequestBoardServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/service/AttachmentStorageTest.java`

**Interfaces — Produces:**
- `AttachmentStorage.requestRootRelative(long requestId)` → `request-{id}`; `relativePathForRequest(long requestId, int ordinal, String originalFilename)` → `request-{id}/{ordinal}-{sanitize(name)}`.
- `RequestBoardService.create(CreateRequest, List<MultipartFile> files, String userId)`; `update(Long id, UpdateRequest, List<MultipartFile> files, String userId)`; `AttachmentDownload download(Long id, Long attId, String viewerId, boolean admin)`(레코드 `Path path, String name, String mimeType`).
- 컨트롤러: `POST /api/requests`·`PUT /api/requests/{id}`에 `consumes = multipart/form-data` 매핑 추가(`@RequestPart("meta")` + `@RequestPart(value="files", required=false)`). 기존 JSON 매핑에는 `consumes`를 달지 않는다(`TaskController.createMultipart` 주석과 같은 이유). `GET /api/requests/{id}/attachments/{attId}`는 `QuestionController`의 첨부 내려받기와 같은 헤더 처리.
- 규칙:
  - 첨부는 DEV에만. 다른 종류에 파일이 오면 400 `첨부는 개발 요청에만 올릴 수 있습니다`.
  - 수정 때 한도는 (남는 기존 수 + 새 파일 수) ≤ 10. 넘으면 400(`AttachmentStorage.validate`의 개수 문구와 같은 문구).
  - 순번은 그 요청의 최대 `ordinal` + 1부터.
  - 쓰기 실패 시 `TaskService.create`처럼 쓴 파일을 지우고 예외를 다시 던진다(tx 롤백).
  - 삭제(`removeAttachmentIds`)는 행을 지우고 파일은 커밋 뒤 `deleteQuietly`.
  - 요청 취소 때 `request-{id}` 폴더를 커밋 뒤 지운다.
  - 내려받기: `findVisible`을 통과한 사용자만. 파일이 없으면 410.
- Tika 텍스트 추출·슬라이드 렌더링은 하지 않는다.

- [ ] **Step 1: 시험 추가** — `relativePathForRequest_형식`, `DEV_첨부와_함께_등록`, `REPO에_첨부면_400`, `수정_첨부_추가와_삭제`, `수정_합계_10개_초과_400`, `쓰기_실패면_파일을_지우고_롤백`, `REPO_첨부_내려받기는_남에게_404`(Review Focus 1), `정리된_첨부는_410`.
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew test --no-daemon --tests '*RequestBoardServiceTest' --tests '*AttachmentStorageTest'`
- [ ] **Step 5: 커밋** — `feat: 개발 요청 첨부`

---

### Task 5: 첨부 정리 잡에 요청 폴더 추가

**Files:**
- Modify: `service/AttachmentCleanupPlanner.java`, `service/AttachmentCleanupJob.java`, `src/main/resources/application.yml`
- Test: `AttachmentCleanupPlannerTest.java`, `AttachmentCleanupJobTest.java`

**Interfaces — Produces:**
- `Owner.REQUEST`; `parse`가 `request-([1-9][0-9]{0,17})`를 인식.
- `record RequestRow(RequestStatus status, OffsetDateTime updatedAt)`; `Owners`에 `Map<Long, RequestRow> requests, Long maxRequestId` 추가.
- `Policy`에 `Duration requestClosedRetention` 추가 — 설정 `app.attachment.cleanup.request-closed-retention-days`(기본 90).
- 판정: 행이 있고 상태가 처리완료·반려이며 `updatedAt`이 보존 기간보다 오래됨 → `DELETE_REQUEST_CLOSED`("닫힌 요청 — 보존 기간 경과"); 열림 → `KEEP_REQUEST_OPEN`; 기간 이내 → `KEEP_REQUEST_RETENTION`; 행 없음 → 기존 고아 규칙(id ≤ 최대 id이고 mtime이 `orphanGrace`보다 오래됨)을 그대로 적용.

- [ ] **Step 1: 시험 추가** — `parse_request_폴더`, `닫힌_요청_90일_지나면_삭제`, `열린_요청은_보존`, `닫힌_지_89일이면_보존`, `행_없는_요청_폴더는_고아_규칙`.
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — 잡이 `request_post`의 상태·`updated_at`을 폴더 id 묶음으로 조회해 `Owners`에 채운다(작업·질문 조회와 같은 방식).
- [ ] **Step 4: 통과 확인** — `./gradlew test --no-daemon --tests '*AttachmentCleanup*'`
- [ ] **Step 5: 커밋** — `feat: 첨부 정리 잡에 요청 첨부 포함`

---

### Task 6: 요청 → 작업 등록

**Files:**
- Modify: `dto/TaskCreateRequest.java`, `dto/TaskResponse.java`, `service/TaskService.java`, `controller/TaskController.java`, `service/RequestBoardService.java`, `entity/RequestStatus.java`(변경 없음 — 가드는 서비스에)
- Create: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceFromRequestTest.java`
- Test(통합, CI): `src/test/java/com/hamonsoft/netismaker/service/RequestToTaskFlowIntegrationTest.java`

**Interfaces — Consumes:** Task 1의 `RequestPost.taskId`·`Task.sourceRequestId`, Task 4의 `RequestAttachmentRepository`·`AttachmentStorage`.

**Interfaces — Produces:**
- `TaskCreateRequest`에 8번째 칸 `Long sourceRequestId` 추가. 기존 7인자·6인자 생성자를 호환용으로 남긴다.
- `TaskResponse.sourceRequestId`(null이어도 키가 나간다).
- `RequestBoardService.RequestSource claimForTask(Long requestId, boolean callerIsAdmin)` — 행 락(`findByIdForUpdate`)을 잡고 검증한 뒤 `record RequestSource(Long id, String requesterId, List<RequestAttachment> attachments)`를 돌려준다. 거절:
  - 관리자가 아님 → 403 `요청을 작업으로 등록할 권한이 없습니다`
  - 요청 없음 → 404
  - DEV가 아님 → 409 `개발 요청이 아닙니다`
  - 상태가 열림이 아님 → 409 `이미 처리된 요청입니다`
  - `taskId != null` → 409 `이미 작업 #{n}으로 등록된 요청입니다`
- `RequestBoardService.linkTask(Long requestId, Long taskId, String adminId)` — `taskId` 기록, 상태 처리완료, 시스템 답글 `관리자({adminId})가 작업 #{taskId}으로 등록 — 처리완료`, `touchAdmin`.
- `TaskService.register` 변경(`sourceRequestId != null`일 때):
  - 맨 먼저 `claimForTask`. `sourceQuestionId`와 함께 오면 400 `출처는 하나만 지정할 수 있습니다`.
  - 작업의 `requesterId` = `RequestSource.requesterId`, `requesterEmail` = null(종료 메일은 `com."user".email`로 대체된다).
  - `countActiveByRequester` 한도 검사를 건너뛴다.
  - 등록 이력 사유: `요청 #{id}에서 등록(관리자 {adminId})`.
  - `setSourceRequestId`, 저장 뒤 `linkTask`.
- `TaskService.inheritRequestAttachments(Task saved, RequestSource src, int firstOrdinal, List<String> written)` → 기존 `InheritOutcome`. `inheritQuestionAttachments`와 같은 규칙: 원본 없으면 건너뜀, `inheritMaxTotalMb` 총량 상한, 복사 전에 `written`에 추가, `TaskAttachment`는 `renderPolicy.initial(name)`으로 렌더 상태를 정하고 Tika sidecar를 만든다(작업에 직접 올린 첨부와 같게). 한 건이라도 있으면 이력 한 줄 `요청 첨부 승계: N개`(건너뛴 것이 있으면 질문 승계 이력과 같은 꼬리).
- `create(…)` 두 경로(JSON·multipart) 모두 사용자 파일 → (대화 전문) → 질문 첨부 → 요청 첨부 순으로 순번을 잇는다.
- **되돌리기 가드**(`RequestBoardService.changeStatus`): `taskId != null`인 요청을 처리완료에서 바꾸려 하면, 연결 작업이 소프트 삭제된 경우에만 허용하고 그때 `taskId`를 비우며 시스템 답글에 ` (작업 #{n} 연결 해제)`를 붙인다. 아니면 409 `작업 #{n}에 연결된 요청은 되돌릴 수 없습니다`.

- [ ] **Step 1: 단위 시험 작성**(`TaskServiceFromRequestTest`, Mockito — `TaskServiceInheritAttachmentsUnitTest`의 틀)
  - `비관리자는_403`
  - `등록자는_원_요청자`(관리자 id가 아니라 요청의 `requesterId`)
  - `요청자가_한도_5건을_넘겨도_등록된다`
  - `이미_연결된_요청은_409`(Review Focus 2)
  - `REPO_요청은_409`, `처리완료_요청은_409`
  - `sourceQuestionId와_함께면_400`
  - `요청_첨부가_작업_첨부로_복사된다`(순번이 사용자 파일 다음)
  - `디스크에_없는_요청_첨부는_건너뛴다`(Review Focus 4)
  - `복사_실패면_파일을_지우고_예외`(Review Focus 3 — `linkTask`가 호출됐더라도 tx 롤백으로 취소됨을 통합 시험이 확인)
- [ ] **Step 2: `RequestBoardServiceTest`에 추가** — `linkTask는_처리완료와_시스템_답글`, `연결된_요청_되돌리기_409`, `연결_작업이_삭제됐으면_되돌리며_연결_해제`.
- [ ] **Step 3: 실패 확인** — `./gradlew test --no-daemon --tests '*TaskServiceFromRequestTest' --tests '*RequestBoardServiceTest'`
- [ ] **Step 4: 구현**
- [ ] **Step 5: 통합 시험 작성**(Testcontainers) — 등록(첨부 1) → 다른 사용자 열람·답글 → 관리자 `POST /api/tasks`(`sourceRequestId`) → 요청 처리완료·`taskId`, 작업 `requesterId`=요청자·첨부 1개·`sourceRequestId`; 같은 바디로 다시 → 409, 작업 수 그대로.
- [ ] **Step 6: 통과 확인** — `./gradlew test --no-daemon --tests '*TaskService*' --tests '*RequestBoard*'`
- [ ] **Step 7: 커밋** — `feat: 개발 요청을 작업으로 등록`

---

### Task 7: 프론트 — 타입·문구·등록 폼

**Files:**
- Modify: `frontend/composables/requestBoard.ts`, `frontend/components/requests/RequestFormDialog.vue`, `frontend/components/requests/RepoAddDropdown.vue`(문구만)
- Test: `frontend/test/request-form-dialog.spec.ts`, `frontend/test/request-board.spec.ts`(신규, 순수 함수)

**Interfaces — Produces**(`requestBoard.ts`):
- `type RequestKind = 'DEV' | 'REPO' | 'FEATURE' | 'MCP'`; `type RequestIssueType = 'BUG' | 'DEV' | 'INQUIRY'`; `type RequestPriority = 'HIGH' | 'NORMAL' | 'LOW'`
- `KIND_OPTIONS` = `[{ value: 'DEV', label: '개발 요청' }, { value: 'REPO', label: '사이트 추가' }]`(등록 폼용). `KIND_FILTER_OPTIONS` = 그 둘 + `기능 추가(예전)`·`MCP 추가(예전)`(관리자 목록 필터용).
- `ISSUE_TYPE_OPTIONS`(버그·개발요청·기능문의), `PRIORITY_OPTIONS`(▲ 높음·● 보통·▼ 낮음), `ISSUE_TYPE_COLOR`, `PRIORITY_COLOR`.
- `RequestListItem`·`RequestDetail`에 Task 3의 새 칸. `interface RequestAttachmentView { id: number; name: string; sizeBytes: number; expired: boolean }`.
- `requestFormData(meta: object, files: File[]): FormData`(`meta`는 `application/json` Blob — `pages/tasks/index.vue`의 multipart 조립과 같은 모양).
- `MAX_REQUEST_FILES = 10`.

**폼 동작:**
- 새 요청 기본 종류 `DEV`. DEV면 유형(필수, 미리 고른 값 없음)·제목·설명·대상 레포(`/api/repo-catalog`의 활성 항목 select, 맨 위 `선택 안 함`, 보조 문구 `모르면 비워 두세요`)·우선순위(기본 보통)·마감일(`q-input type="date"`)·첨부. REPO면 지금 폼 그대로.
- `canSubmit`: DEV는 유형까지 있어야 한다.
- DEV는 항상 multipart로 보낸다(파일이 없어도). REPO는 지금처럼 JSON.
- 수정: 기존 첨부 목록에 삭제(×) → `removeAttachmentIds`, 새 파일 추가. 합계 10개를 넘으면 `등록` 비활성 + 문구 `첨부는 최대 10개입니다`.
- 예전 종류(FEATURE·MCP) 글의 수정은 지금 폼(제목·내용)으로 열린다.
- `data-test`: `request-kind`, `request-issue-type`, `request-target-repo`, `request-priority`, `request-due-date`, `request-files`.

- [ ] **Step 1: 시험 작성** — `request-board.spec.ts`: `KIND_OPTIONS`에 FEATURE·MCP 없음, `requestFormData`가 `meta`·`files`를 담음. `request-form-dialog.spec.ts`: 기본 종류 DEV, 유형 없으면 등록 비활성, DEV 등록이 multipart로 `issueType`·`priority: 'NORMAL'`을 보냄, REPO로 바꾸면 레포 힌트 칸만 보임, 수정에서 첨부 삭제가 `removeAttachmentIds`로 감.
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/request-form-dialog.spec.ts test/request-board.spec.ts`
- [ ] **Step 3: 구현** — `RepoAddDropdown.vue`와 관련 문구의 "REPO 추가"를 "사이트 추가"로.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat: 요청 폼에 개발 요청·사이트 추가`

---

### Task 8: 프론트 — 목록

**Files:**
- Modify: `frontend/pages/requests/index.vue`
- Test: `frontend/test/requests-list.spec.ts`

**동작:**
- 일반 사용자: 세그먼트 `내 요청`(기본, `GET /api/requests`) / `전체 개발 요청`(`GET /api/requests?scope=dev`). 관리자: 세그먼트 없음, 지금의 전체 목록.
- 필터: 종류(관리자만, `KIND_FILTER_OPTIONS`), 유형(`전체 개발 요청`과 관리자 목록에서), 상태(지금 그대로).
- 줄: 종류 칩 · (DEV) 유형 칩·우선순위 · 제목 · 상태 칩 · 날짜 · 새 답글 점. 둘째 줄(DEV, 값 있는 것만): 대상 레포 별칭 · 요청자 id · `마감 MM.DD` · `📎N` · `💬N` · `→ 작업 #N {상태}`.
- 좁은 화면(`$q.screen.lt.md`)에서는 둘째 줄이 줄바꿈된다.
- `data-test`: `requests-scope`, `requests-issue-type-filter`, `request-row`, `request-row-task`.

- [ ] **Step 1: 시험 추가** — 일반 사용자에게 세그먼트가 보이고 `전체 개발 요청`을 고르면 `scope=dev`로 조회, 관리자에게는 세그먼트 없음, DEV 줄에 유형 칩·작업 연결 표시, REPO 줄에는 둘째 줄 없음.
- [ ] **Step 2: 실패 확인** — `npx vitest run test/requests-list.spec.ts`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat: 요청 목록에 전체 개발 요청 보기`

---

### Task 9: 프론트 — 상세

**Files:**
- Modify: `frontend/pages/requests/[id].vue`, `frontend/components/requests/RequestThread.vue`
- Test: `frontend/test/request-detail.spec.ts`

**동작:**
- 머리(DEV): 유형 칩·우선순위·마감일·대상 레포 별칭·요청자 id.
- 첨부 목록: 이름·크기·내려받기(`GET /api/requests/{id}/attachments/{attId}` — 질문 첨부 내려받기와 같은 방식으로 토큰을 실어 받는다). `expired`면 비활성 + 툴팁 `보존 기간이 지나 정리된 파일입니다`(`EXPIRED_ATTACHMENT_TIP`).
- 연결 작업: `taskId`가 있으면 `→ 작업 #N · {taskStatusLabel}`. 보는 사람이 관리자이거나 요청자면 `/tasks/{id}` 링크, 아니면 글자만.
- 버튼: `수정` = `detail.editable`, `취소` = `detail.cancellable`, `작업으로 등록` = `detail.convertible` → `navigateTo('/tasks?fromRequest=' + id)`.
- 답글 입력은 DEV면 로그인 사용자 누구에게나 보인다. 그 밖은 지금 조건.
- `RequestThread`: 답글 머리에 작성자 id를 보이고(지금도 보임), 요청자 본인 답글에 `요청자` 칩을 더한다(`requesterId` prop 추가).
- `data-test`: `request-attachments`, `request-task-link`, `request-convert`.

- [ ] **Step 1: 시험 추가** — `convertible`이면 버튼이 보이고 누르면 `/tasks?fromRequest=7`로 이동, `cancellable=false`면 취소 버튼 없음, 제3자(작성자·관리자 아님)가 DEV 상세에서 답글 입력창을 봄, 연결 작업이 제3자에게는 링크가 아님, 첨부 `expired` 비활성.
- [ ] **Step 2: 실패 확인** — `npx vitest run test/request-detail.spec.ts`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat: 개발 요청 상세·작업으로 등록 버튼`

---

### Task 10: 프론트 — 작업 등록 창 미리 채움 · 출처 표시

**Files:**
- Modify: `frontend/composables/requestBoard.ts`, `frontend/pages/tasks/index.vue`, `frontend/pages/tasks/[id].vue`, `frontend/components/tasks/TaskDetailMobile.vue`, `frontend/scripts/hydration-smoke.mjs`
- Create: `frontend/test/tasks-form-from-request.spec.ts`
- Test: `frontend/test/request-board.spec.ts`

**Interfaces — Produces:**
- `interface RequestTaskPrefill { repoCatalogId: number | null; taskType: 'BUG_FIX' | null; title: string; description: string; attachmentCount: number }` — 질문용 `TaskPrefill`에는 설명·타입 칸이 없어 따로 둔다.
- `taskPrefillFromRequest(r: RequestDetail, catalog: RepoCatalogOption[]): RequestTaskPrefill`
  - `attachmentCount`: `r.attachments` 중 `expired`가 아닌 수
  - 레포를 쓸 수 있는 조건은 질문 경로와 같다(카탈로그에 있고 `host !== 'other'`)
  - 레포: `r.targetRepoCatalogId`가 활성 카탈로그에 있으면 그 항목, 없으면 비움
  - 타입: `r.issueType === 'BUG'`면 `'BUG_FIX'`, 아니면 비움
  - 제목: `r.title`
  - 설명: `r.content + '\n\n— 요청 #' + r.id + ' · ' + 유형 라벨 + ' · 우선순위 ' + 우선순위 라벨 + (마감일 있으면 ' · 마감 ' + r.dueDate) + ' · 요청자 ' + r.requesterId`

**동작:**
- `prefillFromRequest()`: `route.query.fromRequest`가 있고 `fromQuestion`·`fromTask`가 없을 때만. `GET /api/requests/{id}`로 읽고 `convertible`이 아니면 알림 `작업으로 등록할 수 없는 요청입니다`만 띄우고 창을 열지 않는다.
- 등록 바디에 `sourceRequestId`. 안내 줄 `요청 첨부 N개가 함께 등록됩니다`(N = `attachments` 중 `expired`가 아닌 수, 0이면 줄 없음).
- 등록 성공 뒤 쿼리를 지우고 새 작업 상세로 이동(질문 경로와 같은 처리).
- 작업 상세(데스크톱·모바일): `task.sourceRequestId`가 있으면 `↩ 요청 #{id}에서 이어짐`(`/requests/{id}` 링크), 출처 질문 줄과 같은 자리.
- `hydration-smoke.mjs`: `/api/requests?scope=dev` 목, `/api/requests/1`에 새 칸, 작업 목에 `sourceRequestId: null`.

- [ ] **Step 1: 시험 작성** — `request-board.spec.ts`: `taskPrefillFromRequest`의 버그→`BUG_FIX`, 개발요청→타입 비움, 없는 레포 id→레포 비움, 설명 꼬리 문자열 정확 일치. `tasks-form-from-request.spec.ts`: 창이 채워져 열림, 등록 바디에 `sourceRequestId`, 첨부 안내 문구, `convertible=false`면 창이 안 열림, `fromQuestion`이 같이 있으면 질문이 이김.
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `npx vitest run test/tasks-form-from-request.spec.ts test/request-board.spec.ts test/tasks-form-from-question.spec.ts test/tasks-form-from-task.spec.ts`
- [ ] **Step 5: 커밋** — `feat: 요청에서 작업 등록 창 미리 채움`

---

### Task 11: 문서 · 전체 확인

**Files:**
- Modify: `CLAUDE.md`("자주 보는 코드"의 요청 게시판 줄 갱신 + 핵심 설계 절에 이 기능 한 단락: 종류·공개 규칙·첨부 폴더 `request-{id}`·정리 90일·`sourceRequestId`의 등록자=원 요청자·한도 미검사·배포 순서 API(V46) → 프론트)

- [ ] **Step 1: CLAUDE.md 갱신**
- [ ] **Step 2: 백엔드 전체** — `./gradlew test --no-daemon` → 실패 0(Testcontainers는 건너뜀 표시).
- [ ] **Step 3: 프론트 전체** — `cd frontend && npx vitest run` → 실패 0; `npm run lint` → 오류 0, 경고 수가 기준선보다 늘지 않음.
- [ ] **Step 4: 하이드레이션** — `npm run build && npm run test:hydration` → 경고·예외 0, "목 응답이 없어 null로 답한 API" 목록에 요청 관련 경로 없음.
- [ ] **Step 5: graphify 갱신** — `graphify update .`
- [ ] **Step 6: 커밋** — `docs: 요청 게시판 개발 요청 안내`

---

## 배포(이 계획의 범위 밖 — 병합 뒤 사용자 승인으로)

API(V46) → 프론트(`.output` 재빌드). 재기동은 `.\scripts\stop-public.ps1 -Only api` → `Start-ScheduledTask -TaskName netisMaker-public`. 워커·인터뷰 서비스는 재기동하지 않는다.
