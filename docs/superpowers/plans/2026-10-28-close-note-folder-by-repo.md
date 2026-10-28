# 종료 노트 레포 기준 폴더 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 종료 노트를 레포 카탈로그의 노트 폴더(없으면 Claude가 볼트 기존 폴더에서 고르거나 `HamonSoft/<별칭>`)에 쓰고, 정한 폴더를 카탈로그에 기록한다.

**Architecture:** API는 카탈로그 `note_folder` 칸(V36)을 관리하고 종료 claim의 `CloseContext`에 별칭·설명·노트 폴더를 싣는다. 워커의 새 `CloseNoteFolderResolver`가 폴더를 정하고, `CloseNoteWriter`는 노트 폴더와 진행상황 폴더를 따로 받는다. NOTE_WRITTEN 보고의 `noteFolder`를 API가 조건부 UPDATE로 카탈로그에 기록한다.

**Tech Stack:** Java 21 / Spring Boot / JPA / Flyway, JUnit5 + Mockito + AssertJ, Nuxt 3 + Quasar + vitest

**Spec:** `docs/superpowers/specs/2026-10-28-close-note-folder-by-repo-design.md`

## Global Constraints

- 코드·주석·테스트 이름·메시지는 한국어, 주변 코드 스타일을 따른다. 프론트는 작은따옴표·세미콜론 없음.
- 본 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)은 라이브 — 빌드·테스트는 이 worktree에서만.
- 노트 파일 이름·프런트매터·진행상황 줄 형식은 바꾸지 않는다.
- 진행상황 노트는 항상 `closeProps.noteFolder()`(CLOSE_NOTE_FOLDER)에.
- `close_note_path`는 볼트 기준 경로(`{노트 폴더}/{이름}.md`).
- 노트 폴더 값 규칙: 볼트 상대, `/` 구분(`\`는 `/`로 바꿔 받는다), 빈 단계·`.`·`..` 금지, 드라이브 문자·맨 앞 `/` 금지, 500자 이하, 앞뒤 공백 제거, 끝 `/` 제거.
- 카탈로그 수정 요청 `noteFolder`: null=유지, 공백만=비움, 그 밖=검증 후 저장. 등록은 null/공백 모두 비움.
- 후보 루트 = `CLOSE_NOTE_FOLDER`의 첫 단계, 후보 깊이 2, 점 폴더 제외. 폴더 고르기 claude 시간 상한 2분, 모델 `noteModel`.
- 별칭 → 폴더 이름: `\ / : * ? " < > | # ^ [ ]` → `-`, 앞뒤 공백·점 제거, 공백 유지, 비면 별칭 없음 취급.
- 백엔드 테스트: `cmd /c "cd /d <worktree> && .\gradlew.bat test --tests <클래스> --no-daemon"`(JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1). 이 PC 고질 실패 4건(심볼릭 링크 3·ProcessRunnerStreaming 1)은 회귀 아님.

## Review Focus

- 카탈로그 값이 Windows식 `HamonSoft\동부병원`으로 들어옴 → `/`로 바꿔 받아들인다 (Task 1 테스트).
- Claude 답이 백틱·따옴표·끝 `/`·`\`를 섞어 옴(`` `HamonSoft/동부병원/` ``) → 정규화 후 후보와 맞으면 그 폴더 (Task 3 테스트).
- 별칭이 `/`를 포함(`A/B 대시보드`) → 하위 폴더가 생기지 않고 `HamonSoft/A-B 대시보드` (Task 3 테스트).
- 수정 창이 noteFolder를 안 바꿨는데 저장 → 자동 기록값 유지 (Task 5 테스트 — 바디에 키 없음).
- 이미 기록된 카탈로그에 NOTE_WRITTEN이 noteFolder를 실어 옴 → 덮지 않음 (Task 2 테스트 — 조건부 UPDATE 0행).

---

### Task 1: 카탈로그 노트 폴더 칸 (API)

**Files:**
- Create: `src/main/resources/db/migration/V36__repo_catalog_note_folder.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/util/NoteFolderPath.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java`, `dto/RepoCatalogDto.java`, `service/RepoCatalogService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/util/NoteFolderPathTest.java`, `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceNoteFolderTest.java`

**Interfaces:**
- Produces: `NoteFolderPath.normalize(String raw) : String` — 정규화 값, 비면(null·공백) null, 규칙 위반이면 `IllegalArgumentException`(메시지 "노트 폴더는 볼트 안 상대 경로여야 합니다" / "노트 폴더가 너무 깁니다(500자)"). 워커와 API가 함께 쓴다.
- Produces: `RepoCatalogEntry.noteFolder` (`note_folder VARCHAR(500)`), `RepoCatalogDto.View.noteFolder`(마지막 칸), `UpsertRequest.noteFolder`(마지막 칸, `@Size(max=500)`; 기존 7·5인자 보조 생성자는 null 전달, 11인자 보조 생성자 추가로 기존 호출부 호환).

- [ ] **Step 1: 실패 테스트** — `NoteFolderPathTest`: `"  HamonSoft/동부병원/ "`→`"HamonSoft/동부병원"`, `"HamonSoft\\Netis-플랫폼공통\\netisweb_v6.8"`→`"HamonSoft/Netis-플랫폼공통/netisweb_v6.8"`, `null`·`"  "`→null, `"../x"`·`"a/../b"`·`"a//b"`·`"/a"`·`"C:/a"`·`"a/./b"`·`"x".repeat(501)` → IllegalArgumentException. `RepoCatalogServiceNoteFolderTest`(기존 `RepoCatalogServiceTest`의 mock 구성 따름): 등록 시 `"HamonSoft/동부병원"` 저장, 수정 null → 기존 값 유지, 수정 `"  "` → null, 수정 `"../x"` → `TaskException` 400.
- [ ] **Step 2: 실행해 실패 확인** — `--tests "*NoteFolderPathTest" --tests "*RepoCatalogServiceNoteFolderTest"` → 컴파일 실패.
- [ ] **Step 3: 구현** — V36: `ALTER TABLE com.repo_catalog ADD COLUMN note_folder VARCHAR(500);`. 서비스 `applyNoteFolder(e, req, boolean creating)`: creating이면 `normalize`, 수정이면 null=return / 공백=null / 그 밖 normalize; IllegalArgumentException → `badRequest(msg)`. `View.of` 두 곳에 `e.getNoteFolder()`.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "*RepoCatalogService*"` 전부 PASS.
- [ ] **Step 5: 커밋** — `feat(catalog): 레포별 종료 노트 폴더 칸(V36)`

### Task 2: 종료 claim 재료와 노트 폴더 기록 (API)

**Files:**
- Modify: `dto/CloseContext.java`, `dto/CloseProgressRequest.java`, `service/WorkerService.java`, `repository/RepoCatalogRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java`

**Interfaces:**
- Consumes: Task 1 `NoteFolderPath.normalize`, `RepoCatalogEntry.getNoteFolder()`.
- Produces: `CloseContext`에 마지막 세 칸 `String repoAlias, String repoDescription, String noteFolder` — 기존 8·12인자 생성자는 null 셋으로 위임. `CloseContext.of(t, plan, a, history, usage, taskUrl, RepoCatalogEntry catalog)` 추가(catalog null 허용, alias는 `t.getRepoAlias()`), 기존 `of` 6인자는 catalog=null로 위임.
- Produces: `CloseProgressRequest(workerId, step, mergeCommitSha, notePath, String noteFolder)` + 4인자 보조 생성자(noteFolder=null).
- Produces: `RepoCatalogRepository.recordNoteFolderIfEmpty(Long id, String folder) : int` — `@Modifying @Query("update RepoCatalogEntry e set e.noteFolder = :folder where e.id = :id and e.noteFolder is null")`.

- [ ] **Step 1: 실패 테스트** (WorkerServiceCloseTest) — ① 종료 claim: `catalogRepo.findById(9L)`가 noteFolder `"HamonSoft/동부병원"`·설명 `"동부병원 대시보드"`인 항목 → `r.closeContext().noteFolder()`·`repoDescription()` 일치, `repoAlias()`=task의 repo_alias. ② `repo_catalog_id` null + git_url로 찾힘 → 같은 값. ③ NOTE_WRITTEN에 noteFolder `"HamonSoft/라이나생명 대시보드 v6.8"` → `verify(catalogRepo).recordNoteFolderIfEmpty(9L, "HamonSoft/라이나생명 대시보드 v6.8")`. ④ noteFolder null → `recordNoteFolderIfEmpty` 호출 없음. ⑤ noteFolder `"../x"` → 예외 없음, 호출 없음, close_progress에 NOTE_WRITTEN 누적. ⑥ 카탈로그 없음 → 호출 없음.
- [ ] **Step 2: 실패 확인** — `--tests "*WorkerServiceCloseTest"`.
- [ ] **Step 3: 구현** — `closeContext(t)`가 `RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())`로 찾아 넘김. `recordCloseProgress` NOTE_WRITTEN 분기 끝에 `recordNoteFolder(t, req.noteFolder())`: 비면 return, normalize 실패 → `log.warn` 후 return, 카탈로그 없으면 return, `recordNoteFolderIfEmpty` 0행이면 debug 로그.
- [ ] **Step 4: 통과 확인** — `--tests "*WorkerServiceCloseTest" --tests "*CloseContextTest" --tests "*WorkerTaskResponseRepoTest"` PASS.
- [ ] **Step 5: 커밋** — `feat(close): 종료 claim에 노트 폴더 재료, NOTE_WRITTEN 때 카탈로그 기록`

### Task 3: 노트 폴더 결정기 (워커)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteFolderResolver.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteFolderResolverTest.java`

**Interfaces:**
- Consumes: `CloseContext.repoAlias/repoDescription/noteFolder`(Task 2), `NoteFolderPath.normalize`(Task 1), `ClaudeExecAdapter.execWithoutTools(String, File, Duration, String)`.
- Produces:
  - `record Resolved(String folder, boolean record, ClaudeExecAdapter.Usage usage)` — record=카탈로그에 기록할지(2·3단계만 true).
  - `static class ResolveFailed extends Exception` (메시지 = failure_reason 그대로, `usage()` 보유).
  - 생성자 `CloseNoteFolderResolver(ClaudeExecAdapter claude, CloseProperties closeProps)`.
  - `Resolved resolve(Path vault, WorkerTaskResponse task, Path workDir) throws ResolveFailed, InterruptedException`.
  - 패키지 공개 정적: `List<String> candidates(Path vault, String root)`, `String parseAnswer(String stdout, List<String> candidates)`(후보 하나 또는 null), `String aliasFolder(String root, String alias)`(null 가능), `String render(WorkerTaskResponse task, List<String> candidates)`(프롬프트).

- [ ] **Step 1: 실패 테스트** — 볼트 tempdir에 `HamonSoft/동부병원`, `HamonSoft/Netis-플랫폼공통/netisweb_v6.8`, `HamonSoft/Netis-플랫폼공통/netisweb_v6.8/깊은폴더`, `HamonSoft/.trash` 생성.
  - `candidates` = `["HamonSoft/Netis-플랫폼공통", "HamonSoft/Netis-플랫폼공통/netisweb_v6.8", "HamonSoft/동부병원"]`(정렬, 3단계·점 폴더 제외); 루트 없으면 빈 목록.
  - 카탈로그 값 `"HamonSoft/동부병원"` → `Resolved("HamonSoft/동부병원", false, null)`, claude 호출 없음.
  - 카탈로그 값 `"../x"` → ResolveFailed, 메시지 `"노트: 카탈로그 노트 폴더 값이 잘못됐습니다"`로 시작하고 `"레포 카탈로그에서 고친 뒤 재시도"` 포함.
  - claude 답 `` "`HamonSoft/동부병원/`\n" `` → `Resolved("HamonSoft/동부병원", true, usage)`.
  - 답 `"NONE"`, 별칭 `"라이나생명 대시보드 v6.8"` → `"HamonSoft/라이나생명 대시보드 v6.8"`, record=true.
  - 답 `"HamonSoft/없는폴더"` → 별칭 폴더, record=true.
  - 후보 없음(루트 없음) → claude 호출 없이 별칭 폴더.
  - 별칭 `"A/B: 대시보드."` → `"HamonSoft/A-B- 대시보드"`(앞뒤 공백·점 제거).
  - 별칭 null → `Resolved("HamonSoft/netisMaker", false, null)`, claude 호출 없음.
  - claude exit 1 → ResolveFailed, 메시지 `"노트 폴더 고르기 실패(exit=1)"`로 시작.
  - `render` 결과에 별칭·후보 각 줄·`NONE` 포함, Git URL의 `oauth2:tok@`는 들어가지 않음(호스트 제외 경로만).
- [ ] **Step 2: 실패 확인** — `--tests "*CloseNoteFolderResolverTest"`.
- [ ] **Step 3: 구현** — 루트 = `closeProps.noteFolder()`의 첫 `/` 앞. 후보는 `Files.list` 2단계, 디렉터리만(링크 따라가지 않음), 이름이 `.`으로 시작하면 제외. 레포 경로는 `task.repoRef().path()`. 프롬프트: 레포 별칭·경로·설명·작업 제목과 후보 목록을 주고 "후보 중 이 레포의 작업정리가 들어갈 폴더 하나를 그대로, 없으면 NONE만 출력. 고객사 레포를 이름의 버전 숫자만 보고 제품 공통 폴더로 고르지 말 것". 시간 상한 `Duration.ofMinutes(2)`. 답 정리: strip → 백틱·따옴표 제거 → `\`→`/` → 끝 `/` 제거 → 첫 줄.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: 커밋** — `feat(close): 종료 노트 폴더 결정기`

### Task 4: 노트 쓰기·종료 흐름 연결 (워커)

**Files:**
- Modify: `workerdaemon/CloseNoteWriter.java`, `workerdaemon/TaskCloser.java`
- Test: `CloseNoteWriterTest.java`, `TaskCloserTest.java`

**Interfaces:**
- Consumes: Task 3 `CloseNoteFolderResolver`, Task 2 `CloseProgressRequest` 5인자.
- Produces: `CloseNoteWriter.write(Path vault, String noteFolder, String progressFolder, NoteMeta meta, String body, LocalDate date)`; 기존 5인자 `write(vault, folder, …)`는 두 폴더 같게 위임.

- [ ] **Step 1: 실패 테스트** — CloseNoteWriterTest: 노트 폴더 `HamonSoft/동부병원`, 진행상황 `HamonSoft/netisMaker` → 노트 파일은 동부병원에, `netisMaker-진행상황-2026-10-13.md`는 netisMaker에, 줄은 `- [[…-작업7-2026-10-13]] — 작업 #7 종료(병합 m3rg3)`; `relativePath`=`HamonSoft/동부병원/…md`; 진행상황 폴더가 볼트 밖이면 IOException. TaskCloserTest: ① ctx noteFolder `HamonSoft/동부병원` → 노트가 그 폴더, NOTE_WRITTEN 보고 noteFolder null. ② ctx noteFolder null·별칭 `"동부병원 대시보드"`, 볼트에 `HamonSoft/동부병원`, claude 첫 호출(폴더) 답 `HamonSoft/동부병원`·둘째(노트) 본문 → 노트 위치·보고 noteFolder=`HamonSoft/동부병원`·종료 사용량은 두 호출 합. ③ 폴더 고르기 실패 → 종료실패, 노트 단계 보고 없음, 브랜치 안 지움. 기존 테스트의 기대 경로(`HamonSoft/netisMaker/…`)는 별칭 null인 ctx라 그대로 유지.
- [ ] **Step 2: 실패 확인** — `--tests "*CloseNoteWriterTest" --tests "*TaskCloserTest"`.
- [ ] **Step 3: 구현** — TaskCloser에 `CloseNoteFolderResolver` 필드(@Autowired 생성자는 `new CloseNoteFolderResolver(claude, closeProps)`, 테스트용 생성자도 같은 기본). `writeNote`: requireVault → `step("노트 폴더", …)`로 resolve(ResolveFailed는 그 메시지·usage로 StepFailed) → 기존 노트 생성 → `write(vault, resolved.folder(), closeProps.noteFolder(), …)` → `NoteOutcome`에 기록할 폴더 추가 → `progress(task, NOTE_WRITTEN, null, path, recordFolder)`. 사용량은 두 `Usage`를 더하는 `static UsageReport sum(UsageReport a, UsageReport b)`(null 허용). 폴더 고르기 작업 폴더는 노트와 같은 `close/task-{id}` 뒤 정리.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "*Close*"` PASS.
- [ ] **Step 5: 커밋** — `feat(close): 종료 노트를 레포 기준 폴더에 쓰고 진행상황은 한 곳에`

### Task 5: 관리 화면 노트 폴더 칸 (프론트)

**Files:**
- Modify: `frontend/pages/admin/repo-catalog.vue`
- Test: `frontend/test/repo-catalog-admin.spec.ts`

**Interfaces:**
- Consumes: API `View.noteFolder`, `UpsertRequest.noteFolder`(null=유지·""=비움).

- [ ] **Step 1: 실패 테스트** — ① 수정 창에서 노트 폴더 안 바꾸고 저장 → PUT 바디에 `noteFolder` 키 없음(또는 null). ② `HamonSoft/동부병원`으로 바꾸고 저장 → `noteFolder: 'HamonSoft/동부병원'`. ③ 기존 값을 지우고 저장 → `noteFolder: ''`. ④ 등록 창 입력 → POST 바디 `noteFolder` 값. ⑤ 목록(표·모바일 카드)에 값이 보인다(`노트 폴더` 표시). 활성 토글 바디에 `noteFolder` 없음.
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/repo-catalog-admin.spec.ts` (worktree `frontend/node_modules`는 라이브로의 정션 — 끝나면 링크만 지운다).
- [ ] **Step 3: 구현** — `RepoEntry.noteFolder: string | null`, `form.noteFolder`, 열 때 원래 값 `originalNoteFolder` 보관, 저장 바디: 등록이면 `form.noteFolder.trim() || null`, 수정이면 바뀌었을 때만 `form.noteFolder.trim()`(빈 문자열 포함). 입력칸 라벨 `노트 폴더 (선택)`, hint `옵시디언 볼트 안 경로(예: HamonSoft/동부병원). 비우면 첫 작업 종료 때 자동으로 정해 여기에 기록됩니다.` 표 열·카드 줄 `노트 폴더`(값 없으면 `자동`).
- [ ] **Step 4: 통과 확인** — 같은 명령 + `test/admin-catalog-cards-mobile.spec.ts` PASS, `npm run lint` 오류 0.
- [ ] **Step 5: 커밋** — `feat(catalog): 관리 화면 노트 폴더 칸`

### Task 6: 문서·설정 주석

**Files:**
- Modify: `CLAUDE.md`(종료 항목의 노트 폴더 문장 + 자주 보는 코드 표 한 줄), `src/main/resources/application-worker.yml`(note-folder 주석), `workerdaemon/CloseProperties.java`(주석), `docs/superpowers/specs/2026-10-13-task-close-phase-design.md`(§4 ③에 새 스펙 링크 한 줄), `scripts/install-autostart.ps1`은 변경 없음.

- [ ] **Step 1:** 위 문구 수정 — CLAUDE.md 종료 ③: "노트는 레포 카탈로그 `note_folder`(비면 Claude가 볼트 `HamonSoft/` 기존 폴더에서 고르거나 `HamonSoft/<별칭>`, 정한 값은 카탈로그에 기록)에, 진행상황은 `CLOSE_NOTE_FOLDER`에 — 스펙 `docs/superpowers/specs/2026-10-28-close-note-folder-by-repo-design.md`. 배포 순서: API(V36) → 워커 → 프론트".
- [ ] **Step 2:** 전체 백엔드 테스트 `.\gradlew.bat test --no-daemon` — 고질 4건 외 PASS.
- [ ] **Step 3: 커밋** — `docs: 종료 노트 폴더 규칙 반영`
