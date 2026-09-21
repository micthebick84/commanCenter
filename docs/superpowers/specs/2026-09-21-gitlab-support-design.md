# 사내 GitLab 저장소 지원 설계

2026-09-21 · 브레인스토밍 승인 + 코드 전수 조사 반영본

## 1. 개요

레포 카탈로그에 등록한 **사내 GitLab 저장소**를 GitHub 저장소와 똑같이 쓸 수 있게 한다: 브랜치 조회 → 작업 등록 → 인터뷰 → 질문 → 구현(Draft MR 생성) → 디자인/배포.

현재는 카탈로그에 GitLab URL을 **저장만** 할 수 있다(`host='other'`). `RepoCatalogService.resolveForRegistration`이 비-GitHub를 400으로 거절하고, 그 뒤의 브랜치 조회·clone·PR 생성이 전부 `github.com` + `owner/repo`(정확히 2단계)를 전제로 한다.

## 2. 확정된 결정

| 결정 | 내용 |
|---|---|
| 호스트 범위 | **사내 GitLab 1곳만.** 설정 `GITLAB_BASE_URL` + `GITLAB_TOKEN`. 여러 GitLab 호스트·임의 Git 호스트·SSH 인증은 범위 밖 |
| 접근 | **A안** — 이미 저장돼 있는 `git_url`을 기준값으로 삼아 워커까지 전달하고, URL·토큰·로컬 폴더·PR/MR 생성을 호스트별 처리기 한 곳에서 맡는다 |
| MR 생성 | **GitLab REST API**(JDK `HttpClient`). `glab` CLI 의존 없음. 응답 JSON에서 URL·번호를 받음 |
| MR 형태 | GitHub와 동일하게 **Draft**(제목 `Draft: …`), 대상 브랜치 = 등록 시 선택한 브랜치 |
| 이름 유지 | DB 컬럼 `github_repo`/`github_branch`, 상태값 `PR_CREATED("PR생성")`, 필드 `prUrl`/`prNumber`는 **이름을 바꾸지 않는다**(한글 상태값은 DB 저장값이라 변경 = 데이터 마이그레이션). GitLab 작업은 화면 문구만 "MR"로 표시 |
| 하위 호환 | 새 전달 필드는 선택값. 없으면 GitHub로 간주 → 기존 작업·세션·구버전 프로세스와 혼재 가능 |
| 같이 고침 | ① 토큰이 예외 메시지→`failure_reason`→화면, DEBUG 로그로 새는 기존 버그 ② 인터뷰 서비스가 토큰 없이 익명 clone하는 문제(GitHub 비공개 레포 질문/인터뷰도 해결) |

기각: **B안**(`github_repo`에 `gitlab:` 접두어) — 전달 형식은 안 바뀌지만 정규식 5곳·폴더 경로 코드가 각자 접두어를 해석해야 하고 내부 표기가 프롬프트/활동 로그에 노출된다. **C안**(B + `glab` CLI) — 워커 PC마다 설치·인증 필요, 출력 파싱의 취약함이 그대로 남는다.

## 3. 저장소 식별과 설정

### 3.1 설정 키 (API · 워커 · 인터뷰 서비스 공통 환경변수)

| 환경변수 | 예 | 용도 |
|---|---|---|
| `GITLAB_BASE_URL` | `https://gitlab.hamon.vip` | GitLab 판별 기준 호스트 + REST API base. 끝 슬래시는 정리 |
| `GITLAB_TOKEN` | (비밀) | clone/fetch/push 인증 + MR 생성. 필요 scope: `api`, `read_repository`, `write_repository` |

- `application.yml`: `app.gitlab.base-url: ${GITLAB_BASE_URL:}`, `app.gitlab.token: ${GITLAB_TOKEN:}`
- `application-worker.yml`: `netis-maker.worker.gitlab-base-url`, `netis-maker.worker.gitlab-token` (`WorkerProperties`에 컴포넌트 추가)
- 인터뷰 서비스 `config.ts`: `gitlabBaseUrl?`, `gitlabToken?`, 그리고 지금까지 없던 `githubPat?`(`GITHUB_PAT`)
- `GITLAB_BASE_URL`이 비어 있으면 GitLab URL은 `other`로 분류 → **현재 동작과 동일**(기능 꺼짐).

### 3.2 `RepoUrlParser`

시그니처를 `parse(String input, String gitlabBaseUrl)`로 넓힌다(기존 1-인자 오버로드는 `gitlabBaseUrl=null`로 위임).

