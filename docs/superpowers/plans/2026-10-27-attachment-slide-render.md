# 첨부 pptx 슬라이드 렌더링 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업·질문 첨부의 pptx/ppt를 슬라이드별 PNG로 렌더링해 인터뷰·질문 에이전트가 Read로 보게 하고, 질문 → 작업 등록 때 질문 첨부를 작업으로 승계한다.

**Architecture:** 업로드는 원본 + Tika sidecar만 쓰고 렌더링 대상 행을 `PENDING`으로 남긴다. API 안의 전용 단일 스레드 잡이 DB 칸을 큐로 써서 LibreOffice(→PDF) + PDFBox(→PNG)로 `{원본}.render/`를 만든다. 인터뷰 claim은 렌더링 중인 첨부가 있는 세션을 잠시 미루고, claim 바디에 그림 경로를 실어 러너가 프롬프트에 넣는다.

**Tech Stack:** Java 21 · Spring Boot · JPA/Flyway(PostgreSQL `com`) · Apache Tika 3.2.3 · PDFBox(Tika가 끌어오는 판) · LibreOffice headless · Node/TypeScript 인터뷰 서비스(vitest) · Nuxt 3 + Quasar(vitest)

**Spec:** `docs/superpowers/specs/2026-10-27-attachment-slide-render-design.md`

## Global Constraints

- 렌더링 대상 기본 확장자 `pptx,ppt` (설정 `app.attachment.render.extensions`).
- 설정 기본값: `enabled=true`, `soffice-path=${SOFFICE_PATH:}`, `timeout-seconds=120`, `max-pages=30`, `width-px=1600`, `max-media=20`, `claim-wait-seconds=180`; 승계 총량 `app.attachment.inherit-max-total-mb=200`.
- 산출물 경로: `{원본 상대경로}.render/slide-001.png …`, `{원본}.render/media/{원래 이름}`; Tika sidecar `{원본}.txt`(기존 `extractedTextRelativePath` 규칙). 작업 폴더 `{app.attachment.dir}/.render-work/{uuid}`.
- 렌더링 실패·꺼짐이 업로드·등록·claim을 실패시키지 않는다(예외 미전파). 단, 승계 복사 실패는 작업 등록을 롤백한다(스펙 §7.5).
- soffice에는 원본 파일명을 넘기지 않는다 — 작업 폴더의 `input.<확장자>` 사본만.
- 시간 초과 시 soffice 프로세스 트리째 종료(`ProcessHandle.descendants()` 전부 + 본인 `destroyForcibly`).
- 실패 사유 문자열에 절대경로·비밀을 넣지 않는다. 고정 문구: `렌더링 시간 초과(120초)`(초는 설정값), `렌더링 꺼짐`, `렌더링 중단(재기동)`.
- 프롬프트 고정 문구: 슬라이드 줄 `슬라이드 그림({n}장, 순서대로)`, 잘림 `(전체 {total}장 중 {n}장만 그림 — 나머지는 텍스트 추출본 참고)`, 승계 표시 `(질문 #{id}에서 넘어옴)`, 대기 초과 `슬라이드 그림 준비 중(대기 시간 초과)`.
- 새 claim 필드는 모두 optional — 구버전 러너·API와 서로 깨지지 않는다(TS는 `Array.isArray`/`typeof` 가드).
- 테스트 initializer(`TestcontainersConfig`)는 렌더링 잡을 끈다: `app.attachment.render.enabled=false`.
- 프론트는 작은따옴표·세미콜론 없음(CLAUDE.md), `prettier --write` 전체 실행 금지.
- 기능 작업은 이 worktree(`.claude/worktrees/attachment-slide-render`)에서만 — 본 체크아웃은 라이브.

## Review Focus

1. **ZIP 경로·폭탄 pptx** — `ppt/media/../../x.png` 항목이나 압축 해제 시 거대한 항목: 파일명만 쓰고 항목당 30MB 넘으면 건너뛰어야 한다 → Task 3 `extractMedia_경로_무시_및_크기_상한` 테스트.
2. **soffice가 멈추는 문서** — 상한에서 트리째 끝나고 다음 첨부가 계속 처리돼야 한다 → Task 3 `timeout_트리_종료` 테스트(가짜 명령), Task 4 `실패_뒤_다음_건_처리`.
3. **질문 첨부 폴더가 이미 정리된 상태에서 "이 대화로 작업 등록"** — 등록은 성공하고 그 첨부만 빠지며 이력에 제외 수 → Task 6 `정리된_질문_첨부는_건너뜀`.
4. **API 재기동으로 RUNNING에 멈춘 행** — 한 번은 다시 렌더링, 두 번째는 FAILED → Task 4 `stale_RUNNING_회수_1회`.
5. **한글·공백·따옴표가 든 파일명** — 렌더링·프롬프트 경로가 깨지지 않아야 한다 → Task 3 soffice 통합 테스트 픽스처 이름 `대시보드 "업그레이드" 안.pptx`, Task 7 프롬프트 테스트.

