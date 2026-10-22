# 질문 세션 — 읽기 전용 참조 repo + 인터뷰 체크아웃 정리 설계

- 날짜: 2026-10-22
- 상태: 사용자 검토 대기 (brainstorming: 가능성 검토 보고서 → 결정 질문 7개 → 화면 목업 B안 확정 → 동작·정리 잡 정리 확인 → 검토자 점검 반영: 참조 폴더를 짧은 별도 경로로)
- 범위: netisMaker 백엔드(API) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt). 워커 데몬의 동작은 바꾸지 않는다(트리 삭제 함수를 공용으로 옮기는 정도만, §6).
- 단계: "멀티 repo 참조"의 1단계. 2단계(작업의 인터뷰·구현에 참조 붙이기)는 별도 스펙으로 한다.
- 선행 스펙: `2026-08-30-question-sessions-design.md`(질문 세션·기본 거부 게이트), `2026-09-13-question-mcp-attachments-design.md`(대화 중 MCP 변경·첨부 루트), `2026-10-02-question-db-mcp-design.md`(DB 선택·null/[] 규칙), `2026-09-21-gitlab-support-design.md`(RepoRef·토큰은 전송에만).

## 1. 목표와 이유

질문(Q&A) 세션에 대상 repo 하나 외에 **읽기 전용 참조 repo를 최대 3개** 붙인다. 에이전트가 옛 repo와 새 repo를 나란히 읽고 비교해 답하게 한다.

예: "옛 버전(Netis6.5)의 로그인 처리와 지금 버전(Netis7.0)을 비교해 빠진 기능을 알려줘."

**이유.**

- 마이그레이션(옛 repo → 새 repo 이식) 전에 무엇이 다르고 무엇을 옮겨야 하는지 묻는 일이 잦다. 지금 질문 세션은 repo를 하나만 체크아웃한다.
- 질문 세션은 쓰기 경로가 없다(기본 거부 게이트). 그래서 여기서 먼저 비교 품질·토큰 소모·디스크 증가를 실측하고, 2단계로 이어 간다.

**함께 할 일.** 인터뷰·질문 체크아웃(`~/netis-maker/interviews/`)은 지금 정리 잡이 없다(`service/AttachmentCleanupJob.java:38`에 "범위 밖"으로 적혀 있음). 2026-10-22 기준 2.1GB(세션 16개)가 쌓여 있다. 참조가 붙으면 세션당 최대 4벌이 되므로, 끝난 세션의 체크아웃을 지우는 정리 잡을 같이 넣는다.

### 1.1 이 단계에서 하지 않는 것

- 작업(인터뷰·디자인·구현)에 참조 붙이기 — 2단계 스펙
- 대상과 같은 repo의 다른 브랜치를 참조로 쓰기 — 사용자 결정: 다른 repo만
- 태그·특정 커밋 지정, 하위 경로만 받기(sparse checkout)
- 참조 repo의 DB 연결 선택 — DB 선택은 지금처럼 대상 repo 기준
- 질문 → 작업 전환 버튼
- 비공개 GitHub용 코드 작업
  - 코드는 호스트를 가리지 않는다.
  - 필요하면 운영에서 `GITHUB_PAT`만 설정한다(사용자 결정). 2026-10-22 라이브 인터뷰 서비스는 `git=[ gitlab]`.
- claude 자식 프로세스 env의 토큰 제거 같은 보안 후속 — 별건
- Claude 세션 기록(`~/.claude/projects`) 정리

### 1.2 절대 안 되는 것

- 참조 repo에 쓸 수 있게 되는 것(로컬 파일·원격 모두)
- 참조를 고르지 않은 질문의 동작·프롬프트가 지금과 달라지는 것
- 질문 게이트가 느슨해지는 것. 특히 Bash 규칙은 그대로 둔다.
- 참조 문제(권한·네트워크·없는 브랜치)로 질문 세션이 실패(FAILED)로 끝나는 것. 질문 세션의 실패는 되돌릴 수 없는 종료다.
- 진행 중인 세션의 체크아웃이 지워지는 것

## 2. 확정된 결정