| 입력 | host | ownerRepo | canonicalUrl |
|---|---|---|---|
| `github.com` URL(https/http/ssh/scp), 또는 bare `owner/repo` | `github` | `owner/repo` | `https://github.com/owner/repo.git` (변경 없음) |
| 호스트가 `GITLAB_BASE_URL`의 호스트와 같은 URL (https/http, `git@host:path` scp, `ssh://`) | `gitlab` | **프로젝트 전체 경로**(2단계 이상, `.git`·끝 슬래시 제거). 예: `product/netis/web/package/netis-v7.0` | `{GITLAB_BASE_URL}/{path}.git` |
| 그 밖의 URL 모양 | `other` | `null` | 입력 정리본 (변경 없음) |

- GitLab 경로 검증: 세그먼트 2개 이상, 각 세그먼트 `^[A-Za-z0-9_][A-Za-z0-9_.-]*$`, `..` 금지. 위반 시 `IllegalArgumentException`.
- bare `a/b`는 계속 GitHub로 해석한다(기존 동작 보존). GitLab 레포는 **전체 URL로 입력**해야 한다 — 관리자 화면 문구로 안내.
- 호스트 비교는 대소문자 무시, 포트 포함 비교.

### 3.3 기존 카탈로그 행

`repo_catalog.host`/`owner_repo`는 저장 시점의 해석 결과(캐시)다. `GITLAB_BASE_URL` 설정 전에 등록돼 `other`로 저장된 GitLab 행이 있을 수 있으므로, `resolveForRegistration`·`check`·브랜치 조회는 **저장된 `host`를 믿지 않고 `git_url`을 다시 해석**한다. 목록/뷰 DTO도 재해석 값을 내보낸다. → **DB 마이그레이션 없음.** (관리자가 저장을 다시 누르면 행도 갱신된다.)

## 4. 호스트별 처리기 — `com.hamonsoft.netismaker.git`

### 4.1 `RepoRef`

```java
public record RepoRef(String host, String path, String gitUrl) {   // host: "github" | "gitlab"
    public static RepoRef of(String host, String path, String gitUrl);
    /** 전달 필드가 없는 구버전 데이터용: githubRepo(owner/repo)만으로 GitHub ref 생성 */
    public static RepoRef legacyGithub(String ownerRepo);
}
```

### 4.2 `GitRemotes` (순수 클래스, 토큰은 생성자로 주입)

```java
public final class GitRemotes {
    public GitRemotes(String githubPat, String gitlabBaseUrl, String gitlabToken);
    public String authenticatedUrl(RepoRef ref);   // 토큰 있으면 https://oauth2:<tok>@host/path.git, 없으면 gitUrl
    public static String localKey(RepoRef ref);    // 로컬 폴더 키(항상 2단계 상대경로)
    public static String mask(String text);        // "://user:secret@" → "://***@"
}
```

- **`authenticatedUrl`**: GitHub/GitLab 모두 `oauth2:<token>` 기본 인증 형식이 통한다. 토큰이 비면 빈 비밀번호 URL을 만들지 않고 평문 URL(기존 `GitRepoCache` 규칙 유지). GitLab은 `GITLAB_BASE_URL`의 scheme/host/port를 그대로 사용.
- **`localKey`**:
  - `github` → `owner/repo` **그대로**. 기존 캐시(`repos/{owner}/{repo}`)·worktree·인터뷰 `work_dir`가 계속 유효.
  - `gitlab` → `_gitlab/` + 경로의 `/`를 `+`로 치환. 예: `_gitlab/product+netis+web+package+netis-v7.0`.
  - 항상 2단계이므로 폴더를 만드는 기존 코드(`Paths.get(root, key)`, 잠금 파일 `key + ".lock"`, `InterviewService.deriveWorkDir`의 `split("/", 2)`)가 **수정 없이** 동작하고, 잠금 파일이 다른 레포의 작업 폴더 안에 생길 수 없다.
  - 충돌 없음: GitHub 사용자명은 `_`로 시작할 수 없고, GitLab 경로 세그먼트에는 `+`가 올 수 없다(§3.2 정규식).
- **`mask`**: 정규식 `(?<=://)[^/@\s:]+:[^/@\s]+@` → `***@`. git/gh 명령을 로그·예외 메시지로 내보내는 모든 경로가 거친다.

### 4.3 빈 구성

