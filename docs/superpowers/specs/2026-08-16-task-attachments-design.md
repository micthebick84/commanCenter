# 작업 첨부파일 (Task Attachments) 설계

- 날짜: 2026-08-16
- 상태: 사용자 승인됨 (brainstorming 인터랙티브 세션)
- 범위: netisMaker 백엔드(Java) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt)

## 1. 배경과 목표

작업 등록은 현재 4필드(`repoCatalogId`, `githubBranch`, `title`, `description`)뿐이라, 요청자가
요구사항 문서·화면 시안·데이터 샘플 같은 참고 자료를 전달할 방법이 없다. 인터뷰(brainstorming)
에이전트는 레포 코드와 description 텍스트만 보고 질문을 시작한다.

**목표**: 등록 시 파일을 첨부하고, 관리자 승인으로 시작되는 분석(인터뷰) 단계에서 에이전트가
그 파일들을 직접 읽어 brainstorming 품질을 올린다. 관리자·요청자는 UI에서 첨부를 내려받아
확인할 수 있다.

## 2. 확정된 범위 (v1)

| 결정 | 내용 |
|---|---|
| 파일 종류 | 제한 없음 — 실행파일류 확장자만 차단 |
| 첨부 시점 | **등록 시점만**. 승인 후·인터뷰 도중 추가/삭제 없음 |
| 참조 주체 | 분석(인터뷰) 에이전트 + 사람(요청자 본인·관리자) 다운로드 |
| 저장 방식 | **파일시스템 저장 + DB 메타데이터**, 에이전트에는 claim으로 절대경로 전달 |
| 오피스 문서 | v1은 원본 저장만. 서버 측 텍스트 추출(Tika 등)은 후속 확장 |

**v1 제외 (명시적 비범위)**
- 구현/디자인 워커에 첨부 전달 — 파일이 공유 FS에 있으므로 필요 시 해당 claim 계약에
  `AttachmentRef`만 추가하면 됨. 이 설계는 그 확장을 막지 않는다.
- 오피스 문서(docx/xlsx/pptx/hwp) 텍스트 추출 — 에이전트는 못 읽고, 다운로드용으로만 저장.
  프롬프트에 한계를 명시하고 질문 폴백.
- 첨부 자동 정리(GC) — 기존 `~/netis-maker/interviews` 미정리 정책과 동일. `TODOS.md`의
  정리 항목에 첨부 디렉터리를 한 줄 추가만 한다.
- 목록 페이지·ApproveDialog 변경 — 관리자는 승인 전 작업 상세 페이지에서 첨부를 확인한다.

## 3. 아키텍처 요점

단일 호스트 + 공유 파일시스템 전제를 그대로 쓴다. 이 전제는 이미
`InterviewService.deriveWorkDir`(백엔드가 `~/netis-maker/interviews/...` 절대경로를 만들어
claim으로 워커에 전달, "단일 호스트/공유 FS 전제" 주석)에 명시된 기존 전제이며, 첨부는 새
전제를 추가하지 않는다.

```
등록(multipart) ──▶ 디스크 {attachment.dir}/task-{id}/ + com.task_attachment 메타 (한 트랜잭션)
승인 ──▶ InterviewService.claim 이 첨부 목록을 절대경로로 변환해 claim에 포함
워커 ──▶ kickoff 프롬프트에 경로 나열 → 에이전트가 Read 도구로 직접 읽음 (복사 0회)
사람 ──▶ GET /api/tasks/{id}/attachments/{attId} 스트리밍 다운로드 (ACL)
```

에이전트의 Read/Glob/Grep은 현재도 경로 무제한(pre-approved, `permissions.ts` fallthrough
allow)이므로 **권한 변경이 필요 없다**. 첨부 기능이 새 권한을 열지 않는다.

## 4. 데이터 모델 — `V19__task_attachment.sql`

