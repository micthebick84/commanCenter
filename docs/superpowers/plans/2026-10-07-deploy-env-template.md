# 배포 환경변수 이름 자동 채움 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 구현이 끝난 작업의 배포 다이얼로그에 필요한 환경변수 이름(과 같은 레포 직전 배포 값)이 미리 채워져, 관리자는 값만 넣고 배포한다.

**Architecture:** 구현 세션의 Claude가 `.netis-deploy-env.json`에 env 이름 목록을 쓰면 워커가 커밋 전에 회수·삭제해 `PR_CREATED` 보고(`envTemplate`)로 올리고, API는 `task.env_template`(V25)에 저장한다. 관리자 전용 `GET /api/tasks/{id}/deploy-env`가 저장 env·템플릿·같은 레포 직전 배포 env를 순수 함수(`DeployEnvSuggester`)로 합쳐 돌려주고, 분리한 `DeployEnvDialog.vue`가 KEY를 고정 라벨로 보여 준다. 비관리자 작업 응답은 비밀 값을 비운다.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JPA(Hibernate JSON → jsonb) / Flyway / Jackson 2.18 · Nuxt 3 + Quasar · JUnit 5 + Mockito + AssertJ · Testcontainers(CI) · Vitest

**Spec:** `docs/superpowers/specs/2026-10-07-deploy-env-template-design.md`

## Global Constraints

- 작업 위치: 워크트리 `C:/Users/mic/NetisMaker/commanCenter/.claude/worktrees/deploy-env-template`(브랜치 `feat/deploy-env-template`). 메인 체크아웃(라이브 스택 `bootRun`)에서는 빌드·테스트하지 않는다.
- Gradle(Git Bash): `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "<FQCN>"` — **항상 클래스를 지정**한다. Gradle 데몬·Java 프로세스를 종료하지 않는다.
- 줄바꿈: 작업 사본은 CRLF(`core.autocrlf=true`). **기존 파일은 Edit 도구로만 고친다**(Git Bash `sed -i`는 CRLF를 LF로 바꾼다). 새 파일은 작성 뒤 `sed -i 's/\r$//; s/$/\r/' <파일>`로 CRLF로 맞추고, 커밋 전 `file <파일>`로 `CRLF` 확인.
- 주석·문서·커밋 메시지는 한국어. 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 목록 파일명 `.netis-deploy-env.json`, 형식 `{"vars":[{"key","description","secret","required"}]}`(최상위 배열 허용, 모르는 필드 무시).
- KEY 규칙 `[A-Za-z_][A-Za-z0-9_]{0,127}`, 설명 300자, 최대 50개, 같은 KEY는 처음 것만.
- 마이그레이션 `V25__task_env_template.sql`: `env_template JSONB NOT NULL DEFAULT '[]'::jsonb`.
- 엔드포인트 `GET /api/tasks/{id}/deploy-env`는 `ROLE_ADMIN` 한정, 삭제된 작업 404.
- 직전 배포 = 같은 `github_repo` + 같은 호스트(`RepoRef.fromSnapshot(...).host()`), 삭제 안 됨, env 비어 있지 않음, 자기 자신 제외, `배포대기` 이력 최신순, 최대 20건 조회.
- 비관리자 응답(등록 2개·목록·상세·취소·재시도)은 `secret=true` env 값을 `""`로. 관리자 전용 엔드포인트·워커 페이로드는 그대로.
- 다이얼로그 제목은 `배포 — 환경변수` / `재배포 — 환경변수`(기존 테스트가 의존). 빈 KEY·빈 값 행은 전송하지 않는다. 필수 미입력이면 주황 `그래도 배포`/`그래도 재배포`.
- 프론트 스타일: 작은따옴표·세미콜론 없음·2칸·trailing comma. 모바일 분기는 `$q.screen.lt.md` 하나. 클래스명에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지. `q-dialog` 콘텐츠 루트는 `q-card`(div).
- 프론트 테스트용 `frontend/node_modules`는 메인 체크아웃으로의 **정션**이다. 워크트리를 지우기 전에 반드시 `cmd //c "rmdir frontend\\node_modules"`로 링크만 지운다(정션을 따라가 메인 체크아웃 `node_modules`가 지워지는 것을 막기 위해).

## Review Focus

1. **Claude가 목록 파일에 실제 값(`value`)이나 비밀을 적어 넣음** → 템플릿·보고·API·화면 어디에도 값이 실리지 않아야 한다. (Task 2 `values_written_by_mistake_are_never_carried`)
2. **재배포에서 추천 API가 실패하거나 엉뚱한 응답을 줌** → 저장된 env가 비밀 값까지 그대로 다시 전송돼야 한다(빈 값 행으로 빠져 지워지면 안 됨). (Task 10 `추천을 못 받으면 저장된 env로 채우고 비밀 값도 그대로 다시 보낸다`)
3. **직전 배포 작업이 배포 중지(`deployed_at=null`)됐거나 재배포로 순서가 바뀜** → `배포대기` 이력이 가장 최근인 작업을 골라야 한다. (Task 7 통합 테스트, CI)
4. **구버전 워커 보고(`envTemplate` 없음)나 배포 중지 복귀 보고(`PR_CREATED`)** → 저장된 템플릿을 지우거나 바꾸면 안 된다. (Task 5 `an_old_worker_without_template_leaves_it_untouched`, `undeploy_return_to_pr_created_does_not_touch_the_template`)
5. **다이얼로그를 닫았다 다시 열거나, 이전 요청이 늦게 도착함** → 매번 새로 불러온 값이어야 하고, 늦은 이전 응답이 새 행을 덮으면 안 된다. (Task 10 `닫았다 다시 열면…`, `늦게 도착한 이전 응답은…`)

---

### Task 1: 템플릿 항목 모델 · V25 컬럼 · Task 필드

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/entity/EnvTemplateItem.java`
- Create: `src/main/resources/db/migration/V25__task_env_template.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (`envVars` 필드 바로 아래)
- Test: `src/test/java/com/hamonsoft/netismaker/entity/EnvTemplateItemTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `record EnvTemplateItem(String key, String description, boolean secret, boolean required)`; `static List<EnvTemplateItem> EnvTemplateItem.sanitize(List<EnvTemplateItem> raw)`(항상 새 `ArrayList`); 상수 `MAX_ITEMS=50`, `MAX_DESCRIPTION=300`; `Task.getEnvTemplate()`/`setEnvTemplate(List<EnvTemplateItem>)`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/entity/EnvTemplateItemTest.java`:

```java
package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** env 템플릿 정규화 — 워커 파싱 결과와 API 수신값 공용 (스펙 2026-10-07 §3.1). */
class EnvTemplateItemTest {

    @Test
    void null_list_and_null_items_become_empty() {
        assertThat(EnvTemplateItem.sanitize(null)).isEmpty();
        assertThat(EnvTemplateItem.sanitize(Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void null_fields_default_to_empty_strings() {
        EnvTemplateItem item = new EnvTemplateItem(null, null, false, false);
        assertThat(item.key()).isEmpty();
        assertThat(item.description()).isEmpty();
    }

    @Test
    void keys_are_trimmed_and_invalid_names_dropped() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem("  JWT_SECRET ", "서명 키", true, true),
                new EnvTemplateItem("1BAD", "", false, false),
                new EnvTemplateItem("HAS-DASH", "", false, false),
                new EnvTemplateItem("HAS SPACE", "", false, false),
                new EnvTemplateItem("", "", false, false),
                new EnvTemplateItem("_private", "", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("JWT_SECRET", "_private");
        assertThat(out.get(0)).isEqualTo(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void duplicate_keys_keep_the_first_occurrence() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem("DB_URL", "첫 번째", false, true),
                new EnvTemplateItem("DB_URL", "두 번째", true, false),
                new EnvTemplateItem("db_url", "대소문자 다름", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("DB_URL", "db_url");
        assertThat(out.get(0).description()).isEqualTo("첫 번째");
    }

    @Test
    void long_keys_and_descriptions_are_bounded() {
        String longKey = "K" + "x".repeat(128);   // 129자 — 상한(128) 초과
        String desc = "가".repeat(EnvTemplateItem.MAX_DESCRIPTION + 50);
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem(longKey, "", false, false),
                new EnvTemplateItem("OK", "  " + desc + "  ", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("OK");
        assertThat(out.get(0).description()).hasSize(EnvTemplateItem.MAX_DESCRIPTION);
    }

    @Test
    void at_most_fifty_items_are_kept() {
        List<EnvTemplateItem> many = new ArrayList<>();
        IntStream.range(0, EnvTemplateItem.MAX_ITEMS + 10)
                .forEach(i -> many.add(new EnvTemplateItem("VAR_" + i, "", false, false)));

        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(many);

        assertThat(out).hasSize(EnvTemplateItem.MAX_ITEMS);
        assertThat(out.get(EnvTemplateItem.MAX_ITEMS - 1).key()).isEqualTo("VAR_49");
    }

    @Test
    void sanitized_list_is_mutable_for_jpa() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(null);
        out.add(new EnvTemplateItem("A", "", false, false));   // Hibernate가 다룰 수 있게 가변 목록
        assertThat(out).hasSize(1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.entity.EnvTemplateItemTest"`
Expected: FAIL — `compileTestJava`에서 `cannot find symbol: class EnvTemplateItem`

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/entity/EnvTemplateItem.java`:

```java
package com.hamonsoft.netismaker.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 구현 시 추출한 배포 환경변수 템플릿 항목. task.env_template (jsonb) 배열의 한 요소.
 *
 * 값은 담지 않는다 — 이름·설명·비밀 여부·필수 여부만 (스펙 2026-10-07 §3.1). 값은 배포 다이얼로그에서
 * 관리자가 넣고 task.env_vars(EnvVar)로 저장된다.
 */
public record EnvTemplateItem(String key, String description, boolean secret, boolean required) {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_DESCRIPTION = 300;
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public EnvTemplateItem {
        if (key == null) key = "";
        if (description == null) description = "";
    }

    /**
     * 워커 파싱 결과와 API 수신값에 공통으로 거는 정규화 — 잘못된 KEY·중복(처음 것만) 제거,
     * 설명 300자, 최대 50개. 항상 새 가변 목록을 돌려준다(JPA 컬렉션으로 그대로 set).
     */
    public static List<EnvTemplateItem> sanitize(List<EnvTemplateItem> raw) {
        List<EnvTemplateItem> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        for (EnvTemplateItem item : raw) {
            if (out.size() >= MAX_ITEMS) break;
            if (item == null) continue;
            String key = item.key().trim();
            if (!KEY.matcher(key).matches() || !seen.add(key)) continue;
            out.add(new EnvTemplateItem(key, clip(item.description().trim()), item.secret(), item.required()));
        }
        return out;
    }

    private static String clip(String s) {
        if (s.length() <= MAX_DESCRIPTION) return s;
        int end = Character.isHighSurrogate(s.charAt(MAX_DESCRIPTION - 1)) ? MAX_DESCRIPTION - 1 : MAX_DESCRIPTION;
        return s.substring(0, end);
    }
}
```

`src/main/resources/db/migration/V25__task_env_template.sql`:

```sql
-- 구현 시 추출한 배포 환경변수 템플릿 [{key, description, secret, required}] — 값은 담지 않는다.
-- 스펙 docs/superpowers/specs/2026-10-07-deploy-env-template-design.md §3.2. 기존 row는 빈 배열(템플릿 없음).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS env_template JSONB NOT NULL DEFAULT '[]'::jsonb;
```

`Task.java` — Edit로 `envVars` 필드 뒤에 추가:

old:
```java
    /** 배포 시 컨테이너에 주입할 환경변수 (key/value/secret). 배포 다이얼로그에서 set. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_vars", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<EnvVar> envVars = new ArrayList<>();
```
new:
```java
    /** 배포 시 컨테이너에 주입할 환경변수 (key/value/secret). 배포 다이얼로그에서 set. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_vars", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<EnvVar> envVars = new ArrayList<>();

    /**
     * 구현 시 추출한 배포 환경변수 템플릿 (이름·설명·비밀·필수, 값 없음). 구현 성공(PR_CREATED) 보고에서 set —
     * 배포 다이얼로그 미리 채움에 쓴다 (스펙 2026-10-07).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_template", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<EnvTemplateItem> envTemplate = new ArrayList<>();
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.entity.EnvTemplateItemTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
for f in src/main/java/com/hamonsoft/netismaker/entity/EnvTemplateItem.java src/main/resources/db/migration/V25__task_env_template.sql src/test/java/com/hamonsoft/netismaker/entity/EnvTemplateItemTest.java; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file src/main/java/com/hamonsoft/netismaker/entity/EnvTemplateItem.java src/main/resources/db/migration/V25__task_env_template.sql src/test/java/com/hamonsoft/netismaker/entity/EnvTemplateItemTest.java src/main/java/com/hamonsoft/netismaker/entity/Task.java
git add src/main/java/com/hamonsoft/netismaker/entity/EnvTemplateItem.java src/main/resources/db/migration/V25__task_env_template.sql src/main/java/com/hamonsoft/netismaker/entity/Task.java src/test/java/com/hamonsoft/netismaker/entity/EnvTemplateItemTest.java
git commit -m "feat(api): 배포 env 템플릿 항목(EnvTemplateItem)과 task.env_template 컬럼(V25)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 워커 — 목록 파일 회수 (`DeployEnvManifest`)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifestTest.java`

**Interfaces:**
- Consumes: `EnvTemplateItem`, `EnvTemplateItem.sanitize` (Task 1)
- Produces: package-private `final class DeployEnvManifest`; `static final String FILE_NAME = ".netis-deploy-env.json"`; `static List<EnvTemplateItem> harvest(Path worktreeRoot)` — 예외 없음, 파일은 항상 삭제

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifestTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 구현 세션이 남긴 .netis-deploy-env.json 회수 (스펙 2026-10-07 §4.2). */
class DeployEnvManifestTest {

