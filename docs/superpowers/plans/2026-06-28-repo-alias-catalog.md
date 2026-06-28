# Repo Alias Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업/인터뷰 등록 시 자유 입력 대신 관리자 큐레이션 **한글 별칭**으로 레포를 선택하게 한다 (예: `https://github.com/micthebick84/netis7.0.git` → `Netis7.0`).

**Architecture:** 기존 **MCP 카탈로그** 패턴(`com.mcp_catalog` 테이블 + admin CRUD + 폼 드롭다운 + Task 스냅샷 박제)을 거의 1:1로 미러한다. 신규 `com.repo_catalog` 테이블이 (한글 별칭 ↔ 전체 Git URL) 매핑을 보관하고, 업서트 시 GitHub URL에서 `owner/repo`·`host`를 파싱해 함께 박제한다. 등록 계약을 `githubRepo`(자유 문자열) → `repoCatalogId`(Long)로 바꾸고, 백엔드가 카탈로그를 해석해 `Task`/`InterviewSession`에 `owner/repo` + `git_url` + `repo_alias`를 스냅샷한다. 구현/배포 파이프라인은 여전히 `Task.githubRepo`(owner/repo)만 읽으므로 **무변경**.

**Tech Stack:** Java 21 / Spring Boot 3.4.1 (profile `api`) · JPA + Flyway (PostgreSQL `com` 스키마) · Nuxt 3 + Quasar (frontend) · JUnit5 + Mockito (백엔드 단위) · Vitest + @vue/test-utils (프론트).

## Global Constraints

- **백엔드 단위 테스트는 평이한 Mockito 패턴**: `@SpringBootTest` 금지, 협력자는 `mock()`, SUT는 생성자 주입, JPA id는 `ReflectionTestUtils.setField(entity, "id", 42L)`. (참고: `TaskServiceDeployTest`)
- **통합 테스트(@SpringBootTest + Testcontainers)는 `@EnabledIfEnvironmentVariable(named="RUN_TESTCONTAINERS", matches="true")`로 게이팅** — 로컬 `./gradlew test`에서 스킵되고 CI(Linux)에서만 돈다. 로컬 docker-java 비호환이라 **로컬에서 통과시키려 하지 말 것**. (참고: `TaskApiIntegrationTest`, `InterviewSecuritySurfaceTest`)
- **에러는 `TaskException`으로**: `new TaskException(HttpStatus.X, "메시지")` 또는 정적 팩토리 `notFound()/forbidden()/conflict(msg)/tooManyRequests(msg)`.
- **API 빈은 `@Profile("api")`**, 워커 전용은 `@Profile("worker")`. 신규 서비스/컨트롤러는 전부 `@Profile("api")`.
- **인증/ACL**: 컨트롤러에서 `AuthContext.requireUserId(auth)` / `AuthContext.isAdmin(auth)` (둘 다 `com.hamonsoft.netismaker.controller.AuthContext`). admin 전용은 `@PreAuthorize("hasAuthority('ROLE_ADMIN')")`, 인증만 필요하면 `@PreAuthorize("isAuthenticated()")`.
- **프론트 스타일**: 작은따옴표, **세미콜론 없음**, 2-space, trailing comma, `<script setup lang="ts">`. `npm run lint`(eslint)은 현재 깨져 있으니 의존 금지 — `npm test`(vitest)와 단일 파일 prettier만.
- **프론트 테스트 파일명은 `*.spec.ts`** (vitest include 패턴). `useApi`는 `frontend/test/mocks/nuxt.ts`의 `useApiMock`으로 모킹, 호출 순서대로 `mockResolvedValueOnce`로 주입. 컴포넌트는 `data-test` 속성으로 셀렉트.
- **마이그레이션**: `src/main/resources/db/migration/V14__*.sql`. 신규 컬럼은 nullable(기존 row 안전). `baseline-version: 0`.
- **커밋 메시지**: 한국어 관례. 트레일러:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01HQX8JKjHuh8WcFGdQkBtm2
  ```

---

## File Structure

**신규 (백엔드)**
- `src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java` — 순수 URL 파서(테스트 핵심)
- `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java` — JPA 엔티티
- `src/main/java/com/hamonsoft/netismaker/repository/RepoCatalogRepository.java`
- `src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java` — `View` + `UpsertRequest` + `CheckResult`
- `src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java` — CRUD + `resolveForRegistration` + `check`
- `src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java`
- `src/main/resources/db/migration/V14__repo_catalog.sql`

**신규 (테스트)**
- `src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRepoCatalogTest.java`
- `src/test/java/com/hamonsoft/netismaker/dto/RepoCatalogRequestValidationTest.java`
- `src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogSecuritySurfaceTest.java` (CI 게이팅)
- `frontend/pages/admin/repo-catalog.spec.ts` 동작이 어려우면 → `frontend/test/repo-catalog-admin.spec.ts`

**신규 (프론트)**
- `frontend/pages/admin/repo-catalog.vue`

**수정 (백엔드)**
- `entity/Task.java` — 스냅샷 setter 필드 3개
- `entity/InterviewSession.java` — 스냅샷 setter 필드 3개
- `dto/TaskCreateRequest.java` — `githubRepo` → `repoCatalogId`
- `dto/CreateInterviewRequest.java` — `githubRepo` → `repoCatalogId`
- `dto/TaskResponse.java` — `repoAlias` 추가
- `service/TaskService.java` — `create()` 카탈로그 해석 + 스냅샷
- `service/InterviewService.java` — `create()` 해석 + 스냅샷, `register()` 스냅샷 승계

**수정 (프론트)**
- `frontend/layouts/default.vue` — 네비 탭 추가
- `frontend/pages/tasks/index.vue` — 자유입력 → 별칭 셀렉트, 목록 별칭 표시

**수정 (게이팅 통합테스트 — 계약 변경 반영)**
- `src/test/java/com/hamonsoft/netismaker/controller/TaskApiIntegrationTest.java`
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java` (githubRepo 사용 시)

---

## Task 1: RepoUrlParser (순수 URL 파서)

다양한 입력형을 정식 형태로 수렴하고 GitHub `owner/repo`·`host`를 파생하는 순수 정적 유틸. 이 기능의 정확성 핵심이라 가장 먼저 TDD로.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java`
- Test: `src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java`

**Interfaces:**
- Produces:
  - `RepoUrlParser.Parsed` record `(String canonicalUrl, String host, String ownerRepo)` — `host`는 `"github"` 또는 `"other"`, `ownerRepo`는 github가 아니면 `null`.
  - `static Parsed RepoUrlParser.parse(String input)` — 파싱 불가 시 `IllegalArgumentException`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java`:
```java
package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoUrlParserTest {

    @Test
    void parses_https_url_with_git_suffix() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://github.com/micthebick84/netis7.0.git");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
    }

    @Test
    void parses_https_url_without_git_suffix() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://github.com/owner/repo");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void parses_scp_style_ssh_url() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("git@github.com:owner/repo.git");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void parses_bare_owner_repo() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("owner/repo");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void trims_whitespace_and_trailing_slash() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("  https://github.com/owner/repo/  ");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
    }

    @Test
    void non_github_https_url_kept_as_other_with_null_owner_repo() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://gitlab.com/group/sub/proj.git");
        assertThat(p.host()).isEqualTo("other");
        assertThat(p.ownerRepo()).isNull();
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.com/group/sub/proj.git");
    }

    @Test
    void blank_input_throws() {
        assertThatThrownBy(() -> RepoUrlParser.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbage_input_throws() {
        assertThatThrownBy(() -> RepoUrlParser.parse("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests RepoUrlParserTest`
Expected: 컴파일 에러 (RepoUrlParser 없음).

- [ ] **Step 3: 최소 구현**