---

### Task 1: 스키마 V34 + 렌더링 칸 임베더블

**Files:**
- Create: `src/main/resources/db/migration/V34__attachment_render.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/RenderStatus.java`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/AttachmentRendition.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskAttachment.java`, `entity/QuestionAttachment.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/AttachmentRenditionTest.java`

**Interfaces:**
- Produces:
  - `enum RenderStatus { NONE, PENDING, RUNNING, DONE, FAILED, SKIPPED }`
  - `@Embeddable class AttachmentRendition` — 필드(컬럼): `status`(`render_status`, `@Enumerated(STRING)`), `dir`(`render_dir`), `pageCount`(`render_page_count`), `totalPages`(`render_total_pages`), `mediaCount`(`render_media_count`), `error`(`render_error`, 500자), `startedAt`(`render_started_at`), `attempts`(`render_attempts`). 메서드: `static AttachmentRendition of(RenderStatus initial, String error)`, `void markRunning(OffsetDateTime now)`(attempts+1), `void markDone(String dir, int pageCount, int totalPages, int mediaCount)`, `void markFailed(String error)`, `void resetToPending()`, `AttachmentRendition copyWithDir(String newDir)`(승계용 — 상태·숫자·사유 복사, dir 교체, startedAt null, attempts 0).
  - `TaskAttachment`: `@Embedded AttachmentRendition rendition`, 새 `extractedTextPath`(`extracted_text_path`), 새 `sourceQuestionAttachmentId`(`source_question_attachment_id`). `create(...)`에 `extractedTextPath`, `AttachmentRendition`, `Long sourceQuestionAttachmentId`를 받는 오버로드를 더하고 기존 시그니처는 null·`NONE`으로 위임.
  - `QuestionAttachment`: `@Embedded AttachmentRendition rendition`, `create(...)` 오버로드 동일 방식.

- [ ] **Step 1: 실패하는 테스트** — `AttachmentRenditionTest`: `markRunning` 두 번이면 `attempts==2`; `markDone("a.render",2,2,2)` 뒤 `status==DONE`; `copyWithDir("b.render")`가 DONE·숫자·사유를 옮기고 `attempts==0`, `startedAt==null`; `of(SKIPPED,"렌더링 꺼짐").getError()`.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --tests '*AttachmentRenditionTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — V34: 두 테이블에 `render_status VARCHAR(16) NOT NULL DEFAULT 'NONE'`, `render_dir VARCHAR(500)`, `render_page_count INT`, `render_total_pages INT`, `render_media_count INT`, `render_error VARCHAR(500)`, `render_started_at TIMESTAMPTZ`, `render_attempts INT NOT NULL DEFAULT 0`; `task_attachment`에만 `extracted_text_path VARCHAR(500)`, `source_question_attachment_id BIGINT`. 부분 인덱스 `CREATE INDEX … ON com.task_attachment (id) WHERE render_status IN ('PENDING','RUNNING')`(question_attachment도).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS. `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskAttachmentApiIntegrationTest'`로 마이그레이션 적용 확인(PASS).
- [ ] **Step 5: 커밋** — `feat(attachments): V34 렌더링 칸·RenderStatus`

