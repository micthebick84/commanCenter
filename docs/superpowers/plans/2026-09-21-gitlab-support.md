# 사내 GitLab 저장소 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레포 카탈로그에 등록한 사내 GitLab 저장소로 브랜치 조회·작업 등록·인터뷰·질문·구현(Draft MR)·디자인·배포가 GitHub와 똑같이 동작하게 한다.

**Architecture:** 이미 DB에 스냅샷으로 저장되는 `git_url`을 claim 응답으로 워커/인터뷰 서비스까지 전달한다. 저장소는 `RepoRef(host, path, gitUrl)`로 표현하고, 인증 URL 조립·로컬 폴더 키·자격증명 마스킹은 `GitRemotes` 한 곳, PR/MR 생성은 `MergeRequestCreator` 구현체(GitHub=`gh`, GitLab=REST API)가 맡는다. 새 claim 필드는 선택값이라 구버전과 혼재 가능.

**Tech Stack:** Java 21 / Spring Boot 3.4 (API + worker 프로파일), JDK `HttpClient` + Jackson, JUnit5 + Mockito + AssertJ, Node 20+ / TypeScript / vitest (interview service), Nuxt 3 + Quasar / vitest (frontend).

**Spec:** `docs/superpowers/specs/2026-09-21-gitlab-support-design.md` (Task 1이 아래 "스펙 대비 단순화"를 스펙에 반영한다)

## Global Constraints

- 호스트는 `github` | `gitlab` | `other` 세 값. 사내 GitLab **1곳만** 지원.
- 환경변수: API는 `GITLAB_BASE_URL` + `GITLAB_TOKEN`, 워커·인터뷰 서비스는 `GITLAB_TOKEN`만. `GITLAB_BASE_URL`이 비면 GitLab URL은 `other`(=현재 동작).
- **이름을 바꾸지 않는다:** DB 컬럼 `github_repo`/`github_branch`, 상태값 `PR_CREATED("PR생성")`, 필드 `prUrl`/`prNumber`/`githubRepo`/`githubBranch`. DB 마이그레이션 없음.
- GitLab이면 `github_repo`/`owner_repo`에 **프로젝트 전체 경로**(예 `product/netis/web/package/netis-v7.0`)를 넣는다.
- 로컬 폴더 키: GitHub는 `owner/repo` **불변**, GitLab은 `_gitlab/` + 경로의 `/`→`+`. 항상 2단계.
- 새 claim 필드 `gitUrl`, `repoHost`는 optional. 없으면 GitHub로 간주.
- 토큰은 로그·예외 메시지·`failure_reason`에 절대 나가지 않는다 — 나가는 모든 경로에 `GitRemotes.mask` / `maskSecrets`.
- `@Component`는 생성자 1개(`WorkerDaemonConstructorContractTest`). 여러 개면 `@Autowired` 필요 → 만들지 말 것.
- 프론트 코드 스타일: 작은따옴표, 세미콜론 없음, 2-space. `npm run lint-prettier`를 전체에 돌리지 말 것.
- 클래스명에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl gt-* lt-*`) 금지.
- 커밋 메시지는 기존 형식(`feat(scope): …` 한국어), 끝에 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- 이 PC에는 Docker가 없다 → Testcontainers 기반 통합 테스트는 여기서 못 돈다. 각 Task는 **지정한 단위 테스트만** 실행하고, 전체 스위트는 CI(`.github/workflows/ci.yml`)에 맡긴다.

### 공통 실행 환경 (Windows, Git Bash)

```bash
export JAVA_HOME="C:/Users/mic/.jdks/jdk-21.0.12.1+1"
cd /c/Users/mic/NetisMaker/commanCenter
# Java 단위 테스트 1개 클래스
./gradlew.bat test --tests "com.hamonsoft.netismaker.util.RepoUrlParserTest"
# 인터뷰 서비스
(cd netismaker-interview-service && npx vitest run test/gitRemote.test.ts)
# 프론트
(cd frontend && npx vitest run test/questions-index.spec.ts)
```

### 스펙 대비 단순화 (Task 1에서 스펙에 반영)

1. 워커·인터뷰 서비스는 `GITLAB_BASE_URL`이 필요 없다 — 인증 URL은 claim의 `gitUrl`에 자격증명을 끼워 만들고, GitLab REST API base는 `gitUrl`의 `scheme://host[:port]`에서 얻는다. (제약: GitLab이 서브경로 `https://host/gitlab/`에 설치된 경우는 미지원.)
2. `repoHost`는 설정 없이 `gitUrl`의 호스트로 판정한다: 없음/`github.com` → `github`, 그 외 → `gitlab`. (`other`는 등록 단계에서 이미 거절되므로 작업/세션에 남을 수 없다.)
3. `GitRemotes`는 Spring 빈이 아니라 각 서비스가 생성자에서 `new GitRemotes(githubPat, gitlabToken)`로 만든다 — 두 프로파일 동시 활성 시 빈 충돌이 없다.
4. 호스트 비교는 포트를 빼고 **호스트명만**(대소문자 무시). ssh/scp URL은 https와 포트가 달라서다.
5. 프론트의 PR/MR 문구는 `repoHost` DTO 필드 대신 **`prUrl` 모양**(`/-/merge_requests/N`)으로 판정한다. `TaskResponse` 등 조회 DTO는 건드리지 않는다. MR이 생기기 전 안내 문구는 "Draft PR/MR"로 중립화.
6. 프론트 브랜치 로딩을 composable로 뽑지 않는다 — 두 페이지의 로딩 함수를 `catalogId` 기준으로 고치는 최소 변경만 한다(기존 spec이 `vm.onRepoSelected`/`vm.draft`를 직접 검증하고 있어 추출 이득보다 회귀 위험이 크다).

## File Structure

| 파일 | 역할 |
|---|---|
| `src/main/java/.../util/RepoUrlParser.java` (수정) | 입력 → `Parsed(canonicalUrl, host, ownerRepo)`. gitlab 판별 추가 |
| `src/main/java/.../git/RepoRef.java` (신규) | 저장소 값 객체 + `fromSnapshot` |
| `src/main/java/.../git/GitRemotes.java` (신규) | 인증 URL · `localKey` · `mask` |
| `src/main/java/.../service/GitRefService.java` (수정) | `listBranches(RepoRef)` |
| `src/main/java/.../service/RepoCatalogService.java` (수정) | `git_url` 재해석, gitlab 허용, `toView`, `refOf` |
| `src/main/java/.../controller/RepoController.java` (수정) | `?catalogId=` |
| `src/main/java/.../controller/RepoCatalogController.java` (수정) | `service.toView` 사용 |
| `src/main/java/.../dto/RepoCatalogDto.java` (수정) | `View.of(e, host, ownerRepo)` |
| `src/main/java/.../dto/WorkerTaskResponse.java`, `InterviewClaimResponse.java` (수정) | `gitUrl`, `repoHost` + `repoRef()` |
| `src/main/java/.../service/InterviewService.java` (수정) | `deriveWorkDir` → `localKey` |
| `src/main/java/.../workerdaemon/WorkerProperties.java` (수정) | `gitlabToken` |
| `src/main/java/.../workerdaemon/GitRepoCache.java` (수정) | `RepoRef` 기반, origin 갱신, 마스킹 |
| `src/main/java/.../workerdaemon/MergeRequestCreator.java`, `GitHubPrCreator.java`, `GitLabMrCreator.java` (신규) | PR/MR 생성 |
| `src/main/java/.../workerdaemon/GitOpsService.java` (수정) | creator 위임 |
| `src/main/java/.../workerdaemon/WorkerMainLoop.java`, `DeployService.java` (수정) | `RepoRef` 전달, PR 본문 중립화 |
| `src/main/resources/application.yml`, `application-worker.yml` (수정) | 설정 키 |
| `netismaker-interview-service/src/sdk/gitRemote.ts` (신규) | `buildCloneUrl`, `maskSecrets` |
| `netismaker-interview-service/src/{config,types}.ts`, `src/runner/{repoPrepare,interviewRunner}.ts`, `src/index.ts`, `.env.example` (수정) | 토큰·gitUrl 전달 |
| `frontend/composables/mergeRequestLabel.ts` (신규) | `mrNoun`, `mrRef` |
| `frontend/pages/{tasks,questions}/index.vue`, `pages/tasks/[id].vue`, `pages/admin/repo-catalog.vue`, `components/tasks/*.vue`, `composables/taskStages.ts` (수정) | catalogId 조회, 문구 |

---

### Task 1: 스펙에 단순화 반영

**Files:**
- Modify: `docs/superpowers/specs/2026-09-21-gitlab-support-design.md`

**Interfaces:**
- Consumes: 없음
- Produces: 이후 Task가 따르는 확정 스펙

- [ ] **Step 1: 스펙 §2 표 아래에 절 추가**

`## 3. 저장소 식별과 설정` 바로 위에 다음을 삽입한다.

```markdown
## 2.1 구현 계획에서 확정한 단순화 (2026-09-21)

아래 항목이 본문과 다르면 **이 절이 우선**한다.

1. 워커·인터뷰 서비스는 `GITLAB_TOKEN`만 읽는다. `GITLAB_BASE_URL`은 API만 쓴다(§3.1). 인증 URL은 `gitUrl`에 자격증명을 끼워 만들고, REST API base는 `gitUrl`의 `scheme://host[:port]`. GitLab 서브경로 설치는 미지원.
2. `repoHost`는 `gitUrl` 호스트로 판정(없음/`github.com`→`github`, 그 외→`gitlab`). 설정 의존 없음(§5.1).
3. `GitRemotes`는 빈이 아니라 각 서비스 생성자에서 `new GitRemotes(githubPat, gitlabToken)`(§4.2·§4.3). `gitlabBaseUrl` 인자 없음.
4. 호스트 비교는 호스트명만, 대소문자 무시(§3.2의 "포트 포함 비교" 대체).
5. 조회 DTO(`TaskResponse` 등)에 `repoHost`를 추가하지 않는다(§5.3 삭제). 프론트는 `prUrl` 모양으로 PR/MR을 판정(§9).
6. `useRepoBranches` composable을 만들지 않는다. 두 페이지의 로딩 함수를 `catalogId` 기준으로 고친다(§9).
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-09-21-gitlab-support-design.md
git commit -m "docs(spec): GitLab 지원 — 구현 계획에서 확정한 단순화 반영

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `RepoUrlParser` — GitLab URL 판별

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java`
- Test: `src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `RepoUrlParser.parse(String input, String gitlabBaseUrl) → Parsed(canonicalUrl, host, ownerRepo)`; 기존 `parse(String)`은 `parse(input, null)`과 동일.

- [ ] **Step 1: 실패하는 테스트 추가** — `RepoUrlParserTest` 클래스 끝(마지막 `}` 앞)에 추가

```java
    private static final String GL = "https://gitlab.hamon.vip";

    @Test
    void gitlab_https_url_with_nested_groups() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse(
                "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git", GL);
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(p.canonicalUrl())
                .isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void gitlab_url_without_dot_git_and_with_trailing_slash_is_normalized() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj/", GL);
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.ownerRepo()).isEqualTo("group/proj");
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.hamon.vip/group/proj.git");
    }

    @Test
    void gitlab_scp_and_ssh_urls_are_canonicalized_to_base_url() {
        assertThat(RepoUrlParser.parse("git@gitlab.hamon.vip:group/sub/proj.git", GL).canonicalUrl())
                .isEqualTo("https://gitlab.hamon.vip/group/sub/proj.git");
        assertThat(RepoUrlParser.parse("ssh://git@gitlab.hamon.vip:2222/group/sub/proj.git", GL).ownerRepo())
                .isEqualTo("group/sub/proj");
    }

    @Test
    void gitlab_host_match_is_case_insensitive_and_base_trailing_slash_ignored() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://GitLab.Hamon.VIP/group/proj.git",
                "https://gitlab.hamon.vip/");
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.hamon.vip/group/proj.git");
    }

    @Test
    void other_gitlab_host_stays_other_when_base_url_differs_or_is_blank() {
        assertThat(RepoUrlParser.parse("https://gitlab.com/group/sub/proj.git", GL).host()).isEqualTo("other");
        assertThat(RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj.git", "").host()).isEqualTo("other");
        assertThat(RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj.git").host()).isEqualTo("other");
    }

    @Test
    void gitlab_single_segment_or_bad_segment_is_rejected() {
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/onlyone.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/group/../proj.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/group/pr+oj.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void github_inputs_are_unaffected_by_gitlab_base_url() {
        assertThat(RepoUrlParser.parse("owner/repo", GL).host()).isEqualTo("github");
        assertThat(RepoUrlParser.parse("https://github.com/owner/repo.git", GL).canonicalUrl())
                .isEqualTo("https://github.com/owner/repo.git");
    }
```

파일 상단 import에 `import static org.assertj.core.api.Assertions.assertThatThrownBy;`가 없으면 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.util.RepoUrlParserTest"`
Expected: 컴파일 실패 — `parse(String,String)` 없음.

- [ ] **Step 3: 구현** — `RepoUrlParser.java`의 `parse(String input)` 메서드 전체를 아래로 교체하고, 상수 아래에 헬퍼를 추가한다. 클래스 javadoc 2번째 줄 아래에 ` *  사내 GitLab(gitlabBaseUrl의 호스트)이면 host="gitlab" + ownerRepo=프로젝트 전체 경로 + canonicalUrl="{base}/{path}.git".` 한 줄을 추가.