| 결정 | 내용 | 정한 사람 |
|---|---|---|
| 선택 시점 | 질문 등록 때 고르고, 대화 중에도 추가·해제한다 | 사용자 |
| 상한 | 최대 3개. 대화 중 변경 뒤의 결과 기준 | 사용자 |
| 브랜치 | 참조마다 고른다. 기본값은 카탈로그 기본 브랜치, 그것이 비면 repo 기본 브랜치(ls-remote) | 사용자(비었을 때 처리는 설계 판단) |
| 대상과의 관계 | 대상과 다른 repo만. 참조끼리도 같은 repo는 한 번만 | 사용자(참조끼리 중복 금지는 설계 판단) |
| 기준 고정 | 참조는 처음 받은 커밋으로 대화 끝까지 고정하고, 그 커밋을 칩에 보여 준다 | 사용자 |
| 화면 자리 | 새 질문 화면은 대상 레포·브랜치 선택 바로 아래 "참조 repo" 칩 줄(B안). 대화 화면은 헤더 아래 칩 줄 + "참조 변경" | 사용자(목업 확인) |
| 체크아웃 보존 | 끝난 세션의 체크아웃(대상+참조)은 마지막 활동 7일 뒤 삭제 | 사용자 |
| 호스트 | 사내 GitLab 위주. 비공개 GitHub는 필요할 때 PAT 설정만 | 사용자 |
| 참조를 못 받았을 때 | 세션을 실패시키지 않는다. 그 참조 없이 답하고, 에이전트에게 알리고, 칩에 ⚠와 사유 | 설계 판단(사용자 확인) |
| 참조 폴더 위치 | 체크아웃 루트 아래 짧은 별도 경로 `_refs/{세션 id}/{카탈로그 id}`. 대상 폴더 안에 두지 않는다. 처음 안(`{work_dir}-refs/{repo 키}`)은 Windows 경로 길이 한도(260자)를 넘을 수 있어 바꿨다(§3.3) | 사용자(검토 뒤 결정) |
| 권한 | 질문 게이트에서 Read·Grep·Glob만 참조 폴더까지 허용. Bash는 대상 안만(변경 없음) | 설계 판단(사용자 확인) |
| 변경 적용 | MCP·DB와 같이 다음 질문을 보낼 때 함께 적용하고, 그때 시스템 메모를 남긴다 | 설계 판단(사용자 확인) |
| 정리 잡 위치 | API(세션 상태와 체크아웃 경로를 아는 쪽), 매일 03:50 | 설계 판단(사용자 확인) |

## 3. 데이터

### 3.1 마이그레이션 `V31__question_ref_repos.sql`

```sql
-- 질문 세션 참조 repo (스펙 2026-10-22). 선택 당시 스냅샷 + 런타임 상태(고정 커밋·실패 사유).
ALTER TABLE com.interview_session
    ADD COLUMN ref_repos JSONB NOT NULL DEFAULT '[]'::jsonb;
```

- 인터뷰(INTERVIEW) 세션은 이 단계에서 항상 `[]`다.
- `com.task`에는 이 단계에서 컬럼을 더하지 않는다(2단계에서).

### 3.2 원소 모양

엔티티 쪽 값 타입은 `RefRepo`로 하고, 매핑은 `@JdbcTypeCode(SqlTypes.JSON) List<RefRepo>`로 한다(`mcps_extra`와 같은 방식).

| 필드 | 뜻 | 쓰는 쪽 |
|---|---|---|
| `catalogId` | 선택 당시 카탈로그 id | 등록·변경 |
| `alias` | 별칭 스냅샷 | 등록·변경 |
| `path` | GitHub `owner/repo` 또는 GitLab 전체 경로(`RepoCatalogService.ResolvedRepo.ownerRepo`) | 등록·변경 |
| `gitUrl` | 자격증명 없는 정식 URL | 등록·변경 |
| `branch` | 참조 브랜치 | 등록·변경 |
| `sha` | 처음 받은 커밋(40자). 한 번 정해지면 그 참조가 빠질 때까지 바뀌지 않는다 | 러너 보고 |
| `fetchedAt` | `sha`를 처음 기록한 시각 | 러너 보고 |
| `error` | 마지막 준비 실패 사유(자격증명을 가린 문구). 성공하면 비운다 | 러너 보고 |

- 대상 repo의 `git_url`·`repo_alias`·`repo_catalog_id`, 그리고 `mcps_extra`와 같은 "선택 당시 박제" 방식이다. 카탈로그를 고치거나 지워도 세션의 참조는 그대로 남는다.
- 같은 `catalogId`를 다른 브랜치로 바꾸면 "빼고 다시 넣기"로 본다. 이때 `sha`·`fetchedAt`·`error`를 비운다.

### 3.3 체크아웃 경로