```sql
CREATE TABLE IF NOT EXISTS com.task_attachment (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    stored_path       VARCHAR(500) NOT NULL,   -- 루트 기준 상대경로: task-{taskId}/{ordinal}-{sanitized}
    content_type      VARCHAR(100),
    size_bytes        BIGINT NOT NULL,
    uploaded_by       VARCHAR(20) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_attachment_task ON com.task_attachment(task_id);
```

- FK CASCADE는 V17(`task_design`)과 동일 패턴. task는 soft-delete(`deleted_at`)라 실제 행 삭제는
  드물다.
- `stored_path`의 `{ordinal}`은 **업로드 순번(1..N)** — attachment 자신의 DB id가 아니다.
  전 엔티티가 IDENTITY 전략이라 INSERT 전에 id를 알 수 없는데 `stored_path`는 NOT NULL이므로,
  id 기반 파일명은 순환 의존이 된다(placeholder INSERT + UPDATE 우회 금지). 첨부는 등록 시점
  단일 트랜잭션에서만 생성되고 추가/삭제가 없으므로(§2) 순번은 영구히 충돌 없음. 향후 "승인 전
  추가"류 확장 시에는 UUID 파일명으로 전환할 것.
- `stored_path`는 **상대경로**만 저장 — 루트(`app.attachment.dir`) 이동에 견딘다. work_dir이
  절대경로인 것은 "재사용해야 하는 세션 위치"라서이고, 첨부는 그 요구가 없어 상대가 맞다.
- `content_type`은 클라이언트 신고값(신뢰하지 않음, 표시·프롬프트 참고용).
- 신규: `TaskAttachment` 엔티티, `TaskAttachmentRepository`.

## 5. API 변경

### 5.1 `POST /api/tasks` — multipart 매핑 신설 (기존 매핑 무변경)

- 기존 매핑은 **consumes 미지정 상태 그대로 유지**(consumes를 새로 달지 말 것 — absent/특이
  Content-Type 요청의 기존 동작이 바뀐다). multipart 요청은 더 구체적인 신규 매핑으로
  라우팅되므로 하위호환(파일 없는 JSON 등록, 구버전 프론트)이 유지된다.
- 신규 `consumes: multipart/form-data` 매핑:
  `@RequestPart("meta") @Valid TaskCreateRequest` + `@RequestPart(value = "files", required = false) List<MultipartFile>`.
- 작업 생성 + 메타 행 + 디스크 쓰기가 **한 트랜잭션** — 실패 시 롤백 + 쓰다 만 파일
  best-effort 삭제. 부분 상태(파일 없는 메타, 메타 없는 파일, 첨부 실패한 작업) 없음.

### 5.2 `GET /api/tasks/{id}` — `TaskResponse` 확장

`attachments: [{id, fileName, contentType, sizeBytes, createdAt}]` 추가. 상세 페이지가 5초
폴링으로 이미 이 응답을 쓰므로 별도 조회 불필요.

**계약 고정**: `attachments`는 **항상 non-null** (mcpsExtra 관례) — `TaskResponse`는 record라
필드 생략이 불가하므로 모든 `of()` 오버로드가 기본값 `List.of()`를 채운다. **목록 엔드포인트는
항상 `[]`** (첨부를 조회하지 않음 — N+1 회피, 목록 UI는 이 필드를 쓰지 않음). 실데이터는 상세
응답에만 실린다.

### 5.3 `GET /api/tasks/{id}/attachments/{attId}` — 다운로드 신설

- ACL: **요청자 본인 또는 ROLE_ADMIN**. 위반 403, 메타 없음/파일 유실 404.
- `Content-Disposition: attachment` + RFC 5987 `filename*`(한글 파일명), 스트리밍(Resource).
- 경로 resolve 결과가 첨부 루트 하위인지 재확인(디렉터리 탈출 이중 방어).

### 5.4 `POST /worker/interviews/claim` — 계약 확장 ⚠️ 양측 동시 수정