```java
    // http(s)/ssh URL: [user@]host[:port]/path
    private static final Pattern HOSTED_URL =
            Pattern.compile("^(?:https?|ssh)://(?:[^@/\\s]+@)?([^/:\\s]+)(?::\\d+)?/(.+)$");
    // scp 스타일: user@host:path
    private static final Pattern SCP_URL = Pattern.compile("^[^@\\s]+@([^:/\\s]+):(.+)$");
    private static final Pattern GITLAB_SEGMENT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]*$");

    public static Parsed parse(String input) {
        return parse(input, null);
    }

    /** gitlabBaseUrl(예: https://gitlab.hamon.vip)이 비면 GitLab 판별을 하지 않는다. */
    public static Parsed parse(String input, String gitlabBaseUrl) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("레포 입력이 비어 있습니다");
        }
        String s = input.strip();

        // 1) bare owner/repo — 항상 GitHub (GitLab 레포는 전체 URL로 입력)
        if (OWNER_REPO.matcher(s).matches()) {
            return new Parsed("https://github.com/" + s + ".git", "github", s);
        }

        // 2) github URL (https/http/ssh/scp)
        Matcher gh = GITHUB.matcher(s);
        if (gh.find()) {
            String ownerRepo = gh.group(1) + "/" + gh.group(2);
            return new Parsed("https://github.com/" + ownerRepo + ".git", "github", ownerRepo);
        }

        // 3) 사내 GitLab
        String base = normalizeBase(gitlabBaseUrl);
        if (base != null) {
            String path = pathIfHost(s, hostOf(base));
            if (path != null) {
                validateGitlabPath(path, input);
                return new Parsed(base + "/" + path + ".git", "gitlab", path);
            }
        }

        // 4) 그 밖의 URL 모양이면 other 로 보존
        if (URL_SHAPE.matcher(s).matches() || SCP_SHAPE.matcher(s).matches()) {
            String canonical = s.replaceAll("/+$", "");
            return new Parsed(canonical, "other", null);
        }

        throw new IllegalArgumentException("레포 URL 형식을 인식할 수 없습니다: " + input);
    }

    private static String normalizeBase(String gitlabBaseUrl) {
        if (gitlabBaseUrl == null || gitlabBaseUrl.isBlank()) return null;
        return gitlabBaseUrl.strip().replaceAll("/+$", "");
    }

    private static String hostOf(String baseUrl) {
        Matcher m = Pattern.compile("^https?://(?:[^@/\\s]+@)?([^/:\\s]+)").matcher(baseUrl);
        return m.find() ? m.group(1) : null;
    }

    /** s의 호스트가 host와 같으면 정리된 경로(.git·앞뒤 슬래시 제거), 아니면 null. */
    private static String pathIfHost(String s, String host) {
        if (host == null) return null;
        Matcher m = HOSTED_URL.matcher(s);
        if (!m.matches()) {
            m = SCP_URL.matcher(s);
            if (!m.matches()) return null;
        }
        if (!m.group(1).equalsIgnoreCase(host)) return null;
        return m.group(2).replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("\\.git$", "");
    }

    private static void validateGitlabPath(String path, String input) {
        String[] segments = path.split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException("GitLab 프로젝트 경로는 그룹/프로젝트 형태여야 합니다: " + input);
        }
        for (String seg : segments) {
            if (seg.equals("..") || !GITLAB_SEGMENT.matcher(seg).matches()) {
                throw new IllegalArgumentException("GitLab 프로젝트 경로가 올바르지 않습니다: " + input);
            }
        }
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.util.RepoUrlParserTest"`
Expected: PASS (기존 9개 + 신규 7개).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/util/RepoUrlParser.java src/test/java/com/hamonsoft/netismaker/util/RepoUrlParserTest.java
git commit -m "feat(repo): RepoUrlParser — 사내 GitLab URL 판별(다단계 경로)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `RepoRef` + `GitRemotes`

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/git/RepoRef.java`
- Create: `src/main/java/com/hamonsoft/netismaker/git/GitRemotes.java`
- Test: `src/test/java/com/hamonsoft/netismaker/git/GitRemotesTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `record RepoRef(String host, String path, String gitUrl)`; `RepoRef.fromSnapshot(String path, String gitUrl)`; `boolean isGitlab()`; `String apiBase()` (gitUrl의 `scheme://host[:port]`)
  - `new GitRemotes(String githubPat, String gitlabToken)`; `String authenticatedUrl(RepoRef)`; `String tokenFor(RepoRef)`; `static String localKey(RepoRef)`; `static String mask(String)`

- [ ] **Step 1: 실패하는 테스트 작성** — `GitRemotesTest.java`

```java
package com.hamonsoft.netismaker.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitRemotesTest {

    private static final RepoRef GH = RepoRef.fromSnapshot("acme/widgets", "https://github.com/acme/widgets.git");
    private static final RepoRef GL = RepoRef.fromSnapshot("product/netis/web/package/netis-v7.0",
            "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");

    @Test
    void fromSnapshot_without_git_url_is_legacy_github() {
        RepoRef r = RepoRef.fromSnapshot("acme/widgets", null);
        assertThat(r.host()).isEqualTo("github");
        assertThat(r.gitUrl()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(RepoRef.fromSnapshot("acme/widgets", "  ").host()).isEqualTo("github");
    }

    @Test
    void fromSnapshot_derives_host_from_git_url() {
        assertThat(GH.host()).isEqualTo("github");
        assertThat(GH.isGitlab()).isFalse();
        assertThat(GL.host()).isEqualTo("gitlab");
        assertThat(GL.isGitlab()).isTrue();
        assertThat(GL.apiBase()).isEqualTo("https://gitlab.hamon.vip");
        assertThat(RepoRef.fromSnapshot("g/p", "http://localhost:8929/g/p.git").apiBase())
                .isEqualTo("http://localhost:8929");
    }

    @Test
    void authenticatedUrl_injects_the_token_of_the_matching_host() {
        GitRemotes r = new GitRemotes("ghp_AAA", "glpat-BBB");
        assertThat(r.authenticatedUrl(GH)).isEqualTo("https://oauth2:ghp_AAA@github.com/acme/widgets.git");
        assertThat(r.authenticatedUrl(GL))
                .isEqualTo("https://oauth2:glpat-BBB@gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void authenticatedUrl_without_token_is_the_plain_url() {
        GitRemotes r = new GitRemotes("", null);
        assertThat(r.authenticatedUrl(GH)).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(r.authenticatedUrl(GL)).isEqualTo(GL.gitUrl());
        assertThat(r.tokenFor(GL)).isNull();
    }

    @Test
    void token_with_regex_special_chars_is_inserted_literally() {
        GitRemotes r = new GitRemotes(null, "a$1b\\c");
        assertThat(r.authenticatedUrl(GL)).startsWith("https://oauth2:a$1b\\c@gitlab.hamon.vip/");
    }

    @Test
    void localKey_keeps_github_as_is_and_flattens_gitlab_to_two_levels() {
        assertThat(GitRemotes.localKey(GH)).isEqualTo("acme/widgets");
        assertThat(GitRemotes.localKey(GL)).isEqualTo("_gitlab/product+netis+web+package+netis-v7.0");
    }

    @Test
    void mask_hides_credentials_in_urls() {
        assertThat(GitRemotes.mask("git clone https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git x"))
                .isEqualTo("git clone https://***@gitlab.hamon.vip/g/p.git x")
                .doesNotContain("SECRET");
        assertThat(GitRemotes.mask("a https://u:p1@h/x b https://u:p2@h/y"))
                .doesNotContain("p1").doesNotContain("p2");
        assertThat(GitRemotes.mask("no creds https://github.com/a/b.git")).isEqualTo("no creds https://github.com/a/b.git");
        assertThat(GitRemotes.mask(null)).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.git.GitRemotesTest"`
Expected: 컴파일 실패 — `RepoRef`, `GitRemotes` 없음.

- [ ] **Step 3: `RepoRef.java`**

```java
package com.hamonsoft.netismaker.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장소 1개. host는 "github" | "gitlab".
 *  - path: GitHub는 owner/repo, GitLab은 프로젝트 전체 경로(다단계 그룹 포함).
 *  - gitUrl: 자격증명이 없는 정식 https URL.
 */
public record RepoRef(String host, String path, String gitUrl) {

    private static final Pattern ORIGIN = Pattern.compile("^(https?://)(?:[^@/\\s]+@)?([^/\\s]+)");

    /**
     * task/interview_session 스냅샷(github_repo, git_url)에서 복원.
     * git_url이 없으면(카탈로그 도입 전 행, 구버전 API) GitHub로 간주한다.
     * 호스트는 URL로 판정: github.com → github, 그 외 → gitlab
     * (지원하지 않는 호스트는 등록 단계에서 거절되므로 스냅샷에 남을 수 없다).
     */
    public static RepoRef fromSnapshot(String path, String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return new RepoRef("github", path, "https://github.com/" + path + ".git");
        }
        Matcher m = ORIGIN.matcher(gitUrl);
        String authority = m.find() ? m.group(2) : "";
        String hostName = authority.replaceAll(":\\d+$", "");
        return new RepoRef("github.com".equalsIgnoreCase(hostName) ? "github" : "gitlab", path, gitUrl);
    }

    public boolean isGitlab() {
        return "gitlab".equals(host);
    }

    /** REST API base — gitUrl의 scheme://host[:port]. */
    public String apiBase() {
        Matcher m = ORIGIN.matcher(gitUrl);
        if (!m.find()) throw new IllegalStateException("gitUrl에서 호스트를 찾을 수 없습니다: " + gitUrl);
        return m.group(1) + m.group(2);
    }
}
```

- [ ] **Step 4: `GitRemotes.java`**

```java
package com.hamonsoft.netismaker.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 호스트별 원격 처리 — 인증 URL 조립 / 로컬 폴더 키 / 자격증명 마스킹.
 * github.com 문자열 조립과 owner/repo 2단계 가정은 여기 밖에 두지 않는다.
 */
public final class GitRemotes {

    private static final Pattern CREDENTIALS = Pattern.compile("(?<=://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern SCHEME = Pattern.compile("^(https?://)");

    private final String githubPat;
    private final String gitlabToken;

    public GitRemotes(String githubPat, String gitlabToken) {
        this.githubPat = githubPat;
        this.gitlabToken = gitlabToken;
    }

    /** 해당 호스트의 토큰. 없으면 null. */
    public String tokenFor(RepoRef ref) {
        String t = ref.isGitlab() ? gitlabToken : githubPat;
        return (t == null || t.isBlank()) ? null : t;
    }

    /**
     * clone/fetch/push용 URL. 토큰이 있으면 https://oauth2:<token>@host/path.git.
     * 토큰이 없으면 평문 URL — 'oauth2:@host' 같은 빈 비밀번호 URL은 GitHub가 거부한다.
     */
    public String authenticatedUrl(RepoRef ref) {
        String token = tokenFor(ref);
        if (token == null) return ref.gitUrl();
        return SCHEME.matcher(ref.gitUrl())
                .replaceFirst("$1" + Matcher.quoteReplacement("oauth2:" + token + "@"));
    }

    /**
     * 로컬 폴더 키(캐시·worktree·잠금 파일·인터뷰 workDir). 항상 2단계 상대경로.
     *  github → owner/repo (기존 폴더 그대로 유효)
     *  gitlab → _gitlab/<경로의 '/'를 '+'로> — GitHub 사용자명은 '_'로 시작할 수 없고
     *           GitLab 경로에는 '+'가 올 수 없어 충돌이 없다.
     */
    public static String localKey(RepoRef ref) {
        return ref.isGitlab() ? "_gitlab/" + ref.path().replace('/', '+') : ref.path();
    }

    /** "://user:secret@" → "://***@". 로그·예외 메시지로 나가는 모든 git/gh 문자열에 적용. */
    public static String mask(String text) {
        return text == null ? null : CREDENTIALS.matcher(text).replaceAll("***@");
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.git.GitRemotesTest"`
Expected: PASS (7개).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/git src/test/java/com/hamonsoft/netismaker/git
git commit -m "feat(git): RepoRef + GitRemotes — 호스트별 인증 URL·폴더 키·자격증명 마스킹

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: API — 브랜치 조회와 카탈로그가 GitLab을 받는다

**Files:**
- Modify: `src/main/resources/application.yml:74-76`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/GitRefService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java:25-29`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/RepoController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java`

**Interfaces:**
- Consumes: `RepoUrlParser.parse(String,String)`, `RepoRef.fromSnapshot`, `GitRemotes`
- Produces:
  - `GitRefService(String githubPat, String gitlabToken)`; `BranchListResponse listBranches(RepoRef ref)`; `listBranches(String ownerRepo)` 유지
  - `RepoCatalogService(RepoCatalogRepository, GitRefService, String gitlabBaseUrl)`; `RepoRef refOf(Long catalogId)`; `RepoCatalogDto.View toView(RepoCatalogEntry)`
  - `GET /api/repos/branches?catalogId=` (기존 `?repo=` 유지)

- [ ] **Step 1: 테스트 수정·추가** — `RepoCatalogServiceTest.java`

`setUp()`의 서비스 생성을 3-인자로 바꾼다:

```java
        service = new RepoCatalogService(repo, gitRefService, "https://gitlab.hamon.vip");
```

파일 상단 import에 추가: `import com.hamonsoft.netismaker.git.RepoRef;`