- API 프로파일: `GitRemotes` 빈 = (`app.github.pat`, `app.gitlab.base-url`, `app.gitlab.token`)
- 워커 프로파일: `GitRemotes` 빈 = (`WorkerProperties.githubPat/gitlabBaseUrl/gitlabToken`)
- 두 프로파일을 동시에 켜는 단일 호스트 통합 테스트를 위해 `@ConditionalOnMissingBean` + 프로파일별 `@Configuration`으로 중복 등록을 피한다(워커 값 우선).

### 4.4 제거되는 것

`owner/repo` 2단계 정규식 5곳(`GitRefService:37`, `GitRepoCache:53,75`, `RepoUrlParser`의 bare 판정은 유지)과 `github.com` 문자열 조립 3곳(`GitRefService.buildUrl`, `GitRepoCache.repoUrl`, `repoPrepare.ts`). 검증은 `RepoUrlParser` 한 곳, 조립은 `GitRemotes` 한 곳.

## 5. 전달 형식

### 5.1 claim 응답 (API → 워커 / 인터뷰 서비스)

`WorkerTaskResponse`(factory 5개 전부)와 `InterviewClaimResponse`에 추가:

| 필드 | 값 |
|---|---|
| `gitUrl` | `task.git_url` / `interview_session.git_url` (정식 URL). 레거시 행은 `null` |
| `repoHost` | `git_url`을 재해석한 `github`/`gitlab`. `git_url`이 `null`이면 `github` |

수신 측 규칙: `gitUrl == null` → `RepoRef.legacyGithub(githubRepo)`. TS `types.ts`도 두 필드를 optional로 추가(`attachments`/`kind` 도입 때와 같은 "구버전 API는 필드 생략" 패턴).

`githubRepo`는 계속 채운다 — GitLab이면 전체 경로. 프롬프트(`{github_repo}`)·활동 로그·디자인 프로젝트명에 그대로 들어가며, 사람이 읽기에 자연스러운 값이다.

### 5.2 브랜치 조회

- `GET /api/repos/branches?catalogId={id}` 추가. 서버가 카탈로그 행 → `RepoRef` → `GitRefService.listBranches(RepoRef)`.
- 기존 `?repo=owner/repo`는 유지(GitHub 전용, 하위 호환). 둘 다 없거나 둘 다 있으면 400.
- `GitRefService`: `listBranches(RepoRef)`가 본체, `listBranches(String ownerRepo)`는 `legacyGithub` 위임. 404 문구를 호스트 중립으로: "레포를 찾을 수 없거나 접근 권한이 없습니다. 비공개 레포는 토큰(GITHUB_PAT/GITLAB_TOKEN) 등록이 필요합니다." stderr는 `mask` 후 로그.
- 응답 DTO(`BranchListResponse`)는 변경 없음.

### 5.3 조회 DTO

`TaskResponse`, `InterviewResponse`, `QuestionSummaryResponse`에 `repoHost` 추가(프론트의 PR/MR 문구 분기용). `RepoCatalogDto.View.host`는 재해석 값.

## 6. 구현 단계 — MR 생성

### 6.1 구조

```java
public interface MergeRequestCreator {
    boolean supports(String host);
    PrResult create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch, String title, String body)
            throws IOException, InterruptedException;
}
```

- `GitHubPrCreator` — 현재 `GitOpsService.createDraftPr`의 `gh pr create` 로직을 **그대로 이동**(동작 변경 없음. `--repo`에는 `ref.path()`).
- `GitLabMrCreator` — 신규.
- `GitOpsService.createDraftPr(wt, ref, …)`는 `supports(ref.host())`인 creator에 위임. 없으면 `IOException("지원하지 않는 호스트")`.
- `PrResult(url, number)`·`WorkerResultRequest`·DB·`WorkerService`의 `PR_CREATED` 검증은 변경 없음.

### 6.2 `GitLabMrCreator`

- `POST {gitlabBaseUrl}/api/v4/projects/{URLEncoder(path)}/merge_requests`
  헤더 `PRIVATE-TOKEN: <token>`, JSON 바디 `source_branch`, `target_branch`, `title`(`Draft: ` + title), `description`, `remove_source_branch: false`.
- `201` → 응답 JSON의 `web_url` → `PrResult.url`, `iid` → `PrResult.number`.
- `409`(같은 source 브랜치의 열린 MR 존재) → `GET …/merge_requests?state=opened&source_branch={head}` 첫 항목을 반환. 재시도·late-report 상황에서 "MR은 있는데 작업은 실패"가 되지 않게 한다.
- `401/403` → `IOException("GitLab 인증 실패(토큰 scope 확인: api)")`, 그 외 → 상태코드 + 본문 앞 500자(`mask` 적용).
- 토큰 미설정이면 호출 전에 `IOException("GITLAB_TOKEN 미설정")`.
- 타임아웃 connect 10s / request 30s. JSON은 Jackson `ObjectMapper`(이미 의존성에 있음).