    @TempDir Path wt;

    private Path manifest(String json) throws IOException {
        return Files.writeString(wt.resolve(DeployEnvManifest.FILE_NAME), json);
    }

    @Test
    void missing_file_yields_an_empty_template() {
        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
    }

    @Test
    void valid_file_is_parsed_and_then_deleted() throws Exception {
        Path f = manifest("""
                {"vars":[
                  {"key":"SPRING_DATASOURCE_URL","description":"DB 접속 JDBC URL","secret":false,"required":true},
                  {"key":"JWT_SECRET","description":"토큰 서명 키","secret":true,"required":true}
                ]}
                """);

        List<EnvTemplateItem> out = DeployEnvManifest.harvest(wt);

        assertThat(out).containsExactly(
                new EnvTemplateItem("SPRING_DATASOURCE_URL", "DB 접속 JDBC URL", false, true),
                new EnvTemplateItem("JWT_SECRET", "토큰 서명 키", true, true));
        assertThat(f).doesNotExist();   // 커밋(git add -A)에 섞이면 안 된다
    }

    @Test
    void values_written_by_mistake_are_never_carried() throws Exception {
        manifest("{\"vars\":[{\"key\":\"DB_PASSWORD\",\"value\":\"s3cret\",\"secret\":true}]}");

        List<EnvTemplateItem> out = DeployEnvManifest.harvest(wt);

        assertThat(out).containsExactly(new EnvTemplateItem("DB_PASSWORD", "", true, false));
        assertThat(out.toString()).doesNotContain("s3cret");
    }

    @Test
    void a_top_level_array_is_accepted() throws Exception {
        manifest("[{\"key\":\"API_BASE_URL\",\"required\":true}]");

        assertThat(DeployEnvManifest.harvest(wt))
                .containsExactly(new EnvTemplateItem("API_BASE_URL", "", false, true));
    }

    @Test
    void broken_json_yields_empty_and_still_deletes_the_file() throws Exception {
        Path f = manifest("{\"vars\":[{\"key\":");

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
        assertThat(f).doesNotExist();
    }

    @Test
    void unexpected_shape_yields_empty() throws Exception {
        manifest("{\"variables\":{\"A\":1}}");

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
    }

    @Test
    void entries_are_sanitized() throws Exception {
        manifest("{\"vars\":[{\"key\":\"BAD-KEY\"},{\"key\":\"GOOD\"},{\"key\":\"GOOD\"},\"not-an-object\"]}");

        assertThat(DeployEnvManifest.harvest(wt)).extracting(EnvTemplateItem::key).containsExactly("GOOD");
    }

    @Test
    void a_symlink_is_not_followed_and_only_the_link_is_removed() throws Exception {
        Path target = Files.writeString(wt.resolve("elsewhere.json"), "{\"vars\":[{\"key\":\"LEAK\"}]}");
        Path link = wt.resolve(DeployEnvManifest.FILE_NAME);
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경: " + e);
        }

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(target).exists();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.DeployEnvManifestTest"`
Expected: FAIL — `cannot find symbol: variable DeployEnvManifest`

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 구현 세션이 worktree 루트에 남긴 배포 환경변수 목록(.netis-deploy-env.json)을 회수한다 (스펙 2026-10-07 §4.2).
 *
 * 계약 (WorkerMainLoop.DEPLOY_ENV_INSTRUCTION과 동기):
 *   {"vars":[{"key","description","secret","required"}]} — 최상위 배열도 받는다. 모르는 필드(value 등)는 버린다.
 * 파일은 커밋(git add -A) 전에 지운다 — 파싱 성공 여부와 무관. 어떤 실패도 구현을 막지 않는다(빈 목록 + 경고).
 * 링크는 따라가지 않는다 — Claude가 권한 우회로 worktree 밖 파일을 가리키게 만들어도 읽지 않고 링크만 지운다.
 */
@Slf4j
final class DeployEnvManifest {

    static final String FILE_NAME = ".netis-deploy-env.json";
    private static final long MAX_BYTES = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeployEnvManifest() {}