`listBranches(any())`는 오버로드 때문에 모호해진다. 파일 안의 **모든** `gitRefService.listBranches(...)` 스텁/검증을 `RepoRef` 버전으로 바꾼다:
- `when(gitRefService.listBranches("a/b"))` 형태 → `when(gitRefService.listBranches(any(RepoRef.class)))`
- `verify(gitRefService, never()).listBranches(any())` → `verify(gitRefService, never()).listBranches(any(RepoRef.class))`

GitLab 엔트리 헬퍼와 테스트를 클래스 끝에 추가:

```java
    private RepoCatalogEntry gitlabEntry(Long id, String storedHost, String storedOwnerRepo) {
        RepoCatalogEntry e = RepoCatalogEntry.create("Netis7",
                "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git",
                storedHost, storedOwnerRepo, "develop", null, "admin");
        e.setEnabled(true);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void create_gitlab_url_stores_gitlab_host_and_full_path() {
        when(repo.findByAlias("Netis7")).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest("Netis7",
                "git@gitlab.hamon.vip:product/netis/web/package/netis-v7.0.git", null, null, true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getHost()).isEqualTo("gitlab");
        assertThat(saved.getOwnerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(saved.getGitUrl())
                .isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void resolveForRegistration_accepts_gitlab_even_when_row_was_stored_as_other() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "other", null)));

        RepoCatalogService.ResolvedRepo r = service.resolveForRegistration(9L);

        assertThat(r.host()).isEqualTo("gitlab");
        assertThat(r.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
        assertThat(r.defaultBranch()).isEqualTo("develop");
    }

    @Test
    void toView_reports_reparsed_host_and_path() {
        RepoCatalogDto.View v = service.toView(gitlabEntry(9L, "other", null));
        assertThat(v.host()).isEqualTo("gitlab");
        assertThat(v.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
    }

    @Test
    void check_gitlab_calls_listBranches_with_gitlab_ref() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "gitlab", "product/netis/web/package/netis-v7.0")));
        when(gitRefService.listBranches(any(RepoRef.class))).thenReturn(new BranchListResponse(
                "product/netis/web/package/netis-v7.0", "develop",
                List.of(new BranchListResponse.BranchEntry("develop", "abc")), OffsetDateTime.now()));

        RepoCatalogDto.CheckResult result = service.check(9L);

        assertThat(result.reachable()).isTrue();
        verify(gitRefService).listBranches(argThat((RepoRef ref) -> ref.isGitlab()
                && ref.path().equals("product/netis/web/package/netis-v7.0")));
    }

    @Test
    void refOf_returns_ref_for_enabled_entry_and_rejects_other_host() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "gitlab", "x/y")));
        assertThat(service.refOf(9L).isGitlab()).isTrue();

        RepoCatalogEntry bitbucket = RepoCatalogEntry.create("BB", "https://bitbucket.org/a/b.git",
                "other", null, null, null, "admin");
        ReflectionTestUtils.setField(bitbucket, "id", 10L);
        when(repo.findById(10L)).thenReturn(Optional.of(bitbucket));
        assertThatThrownBy(() -> service.refOf(10L))
                .isInstanceOf(TaskException.class).hasMessageContaining("지원하지 않는 호스트");
    }
```

`argThat`를 쓰려면 `import static org.mockito.ArgumentMatchers.argThat;` 추가(이미 `org.mockito.Mockito.*`를 static import 중이면 `Mockito.argThat`로 해결된다 — 컴파일 오류가 나면 명시 import).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.service.RepoCatalogServiceTest"`
Expected: 컴파일 실패 — 3-인자 생성자·`listBranches(RepoRef)`·`toView`·`refOf` 없음.

- [ ] **Step 3: `application.yml`** — `app.github` 블록 바로 아래(`worker:` 위)에 추가

```yaml
  gitlab:
    # 사내 GitLab 1곳. base-url의 호스트와 같은 Git URL을 GitLab 레포로 판별한다. 비우면 GitLab 지원 꺼짐.
    base-url: ${GITLAB_BASE_URL:}
    # 비공개 레포 브랜치 조회용 토큰(read_repository). 워커·인터뷰 서비스도 같은 GITLAB_TOKEN을 읽는다.
    token: ${GITLAB_TOKEN:}
```

- [ ] **Step 4: `GitRefService.java`**

import 추가: `com.hamonsoft.netismaker.git.GitRemotes`, `com.hamonsoft.netismaker.git.RepoRef`. `REPO_PATTERN` 상수는 유지(문자열 오버로드 검증용). 필드·생성자·`listBranches`·`buildUrl`을 아래로 교체:

```java
    private final GitRemotes remotes;

    public GitRefService(@Value("${app.github.pat:}") String githubPat,
                         @Value("${app.gitlab.token:}") String gitlabToken) {
        this.remotes = new GitRemotes(githubPat, gitlabToken);
    }

    /** 하위 호환: GitHub owner/repo 문자열. */
    public BranchListResponse listBranches(String repo) {
        if (repo == null || !REPO_PATTERN.matcher(repo).matches()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "'owner/repo' 형식이어야 합니다");
        }
        return listBranches(RepoRef.fromSnapshot(repo, null));
    }

    public BranchListResponse listBranches(RepoRef ref) {
        String url = remotes.authenticatedUrl(ref);
        String repo = ref.path();
```

그 아래 기존 본문(`ProcessBuilder pb = …`부터 `return parse(repo, out.toString());`까지)은 그대로 두되 실패 분기만 바꾼다:

```java
        if (p.exitValue() != 0) {
            String emsg = GitRemotes.mask(err.toString());
            if (emsg.contains("Repository not found")
                    || emsg.contains("not found")
                    || emsg.contains("Authentication")
                    || emsg.contains("could not read Username")
                    || emsg.contains("HTTP Basic: Access denied")) {
                throw new TaskException(HttpStatus.NOT_FOUND,
                        "레포를 찾을 수 없거나 접근 권한이 없습니다. 비공개 레포는 토큰(GITHUB_PAT/GITLAB_TOKEN) 등록이 필요합니다.");
            }
            log.warn("git ls-remote 실패 repo={} stderr={}", repo, emsg);
            throw new TaskException(HttpStatus.BAD_GATEWAY,
                    "git ls-remote 실패: " + emsg.strip());
        }
```

`private String buildUrl(String repo)` 메서드는 삭제한다. 클래스 javadoc의 "보안:" 문단을 `보안: URL은 RepoUrlParser가 검증한 정식 URL에서만 만들어진다. ProcessBuilder는 셸을 거치지 않고 직접 exec.`로 바꾼다.

- [ ] **Step 5: `RepoCatalogDto.View`** — 기존 `of(e)` 아래에 오버로드 추가

```java
        /** host/ownerRepo를 저장값 대신 현재 설정으로 재해석한 값으로 내보낸다(스펙 §3.3). */
        public static View of(RepoCatalogEntry e, String host, String ownerRepo) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), host, ownerRepo,
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
```

- [ ] **Step 6: `RepoCatalogService.java`**

import 추가: `com.hamonsoft.netismaker.git.RepoRef`, `org.springframework.beans.factory.annotation.Value`. 필드·생성자 교체:

```java
    private final RepoCatalogRepository repo;
    private final GitRefService gitRefService;
    private final String gitlabBaseUrl;

    public RepoCatalogService(RepoCatalogRepository repo, GitRefService gitRefService,
                              @Value("${app.gitlab.base-url:}") String gitlabBaseUrl) {
        this.repo = repo;
        this.gitRefService = gitRefService;
        this.gitlabBaseUrl = gitlabBaseUrl;
    }
```

`resolveForRegistration`의 호스트 검사(`if (!"github".equals(...)) { … }`)와 `return`을 교체:

```java
        RepoUrlParser.Parsed p = reparse(e);
        return new ResolvedRepo(e.getId(), e.getAlias(), p.canonicalUrl(),
                p.host(), p.ownerRepo(), e.getDefaultBranch());
```

메서드 javadoc을 `/** 작업/인터뷰 등록 시: id → 검증된 경로 + 스냅샷 필드. 비활성/누락/지원하지 않는 호스트 거절. */`로.

`check`의 호스트 검사와 `listBranches` 호출을 교체:

```java
        RepoUrlParser.Parsed p;
        try {
            p = reparse(e);
        } catch (TaskException ex) {
            return new RepoCatalogDto.CheckResult(false, null, 0, "GitHub/사내 GitLab 레포만 확인 가능");
        }
        try {
            var res = gitRefService.listBranches(RepoRef.fromSnapshot(p.ownerRepo(), p.canonicalUrl()));
```

`parseOrThrow`를 교체하고 헬퍼 3개 추가:

```java
    private RepoUrlParser.Parsed parseOrThrow(String gitUrl) {
        try {
            return RepoUrlParser.parse(gitUrl, gitlabBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new TaskException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 저장된 host/owner_repo는 저장 시점 해석의 캐시다. GITLAB_BASE_URL 설정 전에 등록돼
     * 'other'로 남은 GitLab 행이 있을 수 있으므로 사용 시점에 git_url을 다시 해석한다.
     */
    private RepoUrlParser.Parsed reparse(RepoCatalogEntry e) {
        RepoUrlParser.Parsed p = parseOrThrow(e.getGitUrl());
        if ("other".equals(p.host()) || p.ownerRepo() == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 호스트입니다(GitHub/사내 GitLab만 가능 — GITLAB_BASE_URL 설정 확인): " + e.getAlias());
        }
        return p;
    }

    /** 브랜치 조회용: 활성 카탈로그 id → RepoRef. */
    @Transactional(readOnly = true)
    public RepoRef refOf(Long id) {
        ResolvedRepo r = resolveForRegistration(id);
        return RepoRef.fromSnapshot(r.ownerRepo(), r.gitUrl());
    }

    public RepoCatalogDto.View toView(RepoCatalogEntry e) {
        try {
            RepoUrlParser.Parsed p = RepoUrlParser.parse(e.getGitUrl(), gitlabBaseUrl);
            return RepoCatalogDto.View.of(e, p.host(), p.ownerRepo());
        } catch (IllegalArgumentException ex) {
            return RepoCatalogDto.View.of(e);
        }
    }
```

`parseOrThrow`가 `static`이 아니게 됐으므로 `create`/`update`의 호출부는 그대로 동작한다(인스턴스 메서드 호출).

- [ ] **Step 7: `RepoCatalogController.java`** — 4곳 교체

```java
        return service.listEnabled().stream().map(service::toView).toList();   // listForUsers
        return service.listAll().stream().map(service::toView).toList();       // listForAdmins
        return service.toView(saved);                                          // create
        return service.toView(service.update(id, req));                        // update
```

- [ ] **Step 8: `RepoController.java`** — 클래스 본문 교체(javadoc의 엔드포인트 설명에 `GET /api/repos/branches?catalogId={id}` 줄 추가, "GitHub 레포 메타 조회" → "레포 메타 조회")

```java
    private final GitRefService git;
    private final RepoCatalogService catalog;

    public RepoController(GitRefService git, RepoCatalogService catalog) {
        this.git = git;
        this.catalog = catalog;
    }

    @GetMapping("/branches")
    @PreAuthorize("isAuthenticated()")
    public BranchListResponse branches(@RequestParam(required = false) String repo,
                                       @RequestParam(required = false) Long catalogId) {
        if ((repo == null) == (catalogId == null)) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "repo 또는 catalogId 중 하나만 지정해야 합니다");
        }
        return catalogId != null ? git.listBranches(catalog.refOf(catalogId)) : git.listBranches(repo);
    }
```

import 추가: `com.hamonsoft.netismaker.service.RepoCatalogService`, `com.hamonsoft.netismaker.service.TaskException`, `org.springframework.http.HttpStatus`.

- [ ] **Step 9: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.service.RepoCatalogServiceTest" --tests "com.hamonsoft.netismaker.util.RepoUrlParserTest"`
Expected: PASS. 기존 `resolveForRegistration_rejects_non_github_entry`(메시지에 "GitHub" 포함)와 `check_non_github_returns_false…`(error에 "GitHub" 포함)도 그대로 통과해야 한다 — 두 메시지 모두 "GitHub"를 포함하게 작성돼 있다.

그다음 컴파일 전체 확인: `./gradlew.bat compileJava compileTestJava -q` → 오류 없음. (`GitRefService`를 `new`로 만드는 다른 테스트가 있으면 2-인자로 고친다: `grep -rn "new GitRefService(" src/test`.)

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/hamonsoft/netismaker/service/GitRefService.java src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java src/main/java/com/hamonsoft/netismaker/controller/RepoCatalogController.java src/main/java/com/hamonsoft/netismaker/controller/RepoController.java src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java
git commit -m "feat(api): 브랜치 조회·레포 카탈로그가 사내 GitLab을 받는다