### 6.3 그대로인 것 / 문구

- commit·push는 기존대로 캐시 clone의 `origin`(인증 URL)로.
- `WorkerMainLoop.renderPrBody`: "자동 생성 PR" → "자동 생성 PR/MR", 아래쪽 `https://github.com/` 링크 제거, "Ready for review로 전환" 안내는 호스트별 문구(GitLab: "Draft 표시를 해제").
- 상태 이력 사유 `"PR 생성: <url>"`은 유지(과거 행과 일관).

## 7. 워커 — `GitRepoCache` / `WorktreeService`

- `ensureFetched(RepoRef ref, String branch)` / `repoDir(RepoRef)`: URL은 `gitRemotes.authenticatedUrl(ref)`, 폴더는 `localKey(ref)`. 기존 `String githubRepo` 시그니처는 `legacyGithub` 위임으로 남긴다(호출부를 한 번에 옮기되 테스트 호환).
- **기존 clone의 origin 갱신**: 폴더가 이미 있으면 fetch 전에 `git remote set-url origin <authenticatedUrl>` — 토큰 교체·추가가 재clone 없이 반영된다.
- `exec()`: DEBUG 로그와 `IOException("git timeout: …")`/실패 메시지 모두 `GitRemotes.mask()` 적용. → `failure_reason` 유출 차단.
- `WorktreeService.create/createForDeploy/createForDesign`: 경로의 repo 부분을 `localKey(ref)`로. `WorkerMainLoop`는 claim에서 `RepoRef`를 한 번 만들어 전 구간에 넘긴다.
- 배포·첨부·Docker 이름은 작업 id 기준이라 변경 없음.

## 8. 인터뷰 서비스

- `types.ts`: claim에 `gitUrl?: string`, `repoHost?: 'github' | 'gitlab'`.
- `src/sdk/gitRemote.ts`(신규, 순수 함수): `buildCloneUrl(claim, cfg)`, `maskSecrets(text)`. Java `GitRemotes`와 같은 규칙.
- `repoPrepare.ts`: clone은 `buildCloneUrl` 사용. 기존 폴더 재사용 시 `git remote set-url origin <url>` → `fetch` → `reset --hard`. 오류는 `maskSecrets` 후 전파(러너가 세션 FAILED 사유로 올리는 경로 포함).
- `workDir`은 지금처럼 API가 정해 준다(`InterviewService.deriveWorkDir` → `localKey` 사용. GitHub 세션의 기존 `work_dir`는 값이 같아 resume 호환).
- `.env.example`에 `GITHUB_PAT`, `GITLAB_BASE_URL`, `GITLAB_TOKEN` 추가.

## 9. 프론트

- **`composables/useRepoBranches.ts`(신규)**: `tasks/index.vue`와 `questions/index.vue`에 중복된 브랜치 로딩(race guard, 상태 문구, 기본 브랜치 프리필)을 하나로. `catalogId`로 조회.
- 지원하지 않는 호스트(`other`) 선택 시 조용히 멈추지 않고 `repoStatus='error'` + "이 레포는 지원하지 않는 호스트입니다(GitHub/사내 GitLab만 가능)".
- **관리자 카탈로그**(`admin/repo-catalog.vue`): 안내 문구·placeholder에 GitLab URL 예시, `host` 칩 — `gitlab`은 주황(경고색)이 아닌 중립색, `other`만 경고색 + "작업 등록 불가". 표 컬럼 라벨 `owner/repo` → `경로`.
- **PR/MR 문구**: `composables/repoHost.ts`(신규) `mrNoun(host)`→`'PR'|'MR'`, `mrRef(host, n)`→`'PR #12'|'MR !12'`. 적용: `tasks/[id].vue`, `TaskCardCompact`, `TaskActionSheet`, `TaskDetailMobile`, `taskStages.ts`의 안내 문구. 상태 칩 라벨 "PR생성"은 유지.

## 10. 오류 처리 요약

