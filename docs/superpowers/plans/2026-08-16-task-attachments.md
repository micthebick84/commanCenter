# 작업 첨부파일 (Task Attachments) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 등록 시 파일을 첨부하고, 관리자 승인으로 시작되는 인터뷰(brainstorming) 에이전트가 그 파일을 Read 도구로 직접 읽으며, 요청자/관리자가 UI에서 내려받을 수 있게 한다.

**Architecture:** 파일은 디스크(`app.attachment.dir`, 기본 `~/netis-maker/attachments/task-{id}/`)에, 메타는 `com.task_attachment`(V19)에 저장. 등록은 `POST /api/tasks`의 multipart 매핑 신설(기존 JSON 매핑 무변경). 승인 후 워커 claim(`InterviewClaimResponse`)에 절대경로 목록을 실어 보내면 Node 인터뷰 워커가 kickoff 프롬프트에 첨부 섹션을 삽입한다(에이전트 Read는 이미 경로 무제한 — 권한 변경 없음). 다운로드는 `GET /api/tasks/{id}/attachments/{attId}` 스트리밍.

**Tech Stack:** Spring Boot 3.4.1 (Java 21, JPA/Flyway/MockMvc/Testcontainers 2.0.5), Node 20 + TypeScript + vitest (인터뷰 워커), Nuxt 3 + Quasar + Vitest/VTU (프론트).

**Spec:** `docs/superpowers/specs/2026-08-16-task-attachments-design.md` — 이 계획의 모든 결정 근거. 실행자는 스펙을 먼저 읽을 것.

## Global Constraints

- 한도(스펙 §6.1 고정값): 최대 **10개**, 파일당 **20MB**, 합계 **50MB**, **0바이트 거부**. 차단 확장자(대소문자 무시): `exe dll so dylib bat cmd sh ps1 msi scr com pif vbs app`
- 저장 상대경로 규칙: `task-{taskId}/{ordinal}-{sanitized}` — `{ordinal}`은 업로드 순번 1..N (**DB id 아님** — IDENTITY 전략이라 INSERT 전 id를 알 수 없다, 스펙 §4)
- `attachments`류 컬렉션 계약: **항상 non-null**. Java는 `[]` 기본, TS 읽기는 무조건 `?? []`/`Array.isArray` 가드 (스펙 §5.2/§5.4/§7)
- 기존 `POST /api/tasks` JSON 매핑에는 **consumes를 추가하지 말 것** (스펙 §5.1)
- 프론트 코드 스타일: 작은따옴표 + 세미콜론 없음 + 2-space (`frontend/CLAUDE.md`), **`npm run lint-prettier`를 frontend/ 전체에 돌리지 말 것**
- Node 서비스(`netismaker-interview-service/`)는 세미콜론 사용 (기존 스타일)
- 백엔드 통합 테스트는 `@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")` 게이트 필수. 로컬 실행: `RUN_TESTCONTAINERS=true ./gradlew test`
- 커밋 메시지는 한국어 + 기존 컨벤션(`feat:`/`fix:`/`docs:` 프리픽스), 끝에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- 서버 에러 메시지는 한국어, 응답 형태는 `{error, message}` (GlobalExceptionHandler 기존 형식)

## File Structure (전체 조감)

| 구분 | 파일 | 역할 |
|---|---|---|
| Create | `src/main/resources/db/migration/V19__task_attachment.sql` | 테이블+인덱스 |
| Create | `src/main/java/com/hamonsoft/netismaker/entity/TaskAttachment.java` | 메타 엔티티 |
| Create | `src/main/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepository.java` | 파생 쿼리 |
| Create | `src/main/java/com/hamonsoft/netismaker/service/AttachmentStorage.java` | 검증·sanitize·쓰기·resolve·삭제 (FS 전담) |
| Modify | `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` | create 오버로드 + 첨부 조회 |
| Modify | `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` | multipart 매핑 + 다운로드 |
| Modify | `src/main/java/com/hamonsoft/netismaker/service/GlobalExceptionHandler.java` | 413/누락파트 핸들러 |
| Modify | `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java` | attachments 필드 |
| Modify | `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java` | AttachmentRef + of 확장 |
| Modify | `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` | claim 시 첨부 조회 |
| Modify | `src/main/resources/application.yml` | multipart + app.attachment.* |
| Modify | `netismaker-interview-service/src/types.ts` | AttachmentRef |
| Modify | `netismaker-interview-service/src/runner/interviewRunner.ts` | kickoff 첨부 섹션 |
| Modify | `netismaker-interview-service/test/fixtures/claims.ts` | 첨부 fixture |
| Modify | `frontend/pages/tasks/index.vue` | q-file + FormData 전송 |
| Modify | `frontend/pages/tasks/[id].vue` | 첨부 섹션 + 다운로드 |
| Modify | `TODOS.md` | 첨부 GC 항목 |

작업 순서 의존성: 1→2→3→4→5→6→7→8, 9·10은 4·6 이후 (프론트), 11은 마지막.

---

### Task 1: V19 마이그레이션 + TaskAttachment 엔티티 + 리포지토리