git_url을 사용 시점에 재해석해 'other'로 저장된 기존 행도 동작.
GET /api/repos/branches?catalogId= 추가, ls-remote stderr 마스킹.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: claim 응답에 `gitUrl`/`repoHost` + 인터뷰 `workDir`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java:153-163` (+ `deriveWorkDir` 호출부)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseRepoTest.java`(신규), `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

**Interfaces:**
- Consumes: `RepoRef.fromSnapshot`, `GitRemotes.localKey`
- Produces: 두 record의 **마지막** 컴포넌트로 `String gitUrl, String repoHost` 추가; 두 record에 `public RepoRef repoRef()`.

- [ ] **Step 1: 실패하는 테스트** — `WorkerTaskResponseRepoTest.java`(신규)

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseRepoTest {

    @Test
    void gitlab_task_carries_git_url_and_host_in_every_claim_kind() {
        Task t = Task.create("product/netis/web/package/netis-v7.0", "develop", "t", "d", "admin",
                3, List.of(), "claude-opus-5", "high");
        t.setGitUrl("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");

        for (WorkerTaskResponse r : new WorkerTaskResponse[]{
                WorkerTaskResponse.forAnalysis(t), WorkerTaskResponse.forImplementation(t, null, null),
                WorkerTaskResponse.forDeploy(t), WorkerTaskResponse.forUndeploy(t),
                WorkerTaskResponse.forDesign(t, null, null, null, null)}) {
            assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
            assertThat(r.repoHost()).isEqualTo("gitlab");
            assertThat(r.repoRef().isGitlab()).isTrue();
        }
    }

    @Test
    void legacy_task_without_git_url_is_github() {
        Task t = Task.create("acme/widgets", "main", "t", "d", "admin", 3, List.of(), "claude-opus-5", "high");
        WorkerTaskResponse r = WorkerTaskResponse.forAnalysis(t);
        assertThat(r.gitUrl()).isNull();
        assertThat(r.repoHost()).isEqualTo("github");
        assertThat(r.repoRef().gitUrl()).isEqualTo("https://github.com/acme/widgets.git");
    }

    @Test
    void worker_side_deserialization_without_new_fields_falls_back_to_github() {
        WorkerTaskResponse r = new WorkerTaskResponse(1L, "acme/widgets", "main", "t", "d",
                WorkerTaskResponse.Kind.ANALYSIS, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        assertThat(r.repoRef().host()).isEqualTo("github");
    }
}
```

`InterviewServiceTest.java` — `claim_moves_queued_to_running_and_sets_worker_and_assigns_work_dir` 테스트 바로 아래에 추가(기존 `session(id, status)` 헬퍼는 `owner/repo` 세션을 만든다. import에 `org.springframework.test.util.ReflectionTestUtils`가 없으면 추가):

```java
    @Test
    void claim_assigns_flattened_work_dir_for_gitlab_session() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        ReflectionTestUtils.setField(s, "githubRepo", "product/netis/web/package/netis-v7.0");
        s.setGitUrl("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w1");

        assertThat(s.getWorkDir())
                .endsWith("/netis-maker/interviews/_gitlab/product+netis+web+package+netis-v7.0/session-1");
        assertThat(resp.get().repoHost()).isEqualTo("gitlab");
        assertThat(resp.get().gitUrl()).isEqualTo(s.getGitUrl());
    }
```

`InterviewClaimResponseTest.java`에 추가:

```java
    @Test
    void of_carries_git_url_and_host() {
        InterviewSession s = InterviewSession.createQuestion("g/sub/p", "main", "t", "q", "admin",
                List.of(), "claude-opus-5", "high");
        s.setGitUrl("https://gitlab.hamon.vip/g/sub/p.git");
        ReflectionTestUtils.setField(s, "id", 5L);
        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());
        assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/g/sub/p.git");
        assertThat(r.repoHost()).isEqualTo("gitlab");
        assertThat(r.repoRef().path()).isEqualTo("g/sub/p");
    }
```