    /** 목록을 읽고 파일을 지운다. 파일이 없거나 형식이 틀리면 빈 목록. 예외를 던지지 않는다. */
    static List<EnvTemplateItem> harvest(Path worktreeRoot) {
        Path file = worktreeRoot.resolve(FILE_NAME);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("배포 env 목록이 일반 파일이 아님(링크 등) — 읽지 않음: {}", file);
                return List.of();
            }
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                log.warn("배포 env 목록이 너무 큼({} bytes) — 무시: {}", size, file);
                return List.of();
            }
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            log.warn("배포 env 목록 읽기 실패 — 템플릿 없이 진행: {} ({})", file, e.toString());
            return List.of();
        } finally {
            delete(file);
        }
    }

    private static List<EnvTemplateItem> parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode vars = root == null ? null : root.isArray() ? root : root.get("vars");
        if (vars == null || !vars.isArray()) {
            log.warn("배포 env 목록 형식이 다름(vars 배열 없음) — 템플릿 없이 진행");
            return List.of();
        }
        List<EnvTemplateItem> raw = new ArrayList<>();
        for (JsonNode n : vars) {
            if (!n.isObject()) continue;
            raw.add(new EnvTemplateItem(n.path("key").asText(""), n.path("description").asText(""),
                    n.path("secret").asBoolean(false), n.path("required").asBoolean(false)));
        }
        return EnvTemplateItem.sanitize(raw);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);   // 링크면 링크 자체만 지운다
        } catch (IOException e) {
            log.warn("배포 env 목록 삭제 실패 — 커밋에 섞일 수 있음(값은 없음): {} ({})", file, e.toString());
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.DeployEnvManifestTest"`
Expected: PASS 7개 + 이 Windows PC에서는 심볼릭 링크 테스트 1개 SKIPPED(권한 없음 — CI 리눅스에서 실행)

- [ ] **Step 5: 커밋**

```bash
for f in src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifestTest.java; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifestTest.java
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployEnvManifestTest.java
git commit -m "feat(worker): 구현 세션의 배포 env 목록 파일 회수(DeployEnvManifest)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 결과 보고에 `envTemplate` 필드

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java`
- Modify: `src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestMaskTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java`

**Interfaces:**
- Consumes: `EnvTemplateItem` (Task 1)
- Produces: `WorkerResultRequest`의 23번째(마지막) 컴포넌트 `List<EnvTemplateItem> envTemplate`(nullable) + 기존 22개 인자 생성자(→ `envTemplate=null`). `masked()`는 설명을 `GitRemotes.mask`로 가림.

- [ ] **Step 1: 실패 테스트 작성**

`WorkerResultRequestMaskTest.java` — import 두 줄 추가:

old:
```java
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
```
new:
```java
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
```

같은 파일 끝(마지막 `}` 앞)에 테스트 2개 추가:

```java
    @Test
    void env_template_descriptions_are_masked_but_keys_and_flags_kept() {
        var masked = new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/7", 7, "netismaker/task-1", "abc123", null,
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(new EnvTemplateItem("GIT_URL", DIRTY, true, true))).masked();

        assertThat(masked.envTemplate()).containsExactly(new EnvTemplateItem("GIT_URL", CLEAN, true, true));
    }

    @Test
    void other_reports_carry_no_env_template() {
        var failed = WorkerResultRequest.deployFailed("w1", "x", "log");

        assertThat(failed.envTemplate()).isNull();
        assertThat(failed.masked().envTemplate()).isNull();
    }
```

`src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 보고 JSON — 레코드에 생성자가 둘(정규 23개 + 기존 22개)이어도 Jackson은 정규 생성자로 읽는다.
 * 워커 전송·API 수신·dead-letter 재전송이 모두 이 경로다 (스펙 2026-10-07 §5, §9).
 */
class WorkerResultRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void env_template_survives_a_json_round_trip() throws Exception {
        var req = new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/7", 7, "netismaker/task-1", "abc123", "log",
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(new EnvTemplateItem("JWT_SECRET", "토큰 서명 키", true, true)));

        WorkerResultRequest back = json.readValue(json.writeValueAsString(req), WorkerResultRequest.class);

        assertThat(back).isEqualTo(req);
    }

    @Test
    void a_report_without_env_template_reads_as_null() throws Exception {
        // 구버전 워커 보고·dead-letter에 남은 옛 보고
        WorkerResultRequest back = json.readValue(
                "{\"workerId\":\"w1\",\"status\":\"PR_CREATED\",\"prUrl\":\"u\",\"headBranch\":\"b\"}",
                WorkerResultRequest.class);

        assertThat(back.envTemplate()).isNull();
        assertThat(back.prUrl()).isEqualTo("u");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestMaskTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestJsonTest"`
Expected: FAIL — `constructor WorkerResultRequest in record WorkerResultRequest cannot be applied to given types` / `cannot find symbol: method envTemplate()`

- [ ] **Step 3: 구현**

`WorkerResultRequest.java` — import:

old:
```java
import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
```
new:
```java
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

import java.util.List;
```

클래스 주석의 PR_CREATED 줄:

old:
```java
 *   status=PR_CREATED            → prUrl, headBranch 필수 (구현) / 또는 UNDEPLOY 성공 복귀
```
new:
```java
 *   status=PR_CREATED            → prUrl, headBranch 필수 (구현, envTemplate 선택) / 또는 UNDEPLOY 성공 복귀
```

컴포넌트 추가 + 기존 22개 인자 생성자 + masked:

old:
```java
        // 사용량 (전 phase 공용, 스펙 §4.1 — null이면 미수집/구버전 워커)
        UsageReport usage
) {
    /**
     * 자유 텍스트 필드를 전부 마스킹한 복사본.
     *
     * 워커가 API로 올리는 텍스트(claude 로그·배포 로그·분석/디자인 마크다운·실패 사유)는
     * 작업 상세 화면에 그대로 렌더되므로, 그 안에 섞여 들어온 인증 URL을 여기서 한 번에 가린다.
     * 식별자·열거형·숫자·결과 URL(prUrl/deployUrl/designUrl 등)은 자유 텍스트가 아니라 그대로 둔다.
     * 적용 지점은 {@code ResultReporter.reportTerminal} 한 곳(전송 + dead-letter 기록 공통).
     */
    public WorkerResultRequest masked() {
        return new WorkerResultRequest(
                workerId, status,
                m(markdownResult), m(subtasksJson), m(claudeLog), durationMs, m(failureReason),
                prUrl, prNumber, headBranch, headSha, m(implementationLog),
                deployUrl, deployContainerId, deployHostPort, deployImage, m(deployLog),
                m(designMarkdown), m(mockupFilesJson), designProjectId, designUrl,
                usage);
    }
```
new:
```java
        // 사용량 (전 phase 공용, 스펙 §4.1 — null이면 미수집/구버전 워커)
        UsageReport usage,
        // 구현 성공 시 추출한 배포 env 템플릿 (스펙 2026-10-07 §5 — null이면 미보고/구버전 워커)
        List<EnvTemplateItem> envTemplate
) {
    /**
     * envTemplate 도입 전 22개 인자 생성자 — 구현 성공 보고 외에는 템플릿이 없다.
     * Jackson은 레코드의 정규 생성자(23개)를 쓰므로 역직렬화와 무관하다(WorkerResultRequestJsonTest).
     */
    public WorkerResultRequest(String workerId, TaskStatus status,
                               String markdownResult, String subtasksJson, String claudeLog,
                               Long durationMs, String failureReason,
                               String prUrl, Integer prNumber, String headBranch, String headSha,
                               String implementationLog,
                               String deployUrl, String deployContainerId, Integer deployHostPort,
                               String deployImage, String deployLog,
                               String designMarkdown, String mockupFilesJson, String designProjectId,
                               String designUrl,
                               UsageReport usage) {
        this(workerId, status, markdownResult, subtasksJson, claudeLog, durationMs, failureReason,
                prUrl, prNumber, headBranch, headSha, implementationLog,
                deployUrl, deployContainerId, deployHostPort, deployImage, deployLog,
                designMarkdown, mockupFilesJson, designProjectId, designUrl, usage, null);
    }

    /**
     * 자유 텍스트 필드를 전부 마스킹한 복사본.
     *
     * 워커가 API로 올리는 텍스트(claude 로그·배포 로그·분석/디자인 마크다운·실패 사유·env 템플릿 설명)는
     * 작업 상세 화면에 그대로 렌더되므로, 그 안에 섞여 들어온 인증 URL을 여기서 한 번에 가린다.
     * 식별자·열거형·숫자·결과 URL(prUrl/deployUrl/designUrl 등)은 자유 텍스트가 아니라 그대로 둔다.
     * 적용 지점은 {@code ResultReporter.reportTerminal} 한 곳(전송 + dead-letter 기록 공통).
     */
    public WorkerResultRequest masked() {
        return new WorkerResultRequest(
                workerId, status,
                m(markdownResult), m(subtasksJson), m(claudeLog), durationMs, m(failureReason),
                prUrl, prNumber, headBranch, headSha, m(implementationLog),
                deployUrl, deployContainerId, deployHostPort, deployImage, m(deployLog),
                m(designMarkdown), m(mockupFilesJson), designProjectId, designUrl,
                usage, maskTemplate(envTemplate));
    }

    /** 템플릿 설명은 Claude가 쓴 자유 텍스트 — 다른 자유 텍스트와 같이 가린다. KEY·플래그는 그대로. */
    private static List<EnvTemplateItem> maskTemplate(List<EnvTemplateItem> items) {
        if (items == null) return null;
        return items.stream()
                .map(i -> i == null ? null
                        : new EnvTemplateItem(i.key(), m(i.description()), i.secret(), i.required()))
                .toList();
    }
```

(정적 팩토리 `deployed`/`deployFailed`/`undeployed`/`designReview`/`designFailed`와 테스트들의 22개 인자 호출은 새 생성자로 그대로 컴파일된다 — 수정하지 않는다.)

- [ ] **Step 4: 통과 확인 (+ 기존 사용처 회귀)**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestMaskTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestJsonTest" --tests "com.hamonsoft.netismaker.workerdaemon.ResultReporterTest" --tests "com.hamonsoft.netismaker.workerdaemon.DeadLetterReplayJobTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceReconcileTest"`
Expected: PASS (실패 0)

- [ ] **Step 5: 커밋**

```bash
sed -i 's/\r$//; s/$/\r/' src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java
file src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestMaskTest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestMaskTest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java
git commit -m "feat(worker): 결과 보고에 배포 env 템플릿 필드(envTemplate) 추가" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 워커 — 프롬프트 지시 · 커밋 전 회수 · 성공 보고

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java`

**Interfaces:**
- Consumes: `DeployEnvManifest.harvest(Path)`, `DeployEnvManifest.FILE_NAME` (Task 2), 23개 인자 `WorkerResultRequest` (Task 3)
- Produces: `static final String WorkerMainLoop.DEPLOY_ENV_INSTRUCTION`; `fillImplementationPrompt(...)`의 결과는 항상 이 문자열로 끝남; `PR_CREATED` 보고의 `envTemplate`는 회수 결과(파일 없으면 빈 목록), 실패 보고는 `null`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 구현 프롬프트의 배포 env 목록 지시 + 커밋 전 회수·보고 (스펙 2026-10-07 §4). */
class WorkerMainLoopDeployEnvTest {

    @TempDir Path tmp;

    // ---- 프롬프트 ----

    private static WorkerTaskResponse implementationTask() {
        Task t = Task.create("owner/repo", "main", "README 정리", "요구사항 본문", "user1", 3,
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 3L);
        return WorkerTaskResponse.forImplementation(t, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = WorkerMainLoopDeployEnvTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            return (String) worker.get("implementation-prompt-template");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_live_and_default_templates_both_end_with_the_deploy_env_instruction() {
        for (String tpl : List.of(workerYmlTemplate(), WorkerMainLoop.defaultImplementationPrompt())) {
            String prompt = WorkerMainLoop.fillImplementationPrompt(tpl, implementationTask(),
                    "35c9110", "netismaker/task-3-readme");

            assertThat(prompt).endsWith(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION);
            assertThat(prompt).contains(DeployEnvManifest.FILE_NAME);
            assertThat(prompt).contains("값은 절대 쓰지 마세요");
            assertThat(prompt).contains("SPRING_DATASOURCE_URL");
        }
    }

    // ---- 회수·보고 (pollAndProcess) ----

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    private static WorkerTaskResponse queuedImplementation() {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION,
                List.of(), null, null, "netismaker/task-7-add-login", "abc1234def", List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private record Run(ResultReporter reporter, GitOpsService gitOps, AtomicBoolean manifestPresentAtCommit) {}

    /** claude가 (manifestJson이 있으면) 목록 파일을 쓰고 exit=claudeExit로 끝나는 구현 1회. */
    private Run run(String manifestJson, int claudeExit) throws Exception {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        GitRepoCache repos = mock(GitRepoCache.class);
        ClaudeExecAdapter claude = mock(ClaudeExecAdapter.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        GitOpsService gitOps = mock(GitOpsService.class);
        ResultReporter reporter = mock(ResultReporter.class);
        File dir = Files.createDirectories(tmp.resolve("wt")).toFile();
        Path manifest = dir.toPath().resolve(DeployEnvManifest.FILE_NAME);
        AtomicBoolean presentAtCommit = new AtomicBoolean();

        when(http.nextTask()).thenReturn(Optional.of(queuedImplementation()));
        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(dir, "base123"));
        when(worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login")))
                .thenReturn(new WorktreeService.CreatedWorktree(dir, "netismaker/task-7-add-login"));
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    if (manifestJson != null) Files.writeString(manifest, manifestJson);
                    return new ClaudeExecAdapter.ExecResult(claudeExit, "done", 10, null);
                });
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            presentAtCommit.set(Files.exists(manifest));   // 남아 있으면 git add -A로 PR에 섞인다
            return "head456";
        });
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));

        WorkerMainLoop loop = new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class));
        loop.pollAndProcess();
        return new Run(reporter, gitOps, presentAtCommit);
    }

    private static WorkerResultRequest reported(ResultReporter reporter) {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    @Test
    void manifest_is_removed_before_commit_and_reported_with_pr_created() throws Exception {
        Run r = run("{\"vars\":[{\"key\":\"JWT_SECRET\",\"description\":\"서명 키\",\"secret\":true,\"required\":true}]}", 0);

        assertThat(r.manifestPresentAtCommit()).isFalse();
        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(req.envTemplate()).containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void without_a_manifest_the_pr_is_still_created_with_an_empty_template() throws Exception {
        Run r = run(null, 0);

        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(req.envTemplate()).isEmpty();
    }

    @Test
    void a_failed_implementation_reports_no_template() throws Exception {
        Run r = run("{\"vars\":[{\"key\":\"A\"}]}", 1);

        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(req.envTemplate()).isNull();
        verify(r.gitOps(), never()).commitAndPush(any(), any(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDeployEnvTest"`
Expected: FAIL — `cannot find symbol: variable DEPLOY_ENV_INSTRUCTION`

- [ ] **Step 3: 구현**

`WorkerMainLoop.java` — import:

old:
```java
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.TaskStatus;
```
new:
```java
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
```

old:
```java
import java.io.File;
import java.util.Optional;
```
new:
```java
import java.io.File;
import java.util.List;
import java.util.Optional;
```

클래스 주석의 구현 흐름:

old:
```java
 *     3) 구현 prompt 치환 (분석 markdown/subtasks 포함) + claude -p exec (worktree에서)
 *     4) GitOpsService.commitAndPush → headSha
```
new:
```java
 *     3) 구현 prompt 치환 (분석 markdown/subtasks 포함) + claude -p exec (worktree에서)
 *        → 커밋 전 배포 env 목록(.netis-deploy-env.json) 회수·삭제 (DeployEnvManifest)
 *     4) GitOpsService.commitAndPush → headSha
```

커밋 전 회수:

old:
```java
        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteDesignDir(designDir.toPath());

        // 4. 변경 commit + push
```
new:
```java
        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteDesignDir(designDir.toPath());

        // 배포 env 목록(.netis-deploy-env.json)도 커밋 전에 회수·삭제 — 없거나 틀려도 구현은 계속 (스펙 2026-10-07 §4.3)
        List<EnvTemplateItem> envTemplate = DeployEnvManifest.harvest(wt.dir().toPath());

        // 4. 변경 commit + push
```

성공 보고:

old:
```java
                null, null, null, null,
                usageOf(exec)
        ));
        log.info("구현 완료 + PR 생성: task={} pr=#{} {}", task.id(), pr.number(), pr.url());
```
new:
```java
                null, null, null, null,
                usageOf(exec),
                envTemplate
        ));
        log.info("구현 완료 + PR 생성: task={} pr=#{} {} (배포 env 템플릿 {}개)",
                task.id(), pr.number(), pr.url(), envTemplate.size());
```

프롬프트 지시 — `fillImplementationPrompt` 교체:

old:
```java
    /** 구현 프롬프트 템플릿의 placeholder를 채운다(yml 템플릿·기본 템플릿 공용). */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName) {
        return tpl
```
new:
```java
    /**
     * 구현 프롬프트 끝에 항상 붙는 배포 env 목록 지시 (스펙 2026-10-07 §4.1). yml·기본 템플릿 공통이고 운영자가
     * 템플릿을 바꿔도 빠지지 않는다. 파일 형식은 DeployEnvManifest가 읽는 계약과 같아야 한다.
     */
    static final String DEPLOY_ENV_INSTRUCTION = """

            ## 배포 환경변수 목록 (마지막 단계 — 필수)
            구현을 마친 뒤, 이 프로젝트를 컨테이너로 실행할 때 외부에서 넣어 줘야 하는 환경변수의 **이름**을
            현재 디렉터리(레포 루트)의 `.netis-deploy-env.json`에 아래 형식으로 쓰세요.
            이 파일은 워커가 읽은 뒤 지우며 커밋되지 않습니다.

            {"vars":[{"key":"SPRING_DATASOURCE_URL","description":"DB 접속 JDBC URL","secret":false,"required":true}]}

            - 값은 절대 쓰지 마세요(이름·설명만). 레포에 있는 실제 비밀번호·토큰을 옮겨 적지 마세요.
            - 대상: DB 접속(URL·계정·암호), 시크릿(JWT 키 등), 외부 API 주소·키처럼 배포 환경마다 달라지는 값.
            - 코드에 안전한 기본값이 있어 비워 둬도 실행되면 required=false.
            - Spring Boot는 설정 키를 relaxed binding 환경변수 이름으로 쓰세요(spring.datasource.url → SPRING_DATASOURCE_URL).
            - 암호·토큰·키는 secret=true.
            - description은 한국어 한 줄.
            - 필요한 환경변수가 없으면 {"vars":[]}를 쓰세요.
            """;

    /** 구현 프롬프트 템플릿의 placeholder를 채운다(yml 템플릿·기본 템플릿 공용). 끝에 배포 env 목록 지시를 붙인다. */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName) {
        return tpl
```

같은 메서드의 마지막 줄:

old:
```java
                .replace("{design_section}", renderDesignSection(task))
                .replace("{plan_section}", renderPlanSection(task));
    }
```
new:
```java
                .replace("{design_section}", renderDesignSection(task))
                .replace("{plan_section}", renderPlanSection(task))
                + DEPLOY_ENV_INSTRUCTION;
    }
```

- [ ] **Step 4: 통과 확인 (+ 같은 루프를 쓰는 기존 테스트)**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDeployEnvTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDesignDirTest"`
Expected: PASS (실패 0)

- [ ] **Step 5: 커밋**

```bash
sed -i 's/\r$//; s/$/\r/' src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java
file src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java
git commit -m "feat(worker): 구현 프롬프트에 배포 env 목록 지시 + 커밋 전 회수해 PR 생성 보고에 실음" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: API — 구현 성공 보고의 템플릿 저장

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceEnvTemplateTest.java`

**Interfaces:**
- Consumes: `WorkerResultRequest.envTemplate()` (Task 3), `EnvTemplateItem.sanitize`, `Task.setEnvTemplate` (Task 1)
- Produces: `PR_CREATED`(정상·지각 정합화)에서 `envTemplate != null`이면 정규화해 저장, `null`이면 유지. 배포 중지 복귀(`recordDeployResult`의 `PR_CREATED`)는 손대지 않음.

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/WorkerServiceEnvTemplateTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 구현 성공(PR_CREATED) 보고의 배포 env 템플릿 저장 (스펙 2026-10-07 §5). */
class WorkerServiceEnvTemplateTest {

    private TaskRepository taskRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo,
                mock(com.hamonsoft.netismaker.repository.TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(com.hamonsoft.netismaker.repository.InterviewSessionRepository.class),
                mock(com.hamonsoft.netismaker.repository.InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task taskIn(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(status);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));
        return t;
    }

    private static WorkerResultRequest prCreated(List<EnvTemplateItem> template) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, 123L, null,
                "https://github.com/o/r/pull/10", 10, "netismaker/task-16", "abcdef1", "impl log",
                null, null, null, null, null,
                null, null, null, null, null,
                template);
    }

    @Test
    void pr_created_stores_the_sanitized_template() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);

        service.recordResult(16L, prCreated(List.of(
                new EnvTemplateItem(" JWT_SECRET ", "서명 키", true, true),
                new EnvTemplateItem("BAD-KEY", "", false, false))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void an_old_worker_without_template_leaves_it_untouched() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("KEEP", "", false, false))));

        service.recordResult(16L, prCreated(null));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("KEEP");
    }

    @Test
    void late_pr_created_reconciliation_also_stores_the_template() {
        Task t = taskIn(TaskStatus.IMPLEMENTATION_FAILED);

        service.recordResult(16L, prCreated(List.of(new EnvTemplateItem("DB_URL", "", false, true))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("DB_URL");
    }

    @Test
    void undeploy_return_to_pr_created_does_not_touch_the_template() {
        Task t = taskIn(TaskStatus.UNDEPLOYING);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("KEEP", "", false, false))));

        service.recordResult(16L, new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, "undeploy log",
                null, null, null, null, null,
                List.of(new EnvTemplateItem("OTHER", "", false, false))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("KEEP");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceEnvTemplateTest"`
Expected: FAIL — `pr_created_stores_the_sanitized_template`, `late_pr_created_reconciliation_also_stores_the_template`가 `expected [EnvTemplateItem[...]] but was []`로 실패(나머지 둘은 이미 통과)

- [ ] **Step 3: 구현**

`WorkerService.java` — import는 추가하지 않는다(이미 `import com.hamonsoft.netismaker.entity.*;`).

지각 정합화 경로:

old:
```java
            t.setHeadSha(req.headSha());
            t.setImplementationLog(req.implementationLog());
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.IMPLEMENTATION_FAILED, TaskStatus.PR_CREATED,
```
new:
```java
            t.setHeadSha(req.headSha());
            t.setImplementationLog(req.implementationLog());
            applyEnvTemplate(t, req);
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.IMPLEMENTATION_FAILED, TaskStatus.PR_CREATED,
```

정상 경로:

old:
```java
                t.setHeadSha(req.headSha());
                t.setImplementationLog(req.implementationLog());
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            case IMPLEMENTATION_FAILED -> {
```
new:
```java
                t.setHeadSha(req.headSha());
                t.setImplementationLog(req.implementationLog());
                applyEnvTemplate(t, req);
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            case IMPLEMENTATION_FAILED -> {
```

헬퍼 — `recordDeployResult` 바로 앞:

old:
```java
    private void recordDeployResult(Task t, WorkerResultRequest req) {
```
new:
```java
    /** 구현 성공 보고의 배포 env 템플릿 저장 — null(구버전 워커)이면 기존 값 유지 (스펙 2026-10-07 §5). */
    private static void applyEnvTemplate(Task t, WorkerResultRequest req) {
        if (req.envTemplate() != null) t.setEnvTemplate(EnvTemplateItem.sanitize(req.envTemplate()));
    }

    private void recordDeployResult(Task t, WorkerResultRequest req) {
```

- [ ] **Step 4: 통과 확인 (+ 기존 WorkerService 테스트)**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceEnvTemplateTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceReconcileTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceStageUsageTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceDesignResultTest"`
Expected: PASS (실패 0)

- [ ] **Step 5: 커밋**

```bash
sed -i 's/\r$//; s/$/\r/' src/test/java/com/hamonsoft/netismaker/service/WorkerServiceEnvTemplateTest.java
file src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceEnvTemplateTest.java
git add src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceEnvTemplateTest.java
git commit -m "feat(api): 구현 성공 보고의 배포 env 템플릿 저장" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 미리 채움 규칙 (`DeployEnvSuggester`) · 응답 DTO

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/DeployEnvSuggestionResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/DeployEnvSuggester.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/DeployEnvSuggesterTest.java`

**Interfaces:**
- Consumes: `EnvVar`, `EnvTemplateItem` (Task 1)
- Produces: `record DeployEnvSuggestionResponse(List<Row> rows, int templateCount, Long previousTaskId)`, `enum Source { SAVED, TEMPLATE, PREVIOUS }`, `record Row(String key, String value, boolean secret, String description, boolean required, Source source, boolean valueFromPrevious)`; `static DeployEnvSuggestionResponse DeployEnvSuggester.suggest(List<EnvVar> saved, List<EnvTemplateItem> template, List<EnvVar> previous, Long previousTaskId)`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/DeployEnvSuggesterTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 배포 다이얼로그 미리 채움 규칙 (스펙 2026-10-07 §6.3). */
class DeployEnvSuggesterTest {

    private static EnvVar env(String key, String value, boolean secret) {
        return new EnvVar(key, value, secret);
    }

    private static EnvTemplateItem tpl(String key, String description, boolean secret, boolean required) {
        return new EnvTemplateItem(key, description, secret, required);
    }

    @Test
    void first_deploy_fills_template_keys_with_previous_values_and_appends_previous_only_keys() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(),
                List.of(tpl("DB_URL", "DB 접속 URL", false, true), tpl("JWT_SECRET", "서명 키", true, true)),
                List.of(env("DB_URL", "jdbc:postgresql://db/app", false), env("DB_PASSWORD", "pw", true)),
                41L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "jdbc:postgresql://db/app", false, "DB 접속 URL", true, Source.TEMPLATE, true),
                new Row("JWT_SECRET", "", true, "서명 키", true, Source.TEMPLATE, false),
                new Row("DB_PASSWORD", "pw", true, "", false, Source.PREVIOUS, true));
        assertThat(r.templateCount()).isEqualTo(2);
        assertThat(r.previousTaskId()).isEqualTo(41L);
    }

    @Test
    void saved_env_wins_and_previous_is_ignored() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(env("DB_URL", "jdbc:saved", false), env("EXTRA", "x", false)),
                List.of(tpl("DB_URL", "DB 접속 URL", true, true), tpl("JWT_SECRET", "서명 키", true, true)),
                List.of(env("DB_URL", "jdbc:prev", false)),
                41L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "jdbc:saved", true, "DB 접속 URL", true, Source.SAVED, false),   // 비밀은 OR
                new Row("EXTRA", "x", false, "", false, Source.SAVED, false),
                new Row("JWT_SECRET", "", true, "서명 키", true, Source.TEMPLATE, false));
        assertThat(r.templateCount()).isEqualTo(2);
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void secret_flag_is_ored_between_template_and_previous() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("API_KEY", "", false, false)), List.of(env("API_KEY", "k", true)), 9L);

        assertThat(r.rows().get(0).secret()).isTrue();
    }

    @Test
    void blank_keys_are_ignored_everywhere_and_keys_are_trimmed() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(env("  ", "x", false)),     // 빈 KEY뿐 → 저장 env 없음으로 본다
                List.of(),
                List.of(env(" DB_URL ", "u", false), env("", "y", false)),
                5L);

        assertThat(r.rows()).containsExactly(new Row("DB_URL", "u", false, "", false, Source.PREVIOUS, true));
        assertThat(r.previousTaskId()).isEqualTo(5L);
    }

    @Test
    void previous_entries_with_empty_values_give_the_name_but_not_a_value() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("DB_URL", "", false, true)),
                List.of(env("DB_URL", "", false), env("OLD_ONLY", "", false)), 7L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "", false, "", true, Source.TEMPLATE, false),
                new Row("OLD_ONLY", "", false, "", false, Source.PREVIOUS, false));
        assertThat(r.previousTaskId()).isEqualTo(7L);   // KEY를 가져왔으므로
    }

    @Test
    void template_without_previous_has_no_previous_task_id() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("A", "", false, false)), List.of(), 3L);

        assertThat(r.rows()).containsExactly(new Row("A", "", false, "", false, Source.TEMPLATE, false));
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void nothing_to_suggest_yields_no_rows() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(null, null, null, null);

        assertThat(r.rows()).isEmpty();
        assertThat(r.templateCount()).isZero();
        assertThat(r.previousTaskId()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.DeployEnvSuggesterTest"`
Expected: FAIL — `package com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse does not exist`

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/dto/DeployEnvSuggestionResponse.java`:

```java
package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * GET /api/tasks/{id}/deploy-env 응답 — 배포 다이얼로그 미리 채움 (스펙 2026-10-07 §6.1).
 * 값은 평문(비밀 포함)이라 관리자 전용 엔드포인트에서만 내보낸다.
 *
 * @param templateCount  이 작업의 env 템플릿 항목 수
 * @param previousTaskId 값이나 KEY를 하나라도 직전 배포에서 가져왔을 때만 그 작업 id
 */
public record DeployEnvSuggestionResponse(List<Row> rows, int templateCount, Long previousTaskId) {

    /** KEY의 출처. */
    public enum Source { SAVED, TEMPLATE, PREVIOUS }

    /** @param valueFromPrevious 값을 직전 배포에서 가져왔는지 */
    public record Row(String key, String value, boolean secret, String description, boolean required,
                      Source source, boolean valueFromPrevious) {}
}
```

`src/main/java/com/hamonsoft/netismaker/service/DeployEnvSuggester.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 배포 다이얼로그 미리 채움 규칙 (스펙 2026-10-07 §6.3). 순수 함수 — 조회(직전 배포 찾기)는 TaskService가 한다.
 *
 *   저장된 env가 있으면: 저장값 그대로 + 템플릿에만 있는 KEY를 빈 값으로. 직전 배포는 보지 않는다.
 *   없으면(첫 배포):    템플릿 KEY에 직전 배포 값을 채우고, 직전 배포에만 있던 KEY를 덧붙인다.
 * 비밀 여부는 출처끼리 OR. 빈 KEY는 모든 입력에서 무시하고 KEY는 trim한다.
 */
public final class DeployEnvSuggester {

    private DeployEnvSuggester() {}

    public static DeployEnvSuggestionResponse suggest(List<EnvVar> saved, List<EnvTemplateItem> template,
                                                     List<EnvVar> previous, Long previousTaskId) {
        Map<String, EnvTemplateItem> tpl = new LinkedHashMap<>();
        if (template != null) {
            for (EnvTemplateItem i : template) {
                if (i != null && !i.key().isBlank()) tpl.putIfAbsent(i.key().trim(), i);
            }
        }
        Map<String, EnvVar> savedByKey = byKey(saved);
        List<Row> rows = new ArrayList<>();

        if (!savedByKey.isEmpty()) {
            for (EnvVar v : savedByKey.values()) {
                EnvTemplateItem t = tpl.get(v.key());
                rows.add(new Row(v.key(), v.value(), v.secret() || (t != null && t.secret()),
                        t == null ? "" : t.description(), t != null && t.required(), Source.SAVED, false));
            }
            for (Map.Entry<String, EnvTemplateItem> e : tpl.entrySet()) {
                if (savedByKey.containsKey(e.getKey())) continue;
                EnvTemplateItem t = e.getValue();
                rows.add(new Row(e.getKey(), "", t.secret(), t.description(), t.required(), Source.TEMPLATE, false));
            }
            return new DeployEnvSuggestionResponse(rows, tpl.size(), null);
        }

        Map<String, EnvVar> prev = byKey(previous);
        boolean usedPrevious = false;
        for (Map.Entry<String, EnvTemplateItem> e : tpl.entrySet()) {
            EnvTemplateItem t = e.getValue();
            EnvVar p = prev.get(e.getKey());
            boolean fromPrev = p != null && !p.value().isBlank();
            usedPrevious |= fromPrev;
            rows.add(new Row(e.getKey(), fromPrev ? p.value() : "", t.secret() || (p != null && p.secret()),
                    t.description(), t.required(), Source.TEMPLATE, fromPrev));
        }
        for (EnvVar p : prev.values()) {
            if (tpl.containsKey(p.key())) continue;
            usedPrevious = true;   // 직전 배포에서 KEY를 가져왔다
            boolean hasValue = !p.value().isBlank();
            rows.add(new Row(p.key(), hasValue ? p.value() : "", p.secret(), "", false, Source.PREVIOUS, hasValue));
        }
        return new DeployEnvSuggestionResponse(rows, tpl.size(), usedPrevious ? previousTaskId : null);
    }

    /** 빈 KEY를 버리고 KEY를 trim, 같은 KEY는 처음 것만 — 입력 순서 유지. */
    private static Map<String, EnvVar> byKey(List<EnvVar> vars) {
        Map<String, EnvVar> out = new LinkedHashMap<>();
        if (vars == null) return out;
        for (EnvVar v : vars) {
            if (v == null || v.key().isBlank()) continue;
            String key = v.key().trim();
            out.putIfAbsent(key, new EnvVar(key, v.value(), v.secret()));
        }
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.DeployEnvSuggesterTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
for f in src/main/java/com/hamonsoft/netismaker/dto/DeployEnvSuggestionResponse.java src/main/java/com/hamonsoft/netismaker/service/DeployEnvSuggester.java src/test/java/com/hamonsoft/netismaker/service/DeployEnvSuggesterTest.java; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file src/main/java/com/hamonsoft/netismaker/dto/DeployEnvSuggestionResponse.java src/main/java/com/hamonsoft/netismaker/service/DeployEnvSuggester.java src/test/java/com/hamonsoft/netismaker/service/DeployEnvSuggesterTest.java
git add src/main/java/com/hamonsoft/netismaker/dto/DeployEnvSuggestionResponse.java src/main/java/com/hamonsoft/netismaker/service/DeployEnvSuggester.java src/test/java/com/hamonsoft/netismaker/service/DeployEnvSuggesterTest.java
git commit -m "feat(api): 배포 다이얼로그 미리 채움 규칙(DeployEnvSuggester)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 직전 배포 조회 · `TaskService.deployEnvSuggestion`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployEnvTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/repository/TaskPreviousDeployIntegrationTest.java` (Testcontainers — CI에서 실행)

**Interfaces:**
- Consumes: `DeployEnvSuggester.suggest` (Task 6), `Task.getEnvTemplate` (Task 1), `RepoRef.fromSnapshot(String path, String gitUrl).host()`
- Produces: `List<Task> TaskRepository.findRecentlyDeployedWithEnv(String githubRepo, Long excludeId, String deployPending)`; `DeployEnvSuggestionResponse TaskService.deployEnvSuggestion(Long taskId)`(삭제/없음 → `TaskException` 404)

- [ ] **Step 1: 실패 테스트 작성 (단위)**

`src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployEnvTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 배포 다이얼로그 미리 채움 조회 (스펙 2026-10-07 §6.2). */
class TaskServiceDeployEnvTest {

    private TaskRepository taskRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(TaskStatusHistoryRepository.class), mock(McpCatalogService.class),
                mock(RepoCatalogService.class), new ObjectMapper(), mock(InterviewService.class),
                mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class));
    }

    private static Task task(long id, String repo, String gitUrl) {
        Task t = Task.create(repo, "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", id);
        t.setGitUrl(gitUrl);
        return t;
    }

    @Test
    void saved_env_skips_the_previous_deploy_lookup() {
        Task t = task(42L, "owner/repo", null);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:saved", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::value).containsExactly("jdbc:saved");
        verify(taskRepo, never()).findRecentlyDeployedWithEnv(any(), any(), any());
    }

    @Test
    void first_deploy_uses_the_most_recent_candidate_on_the_same_host() {
        Task t = task(42L, "group/app", "https://gitlab.hamon.vip/group/app.git");
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB", false, true))));
        Task github = task(40L, "group/app", null);   // 같은 경로지만 GitHub — 섞이면 안 된다
        github.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:github", false))));
        Task gitlab = task(39L, "group/app", "https://gitlab.hamon.vip/group/app.git");
        gitlab.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:gitlab", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        when(taskRepo.findRecentlyDeployedWithEnv("group/app", 42L, TaskStatus.DEPLOY_PENDING.dbValue()))
                .thenReturn(List.of(github, gitlab));

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::value).containsExactly("jdbc:gitlab");
        assertThat(r.previousTaskId()).isEqualTo(39L);
    }

    @Test
    void no_candidate_leaves_template_values_empty() {
        Task t = task(42L, "owner/repo", null);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB", false, true))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        when(taskRepo.findRecentlyDeployedWithEnv(any(), any(), any())).thenReturn(List.of());

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::key, Row::value).containsExactly(org.assertj.core.groups.Tuple.tuple("DB_URL", ""));
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void deleted_or_missing_task_is_404() {
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deployEnvSuggestion(42L))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceDeployEnvTest"`
Expected: FAIL — `cannot find symbol: method findRecentlyDeployedWithEnv` / `method deployEnvSuggestion`

- [ ] **Step 3: 구현**

`TaskRepository.java` — `findActiveByIdForUpdate` 선언 바로 뒤:

old:
```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Task> findActiveByIdForUpdate(@Param("id") Long id);
```
new:
```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Task> findActiveByIdForUpdate(@Param("id") Long id);

    /**
     * 같은 레포에서 env를 넣어 배포 요청한 다른 작업 — '배포대기' 진입이 최근인 순, 최대 20건 (스펙 2026-10-07 §6.2).
     * 배포 중지가 deployed_at을 지우므로 상태 이력으로 정렬한다. env_vars는 배포·재배포 요청 때만 저장되므로
     * "비어 있지 않음" = "배포 요청된 적 있음". 호스트(GitHub/GitLab) 비교는 호출부(TaskService)에서 한다.
     */
    @Query(value = """
        SELECT t.* FROM com.task t
        WHERE t.github_repo = :githubRepo
          AND t.id <> :excludeId
          AND t.deleted_at IS NULL
          AND t.env_vars <> CAST('[]' AS jsonb)
        ORDER BY (SELECT MAX(h.at) FROM com.task_status_history h
                  WHERE h.task_id = t.id AND h.to_status = :deployPending) DESC NULLS LAST,
                 t.id DESC
        LIMIT 20
        """, nativeQuery = true)
    List<Task> findRecentlyDeployedWithEnv(@Param("githubRepo") String githubRepo,
                                           @Param("excludeId") Long excludeId,
                                           @Param("deployPending") String deployPending);
```

`TaskService.java` — import:

old:
```java
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
```
new:
```java
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
```
old:
```java
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
```
new:
```java
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
```

메서드 — `undeploy` 주석 바로 앞:

old:
```java
    /** 배포완료/배포실패/배포중단됨 → 배포중지대기. 워커가 claim해 컨테이너 stop 후 PR생성 복귀. */
```
new:
```java
    /**
     * 배포 다이얼로그 미리 채움 (스펙 2026-10-07 §6). 저장된 env가 없을 때만 같은 레포(같은 호스트)의
     * 직전 배포를 찾아 값을 가져온다. 삭제된 작업은 404.
     */
    @Transactional(readOnly = true)
    public DeployEnvSuggestionResponse deployEnvSuggestion(Long taskId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        List<EnvVar> saved = t.getEnvVars() == null ? List.of() : t.getEnvVars();
        boolean hasSaved = saved.stream().anyMatch(v -> v != null && !v.key().isBlank());
        Task previous = hasSaved ? null : findPreviousDeploy(t);
        return DeployEnvSuggester.suggest(saved, t.getEnvTemplate(),
                previous == null ? List.of() : previous.getEnvVars(),
                previous == null ? null : previous.getId());
    }

    private Task findPreviousDeploy(Task t) {
        String host = RepoRef.fromSnapshot(t.getGithubRepo(), t.getGitUrl()).host();
        return taskRepo.findRecentlyDeployedWithEnv(t.getGithubRepo(), t.getId(),
                        TaskStatus.DEPLOY_PENDING.dbValue()).stream()
                .filter(c -> host.equals(RepoRef.fromSnapshot(c.getGithubRepo(), c.getGitUrl()).host()))
                .findFirst()
                .orElse(null);
    }

    /** 배포완료/배포실패/배포중단됨 → 배포중지대기. 워커가 claim해 컨테이너 stop 후 PR생성 복귀. */
```

- [ ] **Step 4: 통과 확인 (단위)**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceDeployEnvTest" --tests "com.hamonsoft.netismaker.service.TaskServiceDeployTest"`
Expected: PASS (실패 0)

- [ ] **Step 5: 통합 테스트 작성 (native 쿼리 · jsonb 매핑)**

`src/test/java/com/hamonsoft/netismaker/repository/TaskPreviousDeployIntegrationTest.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 직전 배포 조회 native 쿼리 + env_template jsonb 매핑 (스펙 2026-10-07 §3.2, §6.2). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskPreviousDeployIntegrationTest {

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    // catalog id=1 = V14 seed (alias 'Netis7.0') — 모두 같은 레포
    private Task prCreated() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "배포", "설명"), "user1");
        t.setStatus(TaskStatus.PR_CREATED);
        return taskRepo.save(t);
    }

    private static List<EnvVar> env(String value) {
        return List.of(new EnvVar("DB_URL", value, false));
    }

    @Test
    void 배포대기_이력이_최근인_순으로_같은_레포의_env_있는_작업만_돌려준다() throws Exception {
        Task a = prCreated();
        Task b = prCreated();
        prCreated();                       // 배포 요청 없음(env 빈 배열) — 제외
        Task deleted = prCreated();
        Task current = prCreated();

        taskService.deploy(a.getId(), "admin", env("jdbc:a"));
        Thread.sleep(5);
        taskService.deploy(b.getId(), "admin", env("jdbc:b"));
        taskService.deploy(deleted.getId(), "admin", env("jdbc:deleted"));
        Task del = taskRepo.findById(deleted.getId()).orElseThrow();
        ReflectionTestUtils.setField(del, "deletedAt", OffsetDateTime.now());
        taskRepo.save(del);
        // a를 나중에 재배포 → a가 가장 최근. 배포 중지로 deployed_at이 비어 있어도 이력으로 판정한다.
        Task a2 = taskRepo.findById(a.getId()).orElseThrow();
        a2.setStatus(TaskStatus.DEPLOY_FAILED);
        taskRepo.save(a2);
        Thread.sleep(5);
        taskService.redeploy(a.getId(), "admin", env("jdbc:a2"));

        List<Task> found = taskRepo.findRecentlyDeployedWithEnv(current.getGithubRepo(), current.getId(),
                TaskStatus.DEPLOY_PENDING.dbValue());

        assertThat(found).extracting(Task::getId).containsExactly(a.getId(), b.getId());
        assertThat(found.get(0).getDeployedAt()).isNull();
        assertThat(found.get(0).getEnvVars()).extracting(EnvVar::value).containsExactly("jdbc:a2");
    }

    @Test
    void env_template은_jsonb로_저장되고_그대로_읽힌다() {
        Task t = prCreated();
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true))));
        taskRepo.save(t);

        assertThat(taskRepo.findById(t.getId()).orElseThrow().getEnvTemplate())
                .containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }
}
```

Run(컴파일만 — 로컬은 RUN_TESTCONTAINERS 미설정으로 skip): `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.repository.TaskPreviousDeployIntegrationTest"`
Expected: BUILD SUCCESSFUL, 2 tests SKIPPED (CI `Backend (Gradle + Testcontainers)`에서 실행)

- [ ] **Step 6: 커밋**

```bash
for f in src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployEnvTest.java src/test/java/com/hamonsoft/netismaker/repository/TaskPreviousDeployIntegrationTest.java; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java src/main/java/com/hamonsoft/netismaker/service/TaskService.java src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployEnvTest.java src/test/java/com/hamonsoft/netismaker/repository/TaskPreviousDeployIntegrationTest.java
git add src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java src/main/java/com/hamonsoft/netismaker/service/TaskService.java src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployEnvTest.java src/test/java/com/hamonsoft/netismaker/repository/TaskPreviousDeployIntegrationTest.java
git commit -m "feat(api): 같은 레포 직전 배포 조회 + 배포 env 미리 채움 서비스" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: API 엔드포인트 · 비관리자 응답 비밀 값 비우기

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskResponseRedactTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskDeployEnvApiIntegrationTest.java` (Testcontainers — CI)

**Interfaces:**
- Consumes: `TaskService.deployEnvSuggestion` (Task 7), `DeployEnvSuggestionResponse` (Task 6)
- Produces: `GET /api/tasks/{id}/deploy-env`(ROLE_ADMIN) → `DeployEnvSuggestionResponse` JSON; `TaskResponse.redactSecretValues()`; 비관리자 응답의 `envVars[].value`(secret)=`""`

- [ ] **Step 1: 실패 테스트 작성 (단위)**

`src/test/java/com/hamonsoft/netismaker/dto/TaskResponseRedactTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 비관리자 응답의 비밀 env 값 비우기 (스펙 2026-10-07 §7). */
class TaskResponseRedactTest {

    @Test
    void secret_values_are_blanked_and_everything_else_kept() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 5L);
        t.setEnvVars(new ArrayList<>(List.of(
                new EnvVar("DB_URL", "jdbc:x", false),
                new EnvVar("DB_PASSWORD", "pw", true))));
        TaskResponse full = TaskResponse.of(t, null);

        TaskResponse redacted = full.redactSecretValues();

        assertThat(redacted.envVars()).containsExactly(
                new EnvVar("DB_URL", "jdbc:x", false),
                new EnvVar("DB_PASSWORD", "", true));
        assertThat(redacted).usingRecursiveComparison().ignoringFields("envVars").isEqualTo(full);
        assertThat(full.envVars().get(1).value()).isEqualTo("pw");   // 원본 불변
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.dto.TaskResponseRedactTest"`
Expected: FAIL — `cannot find symbol: method redactSecretValues()`

- [ ] **Step 3: 구현**

`TaskResponse.java` — `withTotalCost(...)` 메서드 바로 뒤(`private static TaskResponse build(` 앞):

old:
```java
    private static TaskResponse build(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId,
```
new:
```java
    /**
     * 비밀 env 값을 비운 복사본 — 관리자가 아닌 사용자 응답용 (스펙 2026-10-07 §7). 배포 다이얼로그가 같은 레포의
     * 직전 배포 값(비밀 포함)을 옮겨 오므로, 작업 등록자에게 다른 작업의 비밀이 보이지 않게 한다.
     */
    public TaskResponse redactSecretValues() {
        List<EnvVar> redacted = envVars == null ? List.of() : envVars.stream()
                .map(e -> e.secret() ? new EnvVar(e.key(), "", true) : e)
                .toList();
        return new TaskResponse(id, githubRepo, repoAlias, githubBranch, title, description, status, statusLabel,
                requesterId, retryCount, maxRetry, failureReason, mcpsExtra, redacted, createdAt, updatedAt,
                model, effort, designRequested, analysis, design, implementation, deployment, interviewSessionId,
                attachments, stageUsage, totalCostUsd, totalTokens);
    }

    private static TaskResponse build(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId,
```

`TaskController.java` — import:

old:
```java
import com.hamonsoft.netismaker.dto.DeployRequest;
```
new:
```java
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployRequest;
```

헬퍼 — 생성자 바로 뒤:

old:
```java
        this.deployLogStream = deployLogStream;
        this.interviewService = interviewService;
    }
```
new:
```java
        this.deployLogStream = deployLogStream;
        this.interviewService = interviewService;
    }

    /** 관리자가 아니면 비밀 env 값을 비운다 (스펙 2026-10-07 §7). 관리자 전용 엔드포인트에는 쓰지 않는다. */
    private static TaskResponse forViewer(TaskResponse r, boolean isAdmin) {
        return isAdmin ? r : r.redactSecretValues();
    }
```

등록(JSON):

old:
```java
        Task t = taskService.create(req, userId);
        TaskResponse body = TaskResponse.of(t, null);
```
new:
```java
        Task t = taskService.create(req, userId);
        TaskResponse body = forViewer(TaskResponse.of(t, null), AuthContext.isAdmin(auth));
```

등록(multipart):

old:
```java
        Task t = taskService.create(req, files, userId);
        TaskResponse body = TaskResponse.of(t, null);
```
new:
```java
        Task t = taskService.create(req, files, userId);
        TaskResponse body = forViewer(TaskResponse.of(t, null), AuthContext.isAdmin(auth));
```

목록:

old:
```java
            return total == null ? base : TaskResponse.withTotalCost(base, total);
```
new:
```java
            return forViewer(total == null ? base : TaskResponse.withTotalCost(base, total), isAdmin);
```

상세:

old:
```java
        Task t = taskService.getForView(id, userId, isAdmin);
        return TaskResponse.ofWithUsage(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null),
                interviewService.latestSessionIdForTask(id).orElse(null),
                taskService.getAttachments(id),
                taskService.getStageUsage(id));
```
new:
```java
        Task t = taskService.getForView(id, userId, isAdmin);
        return forViewer(TaskResponse.ofWithUsage(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null),
                interviewService.latestSessionIdForTask(id).orElse(null),
                taskService.getAttachments(id),
                taskService.getStageUsage(id)), isAdmin);
```

취소:

old:
```java
        Task t = taskService.cancel(id, userId, isAdmin);
        return TaskResponse.of(t, null);
```
new:
```java
        Task t = taskService.cancel(id, userId, isAdmin);
        return forViewer(TaskResponse.of(t, null), isAdmin);
```

재시도:

old:
```java
        Task t = taskService.retry(id, userId, isAdmin);
        return TaskResponse.of(t, null);
```
new:
```java
        Task t = taskService.retry(id, userId, isAdmin);
        return forViewer(TaskResponse.of(t, null), isAdmin);
```

엔드포인트 — `undeploy` 메서드 바로 뒤:

old:
```java
        taskService.undeploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }
```
new:
```java
        taskService.undeploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    /** 배포 다이얼로그 미리 채움 — 구현 시 추출한 이름 + 직전 배포 값(비밀 포함, 평문)이라 관리자 한정 (스펙 2026-10-07 §6). */
    @GetMapping("/{id}/deploy-env")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public DeployEnvSuggestionResponse deployEnv(@PathVariable Long id) {
        return taskService.deployEnvSuggestion(id);
    }
```

- [ ] **Step 4: 통과 확인 (단위)**

Run: `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.dto.TaskResponseRedactTest"`
Expected: PASS

- [ ] **Step 5: 통합 테스트 작성 (엔드포인트 권한 · 비우기)**

`src/test/java/com/hamonsoft/netismaker/controller/TaskDeployEnvApiIntegrationTest.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/tasks/{id}/deploy-env 권한·내용 + 비관리자 응답 비밀 값 비우기 (스펙 2026-10-07 §6, §7). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskDeployEnvApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // catalog id=1 = V14 seed (alias 'Netis7.0')
    private Task prCreated(String requester) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "배포", "설명"), requester);
        t.setStatus(TaskStatus.PR_CREATED);
        return taskRepo.save(t);
    }

    @Test
    void 관리자는_직전_배포_값으로_채운_추천을_받는다() throws Exception {
        Task prev = prCreated("user2");
        taskService.deploy(prev.getId(), "admin", List.of(
                new EnvVar("DB_URL", "jdbc:prev", false), new EnvVar("DB_PASSWORD", "pw", true)));
        Task cur = prCreated("user1");
        cur.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB 접속 URL", false, true))));
        taskRepo.save(cur);

        mvc.perform(get("/api/tasks/" + cur.getId() + "/deploy-env").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousTaskId").value(prev.getId().intValue()))
                .andExpect(jsonPath("$.templateCount").value(1))
                .andExpect(jsonPath("$.rows[0].key").value("DB_URL"))
                .andExpect(jsonPath("$.rows[0].value").value("jdbc:prev"))
                .andExpect(jsonPath("$.rows[0].source").value("TEMPLATE"))
                .andExpect(jsonPath("$.rows[0].description").value("DB 접속 URL"))
                .andExpect(jsonPath("$.rows[1].key").value("DB_PASSWORD"))
                .andExpect(jsonPath("$.rows[1].value").value("pw"))
                .andExpect(jsonPath("$.rows[1].source").value("PREVIOUS"));
    }

    @Test
    void 일반_사용자는_추천을_볼_수_없다() throws Exception {
        Task cur = prCreated("user1");

        mvc.perform(get("/api/tasks/" + cur.getId() + "/deploy-env").with(userJwt("user1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일반_사용자_응답에서는_비밀_값이_비고_관리자는_그대로_본다() throws Exception {
        Task cur = prCreated("user1");
        taskService.deploy(cur.getId(), "admin", List.of(
                new EnvVar("DB_URL", "jdbc:x", false), new EnvVar("DB_PASSWORD", "pw", true)));

        mvc.perform(get("/api/tasks/" + cur.getId()).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envVars[0].value").value("jdbc:x"))
                .andExpect(jsonPath("$.envVars[1].value").value(""));
        mvc.perform(get("/api/tasks").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].envVars[1].value").value(""));
        mvc.perform(get("/api/tasks/" + cur.getId()).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envVars[1].value").value("pw"));
    }
}
```

Run(로컬은 skip, 컴파일 확인): `export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.controller.TaskDeployEnvApiIntegrationTest" --tests "com.hamonsoft.netismaker.dto.TaskResponseRedactTest"`
Expected: BUILD SUCCESSFUL — Redact PASS, 통합 3개 SKIPPED(CI에서 실행)

- [ ] **Step 6: 커밋**

```bash
for f in src/test/java/com/hamonsoft/netismaker/dto/TaskResponseRedactTest.java src/test/java/com/hamonsoft/netismaker/controller/TaskDeployEnvApiIntegrationTest.java; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java src/main/java/com/hamonsoft/netismaker/controller/TaskController.java src/test/java/com/hamonsoft/netismaker/dto/TaskResponseRedactTest.java src/test/java/com/hamonsoft/netismaker/controller/TaskDeployEnvApiIntegrationTest.java
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java src/main/java/com/hamonsoft/netismaker/controller/TaskController.java src/test/java/com/hamonsoft/netismaker/dto/TaskResponseRedactTest.java src/test/java/com/hamonsoft/netismaker/controller/TaskDeployEnvApiIntegrationTest.java
git commit -m "feat(api): 배포 env 미리 채움 API(관리자) + 비관리자 응답의 비밀 값 비움" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 프론트 — 판정 순수 함수 (`deployEnvForm.ts`)

**Files:**
- Create: `frontend/composables/deployEnvForm.ts`
- Test: `frontend/test/deploy-env-form.spec.ts`

**Interfaces:**
- Consumes: Task 8 응답 형식(`rows[].{key,value,secret,description,required,source,valueFromPrevious}`, `templateCount`, `previousTaskId`)
- Produces: 타입 `EnvVar`, `EnvRowSource`, `SuggestedRow`, `DeployEnvSuggestion`, `EnvRow`; 함수 `isSuggestion(v)`, `rowsFromSuggestion(s, nextId)`, `rowsFromEnvVars(envVars, nextId)`, `blankRow(nextId)`, `toWireEnvVars(rows)`, `missingRequired(rows)`, `suggestionCaption(s)`

- [ ] **Step 0: 워크트리에서 프론트 테스트 준비 (정션)**

PowerShell:
```powershell
if (-not (Test-Path C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\deploy-env-template\frontend\node_modules)) { New-Item -ItemType Junction -Path C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\deploy-env-template\frontend\node_modules -Target C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules }
```
확인: `ls frontend/node_modules/.bin/ | grep -c vitest` → 1 이상. ⚠️ 워크트리 제거 전 `cmd //c "rmdir frontend\\node_modules"`(링크만 삭제).

- [ ] **Step 1: 실패 테스트 작성**

`frontend/test/deploy-env-form.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import {
  blankRow,
  isSuggestion,
  missingRequired,
  rowsFromEnvVars,
  rowsFromSuggestion,
  suggestionCaption,
  toWireEnvVars,
  type DeployEnvSuggestion,
  type EnvRow,
} from '../composables/deployEnvForm'

const seq = () => {
  let n = 0
  return () => n++
}

const suggestion: DeployEnvSuggestion = {
  rows: [
    { key: 'DB_URL', value: 'jdbc:prev', secret: false, description: 'DB 접속 URL', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'JWT_SECRET', value: '', secret: true, description: '서명 키', required: true, source: 'TEMPLATE', valueFromPrevious: false },
  ],
  templateCount: 2,
  previousTaskId: 41,
}

describe('deployEnvForm (스펙 2026-10-07 §8)', () => {
  it('추천 응답 판별 — rows 배열이 있어야 한다', () => {
    expect(isSuggestion(suggestion)).toBe(true)
    expect(isSuggestion({ id: 42, envVars: [] })).toBe(false) // 작업 응답 같은 엉뚱한 응답
    expect(isSuggestion(null)).toBe(false)
  })

  it('서버 행은 KEY 고정 + 설명·필수·직전 배포 표식을 싣는다', () => {
    const rows = rowsFromSuggestion(suggestion, seq())
    expect(rows.map((r) => [r.id, r.key, r.fixedKey, r.required, r.valueFromPrevious])).toEqual([
      [0, 'DB_URL', true, true, true],
      [1, 'JWT_SECRET', true, true, false],
    ])
    expect(rows[0]!.description).toBe('DB 접속 URL')
    expect(rows[1]!.secret).toBe(true)
  })

  it('대체 경로(저장 env)와 새 행은 KEY를 편집할 수 있다', () => {
    const rows = rowsFromEnvVars([{ key: 'A', value: '1', secret: true }], seq())
    expect(rows[0]).toMatchObject({ key: 'A', value: '1', secret: true, fixedKey: false, required: false })
    expect(rowsFromEnvVars(null, seq())).toEqual([])
    expect(blankRow(seq())).toMatchObject({ key: '', value: '', fixedKey: false })
  })

  it('전송 형식은 KEY를 trim하고 빈 KEY·빈 값 행을 뺀다(값은 그대로)', () => {
    const rows: EnvRow[] = [
      { ...blankRow(seq()), key: ' DB_URL ', value: ' jdbc:x ' },
      { ...blankRow(seq()), key: 'EMPTY', value: '   ' },
      { ...blankRow(seq()), key: '', value: 'orphan' },
      { ...blankRow(seq()), key: 'PW', value: 'p w', secret: true },
    ]
    expect(toWireEnvVars(rows)).toEqual([
      { key: 'DB_URL', value: ' jdbc:x ', secret: false },
      { key: 'PW', value: 'p w', secret: true },
    ])
  })

  it('필수인데 값이 빈 KEY 목록', () => {
    const rows = rowsFromSuggestion(suggestion, seq())
    expect(missingRequired(rows)).toEqual(['JWT_SECRET'])
    rows[1]!.value = 's'
    expect(missingRequired(rows)).toEqual([])
  })

  it('상단 안내 문구', () => {
    expect(suggestionCaption(suggestion)).toBe('구현 시 추출한 변수 2개 · task #41 배포에서 값을 가져왔습니다')
    expect(suggestionCaption({ rows: [], templateCount: 0, previousTaskId: 41 })).toBe('task #41 배포에서 값을 가져왔습니다')
    expect(suggestionCaption({ rows: [], templateCount: 0, previousTaskId: null })).toBeNull()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run test/deploy-env-form.spec.ts`
Expected: FAIL — `Failed to resolve import "../composables/deployEnvForm"`

- [ ] **Step 3: 구현**

`frontend/composables/deployEnvForm.ts`:

```ts
// 배포 다이얼로그(DeployEnvDialog)의 순수 판정 함수 (스펙 2026-10-07 §8). 화면 상태와 떼어 단위 테스트한다.

export interface EnvVar {
  key: string
  value: string
  secret: boolean
}

export type EnvRowSource = 'SAVED' | 'TEMPLATE' | 'PREVIOUS'

export interface SuggestedRow extends EnvVar {
  description: string
  required: boolean
  source: EnvRowSource
  valueFromPrevious: boolean
}

/** GET /api/tasks/{id}/deploy-env 응답 (스펙 §6.1). */
export interface DeployEnvSuggestion {
  rows: SuggestedRow[]
  templateCount: number
  previousTaskId: number | null
}

/** 편집용 행 — fixedKey면 KEY를 라벨로만 보여 준다(서버가 채운 행). id는 v-for의 안정 키. */
export interface EnvRow extends EnvVar {
  id: number
  reveal: boolean
  fixedKey: boolean
  description: string
  required: boolean
  valueFromPrevious: boolean
}

export function isSuggestion(v: unknown): v is DeployEnvSuggestion {
  return !!v && typeof v === 'object' && Array.isArray((v as DeployEnvSuggestion).rows)
}

export function rowsFromSuggestion(s: DeployEnvSuggestion, nextId: () => number): EnvRow[] {
  return s.rows.map((r) => ({
    key: r.key,
    value: r.value ?? '',
    secret: !!r.secret,
    id: nextId(),
    reveal: false,
    fixedKey: true,
    description: r.description ?? '',
    required: !!r.required,
    valueFromPrevious: !!r.valueFromPrevious,
  }))
}

/** 추천을 못 받았을 때(대체 경로) — 저장된 env를 지금처럼 KEY까지 편집 가능한 행으로. */
export function rowsFromEnvVars(envVars: EnvVar[] | null | undefined, nextId: () => number): EnvRow[] {
  return (envVars ?? []).map((e) => ({
    key: e.key,
    value: e.value,
    secret: e.secret,
    id: nextId(),
    reveal: false,
    fixedKey: false,
    description: '',
    required: false,
    valueFromPrevious: false,
  }))
}

export function blankRow(nextId: () => number): EnvRow {
  return {
    key: '',
    value: '',
    secret: false,
    id: nextId(),
    reveal: false,
    fixedKey: false,
    description: '',
    required: false,
    valueFromPrevious: false,
  }
}

const isEmpty = (s: string) => s.trim() === ''

/** 전송 형식 — KEY는 trim, 빈 KEY·빈 값 행은 뺀다(빈 문자열이 앱 기본값을 덮지 않게). 값은 그대로. */
export function toWireEnvVars(rows: EnvRow[]): EnvVar[] {
  return rows
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '' && !isEmpty(r.value))
}

/** 필수인데 값이 빈 행의 KEY 목록 — 막지 않고 "그래도 배포"로 경고만. */
export function missingRequired(rows: EnvRow[]): string[] {
  return rows.filter((r) => r.required && isEmpty(r.value)).map((r) => r.key)
}

/** 상단 안내 — 추출 개수·직전 배포 출처. 둘 다 없으면 null(기본 안내만). */
export function suggestionCaption(s: DeployEnvSuggestion): string | null {
  const parts: string[] = []
  if (s.templateCount > 0) parts.push(`구현 시 추출한 변수 ${s.templateCount}개`)
  if (s.previousTaskId != null) parts.push(`task #${s.previousTaskId} 배포에서 값을 가져왔습니다`)
  return parts.length ? parts.join(' · ') : null
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && npx vitest run test/deploy-env-form.spec.ts`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
for f in frontend/composables/deployEnvForm.ts frontend/test/deploy-env-form.spec.ts; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file frontend/composables/deployEnvForm.ts frontend/test/deploy-env-form.spec.ts
git add frontend/composables/deployEnvForm.ts frontend/test/deploy-env-form.spec.ts
git commit -m "feat(front): 배포 env 다이얼로그 판정 순수 함수(deployEnvForm)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 프론트 — `DeployEnvDialog.vue` · 작업 상세 연결

**Files:**
- Create: `frontend/components/tasks/DeployEnvDialog.vue`
- Modify: `frontend/pages/tasks/[id].vue` (스크립트의 배포 다이얼로그 상태·함수 블록, 템플릿의 `<q-dialog v-model="envDialog">` 블록)
- Test: `frontend/test/deploy-env-dialog.spec.ts`

**Interfaces:**
- Consumes: `deployEnvForm.ts` 전부 (Task 9), `GET /api/tasks/{id}/deploy-env` (Task 8), 기존 `POST /api/tasks/{id}/deploy|redeploy` `{ envVars }`
- Produces: `<DeployEnvDialog v-model :task-id="string" :mode="'deploy'|'redeploy'" :saved-env-vars="EnvVar[]" @submitted>`; 열릴 때마다 추천을 새로 불러옴; 노출 상태 `rows`, `submit()`(테스트가 `wrapper.vm`으로 사용)

- [ ] **Step 1: 실패 테스트 작성**

`frontend/test/deploy-env-dialog.spec.ts`:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import DeployEnvDialog from '../components/tasks/DeployEnvDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

const suggestion = {
  rows: [
    { key: 'DB_URL', value: 'jdbc:prev', secret: false, description: 'DB 접속 URL', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'JWT_SECRET', value: '', secret: true, description: '서명 키', required: true, source: 'TEMPLATE', valueFromPrevious: false },
  ],
  templateCount: 2,
  previousTaskId: 41,
}

function mountDialog(props: { mode?: 'deploy' | 'redeploy'; savedEnvVars?: Array<{ key: string; value: string; secret: boolean }> } = {}) {
  return mount(DeployEnvDialog, {
    attachTo: document.body,
    props: { modelValue: true, taskId: '42', mode: 'deploy', savedEnvVars: [], ...props },
  })
}

const body = () => document.body
const keyLabels = () =>
  [...body().querySelectorAll('[data-test="env-key-label"]')].map((e) => e.textContent?.trim())

describe('DeployEnvDialog (스펙 2026-10-07 §8)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('추천을 불러와 KEY는 라벨로, 설명·필수·직전 배포 표식과 상단 안내를 보여 준다', async () => {
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/deploy-env')
    expect(keyLabels()).toEqual(['DB_URL', 'JWT_SECRET'])
    expect(body().querySelectorAll('[data-test="env-key-input"]').length).toBe(0)
    expect(body().textContent).toContain('DB 접속 URL')
    expect(body().textContent).toContain('직전 배포 값')
    expect(body().textContent).toContain('배포 — 환경변수')
    expect(body().querySelector('[data-test="deploy-env-caption"]')?.textContent).toContain('task #41')
    w.unmount()
  })

  it('필수 값이 비면 경고와 "그래도 배포", 전송은 빈 값 행을 뺀다', async () => {
    useApiMock.mockResolvedValueOnce(suggestion).mockResolvedValueOnce({})
    const w = mountDialog()
    await flushPromises()
    expect(body().querySelector('[data-test="env-missing-required"]')?.textContent).toContain('JWT_SECRET')
    expect(body().querySelector('[data-test="env-submit"]')?.textContent).toContain('그래도 배포')
    await (w.vm as any).submit()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/tasks/42/deploy', {
      method: 'POST',
      body: { envVars: [{ key: 'DB_URL', value: 'jdbc:prev', secret: false }] },
    })
    expect(w.emitted('submitted')).toBeTruthy()
    expect(w.emitted('update:modelValue')?.[0]).toEqual([false])
    w.unmount()
  })

  it('필수 값을 모두 채우면 경고가 사라지고 버튼은 평소 문구', async () => {
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog({ mode: 'redeploy' })
    await flushPromises()
    ;(w.vm as any).rows[1].value = 'sign-key'
    await flushPromises()
    expect(body().querySelector('[data-test="env-missing-required"]')).toBeNull()
    expect(body().querySelector('[data-test="env-submit"]')?.textContent?.trim()).toBe('재배포')
    w.unmount()
  })

  it('추천을 못 받으면 저장된 env로 채우고 비밀 값도 그대로 다시 보낸다', async () => {
    useApiMock.mockRejectedValueOnce(new Error('500')).mockResolvedValueOnce({})
    const w = mountDialog({ mode: 'redeploy', savedEnvVars: [{ key: 'DB_PASSWORD', value: 'pw', secret: true }] })
    await flushPromises()
    expect(body().querySelectorAll('[data-test="env-key-input"]').length).toBe(1) // 대체 경로는 KEY 편집 가능
    await (w.vm as any).submit()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/tasks/42/redeploy', {
      method: 'POST',
      body: { envVars: [{ key: 'DB_PASSWORD', value: 'pw', secret: true }] },
    })
    w.unmount()
  })

  it('엉뚱한 응답(rows 없음)도 대체 경로로', async () => {
    useApiMock.mockResolvedValueOnce({ id: 42, envVars: [] })
    const w = mountDialog({ savedEnvVars: [{ key: 'A', value: '1', secret: false }] })
    await flushPromises()
    expect((w.vm as any).rows.map((r: any) => r.key)).toEqual(['A'])
    w.unmount()
  })

  it('닫았다 다시 열면 추천을 새로 불러온다', async () => {
    useApiMock.mockResolvedValue(suggestion)
    const w = mountDialog()
    await flushPromises()
    ;(w.vm as any).rows[0].value = '고친 값'
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(2)
    expect((w.vm as any).rows[0].value).toBe('jdbc:prev')
    w.unmount()
  })

  it('늦게 도착한 이전 응답은 새로 연 다이얼로그를 덮지 않는다', async () => {
    let resolveFirst!: (v: unknown) => void
    useApiMock
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()
    resolveFirst({
      rows: [{ key: 'STALE', value: 'x', secret: false, description: '', required: false, source: 'TEMPLATE', valueFromPrevious: false }],
      templateCount: 1,
      previousTaskId: null,
    })
    await flushPromises()
    expect((w.vm as any).rows.map((r: any) => r.key)).toEqual(['DB_URL', 'JWT_SECRET'])
    w.unmount()
  })

  it('390px에서는 행 입력이 세로로 쌓인다', async () => {
    await setViewportWidth(390)
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await flushPromises()
    expect(body().querySelector('[data-test="env-row"] .column')).not.toBeNull()
    w.unmount()
    await setViewportWidth(1024)
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run test/deploy-env-dialog.spec.ts`
Expected: FAIL — `Failed to resolve import "../components/tasks/DeployEnvDialog.vue"`

- [ ] **Step 3: 컴포넌트 구현**

`frontend/components/tasks/DeployEnvDialog.vue`:

```vue
<script setup lang="ts">
// 배포/재배포 환경변수 다이얼로그 (스펙 2026-10-07 §8). 열 때마다 서버 추천(구현 시 추출한 이름 + 같은 레포
// 직전 배포 값)을 새로 불러오고, 실패하면 저장된 env로 채운다. 판정은 composables/deployEnvForm.ts 순수 함수.
import { useQuasar } from 'quasar'
import {
  blankRow,
  isSuggestion,
  missingRequired,
  rowsFromEnvVars,
  rowsFromSuggestion,
  suggestionCaption,
  toWireEnvVars,
  type EnvRow,
  type EnvVar,
} from '~/composables/deployEnvForm'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  mode: 'deploy' | 'redeploy'
  savedEnvVars: EnvVar[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

const $q = useQuasar()
const rows = ref<EnvRow[]>([])
const caption = ref<string | null>(null)
const loading = ref(false)
const submitting = ref(false)
let rowSeq = 0
const nextId = () => rowSeq++
let loadSeq = 0

const actionLabel = computed(() => (props.mode === 'deploy' ? '배포' : '재배포'))
const missing = computed(() => missingRequired(rows.value))
const submitLabel = computed(() =>
  missing.value.length ? `그래도 ${actionLabel.value}` : actionLabel.value,
)

async function load() {
  const token = ++loadSeq
  loading.value = true
  rows.value = []
  caption.value = null
  let next: EnvRow[] = []
  let nextCaption: string | null = null
  let failed = false
  try {
    const res = await useApi(`/api/tasks/${props.taskId}/deploy-env`)
    if (!isSuggestion(res)) throw new Error('unexpected deploy-env response')
    next = rowsFromSuggestion(res, nextId)
    nextCaption = suggestionCaption(res)
  } catch {
    failed = true
  }
  if (token !== loadSeq) return // 닫았다 다시 연 뒤 늦게 도착한 이전 응답 — 새 결과를 덮지 않는다
  if (failed) {
    next = rowsFromEnvVars(props.savedEnvVars, nextId)
    $q.notify({ type: 'warning', message: '추천 값을 불러오지 못해 저장된 값으로 채웠습니다' })
  }
  rows.value = next
  caption.value = nextCaption
  loading.value = false
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void load()
  },
  { immediate: true },
)

function addRow() {
  rows.value.push(blankRow(nextId))
}

function removeRow(id: number) {
  rows.value = rows.value.filter((r) => r.id !== id)
}

async function submit() {
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/${props.mode}`, {
      method: 'POST',
      body: { envVars: toWireEnvVars(rows.value) },
    })
    $q.notify({ type: 'positive', message: `${actionLabel.value} 큐 등록` })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? `${actionLabel.value} 실패` })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-dialog
    :model-value="modelValue"
    :maximized="$q.screen.lt.md"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <q-card style="width: min(560px, 100vw)" data-test="deploy-env-dialog">
      <div class="dialog-body">
        <q-card-section class="row items-center no-wrap">
          <div class="text-h6">{{ actionLabel }} — 환경변수</div>
          <q-space />
          <q-btn v-close-popup flat round dense icon="close" aria-label="닫기" />
        </q-card-section>
        <q-card-section class="q-pt-none text-caption text-grey-7">
          <div v-if="caption" class="text-primary q-mb-xs" data-test="deploy-env-caption">{{ caption }}</div>
          <div>
            컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. 값이 빈 변수는 보내지 않습니다.
            비밀 값은 마스킹 표시되지만 평문 저장됩니다.
          </div>
        </q-card-section>
        <q-card-section v-if="loading" class="row justify-center">
          <q-spinner size="28px" color="primary" />
        </q-card-section>
        <q-card-section v-else class="q-gutter-y-md">
          <div v-for="row in rows" :key="row.id" data-test="env-row">
            <div v-if="row.fixedKey" class="row items-center q-gutter-x-sm">
              <code class="env-key" data-test="env-key-label">{{ row.key }}</code>
              <q-badge v-if="row.required" color="orange-8" label="필수" />
              <q-badge v-if="row.valueFromPrevious" outline color="grey-7" label="직전 배포 값" />
            </div>
            <div
              v-if="row.fixedKey && row.description"
              class="text-caption text-grey-7"
              data-test="env-description"
            >
              {{ row.description }}
            </div>
            <div :class="$q.screen.lt.md ? 'column q-gutter-y-xs' : 'row items-center q-gutter-xs no-wrap'">
              <q-input
                v-if="!row.fixedKey"
                v-model="row.key"
                :dense="!$q.screen.lt.md"
                outlined
                placeholder="KEY"
                style="flex: 1"
                data-test="env-key-input"
              />
              <q-input
                v-model="row.value"
                :dense="!$q.screen.lt.md"
                outlined
                :placeholder="row.fixedKey ? '값' : 'value'"
                style="flex: 2"
                :type="row.secret && !row.reveal ? 'password' : 'text'"
                data-test="env-value-input"
              >
                <template v-if="row.secret" #append>
                  <q-icon
                    :name="row.reveal ? 'visibility_off' : 'visibility'"
                    class="cursor-pointer"
                    @click="row.reveal = !row.reveal"
                  />
                </template>
              </q-input>
              <div class="row items-center no-wrap">
                <q-toggle v-model="row.secret" label="비밀" dense />
                <q-btn flat round dense icon="delete" color="grey" aria-label="변수 삭제" @click="removeRow(row.id)" />
              </div>
            </div>
          </div>
          <q-btn flat dense icon="add" label="변수 추가" data-test="env-add" @click="addRow" />
        </q-card-section>
      </div>
      <q-card-section
        v-if="missing.length"
        class="q-py-xs text-caption text-warning"
        data-test="env-missing-required"
      >
        필수 변수 {{ missing.length }}개가 비어 있습니다: {{ missing.join(', ') }}
      </q-card-section>
      <q-card-actions align="right">
        <q-btn v-close-popup flat label="취소" />
        <q-btn
          unelevated
          :color="missing.length ? 'warning' : 'primary'"
          :label="submitLabel"
          :loading="submitting"
          :disable="loading"
          data-test="env-submit"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.env-key {
  font-weight: 600;
  word-break: break-all;
}
</style>
```

- [ ] **Step 4: 다이얼로그 테스트 통과 확인**

Run: `cd frontend && npx vitest run test/deploy-env-dialog.spec.ts`
Expected: PASS (8 tests)

- [ ] **Step 5: 작업 상세에 연결**

`frontend/pages/tasks/[id].vue` — import(다른 tasks 컴포넌트 import 옆):

old:
```ts
import TaskHistoryTimeline from '~/components/tasks/TaskHistoryTimeline.vue'
```
new:
```ts
import TaskHistoryTimeline from '~/components/tasks/TaskHistoryTimeline.vue'
import DeployEnvDialog from '~/components/tasks/DeployEnvDialog.vue'
```

스크립트 블록 교체:

old:
```ts
// 편집용 행: 와이어 포맷(EnvVar)에 UI 전용 상태(reveal) + 안정적 key(id)를 더한다.
// id는 v-for의 stable key로 써서 행 삭제 시 입력/마스킹 상태가 어긋나지 않게 한다.
interface EnvRow extends EnvVar {
  id: number
  reveal: boolean
}

