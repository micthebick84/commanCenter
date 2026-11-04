# 종료 산출물 납품 파일(zip 등) 보관 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포 이미지의 `/opt/netis-artifact/`에 레포가 둔 납품 파일 하나(.war .jar .zip .tar.gz .tgz)를 종료 때 그대로 보관하고, 미리 빌드 레포는 워커가 그 파일을 이미지에 넣어 준다.

**Architecture:** 확장자·경로 규칙을 `util/ArtifactNames` 한 곳에 두고 API(`ArtifactStore`)와 워커(`ArtifactPublisher`·`DeployPrebuilder`)가 같이 쓴다. 종료 때 워커는 카탈로그 경로가 비면 `/opt/netis-artifact/`를 WAR 검색보다 먼저 본다. 배포 때 워커는 미리 빌드가 `.netis-artifact/`에 남긴 파일을 `.netis-prebuild/artifact/`로 옮기고 생성 Dockerfile 끝에 COPY를 붙이며, 모든 `docker build`에 `NETIS_GIT_SHA` 빌드 인자를 준다. DB 변경 없음.

**Tech Stack:** Java 21 · Spring Boot(API·워커 프로파일) · JUnit 5 + AssertJ + Mockito · Nuxt 3 + Quasar · vitest

**Spec:** `docs/superpowers/specs/2026-11-04-close-artifact-bundle-design.md`

## Global Constraints

- 허용 확장자: `.war` `.jar` `.zip` `.tar.gz` `.tgz`(판정은 대소문자 무시, 보관 이름은 소문자). 사람에게 보이는 목록 표기: `.war·.jar·.zip·.tar.gz·.tgz`
- 이미지 안 납품 폴더: `/opt/netis-artifact` (상수, 설정값 아님)
- 미리 빌드 쪽 폴더: 명령이 쓰는 곳 `.netis-artifact/`, 스테이징 `.netis-prebuild/artifact/`
- 생성 Dockerfile에 붙이는 두 줄(문구 그대로):
  `# 종료 산출물 납품 파일 (Symphony 워커가 붙임)` / `COPY .netis-prebuild/artifact/ /opt/netis-artifact/`
- 빌드 인자 이름: `NETIS_GIT_SHA`(값 = head_sha 전체, head_sha가 없으면 넣지 않음)
- 보관 이름 규칙은 그대로 `{레포 마지막 조각}-task-{id}-{sha7}.{확장자}`
- DB 마이그레이션 없음. 단일 WAR 레포의 판정·이름·로그는 지금과 같아야 한다
- 테스트는 클래스 이름으로 돌린다(Git Bash glob이 폴더로 확장되는 문제): `./gradlew test --tests 'com.hamonsoft.netismaker.<패키지>.<클래스>'`. JDK 21(`JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1`)
- 코드 스타일: 한국어 주석·한국어 테스트 메서드 이름(주변 파일 관례), 사람에게 보이는 실패 문구는 아래 각 작업에 적힌 그대로

## Review Focus

- 카탈로그 경로가 대문자 확장자(`/app/ROOT.WAR`)면 보관 이름은 소문자 `.war`여야 하고 API가 받는다 → Task 2 `파일_이름은_확장자를_소문자로_쓴다`
- 이미지 `/opt/netis-artifact/` 아래에 하위 폴더만 있으면(바로 아래 파일 0개) WAR 검색으로 넘어가야 한다 — `find -maxdepth 1 -type f`가 폴더를 안 내므로 빈 출력과 같다 → Task 2 `납품_폴더가_비면_WAR_검색으로_넘어간다`
- 미리 빌드 `.netis-artifact/`에 파일 대신 폴더만 있으면 "파일 없음"으로 배포실패 → Task 3 `납품_폴더에_하위_폴더만_있으면_파일_없음으로_실패한다`
- 생성 Dockerfile이 줄바꿈 없이 끝나도 COPY 줄이 새 줄에서 시작해야 한다 → Task 4 `납품_COPY는_마지막_줄바꿈이_없어도_새_줄에서_시작한다`
- head_sha가 없는 배포(`latest` 이미지)는 빌드 인자를 넣지 않는다 → Task 5 `head_sha가_없으면_빌드_인자를_넣지_않는다`

---

### Task 1: 공용 규칙 `ArtifactNames` + API 이름 검사

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/util/ArtifactNames.java`
- Create: `src/test/java/com/hamonsoft/netismaker/util/ArtifactNamesTest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/ArtifactStore.java:33-36` (`validName`)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/CloseArtifactService.java:51-53` (400 문구)
- Modify: `src/test/java/com/hamonsoft/netismaker/service/ArtifactStoreTest.java:28-35`

