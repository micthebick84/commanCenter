# 종료 산출물 보관·내려받기·등록자 메일 설계

- 날짜: 2026-10-14
- 상태: 사용자 검토 대기 (brainstorming: 질의 1건 + 접근안 3건 → 설계 3절 각각 승인)
- 범위: netisMaker 백엔드(API·워커 데몬) + 프론트. 인터뷰 서비스 변경 없음
- 근거 문서: 기능 의도서(Claude Docs, 2026-10-14 — 목표·비목표·완료 기준), 가능성 검토 문서(같은 날, 드라이브·Google 인증 부분은 폐기)

## 1. 문제

작업 종료 단계(2026-10-13, PR #86)는 병합 → 배포 중지 → 노트 → 브랜치 정리로 끝나지만, 등록자에게 결과물을 건네는 단계가 없다.

- 산출물(WAR·JAR)은 원격 Docker 호스트(10.1.1.75)의 이미지 안에만 있다. 작업 10 `netis-task-10:a7f4ee9`의 `/usr/local/tomcat/webapps/ROOT.war`는 254MB, 작업 9 이미지는 `/app/netis.jar`.
- 등록자는 배포 주소로 동작만 확인할 수 있고 그 파일을 받을 화면이 없다. 지금은 관리자가 `docker cp`로 꺼내 손으로 전달해야 한다.
- commanCenter에는 메일 발송 기능이 없다. 등록자는 화면을 다시 열어야 종료를 안다.

## 2. 확정된 결정

| # | 항목 | 결정 |
|---|---|---|
| 1 | 산출물 기준 | 등록자가 확인한 **배포 이미지**에서 꺼낸다. 이미지는 PR head(`head_sha`) 빌드라 병합 커밋과 다를 수 있어 메일·노트에 두 커밋을 함께 적는다 |
| 2 | 보관 위치 | **Symphony 서버**(API 디스크). 로그인한 등록자 본인과 관리자만 내려받는다 |
| 3 | 메일 방식 | **앱 비밀번호 SMTP**(Gmail, `spring-boot-starter-mail`). 수신자는 **등록자만**(참조 없음) |
| 4 | 대상 | 카탈로그에서 켠 레포, WAR·JAR 모두 |
| 5 | 실패 처리 | 업로드 실패는 **종료실패**(재시도), 메일 실패는 **경고만**(작업종료 유지) |
| 6 | 업로드 영구 실패 | 관리자용 **'산출물 없이 마무리'** — 이때 메일은 보내지 않는다 |
| 7 | 보존 기간 | **작업 삭제 때까지.** 소프트 삭제 30일 뒤 기존 첨부 정리 잡이 함께 지운다 |
| 8 | 내려받기 | **60초 1회용 내려받기 주소**(접근안 A) |
| 9 | 기존 작업 호환 | 작업마다 **`close_artifact_mode`**로 필요한 단계를 정한다(접근안 A) |
| 10 | 메일 발송 | **작업종료 커밋 직후 비동기 1회** + 관리자 다시 보내기(접근안 A) |

기각한 대안과 이유:

- Google 공유 드라이브 업로드: 서비스 계정 키·공유 드라이브·조직 정책에 GCP 조직·Workspace 관리자 권한이 필요한데 작성자에게 없다(2026-10-14).
- 내려받기를 `?access_token=` JWT 쿼리로(8-B): 코드는 가장 적지만 JWT가 브라우저 기록·Cloudflare 로그에 남는다. 기존 첨부처럼 blob(8-C): 254MB가 브라우저 메모리에 올라가 모바일에서 위험하다.
- 워커가 산출물 단계를 항상 보고하고 꺼져 있으면 '건너뜀'(9-B): API와 워커를 반드시 같이 배포해야 한다.
- 발송 대기 테이블 + 재시도 잡(10-B): 메일 한 통 기능에 테이블·잡이 늘어난다. 실패는 관리자 다시 보내기로 충분하다.
- 메일 첨부: 254MB라 Gmail 한도(25MB)를 넘는다.

## 3. 종료 단계와 필요한 단계 규칙

`CloseStep`에 `ARTIFACT_UPLOADED`를 넣는다. 선언 순서가 실행 순서이고 `append`의 기록 순서다.

```
MERGED → UNDEPLOYED → ARTIFACT_UPLOADED → NOTE_WRITTEN → BRANCH_DELETED
```

배포 중지 뒤에 두는 이유: 배포 중지는 컨테이너만 지우고(`docker rm -f`) 이미지는 GC가 작업별 최신 1개를 남긴다(`DEPLOY_GC_KEEP_IMAGES=1`, 작업 10 실제 종료 뒤 남아 있음을 확인). 노트 앞에 두는 이유: 노트에 산출물 줄을 남긴다.

### `task.close_artifact_mode`

| 값 | 의미 |
|---|---|
| `null` | 해당 없음 — 기능이 꺼졌거나 레포가 켜지지 않음, 또는 이 기능 전에 승인된 작업. 지금의 4단계 종료 |
| `REQUIRED` | 산출물 단계를 거쳐야 작업종료 |
| `SKIPPED` | 관리자가 '산출물 없이 마무리'를 눌렀다. 산출물 단계 없이 작업종료 |

- **정하는 시점**: `TaskCloseService.approve`(종료승인대기 → 종료대기). API 설정 `app.close.artifact.enabled`(env `CLOSE_ARTIFACT_ENABLED`, 기본 `false`)가 켜져 있고, 작업의 카탈로그 항목(`task.repo_catalog_id`, 없으면 `git_url`로 `findByGitUrl`)의 `artifact_enabled`가 참이면 `REQUIRED`, 아니면 `null`. 재시도(`close/retry`)·stale 재큐는 값을 바꾸지 않는다.
- **작업종료 판정**: `CloseStep.allDone(csv)` → `CloseStep.allDone(csv, mode)`. 필요한 단계 = 기존 4개 + (`REQUIRED`이면 `ARTIFACT_UPLOADED`). `recordCloseResult`가 이것으로 `CLOSED`를 받는다.
- **claim**: `REQUIRED`이고 `ARTIFACT_UPLOADED`가 아직이면 `WorkerTaskResponse.closeArtifact = {path}`를 싣는다(`path` = 카탈로그 `artifact_path`, 비면 `null`). 그 밖에는 `closeArtifact = null`이고 워커는 산출물 단계를 건너뛴다.

### 산출물 없이 마무리

`POST /api/tasks/{id}/close/skip-artifact` (ADMIN, 바디 `{reason}` 선택)

- 허용 조건: 상태 `종료실패` + 모드 `REQUIRED` + 진행에 `MERGED`·`UNDEPLOYED`가 있고 `ARTIFACT_UPLOADED`가 없음. 아니면 409.
- 효과: 모드 `SKIPPED`, `failure_reason` 비움, `종료실패 → 종료대기`(재시도와 같은 큐 진입), 상태 이력 "산출물 없이 마무리" + 사유. 행 잠금은 다른 종료 전이와 같이 `findActiveByIdForUpdate`.
- 재claim한 워커는 `closeArtifact = null`이라 노트·브랜치 정리만 한다. `closeContext.artifactSkipped = true`이면 노트 프런트매터에 `artifact: 없음(관리자 건너뜀)`을 쓴다.
- 메일은 보내지 않는다(§7).

## 4. 워커: 산출물 꺼내기와 업로드

새 클래스 `ArtifactPublisher`(workerdaemon). `TaskCloser.process`가 `UNDEPLOYED` 뒤, 노트 앞에서 `closeArtifact != null && !done.contains(ARTIFACT_UPLOADED)`일 때 부른다. 실패는 기존처럼 `StepFailed` → 종료실패이고 뒤 단계는 하지 않는다.

1. **이미 올렸는지**: `GET /worker/tasks/{id}/artifact`가 200이면 꺼내지 않고 6(진행 보고)으로 간다. 업로드 뒤 진행 보고 전에 죽은 재시도용.
2. **이미지 이름**: `netis-task-{id}:{head_sha 앞 7자}`. `DeployService`의 계산(`headSha == null`이면 `latest`)을 정적 메서드 `DeployService.imageName(taskId, headSha)`로 뽑아 배포와 함께 쓴다. `docker image inspect`가 실패하면 "산출물: 이미지 없음({이미지}) — 재시도하거나 '산출물 없이 마무리'"로 실패.
3. **경로 정하기**:
   - `closeArtifact.path`가 있으면 그대로 쓴다.
   - 없으면 `docker run --rm --entrypoint find <이미지> <후보 폴더…> -maxdepth 2 -type f ( -name *.war -o -name *.jar )`. 후보 폴더는 워커 설정 `CLOSE_ARTIFACT_SEARCH_DIRS`(기본 `/usr/local/tomcat/webapps,/app`). 없는 폴더의 오류 출력과 종료 코드는 무시하고 stdout의 경로만 본다.
   - 정확히 1개면 그 경로. 0개·2개 이상이거나 이미지에 `find`가 없으면 "산출물: 경로를 정할 수 없습니다 — 레포 카탈로그에 이미지 안 산출물 경로를 지정하세요(후보: …)"로 실패.
4. **꺼내기**: 작업 폴더 `~/netis-maker/close/task-{id}/artifact/`(노트 단계와 같은 상위).
   - 시작 전 `docker rm -f netis-artifact-{id}`(남은 것 정리, 없으면 무시).
   - `docker create --name netis-artifact-{id} --entrypoint true <이미지>` — 실행하지 않으므로 명령 없는 이미지도 만들어진다.
   - `docker cp netis-artifact-{id}:<경로> <작업 폴더>/<파일 이름>`.
   - finally에서 `docker rm -f netis-artifact-{id}`. 원격 `DOCKER_HOST`를 그대로 물려받으므로 파일은 원격에서 워커 PC로 복사된다.
   - 명령은 기존 `ProcessRunner`로 실행한다(테스트 seam). 타임아웃은 `CLOSE_ARTIFACT_COPY_TIMEOUT`(기본 10분).
5. **파일 이름과 업로드**:
   - 이름: `{레포 이름}-task-{id}-{sha7}.{war|jar}`. 레포 이름 = 저장소 경로의 마지막 조각(GitLab 전체 경로 포함), `[A-Za-z0-9._-]` 밖의 글자는 `_`. 확장자는 원본 파일의 것.
   - 워커가 sha256을 계산하고 `PUT /worker/tasks/{id}/artifact?workerId={워커 id}&name={이름}&source={이미지}:{경로}`로 보낸다. 헤더 `X-Artifact-Sha256`, 본문 `application/octet-stream`. RestClient가 본문을 버퍼링하지 않도록 JDK `HttpClient` + `BodyPublishers.ofFile`로 스트리밍한다(`X-Worker-API-Key` 동일). 타임아웃 `CLOSE_ARTIFACT_UPLOAD_TIMEOUT`(기본 10분).
   - 작업 폴더는 finally에서 지운다(실패는 경고).
6. **진행 보고**: `close-progress ARTIFACT_UPLOADED`. API가 `artifact_name`을 이미 기록했으므로 요청에 추가 필드는 없다.

`WorkerTaskResponse`에 `closeArtifact`(record `CloseArtifactSpec(String path)`), `CloseContext`에 `artifactName`·`artifactSizeBytes`·`artifactSkipped`·`taskUrl`을 더한다. 노트(`CloseNoteWriter.NoteMeta`)는 산출물이 있으면 프런트매터 `artifact: {이름}`과 본문 끝 한 줄 "산출물: {이름}({MB}) — Symphony 작업 화면에서 로그인 후 내려받기: {taskUrl}"을 쓴다. 이번 실행에서 막 올린 경우는 워커가 업로드 응답의 값을 쓴다. `taskUrl`은 API가 `APP_PUBLIC_BASE_URL`로 만들고, 설정이 비면 null이라 노트 줄에서 링크를 뺀다.

## 5. API: 저장

### 워커 엔드포인트 (`X-Worker-API-Key`)

`PUT /worker/tasks/{id}/artifact?workerId=&name=&source=`

- 받는 조건: 상태 `종료중`, `workerId`가 작업의 `worker_id`와 같음, 모드 `REQUIRED`. 아니면 409. `name`은 `[A-Za-z0-9._-]{1,200}`이고 `.war`·`.jar`로 끝나야 한다(아니면 400). `source`는 500자 이하. 헤더 `X-Artifact-Sha256`는 64자 16진수.
- 저장: `{app.attachment.dir}/task-{id}/artifact/{name}.part`에 요청 본문을 스트리밍으로 쓰면서 sha256과 바이트 수를 센다. 상한 `app.close.artifact.max-size-mb`(env `CLOSE_ARTIFACT_MAX_SIZE_MB`, 기본 1024)를 넘으면 쓰기를 멈추고 `.part`를 지운 뒤 413.
- 해시가 헤더와 다르면 `.part`를 지우고 400. 같으면 `{name}`으로 원자적 이동(`ATOMIC_MOVE`, 실패 시 `REPLACE_EXISTING`)하고, `artifact/` 폴더의 다른 파일은 지운다(작업당 하나).
- 같은 이름·같은 해시가 이미 기록돼 있고 파일이 있으면 본문을 버리고 200(멱등).
- 확정 뒤 작업에 기록: `artifact_name`, `artifact_size`, `artifact_sha256`, `artifact_source`(쿼리 `source`), `artifact_uploaded_at`. 응답 `{name, sizeBytes, sha256}`.
- 저장 경로 규칙은 `AttachmentStorage`에 `artifactDir(taskId)`로 둔다. 소프트 삭제 30일 뒤 `AttachmentCleanupJob`이 `task-{id}` 폴더째 지우므로 새 정리 코드는 없다.

`GET /worker/tasks/{id}/artifact` → 기록과 파일이 모두 있으면 200 `{name, sizeBytes, sha256}`, 아니면 404.

`close-progress ARTIFACT_UPLOADED`는 `artifact_name`이 기록돼 있어야 받는다(아니면 400).

### `PUT` 본문 크기

워커 경로는 multipart가 아니라 원시 본문이므로 `spring.servlet.multipart` 상한(25MB)과 무관하다. 컨트롤러는 `HttpServletRequest.getInputStream()`을 직접 읽는다.

## 6. API: 내려받기

`POST /api/tasks/{id}/artifact/download-link` (JWT)

- 권한: 등록자 본인(`task.isOwnedBy(username)`) 또는 ADMIN. 아니면 403.
- 기록이 없으면 404, 기록은 있는데 파일이 없으면(정리됨) 410 "보관 파일이 정리되었습니다".
- 응답 `{url: "/api/artifact-downloads/{token}", expiresInSec: 60}`. 토큰은 `SecureRandom` 32바이트 base64url.
- 새 컴포넌트 `ArtifactDownloadTokens`: `ConcurrentHashMap<token, {taskId, expiresAt}>`. 발급 때 만료된 항목을 함께 치운다. API 재기동 시 사라진다(버튼을 다시 누르면 된다).

`GET /api/artifact-downloads/{token}` (인증 없음)

- `SecurityConfig.apiFilterChain`에 `.requestMatchers(HttpMethod.GET, "/api/artifact-downloads/*").permitAll()`을 `/api/**` 앞에 둔다.
- 토큰을 맵에서 꺼내며(1회용) 지운다. 없거나 만료면 404.
- `FileSystemResource` 스트리밍, `Content-Type: application/octet-stream`, `Content-Length`, `Content-Disposition: attachment; filename*=UTF-8''{이름}`, `Cache-Control: no-store`.

프론트는 `/api` 상대 경로 관례(Nitro 프록시, `useInterviewStream`과 같음)를 따라 `window.location.assign(url)`로 이동한다.

## 7. 메일

### 수신 주소

- 작업 등록(`TaskService.create`)에서 JWT `email` 클레임을 `task.requester_email`에 저장한다. 컨트롤러가 username과 함께 넘긴다.
- 종료 요청(`close-request`)을 등록자 본인이 하면 그때의 JWT `email`로 갱신한다(이 기능 전에 등록된 작업과 주소 변경 대응).
- 발송 때 둘 다 비어 있으면 `JdbcTemplate`으로 `SELECT email FROM com."user" WHERE user_id = ?`(등록자 username). 그래도 없으면 `NO_ADDRESS`.

### 발송

- `WorkerService.recordCloseResult`가 `CLOSED`로 전이할 때 `CloseCompletedEvent(taskId)`를 발행한다.
- `CloseMailListener`: `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("closeMailExecutor")`(단일 스레드 `ThreadPoolTaskExecutor`, 새 설정 클래스에 `@EnableAsync`). 현재 코드베이스에 `@Async`는 없다.
- `CloseMailService.send(taskId)`: 모드 `REQUIRED`이고 `artifact_name`이 있을 때만 보낸다. `SKIPPED`·`null`이면 아무것도 하지 않는다(상태도 남기지 않음).
- 결과는 새 트랜잭션으로 `close_mail_status`·`close_mail_at`에 남긴다.

| `close_mail_status` | 뜻 | 상태 이력 |
|---|---|---|
| `SENT` | 보냄 | "메일 발송: {수신 주소}" |
| `FAILED` | SMTP 오류 | "메일 실패: {마스킹한 오류 일부}" |
| `NO_ADDRESS` | 수신 주소 없음 | "메일 건너뜀: 수신 주소 없음" |
| `DISABLED` | `MAIL_USERNAME` 미설정 | "메일 건너뜀: 메일 설정 없음" |

상태 이력은 `from = to = 작업종료`, actor `system`(기존 "삭제" 이력과 같은 모양).

### 설정 (API `public.env`)

`MAIL_HOST`(기본 `smtp.gmail.com`), `MAIL_PORT`(기본 587, STARTTLS), `MAIL_USERNAME`, `MAIL_PASSWORD`(앱 비밀번호), `MAIL_FROM`(비면 `MAIL_USERNAME`), `APP_PUBLIC_BASE_URL`(예: `https://app-win.micthebick.dev`). `spring.mail.*`로 바인딩하고 연결·읽기 타임아웃 10초. `MAIL_PASSWORD`는 로그·이력에 나가지 않게 기존 마스킹을 거친다.

### 내용 (text/plain, UTF-8)

- 제목: `[Symphony] 작업 {id} 종료 — {작업 제목}` — 제목의 CR·LF는 공백으로, 전체 100자에서 자른다.
- 본문:

```
작업 {id} "{작업 제목}"이 종료되었습니다.

레포: {레포 표시명}
산출물: {파일 이름} ({MB, 소수 1자리} MB)
배포 커밋: {head_sha 7자}   병합 커밋: {merge_commit_sha 7자}

로그인 후 내려받기: {APP_PUBLIC_BASE_URL}/tasks/{id}

작업을 삭제하면 30일 뒤 파일도 지워집니다.
```

### 다시 보내기

`POST /api/tasks/{id}/close/mail/resend` (ADMIN) — 상태 `작업종료` + 모드 `REQUIRED` + 산출물 기록이 있어야 한다(아니면 409). 동기로 `CloseMailService.send`를 부르고 결과 상태를 응답한다.

## 8. DB `V27__close_artifact.sql`

`com.task`:

| 컬럼 | 타입 |
|---|---|
| `requester_email` | `VARCHAR(320)` |
| `close_artifact_mode` | `VARCHAR(10)` |
| `artifact_name` | `VARCHAR(255)` |
| `artifact_size` | `BIGINT` |
| `artifact_sha256` | `VARCHAR(64)` |
| `artifact_source` | `VARCHAR(500)` |
| `artifact_uploaded_at` | `TIMESTAMPTZ` |
| `close_mail_status` | `VARCHAR(20)` |
| `close_mail_at` | `TIMESTAMPTZ` |

`com.repo_catalog`: `artifact_enabled BOOLEAN NOT NULL DEFAULT FALSE`, `artifact_path VARCHAR(500)`.

모두 nullable 또는 기본값이 있어 기존 행은 그대로다.

`TaskResponse.close`(기존 `progress`·`mergeCommitSha`·`notePath` 묶음)에 `artifactMode`, `artifact {name, sizeBytes, sha256, uploadedAt}`(없으면 null), `mail {status, at}`(없으면 null)을 더한다. 수신 주소는 응답에 넣지 않는다.

## 9. 프론트

- `composables/taskClose.ts`: `CLOSE_STEPS`에 `{key: 'ARTIFACT_UPLOADED', label: '산출물 업로드'}`를 배포 중지와 노트 사이에 넣는다. `closeSteps`는 `close.artifactMode`가 `null`이면 이 단계를 빼고, `SKIPPED`면 상태 `'skipped'`('건너뜀')로 보여 준다. `CloseView` 타입에 새 필드.
- 작업 상세 종료 카드(`pages/tasks/[id].vue`):
  - 산출물 줄: 파일 이름, 크기(MB), **내려받기** 버튼(등록자 본인·관리자). 누르면 `download-link` POST → `window.location.assign(url)`. 410이면 "보관 파일이 정리되었습니다" 알림.
  - 메일 상태 칩: 보냄 / 실패 / 주소 없음 / 설정 없음(+ 시각).
  - 관리자 **메일 다시 보내기**: 상태가 `FAILED`·`NO_ADDRESS`·`DISABLED`일 때.
  - 관리자 **산출물 없이 마무리**: §3 허용 조건일 때. `confirmDialog($q)` 문구 "산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다." + 사유 입력(선택).
- 모바일 상세(`components/tasks/TaskDetailMobile.vue`)와 액션 시트에 같은 버튼.
- 관리 > 레포 카탈로그(`pages/admin/repo-catalog.vue`, `AdminFormDialog`): "종료 때 산출물 보관" 토글 + "이미지 안 산출물 경로(비우면 자동)" 입력. `RepoCatalogDto`·`RepoCatalogService` 업서트에 두 필드. 목록 카드에 켜짐 표시.
- `scripts/hydration-smoke.mjs`의 `apiResponse` 목에 새 필드를 넣는다.

## 10. 오류 처리

| 상황 | 처리 | 작업 상태 |
|---|---|---|
| 이미지 없음 | "산출물: 이미지 없음 …" | 종료실패 → 재시도 또는 산출물 없이 마무리 |
| 경로를 정할 수 없음(0개·2개 이상·`find` 없음) | 후보 목록과 함께 카탈로그 지정 안내 | 종료실패 → 경로 지정 후 재시도 |
| `docker cp` 실패·타임아웃 | 사유 기록, 임시 컨테이너 제거 | 종료실패 → 재시도 |
| 업로드 실패(네트워크·409·413·해시 불일치) | 사유 기록 | 종료실패 → 재시도(이미 올라갔으면 꺼내지 않음) |
| 메일 실패·주소 없음·설정 없음 | `close_mail_status` + 이력 | 작업종료 유지 → 관리자 다시 보내기 |
| 내려받기 시 파일 정리됨 | 410 | — |

- 워커의 실패 사유는 기존처럼 `GitRemotes.mask`를 거친다. 병합 뒤 단계이므로 '종료 취소'(409)는 여전히 안 되고, 재시도나 산출물 없이 마무리로 끝낸다.
- 원격 워커가 Cloudflare 터널 주소로 API를 부르면 요청 본문 100MB 제한에 걸린다. 지금 Windows 스택 워커는 `http://localhost:8090`이라 해당 없다. 맥 스택 등에서 켤 때 확인할 것.

## 11. 테스트

- **API 단위**: `CloseStep.allDone(csv, mode)`(null·REQUIRED·SKIPPED), 승인 때 모드 결정(전역 스위치 × 카탈로그 × 카탈로그 없음), `skip-artifact` 허용 조건·전이·이력, 업로드(상태·소유·모드 가드, 이름 검증, 해시 불일치 400, 상한 413, 같은 파일 멱등, 다른 파일 교체), `ARTIFACT_UPLOADED` 진행 가드, `download-link` 권한(등록자·타인·관리자)과 404·410, 토큰 1회 사용·만료, 메일(커밋 뒤에만, 네 상태, `SKIPPED`·`null`은 미발송, 주소 대체 조회, 제목 줄바꿈 제거, 다시 보내기 가드). 메일은 가짜 `JavaMailSender`.
- **워커 단위**: `ArtifactPublisher` 명령 조립(`ProcessRunner` 목 — `rm -f`·`create --entrypoint true`·`cp`·finally `rm -f`), `find` 출력 판정(1개·0개·2개·오류 섞임), 이미 업로드된 경우 꺼내지 않음, `closeArtifact == null`이면 단계 건너뜀, 파일 이름 정규화, `TaskCloser` 단계 순서·실패 시 노트 미실행.
- **통합**: `TaskCloseFlowIntegrationTest`에 승인(모드 REQUIRED) → claim의 `closeArtifact` → 업로드 → 진행 → 작업종료 → 메일 이벤트, 그리고 산출물 없이 마무리 경로.
- **프론트 vitest**: `closeSteps` 모드별, 역할·조건별 버튼 노출(내려받기·다시 보내기·산출물 없이 마무리), 내려받기 호출과 410 처리, 카탈로그 폼 두 필드.

## 12. 배포 순서

1. API(V27). 기능은 꺼진 상태(`CLOSE_ARTIFACT_ENABLED` 미설정)라 모드가 계속 `null` — 구버전 워커는 `closeArtifact`를 무시해도 영향이 없다.
2. 워커.
3. 프론트 `.output` 재빌드.
4. API `public.env`에 메일 설정(`MAIL_*`, `APP_PUBLIC_BASE_URL`)과 `CLOSE_ARTIFACT_ENABLED=true` → API 재기동.
5. 레포 카탈로그에서 레포 하나를 켜고 실제 작업 종료로 기능 의도서의 완료 기준을 확인한다.

사전 준비(코드 밖): 발신 계정 2단계 인증·앱 비밀번호, netis-auth 사용자에 실제 회사 메일 주소(지금 admin·user 모두 `example.com` 시드), API 서버에서 `smtp.gmail.com:587` 나가는지.