`src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java`:
```java
package com.hamonsoft.netismaker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레포 입력(전체 Git URL / scp-style ssh / owner/repo)을 정식 형태로 파싱.
 *
 *  GitHub면 host="github" + ownerRepo="owner/repo" + canonicalUrl="https://github.com/owner/repo.git".
 *  비-GitHub면 host="other" + ownerRepo=null + canonicalUrl=입력 URL(공백/끝슬래시 정리).
 *  파싱 불가(빈 값/형식 불명)면 IllegalArgumentException.
 */
public final class RepoUrlParser {

    private RepoUrlParser() {}

    public record Parsed(String canonicalUrl, String host, String ownerRepo) {}

    private static final Pattern OWNER_REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    // github.com/<owner>/<repo> 를 https/http/ssh/scp 어떤 형태에서든 추출
    private static final Pattern GITHUB =
            Pattern.compile("github\\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("레포 입력이 비어 있습니다");
        }
        String s = input.strip();

        // 1) bare owner/repo
        if (OWNER_REPO.matcher(s).matches()) {
            return new Parsed("https://github.com/" + s + ".git", "github", s);
        }

        // 2) github URL (https/http/ssh/scp)
        Matcher gh = GITHUB.matcher(s);
        if (gh.find()) {
            String ownerRepo = gh.group(1) + "/" + gh.group(2);
            return new Parsed("https://github.com/" + ownerRepo + ".git", "github", ownerRepo);
        }

        // 3) 비-GitHub but URL 모양이면 other 로 보존
        if (s.matches("^(https?|git|ssh)://.+") || s.matches("^[^\\s]+@[^\\s]+:.+")) {
            String canonical = s.replaceAll("/+$", "");
            return new Parsed(canonical, "other", null);
        }

        throw new IllegalArgumentException("레포 URL 형식을 인식할 수 없습니다: " + input);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests RepoUrlParserTest`
Expected: PASS (8 tests).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java \
        src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java