- **참조 루트**는 `{체크아웃 루트}/_refs/{세션 id}`다. 예: `~/netis-maker/interviews/_refs/123`.
- **참조 폴더**는 `{참조 루트}/{카탈로그 id}`다. 예: `~/netis-maker/interviews/_refs/123/12`. 한 세션의 참조끼리는 repo(=카탈로그 id)가 겹치지 않으므로 이름이 충돌하지 않는다.
- **짧게 두는 이유는 Windows 경로 길이 한도(260자)다.**
  - 이 PC는 긴 경로가 꺼져 있다(`LongPathsEnabled`=0, git `core.longpaths` 미설정 — 2026-10-22 확인).
  - 지금 대상 체크아웃의 가장 긴 파일 경로가 이미 227자다(`_gitlab/product+netis+web+dashboard+netisdash_dbHospital/session-22/…`).
  - 참조를 대상 폴더 이름 뒤에 붙이면(`{work_dir}-refs/{repo 키}`) netisdash 레포끼리 비교할 때 260자를 넘는 파일이 수십 개 생겨 clone이 실패한다(검토 추정).
  - 이 규칙이면 앞부분이 약 50자라, 지금 레포 기준으로 가장 긴 파일도 약 175자다.
  - 대상 체크아웃의 경로 길이는 지금과 같고, 이 스펙에서 다루지 않는다.
- `_refs`는 `{localKey}`의 첫 단계와 겹치지 않는다. GitHub 사용자명은 `_`로 시작할 수 없고, GitLab은 `_gitlab`, 방어용 값은 `_`다.
- `work_dir`를 정하는 시점(첫 claim)과 규칙은 지금 그대로다(`InterviewService.deriveWorkDir`, `service/InterviewService.java:216-226`). 참조 경로는 저장하지 않고, claim 때마다 같은 규칙으로 계산한다.
- **체크아웃 루트**(`~/netis-maker/interviews`)는 경로 결정과 정리 잡이 같은 값을 쓰도록 한 곳에서 읽는다. 설정 이름은 `app.interview.work-root`, 기본값은 `${user.home}/netis-maker/interviews`다.
  - 지금 `deriveWorkDir`는 `user.home`을 직접 읽는다. 이 설정값을 읽도록 바꾼다.
  - 기본값이 같으므로 기존 세션 경로는 변하지 않는다.

## 4. API

### 4.1 등록 — `POST /api/questions` (JSON·multipart `meta` 둘 다)

`QuestionCreateRequest`에 `List<RefRepoInput> refRepos`를 더한다. `RefRepoInput = {repoCatalogId: Long, branch: String}`이고, null이나 빈 목록이면 참조 없음이다.

**검증.** 실패하면 400이고, 세션을 만들기 전에 검사한다.

1. 3개를 넘으면 거절: "참조 repo는 최대 3개까지 고를 수 있습니다"
2. 항목마다 `RepoCatalogService.resolveForRegistration`을 거친다. 없음·비활성·지원하지 않는 호스트는 거절한다(대상과 같은 규칙).
3. 대상과 같은 카탈로그 id면 거절: "대상 레포는 참조로 고를 수 없습니다"
4. 참조끼리 같은 카탈로그 id면 거절
5. `branch`가 비면 카탈로그 기본 브랜치를 쓰고, 그것도 없으면 400 "참조 브랜치를 지정하세요"를 돌려준다.
   - 화면은 항상 브랜치를 채워 보낸다(§7).
   - 브랜치가 실제로 있는지는 등록 때 확인하지 않는다. 없으면 러너가 받지 못하고 ⚠로 보인다. 대상 브랜치도 등록 때 확인하지 않는다.

검증은 질문과 (2단계) 작업이 같이 쓰도록 한 곳에 둔다. 예: `RefRepoService.resolve(targetCatalogId, inputs)`.

### 4.2 대화 중 변경 — `POST /api/questions/{id}/ask` (JSON·multipart)

`QuestionAskRequest`에 `List<RefRepoInput> refRepos`를 더한다. 규칙은 `mcpCatalogIds`·`dbConnectionIds`와 같다(`service/QuestionService.java:222-262`).

- `null`이면 유지, `[]`이면 전부 해제한다.
- 현재 집합과 같으면(`catalogId`+`branch` 짝, 순서 무관) **검증 없이 아무것도 하지 않는다**.
- 다르면 다음 순서로 처리한다.
  1. 결과가 3개 이하인지 확인한다.
  2. **새로 들어오는 짝만** §4.1의 2~5번으로 검증한다.
  3. 계속 남는 짝은 기존 원소(`sha`·`fetchedAt`·`error` 포함)를 그대로 둔다.
  4. 시스템 메모 턴을 남긴다.
- 이미 세션에 있던 참조는 카탈로그가 나중에 꺼지거나 지워져도 유지하거나 해제할 수 있다. `applyDbChange`와 같은 이유다: 사용자가 보이지 않는 낡은 항목 때문에 선택을 영영 못 고치는 일을 막는다.
- 시스템 메모 예:
  - `참조 repo 변경: ＋ 배치 서버(master), − 공통 UI 라이브러리(main)`
  - 전부 빼면 `참조 repo 변경: 없음(전부 해제)`