const envDialog = ref(false)
const envMode = ref<'deploy' | 'redeploy'>('deploy')
const envRows = ref<EnvRow[]>([])
let envRowSeq = 0

function openDeployDialog(mode: 'deploy' | 'redeploy') {
  envMode.value = mode
  envRows.value = (task.value?.envVars ?? []).map((e) => ({
    ...e,
    id: envRowSeq++,
    reveal: false,
  }))
  envDialog.value = true
}

function addEnvRow() {
  envRows.value.push({
    key: '',
    value: '',
    secret: false,
    id: envRowSeq++,
    reveal: false,
  })
}

function removeEnvRow(id: number) {
  envRows.value = envRows.value.filter((r) => r.id !== id)
}

async function submitDeploy() {
  const endpoint = envMode.value === 'deploy' ? 'deploy' : 'redeploy'
  // UI 전용 필드(id/reveal)는 제외하고 와이어 포맷만 전송.
  const envVars = envRows.value
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '')
  try {
    await useApi(`/api/tasks/${taskId.value}/${endpoint}`, {
      method: 'POST',
      body: { envVars },
    })
    $q.notify({
      type: 'positive',
      message: envMode.value === 'deploy' ? '배포 큐 등록' : '재배포 큐 등록',
    })
    envDialog.value = false
    refresh()
  } catch (e: any) {
    $q.notify({
      type: 'negative',
      message:
        e?.data?.message ??
        (envMode.value === 'deploy' ? '배포 실패' : '재배포 실패'),
    })
  }
}
```
new:
```ts
// 배포/재배포 환경변수 다이얼로그 (스펙 2026-10-07) — 추천 불러오기·전송은 DeployEnvDialog가 맡는다.
const envDialog = ref(false)
const envMode = ref<'deploy' | 'redeploy'>('deploy')