**Files:**
- Create: `src/main/resources/db/migration/V19__task_attachment.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskAttachment.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크). `com.task`는 V1부터 존재.
- Produces: `TaskAttachment.create(Long taskId, String originalFilename, String storedPath, String contentType, long sizeBytes, String uploadedBy)` 팩토리, Lombok `@Getter`로 `getId()/getTaskId()/getOriginalFilename()/getStoredPath()/getContentType()/getSizeBytes()/getUploadedBy()/getCreatedAt()`. `TaskAttachmentRepository.findByTaskIdOrderByIdAsc(Long taskId)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepositoryTest.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskAttachmentRepositoryTest {

    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach void clean() {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task savedTask() {
        Task t = Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null);
        return taskRepo.save(t);
    }

    @Test
    void 저장_후_taskId로_순서대로_조회된다() {
        Task t = savedTask();
        attachmentRepo.save(TaskAttachment.create(t.getId(), "요구사항.pdf",
                "task-" + t.getId() + "/1-요구사항.pdf", "application/pdf", 1234L, "user1"));
        attachmentRepo.save(TaskAttachment.create(t.getId(), "화면.png",
                "task-" + t.getId() + "/2-화면.png", "image/png", 99L, "user1"));

        List<TaskAttachment> found = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId());

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getOriginalFilename()).isEqualTo("요구사항.pdf");
        assertThat(found.get(0).getStoredPath()).startsWith("task-" + t.getId() + "/1-");
        assertThat(found.get(1).getSizeBytes()).isEqualTo(99L);
        assertThat(found.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void contentType은_null을_허용한다() {
        Task t = savedTask();
        TaskAttachment saved = attachmentRepo.save(TaskAttachment.create(
                t.getId(), "raw.bin", "task-" + t.getId() + "/1-raw.bin", null, 1L, "user1"));
        assertThat(attachmentRepo.findById(saved.getId()).orElseThrow().getContentType()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.repository.TaskAttachmentRepositoryTest'`
Expected: 컴파일 실패 (`TaskAttachment`, `TaskAttachmentRepository` 미존재)

- [ ] **Step 3: 마이그레이션 + 엔티티 + 리포지토리 구현**

`src/main/resources/db/migration/V19__task_attachment.sql`:

```sql
-- 작업 첨부파일 메타 (파일 본체는 디스크: app.attachment.dir 하위, 스펙 2026-08-16 §4)
CREATE TABLE IF NOT EXISTS com.task_attachment (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    stored_path       VARCHAR(500) NOT NULL,
    content_type      VARCHAR(100),
    size_bytes        BIGINT NOT NULL,
    uploaded_by       VARCHAR(20) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_attachment_task ON com.task_attachment(task_id);
```

`src/main/java/com/hamonsoft/netismaker/entity/TaskAttachment.java`:

```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 작업 첨부파일 메타 (1 task : N attachment, 등록 시점에만 생성 — 추가/삭제 없음).
 * 파일 본체는 app.attachment.dir 하위 stored_path(상대경로)에 있다.
 * stored_path = task-{taskId}/{ordinal}-{sanitized} — ordinal은 업로드 순번(1..N).
 * DB id가 아닌 이유: IDENTITY 전략이라 INSERT 전에 id를 알 수 없는데 stored_path는 NOT NULL.
 */
@Entity
@Table(name = "task_attachment", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    /** 클라이언트 신고값 — 신뢰하지 않음(표시·프롬프트 참고용). null 가능. */
    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false, length = 20)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TaskAttachment create(Long taskId, String originalFilename, String storedPath,
                                        String contentType, long sizeBytes, String uploadedBy) {
        TaskAttachment a = new TaskAttachment();
        a.taskId = taskId;
        a.originalFilename = originalFilename;
        a.storedPath = storedPath;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.uploadedBy = uploadedBy;
        a.createdAt = OffsetDateTime.now();
        return a;
    }
}
```

`src/main/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepository.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /** 업로드 순서(= id 순) 그대로. */
    List<TaskAttachment> findByTaskIdOrderByIdAsc(Long taskId);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.repository.TaskAttachmentRepositoryTest'`
Expected: PASS (Flyway가 V19 적용, `ddl-auto: validate` 통과 = 엔티티-DDL 일치 증명)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration/V19__task_attachment.sql \
        src/main/java/com/hamonsoft/netismaker/entity/TaskAttachment.java \
        src/main/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepository.java \
        src/test/java/com/hamonsoft/netismaker/repository/TaskAttachmentRepositoryTest.java
git commit -m "feat: 작업 첨부파일 메타 테이블(V19)+엔티티+리포지토리

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: AttachmentStorage — 검증·sanitize·쓰기·resolve·삭제 (순수 단위 테스트)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/AttachmentStorage.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/AttachmentStorageTest.java`

**Interfaces:**
- Consumes: `TaskException(HttpStatus, String)` 공개 생성자 (기존).
- Produces (이후 태스크가 그대로 사용):
  - `void validate(List<MultipartFile> files)` — 한도/확장자/0바이트 위반 시 `TaskException(BAD_REQUEST, 한국어)`
  - `static String sanitize(String name)` — 경로 구분자/`..`/제어문자 제거, 200자 제한, 빈 결과 → `"file"`
  - `String relativePath(long taskId, int ordinal, String originalFilename)` — `task-{taskId}/{ordinal}-{sanitized}`
  - `void write(String relativePath, MultipartFile file)` — 디렉터리 생성 + 저장, IOException → `TaskException(500)`
  - `Path resolve(String relativePath)` — 절대경로 반환, 루트 탈출 시 `TaskException.notFound()`
  - `String absolutePathOf(String relativePath)` — `resolve(...).toString()`
  - `void deleteQuietly(String relativePath)` — 실패 무시(로그만)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/AttachmentStorageTest.java`:

```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStorageTest {

    @TempDir Path tmp;

    /** 테스트용 소형 한도: 최대 2개, 파일당 1MB, 합계 1MB. */
    private AttachmentStorage storage() {
        return new AttachmentStorage(tmp.toString(), 2, 1, 1);
    }

    private static MockMultipartFile file(String name, int bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", new byte[bytes]);
    }

    // ── validate ──────────────────────────────────────────────

    @Test
    void 개수_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(
                file("a.txt", 1), file("b.txt", 1), file("c.txt", 1))))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("최대 2개");
    }

    @Test
    void 파일당_크기_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("big.bin", 1024 * 1024 + 1))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("1MB");
    }

    @Test
    void 합계_크기_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(
                file("a.bin", 600 * 1024), file("b.bin", 600 * 1024))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("합계");
    }

    @Test
    void 빈_파일은_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("empty.txt", 0))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("빈 파일");
    }

    @Test
    void 차단_확장자는_대소문자_무시하고_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("evil.EXE", 1))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("허용되지 않는 파일 형식");
        assertThatThrownBy(() -> storage().validate(List.of(file("run.sh", 1))))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void 정상_파일들은_통과한다() {
        storage().validate(List.of(file("요구사항.pdf", 100), file("화면.png", 100)));
    }

    // ── sanitize ──────────────────────────────────────────────

    @Test
    void sanitize는_경로_구분자와_탈출_시퀀스를_제거한다() {
        assertThat(AttachmentStorage.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentStorage.sanitize("a/b\\c.txt")).isEqualTo("c.txt");
        assertThat(AttachmentStorage.sanitize("한글 파일명.pdf")).isEqualTo("한글 파일명.pdf");
        assertThat(AttachmentStorage.sanitize("bad name\n.txt")).isEqualTo("badname.txt");
        assertThat(AttachmentStorage.sanitize("  ")).isEqualTo("file");
        assertThat(AttachmentStorage.sanitize(null)).isEqualTo("file");
        // 200자 초과 → 뒤쪽(확장자 포함)을 보존
        String longName = "a".repeat(300) + ".pdf";
        String out = AttachmentStorage.sanitize(longName);
        assertThat(out).hasSize(200).endsWith(".pdf");
    }

    // ── relativePath / write / resolve / delete ──────────────

    @Test
    void relativePath_형식은_task디렉터리_순번_파일명이다() {
        assertThat(storage().relativePath(42L, 1, "요구사항.pdf"))
                .isEqualTo("task-42/1-요구사항.pdf");
    }

    @Test
    void write는_디렉터리를_만들고_내용을_저장한다() throws Exception {
        AttachmentStorage s = storage();
        MockMultipartFile f = new MockMultipartFile("files", "a.txt", "text/plain", "내용".getBytes());
        s.write("task-1/1-a.txt", f);
        assertThat(Files.readString(tmp.resolve("task-1/1-a.txt"))).isEqualTo("내용");
    }

    @Test
    void resolve는_루트_탈출을_거부한다() {
        assertThatThrownBy(() -> storage().resolve("../outside.txt"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void absolutePathOf는_루트가_prefix인_절대경로를_준다() {
        String abs = storage().absolutePathOf("task-1/1-a.txt");
        assertThat(abs).startsWith(tmp.toAbsolutePath().toString()).endsWith("1-a.txt");
    }

    @Test
    void deleteQuietly는_없는_파일에도_예외를_던지지_않는다() {
        storage().deleteQuietly("task-9/9-none.txt");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.AttachmentStorageTest'`
Expected: 컴파일 실패 (`AttachmentStorage` 미존재). (Testcontainers 불필요 — 순수 단위)

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/service/AttachmentStorage.java`:

```java
package com.hamonsoft.netismaker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 첨부파일의 파일시스템 전담 컴포넌트 (스펙 §6). DB 메타는 TaskService가,
 * 디스크(검증·경로·쓰기·resolve·삭제)는 여기서만 다룬다.
 *
 * 저장 상대경로 = task-{taskId}/{ordinal}-{sanitized}. ordinal은 업로드 순번(1..N) —
 * 첨부는 등록 트랜잭션에서만 생성되고 추가/삭제가 없어(스펙 §2) 순번이 영구히 유일하다.
 */
@Component
@Profile("api")
public class AttachmentStorage {

    /** 실행파일류만 차단 — 종류 제한 없음이 요구사항 (스펙 §2). */
    static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "so", "dylib", "bat", "cmd", "sh", "ps1",
            "msi", "scr", "com", "pif", "vbs", "app");

    private final Path root;
    private final int maxFiles;
    private final int maxFileSizeMb;
    private final int maxTotalSizeMb;

    public AttachmentStorage(
            @Value("${app.attachment.dir:${user.home}/netis-maker/attachments}") String dir,
            @Value("${app.attachment.max-files:10}") int maxFiles,
            @Value("${app.attachment.max-file-size-mb:20}") int maxFileSizeMb,
            @Value("${app.attachment.max-total-size-mb:50}") int maxTotalSizeMb) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxFiles = maxFiles;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxTotalSizeMb = maxTotalSizeMb;
    }

    /** 한도·확장자·빈파일 검증. 위반 시 400 + 한국어 메시지 (스펙 §6.1 순서). */
    public void validate(List<MultipartFile> files) {
        if (files.size() > maxFiles) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "첨부는 최대 " + maxFiles + "개까지 가능합니다");
        }
        long totalBytes = 0;
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (f.isEmpty()) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "빈 파일(0바이트)은 첨부할 수 없습니다: " + name);
            }
            if (f.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "파일당 " + maxFileSizeMb + "MB 이하만 첨부할 수 있습니다: " + name);
            }
            String ext = extensionOf(name);
            if (BLOCKED_EXTENSIONS.contains(ext)) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "허용되지 않는 파일 형식입니다: ." + ext);
            }
            totalBytes += f.getSize();
        }
        if (totalBytes > (long) maxTotalSizeMb * 1024 * 1024) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "첨부 합계는 " + maxTotalSizeMb + "MB 이하여야 합니다");
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * 파일명 정리: 경로 구분자 뒤만 취하고, '..'/제어문자 제거, 200자 제한(확장자가 뒤에
     * 있으므로 뒤쪽 보존), 빈 결과는 "file".
     */
    static String sanitize(String name) {
        String base = name == null ? "" : name;
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replace("..", "");
        base = base.replaceAll("\\p{Cntrl}", "");
        base = base.trim();
        if (base.length() > 200) base = base.substring(base.length() - 200);
        if (base.isBlank()) base = "file";
        return base;
    }

    public String relativePath(long taskId, int ordinal, String originalFilename) {
        return "task-" + taskId + "/" + ordinal + "-" + sanitize(originalFilename);
    }

    public void write(String relativePath, MultipartFile file) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "첨부 파일 저장에 실패했습니다: " + e.getMessage());
        }
    }

    /** 루트 하위임을 재확인(디렉터리 탈출 이중 방어, 스펙 §5.3). */
    public Path resolve(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) throw TaskException.notFound();
        return p;
    }

    public String absolutePathOf(String relativePath) {
        return resolve(relativePath).toString();
    }

    /** 롤백 시 best-effort 정리 — 실패는 무시한다 (tx는 이미 롤백 경로). */
    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | RuntimeException ignored) {
            // best-effort: 잔존 파일은 무해 (메타가 롤백되어 참조 불가)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.AttachmentStorageTest'`
Expected: PASS (전 케이스)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/AttachmentStorage.java \
        src/test/java/com/hamonsoft/netismaker/service/AttachmentStorageTest.java
git commit -m "feat: AttachmentStorage — 첨부 검증·sanitize·디스크 저장 컴포넌트

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: TaskService.create 파일 오버로드 + 설정 (application.yml)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (생성자·필드·create 오버로드·조회 3메서드)
- Modify: `src/main/resources/application.yml` (multipart + app.attachment)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceAttachmentTest.java`

**Interfaces:**
- Consumes: Task 1의 `TaskAttachment.create(...)`/`findByTaskIdOrderByIdAsc`, Task 2의 `AttachmentStorage` 전체.
- Produces:
  - `Task create(TaskCreateRequest req, List<MultipartFile> files, String requesterId)` — 기존 2-인자 `create`는 **시그니처 유지**(다수 테스트가 호출)
  - `List<TaskAttachment> getAttachments(Long taskId)`
  - `TaskAttachment getAttachment(Long taskId, Long attachmentId)` — taskId 불일치/미존재 → `TaskException.notFound()`
  - `Path resolveAttachmentPath(TaskAttachment att)` — 컨트롤러가 다운로드에 사용

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/TaskServiceAttachmentTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-att-test")
class TaskServiceAttachmentTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // repoCatalogId=1 = V14 시드 (InterviewApiIntegrationTest.startSession과 동일 전제)
    private static TaskCreateRequest req() {
        return new TaskCreateRequest(1L, "main", "첨부 테스트", "설명");
    }

    @Test
    void 파일과_함께_등록하면_메타행과_디스크_파일이_모두_생긴다() throws Exception {
        Task t = taskService.create(req(), List.of(
                file("요구사항.txt", "내용A"), file("데이터.csv", "a,b")), "user1");

        List<TaskAttachment> atts = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId());
        assertThat(atts).hasSize(2);
        assertThat(atts.get(0).getStoredPath()).isEqualTo("task-" + t.getId() + "/1-요구사항.txt");
        assertThat(atts.get(1).getStoredPath()).isEqualTo("task-" + t.getId() + "/2-데이터.csv");
        assertThat(atts.get(0).getUploadedBy()).isEqualTo("user1");

        Path root = Path.of(attachmentDir);
        assertThat(Files.readString(root.resolve("task-" + t.getId() + "/1-요구사항.txt")))
                .isEqualTo("내용A");
    }

    @Test
    void 파일_없이_null_또는_빈리스트면_기존_등록과_동일하다() {
        Task t1 = taskService.create(req(), null, "user1");
        Task t2 = taskService.create(req(), List.of(), "user1");
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t1.getId())).isEmpty();
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t2.getId())).isEmpty();
    }

    @Test
    void 검증_실패면_task도_생성되지_않는다() {
        long before = taskRepo.count();
        assertThatThrownBy(() -> taskService.create(req(),
                List.of(file("evil.exe", "x")), "user1"))
                .isInstanceOf(TaskException.class);
        assertThat(taskRepo.count()).isEqualTo(before);
    }

    @Test
    void getAttachment은_taskId가_다르면_notFound다() {
        Task t = taskService.create(req(), List.of(file("a.txt", "x")), "user1");
        Long attId = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId()).get(0).getId();
        assertThatThrownBy(() -> taskService.getAttachment(t.getId() + 999, attId))
                .isInstanceOf(TaskException.class);
        assertThat(taskService.getAttachment(t.getId(), attId).getOriginalFilename())
                .isEqualTo("a.txt");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskServiceAttachmentTest'`
Expected: 컴파일 실패 (`create(req, files, userId)` 오버로드 미존재)

- [ ] **Step 3: TaskService 구현**

`TaskService.java` 수정 — ① import 추가:

```java
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Path;
```

② 필드/생성자에 두 의존성 추가 (기존 8개 뒤에):

```java
    private final TaskAttachmentRepository attachmentRepo;
    private final AttachmentStorage attachmentStorage;
```

생성자 파라미터 끝에 `TaskAttachmentRepository attachmentRepo, AttachmentStorage attachmentStorage` 추가 + 대입 2줄. (Spring 생성자 주입이라 다른 호출부 컴파일 영향은 테스트에서 `new TaskService(...)`를 직접 쓰는 곳뿐 — `grep -rn "new TaskService(" src/test/`로 확인 후 있으면 인자 2개 추가.)

③ 기존 `create(TaskCreateRequest, String)` **바로 아래에** 오버로드 + 조회 3메서드 추가:

```java
    /**
     * 파일 첨부 등록 (스펙 2026-08-16 §6.2). 검증 → 기존 create 재사용(같은 tx) →
     * 메타 행 + 디스크 쓰기. 실패 시 tx 롤백 + 이미 쓴 파일 best-effort 삭제 — 부분 상태 없음.
     * 경로의 ordinal은 업로드 순번(1..N) — IDENTITY라 INSERT 전 id를 못 쓴다.
     */
    @Transactional
    public Task create(TaskCreateRequest req, List<MultipartFile> files, String requesterId) {
        List<MultipartFile> attached = files == null ? List.of() : files;
        attachmentStorage.validate(attached);
        Task saved = create(req, requesterId);   // 내부 호출 — 이미 @Transactional 안이라 같은 tx
        List<String> written = new ArrayList<>();
        try {
            int ordinal = 1;
            for (MultipartFile f : attached) {
                String rel = attachmentStorage.relativePath(saved.getId(), ordinal, f.getOriginalFilename());
                attachmentRepo.save(TaskAttachment.create(saved.getId(),
                        AttachmentStorage.sanitize(f.getOriginalFilename()), rel,
                        f.getContentType(), f.getSize(), requesterId));
                written.add(rel);
                attachmentStorage.write(rel, f);
                ordinal++;
            }
        } catch (RuntimeException e) {
            written.forEach(attachmentStorage::deleteQuietly);
            throw e;   // tx 롤백 → task/메타 행 전부 취소
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TaskAttachment> getAttachments(Long taskId) {
        return attachmentRepo.findByTaskIdOrderByIdAsc(taskId);
    }

    /** 첨부 단건 — task 소속이 아니면 존재를 숨긴다(404). ACL은 컨트롤러의 getForView가 담당. */
    @Transactional(readOnly = true)
    public TaskAttachment getAttachment(Long taskId, Long attachmentId) {
        TaskAttachment a = attachmentRepo.findById(attachmentId)
                .orElseThrow(TaskException::notFound);
        if (!a.getTaskId().equals(taskId)) throw TaskException.notFound();
        return a;
    }

    public Path resolveAttachmentPath(TaskAttachment att) {
        return attachmentStorage.resolve(att.getStoredPath());
    }
```

④ `application.yml` — `spring:` 블록에 multipart 추가(`security:` 항목 앞), `app:` 블록 끝에 attachment 추가:

```yaml
  servlet:
    multipart:
      # 앱 검증(20MB/50MB, AttachmentStorage)이 먼저 걸려 한국어 에러가 나가도록 약간 높게.
      # 이 한도 초과는 GlobalExceptionHandler의 MaxUploadSizeExceededException(413) 처리.
      max-file-size: 25MB
      max-request-size: 60MB
```

```yaml
  attachment:
    dir: ${user.home}/netis-maker/attachments
    max-files: 10
    max-file-size-mb: 20
    max-total-size-mb: 50
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskServiceAttachmentTest' --tests 'com.hamonsoft.netismaker.service.AttachmentStorageTest'`
Expected: PASS

- [ ] **Step 5: 회귀 확인 + 커밋**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: 전체 PASS (기존 2-인자 create 사용처 무영향 확인)

```bash
git add src/main/java/com/hamonsoft/netismaker/service/TaskService.java \
        src/main/resources/application.yml \
        src/test/java/com/hamonsoft/netismaker/service/TaskServiceAttachmentTest.java
git commit -m "feat: 작업 등록에 첨부파일 저장 오버로드 + multipart/attachment 설정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: multipart 컨트롤러 매핑 + GlobalExceptionHandler 확장

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (create 아래 createMultipart 추가)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/GlobalExceptionHandler.java` (핸들러 2개 추가)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java` (신규)
- Test: `src/test/java/com/hamonsoft/netismaker/service/GlobalExceptionHandlerTest.java` (신규, 순수 단위)

**Interfaces:**
- Consumes: Task 3의 `taskService.create(req, files, userId)`.
- Produces: `POST /api/tasks` (consumes=multipart/form-data, parts: `meta`=JSON, `files`=N개 선택) → 201 + `TaskResponse`. 413/400 에러가 `{error, message}` 한국어 형태.

- [ ] **Step 1: 실패하는 테스트 작성 (통합)**

`src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-att-api-test")
class TaskAttachmentApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private MockMultipartFile metaPart() throws Exception {
        return new MockMultipartFile("meta", "meta", "application/json",
                json.writeValueAsBytes(new TaskCreateRequest(1L, "main", "첨부 등록", "설명")));
    }

    private static MockMultipartFile filePart(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void multipart_등록은_201과_함께_메타행과_디스크_파일을_만든다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("첨부 등록"));

        Long taskId = taskRepo.findAll().get(0).getId();
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(taskId)).hasSize(1);
        assertThat(Files.exists(Path.of(attachmentDir, "task-" + taskId + "/1-요구사항.txt"))).isTrue();
    }

    @Test
    void 파일_파트_없는_multipart_등록도_동작한다() throws Exception {
        mvc.perform(multipart("/api/tasks").file(metaPart()).with(userJwt("user1")))
                .andExpect(status().isCreated());
        assertThat(attachmentRepo.count()).isZero();
    }

    @Test
    void 기존_JSON_등록은_변함없이_동작한다() throws Exception {
        mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                new TaskCreateRequest(1L, "main", "JSON 등록", "설명"))))
                .andExpect(status().isCreated());
    }

    @Test
    void 차단_확장자는_400_한국어_메시지고_task가_생기지_않는다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("evil.exe", "x"))
                        .with(userJwt("user1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("허용되지 않는 파일 형식")));
        assertThat(taskRepo.count()).isZero();
        assertThat(Files.exists(Path.of(attachmentDir))).isFalse();
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (핸들러 단위)**

`src/test/java/com/hamonsoft/netismaker/service/GlobalExceptionHandlerTest.java`:

```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void multipart_한도_초과는_413_한국어_메시지다() {
        var res = handler.handleMaxUpload(new MaxUploadSizeExceededException(25L * 1024 * 1024));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody()).containsKeys("error", "message");
        assertThat(res.getBody().get("message").toString()).contains("한도");
    }

    @Test
    void 필수_파트_누락은_400이고_파트명을_알려준다() {
        var res = handler.handleMissingPart(new MissingServletRequestPartException("meta"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("message").toString()).contains("meta");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.GlobalExceptionHandlerTest'`
Expected: 컴파일 실패 (`handleMaxUpload` 미존재)

- [ ] **Step 4: 구현**

`TaskController.java` — 기존 `create` 메서드 **바로 아래에** 추가 (import에 `org.springframework.web.multipart.MultipartFile`, `java.util.List` 추가):

```java
    /**
     * 파일 첨부 등록 (스펙 2026-08-16 §5.1). 기존 JSON 매핑은 consumes 미지정 그대로 —
     * multipart 요청만 이 더 구체적인 매핑으로 라우팅된다. 기존 매핑에 consumes를 달지 말 것.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskResponse> createMultipart(
            @RequestPart("meta") @Valid TaskCreateRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        Task t = taskService.create(req, files, userId);
        TaskResponse body = TaskResponse.of(t, null);
        return ResponseEntity.created(URI.create("/api/tasks/" + t.getId())).body(body);
    }
```

`GlobalExceptionHandler.java` — 클래스 끝에 추가 (import: `org.springframework.web.multipart.MaxUploadSizeExceededException`, `org.springframework.web.multipart.support.MissingServletRequestPartException`):

```java
    /**
     * 스프링 multipart 한도(spring.servlet.multipart.*, 25MB/60MB) 초과 — 앱 검증(20/50MB)에
     * 도달하기 전에 여기 걸린다. 기본 에러로 새지 않게 프로젝트 공통 형식으로 변환 (스펙 §6.1).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Payload Too Large");
        body.put("message", "업로드 용량 한도를 초과했습니다 (파일당 최대 25MB, 요청 전체 60MB)");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Bad Request");
        body.put("message", "필수 요청 파트가 없습니다: " + e.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
```

주의: **MockMvc는 multipart 한도를 실제로 강제하지 않는다** (서블릿 파서를 안 거침) — 그래서 413 경로는 핸들러 단위 테스트로 검증하고, 실경로는 Task 11 수동 스모크에서 25MB 파일로 확인한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.TaskAttachmentApiIntegrationTest' --tests 'com.hamonsoft.netismaker.service.GlobalExceptionHandlerTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/TaskController.java \
        src/main/java/com/hamonsoft/netismaker/service/GlobalExceptionHandler.java \
        src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java \
        src/test/java/com/hamonsoft/netismaker/service/GlobalExceptionHandlerTest.java
git commit -m "feat: POST /api/tasks multipart 매핑 + 업로드 예외 한국어 응답

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: TaskResponse.attachments + 상세 응답 연결

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (`get`만)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskResponseAttachmentsTest.java` (신규 단위)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java` (테스트 1개 추가)

**Interfaces:**
- Consumes: Task 1 `TaskAttachment`, Task 3 `taskService.getAttachments(Long)`.
- Produces: `TaskResponse.attachments: List<AttachmentView>` — **항상 non-null**; `AttachmentView(Long id, String fileName, String contentType, long sizeBytes, OffsetDateTime createdAt)`; canonical `of(Task, TaskAnalysis, TaskDesign, Long, List<TaskAttachment>)`. 기존 3개 오버로드는 시그니처 유지, `List.of()` 위임 — **목록 응답은 항상 []** (스펙 §5.2, N+1 회피).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/dto/TaskResponseAttachmentsTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseAttachmentsTest {

    private Task task() {
        return Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null);
    }

    @Test
    void 기존_오버로드는_빈_리스트를_기본값으로_가진다() {
        assertThat(TaskResponse.of(task(), null).attachments()).isEmpty();
        assertThat(TaskResponse.of(task(), null, null).attachments()).isEmpty();
        assertThat(TaskResponse.of(task(), null, null, null).attachments()).isEmpty();
    }

    @Test
    void canonical_of는_엔티티를_AttachmentView로_매핑한다() {
        TaskAttachment a = TaskAttachment.create(1L, "요구사항.pdf",
                "task-1/1-요구사항.pdf", "application/pdf", 1234L, "user1");
        TaskResponse r = TaskResponse.of(task(), null, null, null, List.of(a));
        assertThat(r.attachments()).hasSize(1);
        assertThat(r.attachments().get(0).fileName()).isEqualTo("요구사항.pdf");
        assertThat(r.attachments().get(0).sizeBytes()).isEqualTo(1234L);
        assertThat(r.attachments().get(0).contentType()).isEqualTo("application/pdf");
    }

    @Test
    void null_리스트를_넘겨도_빈_리스트로_정규화된다() {
        assertThat(TaskResponse.of(task(), null, null, null, null).attachments()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.dto.TaskResponseAttachmentsTest'`
Expected: 컴파일 실패 (`attachments()` 미존재)

- [ ] **Step 3: TaskResponse 구현**

`TaskResponse.java` 수정 — ① import 추가: `com.hamonsoft.netismaker.entity.TaskAttachment`. ② record 컴포넌트 목록 끝(`Long interviewSessionId` 뒤)에 추가:

```java
        Long interviewSessionId,
        /** 항상 non-null. 목록 엔드포인트는 항상 [] — 실데이터는 상세 응답만 (스펙 §5.2). */
        List<AttachmentView> attachments
```

③ 중첩 record 추가 (`DeploymentView` 아래):

```java
    public record AttachmentView(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            java.time.OffsetDateTime createdAt
    ) {
        static AttachmentView from(TaskAttachment a) {
            return new AttachmentView(a.getId(), a.getOriginalFilename(),
                    a.getContentType(), a.getSizeBytes(), a.getCreatedAt());
        }
    }
```

④ 오버로드 체인 수정 — 기존 3개는 유지하되 canonical에 위임, canonical에 파라미터 추가:

```java
    public static TaskResponse of(Task t, TaskAnalysis a) {
        return of(t, a, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d) {
        return of(t, a, d, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId) {
        return of(t, a, d, interviewSessionId, List.of());
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId,
                                  List<TaskAttachment> attachments) {
```

⑤ canonical 본문의 `new TaskResponse(...)` 마지막 인자 `interviewSessionId` 뒤에 추가:

```java
                interviewSessionId,
                attachments == null ? List.of()
                        : attachments.stream().map(AttachmentView::from).toList()
```

⑥ `TaskController.get`만 수정 — 상세 응답에 실데이터 전달:

```java
    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = taskService.getForView(id, userId, isAdmin);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null),
                interviewService.latestSessionIdForTask(id).orElse(null),
                taskService.getAttachments(id));
    }
```

다른 `TaskResponse.of` 호출부(list/cancel/retry/approve/deploy 등)는 **수정하지 않는다** — 기존 오버로드가 `[]`를 채운다.

⑦ 통합 테스트 추가 — `TaskAttachmentApiIntegrationTest.java`에:

```java
    @Test
    void 상세_조회에는_attachments_메타가_실린다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated());
        Long taskId = taskRepo.findAll().get(0).getId();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/" + taskId).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].sizeBytes").value(6));
        // "내용" = UTF-8 6바이트
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.dto.TaskResponseAttachmentsTest' --tests 'com.hamonsoft.netismaker.controller.TaskAttachmentApiIntegrationTest'`
Expected: PASS

- [ ] **Step 5: 전체 회귀 + 커밋**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: 전체 PASS (record 필드 추가로 깨지는 기존 단언 없는지 — 깨지면 해당 테스트의 생성자 직접 호출부에 `List.of()` 추가)

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java \
        src/main/java/com/hamonsoft/netismaker/controller/TaskController.java \
        src/test/java/com/hamonsoft/netismaker/dto/TaskResponseAttachmentsTest.java \
        src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java
git commit -m "feat: 작업 상세 응답에 첨부 메타(attachments) 노출

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 첨부 다운로드 엔드포인트 (ACL + 스트리밍)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java` (테스트 추가)

**Interfaces:**
- Consumes: Task 3의 `getAttachment(Long, Long)`/`resolveAttachmentPath(TaskAttachment)`, 기존 `getForView` (logsStream과 동일한 ACL 패턴 — `TaskController.java:172-177` 참조).
- Produces: `GET /api/tasks/{id}/attachments/{attId}` → 200 octet-stream + `Content-Disposition: attachment; filename*=UTF-8''...` / 403(타인) / 404(메타 없음·타 task 소속·디스크 유실).

- [ ] **Step 1: 실패하는 테스트 작성**

`TaskAttachmentApiIntegrationTest.java`에 추가 (헬퍼 `adminJwt`도 추가):

```java
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** multipart 등록 후 (taskId, attachmentId) 반환. */
    private long[] registerWithFile() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated());
        Long taskId = taskRepo.findAll().get(0).getId();
        Long attId = attachmentRepo.findByTaskIdOrderByIdAsc(taskId).get(0).getId();
        return new long[]{taskId, attId};
    }

    @Test
    void 요청자_본인은_다운로드할_수_있다() throws Exception {
        long[] ids = registerWithFile();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/" + ids[0] + "/attachments/" + ids[1])
                        .with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes("내용".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 타인은_403_관리자는_200이다() throws Exception {
        long[] ids = registerWithFile();
        String url = "/api/tasks/" + ids[0] + "/attachments/" + ids[1];
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(url).with(userJwt("other"))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(url).with(adminJwt("admin"))).andExpect(status().isOk());
    }

    @Test
    void 디스크_파일이_유실되면_404다() throws Exception {
        long[] ids = registerWithFile();
        Files.delete(Path.of(attachmentDir, "task-" + ids[0] + "/1-요구사항.txt"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/" + ids[0] + "/attachments/" + ids[1])
                        .with(userJwt("user1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_task의_attId를_섞으면_404다() throws Exception {
        long[] ids = registerWithFile();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/" + (ids[0] + 999) + "/attachments/" + ids[1])
                        .with(adminJwt("admin")))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.TaskAttachmentApiIntegrationTest'`
Expected: 신규 4건 404 실패 (엔드포인트 미존재 — 404가 "미구현"과 "구현된 404"로 헷갈리지 않게, 본인 200 케이스가 실패하는지 확인)

- [ ] **Step 3: 구현**

`TaskController.java` — `logsStream` 아래에 추가. import 추가:

```java
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
```

```java
    /**
     * 첨부 다운로드 (스펙 2026-08-16 §5.3). ACL = getForView(요청자 본인 or ADMIN) —
     * logsStream과 동일 패턴. 한글 파일명은 RFC 5987 filename*으로.
     */
    @GetMapping("/{id}/attachments/{attId}")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id,
                                                       @PathVariable Long attId,
                                                       JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        taskService.getForView(id, userId, isAdmin); // 접근 권한 검증 (없으면 403/404 예외)
        TaskAttachment att = taskService.getAttachment(id, attId);
        Path file = taskService.resolveAttachmentPath(att);
        if (!Files.exists(file)) {
            throw new TaskException(HttpStatus.NOT_FOUND, "첨부 파일이 서버에 존재하지 않습니다");
        }
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(att.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(att.getSizeBytes())
                .body(new FileSystemResource(file));
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.TaskAttachmentApiIntegrationTest'`
Expected: PASS (전 케이스)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/TaskController.java \
        src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java
git commit -m "feat: 첨부 다운로드 엔드포인트 (요청자/관리자 ACL + 한글 파일명 스트리밍)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 인터뷰 claim 계약에 attachments 추가 (Java 측)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Modify: `src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java` (claim 테스트 추가)

**Interfaces:**
- Consumes: Task 1 `TaskAttachmentRepository`, Task 2 `AttachmentStorage.absolutePathOf(String)`.
- Produces (Task 8의 TS 타입이 1:1 미러 — **양측 동시 수정 잠금 계약**):
  - `record AttachmentRef(long id, String fileName, String absolutePath, String contentType, long sizeBytes)` — `contentType`만 null 가능
  - `InterviewClaimResponse.attachments: List<AttachmentRef>` — compact constructor가 `null → List.of()` 정규화
  - `of(InterviewSession s, List<InterviewTurn> turns, List<AttachmentRef> attachments)` — **기존 2-인자 of는 제거** (호출부 전수 수정: `grep -rn "InterviewClaimResponse.of(" src/`)

- [ ] **Step 1: 실패하는 테스트 작성**

`InterviewClaimResponseTest.java` — 기존 2개 테스트의 `of(...)` 호출을 3-인자로 바꾸고 신규 2개 추가:

```java
    @Test
    void of_carries_accumulated_session_totalCostUsd_so_the_worker_guard_is_cumulative() {
        InterviewSession s = session();
        ReflectionTestUtils.setField(s, "totalCostUsd", new BigDecimal("4.95"));

        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(4.95);
    }

    @Test
    void of_defaults_totalCostUsd_to_zero_for_a_fresh_session() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(0.0);
    }

    @Test
    void attachments가_null이어도_빈_리스트로_정규화된다_계약은_non_null() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), null);
        assertThat(r.attachments()).isNotNull().isEmpty();
    }

    @Test
    void attachments는_id_파일명_절대경로_타입_크기를_나른다() {
        var ref = new InterviewClaimResponse.AttachmentRef(
                3L, "요구사항.pdf", "/abs/task-7/1-요구사항.pdf", "application/pdf", 1234L);
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of(ref));
        assertThat(r.attachments()).containsExactly(ref);
        assertThat(r.attachments().get(0).absolutePath()).startsWith("/abs/");
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.dto.InterviewClaimResponseTest'`
Expected: 컴파일 실패 (3-인자 of/AttachmentRef 미존재)

- [ ] **Step 3: DTO 구현**

`InterviewClaimResponse.java` — record 컴포넌트 `double totalCostUsd` 뒤에 추가:

```java
        double totalCostUsd,
        /** 등록 시 업로드된 첨부(절대경로 — work_dir과 동일한 단일 호스트 전제). 항상 non-null. */
        List<AttachmentRef> attachments
```

compact constructor + 중첩 record + of 시그니처 변경 (record 본문):

```java
    /** 어느 경로로 생성돼도 non-null 계약 유지 (스펙 §5.4). */
    public InterviewClaimResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record Turn(int seq, String role, String kind, String content, Integer replyToSeq) {}

    /** contentType만 null 가능 — TS 타입도 string | null (스펙 §5.4). */
    public record AttachmentRef(long id, String fileName, String absolutePath,
                                String contentType, long sizeBytes) {}

    public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns,
                                            List<AttachmentRef> attachments) {
```

기존 of 본문 끝의 `mapped, s.getModel(), s.getEffort(), totalCostUsd);` →
`mapped, s.getModel(), s.getEffort(), totalCostUsd, attachments);`

- [ ] **Step 4: InterviewService.claim 구현**

`InterviewService.java` — ① import: `org.springframework.beans.factory.annotation.Value`는 불필요, 대신 필드/생성자에 추가:

```java
    private final TaskAttachmentRepository attachmentRepo;
    private final AttachmentStorage attachmentStorage;
```

(생성자 파라미터 끝에 `TaskAttachmentRepository attachmentRepo, AttachmentStorage attachmentStorage` + 대입. `grep -rn "new InterviewService(" src/test/`로 직접 생성 테스트 확인 — 있으면 인자 추가.)

② `claim(...)` 마지막 줄 교체:

```java
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(s.getId());
        return Optional.of(InterviewClaimResponse.of(s, turns, attachmentRefsFor(s)));
    }

    /** 세션 소유 task의 첨부 → 절대경로 ref (스펙 §5.4). task 없는 레거시 세션은 []. */
    private List<InterviewClaimResponse.AttachmentRef> attachmentRefsFor(InterviewSession s) {
        if (s.getTaskId() == null) return List.of();
        return attachmentRepo.findByTaskIdOrderByIdAsc(s.getTaskId()).stream()
                .map(a -> new InterviewClaimResponse.AttachmentRef(
                        a.getId(), a.getOriginalFilename(),
                        attachmentStorage.absolutePathOf(a.getStoredPath()),
                        a.getContentType(), a.getSizeBytes()))
                .toList();
    }
```

③ `grep -rn "InterviewClaimResponse.of(" src/` — claim 외 다른 호출부가 있으면 `List.of()` 전달로 컴파일 수정.

④ 통합 테스트 추가 — `TaskAttachmentApiIntegrationTest.java` (TaskService 주입 추가: `@Autowired private com.hamonsoft.netismaker.service.TaskService taskService;`):

```java
    @Test
    void 승인_후_워커_claim에_첨부_절대경로가_실린다() throws Exception {
        long[] ids = registerWithFile();
        taskService.approve(ids[0], "admin", null);   // AWAITING_APPROVAL → 인터뷰 세션 생성

        mvc.perform(post("/worker/interviews/claim")
                        .header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].absolutePath").value(
                        org.hamcrest.Matchers.endsWith("task-" + ids[0] + "/1-요구사항.txt")));
    }
```

- [ ] **Step 5: 테스트 통과 확인 + 전체 회귀**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: 전체 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java \
        src/main/java/com/hamonsoft/netismaker/service/InterviewService.java \
        src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java \
        src/test/java/com/hamonsoft/netismaker/controller/TaskAttachmentApiIntegrationTest.java
git commit -m "feat: 인터뷰 claim 계약에 attachments(절대경로) 추가 — Java 측

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Node 인터뷰 워커 — types + kickoff 프롬프트 첨부 섹션

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Modify: `netismaker-interview-service/test/fixtures/claims.ts`
- Test: `netismaker-interview-service/test/interviewRunner.test.ts` (테스트 4개 추가)

**Interfaces:**
- Consumes: Task 7의 Java `AttachmentRef` JSON (`{id, fileName, absolutePath, contentType, sizeBytes}`).
- Produces: `AttachmentRef` TS 인터페이스, `InterviewClaimResponse.attachments?: AttachmentRef[]` (optional — 구버전 백엔드는 필드 자체가 없다). 읽기는 **무조건 가드** (`Array.isArray`, 스펙 §7·§11).

- [ ] **Step 1: 실패하는 테스트 작성**

`test/fixtures/claims.ts` — `freshClaim`에 `attachments: [],` 추가(`totalCostUsd: 0,` 뒤) + 파일 끝에 신규 fixture:

```ts
export const freshClaimWithAttachments: InterviewClaimResponse = {
  ...freshClaim,
  attachments: [
    {
      id: 1,
      fileName: '요구사항.pdf',
      absolutePath: '/Users/micthebick/netis-maker/attachments/task-7/1-요구사항.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1234567,
    },
    {
      id: 2,
      fileName: '화면시안.png',
      absolutePath: '/Users/micthebick/netis-maker/attachments/task-7/2-화면시안.png',
      contentType: null,
      sizeBytes: 2048,
    },
  ],
};
```

`test/interviewRunner.test.ts` — import에 `freshClaimWithAttachments` 추가 후 테스트 4개 추가 (기존 `makeClient`/`questionStream`/`deps` 재사용, kickoff-prompt 테스트와 동일한 seenPrompt 패턴):

```ts
  it('fresh claim with attachments: kickoff prompt lists absolute paths and Read instruction', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaimWithAttachments);
    expect(seenPrompt).toContain('첨부 자료');
    expect(seenPrompt).toContain('/Users/micthebick/netis-maker/attachments/task-7/1-요구사항.pdf');
    expect(seenPrompt).toContain('Read');
    // contentType null은 리터럴 'null'로 렌더링되지 않는다 (스펙 §7)
    expect(seenPrompt).not.toContain('(null');
  });

  it('fresh claim with zero attachments: no attachment section', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(seenPrompt).not.toContain('첨부 자료');
  });

  it('legacy claim WITHOUT the attachments field does not crash (unconditional guard)', async () => {
    const legacy = { ...freshClaim } as Record<string, unknown>;
    delete legacy.attachments;
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(legacy as never);
    expect(client.fail).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalled();
  });

  it('resume claim: attachment section is NOT injected (session already has context)', async () => {
    const resumeWithAtts = { ...resumeClaim, attachments: freshClaimWithAttachments.attachments };
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(resumeWithAtts);
    expect(seenPrompt).not.toContain('첨부 자료');
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd netismaker-interview-service && npm test`
Expected: FAIL — fixture의 `attachments` 필드가 `InterviewClaimResponse` 타입에 없음(tsc/vitest 타입 에러) 또는 '첨부 자료' 미포함 단언 실패

- [ ] **Step 3: 구현**

`src/types.ts` — `InterviewTurn` 아래에 추가:

```ts
/** Java InterviewClaimResponse.AttachmentRef — 등록 시 업로드된 첨부 1건. */
export interface AttachmentRef {
  id: number;
  fileName: string;
  /** 공유 FS 절대경로 (workDir과 동일한 단일 호스트 전제) — 에이전트가 Read로 직접 읽는다. */
  absolutePath: string;
  contentType: string | null;
  sizeBytes: number;
}
```

`InterviewClaimResponse` 인터페이스의 `totalCostUsd: number;` 뒤에 추가:

```ts
  /**
   * 등록 시 업로드된 첨부. 신버전 Java는 항상 []-이상을 보내지만 구버전 백엔드는 필드
   * 자체가 없다 — 읽는 쪽은 무조건 Array.isArray 가드 (스펙 §7/§11, mcpsExtra 선례).
   */
  attachments?: AttachmentRef[];
```

`src/runner/interviewRunner.ts` — ① import 타입 추가: `import type { AttachmentRef, InterviewClaimResponse } from '../types.js';` ② `userTurn` 아래에 헬퍼 2개:

```ts
function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '';
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${bytes}B`;
}

/**
 * kickoff 첨부 섹션 (스펙 2026-08-16 §7). 구버전 백엔드 claim에는 attachments 필드가
 * 없으므로 무조건 Array.isArray 가드 (sessionOptions.toMcpServers 선례). 0건이면 빈 문자열.
 * contentType null은 표기 생략 — 리터럴 'null'을 렌더링하지 않는다.
 */
function attachmentSection(claim: InterviewClaimResponse): string {
  const atts: AttachmentRef[] = Array.isArray(claim.attachments) ? claim.attachments : [];
  if (atts.length === 0) return '';
  const lines = atts.map((a) => {
    const meta = [a.contentType, formatSize(a.sizeBytes)].filter(Boolean).join(', ');
    return `- ${a.absolutePath}${meta ? ` (${meta})` : ''}`;
  });
  return (
    '첨부 자료 (요청자가 등록 시 업로드한 파일):\n' +
    lines.join('\n') +
    '\n\n' +
    'brainstorming 시작 전에 이 파일들을 Read 도구로 읽고 요구사항 파악에 활용하세요. ' +
    '읽을 수 없는 포맷(docx/xlsx 등 오피스 문서)이거나 파일이 없으면 건너뛰고, ' +
    '필요한 내용은 사용자에게 질문으로 확인하세요.\n\n'
  );
}
```

③ `promptFor`의 fresh 분기 — `Request: ${claim.description}\n\n` 직후에 섹션 삽입:

```ts
      `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
        `Title: ${claim.title}\nRequest: ${claim.description}\n\n` +
        attachmentSection(claim) +
        'IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written ' +
```

(resume 분기·force-finish·splice 경로는 **무변경**.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd netismaker-interview-service && npm test`
Expected: 전체 PASS (기존 테스트 포함 — kickoff 마커 테스트가 섹션 삽입에도 여전히 통과해야 함)

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/types.ts \
        netismaker-interview-service/src/runner/interviewRunner.ts \
        netismaker-interview-service/test/fixtures/claims.ts \
        netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat: 인터뷰 kickoff 프롬프트에 첨부파일 섹션 주입 — Node 워커 측

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: 프론트 등록 다이얼로그 — 파일 첨부 + FormData 전송

**Files:**
- Modify: `frontend/pages/tasks/index.vue`
- Test: `frontend/test/tasks-form-attachments.spec.ts` (신규)

**Interfaces:**
- Consumes: Task 4의 multipart `POST /api/tasks` (`meta` JSON part + `files` parts). `useApi`는 FormData에 Content-Type을 강제하지 않으므로 무수정 사용 — **호출부에서 Content-Type을 명시하지 말 것** (스펙 §8.1).
- Produces: 등록 다이얼로그의 `draftFiles: Ref<File[]>`, `validateFiles(files: File[]): string | null`.

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/tasks-form-attachments.spec.ts` (기존 `tasks-form-repo-select.spec.ts`의 PageWrapper 패턴 재사용):

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QFile } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

async function openDialog() {
  useApiMock.mockResolvedValue([])
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
  await addBtn!.trigger('click')
  await flushPromises()
  return w
}

function fillDraft(w: ReturnType<typeof mount>) {
  const page = w.findComponent(TasksIndex)
  const vm = page.vm as unknown as {
    draft: { repoCatalogId: number | null; githubBranch: string; title: string; description: string }
  }
  vm.draft.repoCatalogId = 1
  vm.draft.githubBranch = 'main'
  vm.draft.title = '제목'
  vm.draft.description = '설명'
}

describe('tasks form — attachments', () => {
  it('files이 있으면 FormData(meta+files)로 전송한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const file = new File(['내용'], '요구사항.txt', { type: 'text/plain' })
    w.findComponent(QFile).vm.$emit('update:modelValue', [file])
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    const submitBtn = w
      .findAll('button')
      .filter((b) => b.text().includes('작업 등록'))
      .at(-1)
    await submitBtn!.trigger('click')
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')
    expect(call).toBeTruthy()
    const body = call![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.getAll('files')).toHaveLength(1)
    const metaBlob = body.get('meta') as Blob
    expect(JSON.parse(await metaBlob.text())).toMatchObject({ title: '제목' })
    // Content-Type을 강제로 명시하지 않는다 ($fetch가 boundary를 스스로 설정)
    expect(call![1].headers?.['Content-Type']).toBeUndefined()
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('files이 없으면 기존 JSON body로 전송한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    const submitBtn = w
      .findAll('button')
      .filter((b) => b.text().includes('작업 등록'))
      .at(-1)
    await submitBtn!.trigger('click')
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')
    expect(call![1].body).toMatchObject({ title: '제목', repoCatalogId: 1 })
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('한도 초과(11개)면 전송하지 않고 경고한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const files = Array.from({ length: 11 }, (_, i) => new File(['x'], `f${i}.txt`))
    w.findComponent(QFile).vm.$emit('update:modelValue', files)
    await flushPromises()

    useApiMock.mockClear()
    const submitBtn = w
      .findAll('button')
      .filter((b) => b.text().includes('작업 등록'))
      .at(-1)
    await submitBtn!.trigger('click')
    await flushPromises()

    expect(useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')).toBeUndefined()
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npm test -- tasks-form-attachments`
Expected: FAIL (`findComponent(QFile)` 미존재 — 다이얼로그에 q-file 없음)

- [ ] **Step 3: 구현**

`frontend/pages/tasks/index.vue` — ① script: `draft` 선언 아래에 추가:

```ts
// 첨부 (스펙 2026-08-16 §8.1) — 서버 한도와 동일 값의 사전 검증
const MAX_FILES = 10
const MAX_FILE_MB = 20
const MAX_TOTAL_MB = 50
const draftFiles = ref<File[]>([])

function validateFiles(files: File[]): string | null {
  if (files.length > MAX_FILES) return `첨부는 최대 ${MAX_FILES}개까지 가능합니다`
  const over = files.find((f) => f.size > MAX_FILE_MB * 1024 * 1024)
  if (over) return `파일당 ${MAX_FILE_MB}MB 이하만 첨부할 수 있습니다: ${over.name}`
  if (files.some((f) => f.size === 0)) return '빈 파일(0바이트)은 첨부할 수 없습니다'
  const total = files.reduce((s, f) => s + f.size, 0)
  if (total > MAX_TOTAL_MB * 1024 * 1024) return `첨부 합계는 ${MAX_TOTAL_MB}MB 이하여야 합니다`
  return null
}
```

② `openCreate()`에 리셋 추가: `draftFiles.value = []` (다른 리셋들 옆).

③ `submit()` 교체:

```ts
async function submit() {
  const meta = {
    repoCatalogId: draft.repoCatalogId,
    githubBranch: draft.githubBranch,
    title: draft.title,
    description: draft.description,
  }
  if (draftFiles.value.length > 0) {
    const err = validateFiles(draftFiles.value)
    if (err) {
      $q.notify({ type: 'warning', message: err })
      return
    }
  }
  submitting.value = true
  try {
    if (draftFiles.value.length > 0) {
      // Content-Type을 명시하지 않는다 — $fetch가 FormData boundary를 스스로 설정 (스펙 §8.1)
      const form = new FormData()
      form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
      for (const f of draftFiles.value) form.append('files', f, f.name)
      await useApi('/api/tasks', { method: 'POST', body: form })
    } else {
      await useApi('/api/tasks', { method: 'POST', body: meta })
    }
    $q.notify({ type: 'positive', message: '작업 등록 완료' })
    showCreate.value = false
    refresh()
  } catch (e: any) {
    const msg = e?.data?.message ?? '등록 실패'
    $q.notify({ type: 'negative', message: msg })
  } finally {
    submitting.value = false
  }
}
```

④ template: description `q-input` 아래에 추가:

```vue
          <q-file
            v-model="draftFiles"
            label="첨부파일 (선택 · 최대 10개, 파일당 20MB)"
            outlined
            dense
            multiple
            use-chips
            counter
            append
            data-test="attachment-input"
          >
            <template #prepend>
              <q-icon name="attach_file" />
            </template>
          </q-file>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npm test`
Expected: 전체 PASS (기존 스펙 포함)

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/tasks/index.vue frontend/test/tasks-form-attachments.spec.ts
git commit -m "feat(front): 작업 등록 다이얼로그 파일 첨부 + FormData 전송

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: 프론트 작업 상세 — 첨부 목록 + 다운로드

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`
- Test: `frontend/test/task-detail-attachments.spec.ts` (신규)

**Interfaces:**
- Consumes: Task 5의 상세 응답 `attachments` + Task 6의 다운로드 엔드포인트. 다운로드는 **반드시 `useApi<Blob>(url, { responseType: 'blob' })`** — 전역 `$fetch` 직접 호출은 Authorization 미첨부로 401 (스펙 §8.2). 파일명은 응답 헤더가 아니라 메타의 `fileName` 사용.
- Produces: `AttachmentMeta` 인터페이스, `formatSize(bytes: number): string`, `downloadAttachment(att: AttachmentMeta)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/task-detail-attachments.spec.ts`:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock } from './mocks/nuxt'

// useRoute는 Nuxt 자동 임포트 — test/setup.ts의 다른 auto-import처럼 전역 스텁으로 제공
// (vi.mock('vue-router')는 SFC가 명시 임포트할 때만 듣는다)
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }),
      })
  },
})

// q-breadcrumbs-el의 :to가 router-link를 요구 — 라우터 없이 마운트하므로 스텁
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' } } },
}

const taskFixture = {
  id: 42,
  githubRepo: 'acme/widgets',
  repoAlias: null,
  githubBranch: 'main',
  title: '제목',
  description: '설명',
  status: 'AWAITING_APPROVAL',
  statusLabel: '승인대기',
  requesterId: 'user1',
  retryCount: 0,
  maxRetry: 3,
  failureReason: null,
  mcpsExtra: [],
  envVars: [],
  interviewSessionId: null,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
  model: 'claude-opus-5',
  effort: 'high',
  designRequested: false,
  analysis: null,
  design: null,
  implementation: null,
  deployment: null,
  attachments: [
    {
      id: 7,
      fileName: '요구사항.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1536,
      createdAt: '2026-08-16T00:00:00Z',
    },
  ],
}

describe('task detail — attachments', () => {
  it('첨부 파일명과 크기를 렌더링한다', async () => {
    useApiMock.mockResolvedValue(taskFixture)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()

    expect(w.text()).toContain('첨부파일')
    expect(w.text()).toContain('요구사항.pdf')
    expect(w.text()).toContain('2KB')
    w.unmount()
  })

  it('클릭하면 useApi로 blob 다운로드를 호출한다', async () => {
    useApiMock.mockResolvedValue(taskFixture)
    const createObjectURL = vi.fn(() => 'blob:mock')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })

    const w = mount(PageWrapper, mountOpts)
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue(new Blob(['pdf']))
    const chip = w.findAll('.q-chip').find((c) => c.text().includes('요구사항.pdf'))
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/attachments/7', {
      responseType: 'blob',
    })
    expect(createObjectURL).toHaveBeenCalled()
    w.unmount()
  })

  it('첨부가_없으면_섹션을_렌더링하지_않는다', async () => {
    useApiMock.mockResolvedValue({ ...taskFixture, attachments: [] })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.text()).not.toContain('첨부파일')
    w.unmount()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npm test -- task-detail-attachments`
Expected: FAIL ('첨부파일' 미렌더)

- [ ] **Step 3: 구현**

`frontend/pages/tasks/[id].vue` — ① `TaskResponse` 인터페이스에 필드 추가 + 인터페이스 정의 (EnvVar 인터페이스 근처):

```ts
interface AttachmentMeta {
  id: number
  fileName: string
  contentType: string | null
  sizeBytes: number
  createdAt: string
}
```

`TaskResponse`의 `deployment: DeploymentView | null` 뒤에:

```ts
  attachments: AttachmentMeta[]
```

(`frontend/pages/tasks/index.vue`의 목록용 인터페이스는 **손대지 않는다** — 목록 응답은 항상 `[]`, 스펙 §5.2/§8.2.)

② script에 헬퍼 추가:

```ts
function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}

// 반드시 useApi 경유 — 전역 $fetch는 Authorization 미첨부로 401 (스펙 §8.2).
// 파일명은 응답 헤더가 아니라 메타 fileName 사용 ($fetch는 헤더를 안 돌려준다).
async function downloadAttachment(att: AttachmentMeta) {
  try {
    const blob = await useApi<Blob>(`/api/tasks/${taskId.value}/attachments/${att.id}`, {
      responseType: 'blob',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = att.fileName
    a.click()
    URL.revokeObjectURL(url)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '다운로드 실패' })
  }
}
```

③ template — 요청 상세 `q-card-section` 뒤, mcpsExtra 섹션 앞에 (mcpsExtra 조건부 섹션과 동일 패턴):

```vue
        <q-separator v-if="task.attachments && task.attachments.length" />
        <q-card-section v-if="task.attachments && task.attachments.length">
          <div class="text-caption q-mb-xs">첨부파일</div>
          <q-chip
            v-for="a in task.attachments"
            :key="a.id"
            clickable
            color="blue-grey-1"
            text-color="blue-grey-9"
            icon="attach_file"
            size="sm"
            dense
            :label="`${a.fileName} (${formatSize(a.sizeBytes)})`"
            class="q-mr-xs q-mb-xs"
            @click="downloadAttachment(a)"
          >
            <q-tooltip>클릭하여 다운로드</q-tooltip>
          </q-chip>
        </q-card-section>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npm test`
Expected: 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/tasks/[id].vue frontend/test/task-detail-attachments.spec.ts
git commit -m "feat(front): 작업 상세 첨부파일 목록 + 인증 blob 다운로드

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: 전체 게이트 + TODOS + 수동 스모크 체크리스트

**Files:**
- Modify: `TODOS.md`
- Test: 3개 스택 전체 테스트

**Interfaces:**
- Consumes: Task 1~10 전부.
- Produces: 머지 가능한 브랜치 + 수동 스모크 체크리스트(아래) 실행 결과 보고.

- [ ] **Step 1: TODOS.md에 정리 항목 추가**

기존 "레포 캐시 자동 정리 정책" 항목 아래에:

```markdown
- **첨부파일 정리 정책** (V1.1 후보): `~/netis-maker/attachments/task-{id}/`는 자동 삭제 없음
  (인터뷰 work_dir와 동일 정책). task soft-delete 시 또는 보존 기한 경과 시 디렉터리 삭제 검토.
```

- [ ] **Step 2: 3개 스택 전체 테스트**

```bash
RUN_TESTCONTAINERS=true ./gradlew test
cd netismaker-interview-service && npm test && cd ..
cd frontend && npm test && cd ..
```
Expected: 전부 PASS. 실패 시 해당 태스크로 돌아가 수정 (성공 주장 전에 실제 출력 확인 — verification-before-completion).

- [ ] **Step 3: 커밋**

```bash
git add TODOS.md
git commit -m "docs: 첨부파일 정리 정책 TODO + 첨부 기능 마무리

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 4: 수동 스모크 (풀스택 — mock이 못 잡는 교차 계약 검증, 스펙 §9)**

전제: 풀스택 기동(`./scripts/start-all.sh`, 공개 스택이면 `start-public.sh` 먼저 — 재기동 런북 준수). **백엔드/인터뷰 서비스/프론트 모두 새 코드로 재기동**해야 라이브 반영.

1. 등록 다이얼로그에서 **실제 PDF 1개 + PNG 1개** 첨부 → 등록 성공, 상세 페이지에 첨부 2개 표시
2. 첨부 칩 클릭 → 브라우저 다운로드로 원본과 동일한 파일 수신 (한글 파일명 보존)
3. 관리자 승인 → 인터뷰 시작 → **에이전트 첫 질문이 첨부 내용을 반영하는지** 확인 (예: PDF 속 고유 단어를 언급) — 이 항목이 이 기능의 핵심 검증
4. 25MB 텍스트 파일 업로드 시도 → 413 + 한국어 메시지 (MockMvc로 검증 불가한 실경로)
5. (공개 스택 사용 시) `app.micthebick.dev` 경유로 1~2 재확인 — Cloudflare/Nitro 프록시 multipart 통과 확인

---

## Self-Review 결과 (플랜 작성 후 점검)

- **스펙 커버리지**: §4→Task 1, §6(storage/설정/핸들러)→Task 2·3·4, §5.1→Task 4, §5.2→Task 5, §5.3→Task 6, §5.4→Task 7, §7→Task 8, §8.1→Task 9, §8.2→Task 10, §9(수동 스모크)·§2(TODOS)→Task 11. §11(배포)은 Task 11 스모크 전제에 반영. 갭 없음.
- **자리표시자**: 없음 (모든 코드 스텝에 실코드).
- **타입 일관성**: `AttachmentRef(id, fileName, absolutePath, contentType, sizeBytes)` Java(Task 7)=TS(Task 8) 1:1. `AttachmentView(id, fileName, contentType, sizeBytes, createdAt)` Java(Task 5)=FE `AttachmentMeta`(Task 10) 1:1. `relativePath/absolutePathOf/getAttachment/resolveAttachmentPath` 산출(2·3)과 소비(6·7) 시그니처 일치 확인.