**Interfaces:**
- Produces:
  - `public final class ArtifactNames` (util, 프로파일 없음)
  - `public static final String IMAGE_DIR = "/opt/netis-artifact";`
  - `public static final String ALLOWED_LABEL = ".war·.jar·.zip·.tar.gz·.tgz";`
  - `public static Optional<String> extension(String name)` — 대소문자 무시로 끝을 보고 소문자 확장자(`"war"`, `"jar"`, `"zip"`, `"tar.gz"`, `"tgz"`)를 돌려준다. `tar.gz`를 `gz`보다 먼저 본다. null·해당 없음은 empty.

- [ ] **Step 1: 실패하는 테스트 작성** — `ArtifactNamesTest`

```java
@Test
void 확장자는_대소문자_무시로_찾고_소문자로_돌려준다() {
    assertThat(ArtifactNames.extension("a.war")).contains("war");
    assertThat(ArtifactNames.extension("/x/ROOT.WAR")).contains("war");
    assertThat(ArtifactNames.extension("b.jar")).contains("jar");
    assertThat(ArtifactNames.extension("knu-wars.zip")).contains("zip");
    assertThat(ArtifactNames.extension("netis-dash.TAR.GZ")).contains("tar.gz");
    assertThat(ArtifactNames.extension("x.tgz")).contains("tgz");
}

@Test
void 목록_밖_확장자는_비어_있다() {
    for (String n : new String[]{"x.gz", "x.tar", "x.tar.gzx", "x.zipx", "x.ear", "war", "", null}) {
        assertThat(ArtifactNames.extension(n)).as(n).isEmpty();
    }
}
```

`ArtifactStoreTest`의 `이름은_허용_문자이고_war나_jar로_끝나야_한다`를 `이름은_허용_문자이고_허용_확장자로_끝나야_한다`로 바꾸고 단언을 다음으로:

```java
assertThat(ArtifactStore.validName("netis7.0-task-9-abcdef1.jar")).isTrue();
assertThat(ArtifactStore.validName("w-task-1-abcdef1.war")).isTrue();
assertThat(ArtifactStore.validName("netisdash_knu-task-28-f4000f8.zip")).isTrue();
assertThat(ArtifactStore.validName("k-task-32-1a2b3c4.tar.gz")).isTrue();
assertThat(ArtifactStore.validName("k-task-32-1a2b3c4.tgz")).isTrue();
assertThat(ArtifactStore.validName("w-task-1-abcdef1.WAR")).isFalse();   // 워커는 소문자로 보낸다
assertThat(ArtifactStore.validName("a.gz")).isFalse();
assertThat(ArtifactStore.validName("a.tar")).isFalse();
assertThat(ArtifactStore.validName(".zip")).isFalse();
assertThat(ArtifactStore.validName("../x.war")).isFalse();
assertThat(ArtifactStore.validName("a b.war")).isFalse();
assertThat(ArtifactStore.validName(null)).isFalse();
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.util.ArtifactNamesTest' --tests 'com.hamonsoft.netismaker.service.ArtifactStoreTest'`
Expected: 컴파일 실패(`ArtifactNames` 없음)

- [ ] **Step 3: 구현**
  - `ArtifactNames` 위 Interfaces 그대로. 확장자 목록은 긴 것부터(`tar.gz`, `tgz`, `zip`, `war`, `jar`) — 이름이 `.` + 확장자로 끝나는지 `toLowerCase(Locale.ROOT)`로 본다. 점 하나만 있는 이름(`.zip`)도 extension은 `zip`을 돌려주고, 점으로 시작 금지는 `validName`이 따로 막는다.
  - `ArtifactStore.validName`: 기존 문자 규칙·점 시작 금지 + `ArtifactNames.extension(name).filter(e -> name.endsWith("." + e)).isPresent()`(소문자 끝만 통과).
  - `CloseArtifactService` 400 문구: `"산출물 이름은 [A-Za-z0-9._-]이고 " + ArtifactNames.ALLOWED_LABEL + "로 끝나야 합니다: " + name`
  - `ArtifactStore` 클래스 주석의 "WAR·JAR" 언급이 있으면 같은 목록으로.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.util.ArtifactNamesTest' --tests 'com.hamonsoft.netismaker.service.ArtifactStoreTest' --tests 'com.hamonsoft.netismaker.service.CloseArtifactServiceTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/util/ArtifactNames.java src/test/java/com/hamonsoft/netismaker/util/ArtifactNamesTest.java src/main/java/com/hamonsoft/netismaker/service/ArtifactStore.java src/main/java/com/hamonsoft/netismaker/service/CloseArtifactService.java src/test/java/com/hamonsoft/netismaker/service/ArtifactStoreTest.java
git commit -m "feat(artifact): 산출물 허용 확장자에 zip·tar.gz·tgz 추가"
```

---

### Task 2: 워커 산출물 꺼내기 — 납품 폴더 우선·확장자 확장

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisher.java` (`resolvePath` 138-159, `fileName` 188-196, 클래스 주석)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactProperties.java` (주석의 search-dirs 설명에 "납품 폴더가 없을 때" 추가)
- Modify: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisherTest.java`