- `applyRefChange`는 기존 `ask` 순서(스펙 2026-09-13 §5.2)에서 `applyDbChange` 다음에 둔다. 같은 트랜잭션이라 400이면 답변 턴과 재큐까지 롤백된다.
- SSE `note` 이벤트는 기존 경로(`InterviewStreamService.pushNote`)를 그대로 쓴다.

### 4.3 조회 — `GET /api/questions/{id}`

`InterviewResponse`에 `refRepos`를 더한다. 화면에 필요한 것은 `catalogId`, `alias`, `branch`, `sha`, `fetchedAt`, `error`다. 항상 non-null이다. 목록(`QuestionSummaryResponse`)에는 넣지 않는다.

### 4.4 claim — `POST /worker/interviews/claim`

`InterviewClaimResponse`에 `refRepos`를 더한다. 원소는 `{catalogId, alias, githubRepo(=path), gitUrl, repoHost, branch, dir, sha}`다.

- `dir`은 §3.3 규칙으로 계산한 절대경로다.
- `sha`는 고정 커밋이고, 아직 없으면 null이다.
- 질문(QUESTION) 세션에만 싣는다. 인터뷰 세션은 `[]`다.

### 4.5 답변 보고 — `POST /worker/interviews/{id}/question`

`WorkerQuestionRequest`에 `List<RefState> refStates`를 더한다. `RefState = {catalogId, branch, sha, error}`.

- API는 세션의 현재 `ref_repos`에서 `catalogId`+`branch`가 같은 원소에만 반영한다. 그새 빠진 참조는 무시한다.
- `sha`: 원소에 `sha`가 없을 때만 기록하고 `fetchedAt=now`로 둔다(고정). 이미 있으면 바꾸지 않는다.
- `error`: 값이 있으면 기록하고, 없으면(성공) 비운다.
- `refStates`가 없거나 비어 있으면(구버전 러너) 아무것도 하지 않는다.

## 5. 인터뷰 서비스

### 5.1 참조 준비 (`runner/repoPrepare.ts`의 `ensureRefRepos`)

claim마다(새 질문·추가 질문 모두) 대상 `ensureRepo`(`src/runner/interviewRunner.ts:315-323`) 다음에 실행한다. 참조마다 이렇게 처리한다.

- **폴더에 `.git`이 있고 HEAD 브랜치가 요청 브랜치와 같으면** 그대로 쓴다. fetch하지 않는 것이 곧 커밋 고정이다. 결과 `sha`는 `git rev-parse HEAD`.
- **폴더가 없으면** `git clone --depth 1 --branch <branch> <인증 URL> <dir>`로 받고 origin을 평문 URL로 바꾼다.
  - 대상 `ensureRepo`(`src/runner/repoPrepare.ts:47-75`)와 같은 규칙이다: 자격증명은 전송에만 쓴다.
  - `buildCloneUrl`과 `maskSecrets`를 재사용한다.
- **반쪽 체크아웃은 남기지 않는다.** 받는 도중 실패하거나 중단되면(clone의 checkout 단계 실패, 턴 타임아웃 포함) 그 폴더를 남기지 않는다. 예를 들어 임시 폴더에 받고 성공했을 때만 최종 이름으로 바꾼다. 그래야 다음 턴의 "그대로 쓴다" 판정이 반쪽 폴더를 성공으로 보고 그 커밋으로 고정하지 않는다.
- **폴더는 있는데 브랜치가 다르면**(빼고 다른 브랜치로 다시 넣은 경우) 폴더를 지우고 위처럼 받는다.
- **같은 브랜치로 뺐다가 다시 넣으면** 남아 있는 폴더를 그대로 쓴다. 그 대화에서 처음 받은 커밋을 유지한다.
- **claim에 `sha`가 있는데 폴더가 없어진 경우**(수동 삭제 등)에는 그 커밋을 받아 쓰려고 시도한다(`git fetch --depth 1 <url> <sha>` 후 체크아웃). 안 되면 브랜치 최신을 받고 새 커밋을 보고한다. API는 이미 정해진 `sha`를 바꾸지 않으므로 이때 칩의 커밋과 실제가 다를 수 있다. 드문 경우라 로그 경고만 남긴다. 구현에서 더 단순하게 처리해도 된다.
- **실패**(권한·네트워크·없는 브랜치)하면 예외를 던지지 않고 `{error: 가린 사유}`를 돌려준다. 턴은 계속된다.
- **타임아웃**: 턴 상한(`INTERVIEW_TURN_TIMEOUT_MS`, 기본 30분)의 `AbortSignal`을 git 자식 프로세스에 그대로 넘긴다(대상과 같음).
- 참조끼리는 순차로 받는다. 세션마다 폴더가 따로라 동시 처리(`INTERVIEW_CONCURRENCY`)와 공유 상태가 없다.

### 5.2 프롬프트 (`runner/interviewRunner.ts`, 질문 분기)