### Task 2: 업로드 시 Tika(작업 첨부) + 렌더링 대상 표시

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/config/RenderProperties.java`, `service/render/SofficeLocator.java`, `service/render/RenderTargetPolicy.java`
- Modify: `service/TaskService.java:168-180`, `service/QuestionService.java:306-332`, `src/main/resources/application.yml`(attachment 블록), `src/test/java/com/hamonsoft/netismaker/TestcontainersConfig.java`
- Test: `service/render/PptxFixtures.java`(생성: `static Path twoSlides(Path dir)` — 글 상자 2개짜리 pptx), `service/render/RenderTargetPolicyTest.java`, `service/render/SofficeLocatorTest.java`, `service/TaskServiceAttachmentTest.java`(추가), `service/QuestionServiceTest.java`(추가)

**Interfaces:**
- Consumes: Task 1 `AttachmentRendition.of`, 엔티티 `create` 오버로드.
- Produces:
  - `@ConfigurationProperties("app.attachment.render") record RenderProperties(boolean enabled, String sofficePath, List<String> extensions, int timeoutSeconds, int maxPages, int widthPx, int maxMedia, int claimWaitSeconds)` (기본값은 Global Constraints).
  - `SofficeLocator.resolve(String configured, String osName, Function<Path,Boolean> exists, String pathEnv): Optional<Path>` — 순서: 설정값 → Windows면 `C:\Program Files\LibreOffice\program\soffice.com` → PATH의 `soffice`/`soffice.com`. 빈 결과면 기동 로그 `슬라이드 렌더링: 꺼짐(soffice 없음)`, 있으면 `슬라이드 렌더링: 켜짐(soffice={경로})`.
  - `RenderTargetPolicy.initial(String originalName): AttachmentRendition` — 대상 확장자가 아니면 `NONE`; 대상인데 꺼짐(설정 off 또는 soffice 없음)이면 `SKIPPED`+`렌더링 꺼짐`; 그 외 `PENDING`. `boolean active()`.
  - 커밋 후 깨우기 이벤트 `record AttachmentRenderRequested()` — 업로드에서 `ApplicationEventPublisher.publishEvent`(Task 4가 `@TransactionalEventListener(AFTER_COMMIT)`로 받음).

- [ ] **Step 1: 실패하는 테스트** — `RenderTargetPolicyTest`: `deck.PPTX`→PENDING, `a.docx`→NONE, 꺼짐이면 `deck.pptx`→SKIPPED/`렌더링 꺼짐`. `SofficeLocatorTest`: 설정 경로 우선, Windows 기본 위치, 아무것도 없으면 empty. `TaskServiceAttachmentTest.pptx_첨부는_sidecar와_PENDING`: 작업 등록에 pptx(Task 3의 `PptxFixtures` 대신 이 단계에서 먼저 만든다 — `PptxFixtures.twoSlides(Path)`를 Task 2에서 생성하고 Task 3이 그림·도형을 더한다) → 행의 `extractedTextPath`가 `{rel}.txt`, 파일 존재, `rendition.status==PENDING`, 이벤트 1회. `QuestionServiceTest.pptx_첨부는_PENDING`.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RenderTargetPolicyTest' --tests '*SofficeLocatorTest' --tests '*TaskServiceAttachmentTest' --tests '*QuestionServiceTest'`.
- [ ] **Step 3: 구현** — `TaskService` 첨부 루프가 `QuestionService.writeAttachments`처럼 write → Tika → sidecar(정리 목록에 먼저 추가) → 행 저장(`policy.initial(name)`). 두 서비스 모두 PENDING이 하나라도 있으면 이벤트 발행. yml에 `render:` 블록과 `inherit-max-total-mb: 200`. `TestcontainersConfig`에 `app.attachment.render.enabled=false`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS. 기존 `TaskServiceAttachmentRollbackTest` PASS(sidecar도 롤백 때 지워짐).
- [ ] **Step 5: 커밋** — `feat(attachments): 작업 첨부 Tika sidecar·렌더링 대상 표시`

### Task 3: SlideRenderer (soffice → PDF → PNG + 원본 그림)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/render/SlideRenderer.java`, `service/render/RenderResult.java`, `service/render/RenderException.java`, `service/render/MediaExtractor.java`, `service/render/PdfPageRasterizer.java`
- Modify: `build.gradle`(PDFBox 직접 의존성 — Tika가 끌어온 것과 같은 버전을 `./gradlew dependencies --configuration runtimeClasspath | grep pdfbox`로 확인해 명시)
- Test: `service/render/MediaExtractorTest.java`, `service/render/PdfPageRasterizerTest.java`, `service/render/SlideRendererTest.java`, `service/render/SlideRendererSofficeIT.java`, 픽스처 생성기 `src/test/java/com/hamonsoft/netismaker/service/render/PptxFixtures.java`(Apache POI `XMLSlideShow` — Tika가 이미 끌어온다 — 로 2슬라이드·그림 2장·빨간 테두리 사각형 pptx를 테스트 중에 만든다. 이름 `대시보드 "업그레이드" 안.pptx`)