(import가 없으면 `org.springframework.test.util.ReflectionTestUtils`, `java.util.List` 추가.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseRepoTest" --tests "com.hamonsoft.netismaker.dto.InterviewClaimResponseTest"`
Expected: 컴파일 실패 — `gitUrl()`, `repoHost()`, `repoRef()` 없음.

- [ ] **Step 3: `WorkerTaskResponse.java`**

record 컴포넌트 끝(`String designOutputProjectId` 뒤)에 추가:

```java
        String designOutputProjectId,
        /** 정식 Git URL 스냅샷. 카탈로그 도입 전 작업·구버전 API는 null → GitHub로 간주. */
        String gitUrl,
        /** "github" | "gitlab" — gitUrl에서 판정한 값. null이면 github. */
        String repoHost
```

record 본문 맨 위(`public enum Kind` 위)에 추가:

```java
    /** 워커가 clone/폴더/MR에 쓰는 저장소 참조. 새 필드가 없는 페이로드는 GitHub로 복원된다. */
    public RepoRef repoRef() {
        return RepoRef.fromSnapshot(githubRepo, gitUrl);
    }

    private static String hostOf(Task t) {
        return RepoRef.fromSnapshot(t.getGithubRepo(), t.getGitUrl()).host();
    }
```

5개 factory의 `new WorkerTaskResponse(...)` 인자 **끝에** `, t.getGitUrl(), hostOf(t)`를 추가한다(예: `forAnalysis`의 `null, null, null, null, null, null);` → `null, null, null, null, null, null, t.getGitUrl(), hostOf(t));`). import: `com.hamonsoft.netismaker.git.RepoRef`.

- [ ] **Step 4: `InterviewClaimResponse.java`**

컴포넌트 끝(`String kind` 뒤)에 추가:

```java
        String kind,
        /** 정식 Git URL 스냅샷. null이면 GitHub로 간주(구버전 세션). */
        String gitUrl,
        /** "github" | "gitlab". */
        String repoHost
```

compact 생성자 아래에 추가:

```java
    public RepoRef repoRef() {
        return RepoRef.fromSnapshot(githubRepo, gitUrl);
    }
```

`of(...)`의 `new InterviewClaimResponse(...)` 끝을 교체:

```java
                mapped, s.getModel(), s.getEffort(), totalCostUsd, attachments, s.getKind().name(),
                s.getGitUrl(), RepoRef.fromSnapshot(s.getGithubRepo(), s.getGitUrl()).host());
```

import: `com.hamonsoft.netismaker.git.RepoRef`.

- [ ] **Step 5: `InterviewService.deriveWorkDir`** — 메서드 교체 + 호출부에 `gitUrl` 전달

```java
    /**
     * 결정적 체크아웃 경로: ~/netis-maker/interviews/{localKey}/session-{id}.
     * localKey는 GitHub면 owner/repo(기존 세션의 work_dir와 동일 값), GitLab이면 _gitlab/<평탄화 경로>.
     * 단일 호스트/공유 FS 전제. 같은 세션은 resume마다 항상 같은 경로 → 동일 cwd 보장.
     */
    private String deriveWorkDir(String githubRepo, String gitUrl, Long sessionId) {
        String key = GitRemotes.localKey(RepoRef.fromSnapshot(githubRepo, gitUrl));
        if (!key.contains("/")) key = "_/" + key;   // 방어: 1단계 값(레거시 이상 데이터)
        String home = System.getProperty("user.home");
        return home + "/netis-maker/interviews/" + key + "/session-" + sessionId;
    }
```

호출부(`InterviewService.java:135`) `s.setWorkDir(deriveWorkDir(s.getGithubRepo(), s.getId()));` → `s.setWorkDir(deriveWorkDir(s.getGithubRepo(), s.getGitUrl(), s.getId()));`. import: `com.hamonsoft.netismaker.git.GitRemotes`, `com.hamonsoft.netismaker.git.RepoRef`.

- [ ] **Step 6: 다른 생성 지점 컴파일 수정**

Run: `./gradlew.bat compileJava compileTestJava -q`
`new WorkerTaskResponse(` / `new InterviewClaimResponse(`를 직접 호출하는 테스트·코드에서 인자 개수 오류가 나면 끝에 `, null, null`을 추가한다(`grep -rn "new WorkerTaskResponse(\|new InterviewClaimResponse(" src`).

- [ ] **Step 7: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.dto.*" --tests "com.hamonsoft.netismaker.service.InterviewServiceTest" --tests "com.hamonsoft.netismaker.entity.InterviewSessionTest"`
Expected: PASS. 기존 GitHub `workDir` 단언(`…/interviews/owner/repo/session-1`)이 그대로 통과해야 한다.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/test/java/com/hamonsoft/netismaker
git commit -m "feat(api): claim 응답에 gitUrl·repoHost 전달, 인터뷰 workDir을 localKey로

새 필드는 optional — 없으면 GitHub로 복원돼 구버전과 혼재 가능.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: 워커 — `GitRepoCache`가 `RepoRef`로 동작 + 토큰 마스킹

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java:23`
- Modify: `src/main/resources/application-worker.yml:42`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitRepoCache.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` (131, 189, 199, 300, 308행 부근)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:57-58`
- Modify: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployReconcileJobTest.java:33-34`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/GitRepoCacheTest.java`(신규)

**Interfaces:**
- Consumes: `RepoRef`, `GitRemotes`, `WorkerTaskResponse.repoRef()`
- Produces: `WorkerProperties.gitlabToken()`; `GitRepoCache.ensureFresh(RepoRef, String)`, `fetchOnly(RepoRef, String)`; `withRepoLock(String key, RepoOp)`는 시그니처 불변(키는 `GitRemotes.localKey(ref)`); `WorktreeService`는 **변경 없음**(호출부가 키를 넘긴다).

- [ ] **Step 1: 실패하는 테스트** — `GitRepoCacheTest.java`. 실제 git으로 로컬 bare 레포를 원격 삼아 검증한다(네트워크 불필요).

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepoCacheTest {

    @TempDir Path tmp;

    private WorkerProperties props(Path reposDir) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null, null, null, null, null, null, null, null, null, deploy);
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    /** file:// bare 레포를 만들고 main 브랜치에 커밋 1개. */
    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    @Test
    void gitlab_ref_is_cloned_into_flattened_two_level_dir_with_lock_outside_the_repo() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        // gitUrl이 file:// 이면 SCHEME(https?)에 안 걸려 토큰이 끼워지지 않는다 → 로컬 clone 가능
        RepoRef ref = new RepoRef("gitlab", "product/netis/web/netis-v7.0", bare.toUri().toString());

        GitRepoCache.CheckedOutRepo repo = new GitRepoCache(props(reposDir)).ensureFresh(ref, "main");

        Path expected = reposDir.resolve("_gitlab").resolve("product+netis+web+netis-v7.0");
        assertThat(repo.dir().toPath()).isEqualTo(expected);
        assertThat(expected.resolve("a.txt")).exists();
        assertThat(reposDir.resolve("_gitlab").resolve("product+netis+web+netis-v7.0.lock")).exists();
        assertThat(repo.commitSha()).hasSize(40);
    }

    @Test
    void existing_clone_gets_its_origin_url_refreshed_before_fetch() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");
        Path dir = reposDir.resolve("acme").resolve("widgets");
        git(dir.toFile(), "remote", "set-url", "origin", "file:///nonexistent/stale.git");

        cache.ensureFresh(ref, "main");   // stale origin이면 fetch가 실패했을 것

        String origin = ProcessRunner.requireSuccess(dir.toFile(),
                List.of("git", "remote", "get-url", "origin"), 30).trim();
        assertThat(origin).isEqualTo(bare.toUri().toString());
    }

    @Test
    void failure_message_never_contains_the_token() {
        Path reposDir = tmp.resolve("repos");
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        assertThatThrownBy(() -> new GitRepoCache(props(reposDir)).ensureFresh(ref, "main"))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("glpat-SECRETTOKEN").doesNotContain("oauth2:glpat"));
    }
}
```

(`WorkerProperties` 위치 인자는 Step 3에서 `gitlabToken`을 `githubPat` 바로 뒤에 넣은 뒤의 순서다: `id, version, apiBaseUrl, apiKey, pollIntervalSeconds, heartbeatIntervalSeconds, resultReportMaxRetries, resultReportBackoffMs, replayMaxAttempts, reposDir, deadLetterDir, claudeCliPath, githubPat, gitlabToken, promptTemplate, analysisTimeout, worktreeRoot, branchPrefix, gitUserName, gitUserEmail, implementationPromptTemplate, implementationTimeout, designPromptTemplate, designTimeout, deploy` — 25개.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.GitRepoCacheTest"`
Expected: 컴파일 실패 — 25-인자 생성자·`ensureFresh(RepoRef,…)` 없음.

- [ ] **Step 3: `WorkerProperties.java` + yml + 기존 테스트**

`String githubPat,` 아래에 추가:

```java
        String githubPat,
        // 사내 GitLab 토큰(api + read_repository + write_repository). clone/push + Draft MR 생성.
        String gitlabToken,
```

`application-worker.yml`의 `github-pat:` 줄 아래:

```yaml
    gitlab-token: ${GITLAB_TOKEN:}
```

`DeployReconcileJobTest.java:33-34`의 `new WorkerProperties(...)`에서 13번째 인자(`githubPat` 자리의 `null`) 뒤에 `null` 하나를 추가한다:

```java
        return new WorkerProperties("mac-worker-1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, deploy);
```

- [ ] **Step 4: `GitRepoCache.java`**

import 추가: `com.hamonsoft.netismaker.git.GitRemotes`, `com.hamonsoft.netismaker.git.RepoRef`. 필드·생성자:

```java
    private final WorkerProperties props;
    private final GitRemotes remotes;

    public GitRepoCache(WorkerProperties props) {
        this.props = props;
        this.remotes = new GitRemotes(props.githubPat(), props.gitlabToken());
    }
```

`ensureFresh`, `fetchOnly`, `doEnsureFresh`를 교체(정규식 가드 2곳 삭제, `fetchOnly` javadoc은 유지):

```java
    public CheckedOutRepo ensureFresh(RepoRef ref, String branch) throws IOException, InterruptedException {
        return withRepoLock(GitRemotes.localKey(ref), () -> doEnsureFresh(ref, branch));
    }

    public CheckedOutRepo fetchOnly(RepoRef ref, String headBranch) throws IOException, InterruptedException {
        return withRepoLock(GitRemotes.localKey(ref), () -> {
            Path target = Paths.get(props.reposDir(), GitRemotes.localKey(ref));
            Files.createDirectories(target.getParent());
            if (!Files.exists(target.resolve(".git"))) {
                // 배포는 분석/구현을 거친 repo 대상이라 보통 이미 캐시됨. 없으면 head 브랜치로 clone.
                run(target.getParent().toFile(), "git", "clone", "--depth=1",
                        "--branch", headBranch, remotes.authenticatedUrl(ref), target.getFileName().toString());
            } else {
                refreshOrigin(target, ref);
                // head 브랜치를 origin/<head> tracking ref로 명시적 fetch (단일 브랜치 클론 대비).
                run(target.toFile(), "git", "fetch", "--force", "origin",
                        headBranch + ":refs/remotes/origin/" + headBranch);
            }
            String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
            return new CheckedOutRepo(target.toFile(), sha);
        });
    }

    private CheckedOutRepo doEnsureFresh(RepoRef ref, String branch) throws IOException, InterruptedException {
        Path target = Paths.get(props.reposDir(), GitRemotes.localKey(ref));
        Files.createDirectories(target.getParent());

        if (!Files.exists(target.resolve(".git"))) {
            run(target.getParent().toFile(), "git", "clone", "--depth=1", "--branch", branch,
                    remotes.authenticatedUrl(ref), target.getFileName().toString());
        } else {
            refreshOrigin(target, ref);
            run(target.toFile(), "git", "fetch", "--all", "--prune");
            run(target.toFile(), "git", "checkout", branch);
            run(target.toFile(), "git", "reset", "--hard", "origin/" + branch);
        }

        String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
        return new CheckedOutRepo(target.toFile(), sha);
    }

    /** 토큰 교체·추가가 재clone 없이 반영되도록 매번 origin을 현재 인증 URL로 맞춘다. */
    private void refreshOrigin(Path target, RepoRef ref) throws IOException, InterruptedException {
        run(target.toFile(), "git", "remote", "set-url", "origin", remotes.authenticatedUrl(ref));
    }
```

`withRepoLock`의 파라미터명을 `githubRepo` → `repoKey`로 바꾼다(본문의 3곳 포함). 동작은 같다. `repoUrl(String)` 메서드는 삭제.

`run(...)`의 로그·예외 3곳에 마스킹:

```java
        log.debug("git exec ({}): {}", dir, GitRemotes.mask(String.join(" ", command)));
        ...
            throw new IOException("git timeout: " + GitRemotes.mask(String.join(" ", command)));
        ...
            throw new IOException("git failed (" + p.exitValue() + "): " + GitRemotes.mask(out.toString()));
```

클래스 javadoc의 경로/URL 설명을 갱신:

```java
 *   ~/netis-maker/repos/{localKey}/   — GitHub: {owner}/{repo}, GitLab: _gitlab/{경로의 '/'→'+'}
 *
 *   첫 회: git clone <GitRemotes.authenticatedUrl>
 *   이후: git remote set-url origin <인증 URL> && git fetch && git checkout <branch> && git reset --hard
```

- [ ] **Step 5: 호출부** — `WorkerMainLoop.java`

`processAnalysis`/`processImplementation`/`processDesign` 각각에서 `repos.ensureFresh(task.githubRepo(), task.githubBranch())` → `repos.ensureFresh(task.repoRef(), task.githubBranch())` (3곳: 131, 189, 300행 부근).

`worktrees.create(repo.dir(), task.githubRepo(), …)`와 `worktrees.createForDesign(repo.dir(), task.githubRepo(), …)`의 두 번째 인자를 `GitRemotes.localKey(task.repoRef())`로 (199, 308행 부근). import: `com.hamonsoft.netismaker.git.GitRemotes`.

`DeployService.java:57-58`:

```java
            GitRepoCache.CheckedOutRepo repo = repos.fetchOnly(task.repoRef(), task.headBranch());
            File wt = worktrees.createForDeploy(repo.dir(), GitRemotes.localKey(task.repoRef()),
```

(나머지 인자는 그대로. import 동일.)

`WorktreeService`의 파라미터명 `githubRepo`를 `repoKey`로 바꾸고 javadoc에 "GitRemotes.localKey 값"이라고 적는다(3개 메서드, 동작 변경 없음).

- [ ] **Step 6: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.GitRepoCacheTest" --tests "com.hamonsoft.netismaker.workerdaemon.DeployReconcileJobTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesDeployTest"`
Expected: PASS. (`failure_message_never_contains_the_token`은 DNS 실패까지 몇 초 걸릴 수 있다.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon src/main/resources/application-worker.yml src/test/java/com/hamonsoft/netismaker/workerdaemon
git commit -m "feat(worker): GitRepoCache를 RepoRef 기반으로 — GitLab clone, origin 갱신, 토큰 마스킹

git 실패/타임아웃 메시지에 oauth2:<token>@ 가 실려 failure_reason으로 새던 문제 차단.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: 구현 단계 — GitLab Draft MR 생성

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/MergeRequestCreator.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitHubPrCreator.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitLabMrCreator.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitOpsService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java:265-267, 468-488`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/GitLabMrCreatorTest.java`(신규)

**Interfaces:**
- Consumes: `RepoRef`(`isGitlab`, `apiBase`, `path`), `GitRemotes.mask`, `WorkerProperties.githubPat/gitlabToken`, `GitOpsService.PrInfo(url, number)`
- Produces: `interface MergeRequestCreator { boolean supports(RepoRef); PrInfo create(File, RepoRef, String baseBranch, String headBranch, String title, String body) }`; `GitOpsService.createDraftPr(File, RepoRef, String, String, String, String)`.

- [ ] **Step 1: 실패하는 테스트** — `GitLabMrCreatorTest.java`

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabMrCreatorTest {

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();   // "METHOD rawPath?query | token | body"
    private int postStatus = 201;
    private String postBody = "{\"iid\":12,\"web_url\":\"http://gl/g/sub/p/-/merge_requests/12\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                    + " | " + ex.getRequestHeaders().getFirst("PRIVATE-TOKEN") + " | " + body);
            int status;
            String resp;
            if (ex.getRequestMethod().equals("POST")) {
                status = postStatus;
                resp = postBody;
            } else {
                status = 200;
                resp = "[{\"iid\":7,\"web_url\":\"http://gl/g/sub/p/-/merge_requests/7\"}]";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RepoRef ref() {
        return RepoRef.fromSnapshot("g/sub/p", "http://127.0.0.1:" + server.getAddress().getPort() + "/g/sub/p.git");
    }

    @Test
    void creates_draft_mr_with_url_encoded_project_path_and_token_header() throws Exception {
        GitOpsService.PrInfo pr = new GitLabMrCreator("glpat-T").create(null, ref(),
                "develop", "netismaker/task-1-x", "제목", "본문");

        assertThat(pr.number()).isEqualTo(12);
        assertThat(pr.url()).isEqualTo("http://gl/g/sub/p/-/merge_requests/12");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("POST /api/v4/projects/g%2Fsub%2Fp/merge_requests | glpat-T | ");
        assertThat(seen.get(0)).contains("\"source_branch\":\"netismaker/task-1-x\"")
                .contains("\"target_branch\":\"develop\"")
                .contains("\"title\":\"Draft: 제목\"")
                .contains("\"description\":\"본문\"");
    }

    @Test
    void conflict_returns_the_existing_open_mr_for_the_source_branch() throws Exception {
        postStatus = 409;
        postBody = "{\"message\":[\"Another open merge request already exists for this source branch: !7\"]}";

        GitOpsService.PrInfo pr = new GitLabMrCreator("glpat-T").create(null, ref(),
                "develop", "netismaker/task-1-x", "t", "b");

        assertThat(pr.number()).isEqualTo(7);
        assertThat(seen.get(1)).startsWith(
                "GET /api/v4/projects/g%2Fsub%2Fp/merge_requests?state=opened&source_branch=netismaker%2Ftask-1-x");
    }

    @Test
    void auth_failure_is_reported_without_leaking_the_token() {
        postStatus = 401;
        postBody = "{\"message\":\"401 Unauthorized\"}";
        assertThatThrownBy(() -> new GitLabMrCreator("glpat-T").create(null, ref(), "develop", "h", "t", "b"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("GitLab 인증 실패")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("glpat-T"));
    }

    @Test
    void missing_token_fails_before_any_request() {
        assertThatThrownBy(() -> new GitLabMrCreator(" ").create(null, ref(), "develop", "h", "t", "b"))
                .isInstanceOf(IOException.class).hasMessageContaining("GITLAB_TOKEN");
        assertThat(seen).isEmpty();
    }

    @Test
    void supports_only_gitlab_refs() {
        assertThat(new GitLabMrCreator("x").supports(ref())).isTrue();
        assertThat(new GitLabMrCreator("x").supports(RepoRef.fromSnapshot("a/b", null))).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.GitLabMrCreatorTest"`
Expected: 컴파일 실패 — `GitLabMrCreator` 없음.

- [ ] **Step 3: `MergeRequestCreator.java`**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;

import java.io.File;
import java.io.IOException;

/** push된 head 브랜치로 Draft PR(GitHub) / Draft MR(GitLab)을 만든다. */
public interface MergeRequestCreator {

    boolean supports(RepoRef ref);

    GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                String title, String body) throws IOException, InterruptedException;
}
```

- [ ] **Step 4: `GitHubPrCreator.java`** — `GitOpsService.createDraftPr`의 기존 로직을 그대로 옮긴다

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** gh CLI로 Draft PR 생성. 사전 조건: gh 설치 + (`gh auth login` 또는 GH_TOKEN). */
@Slf4j
public class GitHubPrCreator implements MergeRequestCreator {

    private static final long GH_TIMEOUT_SECONDS = 120;
    private static final Pattern PR_URL_PATTERN = Pattern.compile("https?://[\\w./-]*/pull/(\\d+)");

    private final String githubPat;

    public GitHubPrCreator(String githubPat) {
        this.githubPat = githubPat;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return !ref.isGitlab();
    }

    @Override
    public GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                       String title, String body) throws IOException, InterruptedException {
        List<String> cmd = List.of("gh", "pr", "create",
                "--repo", ref.path(),
                "--draft",
                "--base", baseBranch,
                "--head", headBranch,
                "--title", title,
                "--body", body);
        Map<String, String> env = new HashMap<>();
        // GitHub PAT가 있고 gh auth login을 안 했어도 GH_TOKEN으로 동작.
        if (githubPat != null && !githubPat.isBlank()) {
            env.put("GH_TOKEN", githubPat);
        }
        String stdout = ProcessRunner.run(worktreeDir, cmd, env, GH_TIMEOUT_SECONDS).stdout().trim();
        Matcher m = PR_URL_PATTERN.matcher(stdout);
        if (!m.find()) {
            throw new IOException("gh pr create 출력에서 PR URL 파싱 실패. 출력:\n" + GitRemotes.mask(stdout));
        }
        int prNumber = Integer.parseInt(m.group(1));
        log.info("PR 생성: #{} {}", prNumber, m.group());
        return new GitOpsService.PrInfo(m.group(), prNumber);
    }
}
```

- [ ] **Step 5: `GitLabMrCreator.java`**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitLab REST API로 Draft MR 생성 (glab CLI 의존 없음).
 *   POST {apiBase}/api/v4/projects/{url-encoded path}/merge_requests
 * 토큰 scope: api. 같은 source 브랜치의 열린 MR이 이미 있으면(409) 그 MR을 돌려준다 —
 * 재시도 때 "MR은 있는데 작업은 실패"가 되지 않게.
 */
@Slf4j
public class GitLabMrCreator implements MergeRequestCreator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitLabMrCreator(String token) {
        this.token = token;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return ref.isGitlab();
    }

    @Override
    public GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                       String title, String body) throws IOException, InterruptedException {
        if (token == null || token.isBlank()) {
            throw new IOException("GITLAB_TOKEN 미설정 — GitLab MR을 만들 수 없습니다");
        }
        String mrs = ref.apiBase() + "/api/v4/projects/" + enc(ref.path()) + "/merge_requests";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_branch", headBranch);
        payload.put("target_branch", baseBranch);
        payload.put("title", "Draft: " + title);
        payload.put("description", body);
        payload.put("remove_source_branch", false);

        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(mrs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8)));

        if (res.statusCode() == 201) {
            return toInfo(JSON.readTree(res.body()));
        }
        if (res.statusCode() == 409) {
            HttpResponse<String> list = send(HttpRequest.newBuilder(
                    URI.create(mrs + "?state=opened&source_branch=" + enc(headBranch))).GET());
            JsonNode arr = list.statusCode() == 200 ? JSON.readTree(list.body()) : null;
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                log.info("기존 열린 MR 재사용: {}", arr.get(0).path("web_url").asText());
                return toInfo(arr.get(0));
            }
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IOException("GitLab 인증 실패(" + res.statusCode() + ") — 토큰 scope(api) 확인");
        }
        String snippet = res.body() == null ? "" : res.body().substring(0, Math.min(500, res.body().length()));
        throw new IOException("GitLab MR 생성 실패(" + res.statusCode() + "): " + GitRemotes.mask(snippet));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.header("PRIVATE-TOKEN", token).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static GitOpsService.PrInfo toInfo(JsonNode mr) throws IOException {
        String url = mr.path("web_url").asText(null);
        int iid = mr.path("iid").asInt(0);
        if (url == null || iid <= 0) {
            throw new IOException("GitLab MR 응답에 web_url/iid가 없습니다");
        }
        log.info("MR 생성: !{} {}", iid, url);
        return new GitOpsService.PrInfo(url, iid);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 6: `GitOpsService.java`** — `createDraftPr`를 위임으로 교체

삭제: `GH_TIMEOUT_SECONDS`, `PR_URL_PATTERN` 상수와 사용하지 않게 된 import(`ArrayList`, `HashMap`, `Map`, `Matcher`, `Pattern`). 필드·생성자·메서드:

```java
    private final WorkerProperties props;
    private final List<MergeRequestCreator> creators;

    public GitOpsService(WorkerProperties props) {
        this.props = props;
        this.creators = List.of(new GitHubPrCreator(props.githubPat()),
                new GitLabMrCreator(props.gitlabToken()));
    }

    /**
     * Draft PR(GitHub) / Draft MR(GitLab) 생성.
     * @return (url, number) — GitLab은 MR의 web_url과 iid
     */
    public PrInfo createDraftPr(File worktreeDir, RepoRef ref,
                                String baseBranch, String headBranch,
                                String title, String body)
            throws IOException, InterruptedException {
        for (MergeRequestCreator c : creators) {
            if (c.supports(ref)) {
                return c.create(worktreeDir, ref, baseBranch, headBranch, title, body);
            }
        }
        throw new IOException("지원하지 않는 호스트: " + ref.host());
    }
```

import: `com.hamonsoft.netismaker.git.RepoRef`. 클래스 javadoc 첫 줄을 `worktree에서 구현 산출물을 commit/push하고 Draft PR(GitHub) 또는 Draft MR(GitLab)을 생성한다.`로, "사전 조건 2)"에 `GitLab은 GITLAB_TOKEN(scope: api)만 있으면 됨 — CLI 불필요`를 추가.

- [ ] **Step 7: `WorkerMainLoop.java`**

265–267행 부근: `gitOps.createDraftPr(wt.dir(), task.githubRepo(), …` → `gitOps.createDraftPr(wt.dir(), task.repoRef(), …` (나머지 인자 그대로).

`renderPrBody`의 첫 줄과 끝 3줄을 교체:

```java
        boolean gitlab = task.repoRef().isGitlab();
        ...
        return "## netisMaker 자동 생성 " + (gitlab ? "MR" : "PR") + "\n\n"
        ...
                + "\n\n---\n"
                + "🤖 Generated with netisMaker by Claude Code.\n"
                + (gitlab ? "리뷰 후 Draft 표시를 해제하세요.\n" : "리뷰 후 Ready for review로 전환하세요.\n");
```

(`boolean gitlab = …` 선언은 메서드 첫 줄에 둔다. 가운데 본문은 그대로.)

- [ ] **Step 8: 통과 확인**

Run: `./gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.GitLabMrCreatorTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest"`
Expected: PASS (5개 + 계약 테스트). `GitHubPrCreator`/`GitLabMrCreator`는 `@Component`가 아니므로 계약 테스트 대상이 아니다.

Run: `./gradlew.bat compileJava compileTestJava -q` → 오류 없음.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon src/test/java/com/hamonsoft/netismaker/workerdaemon/GitLabMrCreatorTest.java
git commit -m "feat(worker): GitLab Draft MR 생성(REST API) — MergeRequestCreator로 호스트 분기

409(같은 브랜치의 열린 MR)면 기존 MR을 돌려줘 재시도가 실패로 기록되지 않는다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: 인터뷰 서비스 — 인증 clone + 마스킹

**Files:**
- Create: `netismaker-interview-service/src/sdk/gitRemote.ts`
- Modify: `netismaker-interview-service/src/config.ts`, `src/types.ts`, `src/runner/repoPrepare.ts`, `src/runner/interviewRunner.ts:30,135-143,246-251`, `src/index.ts:16-25`, `.env.example`
- Test: `netismaker-interview-service/test/gitRemote.test.ts`(신규), `test/repoPrepare.test.ts`

**Interfaces:**
- Consumes: claim의 `gitUrl?`, `repoHost?`
- Produces: `buildCloneUrl(repo: { githubRepo: string; gitUrl?: string | null; repoHost?: string | null }, tokens: GitTokens): string`; `maskSecrets(text: string): string`; `interface GitTokens { githubPat?: string; gitlabToken?: string }`; `RepoInput`에 `gitUrl?`, `repoHost?`, `tokens?` 추가; `Config.githubPat?`, `Config.gitlabToken?`.

- [ ] **Step 1: 실패하는 테스트** — `test/gitRemote.test.ts`

```ts
import { describe, expect, it } from 'vitest';
import { buildCloneUrl, maskSecrets } from '../src/sdk/gitRemote.js';

describe('buildCloneUrl', () => {
  it('legacy claim (no gitUrl) → anonymous github url, or PAT url when configured', () => {
    expect(buildCloneUrl({ githubRepo: 'acme/widgets' }, {})).toBe('https://github.com/acme/widgets.git');
    expect(buildCloneUrl({ githubRepo: 'acme/widgets' }, { githubPat: 'ghp_A' })).toBe(
      'https://oauth2:ghp_A@github.com/acme/widgets.git',
    );
  });

  it('gitlab claim → token injected into the claim gitUrl', () => {
    const claim = {
      githubRepo: 'product/netis/web/netis-v7.0',
      gitUrl: 'https://gitlab.hamon.vip/product/netis/web/netis-v7.0.git',
      repoHost: 'gitlab',
    };
    expect(buildCloneUrl(claim, { gitlabToken: 'glpat-B', githubPat: 'ghp_A' })).toBe(
      'https://oauth2:glpat-B@gitlab.hamon.vip/product/netis/web/netis-v7.0.git',
    );
    expect(buildCloneUrl(claim, {})).toBe(claim.gitUrl);
  });

  it('host falls back to the gitUrl hostname when repoHost is missing', () => {
    expect(
      buildCloneUrl({ githubRepo: 'g/p', gitUrl: 'https://gitlab.hamon.vip/g/p.git' }, { gitlabToken: 'T', githubPat: 'P' }),
    ).toBe('https://oauth2:T@gitlab.hamon.vip/g/p.git');
    expect(
      buildCloneUrl({ githubRepo: 'a/b', gitUrl: 'https://github.com/a/b.git' }, { gitlabToken: 'T', githubPat: 'P' }),
    ).toBe('https://oauth2:P@github.com/a/b.git');
  });
});

describe('maskSecrets', () => {
  it('hides credentials embedded in urls', () => {
    const masked = maskSecrets('Command failed: git clone https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git /wd');
    expect(masked).toBe('Command failed: git clone https://***@gitlab.hamon.vip/g/p.git /wd');
    expect(maskSecrets('plain https://github.com/a/b.git')).toBe('plain https://github.com/a/b.git');
  });
});
```

`test/repoPrepare.test.ts` — 기존 2개 테스트는 그대로 두고(레거시 claim 동작 보존 검증) 아래 3개를 `describe` 안에 추가:

```ts
  it('clones with an authenticated gitlab url built from the claim', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      {
        githubRepo: 'g/sub/p',
        githubBranch: 'develop',
        workDir: '/wd/s1',
        gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git',
        repoHost: 'gitlab',
        tokens: { gitlabToken: 'glpat-B' },
      },
      { run, exists: vi.fn().mockReturnValue(false) },
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      ['clone', '--depth', '1', '--branch', 'develop', 'https://oauth2:glpat-B@gitlab.hamon.vip/g/sub/p.git', '/wd/s1'],
      expect.any(Object),
    );
  });

  it('refreshes origin with the current authenticated url before fetching an existing checkout', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/s2', tokens: { githubPat: 'ghp_A' } },
      { run, exists: vi.fn().mockReturnValue(true) },
    );
    const cmds = run.mock.calls.map((c) => [c[0], ...(c[1] as string[])].join(' '));
    expect(cmds[0]).toBe('git remote set-url origin https://oauth2:ghp_A@github.com/acme/widgets.git');
    expect(cmds[1]).toBe('git fetch --depth 1 origin main');
    expect(cmds[2]).toBe('git reset --hard origin/main');
  });

  it('never leaks the token through a git failure', async () => {
    const run = vi
      .fn()
      .mockRejectedValue(new Error('Command failed: git clone https://oauth2:glpat-B@gitlab.hamon.vip/g/p.git /wd'));
    await expect(
      ensureRepo(
        {
          githubRepo: 'g/p',
          githubBranch: 'main',
          workDir: '/wd/s3',
          gitUrl: 'https://gitlab.hamon.vip/g/p.git',
          repoHost: 'gitlab',
          tokens: { gitlabToken: 'glpat-B' },
        },
        { run, exists: vi.fn().mockReturnValue(false) },
      ),
    ).rejects.toThrow(/https:\/\/\*\*\*@gitlab\.hamon\.vip/);
    await expect(
      ensureRepo(
        { githubRepo: 'g/p', githubBranch: 'main', workDir: '/wd/s3', gitUrl: 'https://gitlab.hamon.vip/g/p.git', tokens: { gitlabToken: 'glpat-B' } },
        { run, exists: vi.fn().mockReturnValue(false) },
      ),
    ).rejects.not.toThrow(/glpat-B/);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/gitRemote.test.ts test/repoPrepare.test.ts`
Expected: FAIL — `../src/sdk/gitRemote.js` 없음.

- [ ] **Step 3: `src/sdk/gitRemote.ts`**

```ts
/** 호스트별 토큰. Java GitRemotes와 같은 규칙(스펙 2026-09-21 §8). */
export interface GitTokens {
  githubPat?: string;
  gitlabToken?: string;
}

export interface RepoLocator {
  githubRepo: string;
  /** 정식 Git URL. 구버전 API claim에는 없다 → GitHub로 간주. */
  gitUrl?: string | null;
  repoHost?: string | null;
}

function hostOf(repo: RepoLocator): 'github' | 'gitlab' {
  if (repo.repoHost === 'gitlab' || repo.repoHost === 'github') return repo.repoHost;
  if (!repo.gitUrl) return 'github';
  const m = /^https?:\/\/(?:[^@/\s]+@)?([^/:\s]+)/.exec(repo.gitUrl);
  return m && m[1].toLowerCase() !== 'github.com' ? 'gitlab' : 'github';
}

/**
 * clone/fetch용 URL. 토큰이 있으면 https://oauth2:<token>@host/path.git, 없으면 평문 URL
 * ('oauth2:@host' 같은 빈 비밀번호 URL은 GitHub가 거부한다).
 */
export function buildCloneUrl(repo: RepoLocator, tokens: GitTokens): string {
  const plain = repo.gitUrl || `https://github.com/${repo.githubRepo}.git`;
  const token = hostOf(repo) === 'gitlab' ? tokens.gitlabToken : tokens.githubPat;
  if (!token) return plain;
  return plain.replace(/^(https?:\/\/)/, (_all, scheme: string) => `${scheme}oauth2:${token}@`);
}

/** "://user:secret@" → "://***@". git 오류가 세션 실패 사유·로그로 나가기 전에 적용. */
export function maskSecrets(text: string): string {
  return text.replace(/(?<=:\/\/)[^/@\s:]+:[^/@\s]+@/g, '***@');
}
```

- [ ] **Step 4: `src/runner/repoPrepare.ts`**

import 추가: `import { buildCloneUrl, maskSecrets, type GitTokens } from '../sdk/gitRemote.js';`

`RepoInput`에 필드 추가(`githubRepo` 주석도 갱신):

```ts
  githubRepo: string; // GitHub "owner/repo" 또는 GitLab 프로젝트 전체 경로
  githubBranch: string;
  workDir: string; // = claim.workDir, also options.cwd
  /** 정식 Git URL + 호스트(claim에서 전달). 없으면 GitHub로 간주. */
  gitUrl?: string | null;
  repoHost?: string | null;
  /** clone/fetch 인증 토큰. 없으면 익명(공개 레포만). */
  tokens?: GitTokens;
```

`ensureRepo` 함수(javadoc 포함)를 교체:

```ts
/**
 * Ensures the repo is checked out at workDir BEFORE the first turn AND before every resume
 * (resume is cwd-pinned — spike 02). Fresh => shallow clone; existing => fetch + hard reset.
 * URL은 claim의 gitUrl + 호스트별 토큰으로 만든다(비공개 GitHub/사내 GitLab). 기존 체크아웃도
 * 매번 origin을 현재 인증 URL로 맞춰 토큰 교체가 재clone 없이 반영된다.
 * git 오류 메시지는 자격증명을 가린 뒤 다시 던진다 — 세션 실패 사유로 화면에 노출되기 때문.
 */
export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
  const { githubBranch, workDir } = input;
  const abort = input.signal ? { signal: input.signal } : {};
  const url = buildCloneUrl(input, input.tokens ?? {});
  try {
    if (ops.exists(join(workDir, '.git'))) {
      await ops.run('git', ['remote', 'set-url', 'origin', url], { cwd: workDir, ...abort });
      await ops.run('git', ['fetch', '--depth', '1', 'origin', githubBranch], { cwd: workDir, ...abort });
      await ops.run('git', ['reset', '--hard', `origin/${githubBranch}`], { cwd: workDir, ...abort });
      return;
    }
    ops.mkdir?.(workDir);
    await ops.run('git', ['clone', '--depth', '1', '--branch', githubBranch, url, workDir], { ...abort });
  } catch (e) {
    if (e instanceof Error) {
      e.message = maskSecrets(e.message);
      const withStd = e as Error & { stderr?: unknown; cmd?: unknown };
      if (typeof withStd.stderr === 'string') withStd.stderr = maskSecrets(withStd.stderr);
      if (typeof withStd.cmd === 'string') withStd.cmd = maskSecrets(withStd.cmd);
    }
    throw e;
  }
}
```

(기존 첫 번째 테스트는 `githubRepo`만 주므로 URL이 `https://github.com/acme/widgets.git` 그대로다. 기존 두 번째 테스트는 `toContain`이라 `remote set-url` 호출이 추가돼도 통과한다.)

- [ ] **Step 5: `types.ts`** — `InterviewClaimResponse`의 `kind?` 아래에 추가

```ts
  /**
   * 정식 Git URL 스냅샷 + 호스트('github' | 'gitlab'). 구버전 백엔드는 필드가 없다 —
   * 미존재는 GitHub로 취급(kind/attachments 선례). clone URL 조립에만 쓴다.
   */
  gitUrl?: string | null;
  repoHost?: 'github' | 'gitlab' | null;
```

- [ ] **Step 6: `config.ts`** — `Config`에 필드 2개, `loadConfig`에 2줄

```ts
  /** 비공개 GitHub 레포 clone용(선택). Java 워커와 같은 GITHUB_PAT. */
  githubPat?: string;
  /** 사내 GitLab clone용(선택). Java 워커와 같은 GITLAB_TOKEN. */
  gitlabToken?: string;
```

```ts
    githubPat: env.GITHUB_PAT || undefined,
    gitlabToken: env.GITLAB_TOKEN || undefined,
```

- [ ] **Step 7: 러너 배선** — `interviewRunner.ts`, `index.ts`

`interviewRunner.ts`의 deps 인터페이스(`ensureRepo?:` 선언 근처)에 추가:

```ts
  /** clone/fetch 인증 토큰(GITHUB_PAT / GITLAB_TOKEN). 미지정이면 익명. */
  gitTokens?: GitTokens;
```

import: `import type { GitTokens } from '../sdk/gitRemote.js';`

`await this.ensureRepo({ … })` 호출(246행 부근)을 교체:

```ts
      await this.ensureRepo({
        githubRepo: claim.githubRepo,
        githubBranch: claim.githubBranch,
        workDir: claim.workDir,
        gitUrl: claim.gitUrl,
        repoHost: claim.repoHost,
        tokens: this.deps.gitTokens,
        signal: controller.signal,
      });
```

`index.ts`의 `new InterviewRunner(client, realQuery, { … })` 옵션에 추가:

```ts
    gitTokens: { githubPat: cfg.githubPat, gitlabToken: cfg.gitlabToken },
```

기동 로그에 토큰 **유무만** 표시(값 금지): `console.log` 템플릿 끝에 `` git=[${cfg.githubPat ? 'github' : ''}${cfg.gitlabToken ? ' gitlab' : ''}]`` 추가.

`test/interviewRunner.test.ts`에서 `ensureRepo`가 **정확히** `{githubRepo, githubBranch, workDir}`로 호출됐다고 단언하는 곳(37행 부근, 425–430행 부근)을 `expect.objectContaining({ githubRepo: 'acme/widgets', githubBranch: 'main', workDir: … })`로 바꾼다(기존 기대값의 나머지 키는 그대로 유지).

- [ ] **Step 8: `.env.example`** — `WORKER_ID=` 줄 아래에 추가

```bash
# 비공개 레포 clone 인증(선택). Java 워커와 같은 값. 없으면 공개 레포만 clone 가능.
# GITHUB_PAT=ghp_...
# 사내 GitLab(read_repository). 호스트는 claim의 gitUrl에서 오므로 base URL 설정은 필요 없다.
# GITLAB_TOKEN=glpat-...
```

- [ ] **Step 9: 통과 확인**

Run: `cd netismaker-interview-service && npx vitest run && npx tsc -p tsconfig.json --noEmit`
Expected: 전체 PASS, 타입 오류 없음.

- [ ] **Step 10: Commit**

```bash
git add netismaker-interview-service
git commit -m "feat(interview): 인증 clone(GitHub PAT/GitLab 토큰) + git 오류 자격증명 마스킹

claim의 gitUrl/repoHost로 URL을 만든다. 익명 clone만 하던 탓에 비공개 GitHub 레포의
질문/인터뷰가 실패하던 문제도 함께 해결.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: 프론트 — catalogId 브랜치 조회, 카탈로그 관리 문구, PR/MR 표기

**Files:**
- Create: `frontend/composables/mergeRequestLabel.ts`, `frontend/composables/mergeRequestLabel.spec.ts`
- Modify: `frontend/pages/questions/index.vue`, `frontend/pages/tasks/index.vue`, `frontend/pages/admin/repo-catalog.vue`, `frontend/pages/tasks/[id].vue`, `frontend/components/tasks/TaskActionSheet.vue`, `TaskCardCompact.vue`, `TaskDetailMobile.vue`, `frontend/composables/taskStages.ts:441-445`
- Test: `frontend/test/questions-index.spec.ts`, `frontend/test/tasks-form-repo-select.spec.ts`, `frontend/test/repo-catalog-admin.spec.ts`

**Interfaces:**
- Consumes: `GET /api/repos/branches?catalogId=`, 카탈로그 View의 `host`
- Produces: `mrNoun(prUrl?: string | null): 'PR' | 'MR'`, `mrRef(prUrl: string | null | undefined, n: number): string`

- [ ] **Step 1: 실패하는 테스트** — `frontend/composables/mergeRequestLabel.spec.ts`

```ts
import { describe, it, expect } from 'vitest'
import { mrNoun, mrRef } from './mergeRequestLabel'

describe('mergeRequestLabel', () => {
  it('GitHub PR URL과 URL 없음은 PR', () => {
    expect(mrNoun('https://github.com/acme/widgets/pull/13')).toBe('PR')
    expect(mrNoun(null)).toBe('PR')
    expect(mrRef('https://github.com/acme/widgets/pull/13', 13)).toBe('PR #13')
  })

  it('GitLab MR URL은 MR !n', () => {
    const url = 'https://gitlab.hamon.vip/product/netis/web/netis-v7.0/-/merge_requests/12'
    expect(mrNoun(url)).toBe('MR')
    expect(mrRef(url, 12)).toBe('MR !12')
  })
})
```

`frontend/test/questions-index.spec.ts` — 레이스 가드 테스트의 mock과 픽스처를 catalogId 기준으로 바꾼다:
- 카탈로그 픽스처 각 항목에 `host: 'github'` 추가(파일 안의 모든 `/api/repo-catalog` 응답).
- `opts?.params?.repo === 'org/a'` → `opts?.params?.catalogId === 1`, `opts?.params?.repo === 'org/b'` → `opts?.params?.catalogId === 2`.

그리고 같은 `describe` 안에 추가:

```ts
  it('GitLab 카탈로그 항목(ownerRepo=다단계 경로)도 catalogId로 브랜치를 불러온다', async () => {
    useApiMock.mockImplementation((url: string, opts?: { params?: any }) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 5, alias: 'Netis7(GitLab)', host: 'gitlab', ownerRepo: 'product/netis/web/netis-v7.0', defaultBranch: null },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 5)
        return Promise.resolve({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'z' }] })
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.onRepoSelected(5)
    await flushPromises()
    expect(vm.draft.githubBranch).toBe('develop')
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 5 } })
    w.unmount()
  })

  it('지원하지 않는 호스트(other)는 조용히 멈추지 않고 이유를 보여 준다', async () => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 6, alias: 'Bitbucket', host: 'other', ownerRepo: null, defaultBranch: null },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.onRepoSelected(6)
    await flushPromises()
    expect(vm.repoStatus).toBe('error')
    expect(vm.repoStatusMsg).toContain('지원하지 않는 호스트')
    expect(useApiMock).not.toHaveBeenCalledWith('/api/repos/branches', expect.anything())
    w.unmount()
  })