- `questionPromptFor`의 접두 절에 DB 절(`dbSection`)처럼 **참조 절**을 붙인다. 새 질문과 추가 질문 모두 매 턴 붙는다. 지금 `prefix`가 두 분기 모두에 붙는 구조라 그대로 쓰면 된다(`src/runner/interviewRunner.ts:166-190`).
- 참조가 없으면 빈 문자열이다. 그래서 프롬프트가 지금과 같다.
- 내용 초안(문구는 구현에서 다듬는다):

  ```
  참조 repo (읽기 전용 — 비교·참고용, 수정 금지):
  - Netis6.5(구) · release/6.5@a1b2c3d : C:/Users/mic/netis-maker/interviews/_refs/123/12
  - 공통 UI 라이브러리 · main@9f8e7d6 : …
  사용할 수 없는 참조: 배치 서버(master) — 브랜치를 찾을 수 없음
  규칙:
  - 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로를 Read/Grep/Glob의 경로로 주어 읽는다(셸 명령은 대상 안에서만 동작).
  - 참조 파일 근거는 `별칭:경로:줄`(예: `Netis6.5(구):src/…/LoginService.java:88`), 대상 파일은 지금처럼 `경로:줄`.
  - 사용할 수 없는 참조의 내용은 추측하지 말고 못 읽었다고 답한다.
  ```

- "환경 준비" 활동 표시(`interviewRunner.ts:314`)에 참조 수를 덧붙인다(예: `Netis7.0 + 참조 2`). 문구는 구현 판단.

### 5.3 권한 (`sdk/permissions.ts`, `sdk/sessionOptions.ts`)

- `buildOptions`에 `refRoots?: string[]`를 더해 `buildCanUseTool`로 넘긴다.
  - `refRoots`는 이번 턴에 **준비에 성공한** 참조 폴더들이다.
  - SDK 옵션 자체에는 싣지 않는다(`attachmentRoot`와 같은 방식, `src/sdk/sessionOptions.ts:31-36`).
- **QUESTION 게이트만 바꾼다**(`src/sdk/permissions.ts:444-458`).
  - Read: 대상 안, `attachmentRoot` 안, **참조 폴더 안** 중 하나면 허용한다(`questionReadGate` `:245-261`에 루트를 더한다).
  - Grep·Glob: `path`가 대상 안이거나 **참조 폴더 안**이면 허용한다. `path`가 없으면 cwd(대상)다. Glob `pattern`의 절대경로·`..`·`~` 금지는 그대로 두고, 참조는 `path`로 지정한다.
  - 판정은 기존 `insideRepoReal`(실경로·symlink 해소, 대칭 비교)을 루트마다 적용한다. `~` 금지도 그대로다.
  - **Bash는 바꾸지 않는다.** 대상 안의 상대경로만 된다.
  - 대화 중 빠진 참조나 준비에 실패한 참조는 루트에 없으므로 다음 턴부터 거부된다.