**Interfaces:**
- Consumes: Task 2 `RenderProperties`, `SofficeLocator`.
- Produces:
  - `record RenderResult(int pageCount, int totalPages, int mediaCount)`
  - `class RenderException extends Exception` — `getMessage()`는 사용자에게 보여도 되는 짧은 사유.
  - `SlideRenderer.render(Path source, String extension, Path outDir): RenderResult throws RenderException` — 작업 폴더 생성 → `input.<ext>` 복사 → soffice(`--headless --norestore --nologo -env:UserInstallation=file:///<work>/profile --convert-to pdf --outdir <work> <work>/input.<ext>`) → 래스터 → (pptx면) 미디어 → 작업 폴더 삭제(finally). 출력은 `outDir`을 먼저 비운 뒤 쓴다.
  - `PdfPageRasterizer.rasterize(Path pdf, Path outDir, int widthPx, int maxPages): RenderResult`(mediaCount 0) — 페이지 하나씩 그리고 버린다. 파일명 `slide-%03d.png`.
  - `MediaExtractor.extract(Path pptx, Path mediaDir, int maxMedia): int` — `ppt/media/`의 png/jpg/jpeg/gif/bmp, 10KB 미만 제외, 항목당 30MB 넘으면 건너뜀, 파일명만 사용, 이름순.
  - 시간 초과 테스트용으로 명령 구성은 `List<String> command(Path soffice, Path work, String ext)` 패키지 가시 메서드, 프로세스 실행은 생성자 주입 `ProcessStarter`(기본은 `ProcessBuilder`)로 가짜 명령을 넣을 수 있게 한다.

- [ ] **Step 1: 실패하는 테스트**
  - `MediaExtractorTest.extractMedia_경로_무시_및_크기_상한`: 테스트에서 ZipOutputStream으로 만든 pptx(`ppt/media/image1.png` 20KB, `ppt/media/../../evil.png` 20KB, `ppt/media/icon.png` 2KB, `ppt/media/v.emf`) → 결과 2, 파일 `image1.png`·`evil.png`만 `mediaDir` 안에, 상위 폴더에 아무것도 없음. `maxMedia=1`이면 1.
  - `PdfPageRasterizerTest`: PDFBox로 만든 3페이지 PDF, `maxPages=2` → `RenderResult(2,3,0)`, `slide-001.png` 폭 1600.
  - `SlideRendererTest.timeout_트리_종료`: 가짜 starter가 자식 하나를 띄우고 잠드는 명령(`cmd /c ping -n 30 127.0.0.1`/`sh -c "sleep 30 & sleep 30"`)을 돌리게 하고 `timeoutSeconds=1` → `RenderException("렌더링 시간 초과(1초)")`, 끝난 뒤 자식 프로세스가 살아 있지 않음, 작업 폴더 삭제됨.
  - `SlideRendererSofficeIT`: `assumeTrue(SofficeLocator… .isPresent())` → 픽스처 렌더링 결과 `pageCount==2`, `mediaCount==2`, PNG 2장 존재.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.render.*'`.
- [ ] **Step 3: 구현** — 위 시그니처대로. soffice 종료 코드 ≠ 0 또는 PDF 없음 → `RenderException("PDF 변환 실패")`; 래스터 예외 → `"그림 변환 실패"`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS(LibreOffice 없는 PC에서는 IT가 skipped로 나온다 — 정상).
- [ ] **Step 5: 커밋** — `feat(attachments): 슬라이드 렌더러(soffice·PDFBox·원본 그림)`

### Task 4: 렌더링 큐·잡 (단일 스레드, 재기동 회수)

**Files:**
- Create: `service/render/AttachmentRenderQueue.java`, `service/render/AttachmentRenderJob.java`, `service/render/RenderJobRef.java`
- Modify: `repository/TaskAttachmentRepository.java`, `repository/QuestionAttachmentRepository.java`, `service/AttachmentStorage.java`
- Test: `service/render/AttachmentRenderJobTest.java`(목), `service/render/AttachmentRenderQueueIntegrationTest.java`(Testcontainers)