function openDeployDialog(mode: 'deploy' | 'redeploy') {
  envMode.value = mode
  envDialog.value = true
}
```

템플릿 블록 교체:

old:
```vue
      <q-dialog v-model="envDialog" :maximized="$q.screen.lt.md">
        <q-card style="width: min(480px, 100vw)">
          <div class="dialog-body">
            <q-card-section class="row items-center">
              <div class="text-h6">
                {{ envMode === 'deploy' ? '배포' : '재배포' }} — 환경변수
              </div>
              <q-space />
              <q-btn v-close-popup flat round dense icon="close" />
            </q-card-section>
            <q-card-section class="text-caption text-grey-7">
              컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. DB 접속
              정보·시크릿을 여기에 입력하세요. (예:
              <code>SPRING_DATASOURCE_URL</code>, <code>JWT_SECRET</code>) 비밀
              값은 마스킹 표시되지만 평문 저장됩니다.
            </q-card-section>
            <q-card-section class="q-gutter-sm">
              <div
                v-for="row in envRows"
                :key="row.id"
                :class="$q.screen.lt.md ? 'column q-gutter-y-xs' : 'row items-center q-gutter-xs no-wrap'"
              >
                <q-input
                  v-model="row.key"
                  dense
                  outlined
                  placeholder="KEY"
                  style="flex: 1"
                />
                <q-input
                  v-model="row.value"
                  dense
                  outlined
                  placeholder="value"
                  style="flex: 2"
                  :type="row.secret && !row.reveal ? 'password' : 'text'"
                >
                  <template v-if="row.secret" #append>
                    <q-icon
                      :name="row.reveal ? 'visibility_off' : 'visibility'"
                      class="cursor-pointer"
                      @click="row.reveal = !row.reveal"
                    />
                  </template>
                </q-input>
                <q-toggle v-model="row.secret" label="비밀" dense />
                <q-btn
                  flat
                  round
                  dense
                  icon="delete"
                  color="grey"
                  @click="removeEnvRow(row.id)"
                />
              </div>
              <q-btn flat dense icon="add" label="변수 추가" @click="addEnvRow" />
            </q-card-section>
          </div>
          <q-card-actions align="right">
            <q-btn v-close-popup flat label="취소" />
            <q-btn
              unelevated
              color="primary"
              :label="envMode === 'deploy' ? '배포' : '재배포'"
              @click="submitDeploy"
            />
          </q-card-actions>
        </q-card>
      </q-dialog>