**Interfaces:**
- Consumes: `ArtifactNames.IMAGE_DIR`, `ArtifactNames.extension(String)`, `ArtifactNames.ALLOWED_LABEL` (Task 1)
- Produces: `static List<String> bundleFiles(String stdout)` — find 출력에서 `ArtifactNames.IMAGE_DIR + "/"`로 시작하는 줄만(strip·중복 제거, 순서 유지). 기존 `candidates`·`fileName` 시그니처는 그대로.

판정 순서(스펙 §5.1):
1. 카탈로그 경로 있음 → 그 경로.
2. 없음 → `docker run --rm --entrypoint find <이미지> /opt/netis-artifact -mindepth 1 -maxdepth 1 -type f` → `bundleFiles`: 1개면 그것, 2개 이상이면 실패, 0개면 3으로.
3. 지금의 WAR·JAR 검색(인자·문구 그대로).
4. 고른 경로의 확장자가 `ArtifactNames.extension`에 없으면 실패.

실패 문구(그대로):
- 확장자: `"산출물: " + ArtifactNames.ALLOWED_LABEL + "만 보관합니다: " + path`
- 여러 개: `"산출물: /opt/netis-artifact/에 파일이 여러 개입니다 — 하나만 두거나 카탈로그에 경로를 지정하세요(후보: " + String.join(", ", files) + ")"`

- [ ] **Step 1: 테스트 고치고 추가**

가짜 docker의 `run` 분기를 인자로 가른다 — 필드 `private String bundleOut = "";` 추가:

```java
case "run" -> { return new ProcessRunner.Result(1, args.contains(ArtifactNames.IMAGE_DIR) ? bundleOut : findOut); }
```

기존 테스트 수정:
- `경로가_없으면_find_결과가_하나일때_그것을_쓴다`: `calls.get(1)`은 납품 폴더 조회
  `List.of("run", "--rm", "--entrypoint", "find", "netis-task-7:abcdef1", "/opt/netis-artifact", "-mindepth", "1", "-maxdepth", "1", "-type", "f")`,
  기존 WAR 검색 단언은 `calls.get(2)`로.
- `war나_jar가_아닌_경로는_거절한다` → `허용_밖_확장자는_거절한다`: `/app/x.ear` → `hasMessage("산출물: .war·.jar·.zip·.tar.gz·.tgz만 보관합니다: /app/x.ear")`.

새 테스트:

```java
@Test
void 납품_폴더에_파일이_하나면_WAR_검색_없이_그것을_올린다() throws Exception {
    bundleOut = "/opt/netis-artifact/knu-wars.zip\n";
    findOut = "/usr/local/tomcat/webapps/ROOT.war\n";
    publisher().publish(task(null, "product/netis/web/dashboard/netisdash_knu"));
    assertThat(calls).noneMatch(c -> c.contains("*.war"));
    assertThat(calls).anyMatch(c -> c.equals(List.of("cp", "netis-artifact-7:/opt/netis-artifact/knu-wars.zip", c.get(2))));
    verify(http).uploadArtifact(eq(7L), eq("netisdash_knu-task-7-abcdef1.zip"),
            eq("netis-task-7:abcdef1:/opt/netis-artifact/knu-wars.zip"), any(), anyString(), any());
}

@Test
void 납품_폴더가_비면_WAR_검색으로_넘어간다() throws Exception {
    bundleOut = "find: '/opt/netis-artifact': No such file or directory\n";
    findOut = "/usr/local/tomcat/webapps/ROOT.war\n";
    publisher().publish(task(null, "acme/w"));
    verify(http).uploadArtifact(eq(7L), eq("w-task-7-abcdef1.war"), anyString(), any(), anyString(), any());
}

@Test
void 납품_폴더에_파일이_여러_개면_후보를_나열해_실패한다() {
    bundleOut = "/opt/netis-artifact/a.zip\n/opt/netis-artifact/b.zip\n";
    assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
            .isInstanceOf(ArtifactPublisher.ArtifactException.class)
            .hasMessage("산출물: /opt/netis-artifact/에 파일이 여러 개입니다 — 하나만 두거나 카탈로그에 경로를 지정하세요(후보: /opt/netis-artifact/a.zip, /opt/netis-artifact/b.zip)");
}

@Test
void 납품_폴더_파일의_확장자가_목록_밖이면_실패한다() {
    bundleOut = "/opt/netis-artifact/readme.txt\n";
    assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
            .hasMessage("산출물: .war·.jar·.zip·.tar.gz·.tgz만 보관합니다: /opt/netis-artifact/readme.txt");
}

@Test
void 카탈로그_경로가_zip이면_그대로_올린다() throws Exception {
    publisher().publish(task("/opt/x/bundle.tar.gz", "acme/w"));
    assertThat(calls).noneMatch(c -> c.get(0).equals("run"));
    verify(http).uploadArtifact(eq(7L), eq("w-task-7-abcdef1.tar.gz"), anyString(), any(), anyString(), any());
}

@Test
void 파일_이름은_확장자를_소문자로_쓴다() {
    assertThat(ArtifactPublisher.fileName(task(null, "acme/w"), "/app/ROOT.WAR")).isEqualTo("w-task-7-abcdef1.war");
    assertThat(ArtifactPublisher.fileName(task(null, "acme/w"), "/opt/netis-artifact/B.TGZ")).isEqualTo("w-task-7-abcdef1.tgz");
    assertThat(ArtifactPublisher.bundleFiles("x\n/opt/netis-artifact/a.zip\n/opt/netis-artifact/a.zip\n/app/b.war\n"))
            .containsExactly("/opt/netis-artifact/a.zip");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ArtifactPublisherTest'`