- INTERVIEW 게이트는 바꾸지 않는다. 이 단계에서 인터뷰 세션에는 참조가 없다.
- **게이트 변경이라 보안 리뷰 대상이다.** 다음 경우를 테스트로 고정한다.
  - 참조 폴더 안의 symlink가 밖(예: `C:\Windows`)을 가리킬 때 거부되는지
  - Windows 경로 표기 `C:/`와 `C:\` 둘 다, 그리고 대소문자 차이
  - 참조 폴더와 이름 앞부분만 같은 형제 폴더(예: 참조가 `_refs/123/12`일 때 `_refs/123/120`, 다른 세션의 `_refs/1234`)가 거부되는지

### 5.4 보고

- `postQuestion` 바디에 `refStates`를 싣는다(§4.5). 참조 준비 결과를 그대로 보낸다.
- 턴이 실패해 `fail`로 끝나면 보고하지 않는다. 그 세션은 끝나기 때문이다.

## 6. 체크아웃 정리 잡 (API)

**구조.** 이름은 예를 들어 `InterviewCheckoutCleanupJob`과 순수 판정 `InterviewCheckoutCleanupPlanner`로 한다. `AttachmentCleanupJob`·`AttachmentCleanupPlanner`와 같은 구조이고 `@Profile("api")`다.

**설정** `app.interview.checkout-cleanup`:

- `enabled`: 기본 true
- `cron`: 기본 `0 50 3 * * *`. 02:30 worktree, 03:00 레포 캐시, 03:30 첨부, 03:40 DB MCP 토큰 정리와 겹치지 않게 했다.
- `retention-days`: 기본 7, 1 이상

**한 회차**

1. 체크아웃 루트(§3.3) 아래에서 두 종류의 폴더를 모은다.
   - 대상: 두 단계 깊이의 `session-{id}`. `{localKey}`는 `owner/repo` 또는 `_gitlab/<평탄화>`이고, 방어용 `_/<값>`도 포함한다(`deriveWorkDir`).
   - 참조: `_refs/{id}`.
2. 그 id들의 세션 상태와 `last_activity_at`을 DB에서 한 번에(IN 절 묶음) 조회한다. **조회가 실패하면 이번 회차는 아무것도 지우지 않는다**(fail-closed).
3. 판정한다. 상태는 질문·인터뷰 공통 `InterviewStatus`다.
   - 끝난 상태(등록됨·취소됨·만료됨·인터뷰실패)이고 `last_activity_at`이 보존 일수보다 오래됐으면 그 세션의 `session-{id}`와 `_refs/{id}`를 지운다.
   - 진행 중(인터뷰대기·인터뷰중·입력대기·플랜완료)이면 남긴다.
     - 입력대기는 24시간 입력이 없으면 만료되므로(`app.interview.idle-ttl-minutes`) 결국 정리 대상이 된다.
     - 플랜완료는 확정이나 취소로만 끝난다.
   - DB에 행이 없는 id(고아)는 폴더 수정 시각이 보존 일수보다 오래됐을 때만 지운다.
4. 삭제는 워커 `RepoCacheCleanupJob.deleteTree`·`deleteEntry`(`workerdaemon/RepoCacheCleanupJob.java:461-522`)를 `util`로 옮겨 함께 쓴다.
   - 이 함수는 링크와 Windows 정션을 따라가지 않고, git의 읽기 전용 파일은 `FileDeletion`으로 속성을 풀고 지운다.
   - 지우기 전에 실제 경로가 체크아웃 루트 안인지 확인한다.
5. 폴더 하나가 실패해도 로그만 남기고 다음으로 넘어간다. 남은 것은 다음 회차에 지운다.
6. 회차 로그에 지운 세션 수, 용량, 남긴 수, 실패 수를 남긴다.

**테스트·배포·전제**

- 통합 테스트에서는 `TestcontainersConfig`(`src/test/java/com/hamonsoft/netismaker/TestcontainersConfig.java:41` 근처)에서 이 잡을 끈다(`app.interview.checkout-cleanup.enabled=false`). 첨부 정리와 같은 이유로 실제 루트를 보호하기 위해서다. 단위 테스트는 임시 루트를 쓴다.
- 배포 뒤 첫 회차에 지금 쌓인 것 가운데 조건에 맞는 폴더가 한꺼번에 지워진다. 체크아웃은 다시 받을 수 있는 사본이고, 대화·첨부·플랜은 DB와 첨부 폴더에 따로 있다.
- 전제: API와 인터뷰 서비스가 같은 PC, 같은 사용자로 돈다. `work_dir`·첨부 절대경로가 이미 이 전제를 쓴다. 인터뷰 서비스를 다른 PC로 옮기면 이 잡은 그 PC의 폴더를 지우지 못한다. 그때 다시 설계한다.

## 7. 프론트엔드

### 7.1 공용 컴포넌트

이름은 예를 들어 `components/RefRepoChips.vue`와 추가 창으로 한다.

- **칩 줄**: 라벨 "참조 repo · 읽기 전용 · 최대 3개"와 칩 `📚 별칭 · 브랜치[@커밋7자]`.
  - 상태는 세 가지다.
    - 커밋 있음: `@a1b2c3d`. 툴팁은 "고정 커밋 a1b2c3d · 받은 시각 — 대화 끝까지 이 커밋 기준".
    - 아직 안 받음: "다음 답변 때 받음".
    - 실패: ⚠. 툴팁에 사유.
  - 편집할 수 있을 때만 ✕와 "＋ 참조 추가"를 보인다.
- **추가 창**
  - 레포 선택: 카탈로그 활성 목록에서 대상, 이미 고른 repo, `host==='other'`를 뺀다.
  - 브랜치 선택: `GET /api/repos/branches?catalogId=`로 목록을 받는다. 기본값은 카탈로그 `defaultBranch`, 없으면 응답의 `defaultBranch`.
  - 3개면 "＋ 참조 추가"를 숨기거나 막는다.
- **늦은 응답 무시**: 레포를 바꾸는 중에 늦게 온 브랜치 응답이 선택을 덮지 않게 한다(기존 `inflightRepo` 방식).
- **Quasar 규칙**(CLAUDE.md): 반응형 헬퍼 이름을 클래스로 쓰지 않는다. `q-dialog` 루트는 `<div>`로 둔다. 좁은 화면 분기는 `$q.screen.lt.md` 하나로 한다.

### 7.2 새 질문 화면 (`pages/questions/index.vue`)

- `#top` 슬롯의 대상 레포·브랜치 선택 바로 아래에 편집 가능한 칩 줄을 둔다(B안).
- 대상 레포를 바꾸면, 새 대상과 같은 repo인 참조만 빼고 나머지는 유지한다. 구현 판단.
- 등록 바디에는 참조가 있을 때만 `refRepos: [{repoCatalogId, branch}]`를 넣는다. 없으면 필드를 생략해 기존 정확-바디 테스트(`test/questions-index.spec.ts`)를 유지한다.
- 입력창 툴바는 바꾸지 않는다. 390px에서 이미 꽉 차 있다(`components/chat/QuestionComposer.vue` 주석의 2026-09-08 실측).