`InterviewClaimResponse`(Java record)와 `netismaker-interview-service/src/types.ts`에 동시 추가:

```java
List<AttachmentRef> attachments   // 항상 non-null, 없으면 [] (mcpsExtra와 동일 규약)
record AttachmentRef(long id, String fileName, String absolutePath,
                     String contentType, long sizeBytes) {}
```

- `absolutePath` = `app.attachment.dir` + `stored_path` (백엔드가 조립 — work_dir과 동일하게
  단일 호스트 전제 하의 절대경로 전달).
- **조립 위치 고정**: `InterviewClaimResponse.of`의 시그니처를
  `of(s, turns, List<AttachmentRef> attachments)`로 확장하고, record **compact constructor에서
  `null → List.of()` 정규화**(어느 경로로 생성돼도 non-null 계약 유지). 조회는
  `InterviewService.claim`에서 세션의 `task_id`로 `TaskAttachmentRepository.findByTaskId` —
  `InterviewService`에 `TaskAttachmentRepository`와 `app.attachment.dir` 설정 주입을 추가한다.
  기존 `InterviewClaimResponseTest`의 `of` 호출부 컴파일 수정은 예상된 작업.
- `contentType`은 DB에서 nullable이므로 record에서도 null 가능 — TS 타입은
  `contentType: string | null`(§7 프롬프트 렌더링 폴백 참조).
- 이 프로젝트에서 반복된 결함 지점이 **교차 컴포넌트 계약**(P2~P4 리뷰·스모크 이력)이므로
  §9의 계약 테스트를 양쪽에 필수로 둔다.

## 6. 백엔드 흐름과 설정

### 6.1 업로드 검증 (컨트롤러/서비스 초입, 순서대로)