**Interfaces:**
- Consumes: Task 1 엔티티·`AttachmentRendition`, Task 2 이벤트·`RenderProperties`, Task 3 `SlideRenderer.render`.
- Produces:
  - `record RenderJobRef(Kind kind, long attachmentId, String storedPath, String originalFilename)`, `enum Kind { TASK, QUESTION }`.
  - 리포지토리 네이티브 쿼리 `Optional<Long> findFirstPendingIdForUpdateSkipLocked()`(`… WHERE render_status='PENDING' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED`), `List<…> findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus, OffsetDateTime)`.
  - `AttachmentRenderQueue`(`@Transactional` 메서드): `Optional<RenderJobRef> claimNext()`(TASK 먼저, 없으면 QUESTION; `markRunning`), `void complete(RenderJobRef, String renderDirRel, RenderResult)`, `void fail(RenderJobRef, String reason)`, `int recoverStale(OffsetDateTime cutoff)`(attempts==1 → `resetToPending`, ≥2 → `markFailed("렌더링 중단(재기동)")`).
  - `AttachmentStorage.renderDirRelativePath(String rel)` = `rel + ".render"`, `Path renderWorkRoot()` = `{dir}/.render-work`, `void deleteQuietly`가 폴더면 트리째 지우도록 확장(`util/FileTrees.deleteTree` 사용).
  - `AttachmentRenderJob`: 자체 `ScheduledExecutorService`(스레드 1, 이름 `attachment-render`) — 기동 때 `renderWorkRoot` 비우기 + `recoverStale(now - 2×timeout)` 후 5초 고정 지연으로 `drain()`; `@TransactionalEventListener(AFTER_COMMIT) onRequested(AttachmentRenderRequested)`는 같은 executor에 `drain()` 제출. `boolean drainOnce()`(한 건 처리, 없으면 false) — 렌더러 호출은 트랜잭션 밖. `RenderTargetPolicy.active()`가 false면 drain하지 않는다. `@Profile("api")`, `@PreDestroy`에서 executor 종료.

- [ ] **Step 1: 실패하는 테스트**
  - `AttachmentRenderJobTest.실패_뒤_다음_건_처리`: 큐가 두 건을 주고 렌더러가 첫 건에 `RenderException("PDF 변환 실패")` → `fail(첫,"PDF 변환 실패")`, `complete(둘째, …)` 호출.
  - `AttachmentRenderJobTest.꺼짐이면_drain_안함`.
  - `AttachmentRenderQueueIntegrationTest.claimNext_RUNNING_전이`, `stale_RUNNING_회수_1회`(attempts 1 → PENDING, 2 → FAILED/`렌더링 중단(재기동)`), `동시_claim_한_건씩`(두 스레드가 서로 다른 행).
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*AttachmentRenderJobTest'`; IT는 `RUN_TESTCONTAINERS=true`(CI).
- [ ] **Step 3: 구현** — 위 인터페이스대로.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `RUN_TESTCONTAINERS=true ./gradlew test --tests '*AttachmentRenderQueueIntegrationTest'` PASS.
- [ ] **Step 5: 커밋** — `feat(attachments): 렌더링 큐·단일 스레드 잡·재기동 회수`

### Task 5: claim 대기 + claim 바디 새 필드

**Files:**
- Modify: `repository/InterviewSessionRepository.java:49-56`, `service/InterviewService.java:187-188, 246-283`, `dto/InterviewClaimResponse.java:91-101`
- Test: `service/InterviewServiceClaimRenderWaitIntegrationTest.java`(Testcontainers), `dto/InterviewClaimResponseTest.java`(추가), `service/InterviewServiceTest.java`(추가)