### 7.3 대화 화면 (`pages/questions/[id].vue`, `components/InterviewPanel.vue`)

- 헤더 아래, DB 칩 줄 위에 참조 칩 줄(읽기 전용 표시)과 "✎ 참조 변경"을 둔다.
- "참조 변경"은 입력 대기(`awaiting`)일 때만 열린다.
  - 고른 값은 MCP·DB처럼 화면 상태(`pickedRefs`)로 들고 있다가 **다음 질문을 보낼 때** ask 바디에 싣는다(`AskExtra.refRepos`).
  - 변경이 없을 때 필드를 생략할지, 그대로 보내 서버의 no-op에 맡길지는 구현 판단.
- 보낸 뒤에는 서버 응답과 SSE `note`로 칩 줄이 갱신된다. 새로 더한 참조는 답변이 올 때까지 "다음 답변 때 받음"으로 보인다.
- `pickedRefs`는 세션 값으로 **처음 한 번만** 채우고, 그 뒤로는 "다음 질문에 쓸 초안"으로 둔다.
  - 화면이 상세를 주기적으로 다시 받기 때문에, 그때마다 서버 값으로 맞추면 보내기 전의 변경이 지워진다.
  - 지금 `pickedMcp`·`pickedDb`의 1회 시드(`pages/questions/[id].vue:60-68`, `pickedSeeded`)와 같다.
  - 칩 줄은 서버 값(커밋·상태)을, "참조 변경" 창은 초안을 보여 준다.
- 대상 레포 칩(헤더 📁)은 그대로 둔다.

### 7.4 타입·스모크

- `composables/questions.ts`의 `QuestionDetail`에 `refRepos?`를 더한다.
- `scripts/hydration-smoke.mjs`의 `QUESTION` 목에 `refRepos`를 넣는다. 커밋 있음·없음·실패 상태를 하나씩 넣는다. 새 필드는 목에도 같이 넣는다는 규칙(CLAUDE.md)을 따른다.

## 8. 오류 처리

| 지점 | 동작 |
|---|---|
| 등록·변경 검증 실패 | 400과 사유(화면에 표시). 세션·턴은 만들어지지 않거나 롤백된다 |
| 참조 받기 실패(권한·네트워크·없는 브랜치) | 세션은 계속된다. 그 참조는 게이트 루트에서 빠지고, 프롬프트 "사용할 수 없는 참조"에 사유가 들어가고, 보고 `error`로 칩에 ⚠ |
| 참조 받는 중 턴 타임아웃 | 지금과 같다(턴 실패). git 자식 프로세스까지 중단 |
| 에이전트가 빠진·실패한 참조 경로를 읽으려 함 | 게이트가 거부한다(질문 세션 거부 메시지) |
| 정리 잡 DB 조회 실패 | 그 회차 전체를 건너뛴다 |
| 정리 잡 폴더 삭제 실패 | 로그를 남기고 다음 회차에 다시 시도 |
| 토큰·자격증명 | 오류 문구는 `maskSecrets`로 가린 뒤에만 `error`·로그·프롬프트에 넣는다 |

## 9. 배포 순서

인터뷰 서비스 → API(V31) → 프론트(`.output` 재빌드).

- 새 필드는 양쪽 모두 모르는 값을 무시한다. API는 Spring Boot 기본 설정, 워커 클라이언트는 `FAIL_ON_UNKNOWN_PROPERTIES=false`, TS는 추가 필드를 무시한다.
- 화면을 마지막에 올려, 실행 쪽이 준비되기 전에는 참조를 고를 수 없게 한다.
- 정리 잡은 API와 함께 켜진다. 처음 동작을 지켜보고 싶으면 `enabled=false`로 배포한 뒤 켠다(dry-run 없음).

## 10. 테스트

**백엔드 단위**