```
new:
```vue
      <DeployEnvDialog
        v-model="envDialog"
        :task-id="taskId"
        :mode="envMode"
        :saved-env-vars="task.envVars ?? []"
        @submitted="refresh"
      />
```

확인: `grep -n "envRows\|submitDeploy\|addEnvRow\|removeEnvRow\|EnvRow" "frontend/pages/tasks/[id].vue"` → 출력 없음.

- [ ] **Step 6: 상세 화면 회귀 + 타입체크**

Run: `cd frontend && npx vitest run test/deploy-env-dialog.spec.ts test/deploy-env-form.spec.ts test/task-detail-progress.spec.ts test/task-detail-mobile.spec.ts test/task-detail-approve.spec.ts test/task-detail-usage.spec.ts test/task-detail-attachments.spec.ts`
Expected: PASS (실패 0) — 기존 테스트는 `deploy-env` 호출이 작업 응답을 받아 대체 경로로 열리고 제목 `배포 — 환경변수`/`재배포 — 환경변수`를 그대로 찾는다.

Run: `cd frontend && npx nuxi prepare && npx nuxi typecheck`
Expected: 오류 0

- [ ] **Step 7: 커밋**

```bash
for f in frontend/components/tasks/DeployEnvDialog.vue frontend/test/deploy-env-dialog.spec.ts; do sed -i 's/\r$//; s/$/\r/' "$f"; done
file frontend/components/tasks/DeployEnvDialog.vue frontend/test/deploy-env-dialog.spec.ts "frontend/pages/tasks/[id].vue"
git add frontend/components/tasks/DeployEnvDialog.vue frontend/test/deploy-env-dialog.spec.ts "frontend/pages/tasks/[id].vue"
git commit -m "feat(front): 배포 다이얼로그 분리 — 추천 이름 고정 라벨·직전 배포 값·필수 경고" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 문서 · 전체 검증 · 실화면 확인