**Interfaces:**
- Consumes: Task 1 `rendition`, Task 2 `RenderProperties.claimWaitSeconds`, Task 4 `renderDirRelativePath`.
- Produces:
  - `findClaimableForUpdateSkipLocked(OffsetDateTime renderWaitCutoff, Pageable)` — JPQL에 두 NOT EXISTS: 같은 `taskId`의 `TaskAttachment`, 같은 세션의 `QuestionAttachment` 중 `rendition.status IN (PENDING, RUNNING)`이고 `createdAt > :renderWaitCutoff`. 호출부는 `now - claimWaitSeconds`(0이면 `now` — 기다리지 않음). (세션의 모든 첨부를 본다 — 스펙 §7.2의 "그 claim이 싣는 첨부"보다 넓지만 오래된 턴의 첨부는 cutoff 밖이라 결과가 같다.)
  - `AttachmentRef`에 필드 추가: `List<String> slideImagePaths`, `Integer slideTotalPages`, `List<String> mediaImagePaths`, `String renderNote`, `Long inheritedFromQuestionId`. 기존 5·6인자 생성자는 빈 목록·null로 위임(compact 생성자에서 null 목록 → `List.of()`).
  - `InterviewService.toRef(TaskAttachment, Long sourceQuestionId)`·`toRef(QuestionAttachment)` — 작업 첨부도 `extractedTextPath`를 채운다. 상태별: DONE → `slide-001..pageCount` 절대경로(첫 파일이 없으면 목록 비우고 `renderNote="슬라이드 그림 없음: 파일이 정리됨"`), `slideTotalPages=totalPages`, `media/` 목록(이름순); FAILED·SKIPPED → `renderNote="슬라이드 그림 없음: {error}"`; PENDING·RUNNING → `"슬라이드 그림 준비 중(대기 시간 초과)"`; NONE → 모두 비움. `inheritedFromQuestionId` = 작업 첨부의 `sourceQuestionAttachmentId != null`이면 `task.sourceQuestionId`.

- [ ] **Step 1: 실패하는 테스트**
  - IT `렌더링_중_첨부가_있으면_claim_보류`: 질문 세션 + PENDING 첨부(방금) → `claim` empty; `createdAt`을 181초 전으로 → claim됨, 그 첨부 `renderNote=="슬라이드 그림 준비 중(대기 시간 초과)"`. `claim-wait-seconds=0`이면 바로 claim.
  - `InterviewServiceTest.DONE_첨부_경로`: pageCount 2·media 2 → 경로 2+2개, 순서 `slide-001`, `slide-002`.
  - `InterviewClaimResponseTest`: 새 필드 JSON 직렬화, 구 생성자는 빈 목록.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*InterviewServiceTest' --tests '*InterviewClaimResponseTest'` (+ IT는 RUN_TESTCONTAINERS).
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 기존 `InterviewServiceRestartTest`·`InterviewClaimResponseTest` 전부 PASS.
- [ ] **Step 5: 커밋** — `feat(interview): 렌더링 대기 claim·슬라이드 그림 경로 전달`

### Task 6: 질문 → 작업 첨부 승계

**Files:**
- Modify: `service/TaskService.java:161-238`(새 `inheritQuestionAttachments`), `service/AttachmentStorage.java`(`copyTree`·`exists`)
- Test: `service/TaskServiceInheritAttachmentsTest.java`(Testcontainers — 실제 파일 복사·롤백), `service/TaskServiceSourceQuestionTest.java`(목 — 순번·이력)

**Interfaces:**
- Consumes: Task 1 `copyWithDir`·`resetToPending`·`create` 오버로드, Task 2 `RenderTargetPolicy`·이벤트, Task 4 `renderDirRelativePath`·폴더 `deleteQuietly`.
- Produces:
  - `AttachmentStorage.copyTree(String fromRel, String toRel)`(파일·폴더 모두, 링크 따라가지 않음), `boolean exists(String rel)`.
  - `TaskService` 생성자에 `QuestionAttachmentRepository`, `@Value("${app.attachment.inherit-max-total-mb:200}")` 주입.
  - `private InheritOutcome inheritQuestionAttachments(Task saved, int firstOrdinal, List<String> written)`, `record InheritOutcome(int copied, int skippedMissing, int skippedOverLimit)` — 대화 전문 다음 순번부터. 대상 `findBySessionIdOrderByIdAsc(sourceQuestionId)`. 원본 없음 → skippedMissing; 누적 `sizeBytes`가 상한 초과 → skippedOverLimit. 원본 복사 → sidecar 있으면 복사 → `.render` 있고 상태 DONE이면 복사 + `copyWithDir`, 질문 쪽 PENDING·RUNNING 또는 DONE인데 폴더 없음 → `policy.initial(name)`(대상이면 PENDING), FAILED·SKIPPED·NONE → 그대로 복사. 각 복사 대상은 쓰기 전에 `written`에 추가. 행: `uploaded_by`=질문 첨부의 값, `sourceQuestionAttachmentId`=질문 첨부 id.
  - 이력(같은 상태 로그, `TaskService.java:353` 선례): `질문 #{id} 첨부 승계: {copied}개` + 제외가 있으면 ` / 제외 {n}개(정리됨·용량 상한)`. 승계 대상이 0개면 이력 없음.