```

(`vm.repoStatus`/`vm.repoStatusMsg`가 `<script setup>`에서 노출되지 않아 `undefined`면, 같은 파일의 기존 테스트가 `vm.draft`에 접근하는 방식 그대로 동작하는지 먼저 확인한다 — `<script setup>`의 top-level ref는 test-utils `vm`에서 접근 가능하다.)

`frontend/test/tasks-form-repo-select.spec.ts` — 같은 방식으로 픽스처에 `host: 'github'` 추가, `/api/repos/branches` mock·단언의 `params.repo` → `params.catalogId`(해당 카탈로그 항목의 `id` 값).

`frontend/test/repo-catalog-admin.spec.ts` — 목록 픽스처에 GitLab 행 1개 추가 후 단언 추가:

```ts
    // host:'gitlab' 행은 경고색(orange) 칩이 아니고, 'other' 행만 "등록 불가" 표시
    expect(w.text()).toContain('gitlab')
```

(픽스처: `{ id: 2, alias: 'GL', gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git', host: 'gitlab', ownerRepo: 'g/sub/p', defaultBranch: null, description: null, enabled: true, createdBy: 'admin', createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z' }` — 기존 픽스처 객체의 키 구성에 맞춘다.)

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run composables/mergeRequestLabel.spec.ts test/questions-index.spec.ts test/tasks-form-repo-select.spec.ts`
Expected: FAIL — 모듈 없음 / `params.catalogId` 불일치.