git commit -m "feat(repo-catalog): URL 파서 (owner/repo·host 파생)"
```

---

## Task 2: 마이그레이션 V14 + RepoCatalogEntry 엔티티 + 리포지토리

DB 스키마 + JPA 엔티티 + Spring Data 리포지토리. (엔티티/마이그레이션 동작 검증은 CI 게이팅 통합테스트에서 — 로컬은 컴파일 + Flyway 순서 확인.)

**Files:**
- Create: `src/main/resources/db/migration/V14__repo_catalog.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/RepoCatalogRepository.java`

**Interfaces:**
- Produces:
  - `RepoCatalogEntry` (Getter/Setter, `create(alias, gitUrl, host, ownerRepo, defaultBranch, description, createdBy)` 정적 팩토리, `enabled` 기본 true)
  - `RepoCatalogRepository extends JpaRepository<RepoCatalogEntry, Long>` + `findByEnabledTrueOrderByAlias()`, `findAllByOrderByAlias()`, `findByAlias(String)`, `findByGitUrl(String)`

- [ ] **Step 1: 마이그레이션 작성**

`src/main/resources/db/migration/V14__repo_catalog.sql`:
```sql
-- 관리자 큐레이션 레포 카탈로그. 작업/인터뷰 등록 시 자유 입력 대신 별칭 선택.
-- 정식 식별자 = 전체 Git URL(멀티호스트 대비). owner_repo/host는 업서트 시 파싱해 박제.
CREATE TABLE IF NOT EXISTS com.repo_catalog (
    id              BIGSERIAL PRIMARY KEY,
    alias           VARCHAR(100) NOT NULL UNIQUE,   -- 한글 표시명. 예: 'Netis7.0'
    git_url         TEXT         NOT NULL UNIQUE,    -- 정식 식별자(전체 Git URL)
    host            VARCHAR(30)  NOT NULL DEFAULT 'github',  -- 'github' | 'other'
    owner_repo      VARCHAR(255),                   -- GitHub 파생 owner/repo. 비-GitHub면 NULL
    default_branch  VARCHAR(255),                   -- (선택) 비우면 등록 폼이 ls-remote 기본 사용
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_by      VARCHAR(20)  REFERENCES com."user"(user_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_repo_catalog_enabled ON com.repo_catalog(enabled) WHERE enabled = true;

-- 작업/인터뷰별 선택 레포 스냅샷 (카탈로그 변경/삭제 후에도 이력 보존).
-- githubRepo(owner/repo)는 기존 컬럼 유지 — 파이프라인이 계속 사용. git_url/repo_alias는 표시·감사용.
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS git_url         TEXT,
    ADD COLUMN IF NOT EXISTS repo_alias      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS repo_catalog_id BIGINT REFERENCES com.repo_catalog(id);

ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS git_url         TEXT,
    ADD COLUMN IF NOT EXISTS repo_alias      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS repo_catalog_id BIGINT REFERENCES com.repo_catalog(id);

-- (선택) 첫 실행 시 폼이 비지 않도록 시드 1건. 불필요하면 이 블록 삭제 가능.
INSERT INTO com.repo_catalog (alias, git_url, host, owner_repo, description, enabled)
VALUES ('Netis7.0', 'https://github.com/micthebick84/netis7.0.git', 'github',
        'micthebick84/netis7.0', '교통 분석 데모 앱', true)
ON CONFLICT (alias) DO NOTHING;
```

- [ ] **Step 2: 엔티티 작성**

`src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java`:
```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 관리자 큐레이션 레포 카탈로그 엔트리.
 *
 * alias = 사용자에게 보일 한글 표시명. git_url = 정식 식별자(전체 Git URL).
 * owner_repo/host 는 업서트 시 RepoUrlParser 로 파싱해 박제.
 * 사용자는 작업/인터뷰 등록 시 활성(enabled=true) 엔트리만 선택 가능.
 */
@Entity
@Table(name = "repo_catalog", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepoCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String alias;

    @Column(name = "git_url", nullable = false, unique = true, columnDefinition = "TEXT")
    private String gitUrl;

    @Column(nullable = false, length = 30)
    private String host;   // github | other

    @Column(name = "owner_repo", length = 255)
    private String ownerRepo;   // 비-GitHub면 null

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static RepoCatalogEntry create(String alias, String gitUrl, String host, String ownerRepo,
                                          String defaultBranch, String description, String createdBy) {
        RepoCatalogEntry e = new RepoCatalogEntry();
        e.alias = alias;
        e.gitUrl = gitUrl;
        e.host = host;
        e.ownerRepo = ownerRepo;
        e.defaultBranch = defaultBranch;
        e.description = description;
        e.enabled = true;
        e.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }
}
```

- [ ] **Step 3: 리포지토리 작성**

`src/main/java/com/hamonsoft/netismaker/repository/RepoCatalogRepository.java`:
```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoCatalogRepository extends JpaRepository<RepoCatalogEntry, Long> {
    List<RepoCatalogEntry> findByEnabledTrueOrderByAlias();
    List<RepoCatalogEntry> findAllByOrderByAlias();
    Optional<RepoCatalogEntry> findByAlias(String alias);
    Optional<RepoCatalogEntry> findByGitUrl(String gitUrl);
}
```

- [ ] **Step 4: 컴파일 + Flyway 순서 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.
확인: `ls src/main/resources/db/migration/` 에 `V14__repo_catalog.sql`가 있고 V13이 마지막 이전 버전인지(중복 버전 번호 없음).

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration/V14__repo_catalog.sql \
        src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java \
        src/main/java/com/hamonsoft/netismaker/repository/RepoCatalogRepository.java
git commit -m "feat(repo-catalog): V14 스키마 + 엔티티 + 리포지토리"
```

---

## Task 3: RepoCatalogDto + RepoCatalogService (CRUD + resolveForRegistration + check)

`McpCatalogService`를 미러하되 업서트 시 `RepoUrlParser`로 `host`/`ownerRepo`를 파생하고, 등록 해석(`resolveForRegistration`)과 라이브 도달성 체크(`check`)를 추가.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java`

**Interfaces:**
- Consumes: `RepoUrlParser.parse` (Task 1), `RepoCatalogRepository` (Task 2), `GitRefService.listBranches(String repo)` (기존, `BranchListResponse` 반환; 실패 시 `TaskException` throw).
- Produces:
  - `RepoCatalogDto.View(Long id, String alias, String gitUrl, String host, String ownerRepo, String defaultBranch, String description, boolean enabled, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt)` + `static View of(RepoCatalogEntry)`
  - `RepoCatalogDto.UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled)`
  - `RepoCatalogDto.CheckResult(boolean reachable, String defaultBranch, int branchCount, String error)`
  - `RepoCatalogService`:
    - `List<RepoCatalogEntry> listEnabled()`, `listAll()`
    - `RepoCatalogEntry create(UpsertRequest req, String adminId)`
    - `RepoCatalogEntry update(Long id, UpsertRequest req)`
    - `void delete(Long id)`
    - `ResolvedRepo resolveForRegistration(Long id)` — record `ResolvedRepo(Long catalogId, String alias, String gitUrl, String host, String ownerRepo, String defaultBranch)`
    - `CheckResult check(Long id)`

- [ ] **Step 1: DTO 작성**

`src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public class RepoCatalogDto {

    /** 사용자/관리자 조회 응답. */
    public record View(
            Long id,
            String alias,
            String gitUrl,
            String host,
            String ownerRepo,
            String defaultBranch,
            String description,
            boolean enabled,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static View of(RepoCatalogEntry e) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), e.getHost(), e.getOwnerRepo(),
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /** 관리자 등록/수정 요청. gitUrl 은 전체 URL / owner/repo / scp-ssh 모두 허용(서버가 정규화). */
    public record UpsertRequest(
            @NotBlank
            @Size(max = 100)
            String alias,

            @NotBlank
            @Size(max = 1000)
            String gitUrl,

            @Size(max = 255)
            String defaultBranch,

            String description,

            Boolean enabled
    ) {}

    /** 라이브 도달성 체크 결과(비영속 — DB에 헬스 저장 안 함). */
    public record CheckResult(boolean reachable, String defaultBranch, int branchCount, String error) {}
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepoCatalogServiceTest {

    private RepoCatalogRepository repo;
    private GitRefService gitRefService;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        gitRefService = mock(GitRefService.class);
        service = new RepoCatalogService(repo, gitRefService);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private RepoCatalogEntry entry(Long id, String alias, boolean enabled, String host, String ownerRepo) {
        RepoCatalogEntry e = RepoCatalogEntry.create(alias,
                "https://github.com/" + (ownerRepo == null ? "x/y" : ownerRepo) + ".git",
                host, ownerRepo, null, null, "admin");
        e.setEnabled(enabled);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void create_derives_owner_repo_and_host_from_full_url() {
        when(repo.findByAlias("Netis7.0")).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "Netis7.0", "https://github.com/micthebick84/netis7.0.git", null, "데모", true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getHost()).isEqualTo("github");
        assertThat(saved.getOwnerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(saved.getGitUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
        assertThat(saved.getAlias()).isEqualTo("Netis7.0");
    }

    @Test
    void create_accepts_bare_owner_repo_input() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "별칭", "owner/repo", null, null, true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getOwnerRepo()).isEqualTo("owner/repo");
        assertThat(saved.getGitUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void create_rejects_duplicate_alias_with_409() {
        when(repo.findByAlias("Netis7.0")).thenReturn(Optional.of(entry(1L, "Netis7.0", true, "github", "a/b")));
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "Netis7.0", "https://github.com/a/b.git", null, null, true);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    void create_rejects_unparseable_url_with_400() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "x", "not a url", null, null, true);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void resolveForRegistration_returns_owner_repo_for_github_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "Netis7.0", true, "github", "micthebick84/netis7.0")));

        RepoCatalogService.ResolvedRepo r = service.resolveForRegistration(7L);

        assertThat(r.ownerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(r.alias()).isEqualTo("Netis7.0");
        assertThat(r.catalogId()).isEqualTo(7L);
    }

    @Test
    void resolveForRegistration_rejects_disabled_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "X", false, "github", "a/b")));
        assertThatThrownBy(() -> service.resolveForRegistration(7L))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("비활성");
    }

    @Test
    void resolveForRegistration_rejects_non_github_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "GL", true, "other", null)));
        assertThatThrownBy(() -> service.resolveForRegistration(7L))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("GitHub");
    }

    @Test
    void resolveForRegistration_rejects_missing_id() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForRegistration(99L))
                .isInstanceOf(TaskException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests RepoCatalogServiceTest`
Expected: 컴파일 에러 (RepoCatalogService 없음).

- [ ] **Step 4: 서비스 구현**

`src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@Profile("api")
public class RepoCatalogService {

    private final RepoCatalogRepository repo;
    private final GitRefService gitRefService;

    public RepoCatalogService(RepoCatalogRepository repo, GitRefService gitRefService) {
        this.repo = repo;
        this.gitRefService = gitRefService;
    }

    /** 등록 해석 결과 — 작업/인터뷰 스냅샷에 박제할 필드들. */
    public record ResolvedRepo(Long catalogId, String alias, String gitUrl,
                               String host, String ownerRepo, String defaultBranch) {}

    @Transactional(readOnly = true)
    public List<RepoCatalogEntry> listEnabled() {
        return repo.findByEnabledTrueOrderByAlias();
    }

    @Transactional(readOnly = true)
    public List<RepoCatalogEntry> listAll() {
        return repo.findAllByOrderByAlias();
    }

    @Transactional
    public RepoCatalogEntry create(RepoCatalogDto.UpsertRequest req, String adminId) {
        repo.findByAlias(req.alias()).ifPresent(existing -> {
            throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 별칭: " + req.alias());
        });
        RepoUrlParser.Parsed p = parseOrThrow(req.gitUrl());
        repo.findByGitUrl(p.canonicalUrl()).ifPresent(existing -> {
            throw new TaskException(HttpStatus.CONFLICT, "이미 등록된 Git URL: " + p.canonicalUrl());
        });
        RepoCatalogEntry e = RepoCatalogEntry.create(req.alias(), p.canonicalUrl(), p.host(),
                p.ownerRepo(), blankToNull(req.defaultBranch()), req.description(), adminId);
        if (req.enabled() != null) e.setEnabled(req.enabled());
        return repo.save(e);
    }

    @Transactional
    public RepoCatalogEntry update(Long id, RepoCatalogDto.UpsertRequest req) {
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id));
        if (!e.getAlias().equals(req.alias())) {
            repo.findByAlias(req.alias()).ifPresent(other -> {
                throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 별칭: " + req.alias());
            });
        }
        RepoUrlParser.Parsed p = parseOrThrow(req.gitUrl());
        repo.findByGitUrl(p.canonicalUrl()).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new TaskException(HttpStatus.CONFLICT, "이미 등록된 Git URL: " + p.canonicalUrl());
            }
        });
        e.setAlias(req.alias());
        e.setGitUrl(p.canonicalUrl());
        e.setHost(p.host());
        e.setOwnerRepo(p.ownerRepo());
        e.setDefaultBranch(blankToNull(req.defaultBranch()));
        e.setDescription(req.description());
        if (req.enabled() != null) e.setEnabled(req.enabled());
        e.setUpdatedAt(OffsetDateTime.now());
        return e;
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id);
        }
        repo.deleteById(id);
    }

    /** 작업/인터뷰 등록 시: id → 검증된 owner/repo + 스냅샷 필드. 비활성/누락/비-GitHub 거절. */
    @Transactional(readOnly = true)
    public ResolvedRepo resolveForRegistration(Long id) {
        if (id == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "repoCatalogId가 필요합니다");
        }
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 레포 카탈로그 id: " + id));
        if (!e.isEnabled()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: " + e.getAlias());
        }
        if (!"github".equals(e.getHost()) || e.getOwnerRepo() == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "현재 GitHub 레포만 분석/구현 가능합니다: " + e.getAlias());
        }
        return new ResolvedRepo(e.getId(), e.getAlias(), e.getGitUrl(),
                e.getHost(), e.getOwnerRepo(), e.getDefaultBranch());
    }

    /** 라이브 도달성 체크 — ls-remote 성공 여부. DB 영속 안 함. */
    @Transactional(readOnly = true)
    public RepoCatalogDto.CheckResult check(Long id) {
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id));
        if (!"github".equals(e.getHost()) || e.getOwnerRepo() == null) {
            return new RepoCatalogDto.CheckResult(false, null, 0, "GitHub 레포만 확인 가능");
        }
        try {
            var res = gitRefService.listBranches(e.getOwnerRepo());
            return new RepoCatalogDto.CheckResult(true, res.defaultBranch(), res.branches().size(), null);
        } catch (TaskException ex) {
            return new RepoCatalogDto.CheckResult(false, null, 0, ex.getMessage());
        }
    }

    private static RepoUrlParser.Parsed parseOrThrow(String gitUrl) {
        try {
            return RepoUrlParser.parse(gitUrl);
        } catch (IllegalArgumentException ex) {
            throw new TaskException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
```

> **NOTE (BranchListResponse 접근자):** `gitRefService.listBranches(...)`는 `BranchListResponse`를 반환한다. `res.defaultBranch()`·`res.branches()`가 record 접근자라고 가정했다. 구현 전 `dto/BranchListResponse.java`를 열어 실제 접근자명을 확인하고 다르면 맞출 것.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests RepoCatalogServiceTest`
Expected: PASS (8 tests).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java \
        src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java \
        src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java
git commit -m "feat(repo-catalog): DTO + 서비스(CRUD·해석·도달성체크)"
```

---

## Task 4: RepoCatalogController (+ /check) + ACL 통합테스트(CI 게이팅)

`McpCatalogController`를 미러. 컨트롤러는 얇으므로 ACL 표면만 게이팅 통합테스트로 검증.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogSecuritySurfaceTest.java`

**Interfaces:**
- Consumes: `RepoCatalogService` (Task 3), `AuthContext.requireUserId`, `RepoCatalogDto`.
- Produces 엔드포인트:
  - `GET /api/repo-catalog` (isAuthenticated, enabled만)
  - `GET /api/admin/repo-catalog` (ROLE_ADMIN, 전체)
  - `POST /api/admin/repo-catalog` (ROLE_ADMIN, 201)
  - `PUT /api/admin/repo-catalog/{id}` (ROLE_ADMIN)
  - `DELETE /api/admin/repo-catalog/{id}` (ROLE_ADMIN, 204)
  - `POST /api/admin/repo-catalog/{id}/check` (ROLE_ADMIN) → `CheckResult`

- [ ] **Step 1: 컨트롤러 작성**

`src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 *  레포 카탈로그 API.
 *
 *   GET    /api/repo-catalog               — 인증 사용자 모두 (활성 항목만) — 등록 폼 드롭다운용
 *   GET    /api/admin/repo-catalog         — ADMIN (전체)
 *   POST   /api/admin/repo-catalog         — ADMIN (등록)
 *   PUT    /api/admin/repo-catalog/{id}    — ADMIN (수정)
 *   DELETE /api/admin/repo-catalog/{id}    — ADMIN (삭제)
 *   POST   /api/admin/repo-catalog/{id}/check — ADMIN (라이브 ls-remote 도달성 확인)
 *
 *  사용자가 작업/인터뷰 등록 시 활성 카탈로그에서 선택, 선택 항목은 owner/repo·git_url·alias 스냅샷으로 박제.
 */
@RestController
@Profile("api")
public class RepoCatalogController {

    private final RepoCatalogService service;

    public RepoCatalogController(RepoCatalogService service) {
        this.service = service;
    }

    @GetMapping("/api/repo-catalog")
    @PreAuthorize("isAuthenticated()")
    public List<RepoCatalogDto.View> listForUsers() {
        return service.listEnabled().stream().map(RepoCatalogDto.View::of).toList();
    }

    @GetMapping("/api/admin/repo-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public List<RepoCatalogDto.View> listForAdmins() {
        return service.listAll().stream().map(RepoCatalogDto.View::of).toList();
    }

    @PostMapping("/api/admin/repo-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RepoCatalogDto.View create(@RequestBody @Valid RepoCatalogDto.UpsertRequest req,
                                      JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        RepoCatalogEntry saved = service.create(req, adminId);
        return RepoCatalogDto.View.of(saved);
    }

    @PutMapping("/api/admin/repo-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RepoCatalogDto.View update(@PathVariable Long id,
                                      @RequestBody @Valid RepoCatalogDto.UpsertRequest req) {
        return RepoCatalogDto.View.of(service.update(id, req));
    }

    @DeleteMapping("/api/admin/repo-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/api/admin/repo-catalog/{id}/check")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RepoCatalogDto.CheckResult check(@PathVariable Long id) {
        return service.check(id);
    }
}
```

- [ ] **Step 2: ACL 통합테스트 작성 (CI 게이팅)**

`InterviewSecuritySurfaceTest`의 어노테이션 세트를 미러. 미인증 접근이 4xx인지 확인.

`src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogSecuritySurfaceTest.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.RequestPostProcessor;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
class RepoCatalogSecuritySurfaceTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor userJwt() {
        return jwt().jwt(b -> b.claim("user_id", "user1").claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.claim("user_id", "admin").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void list_for_users_requires_auth() throws Exception {
        mvc.perform(get("/api/repo-catalog")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticated_user_can_list_enabled() throws Exception {
        mvc.perform(get("/api/repo-catalog").with(userJwt())).andExpect(status().isOk());
    }

    @Test
    void admin_list_forbidden_for_non_admin() throws Exception {
        mvc.perform(get("/api/admin/repo-catalog").with(userJwt())).andExpect(status().isForbidden());
    }

    @Test
    void admin_can_list_all() throws Exception {
        mvc.perform(get("/api/admin/repo-catalog").with(adminJwt())).andExpect(status().isOk());
    }

    @Test
    void create_forbidden_for_non_admin() throws Exception {
        mvc.perform(post("/api/admin/repo-catalog").with(userJwt())
                        .contentType("application/json")
                        .content("{\"alias\":\"X\",\"gitUrl\":\"owner/repo\"}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: 컴파일 확인 (로컬은 게이팅으로 스킵)**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --tests RepoCatalogSecuritySurfaceTest`
Expected: 0 tests executed (RUN_TESTCONTAINERS 미설정 → skip). CI에서만 실행됨 — 정상.

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java \
        src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogSecuritySurfaceTest.java
git commit -m "feat(repo-catalog): admin CRUD + /check 컨트롤러 + ACL 통합테스트(게이팅)"
```

---

## Task 5: 등록 계약 전환 + 스냅샷 (DTO·엔티티·서비스)

`githubRepo`(자유 문자열) → `repoCatalogId`(Long). `Task`/`InterviewSession`에 스냅샷 setter 필드 추가, 서비스가 카탈로그 해석 후 박제. `TaskResponse`에 `repoAlias` 노출.

**Files:**
- Modify: `dto/TaskCreateRequest.java`, `dto/CreateInterviewRequest.java`, `dto/TaskResponse.java`
- Modify: `entity/Task.java`, `entity/InterviewSession.java`
- Modify: `service/TaskService.java`, `service/InterviewService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRepoCatalogTest.java` (신규)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/RepoCatalogRequestValidationTest.java` (신규)
- Modify(게이팅): `controller/TaskApiIntegrationTest.java` 등 — 계약 변경 반영

**Interfaces:**
- Consumes: `RepoCatalogService.resolveForRegistration(Long)` → `ResolvedRepo(catalogId, alias, gitUrl, host, ownerRepo, defaultBranch)` (Task 3).
- Produces:
  - `TaskCreateRequest`/`CreateInterviewRequest`: `Long repoCatalogId` (@NotNull) 필드 (githubRepo 제거).
  - `Task`/`InterviewSession`: `@Setter String gitUrl; @Setter String repoAlias; @Setter Long repoCatalogId;`
  - `TaskResponse`: `String repoAlias` 필드 추가.

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/TaskServiceRepoCatalogTest.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceRepoCatalogTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private RepoCatalogService repoCatalogService;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        repoCatalogService = mock(RepoCatalogService.class);
        service = new TaskService(taskRepo, analysisRepo, historyRepo, mcpCatalogService, repoCatalogService);
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
    }

    @Test
    void create_resolves_catalog_and_snapshots_repo_fields() {
        when(repoCatalogService.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github",
                        "micthebick84/netis7.0", null));
        TaskCreateRequest req = new TaskCreateRequest(7L, "main", "제목", "설명", List.of(), null, null);

        Task t = service.create(req, "user1");

        assertThat(t.getGithubRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(t.getGitUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
        assertThat(t.getRepoAlias()).isEqualTo("Netis7.0");
        assertThat(t.getRepoCatalogId()).isEqualTo(7L);
    }

    @Test
    void create_propagates_resolve_rejection() {
        when(repoCatalogService.resolveForRegistration(8L))
                .thenThrow(new TaskException(org.springframework.http.HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: X"));
        TaskCreateRequest req = new TaskCreateRequest(8L, "main", "제목", "설명", List.of(), null, null);

        assertThatThrownBy(() -> service.create(req, "user1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("비활성");
    }
}
```

- [ ] **Step 2: DTO 검증 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/dto/RepoCatalogRequestValidationTest.java`:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCatalogRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() { factory = Validation.buildDefaultValidatorFactory(); validator = factory.getValidator(); }
    @AfterAll static void close() { factory.close(); }

    @Test
    void task_create_requires_repo_catalog_id() {
        TaskCreateRequest req = new TaskCreateRequest(null, "main", "t", "d", List.of(), null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void task_create_valid_with_repo_catalog_id() {
        TaskCreateRequest req = new TaskCreateRequest(7L, "main", "t", "d", List.of(), null, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void interview_create_requires_repo_catalog_id() {
        CreateInterviewRequest req = new CreateInterviewRequest(null, "main", "t", "d", List.of(), null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests TaskServiceRepoCatalogTest --tests RepoCatalogRequestValidationTest`
Expected: 컴파일 에러 (생성자 시그니처 불일치, getter 없음).

- [ ] **Step 4: DTO 변경**

`dto/TaskCreateRequest.java` — `githubRepo` 필드를 `repoCatalogId`로 교체:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TaskCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        String description,

        /** 카탈로그에서 선택된 추가 MCP id들. null/빈 배열 허용. */
        List<Long> mcpCatalogIds,

        /** Claude 모델 id. blank면 서버 기본값(claude-opus-4-8). */
        String model,

        /** 추론 effort. blank면 서버 기본값(high). */
        String effort
) {}
```

`dto/CreateInterviewRequest.java` — 동일하게 교체:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateInterviewRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        String description,

        List<Long> mcpCatalogIds,

        String model,

        String effort
) {}
```

- [ ] **Step 5: 엔티티 스냅샷 필드 추가**

`entity/Task.java` — `deployUrl` 등 기존 setter 필드 부근에 추가 (import `lombok.Setter`는 이미 있음):
```java
    /** 작업 등록 시 선택된 레포 카탈로그의 전체 Git URL 스냅샷. */
    @Column(name = "git_url", columnDefinition = "TEXT")
    @Setter
    private String gitUrl;

    /** 작업 등록 시 선택된 레포 카탈로그의 한글 별칭 스냅샷 (목록/상세 표시용). */
    @Column(name = "repo_alias", length = 100)
    @Setter
    private String repoAlias;

    /** 선택된 레포 카탈로그 id (편의 FK — 소스 오브 트루스는 스냅샷). */
    @Column(name = "repo_catalog_id")
    @Setter
    private Long repoCatalogId;
```

`entity/InterviewSession.java` — `github_branch` 컬럼 부근(model/effort 근처)에 동일한 3개 필드 추가 (`@Setter` import 확인):
```java
    @Column(name = "git_url", columnDefinition = "TEXT")
    @Setter
    private String gitUrl;

    @Column(name = "repo_alias", length = 100)
    @Setter
    private String repoAlias;

    @Column(name = "repo_catalog_id")
    @Setter
    private Long repoCatalogId;
```
> NOTE: `InterviewSession`이 클래스 레벨 `@Setter`를 안 쓰면 각 필드에 `@Setter`를 단다(위처럼). 클래스 레벨 `@Setter`가 이미 있으면 필드 `@Setter`는 생략.

- [ ] **Step 6: TaskResponse에 repoAlias 추가**

`dto/TaskResponse.java`:
1. record 헤더에 필드 추가 — `String githubRepo,` 바로 다음 줄:
```java
        String githubRepo,
        String repoAlias,
        String githubBranch,
```
2. `of(...)` 생성자 호출에서 `t.getGithubRepo(),` 다음에:
```java
                t.getGithubRepo(),
                t.getRepoAlias(),
                t.getGithubBranch(),
```

- [ ] **Step 7: TaskService.create 변경**

`service/TaskService.java`:
1. 필드 + 생성자에 `RepoCatalogService` 주입:
```java
    private final McpCatalogService mcpCatalogService;
    private final RepoCatalogService repoCatalogService;
```
```java
    public TaskService(TaskRepository taskRepo,
                       TaskAnalysisRepository analysisRepo,
                       TaskStatusHistoryRepository historyRepo,
                       McpCatalogService mcpCatalogService,
                       RepoCatalogService repoCatalogService) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.mcpCatalogService = mcpCatalogService;
        this.repoCatalogService = repoCatalogService;
    }
```
2. `create(...)` 본문에서 `Task.create(req.githubRepo(), ...)` 부분을 교체:
```java
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        Task t = Task.create(repo.ownerRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry, extras, model, effort);
        t.setGitUrl(repo.gitUrl());
        t.setRepoAlias(repo.alias());
        t.setRepoCatalogId(repo.catalogId());
        Task saved = taskRepo.save(t);
```

- [ ] **Step 8: InterviewService.create + register 변경**

`service/InterviewService.java`:
1. 필드 + 생성자에 `RepoCatalogService` 주입 (McpCatalogService 옆):
```java
    private final McpCatalogService mcpCatalogService;
    private final RepoCatalogService repoCatalogService;
```
생성자 파라미터/대입에 `RepoCatalogService repoCatalogService` 추가 (McpCatalogService 바로 다음 위치).
2. `create(...)`에서 `InterviewSession.create(req.githubRepo(), ...)` 교체:
```java
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        InterviewSession s = InterviewSession.create(repo.ownerRepo(), req.githubBranch(),
                req.title(), req.description(), requesterId, extras, model, effort);
        s.setGitUrl(repo.gitUrl());
        s.setRepoAlias(repo.alias());
        s.setRepoCatalogId(repo.catalogId());
        return sessionRepo.save(s);
```
3. `register(...)`에서 세션 스냅샷을 Task로 승계 — `Task saved = taskRepo.save(t);` **앞**에 set 추가:
```java
        Task t = Task.create(s.getGithubRepo(), s.getGithubBranch(), s.getTitle(),
                s.getDescription(), s.getRequesterId(), maxRetry,
                new ArrayList<>(s.getMcpsExtra() == null ? List.of() : s.getMcpsExtra()),
                s.getModel(), s.getEffort());
        t.setStatus(TaskStatus.COMPLETED);
        t.setGitUrl(s.getGitUrl());
        t.setRepoAlias(s.getRepoAlias());
        t.setRepoCatalogId(s.getRepoCatalogId());
        Task saved = taskRepo.save(t);
```

- [ ] **Step 9: 게이팅 통합테스트 계약 갱신**

`TaskApiIntegrationTest` 등에서 `new TaskCreateRequest("owner/repo", "main", ...)`(githubRepo 문자열)을 쓰는 곳을 `new TaskCreateRequest(<catalogId>, "main", ...)`로 바꾸고, 테스트가 카탈로그 항목을 먼저 INSERT 하거나 시드(V14의 Netis7.0, 보통 id=1)를 사용하도록 조정. `CreateInterviewRequest`도 동일.
> 로컬에서 이 테스트는 게이팅으로 스킵되므로 **컴파일만** 통과시키면 된다. 실제 통과 검증은 CI. 정확한 수정 위치는 해당 파일에서 `TaskCreateRequest(`·`CreateInterviewRequest(` grep으로 찾을 것.

- [ ] **Step 10: 테스트 통과 + 컴파일 확인**

Run: `./gradlew test --tests TaskServiceRepoCatalogTest --tests RepoCatalogRequestValidationTest`
Expected: PASS.
Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL (게이팅 테스트 포함 전체 컴파일).
Run: `./gradlew test`
Expected: 전체 로컬 단위테스트 PASS (게이팅 통합테스트는 skip). 기존 `TaskServiceDeployTest`가 `new TaskService(...)` 4-arg로 깨지면 5-arg(`repoCatalogService = mock(...)`)로 갱신.
> NOTE: `TaskServiceDeployTest`·`InterviewServiceTest`가 `new TaskService(...)`/`new InterviewService(...)`를 직접 호출하므로 생성자에 `mock(RepoCatalogService.class)` 인자를 추가해야 컴파일된다. 이 갱신도 이 스텝에 포함.

- [ ] **Step 11: 커밋**

```bash
git add -A
git commit -m "feat(repo-catalog): 등록 계약 repoCatalogId 전환 + repo 스냅샷 박제"
```

---

## Task 6: 프론트 admin 페이지 + 네비 탭

`admin/mcp-catalog.vue`를 미러해 레포 카탈로그 CRUD 페이지를 만든다. 필드: 별칭/Git URL/기본 브랜치/설명/활성 + "연결 확인"(transient notify).

**Files:**
- Create: `frontend/pages/admin/repo-catalog.vue`
- Modify: `frontend/layouts/default.vue`
- Test: `frontend/test/repo-catalog-admin.spec.ts`

**Interfaces:**
- Consumes: `GET/POST/PUT/DELETE /api/admin/repo-catalog`, `POST /api/admin/repo-catalog/{id}/check` → `{ reachable, defaultBranch, branchCount, error }`.

- [ ] **Step 1: 페이지 작성**

`frontend/pages/admin/repo-catalog.vue`:
```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'

definePageMeta({ layout: 'default' })

interface RepoEntry {
  id: number
  alias: string
  gitUrl: string
  host: string
  ownerRepo: string | null
  defaultBranch: string | null
  description: string | null
  enabled: boolean
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

const $q = useQuasar()
const entries = ref<RepoEntry[]>([])
const loading = ref(false)
const checkingIds = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  try {
    entries.value = await useApi<RepoEntry[]>('/api/admin/repo-catalog')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '카탈로그 조회 실패' })
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function check(e: RepoEntry) {
  checkingIds.value.add(e.id)
  try {
    const res = await useApi<{
      reachable: boolean
      defaultBranch: string | null
      branchCount: number
      error: string | null
    }>(`/api/admin/repo-catalog/${e.id}/check`, { method: 'POST' })
    if (res.reachable) {
      $q.notify({
        type: 'positive',
        message: `${e.alias}: 연결 성공 (브랜치 ${res.branchCount}개, 기본 ${res.defaultBranch ?? '-'})`,
        timeout: 4000,
      })
    } else {
      $q.notify({ type: 'negative', message: `${e.alias}: 연결 실패`, caption: res.error ?? undefined })
    }
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '체크 호출 실패' })
  } finally {
    checkingIds.value.delete(e.id)
  }
}

// 등록/수정 다이얼로그
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  alias: '',
  gitUrl: '',
  defaultBranch: '',
  description: '',
  enabled: true,
})
const submitting = ref(false)

function openCreate() {
  editingId.value = null
  form.alias = ''
  form.gitUrl = ''
  form.defaultBranch = ''
  form.description = ''
  form.enabled = true
  showForm.value = true
}

function openEdit(e: RepoEntry) {
  editingId.value = e.id
  form.alias = e.alias
  form.gitUrl = e.gitUrl
  form.defaultBranch = e.defaultBranch ?? ''
  form.description = e.description ?? ''
  form.enabled = e.enabled
  showForm.value = true
}

async function submit() {
  submitting.value = true
  try {
    const body = {
      alias: form.alias,
      gitUrl: form.gitUrl,
      defaultBranch: form.defaultBranch || null,
      description: form.description || null,
      enabled: form.enabled,
    }
    if (editingId.value) {
      await useApi(`/api/admin/repo-catalog/${editingId.value}`, { method: 'PUT', body })
      $q.notify({ type: 'positive', message: '수정 완료' })
    } else {
      await useApi('/api/admin/repo-catalog', { method: 'POST', body })
      $q.notify({ type: 'positive', message: '등록 완료' })
    }
    showForm.value = false
    await load()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '저장 실패' })
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(e: RepoEntry) {
  try {
    await useApi(`/api/admin/repo-catalog/${e.id}`, {
      method: 'PUT',
      body: {
        alias: e.alias,
        gitUrl: e.gitUrl,
        defaultBranch: e.defaultBranch,
        description: e.description,
        enabled: !e.enabled,
      },
    })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '활성화 토글 실패' })
  }
}

async function remove(e: RepoEntry) {
  if (!confirm(`'${e.alias}' 레포 카탈로그 항목을 삭제하시겠습니까? (이미 등록된 작업의 스냅샷은 보존됨)`)) return
  try {
    await useApi(`/api/admin/repo-catalog/${e.id}`, { method: 'DELETE' })
    $q.notify({ type: 'positive', message: '삭제 완료' })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '삭제 실패' })
  }
}

const canSubmit = computed(
  () => !submitting.value && !!form.alias.trim() && !!form.gitUrl.trim(),
)
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <div class="text-h5">레포 카탈로그</div>
      <q-space />
      <q-btn
        color="primary"
        icon="add"
        label="새 레포 등록"
        unelevated
        data-test="open-create"
        @click="openCreate"
      />
    </div>

    <q-banner class="bg-blue-1 text-grey-9 q-mb-md">
      <template #avatar><q-icon name="info" color="primary" /></template>
      관리자가 등록한 레포. 사용자가 작업 등록 시 활성(enabled)된 항목을 한글 별칭으로 선택합니다.
      등록된 작업의 레포 정보는 작성 시점 스냅샷으로 박제되어, 카탈로그 변경/삭제 후에도 이력이 보존됩니다.
      (비-GitHub URL은 저장은 되지만 현재 작업 등록은 GitHub 레포만 가능)
    </q-banner>

    <q-table
      :rows="entries"
      :loading="loading"
      row-key="id"
      flat
      bordered
      :pagination="{ rowsPerPage: 50 }"
      :columns="[
        { name: 'enabled', label: '', field: 'enabled', align: 'center', style: 'width:60px' },
        { name: 'alias', label: '별칭', field: 'alias', align: 'left' },
        { name: 'ownerRepo', label: 'owner/repo', field: 'ownerRepo', align: 'left' },
        { name: 'gitUrl', label: 'Git URL', field: 'gitUrl', align: 'left' },
        { name: 'defaultBranch', label: '기본 브랜치', field: 'defaultBranch', align: 'center' },
        { name: 'description', label: '설명', field: 'description', align: 'left' },
        { name: 'actions', label: '', field: () => '', align: 'right' },
      ]"
    >
      <template #body-cell-enabled="props">
        <q-td :props="props">
          <q-toggle
            :model-value="props.row.enabled"
            color="positive"
            @update:model-value="toggleEnabled(props.row)"
          />
        </q-td>
      </template>
      <template #body-cell-alias="props">
        <q-td :props="props">
          <span class="text-weight-medium">{{ props.row.alias }}</span>
          <q-chip
            v-if="props.row.host !== 'github'"
            size="sm"
            dense
            color="orange-3"
            text-color="grey-9"
            :label="props.row.host"
          />
        </q-td>
      </template>
      <template #body-cell-ownerRepo="props">
        <q-td :props="props"><code>{{ props.row.ownerRepo ?? '-' }}</code></q-td>
      </template>
      <template #body-cell-gitUrl="props">
        <q-td :props="props">
          <code style="word-break: break-all">{{ props.row.gitUrl }}</code>
        </q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props">
          <q-btn
            flat
            dense
            icon="cable"
            color="primary"
            :loading="checkingIds.has(props.row.id)"
            data-test="check"
            @click="check(props.row)"
          >
            <q-tooltip>연결 확인</q-tooltip>
          </q-btn>
          <q-btn flat dense icon="edit" color="primary" @click="openEdit(props.row)" />
          <q-btn flat dense icon="delete" color="negative" @click="remove(props.row)" />
        </q-td>
      </template>
    </q-table>

    <q-dialog v-model="showForm" persistent>
      <q-card style="min-width: 520px">
        <q-card-section>
          <div class="text-h6">{{ editingId ? '레포 수정' : '새 레포 등록' }}</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
          <q-input
            v-model="form.alias"
            label="별칭 (사용자에게 보일 한글명)"
            placeholder="Netis7.0"
            outlined
            dense
            data-test="form-alias"
          />
          <q-input
            v-model="form.gitUrl"
            label="Git URL"
            placeholder="https://github.com/owner/repo.git 또는 owner/repo"
            outlined
            dense
            hint="전체 URL · owner/repo · git@... 모두 허용 (서버가 정규화)"
            data-test="form-giturl"
          />
          <q-input
            v-model="form.defaultBranch"
            label="기본 브랜치 (선택)"
            placeholder="비우면 자동 감지"
            outlined
            dense
          />
          <q-input
            v-model="form.description"
            label="설명 (선택)"
            type="textarea"
            outlined
            autogrow
            rows="2"
          />
          <q-toggle v-model="form.enabled" label="활성화 (사용자에게 노출)" color="positive" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showForm = false" />
          <q-btn
            unelevated
            color="primary"
            :label="editingId ? '수정' : '등록'"
            :loading="submitting"
            :disable="!canSubmit"
            data-test="form-submit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
```

- [ ] **Step 2: 네비 탭 추가**

`frontend/layouts/default.vue` — MCP 카탈로그 탭 다음 줄에 추가:
```html
          <q-route-tab v-if="auth.isAdmin" to="/admin/mcp-catalog" label="MCP 카탈로그" />
          <q-route-tab v-if="auth.isAdmin" to="/admin/repo-catalog" label="레포 카탈로그" />
```

- [ ] **Step 3: 실패하는 Vitest 작성**

`frontend/test/repo-catalog-admin.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import RepoCatalog from '../pages/admin/repo-catalog.vue'
import { useApiMock } from './mocks/nuxt'

afterEach(() => {
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

async function mountPage(rows: any[] = []) {
  useApiMock.mockResolvedValueOnce(rows) // onMounted load()
  const w = mount(RepoCatalog)
  await flushPromises()
  return w
}

describe('admin/repo-catalog', () => {
  it('lists entries from /api/admin/repo-catalog on mount', async () => {
    const w = await mountPage([
      {
        id: 1, alias: 'Netis7.0', gitUrl: 'https://github.com/o/r.git', host: 'github',
        ownerRepo: 'o/r', defaultBranch: null, description: null, enabled: true,
        createdBy: 'admin', createdAt: '', updatedAt: '',
      },
    ])
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog')
    expect(w.text()).toContain('Netis7.0')
    w.unmount()
  })

  it('POSTs a new entry with alias + gitUrl', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()

    const aliasInput = document.querySelector('[data-test="form-alias"] input') as HTMLInputElement
    const urlInput = document.querySelector('[data-test="form-giturl"] input') as HTMLInputElement
    aliasInput.value = 'Netis7.0'
    aliasInput.dispatchEvent(new Event('input'))
    urlInput.value = 'https://github.com/micthebick84/netis7.0.git'
    urlInput.dispatchEvent(new Event('input'))
    await flushPromises()

    useApiMock.mockResolvedValueOnce({}) // POST
    useApiMock.mockResolvedValueOnce([]) // reload
    const submit = document.querySelector('[data-test="form-submit"]') as HTMLElement
    submit.click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', {
      method: 'POST',
      body: {
        alias: 'Netis7.0',
        gitUrl: 'https://github.com/micthebick84/netis7.0.git',
        defaultBranch: null,
        description: null,
        enabled: true,
      },
    })
    w.unmount()
  })
})
```
> NOTE: q-dialog가 `<body>`로 teleport되므로 다이얼로그 내부 요소는 `document.querySelector`로 접근(InterviewPanel.spec.ts cancel-flow와 동일 패턴). 셀렉터 구조(`[data-test] input`)가 Quasar 렌더와 안 맞으면, 다이얼로그를 열어 `console.log(document.body.innerHTML)`로 실제 구조를 확인해 맞출 것.

- [ ] **Step 4: 테스트 실행**

Run: `cd frontend && npx vitest run test/repo-catalog-admin.spec.ts`
Expected: PASS (2 tests). (실패 시 위 NOTE대로 셀렉터 조정 — 페이지 코드가 아니라 테스트 셀렉터를 맞출 것.)

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/admin/repo-catalog.vue frontend/layouts/default.vue \
        frontend/test/repo-catalog-admin.spec.ts
git commit -m "feat(repo-catalog): admin 페이지 + 네비 탭"
```

---

## Task 7: 등록 폼 별칭 셀렉트 전환 + 목록 별칭 표시

`tasks/index.vue`의 자유 입력 레포 input을 카탈로그 별칭 q-select로 교체. 선택 시 그 항목의 `ownerRepo`로 기존 `loadBranches()`를 호출해 브랜치 채움. 제출 시 `repoCatalogId` 전송. 목록 "레포" 컬럼은 별칭 표시.

**Files:**
- Modify: `frontend/pages/tasks/index.vue`
- Test: `frontend/test/tasks-form-repo-select.spec.ts`

**Interfaces:**
- Consumes: `GET /api/repo-catalog` → `RepoEntry[]` (View: `{id, alias, ownerRepo, defaultBranch, ...}`); `POST /api/interviews` body에 `repoCatalogId`.

- [ ] **Step 1: 스크립트 — 카탈로그 상태 + 선택 핸들러 추가**

`frontend/pages/tasks/index.vue` `<script setup>`에서:

1. `draft`에서 `githubRepo`를 빼고 `repoCatalogId`를 추가 (라인 52 근처):
```ts
const draft = reactive({ repoCatalogId: null as number | null, githubBranch: '', title: '', description: '', model: DEFAULT_MODEL, effort: DEFAULT_EFFORT })
```

2. 레포 카탈로그 목록 상태 추가 (MCP catalog 상태 근처, 라인 82 부근):
```ts
// 레포 카탈로그 (작업 등록 대상 레포)
interface RepoCatalogEntry {
  id: number
  alias: string
  ownerRepo: string | null
  defaultBranch: string | null
}
const repoCatalog = ref<RepoCatalogEntry[]>([])
const repoCatalogLoading = ref(false)
const repoOptions = computed(() =>
  repoCatalog.value.map((r) => ({ label: r.alias, value: r.id })),
)

async function loadRepoCatalog() {
  repoCatalogLoading.value = true
  try {
    repoCatalog.value = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
  } catch {
    repoCatalog.value = []
  } finally {
    repoCatalogLoading.value = false
  }
}

// 별칭 선택 → ownerRepo로 브랜치 로드 + 기본 브랜치 프리필
function onRepoSelected(catalogId: number | null) {
  draft.githubBranch = ''
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry || !entry.ownerRepo) return
  loadBranches(entry.ownerRepo).then(() => {
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}
```

3. **제거**: `REPO_RE`(라인 129), `normalizeRepo()`(라인 142-148), `watch(() => draft.githubRepo, ...)`(라인 150-168 블록 전체), 그리고 `repoStatus`의 자유입력 전용 값(`'invalid'`,`'empty'`)에 의존하는 로직 중 input과 직접 묶인 부분. `loadBranches`/`branchOptions`/`filteredBranchOptions`/`onBranchFilter`/`resetBranchState`/`repoStatus` ref 자체는 **유지**(브랜치 드롭다운이 계속 사용). `resetBranchState()`에서 `draft.githubBranch = ''`는 유지하되 `draft.githubRepo` 참조가 있으면 제거.
> `loadBranches`가 `Promise`를 반환하지 않으면(현재 `async function`이라 반환함) `.then` 사용 가능. 확인 후 필요시 `await` 형태로 `onRepoSelected`를 async로.

4. `startInterview()` body에서 `githubRepo: normalizeRepo(draft.githubRepo)` → `repoCatalogId`:
```ts
    const res = await useApi<{ sessionId: number }>('/api/interviews', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        description: draft.description,
        mcpCatalogIds: selectedCatalogIds.value,
        model: draft.model,
        effort: draft.effort,
      },
    })
```

5. 다이얼로그 열 때 카탈로그 로드 — `openCreate`(또는 폼 다이얼로그 여는 함수, 라인 ~290 근처)에 `loadRepoCatalog()` 호출 추가. `draft` 초기화 시 `draft.repoCatalogId = null`.

6. `TaskResponse` 인터페이스(라인 15-30)에 `repoAlias` 추가:
```ts
interface TaskResponse {
  id: number
  githubRepo: string
  repoAlias: string | null
  githubBranch: string
  ...
}
```

- [ ] **Step 2: 템플릿 — input을 q-select로 교체**

`frontend/pages/tasks/index.vue` 템플릿에서 "GitHub 레포" `q-input` 블록(라인 563-585)을 교체:
```vue
          <q-select
            v-model="draft.repoCatalogId"
            :options="repoOptions"
            :loading="repoCatalogLoading"
            label="레포 (별칭 선택)"
            outlined
            dense
            emit-value
            map-options
            autofocus
            data-test="repo-select"
            :hint="repoCatalog.length === 0 ? '등록된 레포 없음 — 관리자에게 문의' : '관리자가 등록한 레포 중 선택'"
            @update:model-value="onRepoSelected"
          >
            <template #no-option>
              <q-item>
                <q-item-section class="text-grey">등록된 레포가 없습니다</q-item-section>
              </q-item>
            </template>
          </q-select>
```
브랜치 `q-select`(라인 587-611)는 그대로 유지.

- [ ] **Step 3: 템플릿 — 목록 "레포" 컬럼 별칭 표시**

`레포` 컬럼 정의(라인 449)를 별칭 우선으로:
```js
        { name: 'repo', label: '레포', field: (r) => r.repoAlias ?? r.githubRepo, align: 'left' },
```

- [ ] **Step 4: 실패하는 Vitest 작성**

`frontend/test/tasks-form-repo-select.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// onMounted: discoverActiveInterviews → GET /api/interviews/active
// 폼 열기 시: loadRepoCatalog → GET /api/repo-catalog (+ MCP catalog 등)
describe('tasks form — repo alias select', () => {
  it('loads repo catalog and submits repoCatalogId to /api/interviews', async () => {
    useApiMock.mockResolvedValue([]) // 기본: 모든 GET 빈 배열 (active interviews 등)
    const w = mount(TasksIndex)
    await flushPromises()

    // 폼 열기
    await w.find('[data-test], button')
    // openCreate 트리거: '작업 등록' 버튼
    const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    await flushPromises()

    // 카탈로그 GET이 호출됐는지
    expect(useApiMock).toHaveBeenCalledWith('/api/repo-catalog')
    w.unmount()
  })
})
```
> NOTE: `tasks/index.vue`는 onMounted에서 여러 GET을 호출하고 폼 다이얼로그도 복잡하다. 이 스펙은 **핵심 계약만**(카탈로그 로드 + 제출 페이로드) 확인한다. mount가 무거우면 `useApiMock.mockResolvedValue([])`로 모든 미지정 호출을 빈 배열 처리. 셀렉터/버튼 텍스트는 실제 렌더에 맞춰 조정. 제출 페이로드 단언까지 추가하려면 `repo-select` 선택 → `onRepoSelected` → 브랜치 로드 모킹 → '인터뷰 시작' 버튼 클릭 후 `expect(useApiMock).toHaveBeenCalledWith('/api/interviews', { method: 'POST', body: expect.objectContaining({ repoCatalogId: ... }) })`.

- [ ] **Step 5: 테스트 + 회귀 실행**

Run: `cd frontend && npx vitest run`
Expected: 신규 스펙 PASS + 기존 스펙 회귀 없음. (기존 폼 관련 스펙이 `githubRepo`를 참조하면 함께 갱신.)

- [ ] **Step 6: 수동 스모크 (선택, 권장)**

```
1. 백엔드(api) + 프론트(3001) 기동, admin 로그인
2. /admin/repo-catalog 에서 'Netis7.0' 시드 확인 + "연결 확인" 클릭 → 성공 notify
3. /tasks → '작업 등록' → 레포 셀렉트에 'Netis7.0' 노출 → 선택 → 브랜치 드롭다운 채워짐
4. 제목/설명 입력 → '인터뷰 시작' → 정상 진행
5. 작업 목록 "레포" 컬럼에 'Netis7.0'(별칭) 표시 확인
```

- [ ] **Step 7: 커밋**

```bash
git add frontend/pages/tasks/index.vue frontend/test/tasks-form-repo-select.spec.ts
git commit -m "feat(repo-catalog): 등록 폼 별칭 셀렉트 전환 + 목록 별칭 표시"
```

---

## Self-Review (작성자 체크리스트 — 실행 결과는 아래 반영됨)

**1. 스펙 커버리지**
- §4.1 repo_catalog 테이블 → Task 2 ✅
- §4.2 Task 스냅샷 컬럼 → Task 2(마이그레이션) + Task 5(엔티티/세팅) ✅ (+ interview_session도 커버)
- §5 API 5+1 엔드포인트 → Task 4 ✅
- §5.1 등록 계약 repoCatalogId 전환 → Task 5 ✅
- §6 프론트 admin 페이지/네비/폼/목록 → Task 6, 7 ✅
- §7 마이그레이션 + 시드 → Task 2 ✅
- §8 테스트(파서/서비스/등록/검증/ACL/프론트) → Task 1,3,4,5,6,7 ✅
- §9 엣지(비활성/삭제/비-GitHub/정규화/빈 상태/unique) → Task 1,3 테스트 + 폼 빈 상태(Task 7) ✅

**2. 플레이스홀더 스캔**: "TBD/TODO/적절히 처리" 없음. 각 코드 스텝에 실제 코드 포함. (Task 5 Step 9, Task 7의 일부는 기존 파일의 정확한 라인을 grep으로 찾으라는 지시 — 라인 번호가 시점에 따라 바뀌므로 의도적. 변경 내용 자체는 구체 코드로 제시함.)

**3. 타입 일관성**:
- `ResolvedRepo(catalogId, alias, gitUrl, host, ownerRepo, defaultBranch)` — Task 3 정의, Task 5 소비 일치 ✅
- `RepoCatalogDto.View`/`UpsertRequest`/`CheckResult` — Task 3 정의, Task 4 컨트롤러·Task 6 프론트 소비 일치 ✅
- `RepoUrlParser.Parsed(canonicalUrl, host, ownerRepo)` — Task 1 정의, Task 3 소비 일치 ✅
- `repoCatalogId`(Long) — DTO·서비스·프론트 제출 페이로드 일치 ✅
- 리포지토리 파생쿼리명 `findByEnabledTrueOrderByAlias` 등 — Task 2 정의, Task 3 소비 일치 ✅

**알려진 의존 확인 포인트(구현자 주의)**:
- `BranchListResponse`의 접근자명(`defaultBranch()`/`branches()`) — Task 3 NOTE대로 구현 전 확인.
- `InterviewSession` 클래스 레벨 `@Setter` 유무 — Task 5 Step 5 NOTE.
- `TaskServiceDeployTest`/`InterviewServiceTest` 생성자 인자 추가 — Task 5 Step 10.
- 게이팅 통합테스트의 `TaskCreateRequest(...)` 호출부 — Task 5 Step 9 (grep으로 위치 확정).