- [ ] **Step 1: 실패하는 테스트** (`TaskServiceInheritAttachmentsTest`)
  - `등록_턴_첨부_순번_N+2부터`: 사용자 파일 1개 + 대화 전문 + 질문 첨부 2개(등록 1, 턴 1) → 순번 3·4, 원본·sidecar 존재.
  - `DONE_렌더링_승계`: 질문 첨부 DONE + `.render/slide-001.png` → 작업 쪽 같은 상태, 새 `render_dir`, 파일 존재.
  - `질문쪽_PENDING이면_작업쪽_PENDING`.
  - `정리된_질문_첨부는_건너뜀`: 원본 파일 삭제 → 등록 성공, 행 없음, 이력 `… / 제외 1개(정리됨·용량 상한)`.
  - `용량_상한_초과분_제외`: 상한 1MB, 첨부 2개 각 0.8MB → 1개만.
  - `복사_실패시_등록_롤백`: `copyTree`가 두 번째에서 던지게(스파이) → 예외, 작업 행 없음, 첫 복사본 삭제됨.
  - `출처_질문_없으면_그대로`.
- [ ] **Step 2: 실패 확인** — `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceInheritAttachmentsTest' --tests '*TaskServiceSourceQuestionTest'`.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `TaskServiceAttachmentRollbackTest` PASS.
- [ ] **Step 5: 커밋** — `feat(tasks): 질문 → 작업 첨부 승계`

### Task 7: 인터뷰 서비스 프롬프트