**Files:**
- Modify: `CLAUDE.md` (자주 보는 코드 표 1행, 환경 메모 1항목)

**Interfaces:**
- Consumes: Task 1–10 결과물
- Produces: 문서 갱신 + 검증 기록(커밋 메시지·PR 본문에 쓸 숫자)

- [ ] **Step 1: CLAUDE.md 갱신 (Edit 도구)**

"자주 보는 코드" 표에서 `| SSR 하이드레이션 안전 인증 게이트`로 시작하는 행 **바로 앞**에 행 추가:

```markdown
| 배포 환경변수 미리 채움 (구현 시 Claude가 남긴 `.netis-deploy-env.json` → `task.env_template`, 같은 레포 직전 배포 값, 비관리자 응답 비밀 값 비움) | `workerdaemon/DeployEnvManifest.java`·`WorkerMainLoop.DEPLOY_ENV_INSTRUCTION`, `service/DeployEnvSuggester.java`·`TaskService.deployEnvSuggestion`(`GET /api/tasks/{id}/deploy-env`, 관리자), `TaskResponse.redactSecretValues`, `frontend/components/tasks/DeployEnvDialog.vue` + `composables/deployEnvForm.ts` |
```

"환경 메모"에서 `- **첨부 정리(API)**`로 시작하는 항목 **바로 뒤**에 추가:

```markdown
- **배포 환경변수 미리 채움(2026-10-07, 스펙 `docs/superpowers/specs/2026-10-07-deploy-env-template-design.md`)**: 구현 세션이 레포 루트 `.netis-deploy-env.json`에 env **이름**만 남기면 워커가 커밋 전에 읽고 지워 `PR_CREATED` 보고로 올리고, API가 `task.env_template`(V25)에 저장한다(목록이 없거나 틀려도 구현은 계속). 배포 다이얼로그는 `GET /api/tasks/{id}/deploy-env`(관리자)로 저장 env → 템플릿 + 같은 레포·같은 호스트 직전 배포(`배포대기` 이력 최신순) 값 순으로 채운다. 직전 배포의 **비밀 값도 복사**하므로 비관리자 작업 응답은 비밀 값을 비운다. 값이 빈 행은 전송하지 않는다(빈 문자열이 앱 기본값을 덮지 않게). 배포 순서: API → 워커 → 프론트.
```

- [ ] **Step 2: 백엔드 전체 컴파일 + 관련 테스트 일괄**

Run:
```bash
export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew compileJava compileTestJava
export JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1; ./gradlew test --tests "com.hamonsoft.netismaker.entity.EnvTemplateItemTest" --tests "com.hamonsoft.netismaker.workerdaemon.DeployEnvManifestTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestMaskTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestJsonTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDeployEnvTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDesignDirTest" --tests "com.hamonsoft.netismaker.workerdaemon.ResultReporterTest" --tests "com.hamonsoft.netismaker.workerdaemon.DeadLetterReplayJobTest" --tests "com.hamonsoft.netismaker.workerdaemon.SilentLossTrackerTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceEnvTemplateTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceReconcileTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceStageUsageTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceDesignResultTest" --tests "com.hamonsoft.netismaker.service.DeployEnvSuggesterTest" --tests "com.hamonsoft.netismaker.service.TaskServiceDeployEnvTest" --tests "com.hamonsoft.netismaker.service.TaskServiceDeployTest" --tests "com.hamonsoft.netismaker.dto.TaskResponseRedactTest" --tests "com.hamonsoft.netismaker.repository.TaskPreviousDeployIntegrationTest" --tests "com.hamonsoft.netismaker.controller.TaskDeployEnvApiIntegrationTest"
```
Expected: BUILD SUCCESSFUL, 실패 0 (심볼릭 링크 1건·Testcontainers 5건 SKIPPED는 정상 — CI에서 실행). 통과/skip 수를 기록.

- [ ] **Step 3: 프론트 전체 vitest + 타입체크**

Run: `cd frontend && npx vitest run && npx nuxi typecheck`
Expected: 실패 0, 타입 오류 0. 통과 수를 기록.

- [ ] **Step 4: 실화면 확인 (커밋하지 않는 임시 목 API)**

스크래치패드에 `mock-api.mjs` 작성(커밋 안 함, 워크트리 밖):

```js
import http from 'node:http'

const task = {
  id: 42, githubRepo: 'acme/widgets', repoAlias: 'Widgets', githubBranch: 'main', title: '배포 env 확인', description: '설명',
  status: 'PR_CREATED', statusLabel: 'PR생성', requesterId: 'user1', retryCount: 0, maxRetry: 3, failureReason: null,
  mcpsExtra: [], envVars: [], interviewSessionId: null, createdAt: '2026-10-07T00:00:00Z', updatedAt: '2026-10-07T00:00:00Z',
  model: 'claude-opus-5-5', effort: 'high', designRequested: false,
  analysis: { markdownResult: '# 요약', subtasksJson: '[]', durationMs: 1000, approved: true, approvedBy: null, approvedAt: null, completedAt: '2026-10-07T00:00:00Z' },
  design: null,
  implementation: { prUrl: 'https://github.com/acme/widgets/pull/1', prNumber: 1, headBranch: 'feature/x', headSha: 'abcdef1234567', implementationLog: null },
  deployment: null, attachments: [], stageUsage: [], totalCostUsd: 0.42, totalTokens: 123000,
}
const suggestion = {
  rows: [
    { key: 'SPRING_DATASOURCE_URL', value: 'jdbc:postgresql://db.internal:5432/netis', secret: false, description: 'DB 접속 JDBC URL', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'SPRING_DATASOURCE_PASSWORD', value: 'pw', secret: true, description: 'DB 접속 암호', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'JWT_SECRET', value: '', secret: true, description: '토큰 서명 키 — 아주 긴 설명이 줄바꿈되는지 보려고 길게 씁니다', required: true, source: 'TEMPLATE', valueFromPrevious: false },
    { key: 'A_VERY_LONG_ENVIRONMENT_VARIABLE_NAME_FOR_WRAP_CHECK', value: 'x', secret: false, description: '', required: false, source: 'PREVIOUS', valueFromPrevious: true },
  ],
  templateCount: 3,
  previousTaskId: 41,
}
http.createServer((req, res) => {
  const url = (req.url ?? '').split('?')[0]
  let body = {}
  if (url === '/api/tasks/42') body = task
  else if (url === '/api/tasks/42/deploy-env') body = suggestion
  else if (url.endsWith('/history') || url.endsWith('/interviews')) body = []
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify(body))
}).listen(18999, () => console.log('mock api :18999'))
```

백그라운드 실행(둘 다 Bash `run_in_background`):
```bash
node "C:/Users/mic/AppData/Local/Temp/claude/C--Users-mic-NetisMaker/8b202bdd-5662-4abd-a9b1-d7ac5e583fb3/scratchpad/mock-api.mjs"
cd frontend && NUXT_API_PROXY_TARGET=http://127.0.0.1:18999 npx nuxi dev --port 3101
```
(라이브 API `:8090`에는 절대 붙지 않도록 프록시 대상을 목으로 바꾼다.)

브라우저(내장 Browser 창):
1. `http://localhost:3101/login` 이동 → JS 실행:
   `localStorage.setItem('netis-maker-auth', JSON.stringify({ accessToken: 'dev', refreshToken: null, idToken: null, expiresAt: Date.now() + 3600000, me: { user_id: 'admin', authorities: ['ROLE_ADMIN'] } }))`
2. `http://localhost:3101/tasks/42` 이동 → `[data-test="next-action-primary"]`(배포) 클릭 → 1280px 스크린샷: KEY 라벨 4개·설명·`필수`/`직전 배포 값` 표식·상단 안내·`그래도 배포`(JWT_SECRET 비어 있음) 확인.
3. 창 폭 390(`resize_window` preset mobile) → 새로고침 → 배포 클릭 → 스크린샷: 전체화면 다이얼로그, 행이 세로로 쌓임, 긴 KEY 줄바꿈, JS로 `document.documentElement.scrollWidth <= window.innerWidth` 가 `true`.
4. 창 폭 원복(`resize_window` preset desktop).

끝나면 두 백그라운드 작업을 멈추고(TaskStop) 스크래치패드 목 파일은 그대로 둔다(커밋 대상 아님).

- [ ] **Step 5: 커밋**

```bash
file CLAUDE.md
git add CLAUDE.md
git commit -m "docs: 배포 환경변수 미리 채움 — CLAUDE.md 자주 보는 코드·환경 메모" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git log --oneline origin/main..HEAD
```

(이후 PR 생성·병합은 superpowers:finishing-a-development-branch로 사용자에게 확인받아 진행한다. 워크트리를 지울 때는 먼저 `cmd //c "rmdir frontend\\node_modules"`.)