Expected: 새 테스트·고친 테스트 FAIL(호출 목록·문구 불일치)

- [ ] **Step 3: 구현** — 위 판정 순서·문구대로 `resolvePath`를 고치고 `bundleFiles`를 추가. `fileName`의 확장자는 `ArtifactNames.extension(path).orElseThrow()`(경로 판정을 통과한 뒤에만 불린다). 납품 폴더 조회도 기존 `run(…, SHORT_TIMEOUT_SECONDS)`·종료 코드 무시 규칙을 따른다. 클래스 주석의 "WAR·JAR"를 "납품 파일(WAR·JAR·zip 등)"로.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ArtifactPublisherTest'`
Expected: PASS(기존 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisher.java src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactProperties.java src/test/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisherTest.java
git commit -m "feat(artifact): 종료 때 이미지 /opt/netis-artifact/ 납품 파일을 먼저 본다"
```

---

### Task 3: 미리 빌드 납품 파일 스테이징 (`DeployPrebuilder`)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilder.java` (상수, `Staged`, `run` 끝 132-151, `promptSection` 243-251)
- Modify: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilderTest.java`

**Interfaces:**
- Consumes: `ArtifactNames.extension`, `ArtifactNames.ALLOWED_LABEL` (Task 1)
- Produces:
  - `static final String DELIVERY_SOURCE_DIR = ".netis-artifact";`
  - `static final String DELIVERY_STAGE_DIR = STAGE_DIR + "/artifact";` (`.netis-prebuild/artifact`)
  - `public record Staged(String fileName, long sizeBytes, String sourceRelative, String deliveryName)` — `deliveryName`은 납품 파일이 없으면 null. 기존 3인자 생성자를 유지한다: `public Staged(String fileName, long sizeBytes, String sourceRelative) { this(fileName, sizeBytes, sourceRelative, null); }`(기존 테스트·`DeployServiceBaseImageTest`가 3인자로 만든다).
  - `static Optional<String> stageDelivery(Path worktree, Consumer<String> sink) throws PrebuildException` — `run`이 배포 산출물 복사 뒤 부른다.

동작(스펙 §6.1):
- `worktree/.netis-artifact`가 디렉터리가 아니면(없음 포함) `Optional.empty()`.
- 바로 아래 **일반 파일**(링크 따라가지 않음, 하위 폴더 무시)이 정확히 1개이고 확장자가 목록 안이면 `worktree/.netis-prebuild/artifact/<같은 이름>`으로 복사, sink에 `"[미리 빌드 납품] " + name + " (" + "%.1f" MB + "MB) → .netis-prebuild/artifact/"`, `Optional.of(name)`.
- 아니면 `PrebuildException("미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(" + 사유 + ")")` — 사유: `파일 없음` / `여러 개: a.zip, b.zip`(이름순, 기존 `MAX_LISTED` 규칙) / `확장자 밖: x.txt — .war·.jar·.zip·.tar.gz·.tgz`. `run`은 이 예외에 출력 꼬리를 붙여 다시 던진다(`resolveArtifact` 실패와 같은 방식).
- `promptSection` 끝에 한 줄: `"- `.netis-prebuild/artifact/`가 있으면 워커가 COPY를 따로 붙입니다. Dockerfile에서 다루지 마세요.\n"`

- [ ] **Step 1: 실패하는 테스트 작성** (`DeployPrebuilderTest`, `@TempDir root`를 worktree로)

```java
@Test
void 납품_폴더가_없으면_비어_있다() throws Exception {
    assertThat(DeployPrebuilder.stageDelivery(root, l -> {})).isEmpty();
}

@Test
void 납품_파일_하나를_스테이징한다() throws Exception {
    Files.createDirectories(root.resolve(".netis-artifact"));
    Files.writeString(root.resolve(".netis-artifact/knu-wars.zip"), "zip");
    List<String> out = new CopyOnWriteArrayList<>();
    assertThat(DeployPrebuilder.stageDelivery(root, out::add)).contains("knu-wars.zip");
    assertThat(root.resolve(".netis-prebuild/artifact/knu-wars.zip")).hasContent("zip");
    assertThat(out).containsExactly("[미리 빌드 납품] knu-wars.zip (0.0MB) → .netis-prebuild/artifact/");
}

@Test
void 납품_폴더에_하위_폴더만_있으면_파일_없음으로_실패한다() throws Exception {
    Files.createDirectories(root.resolve(".netis-artifact/sub"));
    Files.writeString(root.resolve(".netis-artifact/sub/a.zip"), "x");
    assertThatThrownBy(() -> DeployPrebuilder.stageDelivery(root, l -> {}))
            .isInstanceOf(PrebuildException.class)
            .hasMessage("미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(파일 없음)");
}

@Test
void 납품_파일이_여러_개면_실패한다() throws Exception {
    Files.createDirectories(root.resolve(".netis-artifact"));
    Files.writeString(root.resolve(".netis-artifact/b.zip"), "b");
    Files.writeString(root.resolve(".netis-artifact/a.zip"), "a");
    assertThatThrownBy(() -> DeployPrebuilder.stageDelivery(root, l -> {}))
            .hasMessage("미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(여러 개: a.zip, b.zip)");
}

@Test
void 납품_파일_확장자가_목록_밖이면_실패한다() throws Exception {
    Files.createDirectories(root.resolve(".netis-artifact"));
    Files.writeString(root.resolve(".netis-artifact/x.txt"), "x");
    assertThatThrownBy(() -> DeployPrebuilder.stageDelivery(root, l -> {}))
            .hasMessage("미리 빌드 납품 파일은 .netis-artifact/ 바로 아래 하나여야 함(확장자 밖: x.txt — .war·.jar·.zip·.tar.gz·.tgz)");
}
```

`run` 통합 확인 하나 — 기존 `makeWarCommand()` 패턴처럼 OS별 명령이 `t/a.war`와 `.netis-artifact/d.zip`을 만들게 하고 `Staged.deliveryName()`이 `"d.zip"`, 납품 폴더 없는 기존 `run` 테스트는 `deliveryName()`이 null인지 단언을 하나 더한다. `promptSection_본문`에 `.contains("`.netis-prebuild/artifact/`가 있으면 워커가 COPY를 따로 붙입니다.")` 추가.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.DeployPrebuilderTest'`
Expected: 컴파일 실패(`stageDelivery` 없음)

- [ ] **Step 3: 구현** — Interfaces·동작 그대로. 목록은 `Files.newDirectoryStream` + `Files.isRegularFile(p, NOFOLLOW_LINKS)`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.DeployPrebuilderTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServicePrebuildTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServiceBaseImageTest'`
Expected: PASS(3인자 `Staged`를 쓰는 기존 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilder.java src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilderTest.java
git commit -m "feat(prebuild): 미리 빌드가 남긴 .netis-artifact/ 납품 파일을 스테이징"
```

---

### Task 4: 생성 Dockerfile에 납품 COPY 붙이기 (`DockerfileSupport`·`DeployService`)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupport.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:127-149`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilder.java` (`dockerfileUsesDeliveryStage`)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupportTest.java`, `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployServicePrebuildTest.java`

**Interfaces:**
- Consumes: `Staged.deliveryName()`, `DeployPrebuilder.DELIVERY_STAGE_DIR` (Task 3), `ArtifactNames.IMAGE_DIR` (Task 1)
- Produces:
  - `public static final String ARTIFACT_COPY_BLOCK = "# 종료 산출물 납품 파일 (Symphony 워커가 붙임)\nCOPY .netis-prebuild/artifact/ /opt/netis-artifact/\n";` (DockerfileSupport)
  - `public static String appendArtifactCopy(String dockerfileContent)` — 줄바꿈 규칙은 `appendJavaLocale`과 같고, 언제나 붙인다(생성 Dockerfile 전용).
  - `static boolean dockerfileUsesDeliveryStage(String dockerfileText)` (DeployPrebuilder) — 본문에 `.netis-prebuild/artifact`가 있는지.

DeployService 연결(스펙 §6.2·§6.4):
- 생성 분기: 로케일 붙이기 **뒤**, `.dockerignore` 쓰기 앞 또는 뒤(순서 무관)에 `staged != null && staged.deliveryName() != null`이면 파일 끝에 붙이고 로그 `[납품 파일] COPY .netis-prebuild/artifact/ → /opt/netis-artifact/ 추가`.
- 레포 Dockerfile 분기: 같은 조건에서 `!dockerfileUsesDeliveryStage(본문)`이면 로그 `[경고] Dockerfile이 .netis-prebuild/artifact/를 쓰지 않음 — 납품 파일이 이미지에 들어가지 않음`.

- [ ] **Step 1: 실패하는 테스트 작성**

`DockerfileSupportTest`:

```java
@Test
void 납품_COPY는_파일_끝에_붙는다() {
    assertThat(DockerfileSupport.appendArtifactCopy("FROM a AS b\nFROM tomcat\nCMD [\"run\"]\n"))
            .isEqualTo("FROM a AS b\nFROM tomcat\nCMD [\"run\"]\n" + DockerfileSupport.ARTIFACT_COPY_BLOCK);
}

@Test
void 납품_COPY는_마지막_줄바꿈이_없어도_새_줄에서_시작한다() {
    assertThat(DockerfileSupport.appendArtifactCopy("FROM x\nCMD [\"run\"]"))
            .isEqualTo("FROM x\nCMD [\"run\"]\n" + DockerfileSupport.ARTIFACT_COPY_BLOCK);
}
```

`DeployServicePrebuildTest` — 상수 `STAGED_WITH_DELIVERY = new DeployPrebuilder.Staged("hm-dash-daegu.war", 1024, "hm-dash/target/hm-dash-daegu.war", "knu-wars.zip")`와 그것을 돌려주는 준비 메서드를 더하고:

```java
@Test
void 생성한_Dockerfile에_납품_파일이_있으면_로케일_뒤에_COPY를_붙인다() throws Exception {
    // claude가 COPY .netis-prebuild/hm-dash-daegu.war 만 있는 Dockerfile을 만든다고 두고 deploy
    // 단언: 최종 Dockerfile이 ARTIFACT_COPY_BLOCK으로 끝나고, JAVA_LOCALE_BLOCK이 그 앞에 있다
    //       로그에 "[납품 파일] COPY .netis-prebuild/artifact/ → /opt/netis-artifact/ 추가", "[경고]" 없음
}

@Test
void 납품_파일이_없으면_COPY를_붙이지_않는다() throws Exception {
    // 기존 STAGED(3인자)로 생성 경로 → Dockerfile에 ".netis-prebuild/artifact" 없음, 로그에 "[납품 파일]" 없음
}

@Test
void 레포_Dockerfile이_납품_폴더를_안_쓰면_경고만_한다() throws Exception {
    // 레포 Dockerfile "FROM x\nCOPY .netis-prebuild/hm-dash-daegu.war /a.war\n" + STAGED_WITH_DELIVERY
    // 단언: Dockerfile 본문 그대로, 로그에 "[경고] Dockerfile이 .netis-prebuild/artifact/를 쓰지 않음 — 납품 파일이 이미지에 들어가지 않음"
}
```

(주석 자리는 이 파일의 `생성한_Dockerfile이면_dockerignore를_나중에_쓴다`·`레포_Dockerfile이면_dockerignore를_건드리지_않는다`와 같은 준비·단언 모양으로 채운다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupportTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServicePrebuildTest'`
Expected: FAIL

- [ ] **Step 3: 구현** — Interfaces·연결 규칙 그대로.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupportTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServicePrebuildTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServiceJavaLocaleTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServiceBaseImageTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupport.java src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilder.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupportTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployServicePrebuildTest.java
git commit -m "feat(deploy): 생성 Dockerfile에 납품 파일 COPY를 붙인다"
```

---

### Task 5: `docker build`에 `NETIS_GIT_SHA` 빌드 인자

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java:49-57` (`DeploySpec`)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java:102-105`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:164-` (spec 생성)
- Modify: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetRunArgsTest.java`, `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DnsRegisteringDeployTargetTest.java:96`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTargetBuildArgsTest.java` (새 파일)

**Interfaces:**
- Produces:
  - `DeploySpec`에 마지막 구성요소 `Map<String, String> buildArgs` 추가(빈 맵 허용, null 불가 — 생성자에서 `Map.copyOf`나 null→`Map.of()`).
  - `static List<String> buildCommand(DeployTarget.DeploySpec spec)` (LocalDockerTarget) — `docker build -t <image>` + 라벨들 + `--build-arg K=V`(맵 순서) + `.`. `deploy`는 이것을 쓴다.
  - `DeployService`는 `task.headSha()`가 비어 있지 않으면 `Map.of("NETIS_GIT_SHA", task.headSha())`, 아니면 `Map.of()`.

- [ ] **Step 1: 실패하는 테스트 작성** — `LocalDockerTargetBuildArgsTest`

```java
@Test
void 빌드_인자는_라벨_뒤_컨텍스트_앞에_들어간다() {
    var spec = new DeployTarget.DeploySpec(7, Path.of("/tmp/wt"), "netis-task-7:abc1234", "netis-task-7", 8080,
            Map.of(), Map.of("netis-maker.task", "7"), Map.of("NETIS_GIT_SHA", "abc1234def"));
    assertThat(LocalDockerTarget.buildCommand(spec)).containsExactly(
            "docker", "build", "-t", "netis-task-7:abc1234", "--label", "netis-maker.task=7",
            "--build-arg", "NETIS_GIT_SHA=abc1234def", ".");
}

@Test
void head_sha가_없으면_빌드_인자를_넣지_않는다() {
    var spec = new DeployTarget.DeploySpec(7, Path.of("/tmp/wt"), "netis-task-7:latest", "netis-task-7", 8080,
            Map.of(), Map.of("netis-maker.task", "7"), Map.of());
    assertThat(LocalDockerTarget.buildCommand(spec)).doesNotContain("--build-arg");
}
```

DeployService 쪽은 기존 `DeployServicePrebuildTest`의 `target.deploy` 캡처로 하나: `ArgumentCaptor<DeployTarget.DeploySpec>` → `buildArgs()`가 `{NETIS_GIT_SHA=abc1234def}`(테스트의 `task()` head_sha 값).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetBuildArgsTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현** — Interfaces 그대로. 기존 `DeploySpec` 생성 두 테스트 파일은 마지막 인자 `Map.of()`를 더한다.

- [ ] **Step 4: 통과 확인** — 워커 전체 테스트

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetBuildArgsTest' --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.LocalDockerTargetRunArgsTest' --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.DnsRegisteringDeployTargetTest' --tests 'com.hamonsoft.netismaker.workerdaemon.DeployServicePrebuildTest'`
Expected: PASS

Run: `./gradlew test` (전체 — 이 작업이 마지막 백엔드 작업이다)
Expected: 이 PC에서 원래 실패하던 4건(심볼릭 링크 권한 3·`sh` 없음 1 — PR #105 이후엔 건너뜀)과 부하 때 간헐 `ProcessRunnerTimeoutTest` 말고는 실패 없음

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/
git commit -m "feat(deploy): docker build에 NETIS_GIT_SHA 빌드 인자를 넘긴다"
```

---

### Task 6: 카탈로그 화면 문구 + 문서

**Files:**
- Modify: `frontend/pages/admin/repo-catalog.vue:432-449`
- Modify: `frontend/test/repo-catalog-admin.spec.ts` (라벨·placeholder 단언 추가)
- Modify: `CLAUDE.md` — "종료 산출물(2026-10-14 …)" 문단 끝과 "배포 전 미리 빌드" 문단에 한 줄씩

**Interfaces:** 없음(문구만)

문구(그대로):
- 토글 라벨: `종료 때 산출물 보관 (WAR·JAR·zip, 등록자 메일)`
- 경로 placeholder: `비우면 자동: /opt/netis-artifact/의 파일 하나 → 없으면 webapps·/app의 WAR·JAR 하나`
- CLAUDE.md 종료 산출물 문단: `납품 파일(2026-11-04, 스펙 docs/superpowers/specs/2026-11-04-close-artifact-bundle-design.md): 허용 확장자 .war·.jar·.zip·.tar.gz·.tgz(util/ArtifactNames). 카탈로그 경로가 비면 이미지 /opt/netis-artifact/ 바로 아래 파일 하나를 WAR 검색보다 먼저 본다(2개 이상이면 종료실패).`
- CLAUDE.md 미리 빌드 문단: `미리 빌드 명령이 .netis-artifact/에 납품 파일 하나를 남기면 워커가 .netis-prebuild/artifact/로 옮기고 생성 Dockerfile 끝에 COPY …/opt/netis-artifact/를 붙인다(0개·2개 이상·확장자 밖이면 배포실패). 모든 docker build에 --build-arg NETIS_GIT_SHA=<head_sha>.`

- [ ] **Step 1: 실패하는 테스트** — `repo-catalog-admin.spec.ts`의 `산출물 보관을 켜면 경로 입력이 보이고 함께 보낸다`에 라벨 `종료 때 산출물 보관 (WAR·JAR·zip, 등록자 메일)`과 placeholder 문구가 보이는지 단언 추가.
- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npx vitest run test/repo-catalog-admin.spec.ts` / Expected: FAIL(옛 문구). worktree엔 `frontend/node_modules`가 없으니 본 체크아웃 `node_modules`로의 정션을 만든다(`New-Item -ItemType Junction`), 지우기 전에 `cmd //c "rmdir frontend\\node_modules"`로 링크만 먼저 지울 것.
- [ ] **Step 3: 구현** — 위 문구로 바꾸고 CLAUDE.md 두 줄 추가.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS
- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/admin/repo-catalog.vue frontend/test/repo-catalog-admin.spec.ts CLAUDE.md
git commit -m "docs(artifact): 카탈로그 산출물 문구와 CLAUDE.md에 납품 파일 규칙"
```

---

### Task 7: PR·라이브 반영 (운영, 사용자 승인 단계 포함)

코드 변경 없음. 스펙 §12 순서.

- [ ] **Step 1:** 브랜치 푸시·PR(`feature/close-artifact-bundle` → `main`), 본문에 스펙 링크·배포 순서. CI 3개 통과 확인. 병합은 사용자 지시("병합해줘"/"CI 통과 후 병합") 때만.
- [ ] **Step 2:** 병합 뒤 본 체크아웃 `git pull --ff-only`. 진행 중 배포·종료 없음 확인(`com.task`의 `배포중`·`종료중`) 후 `.\scripts\stop-public.ps1 -Only api,worker` → `Start-ScheduledTask -TaskName netisMaker-public`. 확인: `.run\api.log`·`.run\worker.log`의 `Started`, 워커 `기동 정합 완료`.
- [ ] **Step 3:** 프론트: front 내리고 `frontend`에서 `NUXT_IGNORE_LOCK=1 npm run build` → 작업 스케줄러. 카탈로그 화면에서 새 라벨 확인.
- [ ] **Step 4:** `NETIS_GIT_SHA`를 쓰지 않는 레포의 다음 배포 로그에서 경고 한 줄만 있고 배포완료인지 확인(스펙 §7). 원격 데몬 출력이 예상과 다르면(오류로 끝남) 여기서 멈추고 보고.

---

### Task 8: 경북대 카탈로그 12 값 교체 (운영)

- [ ] **Step 1:** Task 7 반영 뒤 DB(또는 관리 화면)에서 카탈로그 12를 스펙 §10.1 값으로: 명령 `mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ -Pdaegu clean package -DskipTests && mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ -Psangju package -DskipTests && mkdir .netis-artifact-src .netis-artifact && copy /y hm-dash\target\hm-dash*.war .netis-artifact-src\ && tar -a -cf .netis-artifact\knu-wars.zip --options zip:compression=store -C .netis-artifact-src .`, 배포 산출물 `hm-dash/target/hm-dash{,-daegu}.war`, 도구 `jdk8-maven`, 산출물 보관 켬·경로 비움.
- [ ] **Step 2:** 사용자에게 작업 28 재배포를 요청. 배포 로그에 `[미리 빌드 납품] knu-wars.zip (…MB)`·`[납품 파일] … 추가`·배포완료, 시험 주소가 대구 화면인지 확인.
- [ ] **Step 3:** 사용자 종료 승인 뒤 `attachments\task-28\artifact\netisdash_knu-task-28-<sha7>.zip`이 생기고 `tar -tvf`로 `hm-dash-daegu.war`·`hm-dash-sangju.war` 두 개인지 확인.

---

### Task 9: 강원 레포 Dockerfile (별도 레포, 사용자 승인 단계 포함)

대상: `gitlab.hamon.vip/product/netis/web/dashboard/netisdash_kangwonedu`. 요구사항은 스펙 §10.2 그대로(루트 앱 runner 유지·glibc bundle 단계·Node v22.23.4 tar.xz + SHASUMS 대조·`DASH_BASE_PATH=/dash` 빌드·Info-ZIP zip·`/opt/netis-artifact/netis-dash-deploy-<sha7>.zip` 하나·`ARG NETIS_GIT_SHA`).

- [ ] **Step 1:** `%TEMP%\<짧은 이름>`에 `main`을 받는다(토큰은 전송 URL에만, 받은 뒤 `git remote remove origin` — 워커 레포 캐시는 건드리지 않음). `Dockerfile`·`.dockerignore` 작성.
- [ ] **Step 2:** 이 PC에서 `DOCKER_HOST=ssh://Administrator@10.1.1.75`로 `docker build --build-arg NETIS_GIT_SHA=<main sha> -t netis-kw-bundle-check .` → 성공, 빌드 시간 기록(작업 27 빌드 시간과 비교, 30분 상한 안).
- [ ] **Step 3:** 확인: 이미지 `/opt/netis-artifact/`에 zip 하나 → 임시 컨테이너로 꺼내 리눅스 컨테이너(이미 있는 `node:22-alpine`)에서 `unzip` → `cd netis-dash-deploy-*/ && sha256sum -c SHA256SUMS` 통과, `install.sh`·`uninstall.sh` `-rwxr-xr-x`, `BASE_PATH`=`/dash`. 같은 이미지로 컨테이너를 띄워 `/` → 307. 끝나면 시험 이미지·컨테이너 삭제.
- [ ] **Step 4:** **사용자 승인 뒤** 브랜치를 푸시하고 `main` 대상 MR을 연다(토큰은 전송 URL에만). 병합은 사용자.
- [ ] **Step 5:** 병합 확인 뒤 카탈로그 11 `artifact_enabled=true`(경로 비움). 다음 강원 작업의 종료에서 zip 확인은 사용자와.