- [ ] **Step 3: `frontend/composables/mergeRequestLabel.ts`**

```ts
// PR(GitHub) / MR(GitLab) 표기. 작업 DTO에 호스트 필드를 두지 않고 URL 모양으로 판정한다 —
// GitLab MR URL은 항상 '/-/merge_requests/<iid>' (스펙 2026-09-21 §2.1-5).
const GITLAB_MR = /\/-\/merge_requests\/\d+/

export function mrNoun(prUrl?: string | null): 'PR' | 'MR' {
  return prUrl && GITLAB_MR.test(prUrl) ? 'MR' : 'PR'
}

/** 'PR #13' | 'MR !12' */
export function mrRef(prUrl: string | null | undefined, n: number): string {
  return mrNoun(prUrl) === 'MR' ? `MR !${n}` : `PR #${n}`
}
```

- [ ] **Step 4: `pages/questions/index.vue`** — 4곳

`RepoCatalogEntry`에 `host: string` 추가(`ownerRepo` 위).

`let inflightRepo = ''` → `let inflightRepo = 0 // 응답 도착 시 최신 선택(catalogId)과 일치하는지 가드`.

`loadBranches`의 시그니처·요청을 교체(본문의 가드 비교는 그대로 `inflightRepo !== repo`):

```ts
async function loadBranches(repo: number) {
```
```ts
    }>('/api/repos/branches', { params: { catalogId: repo } })
```

catch의 기본 문구: `'레포를 찾을 수 없거나 비공개 레포입니다'` → `'레포를 찾을 수 없거나 접근 권한이 없습니다'`.

`onRepoSelected` 교체:

```ts
function onRepoSelected(catalogId: number | null) {
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry) return
  if (entry.host === 'other') {
    repoStatus.value = 'error'
    repoStatusMsg.value = '이 레포는 지원하지 않는 호스트입니다 (GitHub/사내 GitLab만 가능)'
    return
  }
  loadBranches(entry.id).then(() => {
    // 늦게 도착한 이전 레포의 콜백이 현재 선택을 덮어쓰지 않게 — loadBranches 내부 가드와 동일 기준.
    if (inflightRepo !== entry.id) return
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}
```

- [ ] **Step 5: `pages/tasks/index.vue`** — 같은 변경

`RepoCatalogEntry`에 `host: string`. `let inflightRepo = ''` → `let inflightRepo = 0`. `loadBranches(repo: string)` → `loadBranches(repo: number)`, `{ params: { repo } }` → `{ params: { catalogId: repo } }`. 404 기본 문구를 위와 같게. 주석 `// 별칭 선택 → ownerRepo로 브랜치 로드 …` → `// 별칭 선택 → catalogId로 브랜치 로드 + 기본 브랜치 프리필`. `onRepoSelected`:

```ts
function onRepoSelected(catalogId: number | null) {
  draft.githubBranch = ''
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry) return
  if (entry.host === 'other') {
    repoStatus.value = 'error'
    repoStatusMsg.value = '이 레포는 지원하지 않는 호스트입니다 (GitHub/사내 GitLab만 가능)'
    return
  }
  loadBranches(entry.id).then(() => {
    if (inflightRepo !== entry.id) return
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}
```

570행 `PR #{{ c.task.implementation.prNumber }}` → `{{ mrRef(c.task.implementation.prUrl, c.task.implementation.prNumber) }}`, 그리고 `<script setup>` import에 `import { mrRef } from '~/composables/mergeRequestLabel'`.

- [ ] **Step 6: `pages/admin/repo-catalog.vue`**

배너 마지막 줄 교체:

```
      (GitHub와 사내 GitLab 레포를 등록할 수 있습니다. 그 밖의 호스트는 저장은 되지만 작업 등록은 불가)
```

칩 교체:

```vue
          <q-chip
            v-if="props.row.host === 'gitlab'"
            size="sm"
            dense
            color="blue-grey-2"
            text-color="grey-9"
            label="gitlab"
          />
          <q-chip
            v-else-if="props.row.host !== 'github'"
            size="sm"
            dense
            color="orange-3"
            text-color="grey-9"
            :label="`${props.row.host} · 등록 불가`"
          />
```