1. 파일 개수 ≤ `max-files`(기본 10)
2. 파일당 크기 ≤ `max-file-size-mb`(기본 20MB), 합계 ≤ `max-total-size-mb`(기본 50MB)
3. 0바이트 파일 거부
4. 차단 확장자(대소문자 무시): `exe dll so dylib bat cmd sh ps1 msi scr com pif vbs app`
5. 파일명 sanitize: 경로 구분자(`/`,`\`)·`..` 시퀀스·제어문자(0x00–0x1F) 제거, 앞뒤 공백
   trim, 200자 초과 절단, 결과가 비면 `file`. 한글은 보존.

실패는 400 + 한국어 메시지, 작업은 생성되지 않는다.

**예외 핸들러 보강 (필수)**: 현재 `GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를
상속하지 않아 `MaxUploadSizeExceededException`(스프링 multipart 한도 초과)과
`MissingServletRequestPartException`(meta part 누락)이 스프링 기본 에러로 샌다 — 25MB 초과
파일은 앱 검증에 도달하기 전에 여기 걸린다. 두 예외의 `@ExceptionHandler`를 추가해 프로젝트
표준 `{error, message}` 한국어 응답(각각 413, 400)으로 변환한다.

### 6.2 저장

- 신규 컴포넌트 `AttachmentStorage`(검증·쓰기·resolve·삭제 담당, 단위 테스트 대상)로
  `TaskService` 비대화를 피한다.
- `TaskService.create` 확장: 기존 로직(동시 한도 → 레포 resolve → task 저장 → history) 뒤에
  attachment 행 저장 → 디스크 쓰기. 경로는 `task-{taskId}/{ordinal}-{sanitized}`(§4 —
  taskId는 task 저장 직후 확보, ordinal은 업로드 순번이라 INSERT 전에 확정 가능).
- 예외 시: 트랜잭션 롤백 + 이미 쓴 파일 best-effort 삭제(try/catch, 실패는 로그만).

### 6.3 설정 (`application.yml`)

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 25MB      # 앱 검증(20MB)이 먼저 걸리도록 여유
      max-request-size: 60MB   # 앱 합계(50MB) + meta 여유
app:
  attachment:
    dir: ${user.home}/netis-maker/attachments
    max-files: 10
    max-file-size-mb: 20
    max-total-size-mb: 50
```

Cloudflare 터널 업로드 상한(플랜 기본 100MB) 안쪽. Nuxt(Nitro) 프록시에는 바디 제한 설정이
없으나 공개 터널 경유 업로드는 §9 수동 스모크로 확인한다.

## 7. 인터뷰 워커 전달 (netismaker-interview-service)

- `types.ts`: `AttachmentRef` 추가(Java record와 필드 1:1), `InterviewClaimResponse.attachments:
  AttachmentRef[]`.
- `interviewRunner.ts` `promptFor` — **fresh start(claudeSessionId 없음)일 때만**, 첨부가 1건
  이상이면 kickoff 프롬프트에 섹션 추가:

```
첨부 자료 (요청자가 등록 시 업로드한 파일):
- {absolutePath} ({contentType}, {size})
- ...

brainstorming 시작 전에 이 파일들을 Read 도구로 읽고 요구사항 파악에 활용하세요.
읽을 수 없는 포맷(docx/xlsx 등 오피스 문서)이거나 파일이 없으면 건너뛰고,
필요한 내용은 사용자에게 질문으로 확인하세요.
```

- resume 턴에는 추가하지 않는다(세션이 이미 읽은 상태).
- `contentType`이 null이면 괄호 안에서 생략(리터럴 `null`을 렌더링하지 말 것).
- 읽기는 **무조건 가드**: `claim.attachments ?? []` + `Array.isArray` 방어(mcpsExtra를 읽는
  `sessionOptions.ts`의 기존 선례와 동일). 필드가 없는 구버전 claim에서도 크래시 없이 섹션만
  생략된다 — 이 가드는 §11의 롤아웃 순서 권고와 무관하게 필수.
- 파일이 중간에 유실돼도 Read 실패 → 프롬프트 지침대로 질문 폴백 — 인터뷰가 죽지 않는다.
- 워커 측 다운로드/복사/권한 변경 **없음**.

## 8. 프론트엔드 (frontend/)

### 8.1 등록 다이얼로그 (`pages/tasks/index.vue`)

- 설명란 아래 `q-file`(multiple, `use-chips`, counter, 개별 제거). `draft`에 `files: File[]`.
- 클라이언트 사전 검증: 서버와 같은 한도(10개/20MB/50MB) — 초과 시 notify로 부드러운 에러,
  전송 차단. (차단 확장자는 서버 판정이 정본, 클라이언트는 힌트만.)
- 전송: 파일이 있으면 `FormData`(`meta` = `Blob([JSON], {type: 'application/json'})` +
  `files` N개), 없으면 기존 JSON 그대로. `useApi`는 FormData에 Content-Type을 강제하지
  않으므로($fetch가 boundary 자동 설정) 무수정 통과 — 호출부에서 Content-Type을 명시하지
  않을 것.

### 8.2 작업 상세 (`pages/tasks/[id].vue`)

- 작업 정보 카드의 요청 상세 아래에 조건부 첨부 섹션(기존 `mcpsExtra` 조건부 섹션과 동일
  패턴): 파일명·크기·다운로드 버튼. 요청자·관리자 모두 다운로드 가능하므로(§5.3 ACL) 별도
  admin 게이팅 불필요.
- `TaskResponse` 인터페이스는 **[id].vue(상세)만** `attachments` 추가. index.vue의 목록
  인터페이스는 "목록이 실제 쓰는 필드만 선언"하는 기존 부분집합 관례대로 **무변경**(§5.2의
  목록=`[]` 계약과 일관).
- 다운로드: `<a href>`는 Bearer 헤더를 못 실으므로 **`useApi<Blob>(url, { responseType:
  'blob' })` → objectURL → 프로그램적 클릭**. 반드시 useApi 경유(전역 `$fetch` 직접 호출은
  Authorization 미첨부로 401). 다운로드 파일명은 응답 헤더 파싱이 아니라(ofetch는 헤더를
  안 돌려줌) `attachments` 메타의 `fileName`을 `<a download>`에 사용. (SSE식 `?access_token=`
  쿼리 방식은 복사 가능한 URL에 토큰이 남아 배제.)

## 9. 테스트 전략

| 계층 | 내용 |
|---|---|
| BE 단위 | `AttachmentStorage`: 한도·차단 확장자·0바이트·sanitize(탈출 시퀀스 포함)·상대경로 규칙·실패 시 클린업 |
| BE 통합 (Testcontainers, `RUN_TESTCONTAINERS` 게이트) | multipart 등록 → DB행+디스크 파일 일치 → 승인 후 claim 응답에 `attachments` 포함(절대경로 검증) → 다운로드 ACL 3종(본인 200 / 타인 403 / 관리자 200) + 파일 유실 404 |
| 계약 (교차, 필수) | Java `InterviewClaimResponseTest`에 attachments 필드/`null→[]` 정규화 단언 + Node `types.ts` 동기 + `interviewRunner.test.ts`: "첨부 있는 fresh claim → 프롬프트에 경로 포함 / 0건 → 섹션 없음 / resume → 미포함 / **attachments 필드 자체가 없는 claim → 크래시 없음**" |
| FE Vitest | FormData 구성(meta+files, Content-Type 미지정), 한도 초과 에러, 파일 없을 때 JSON 경로 유지, 상세 첨부 렌더·다운로드 호출 |
| 수동 스모크 | 실제 PDF+이미지 업로드 → 승인 → 에이전트가 파일 내용을 반영해 질문하는지 확인. 이 프로젝트의 반복 교훈: **mock은 교차 계약을 못 잡는다** — 실연동 스모크 필수 |

## 10. 에러 처리 요약

| 상황 | 동작 |
|---|---|
| 업로드 검증 실패 (앱 한도·확장자·sanitize) | 400 + 한국어 메시지, 작업 미생성 |
| 스프링 multipart 한도 초과 (파일 >25MB / 요청 >60MB) | 413 + 한국어 메시지 (§6.1 예외 핸들러), 작업 미생성 |
| 디스크 쓰기 실패 | 트랜잭션 롤백 + 쓴 파일 정리, 500 |
| 다운로드: 메타 없음/파일 유실 | 404 |
| 다운로드: 권한 없음 | 403 |
| 에이전트 Read 실패(유실·비지원 포맷) | 프롬프트 지침대로 질문 폴백, 인터뷰 계속 |

## 11. 배포·호환성

- **롤아웃 순서 자유(계약이 관대함)**: 구버전 프론트는 JSON 등록이 그대로 동작. 구버전
  인터뷰 서비스는 claim의 `attachments`를 무시(TS는 런타임 검증 없음) — 첨부만 활용 못 할 뿐
  기능 저하 없음. 신버전 인터뷰 서비스의 `?? []` 가드(§7)는 **롤아웃 순서와 무관하게 무조건
  구현** — 가드 덕에 구버전 백엔드 조합에서도 크래시 없이 첨부만 비활성. 백엔드 먼저 배포
  권고는 심층방어일 뿐이다.
- 라이브 반영에는 백엔드 재빌드/재기동 + 인터뷰 서비스 재기동 + 프론트 재빌드 필요
  (기존 재기동 런북 준수).
- Flyway V19는 부팅 시 자동 적용.

## 12. 향후 확장 경로 (설계가 열어둔 것)

- 구현/디자인 워커 전달: 해당 claim 계약에 `AttachmentRef` 추가 + 프롬프트 반영만.
- 오피스 문서 텍스트 추출: 업로드 시 Tika 사이드카 `.txt` 생성 → `AttachmentRef`에
  `extractedTextPath` 필드 추가.
- 첨부 GC: task soft-delete 시 또는 보존 기한 경과 시 `task-{id}/` 삭제 — `TODOS.md` 항목.
