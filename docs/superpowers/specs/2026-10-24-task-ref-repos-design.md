# 작업(인터뷰·구현) — 읽기 전용 참조 repo 설계 (멀티 repo 2단계)

- 날짜: 2026-10-24
- 상태: 사용자 승인됨 2026-10-24 (brainstorming: 가능성 보고서 §3·§5 → 결정 질문 7개 → 흐름 정리 → 화면 목업 확인 → 검토자 점검(영역별 4명 + 교차 점검 1명) 반영 → 검토 뒤 사용자 결정 1개(재시도 때 참조 빼기)) · 구현됨
- 기준: main `a5a36d1` (1단계 [PR #98](https://github.com/micthebick84/commanCenter/pull/98)·대상 clone 무프롬프트 [PR #99](https://github.com/micthebick84/commanCenter/pull/99) 라이브)
- 범위: 백엔드(API) + 인터뷰 서비스(Node) + 워커 데몬(Java) + 프론트엔드(Nuxt)
- 단계: "멀티 repo 참조"의 2단계. 1단계는 질문 세션(`2026-10-22-question-ref-repos-design.md`, 구현됨).
- 선행 스펙: `2026-10-22-question-ref-repos-design.md`(참조 모양·칩·고정 규칙), `2026-10-14-task-db-connection-design.md`(승인 때 고르는 자리·작업→세션 복사), `2026-10-15-implementation-mode-selection-design.md`(구현 방식·계획 scratch·재시도), `2026-09-21-gitlab-support-design.md`(RepoRef·토큰은 전송에만).
- 경로 표기: Java는 `src/main/java/com/hamonsoft/netismaker/` 아래 상대 경로, 인터뷰 서비스는 `netismaker-interview-service/src/` 아래 상대 경로.

## 1. 목표와 이유

작업(task)에도 대상 repo 하나 외에 **읽기 전용 참조 repo를 최대 3개** 붙인다. 작업 인터뷰가 옛 repo와 새 repo를 나란히 읽고 이식 계획을 세우고, 구현이 같은 커밋의 참조를 옆에 두고 대상에만 코드를 옮겨 적게 한다.

예: 대상 `Netis7.0`, 참조 `옛앱(release/6.5)` — "옛앱의 결재선 계산을 새 앱으로 이식".

**이유.**

- 1단계(질문 세션)로 비교는 할 수 있게 됐지만, 실제 이식은 작업(인터뷰 → 계획 → 구현 → PR)으로 해야 한다. 지금 작업은 repo를 하나만 본다.
- 질문에서 비교한 결과를 작업으로 넘기는 경로가 없다. 이번에 "이 대화로 작업 등록" 버튼을 함께 넣는다(사용자 결정).

### 1.1 이 단계에서 하지 않는 것

- 디자인 단계(디자인대기 → 시안)·레거시 분석(`processAnalysis`)에 참조 붙이기 — 사용자 결정(디자인), 분석은 진입점 없음
- 여러 repo에 쓰기(다중 PR/MR). 쓰기 대상은 지금처럼 1개
- 계획 확정 뒤 참조를 더하거나 브랜치·커밋을 바꾸기. 확정 뒤에는 고정이고, 구현 재시도 때 **빼는 것만** 된다(§4.10)
- 질문이 고정한 커밋을 작업으로 넘기기 — 사용자 결정. 대화 전문에 적어 두기만 한다
- 태그·특정 커밋 지정, 같은 repo의 다른 브랜치를 참조로, 하위 경로만 받기(sparse checkout) — 1단계와 같음
- 워커의 **대상** repo git 호출의 프롬프트 차단(인터뷰 쪽은 PR #99로 됨) — 기존 동작, 후속
- 인터뷰(INTERVIEW) 게이트의 기존 구멍 막기(NotebookEdit 허용, Bash `find -delete` 등) — 기존 동작, 별건. 구현은 원격의 고정 커밋으로 따로 받으므로 인터뷰 중 참조 폴더가 바뀌어도 구현으로 번지지 않는다
- 참조 repo의 DB 연결 선택 — DB는 지금처럼 대상 repo 기준

### 1.2 절대 안 되는 것

- 워커가 참조 repo를 커밋·push하는 것, 로컬 참조 폴더의 변경이 PR에 섞이는 것
  - 구현 에이전트의 **의도적인** 참조 push는 기술적으로 완전히 막지 못한다(권한 확인을 건너뛰는 세션, 같은 OS 사용자). 참조 worktree는 detached HEAD + worktree 전용 `pushurl` 무효화로 실수 push를 막고(§6.2), 프롬프트 규칙과 사후 검사로 드러낸다. 확실한 차단은 운영 조치(옛 repo를 GitLab에서 Archive·보호 브랜치)다. 가능성 보고서 §3.5와 같은 판단.
- 참조를 고르지 않은 작업의 동작이 지금과 달라지는 것: 화면 → API 바디(등록·승인·답변·확정), 인터뷰 kickoff·resume 프롬프트, 인터뷰 서비스 → API 보고 바디(`question`·`plan`, 빈 필드는 키째 생략), 구현 프롬프트, PR 본문. 워커 ↔ API의 Java DTO(구현 claim·결과)는 기존 관례대로 빈 목록·null 필드를 실어도 된다(`dbMcp`·`implementation` 선례, 동작은 같음 — 설계 판단)
- 참조 문제(권한·네트워크·없는 브랜치)로 **인터뷰**가 실패(FAILED)로 끝나는 것
- 참조를 다 준비하지 못한 채 **구현**이 claude를 실행하는 것
- 참조 폴더를 대상 worktree **안**에 두는 것(`git add -A`가 쓸어 담고, 그 안의 CLAUDE.md·`.claude/rules`가 필요 시 로드된다)
- 구현 claude에 참조 폴더를 `--add-dir`로 넘기는 것(`--setting-sources project` 아래에서 참조 repo의 `.claude/skills`·`commands`·`agents`·`enabledPlugins`가 구현 세션에 붙는다 — 공식 문서 확인, §6.7)

## 2. 확정된 결정

| 결정 | 내용 | 정한 사람 |
|---|---|---|
| 고르는 자리 | 작업 등록 때 요청자가 고르고, 승인 다이얼로그에서 관리자가 더하거나 뺀다 | 사용자 |
| 커밋 고정 | 작업 인터뷰가 처음 받은 커밋을 구현(재시도 포함)도 그대로 쓴다 | 사용자 |
| 못 받았을 때 | 인터뷰는 칩에 ⚠ 띄우고 계속(다음 턴에 다시 시도). 구현은 claude 실행 전에 구현실패(사유 표시, 재시도 가능) | 사용자 |
| 구현이 참조를 고쳤을 때 | 경고만 — PR 본문과 작업 이력에 적고 PR은 진행. 참조 폴더는 버린다. 커밋·push는 대상만 | 사용자 |
| 인터뷰 중 변경 | 인터뷰 답변 때 참조를 더하거나 뺄 수 있다(1단계 질문 `ask`와 같은 규칙·시스템 메모). 계획 확정 때 그 시점의 참조·고정 커밋이 작업에 저장된다 | 사용자 |
| 질문 → 작업 | 질문 대화 화면에 "이 대화로 작업 등록" 버튼. 등록 창을 대상·브랜치·참조·제목으로 채우고, 대화 전문 `.md`를 작업 첨부로, 출처 질문을 기록 | 사용자 |
| 디자인 단계 | 참조 없음 | 사용자 |
| 고정 커밋을 끝내 못 받을 때 | 구현 재시도 창에서 관리자가 문제 참조를 빼고 재시도한다(빼기만, 더하기·바꾸기 없음, 이력에 남김). 브랜치 최신으로 몰래 대신 받지 않는다 | 사용자(검토 뒤) |
| 1단계에서 이어 쓰는 규칙 | 최대 3개, 대상과 다른 repo만(참조끼리도 중복 금지), 참조마다 브랜치(기본 = 카탈로그 기본 브랜치), 칩에 고정 커밋, 사내 GitLab 위주(비공개 GitHub는 PAT), git은 자격증명 도우미·askpass·프롬프트 없이 | 사용자(1단계) |
| 계획 속 참조 표기 | 계획·설계 문서는 참조 파일을 절대 경로가 아니라 `별칭:경로:줄`로 적는다(인터뷰와 구현의 폴더 위치가 다름) | 설계 판단(사용자 확인) |
| 확정 때 고정 커밋이 없는 참조 | 인터뷰에서 한 번도 받지 못한 참조(sha 없음)는 구현으로 넘기지 않는다. 확정 화면에 미리 보여 주고 이력에 남긴다(§4.6) | 설계 판단 |
| 인터뷰 중 변경할 수 있는 사람 | 관리자만 — 작업 인터뷰 답변·확정 API가 이미 관리자 전용이다. 요청자는 칩을 읽기만 한다 | 설계 판단(기존 권한) |
| 워커 참조 폴더 | `{worktree-root}/_refs/task-{id}/{카탈로그 id}` — 대상 worktree 밖 짧은 경로(Windows 260자, 1단계와 같은 이유). 성공·실패 모두 작업 끝에 지운다 | 설계 판단(사용자 확인) |
| SDD·리뷰 방식 | 계획 scratch 파일 머리에 참조 절을 넣고, SDD 지시에 "서브에이전트에게 참조 절을 넘길 것"을 더한다 | 설계 판단(사용자 확인) |
| 대화 전문 | 서버가 만든다(`sourceQuestionId`로 질문 조회 권한 확인). 사용자가 올린 파일 10개 한도와 별개로 1개 | 설계 판단 |
| 마이그레이션 | V33 하나: `task.ref_repos`, `task.source_question_id` | 설계 판단(사용자 확인) |
| 인터뷰 참조 받기 시간 | 참조 하나당 5분 상한. 넘으면 그 참조만 ⚠(시간 초과), 턴은 계속. 질문 세션도 같은 도우미라 함께 적용 | 설계 판단(검토 반영) |
| 구버전 워커 막기 | 워커가 claim 요청에 참조 지원을 알리고, API는 알리지 않은 워커에게 참조 있는 구현을 넘기지 않는다 | 설계 판단(검토 반영) |
| 인터뷰 중 뺀 참조 | 규칙에 "목록에 없는 참조(앞 턴 것 포함)는 쓰지 않는다"를 넣고, 전부 뺀 세션에는 그 사실을 한 줄로 알린다 | 설계 판단(검토 반영) |

## 3. 데이터

### 3.1 마이그레이션 `V33__task_ref_repos.sql`

```sql
ALTER TABLE com.task ADD COLUMN ref_repos JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE com.task ADD COLUMN source_question_id BIGINT;
```

- 원소는 1단계 `entity/RefRepo`(catalogId, alias, path, gitUrl, branch, sha, fetchedAt, error) 그대로다. `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")`(V28 `db_connection_ids` 선례). 테스트 컨텍스트가 `ddl-auto: validate`라 엔티티와 컬럼이 맞아야 한다.
- `source_question_id`에는 FK를 걸지 않는다. `interview_session.task_id → task(id)`와 순환이 되고, 기록용이라 무결성이 필요 없다.
- 기존 작업은 `[]`·`null`이라 동작이 같다.

### 3.2 참조 목록의 수명

| 시점 | 어디에 | 무엇을 |
|---|---|---|
| 등록 | `task.ref_repos` | 요청자가 고른 것(sha·error 없음). `RepoCatalogService.resolveReferences(대상, 입력)` |
| 승인 | `task.ref_repos` → 새 인터뷰 세션 `interview_session.ref_repos`에 복사 | 관리자가 조정한 결과. `createForTask`가 DB 연결처럼 복사 |
| 인터뷰 | 세션 `ref_repos` | 턴마다 준비 결과로 고정(sha·fetchedAt)·실패(error). 답변 때 추가·해제 |
| 계획 확정 | 세션 → `task.ref_repos` | **sha가 있는 원소만**, error는 비워서. sha 없는 원소는 빼고 이력에 남긴다 |
| 구현·재시도·재기동 재큐 | `task.ref_repos` 그대로 | `prepareImplementationRequeue`가 건드리지 않으므로 같은 커밋 |
| 구현 재시도에서 빼기 | `task.ref_repos`에서 지정한 원소 삭제 | 관리자가 고른 것만. 남은 원소는 같은 커밋 그대로 |
| 인터뷰 취소·실패 뒤 재승인 | `task.ref_repos`(마지막 승인 때 값) | 인터뷰 중 바꾼 것은 버려진다(새 세션은 다시 복사·다시 고정) |

## 4. API

### 4.1 등록 — `POST /api/tasks` (JSON·multipart `meta`)

- `TaskCreateRequest`에 `@Valid List<RefRepoInput> refRepos`, `Long sourceQuestionId`를 더한다. 4인자 호환 생성자를 남긴다(호출 32곳).
- `TaskService.create`: `resolveForRegistration` 바로 뒤에 `resolveReferences(repo.catalogId(), req.refRepos())`. 오류 문구는 1단계 그대로(`참조 repo는 최대 3개까지 고를 수 있습니다` 등).
- `sourceQuestionId`가 있으면:
  - 등록자가 그 질문을 볼 수 있어야 한다(소유자 또는 관리자 — `QuestionService.requireViewable`과 같은 규칙). 아니면 403, 없거나 질문이 아니면 404.
  - 질문이 답변 중(QUEUED·RUNNING)이면 409 `답변이 끝난 뒤에 작업으로 등록할 수 있습니다`.
  - `task.source_question_id`를 저장하고, 대화 전문 `.md`를 만들어 작업 첨부로 저장한다(JSON·multipart 둘 다). 파일 이름 `question-{id}-대화.md`, content type `text/markdown`. 사용자 첨부(최대 10개) 다음 순번. 쓰기 실패는 등록 전체를 롤백(기존 multipart 롤백 방식).
  - 대화 전문에 넣는 것: 제목, 대상 `별칭 · 브랜치`, 질문 당시 참조와 그 고정 커밋(`별칭 · 브랜치@sha7`, 못 받았으면 `받지 못함`), 첫 질문(세션 `description` — 턴이 아님), 그 뒤 턴을 순서대로(사용자 추가 질문·답변·시스템 메모를 역할 표시와 함께). 질문 첨부는 파일 이름만 적고 내용은 복사하지 않는다. 형식 세부는 구현 판단.
- 대상과 같은 repo·참조끼리 중복은 1단계처럼 400.

### 4.2 승인 — `POST /api/tasks/{id}/approve`

- `ApproveRequest`에 `@Valid List<RefRepoInput> refRepos`를 5번째로 더한다(4인자 호환 생성자). 승인 컨트롤러 파라미터에는 지금 `@Valid`가 없으므로 붙인다(없으면 브랜치 255자 상한이 돌지 않는다. 바디는 지금처럼 생략 가능).
- 의미는 1단계 `ask`와 같다: `null` = 등록 값 유지, `[]` = 전부 해제, 같은 집합 = 검증 없는 no-op, 바뀌면 **새로 들어온 것만** 카탈로그 검증(등록 뒤 비활성된 기존 참조로 400이 나지 않게). 1단계 `QuestionService.applyRefChange`의 짝 맞춤·검증 로직을 정적 도우미로 뽑아 같이 쓴다(노트 남기는 자리는 각자 — `QuestionServiceTest`의 mock verify 보호).
- `startInterview`에서 세션을 만들기 전에 반영 → `t.setRefRepos(...)` → `createForTask`가 `s.setRefRepos(copy)`(시그니처 그대로). 바뀌었으면 승인 이력 다음에 별도 이력 행 `참조 repo 조정: ＋ …, − …`(기존 승인 이력 문구는 그대로).

### 4.3 인터뷰 답변 — `POST /api/interviews/{id}/answer` (관리자)

- `AnswerRequest`에 `@Valid List<RefRepoInput> refRepos`를 더한다(2인자 호환 생성자). 규칙은 §4.2와 같다(대상 = 세션의 `repoCatalogId`).
- 컨트롤러가 `submitAnswer`를 직접 부르는 지금 구조로는 참조 400이 답변 턴과 다른 트랜잭션이 된다. 서비스에 래퍼 하나를 두고 `submitAnswer` → 참조 변경 → `appendSystemNote`를 같은 트랜잭션으로 처리한다(400이면 답변 턴·재큐까지 롤백 — 1단계 `ask`와 같음).
- 시스템 메모 문구는 1단계 그대로: `참조 repo 변경: ＋ {별칭}({브랜치}), − {별칭}({브랜치})` / `참조 repo 변경: 없음(전부 해제)`. 컨트롤러는 `pushStatus(QUEUED)` 뒤 `pushNote`.
- 상한 3은 변경 뒤 결과 기준.

### 4.4 인터뷰 claim — `POST /worker/interviews/claim`

- `InterviewService.refsFor`의 `isQuestion()` 조건을 뺀다. INTERVIEW 세션도 `ClaimRefRepo`(dir = `{interview work-root}/_refs/{세션 id}/{카탈로그 id}`)를 싣는다. 경로 규칙·체크아웃 정리 잡은 1단계 그대로 적용된다(세션 id 공간이 같다).
- INTERVIEW claim에 `refsCleared`(boolean)를 더한다: 세션의 참조가 대화 중 변경으로 0개가 됐으면 true(마지막 참조 변경 메모가 `없음(전부 해제)`인 경우), 그 밖에는 false. 참조를 한 번도 고르지 않은 세션은 false라 프롬프트가 그대로다(§5.2). claim 레코드가 질문과 공용이라 QUESTION claim에도 늘 `false`로 실린다(러너는 질문에서 읽지 않는다 — §10 첫 줄).

### 4.5 인터뷰 보고 — `question`·`plan`

- `POST …/question`: 이미 kind와 무관하게 `applyRefStates`를 부른다. 변경 없음.
- `POST …/plan`: `WorkerPlanRequest`에 `List<RefState> refStates`를 더하고(호환 생성자) `recordPlan`에서 `applyRefStates`를 부른다. 없으면 마지막 답변에서 더한 참조가 계획 턴에 처음 받아져도 커밋이 고정되지 않는다.

### 4.6 계획 확정 — `POST /api/interviews/{id}/confirm`

- 바디는 그대로(`designRequested`, `implementationMode`).
- `confirm`에서 `t.setImplementationMode` 근처: 세션 `ref_repos` 중 **sha가 있는 원소만** error를 비워 `task.ref_repos`로 복사한다. sha가 없는 원소는 뺀다.
- 기존 확정 이력 문구는 바꾸지 않는다(테스트 고정). 참조가 있으면 별도 이력 행:
  - `구현 참조: {별칭}({브랜치})@{sha7}, …`
  - 뺀 것이 있으면 이어서 ` / 제외(인터뷰에서 받지 못함): {별칭}({브랜치}), …`
  - 고정된 참조가 하나도 없으면(모두 sha 없음) `구현 참조: 없음 / 제외(인터뷰에서 받지 못함): {별칭}({브랜치}), …`
  - 세션 참조가 비었으면(고르지 않았거나 인터뷰 중 모두 뺐음) 작업 참조를 `[]`로 덮고 이력 행은 남기지 않는다.
- 확정은 막지 않는다. 관리자는 확정 화면(§7.5)에서 미리 본다.

### 4.7 구현 claim — `WorkerTaskResponse`

- 31번째 구성요소 `List<TaskRefRepo> refRepos`: catalogId, alias, githubRepo(=path), gitUrl, repoHost, branch, sha. **dir은 싣지 않는다** — worktree-root는 워커 설정이라 워커가 계산한다.
- `forImplementation`에서만 채우고 디자인·배포·종료 claim은 `[]`. 30인자 호환 생성자와 `withDbMcp` 사본 메서드에 반영. 구버전 API의 null은 `List.of()`로 정규화.
- 구버전 워커는 모르는 필드를 무시하고 참조 없이 구현해 버린다. 배포 순서(§9)에 더해 기술적으로 막는다: 새 워커는 `GET /worker/next-task?workerId=…&supports=refRepos`로 claim하고, API는 이것이 없는 요청에는 `ref_repos`가 비어 있지 않은 구현 대기 작업을 claim하지 않는다(그 작업은 대기로 남는다 — 디자인·배포·종료 claim은 영향 없음). `supports`는 쉼표로 구분한 기능 토큰이고 앞뒤 공백을 지운 토큰 중 `refRepos`와 정확히 같은 것이 있어야 한다. 구버전 워커용 claim은 네이티브 쿼리(`TaskRepository.findClaimableExcludingRefImplementations…`)이고, 그 후보 상태 목록(`CLAIMABLE_STATUSES`)은 기존 JPQL claim 쿼리의 목록과 같아야 한다(단위 테스트가 쿼리 문자열과 대조).

### 4.8 구현 결과 — `WorkerResultRequest`

- 25번째 `List<String> refWarnings`(가린 문구, 원소당 500자). 호환 생성자, `withImplementationAttempt`, `masked()`에 반영.
- `recordResult`의 PR_CREATED 처리(지각 정합 분기 포함)에서 기존 이력 행 다음에 같은 상태 이력 행을 하나 더 남긴다: `참조 repo 변경 감지(PR에는 포함 안 됨): {경고들}`(500자, `DbMcpGrantService`의 같은 상태 행 선례).
- 참조 준비 실패는 기존 `IMPLEMENTATION_FAILED` + `failureReason`으로 충분하다(API 변경 없음).

### 4.9 조회

- `TaskResponse`에 `refRepos`(`InterviewResponse.RefRepoView`: catalogId, alias, branch, sha, fetchedAt, error)와 `sourceQuestionId`를 더한다. `build`·`withTotalCost`·`redactSecretValues` 세 곳 모두.
- 상세 응답에서 작업이 인터뷰 단계(인터뷰중·입력대기·플랜승인대기)면 `refRepos`는 **진행 중 세션의** 참조(상태 포함)를, 그 밖에는 작업의 참조를 준다. 화면은 5초 폴링으로 고정·실패 상태를 따라간다. 목록 응답은 작업의 참조만(세션 조회를 늘리지 않음). 세부는 구현 판단.

### 4.10 구현 재시도 — `POST /api/tasks/{id}/implementation/retry` (관리자)

- 바디에 `removeRefCatalogIds`(카탈로그 id 목록, 선택)를 더한다. 비었거나 없으면 지금과 같다(바디 `{implementationMode}`만 보내는 기존 호출·테스트 그대로).
- 목록의 id는 모두 지금 `task.ref_repos`에 있어야 한다(아니면 400 `작업에 없는 참조는 뺄 수 없습니다`). 빼기만 하고 남은 원소의 sha는 그대로.
- 기존 재시도 이력 문구는 바꾸지 않고(테스트 고정), 뺀 것이 있으면 다음에 별도 이력 행: `참조 제외(구현 재시도): {별칭}({브랜치}), …`.
- 모든 참조를 빼면 참조 없는 구현이 된다(프롬프트·PR 본문에 참조 절 없음).

## 5. 인터뷰 서비스 (INTERVIEW)

### 5.1 참조 준비

- `claimRefRepos`의 `kind === 'QUESTION'` 조건을 뺀다(보낼지는 API `refsFor`가 정함). `types.ts`의 "QUESTION만" 주석도 고친다.
- `runTurn`에서 `ensureRepo` → `throwIfAborted` 다음, DB MCP 만들기 전에: `ensureRefRepos` → `throwIfAborted` → `logRefFailures`. 질문 분기(`runQuestionTurn`)와 공통 도우미로 묶는다. 실패한 참조는 턴을 실패시키지 않는다. 준비기 자체가 예외를 던져도 모든 참조를 못 받은 것(⚠, 사유는 가린 예외 메시지 — 비면 `참조 repo를 받지 못했습니다`)으로 돌리고 턴을 계속한다 — 질문 세션도 같다(1단계에서는 턴 실패였다). 턴이 이미 중단됐으면(턴 상한) 그대로 중단한다.
- **참조 하나당 받기 시간 상한 5분**(설정값, 기본 300000ms). 넘으면 그 참조의 git을 멈추고 `.part`를 지운 뒤 실패로 돌린다(사유 `참조 repo 받기 시간 초과(5분)`). 턴 전체 상한(30분)의 중단 신호는 지금처럼 함께 따른다. 질문 세션도 같은 도우미라 함께 적용된다 — 참조 문제로 턴이 실패하지 않게 하는 §1.2를 지키기 위해서다(1단계는 "참조 받는 중 턴 타임아웃 = 턴 실패"를 받아들였으나, 작업 인터뷰는 실패하면 승인대기로 돌아가 문답을 잃는다).
- INTERVIEW는 Read/Grep/Glob이 사전 승인이라 `refRoots`·게이트·`sessionOptions`는 바꾸지 않는다(`permissions.test.ts`·`sessionOptions.test.ts`의 "INTERVIEW 그대로" 테스트 유지). 쓰기는 지금처럼 대상 `docs/superpowers/**`만.

### 5.2 프롬프트

- **kickoff**(새 세션): `참조 절 + DB 절 + 기존 kickoff`(질문과 같은 참조→DB 순서).
- **resume**(답변 턴): `참조 절 + 답변`. 참조 절은 매 턴 다시 싣는다 — 인터뷰 중 변경·실패를 알리기 위해서다. 기존 `prefix` 인자(DB 절)를 resume에 재사용하지 않는다(DB가 붙은 인터뷰의 resume 프롬프트가 바뀌므로). kickoff 전용 접두와 매 턴 접두를 나눈다.
- 참조가 없으면 참조 절은 `''` — 참조 없는 인터뷰의 kickoff·resume 프롬프트는 바이트 그대로.
- 강제 마무리 턴(재형식 splice)·handoff splice에는 참조 절을 붙이지 않는다(보고는 함, §5.3).
- **INTERVIEW용 참조 절** — 헤더·참조 줄·사용 불가 줄은 1단계 `refSection`과 같고, 규칙만 다르다(질문용 출력은 바이트 그대로 유지):

```
참조 repo (읽기 전용 — 비교·참고용, 수정 금지):
- {별칭} · {브랜치}@{sha7} : {절대경로}
사용할 수 없는 참조: {별칭}({브랜치}) — {사유}
규칙:
- 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로에서 읽기만 한다.
- 참조 폴더의 파일은 만들거나 고치거나 지우지 않는다(셸 명령 포함).
- 위 목록에 없는 참조(앞 턴에 있던 것 포함)는 더 쓰지 않는다. 구현에는 이 목록의 참조만 넘어간다.
- 설계·계획 문서에는 참조 파일을 절대 경로가 아니라 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`)로 적는다. 구현 단계에서는 참조 폴더 위치가 다르다.
- 사용할 수 없는 참조의 내용은 추측하지 않는다.
```

- `refsCleared`가 true면(참조를 대화 중 모두 뺐음) 참조 절 대신 이 한 줄을 매 턴 싣는다: `참조 repo: 없음(대화 중 모두 뺐다) — 앞서 본 참조는 더 쓰지 않는다. 계획은 대상 repo만으로 세운다.` + `\n\n`.

### 5.3 보고

- `postQuestion`·`postPlan` 모두 참조가 있을 때만 `refStates`를 싣는다(키 자체를 생략 — 참조 없는 바디 바이트 그대로). 실패·중단된 턴은 보고하지 않는다(1단계와 같음).

### 5.4 알려진 한계

- 인터뷰 중 뺀 참조의 폴더는 디스크에 남고(같은 브랜치로 다시 더하면 커밋을 지키려고 재사용 — 1단계 결정), INTERVIEW의 Read는 사전 승인이라 계속 읽힐 수 있다. 매 턴 참조 절에 지금 목록만 싣는 것으로 대신한다.
- 참조는 차례로 받으므로 참조 3개가 모두 5분 상한까지 걸리고(최대 15분) 대상 clone도 오래 걸리면 턴 상한(30분)에 닿아 턴이 실패할 수 있다. 참조당 상한 × 3이 턴 상한보다 충분히 작아야 한다(`.env.example`에 적음, 기동 때 검사는 하지 않음).
- 인터뷰 중 참조 폴더가 사라져(수동 삭제 등) 다음 턴에 다시 받으면 브랜치 최신을 받는다. API는 처음 고정한 커밋을 지키므로 인터뷰가 그 뒤에 본 커밋과 구현이 쓸 고정 커밋이 다를 수 있다(1단계의 단순화를 그대로 둠, 러너는 경고 로그만 남긴다).

## 6. 워커 — 구현

### 6.1 순서 (`WorkerMainLoop.processImplementation`)

1. **참조 준비**(참조가 있을 때만, 입력 순서대로 하나씩) — 하나라도 실패하면 이미 만든 참조 폴더를 지우고 `IMPLEMENTATION_FAILED`로 보고하고 끝낸다. 대상은 fetch도 worktree도 하지 않는다. 사유: `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}`(가림, 마지막 `fatal:`/`error:` 줄 우선, 없으면 비지 않은 첫 줄, 300자까지 — 메시지가 없으면 예외 이름. 워커가 직접 판정한 실패의 문구는 §6.2)
2. 대상 `ensureFresh`(지금 그대로) — 실패하면 1에서 만든 참조 폴더를 지우고 지금처럼 `레포 fetch 실패: …`로 보고한다
3. 대상 worktree 만들기 → scratch → 프롬프트 → claude(지금 그대로 + §6.4·§6.5)
4. claude가 끝나면(성공·실패 모두) **참조 변경 검사**(§6.6)
5. 커밋·push·PR(대상만, 지금 그대로) — PR 본문에 참조 절(§6.6)
6. 참조 폴더 지우기(성공·실패 모든 경로). 대상 worktree 처리는 지금 그대로(성공이면 지움, 실패면 남김)

참조 준비를 대상 `ensureFresh`보다 먼저 하는 이유(구현 중 결정): `WorktreeService.create`는 만드는 시점의 `origin/{base}`에서 분기하고 프롬프트·리뷰·커밋 판정의 baseSha는 fetch 때의 커밋이다. 그 사이에 분 단위의 참조 준비가 끼면 같은 PC의 다른 워커가 같은 브랜치를 최신화할 때 둘이 어긋나므로, 대상 fetch → 대상 worktree 사이를 참조가 없을 때처럼 짧게 둔다(락은 여전히 한 번에 하나 — §6.2).

참조가 없으면 1·4·6은 아무것도 하지 않고, 새 메서드도 부르지 않는다(기존 테스트의 mock이 null을 돌려줌).

### 6.2 참조 폴더 만들기 (고정 커밋, detached)

- 폴더: `{worktree-root}/_refs/task-{작업 id}/{카탈로그 id}`. git이 보는 원본은 그 참조 repo의 레포 캐시 `{repos-dir}/{localKey}`(대상과 같은 캐시·락을 공유).
- 캐시가 없으면 기존 clone 경로로 만든다(평문 origin 유지).
- 그 참조의 레포 락(`withRepoLock(refKey)`) 안에서:
  1. `git cat-file -e {sha}^{commit}` — 있으면 4로
  2. 없으면 `git fetch --depth=1 {인증 URL} {40자 sha}` — GitHub과 GitLab 13.2+(Gitaly가 `uploadpack.allowAnySHA1InWant`를 켬)는 허용한다. 사내 GitLab 버전은 미확인(§10 라이브 확인 1)
  3. 거부되면 대안: 그 브랜치를 **참조 전용 이름공간**(예: `refs/netis-refs/{브랜치}`)으로 깊이를 늘려 가며 받는다(`--depth=50` → `--depth=500`, 그 이상은 하지 않음). 매번 1로 다시 확인하고, 끝까지 없으면 실패. 브랜치 받기 자체가 실패하면(권한·네트워크·없는 브랜치) 더 깊이 받아도 소용없으므로 거기서 그 git 오류로 실패한다(구현 중 결정 — 일시적 실패는 관리자 재시도로). `refs/remotes/origin/*`·캐시 HEAD는 건드리지 않고, 깊이 제한 없는 fetch는 하지 않는다(공유 캐시에 수 GB를 받으며 락을 오래 쥐면 다른 작업이 60초 락 대기로 실패한다)
  4. 같은 폴더가 있으면 지우고 `git worktree add --force --detach {폴더} {sha}`
  5. 실수 push 막기: 그 worktree에만 `remote.origin.pushurl`을 쓸 수 없는 값으로 둔다(worktree 전용 설정 — 공유 캐시에 `extensions.worktreeConfig`를 켠다. 다른 worktree·대상 작업에는 영향 없음). detached HEAD라 그냥 `git push`도 실패한다
- **`ensureFresh`는 참조에 쓰지 않는다** — 공유 캐시의 HEAD와 `origin/<브랜치>`를 옮겨, 같은 repo를 대상으로 쓰는 다른 작업의 기준 커밋을 흔든다.
- **락은 한 번에 하나만** 잡는다(참조1 → 참조2 → 대상 fetch → 대상 worktree 순차, 중첩 금지 — 순서는 §6.1). 같은 키를 중첩하면 60초 뒤 "repo lock timeout"이 되고, 다른 키를 중첩하면 프로세스 사이 교착이 생길 수 있다.
- **git은 자격증명 도우미·askpass·프롬프트 없이**: `-c credential.helper= -c core.askPass=`, env `GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`, `GIT_ASKPASS`·`SSH_ASKPASS` 제거(지금 `ProcessRunner`는 env를 지우지 못하므로 넓힌다). 참조 git 호출에만 적용한다.
- sha 없는 참조는 §4.6에 따라 오지 않는다. 혹시 오면(구버전 API 등) 그 참조는 준비 실패로 다룬다. 고정 커밋으로 인정하는 sha는 40자·64자 16진수뿐이다(API `applyRefStates`와 같은 정의, git에는 소문자로 넘긴다).
- 워커가 직접 판정한 준비 실패의 사유(§6.1의 `{사유}` 자리):
  - `고정 커밋 정보가 없거나 올바르지 않습니다` — sha가 없거나 위 모양이 아님
  - `참조 브랜치 정보가 없습니다` — 브랜치가 비었음
  - `고정 커밋 {sha7}을(를) 찾지 못했습니다 — 브랜치 {브랜치}의 최근 500개 커밋 안에 없음` — 3의 대안까지 받았는데 없음
  - 그 밖(clone·fetch·worktree add 실패)은 git 출력의 마지막 `fatal:`/`error:` 줄

### 6.3 시간

- 구현 상한(직접 45 / 리뷰 80 / SDD 240분)과 멈춤 판정(60 / 95 / 260분, `claimed_at` 기준)의 여유는 15~20분이고 참조 준비가 이를 먹는다.
- claude 상한에서 **참조 준비에 쓴 시간만큼 뺀다**(참조가 없으면 지금과 같음). 남은 시간이 없으면 준비한 참조 폴더를 지우고 claude 없이 시간 초과로 실패한다: `시간 초과: 참조 repo 준비에 {초}초를 써 구현 상한({분}분)이 남지 않았습니다`.
- 참조별 준비 시간을 로그에 남긴다.

### 6.4 프롬프트 — 참조 절

- 템플릿 밖, 호출 자리에서 DB 절 뒤에 붙인다(운영자 템플릿을 바꿔도 빠지지 않게. `endsWith(DEPLOY_ENV_INSTRUCTION)` 테스트는 참조 없을 때 그대로). 참조가 없으면 `''`.

```
## 참조 repo (읽기 전용)
아래 폴더는 비교·이식할 때 읽기만 하는 참조다. 구현은 현재 디렉토리(대상)에서만 한다.
- {별칭} · {브랜치}@{sha7} : {절대경로}
규칙:
- 참조 폴더의 파일을 만들거나 고치거나 지우지 않는다. 빌드·설치·테스트 같은 명령도 참조 폴더에서 돌리지 않는다.
- 계획·설계에 적힌 `별칭:경로:줄`은 위 별칭의 폴더에서 찾는다.
- 참조 폴더는 커밋되지 않는다. 필요한 코드는 대상에 옮겨 적는다.
```

- 앞에 빈 줄 둘(`\n\n`)을 둔다(DB 절과 같은 방식).

### 6.5 리뷰·SDD 방식

- 계획 scratch 파일(`.netis/plan/task-{id}-plan.md`)을 쓸 때 위 참조 절을 **파일 머리에** 붙인다. `countTaskHeadings`·`normalizeTaskHeadings`에 넘기는 계획 본문에는 붙이지 않는다. 참조 절에는 `### Task N`·`### 작업 N` 모양의 줄이 없어야 한다.
- SDD 계획 절(`sddPlanSection`)에 참조가 있을 때만 한 줄을 더한다: `- 서브에이전트(구현·리뷰)에게 일을 맡길 때 계획 파일 머리의 "참조 repo" 절을 그대로 함께 넘긴다.` — SDD 지시서(task-brief)는 `Task N` 사이 줄만 뽑아 머리를 놓치기 때문이다.

### 6.6 참조 변경 검사·PR 본문·보고

- **기준선과 비교한다.** 참조 worktree를 만든 직후 `git status --porcelain --untracked-files=all --ignored=matching` 결과를 기준선으로 잡아 두고, claude가 끝난 뒤 같은 명령과 `git rev-parse HEAD`를 다시 본다. 기준선에 없던 줄(무시 파일 포함 — 참조 폴더에서 빌드·설치한 흔적도 잡는다)이 있거나 HEAD가 고정 sha가 아니면 경고 `{별칭}: 파일 {N}개`(HEAD가 다르면 `{별칭}: HEAD가 고정 커밋과 다름`). 기준선과 비교하므로 checkout 직후부터 dirty로 보이는 옛 repo(CRLF로 커밋된 파일·`.gitattributes`)에서도 가짜 경고가 나지 않는다.
- 경고 문구는 참조마다 하나, 넷 중 하나다:
  - `{별칭}: 파일 {N}개` — 기준선에 없던 상태 줄 N개
  - `{별칭}: HEAD가 고정 커밋과 다름` — 파일 수보다 우선
  - `{별칭}: 참조 폴더가 지워짐` — 폴더가 없음
  - `{별칭}: 변경 확인 실패` — 폴더에 `.git`이 없거나 git을 돌리지 못함
- 경고는 PR 본문과 결과 보고(`refWarnings`, §4.8)에 싣는다(여럿이면 `, `로 잇는다). 구현 실패로 끝난 경우에도 실패 사유 끝에 ` — 참조 repo 변경 감지: {경고들}`을 덧붙인다.
- PR 본문: 머리 목록(Task·베이스·구현 SHA·구현 방식·디자인·소요시간) 다음, 잘리지 않는 고정 절로 넣고 길이 상한(60,000자) 계산의 `room`에 포함한다. 참조가 없으면 아무것도 넣지 않는다(기존 PR 본문 테스트 그대로).

```
## 참조 repo (읽기 전용)
- {별칭} · {브랜치}@{sha7}
> ⚠ 참조 repo 변경 감지(이 PR에는 포함 안 됨): {경고들}
```

  경고 줄은 경고가 있을 때만.

### 6.7 `--add-dir`를 쓰지 않는 이유

- 구현은 권한 확인을 건너뛰므로 절대 경로로 읽는 데 `--add-dir`가 필요 없다.
- `--add-dir` 폴더는 `--setting-sources project` 아래에서 `.claude/skills`·`commands`·`agents`와 `enabledPlugins`가 로드된다(CLAUDE.md는 `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`일 때만). 참조 repo의 설정이 구현 세션에 붙으면 안 된다.
- 대상 worktree 밖 형제 폴더를 절대 경로로 읽을 때는 그 폴더의 CLAUDE.md가 로드되지 않는다(cwd 아래 하위 폴더만 해당 — 공식 문서 "How CLAUDE.md files load").

### 6.8 정리

- 작업 끝(모든 경로)에 참조 폴더를 지운다: 참조 캐시의 락 안에서 `git worktree remove --force` → 남은 트리 삭제(링크를 따라가지 않는 `util/FileTrees`) → `git worktree prune`. 대상용 `discard(repoKey, kind, id)`와 별도 메서드로 둔다(테스트가 그 시그니처를 `never()`로 검사).
- 크래시 등으로 남은 `_refs/task-{id}/…`는 `WorktreeCleanupJob`이 지운다. 지금 스캔(`root/a/b/{task|deploy|design}-N`)은 이 모양을 건너뛰므로 `_refs` 아래를 따로 판정한다: 작업 id로 진행 중 보호(`GET /worker/active-worktree-tasks`)와 나이(7일)를 보고, 각 폴더의 `.git` 파일(gitdir)로 원본 캐시를 찾아 그 캐시의 비차단 락으로 지운다. `tryWithRepoLock("_refs/…")`처럼 가짜 키로 락을 잡지 않는다(`{repos-dir}/_refs/….lock`이 생긴다).
  - 원본 캐시를 찾을 수 없으면(`.git` 파일 없음·형식 이상·repos-dir 밖·이미 회수된 캐시) 진행 중이 아니고 7일이 지났을 때 git 없이 폴더만 지운다(링크를 따라가지 않는 삭제). 참조 폴더는 고정 커밋에서 언제든 다시 만들 수 있는 사본이고, 캐시 쪽에 남은 worktree 메타는 다음 `git worktree prune`이나 레포 캐시 정리가 처리한다. 대상 `discard`가 캐시가 없을 때 폴더를 직접 지우는 것과 같은 방향.
- `WorktreeKind`에 값을 더하지 않는다(종료 단계 `TaskCloser`가 모든 값을 돌며 대상 키로 지운다).

## 7. 프론트엔드

### 7.1 칩 문구 — 작업 맥락

`RefRepoChips`에 맥락 prop을 더한다(기본값 = 질문, 1단계 문구·테스트 그대로). 작업 맥락 문구:

| 자리 | 대기 칩 | 고정 칩 툴팁 | 실패 칩 툴팁 마지막 줄 |
|---|---|---|---|
| 작업 등록·승인 다이얼로그, 작업 상세(승인대기) | `인터뷰 때 받음` | — | — |
| 작업 상세(인터뷰 중) | `다음 답변 때 받음` | `고정 커밋 {sha7} · {받은 시각} — 구현까지 이 커밋 기준` | `이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다` |
| 작업 상세(확정 뒤) | — | `고정 커밋 {sha7} · {받은 시각} — 구현까지 이 커밋 기준` | — |

라벨 `참조 repo · 읽기 전용 · 최대 3개`, `＋ 참조 추가`, `✎ 참조 변경`, `참조 repo 없음`은 1단계 그대로.

### 7.2 작업 등록 창 (`pages/tasks/index.vue`)

- 레포·브랜치 선택 바로 아래 편집 칩 줄 + 인라인 추가 폼(새 질문 화면 B안과 같은 모양). 대상을 참조 중 하나로 바꾸면 그 참조만 뺀다.
- 바디에는 참조가 있을 때만 `refRepos`, 출처가 있을 때만 `sourceQuestionId`(기존 정확-바디 테스트 4필드 유지).
- **질문에서 넘어온 경우**: `/tasks?fromQuestion={id}`로 들어오면 마운트 뒤(서버 렌더 아님) 질문을 읽어 창을 열고 대상·브랜치·참조(sha·상태는 지우고 대기 칩으로)·제목을 채운 뒤 주소에서 쿼리를 뺀다(새로고침해도 다시 안 열림). "작업 상세"는 채우지 않는다(질문은 비교였고 작업은 이식이라 요청자가 직접 쓴다 — 대화 전문은 첨부로 간다).
  - 브랜치는 브랜치 목록이 온 뒤에 적용한다(카탈로그 기본 브랜치 덮어쓰기보다 나중). 질문의 브랜치가 목록에 없거나 조회가 실패하면 브랜치를 비우고 `질문의 브랜치 {브랜치}를 찾을 수 없습니다 — 브랜치를 골라 주세요`로 알린다(기본 브랜치로 조용히 바꾸지 않음).
  - 창 맨 위 안내: `질문 #{id} "{제목}"에서 이어짐 — 대화 전문이 첨부됩니다` + ✕(누르면 출처 연결과 대화 전문 첨부를 뺀다. 채운 값은 그대로). ✕의 `aria-label`은 `출처 연결 빼기`.
  - 대상 repo가 카탈로그에 없거나(비활성) 질문에 `repoCatalogId`가 없으면 대상은 비워 두고 알린다: `질문의 대상 레포를 카탈로그에서 찾을 수 없습니다 — 레포를 골라 주세요`. 참조도 카탈로그에 없는(지원하지 않는 호스트 포함) 것은 빼고 알린다: `카탈로그에 없는 참조는 뺐습니다: {별칭}({브랜치}), …`.
  - 질문을 읽지 못하면 창을 열지 않고 서버 문구(없으면 `질문을 불러오지 못했습니다`)로 알린다.
  - 등록 뒤 동작은 지금처럼(목록 갱신).
- 테스트 환경에 `useRoute` 기본값(`query: {}`)을 더한다(지금 `tasks/index.vue`는 `useRoute`를 쓰지 않아 여러 spec이 주입하지 않음).

### 7.3 승인 다이얼로그 (`components/ApproveDialog.vue`)

- DB 연결 아래, 사용량 위에 편집 칩 줄 + 추가 폼. 열 때마다 `task.refRepos`로 채운다(재승인 때도 작업의 값).
- 카탈로그는 `＋ 참조 추가`를 처음 누를 때 불러온다(열 때 API 호출을 늘리지 않음). 못 받으면 서버 문구(없으면 `레포 목록을 불러오지 못했습니다`)로 알리고 다음에 누를 때 다시 부른다.
- 바디에는 바뀌었을 때만 `refRepos`(기존 정확-바디 테스트 유지).
- 승인 요약(`ApproveTaskSummary`)에는 읽기 전용 칩 줄을 넣지 않는다(편집 줄이 바로 아래에 있으므로). 모바일 전체화면에서도 같은 순서.

### 7.4 작업 상세 (`pages/tasks/[id].vue`, `components/tasks/TaskDetailMobile.vue`)

- 데스크톱: DB 연결 칩 다음에 참조 칩 줄(`TaskResponse.refRepos`, §4.9). 참조가 있을 때, 그리고 인터뷰 단계에서 관리자가 볼 때(참조 없이 등록된 작업에도 더할 수 있게 — 비면 `참조 repo 없음`) 그린다. 그 밖에 참조가 없으면 숨긴다(DB 칩과 같음 — 참조 없는 작업 상세는 지금 그대로). 출처가 있으면 "레포" 옆에 `↩ 질문 #{id}에서 이어짐` 링크(`/questions/{id}` — 볼 권한이 없으면 그 화면이 안내).
- 인터뷰 단계에서 관리자에게만 `✎ 참조 변경`(입력대기일 때만 열림, 툴팁 `인터뷰 중 변경 — 다음 답변부터 적용`). 카탈로그는 메뉴를 처음 열 때 불러오고, 못 받으면 서버 문구(없으면 `레포 목록을 불러오지 못했습니다`)로 알린다. 1단계 대화 화면과 같은 방식: 초안은 **인터뷰 세션마다 한 번** 시드한다(`interviewSessionId`가 바뀌면 다시 — 같은 페이지에서 취소·재승인으로 새 세션이 생겨도 낡은 초안이 승인 때 뺀 참조를 되살리지 않게), 바뀌었을 때만 답변 바디에 `refRepos`. `InterviewPanel`이 두 번(모바일·데스크톱) 쓰이므로 둘 다 연결한다. 기본 입력창(`send-answer`)은 유지.
- `InterviewPanel`은 INTERVIEW 답변에서도 `refRepos`를 싣는다(지금 질문만 — `InterviewPanel.spec.ts`의 "INTERVIEW는 refRepos를 버린다" 테스트를 뒤집는다).
- 모바일: 정보 영역에 같은 칩 줄(읽기 전용) — 인터뷰 중 `✎ 참조 변경`은 모바일 인터뷰 카드에도.

### 7.5 계획 확정 (`components/InterviewPanel.vue`)

- "구현 진행" 위에 `구현에 쓸 참조 (이 커밋으로 고정)` 칩 줄. 분류는 서버 복사 규칙(§4.6)과 같다:
  - sha 있음 → 고정 칩(error가 있어도 고정 칩으로 그리고 아래에 `⚠ {별칭}: 마지막 턴에는 받지 못했습니다 — 고정 커밋으로 구현합니다`)
  - sha 없음 → 칩 대신 `⚠ {별칭}({브랜치}) — 인터뷰에서 받지 못해 구현에 넘기지 않습니다`
- 데이터는 작업 상세가 이미 폴링하는 `TaskResponse.refRepos`(인터뷰 단계 = 세션 참조)를 prop으로 넘겨 쓴다(패널은 폴링이 없음). 요청자에게도 보인다(읽기 전용).
- 확정 바디는 그대로.

### 7.5b 구현 재시도 창 (`components/tasks/ImplementationRetryDialog.vue`)

- 작업에 참조가 있으면 구현 방식 아래에 `참조 repo` 목록을 보여 주고, 참조마다 `이 참조 빼고 재시도` 선택을 둔다(기본 해제). 실패 사유가 `참조 repo 준비 실패: {별칭}(…)`이면 그 참조를 미리 선택해 둔다.
- 하나라도 고르면 바디에 `removeRefCatalogIds`를 싣는다(아니면 지금 바디 그대로).
- 안내 문구: `뺀 참조는 이번 구현과 이후 재시도에서 쓰지 않습니다.`

### 7.6 질문 대화 화면 (`pages/questions/[id].vue`)

- 헤더 `세션 종료` 앞에 `이 대화로 작업 등록` 버튼(아이콘 `add_task` — 작업 등록 버튼과 같은 것. 앱은 클래식 Material Icons만 실어서 `assignment_add`는 없다. 좁은 화면은 아이콘만 + `aria-label`). 끝난 질문에서도 보인다. 답변 중이거나 `repoCatalogId`가 없으면 막는다.
- 누르면 `/tasks?fromQuestion={id}`로 이동.

### 7.7 타입·스모크

- `TaskResponse` 타입 사본들(`pages/tasks/[id].vue`, `index.vue`, `TaskDetailMobile`의 `DetailTask`, `ApproveTaskSummary`의 `SummaryTask` 등)에 필요한 곳만 `refRepos?`·`sourceQuestionId?`를 더한다.
- `scripts/hydration-smoke.mjs`: 작업 목에 참조 3상태, 질문 목에 `repoCatalogId`. `/tasks?fromQuestion=3` 경로를 더하고 `/api/repos/branches` 목을 더한다.

## 8. 오류 처리

| 상황 | 결과 |
|---|---|
| 등록·승인·답변의 참조 검증 실패 | 400, 1단계 문구 |
| 출처 질문을 볼 권한 없음 / 없음 / 답변 중 | 403 / 404 / 409 |
| 인터뷰에서 참조 받기 실패·5분 초과 | 칩 ⚠ + 사유, 그 참조 없이 턴 진행, 다음 턴에 다시 시도 |
| 참조 지원을 알리지 않은 워커의 claim | 참조 있는 구현은 넘기지 않음(대기로 남음) |
| 확정 때 sha 없는 참조 | 구현에서 빼고 이력·확정 화면에 표시 |
| 구현에서 참조 준비 실패(clone·고정 커밋 받기·worktree) | claude 실행 전 `구현실패`, `참조 repo 준비 실패: …`. 일시적이면 그대로 재시도, 고정 커밋이 사라졌거나 권한이 없으면 재시도 창에서 그 참조를 빼고 재시도 |
| 재시도 때 작업에 없는 참조를 빼려 함 | 400 `작업에 없는 참조는 뺄 수 없습니다` |
| 구현이 참조 폴더를 바꿈 | PR 진행, PR 본문·이력에 경고 |
| 참조 준비로 시간 여유 부족 | claude 상한에서 뺌, 남은 시간 없으면 시간 초과 실패 |

## 9. 배포 순서·호환

- **인터뷰 서비스·워커 → API(V33) → 프론트.**
  - 구버전 러너는 INTERVIEW claim의 `refRepos`를 버리고(참조 없이 인터뷰), 구버전 워커는 모르는 필드를 무시한다(참조 없이 구현). 실행 쪽을 먼저 올려야 한다.
  - 새 러너가 구버전 API에 `postPlan.refStates`를 보내면 무시된다(400 아님).
  - 화면이 마지막이라 그 전에는 아무도 참조를 고를 수 없다.
- 워커는 진행 중 구현이 없을 때 재기동한다(기존 규칙).

## 10. 테스트

**반드시 있어야 하는 것**

- 참조 없는 작업 불변식: 화면·인터뷰 서비스 바디는 바이트 그대로 — 화면 → API 바디(등록·승인·답변·확정·재시도), INTERVIEW kickoff·resume 프롬프트(DB 붙은 경우 포함), 인터뷰 서비스 → API `postQuestion`·`postPlan` 바디, 구현 프롬프트(`endsWith(DEPLOY_ENV_INSTRUCTION)`), PR 본문. 워커 ↔ API Java DTO는 값이 같으면 됨(빈 목록·null 허용 — §1.2): API → 인터뷰 서비스·워커 claim에는 늘 실리는 필드(`refsCleared: false`, `refRepos: []`)가 늘 수 있고, 워커 → API 결과는 `refWarnings`가 null이어도 된다
- API: 등록 참조 검증·출처 질문 권한·대화 전문 첨부(첫 질문이 `description`에서 옴), 승인의 null/[]/같은 집합/새 것만 검증, 답변 + 참조 400이면 답변 턴까지 롤백, INTERVIEW claim에 dir 포함, `recordPlan`의 `applyRefStates`, 확정 복사(sha 있는 것만, error 비움, 이력 행), 구현 claim의 `refRepos`, `refWarnings` 이력 행(지각 정합 포함), V33 JSONB 왕복(Testcontainers)
- 인터뷰 서비스: INTERVIEW 참조 준비 순서, 참조당 5분 상한(넘으면 그 참조만 ⚠, 턴 계속 — 질문 세션 포함), kickoff/resume 접두 분리, INTERVIEW용 규칙 문구, `refsCleared` 한 줄, 질문용 `refSection` 바이트 그대로, refStates 키 생략, 중단 턴 미보고
- 워커: 참조 준비(캐시 있음/없음, sha 있음/없음 → depth=1 fetch → 참조 전용 이름공간 대안(깊이 상한) → 실패, `origin/*`·캐시 HEAD 불변), worktree 전용 pushurl 무효화, 락 비중첩, 무프롬프트 인자·env, 준비 실패 시 claude 미실행·대상 worktree 미생성·참조 폴더 정리, 시간 차감, 프롬프트 절, plan scratch 머리(Task 줄 수 불변), SDD 한 줄, 기준선 대비 변경 검사(무시 파일 포함·처음부터 dirty인 repo에서 가짜 경고 없음), PR 본문 절(상한 계산 포함), 모든 경로의 참조 폴더 정리, 정리 잡의 `_refs` 판정(진행 중 보호·가짜 락 키 없음·원본 캐시를 못 찾을 때 폴더만 삭제), claim 요청의 참조 지원 표시
- API 추가: 참조 지원을 알리지 않은 워커는 참조 있는 구현을 claim하지 못함(디자인·배포·종료는 그대로), 승인 컨트롤러 `@Valid`, INTERVIEW claim `refsCleared`, 구현 재시도의 `removeRefCatalogIds`(빼기만·없는 id 400·이력 행·바디 없으면 지금 그대로)
- 프론트 추가: 재시도 창의 참조 빼기 선택(참조 준비 실패 사유면 미리 선택, 고를 때만 바디에 실음)
- 프론트: 등록·승인 바디(참조 있을 때만), `fromQuestion` 프리필(브랜치 적용 순서·없는 브랜치 알림, 작업 상세 비움, 쿼리 제거, 카탈로그에 없는 대상·참조), 칩 맥락 문구(승인대기 포함), 작업 상세 칩 줄 표시 조건과 세션마다 초안 시드, INTERVIEW 답변 `refRepos`, 확정 화면 분류(sha+error·sha 없음), 질문 화면 버튼(아이콘 `add_task`, 상태별 막힘), 하이드레이션 스모크 경로·목

**라이브 확인(병합 뒤)**

1. 사내 GitLab에서 `git fetch --depth=1 <url> <옛 sha>`가 되는지(GitLab 13.2+는 서버가 허용 — 버전 미확인)
2. GitLab 참조로 작업 등록 → 승인 조정 → 인터뷰(칩 고정·⚠·답변 중 변경 메모) → 확정(구현 참조 이력) → 구현(PR 본문 참조 절, 커밋에 참조 없음)
3. 구현 claude가 참조 폴더 CLAUDE.md를 읽지 않는지(`InstructionsLoaded` 훅이나 로그)
4. 참조 준비 시간과 구현 판정 여유
5. 질문 → "이 대화로 작업 등록" → 대화 전문 첨부가 인터뷰 kickoff에서 읽히는지

## 11. 영향 파일 (구현 반영)

- 백엔드: `db/migration/V33__task_ref_repos.sql`(신규), `entity/Task`·`InterviewSession`(주석), `dto/TaskCreateRequest`·`ApproveRequest`·`AnswerRequest`·`ImplementationRetryRequest`·`InterviewClaimResponse`·`WorkerPlanRequest`·`WorkerTaskResponse`·`WorkerResultRequest`·`TaskResponse`·`InterviewResponse`(주석), `service/RefRepoChanges`(신규 — `QuestionService.applyRefChange`에서 뺀 정적 도우미, 질문·승인·답변 공용)·`QuestionTranscript`(신규 — 대화 전문 렌더러)·`TaskService`·`InterviewService`·`QuestionService`·`WorkerService`, `repository/TaskRepository`(구버전 워커용 네이티브 claim 쿼리·`CLAIMABLE_STATUSES`), `git/GitRemotes`(`maskAndCut` — 가린 뒤 자르기, 이력·보고 공용), `controller/InterviewController`·`TaskController`·`WorkerController`(`supports`). `RepoCatalogService`는 그대로 썼다(바꾸지 않음)
- 워커: `workerdaemon/WorkerMainLoop`·`GitRepoCache`·`WorktreeService`·`WorktreeCleanupJob`·`WorktreeCleanupPlanner`·`ProcessRunner`·`ImplementationPrompts`·`WorkerHttpClient`(`supports=refRepos`), `RefRepoPrompts`(신규 — 참조 절 빌더)
- 인터뷰 서비스: `runner/interviewRunner.ts`, `runner/repoPrepare.ts`(참조당 `timeoutMs`), `types.ts`, `config.ts`·`index.ts`·`.env.example`(`INTERVIEW_REF_TIMEOUT_MS`), 테스트·fixture. `api/javaClient.ts`는 바디를 그대로 직렬화하므로 바꾸지 않았다
- 프론트: `pages/tasks/index.vue`·`[id].vue`, `components/ApproveDialog.vue`·`InterviewPanel.vue`·`RefRepoChips.vue`·`tasks/TaskDetailMobile.vue`·`tasks/ApproveTaskSummary.vue`(타입)·`tasks/ImplementationRetryDialog.vue`·`tasks/TaskRefChangeMenu.vue`(신규 — 인터뷰 중 참조 변경 메뉴), `composables/refRepos.ts`(칩 맥락 문구·편집 초안 `useRefDraft`·카탈로그 한 번 받기 `useRefCatalog`·확정 분류·재시도 미리 고르기), `pages/questions/[id].vue`, `composables/questions.ts`, `test/setup.ts`, `scripts/hydration-smoke.mjs`
- 문서: `CLAUDE.md`(질문 세션·작업 항목, 자주 보는 코드, 배포 순서, worktree 보존)

## 12. 구현에 맡기는 것

- 대화 전문 `.md`의 세부 형식(제목·역할 표시·시각 표기)
- 상세 응답에서 인터뷰 단계 참조를 고르는 방법(§4.9의 동작만 지키면 됨)
- `--deepen` 횟수·단계 크기, 참조 git 명령별 시간 상한
- 참조 절 빌더의 위치(새 클래스 또는 `ImplementationPrompts`)
- 작업 상세에서 `✎ 참조 변경`을 패널 슬롯으로 넣을지 패널 기능으로 넣을지(두 인스턴스 모두 동작하면 됨)
- `ProcessRunner`의 env 제거 방식