표 컬럼 라벨 `label: 'owner/repo'` → `label: '경로'`. 입력란:

```vue
            placeholder="https://github.com/owner/repo.git 또는 https://gitlab.hamon.vip/group/sub/project.git"
            ...
            hint="GitHub: 전체 URL · owner/repo · git@… / 사내 GitLab: 전체 URL 필수 (서버가 정규화)"
```

- [ ] **Step 7: PR/MR 표기 교체** — 각 파일 `<script setup>`에 `import { mrNoun, mrRef } from '~/composables/mergeRequestLabel'`(쓰는 것만)

`pages/tasks/[id].vue`:
- 150행 `'…구현 + Draft PR 생성합니다.'` → `'…구현 + Draft PR/MR을 생성합니다.'`
- 417행 `` parts.push(`PR #${t.implementation.prNumber}`) `` → `parts.push(mrRef(t.implementation.prUrl, t.implementation.prNumber))`
- 489행 `` :label="`PR #${task.implementation.prNumber} 열기`" `` 형태 → `` :label="`${mrRef(task.implementation.prUrl, task.implementation.prNumber)} 열기`" ``(해당 줄의 실제 접미 문구를 유지)
- 542행 `PR #{{ task.implementation.prNumber }} 열기` → `{{ mrRef(task.implementation.prUrl, task.implementation.prNumber) }} 열기`
- 746행 `` :label="`PR #${task.implementation.prNumber}`" `` → `:label="mrRef(task.implementation.prUrl, task.implementation.prNumber)"`
- 753행 `PR URL` → `{{ mrNoun(task.implementation.prUrl) }} URL`
- 974행 `'…구현 + Draft PR 생성'` → `'…구현 + Draft PR/MR 생성'`

`components/tasks/TaskActionSheet.vue:96`: `PR #{{ task.implementation.prNumber }} 열기` → `{{ mrRef(task.implementation.prUrl, task.implementation.prNumber) }} 열기`
`components/tasks/TaskCardCompact.vue:135`: `PR #{{ task.implementation.prNumber }}` → `{{ mrRef(task.implementation.prUrl, task.implementation.prNumber) }}` (여러 줄에 걸친 보간이면 닫는 `}}`까지 포함해 교체)
`components/tasks/TaskDetailMobile.vue:165`: `` `PR #${task.implementation.prNumber}` `` → `mrRef(task.implementation.prUrl, task.implementation.prNumber)`

`composables/taskStages.ts` `case 'PR_CREATED'` 교체 + 파일 상단 import `import { mrNoun } from './mergeRequestLabel'`:

```ts
    case 'PR_CREATED': {
      const noun = mrNoun(task.implementation?.prUrl)
      if (isAdmin) return { text: `${noun} 생성됨 — 배포할 수 있습니다`, primary: { label: '배포', icon: 'rocket_launch', kind: 'deploy' } }
      return task.implementation?.prUrl
        ? { text: `${noun} 생성됨`, primary: { label: `${noun} 열기`, icon: 'open_in_new', kind: 'open-pr' } }
        : { text: `${noun} 생성됨` }
    }
```

GitHub 작업의 문구는 글자 하나 바뀌지 않으므로 기존 spec(`PR #13`, `PR 생성됨`)은 그대로 통과해야 한다.

- [ ] **Step 8: 통과 확인**

Run: `cd frontend && npx vitest run`
Expected: 전체 PASS. 실패가 있으면 그 spec의 카탈로그 픽스처에 `host`가 빠졌거나 `params.repo`를 단언하는 곳이 남은 것이다.

Run: `cd frontend && npx nuxi typecheck`
Expected: 새 오류 없음(기존 경고는 무시).

- [ ] **Step 9: Commit**

```bash
git add frontend
git commit -m "feat(front): catalogId로 브랜치 조회, GitLab 카탈로그 표기, PR/MR 문구 분기

지원하지 않는 호스트를 고르면 조용히 멈추지 않고 이유를 보여 준다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: 문서·운영 스크립트

**Files:**
- Modify: `CLAUDE.md`, `scripts/INFRA-CHECKLIST.md`, `scripts/start-all.sh`, `scripts/start-public.sh`

**Interfaces:**
- Consumes: 앞 Task의 환경변수 이름
- Produces: 운영자가 따라 할 설정 절차

- [ ] **Step 1: `CLAUDE.md`**

"핵심 설계 제약" 목록 끝에 추가:

```markdown
- **레포 호스트는 GitHub + 사내 GitLab 1곳**(2026-09-21, 스펙 `docs/superpowers/specs/2026-09-21-gitlab-support-design.md`). 저장소는 `git.RepoRef(host, path, gitUrl)`로 다루고 **`github.com` 문자열 조립·`owner/repo` 2단계 가정을 새로 만들지 말 것** — 인증 URL/로컬 폴더 키/마스킹은 `git.GitRemotes`, PR/MR 생성은 `workerdaemon.MergeRequestCreator`(GitHub=`gh`, GitLab=REST API) 한 곳. GitLab이면 `github_repo` 컬럼에 프로젝트 **전체 경로**가 들어가고 로컬 폴더는 `_gitlab/<경로의 '/'→'+'>`. 컬럼·상태값(`PR생성`)·`prUrl/prNumber` 이름은 그대로. claim의 `gitUrl/repoHost`는 optional(없으면 GitHub). 토큰은 로그·예외·`failure_reason`에 나가면 안 된다 → `GitRemotes.mask` / `maskSecrets`.
- **GitLab 설정**: API `GITLAB_BASE_URL` + `GITLAB_TOKEN`, 워커·인터뷰 서비스 `GITLAB_TOKEN`. 토큰 scope `api, read_repository, write_repository`. `GITLAB_BASE_URL`이 비면 GitLab URL은 `other`(등록 불가). GitLab 레포는 카탈로그에 **전체 URL**로 등록(bare `a/b`는 항상 GitHub). 배포 순서: 인터뷰 서비스+워커 → API → 프론트 → **마지막에** GitLab 카탈로그 등록.
```

"구현 단계 워커 사전 조건" 표에 행 추가:

```markdown
| `GITLAB_TOKEN` | 사내 GitLab clone/push + Draft MR | scope `api, read_repository, write_repository`. `glab` CLI 불필요 |
```

"자주 보는 코드" 표에 행 추가:

```markdown
| 레포 호스트 판별 · 인증 URL · 폴더 키 · 토큰 마스킹 | `util/RepoUrlParser.java`, `git/RepoRef.java`, `git/GitRemotes.java`, 인터뷰 서비스 `src/sdk/gitRemote.ts` |
| PR/MR 생성 (GitHub `gh` / GitLab REST) | `workerdaemon/MergeRequestCreator.java`, `GitHubPrCreator.java`, `GitLabMrCreator.java`, 위임 `GitOpsService.java` |
| PR/MR 화면 문구 | `frontend/composables/mergeRequestLabel.ts` (`prUrl` 모양으로 판정) |
```

- [ ] **Step 2: `scripts/INFRA-CHECKLIST.md`** D항 `.env` 예시에 두 줄 추가

```bash
  GITLAB_TOKEN=glpat-...          # 사내 GitLab (api, read_repository, write_repository)
```

E항에 체크 항목 추가: `- [ ] (GitLab 사용 시) API 환경에 GITLAB_BASE_URL=https://gitlab.hamon.vip + GITLAB_TOKEN 주입`

- [ ] **Step 3: mac 기동 스크립트의 env 전달 확인**

Run: `grep -n "GITHUB_PAT\|env \\\\" scripts/start-all.sh scripts/start-public.sh`
두 스크립트가 자식 프로세스에 `GITHUB_PAT`을 **명시적으로** 넘기는 곳이 있으면 같은 자리에 `GITLAB_BASE_URL="${GITLAB_BASE_URL:-}"`·`GITLAB_TOKEN="${GITLAB_TOKEN:-}"`를 추가한다. 명시 전달이 없고 부모 환경을 그대로 상속한다면(=`nohup env VAR=… cmd`에 추가 변수만 있는 형태) 변경하지 않는다 — 상속으로 전달된다. 어느 쪽이었는지 커밋 메시지에 한 줄 적는다.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md scripts/INFRA-CHECKLIST.md scripts/start-all.sh scripts/start-public.sh
git commit -m "docs: GitLab 지원 — 설계 제약·환경변수·배포 순서·코드 위치

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: 이 Windows 스택에서 실제 검증

**전제(운영자가 직접):** `C:\Users\mic\netis-maker\public.env`에 `GITLAB_BASE_URL=https://gitlab.hamon.vip`, `GITLAB_TOKEN=<api+read_repository+write_repository>`가 들어 있어야 한다. 토큰 값은 채팅·커밋·로그에 남기지 않는다. 없으면 이 Task는 **중단하고 운영자에게 요청**한다.

**Files:** 없음(검증만). 발견한 결함은 해당 Task의 파일에서 고치고 별도 커밋.

- [ ] **Step 1: 재기동(프론트 재빌드 포함)**

```powershell
cd C:\Users\mic\NetisMaker\commanCenter
.\scripts\stop-public.ps1
.\scripts\start-public.ps1 -WithWorkers -RebuildFront
```
Expected: postgres/auth/api/front/worker/interview 전부 `[ok]`. `.run\interview.log` 첫 줄에 `git=[… gitlab]`.

- [ ] **Step 2: git 도달성 (토큰 없이 확인 가능한 부분)**

Run: `git ls-remote https://gitlab.hamon.vip/product/netis/web/test.git 2>&1 | head -3`
Expected: 인증 요구 메시지(=호스트 도달 OK). 연결 자체가 안 되면 네트워크 문제이므로 중단·보고.

- [ ] **Step 3: 카탈로그 등록 → 확인 → 브랜치** (브라우저, 관리자 로그인은 운영자가)

`/admin/repo-catalog`에서 별칭 `GitLab-test`, URL `https://gitlab.hamon.vip/product/netis/web/test.git` 등록.
Expected: `gitlab` 칩(중립색), 경로 컬럼 `product/netis/web/test`, "확인" → 도달 가능 + 브랜치 수.

- [ ] **Step 4: 질문 세션**

`/questions`에서 `GitLab-test` 선택 → 브랜치 목록 로드 → "이 저장소의 최상위 폴더 구성을 알려줘" 전송.
Expected: 답변 도착. `%USERPROFILE%\netis-maker\interviews\_gitlab\product+netis+web+test\session-N\.git` 존재.
확인: `Select-String -Path .run\interview.log,.run\api.log -Pattern 'glpat-'` → 0건.

- [ ] **Step 5: 작업 등록 → 인터뷰 → 구현(Draft MR)**

작업 등록(같은 레포) → 관리자 승인(인터뷰 시작) → 인터뷰 답변 → 플랜 확정 → 구현 대기 → 워커 처리.
Expected: 상태 `PR생성`, 상세 화면에 `MR !N` 버튼·`MR URL`, 링크가 `https://gitlab.hamon.vip/product/netis/web/test/-/merge_requests/N`으로 열리고 제목이 `Draft: …`.
폴더: `repos\_gitlab\product+netis+web+test`, `worktrees\_gitlab\product+netis+web+test\task-N`, 잠금 `repos\_gitlab\product+netis+web+test.lock`.
확인: `Select-String -Path .run\worker.log -Pattern 'glpat-'` → 0건, DB `select failure_reason from com.task where failure_reason like '%glpat-%'` → 0행.

- [ ] **Step 6: GitHub 회귀**

공개 GitHub 레포(예: `micthebick84/netis-auth`)를 카탈로그에 등록 → 브랜치 조회 → 질문 1건.
Expected: 기존과 동일 동작, 폴더 `interviews\micthebick84\netis-auth\session-N`.

- [ ] **Step 7: 결과 기록**

검증 중 만든 Draft MR은 운영자에게 알리고 닫을지 묻는다(임의로 닫지 않는다). 결과를 옵시디언 `HamonSoft/netisMaker/`에 진행상황 노트로 남긴다.

---

## Self-Review 결과

**스펙 대응:** §3 식별·설정 → Task 2, 4 / §4 처리기 → Task 3 / §5.1 claim → Task 5 / §5.2 브랜치 → Task 4, 9 / §5.3 → 단순화로 삭제(Task 1) / §6 MR → Task 7 / §7 워커 → Task 6 / §8 인터뷰 서비스 → Task 8 / §9 프론트 → Task 9 / §10 오류 처리 → Task 4(호스트 거절·404 문구), 6(마스킹), 7(409·401) / §11 테스트 → 각 Task + Task 11 / §12 배포 순서·§14 문서 → Task 10.

**형식 일관성:** `RepoRef.fromSnapshot(path, gitUrl)`·`isGitlab()`·`apiBase()`·`GitRemotes(githubPat, gitlabToken)`·`localKey`·`mask`·`WorkerTaskResponse.repoRef()`·`GitOpsService.PrInfo(url, number)`·`createDraftPr(File, RepoRef, …)`가 모든 Task에서 같은 이름·시그니처다. TS 쪽은 `buildCloneUrl(repo, tokens)`·`maskSecrets`·`GitTokens`.

**알려진 한계(의도된 것):** GitLab 서브경로 설치 미지원, 여러 GitLab 호스트 미지원, bare `a/b` 입력은 항상 GitHub, MR 상태 추적 없음.