**Files:**
- Modify: `netismaker-interview-service/src/types.ts:45-57`, `src/runner/interviewRunner.ts:89-109, 145-166`
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`(첨부 describe 두 곳에 추가), `test/fixtures/claims.ts`

**Interfaces:**
- Consumes: Task 5 claim 필드(JSON 이름 그대로: `slideImagePaths`, `slideTotalPages`, `mediaImagePaths`, `renderNote`, `inheritedFromQuestionId`).
- Produces: `AttachmentRef` TS 필드 전부 optional(`?: string[]`, `?: number | null`, `?: string | null`). 공용 `attachmentDetailLines(a: AttachmentRef): string[]` — 들여 쓴 하위 줄(`  - 텍스트 추출본: …`, `  - 슬라이드 그림({n}장, 순서대로): p1, p2` + 잘림 문구, `  - 원본 그림(작은 글씨를 크게 볼 때): …`, `  - {renderNote}`). 두 섹션이 첨부 한 줄 뒤에 붙인다. 승계면 첨부 줄 끝에 ` (질문 #{id}에서 넘어옴)`.
- 작업 kickoff 안내문 교체(스펙 §7.3 뜻 그대로): 오피스 원본은 Read로 못 연다 → 슬라이드 그림을 모두 순서대로 Read해 레이아웃·빨간 표시·화살표가 가리키는 변경점 파악 → 글은 텍스트 추출본 → 작은 글씨는 원본 그림 → 그림 없는 첨부는 추출본만, 모르면 질문 → **계획에 슬라이드 번호와 함께 변경점을 적는다**. 질문 세션 안내문은 같은 뜻 2문장.

- [ ] **Step 1: 실패하는 테스트**
  - kickoff: 그림 2장·media 1·sidecar 있는 첨부 → 프롬프트에 `슬라이드 그림(2장, 순서대로): ` 뒤 두 경로, `텍스트 추출본: `, `원본 그림`, `슬라이드 번호`; 예전 문구 `읽을 수 없는 포맷(docx/xlsx 등 오피스 문서)` 없음.
  - 잘림: `slideTotalPages=45`, 30장 → `(전체 45장 중 30장만 그림 — 나머지는 텍스트 추출본 참고)`.
  - `renderNote` 줄, 승계 표시 `(질문 #12에서 넘어옴)`.
  - 구버전 claim(새 필드 없음) → 하위 줄 없이 지금과 같은 첨부 줄.
  - 파일명 `대시보드 "업그레이드" 안.pptx`가 그대로 나온다.
  - 질문 섹션(신규·resume 첨부) 같은 하위 줄.
- [ ] **Step 2: 실패 확인** — `cd netismaker-interview-service && npx vitest run test/interviewRunner.test.ts`.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 PASS, `npx tsc --noEmit` 오류 0.
- [ ] **Step 5: 커밋** — `feat(interview-service): 슬라이드 그림·추출본·승계 첨부 프롬프트`

### Task 8: 등록 창 승계 안내 한 줄

**Files:**
- Modify: `frontend/pages/tasks/index.vue`(출처 질문 표시 근처), `frontend/composables/questions.ts`(`taskPrefillFromQuestion`이 첨부 이름 목록도 돌려줌)
- Test: `frontend/test/tasks-form-from-question.spec.ts`(추가)

**Interfaces:**
- Consumes: 기존 `QuestionDetail.attachments`·`turns[].attachments`(`composables/questions.ts:58,65`).
- Produces: `taskPrefillFromQuestion(...)` 반환에 `attachmentNames: string[]`(등록 + 턴 첨부, 순서대로).

- [ ] **Step 1: 실패하는 테스트** — 첨부 3개 있는 질문에서 들어오면 `질문 첨부 3개가 함께 등록됩니다` 문구가 보이고, 펼치면 이름 3개; 첨부 없으면 문구 없음.
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/tasks-form-from-question.spec.ts`.
- [ ] **Step 3: 구현** — 접힘은 `q-expansion-item` 대신 텍스트 버튼 + `v-if` 목록(작게, 기존 출처 칩 아래).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `npx vitest run` 전체 PASS, `npm run lint` 오류 0(경고 수 증가 없음).
- [ ] **Step 5: 커밋** — `feat(frontend): 질문 첨부 승계 안내`

### Task 9: 운영 — 백업 제외·문서

**Files:**
- Modify: `scripts/backup-public.ps1:174-178`, `scripts/tests/backup-public.test.ps1`, `CLAUDE.md`, `TODOS.md`
- Test: `scripts/tests/backup-public.test.ps1`

**Interfaces:**
- Consumes: 경로 규칙(`*.render`, `.render-work`).
- Produces: 백업 zip이 `.render-work` 폴더와 이름이 `.render`로 끝나는 폴더를 뺀다(`CreateFromDirectory` 대신 파일 열거 + 제외 후 `ZipArchive`에 추가).

- [ ] **Step 1: 실패하는 테스트** — 테스트 첨부 폴더에 `task-1/1-a.pptx`, `task-1/1-a.pptx.render/slide-001.png`, `.render-work/x/input.pptx` → zip 항목에 `task-1/1-a.pptx`만, 나머지 없음.
- [ ] **Step 2: 실패 확인** — 기존 테스트 실행 방식대로(`scripts/tests/backup-public.test.ps1` 머리 주석의 명령).
- [ ] **Step 3: 구현** + 문서: CLAUDE.md "질문 세션" 첨부 설명 끝에 렌더링·승계 한 단락(설정 키, `SOFFICE_PATH`, 배포 순서 API → 인터뷰 서비스 → 프론트, 기동 로그 문구, 실패 사유 문구), "자주 보는 코드" 표에 `service/render/*`·`TaskService.inheritQuestionAttachments`·`interviewRunner.ts attachmentDetailLines` 행, "환경 메모"에 LibreOffice 설치·매크로 비실행 확인·업데이트 대상. TODOS에 "LibreOffice 보안 업데이트 주기 점검", "원격 링크 이미지 가져오기 차단 검토".
- [ ] **Step 4: 통과 확인** — 테스트 PASS.
- [ ] **Step 5: 커밋** — `chore(ops): 백업에서 렌더링 산출물 제외·문서`

### Task 10: 라이브 확인 (사람 + 운영자)

머지·배포 뒤 한다(코드 변경 없음, 결과를 PR에 댓글로).

- [ ] 라이브 PC에 LibreOffice 설치, `public.env`에 필요하면 `SOFFICE_PATH`, 배포 순서대로 재기동, API 로그 `슬라이드 렌더링: 켜짐(soffice=…)`.
- [ ] `261027_대시보드_업그레이드개발.pptx`로 작업 등록 → `.render/slide-001.png`·`slide-002.png` 생성, 한글 깨짐 없음.
- [ ] 승인 → 인터뷰 로그에 두 PNG Read → 계획에 "슬라이드 1: 상단 카드 2개·하단 차트 2개 추가", "슬라이드 2: 설정 팝업 장비·회선 타이틀 설정"이 슬라이드 번호와 함께 들어감.
- [ ] 같은 파일로 질문 → "이 대화로 작업 등록" → 작업 첨부에 pptx·`.txt`·`.render`가 넘어오고 이력 `질문 #… 첨부 승계: 1개`.