| 상황 | 동작 |
|---|---|
| `GITLAB_BASE_URL` 미설정 + GitLab URL | `other` → 등록 시 400 "지원하지 않는 호스트…(GITLAB_BASE_URL 설정 확인)" |
| 토큰 없음/권한 없음(ls-remote) | 404 + 호스트 중립 안내 문구 |
| 구버전 워커가 GitLab 작업 claim | 기존 정규식에 걸려 `invalid github_repo`로 실패 기록. 데이터 손상 없음 → 배포 순서로 예방(§12) |
| MR 중복(409) | 기존 열린 MR을 찾아 성공 처리 |
| MR API 실패 | `IMPLEMENTATION_FAILED` + 마스킹된 사유. 브랜치 push분은 보존(기존 정책) |
| git 타임아웃/실패 | 메시지에서 자격증명 마스킹 |

## 11. 테스트

**먼저 쓰는 단위 테스트**
- `RepoUrlParserTest`: gitlab https/scp/`ssh://`, 5단계 경로, `.git`·끝 슬래시, 포트, 대소문자, base-url 미설정 시 `other`, 잘못된 세그먼트 거절. 기존 `non_github_https_url_kept_as_other…`는 "base-url이 다르면 other"로 유지.
- `GitRemotesTest`(신규): `authenticatedUrl`(github/gitlab × 토큰 유/무), `localKey`(github 불변, gitlab 평탄화), `mask`(토큰 URL, 여러 개, 없는 경우).
- `RepoCatalogServiceTest`: gitlab 등록 통과 + `ownerRepo`=전체 경로, 저장된 `host='other'`여도 재해석으로 통과, `other`는 계속 거절, `check()`가 gitlab에서 `listBranches(RepoRef)` 호출.
- `WorkerTaskResponse`/`InterviewClaimResponseTest`: `gitUrl`·`repoHost` 채움, 레거시(null) → `github`.
- `InterviewServiceTest`: gitlab 세션의 `workDir`가 `…/interviews/_gitlab/<flat>/session-N`, github는 기존 값 그대로.
- `GitLabMrCreatorTest`(신규): JDK `com.sun.net.httpserver.HttpServer` 스텁으로 201 / 409→GET / 401 / 토큰 없음. 요청 경로의 URL 인코딩(`product%2Fnetis%2F…`)·헤더·바디 검증.
- `GitRepoCache`: `exec` 실패/타임아웃 메시지에 토큰이 없는지(가짜 git 실행 파일 대신 존재하지 않는 호스트 URL + 짧은 타임아웃, 또는 `mask` 적용 지점 단위 검증).
- 인터뷰 서비스 vitest: `gitRemote.test.ts`(신규), `repoPrepare.test.ts`(인증 URL, `remote set-url`, 마스킹), `interviewRunner.test.ts` 픽스처.
- 프론트 vitest: `useRepoBranches`(catalogId 호출, race guard, other 메시지), `repoHost`(문구), `repo-catalog-admin.spec`, `tasks-form-repo-select.spec`, `questions-index.spec`(호출 파라미터가 `catalogId`로 바뀜), PR/MR 문구가 나오는 컴포넌트 spec.

**실제 검증(이 Windows 스택)**: 사내 GitLab의 **`product/netis/web/test`**(운영 아님)로 카탈로그 등록 → 확인 → 브랜치 조회 → 질문 → 작업 등록·인터뷰 → 구현(Draft MR 생성) 끝까지. GitHub 레포로 회귀 확인(브랜치 조회·질문). 토큰은 운영자가 `public.env`에 직접 넣는다.

## 12. 배포 순서

1. 인터뷰 서비스 + 워커 (새 필드를 이해하는 쪽 먼저)
2. API
3. 프론트 `.output` 재빌드
4. `GITLAB_BASE_URL`/`GITLAB_TOKEN` 설정 후 재기동, 카탈로그에 GitLab 레포 등록

API를 먼저 올려도 GitLab 카탈로그를 등록하기 전까지는 GitLab 작업이 생기지 않으므로 안전하다. 핵심은 **GitLab 레포 등록을 맨 마지막에** 하는 것.

## 13. 범위 밖

여러 GitLab 호스트 / 임의 Git 호스트 / SSH 인증 · `ls-remote` 캐시 · 상태값·컬럼·필드 이름 변경 · MR 상태 추적(머지 감지, 파이프라인) · GitLab 웹훅 · bare 2단계 입력을 GitLab으로 해석하는 옵션 · 토큰을 URL 대신 credential helper로 주입하는 방식(마스킹으로 유출 경로만 차단).

## 14. 문서 갱신

`CLAUDE.md`(설계 제약·환경변수·자주 보는 코드 표), `scripts/INFRA-CHECKLIST.md`(D항 `.env`), `netismaker-interview-service/.env.example`, `scripts/start-all.sh`·`start-public.sh`의 env 전달 확인(필요 시 `GITLAB_*` 통과).