- 등록 검증: 상한, 없음/비활성/지원하지 않는 호스트, 대상과 같음, 중복, 빈 브랜치의 기본값/400
- `applyRefChange`: null 유지, [] 해제, 같은 집합 no-op(사후 비활성 항목이 있어도), 새 짝만 검증, 브랜치 변경 시 상태 초기화, 시스템 메모 문구
- claim: `refRepos`의 `dir` 계산(`{체크아웃 루트}/_refs/{세션 id}/{카탈로그 id}`), 인터뷰 세션은 `[]`
- 보고 반영: 없는 짝 무시, `sha`는 한 번만, `error` 기록과 비움, `refStates`가 없으면 무시
- 정리 판정(Planner): 끝난 상태 + 기간, 진행 중 보존, 고아 mtime, DB 실패 시 전체 건너뜀, 루트 밖 거부, `session-{id}`와 `_refs/{id}`를 같은 세션으로 묶어 판정
- 체크아웃 루트 설정: 기본값일 때 기존 `work_dir`와 같은 경로

**인터뷰 서비스(vitest)**

- `ensureRefRepos`: 새로 받기, 그대로 쓰기(fetch 안 함), 브랜치 바뀜, 실패 시 가린 사유, origin 평문, 받는 도중 실패·중단하면 폴더가 남지 않고 다음 턴에 다시 받음
- 프롬프트: 참조가 없으면 빈 절(기존 프롬프트와 동일), 있으면 경로·커밋·실패 안내
- 게이트: 참조 안의 Read/Grep/Glob 허용, 밖은 거부, symlink 탈출 거부, 이름 앞부분만 같은 형제 폴더 거부, Bash는 그대로(참조 경로 거부), 빠진 참조 거부

**프론트(vitest)**

- 칩 상태 세 가지
- 추가 창: 대상·중복·other 제외, 3개 상한, 브랜치 기본값
- 등록 바디: 참조가 없으면 필드 생략
- 대화 중 변경이 ask 바디에 실림, 입력 대기가 아니면 잠김

**하이드레이션 스모크**: 새 목 필드로 `/questions/[id]`가 통과하는지.

**라이브 확인(병합 뒤)**

1. 사내 GitLab 참조 1개로 비교 질문 → 답에 `별칭:경로:줄` 근거, 칩에 `@커밋`
2. 대화 중 참조 추가 → 시스템 메모, "다음 답변 때 받음" → 커밋 표시
3. 없는 브랜치로 참조 → ⚠, 답은 계속 나옴
4. 다음 날 정리 잡 로그 확인

## 11. 영향 파일 (예상)

- **백엔드**
  - `db/migration/V31__question_ref_repos.sql`
  - `entity/InterviewSession.java`, 신규 `entity/RefRepo.java`(값 타입)
  - `dto/QuestionCreateRequest.java`, `dto/QuestionAskRequest.java`, `dto/InterviewResponse.java`, `dto/InterviewClaimResponse.java`, `dto/WorkerQuestionRequest.java`
  - `service/QuestionService.java`, `service/InterviewService.java`(claim, 보고 반영, 체크아웃 루트)
  - 신규 검증 서비스(예: `service/RefRepoService.java`)
  - 신규 `service/InterviewCheckoutCleanupJob.java`·`InterviewCheckoutCleanupPlanner.java`, `util/`로 옮기는 트리 삭제(+ `workerdaemon/RepoCacheCleanupJob.java`는 옮긴 함수를 호출하도록만)
  - `src/main/resources/application.yml`, `src/test/.../TestcontainersConfig.java`
- **인터뷰 서비스**: `src/types.ts`, `src/runner/repoPrepare.ts`, `src/runner/interviewRunner.ts`, `src/sdk/sessionOptions.ts`, `src/sdk/permissions.ts`
- **프론트**: 신규 칩·추가 창 컴포넌트, `pages/questions/index.vue`, `pages/questions/[id].vue`, `components/InterviewPanel.vue`, `composables/questions.ts`, `scripts/hydration-smoke.mjs`
- **문서**: `CLAUDE.md`의 "핵심 설계 제약" 질문 세션 항목, "자주 보는 코드" 표, 환경 메모(정리 잡)

## 12. 구현에 맡기는 것

- 컴포넌트·서비스 이름, 프롬프트 문구 다듬기, 칩 색(DB 칩 teal, 첨부 blue-grey와 구별되게)
- 대상 레포를 바꿀 때 참조 처리. 겹치는 것만 빼기를 권한다.
- `InterviewResponse.refRepos`에 넣을 필드 범위(§4.3 최소 목록 이상)
- 고정 커밋 폴더가 사라진 드문 경우의 처리 단순화(§5.1)
- "환경 준비" 활동 표시 문구, 대화 중 변경이 없을 때 ask 바디에 필드를 실을지
- 정리 잡의 고아 판정 가드. 예: `AttachmentCleanupPlanner`처럼 `id ≤ MAX(id)`일 때만 고아로 볼지
- 반쪽 체크아웃을 막는 방법(임시 폴더 후 이름 바꾸기, 또는 실패 시 삭제)
