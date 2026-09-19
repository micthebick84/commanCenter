# 질문 세션 — 대화 중 MCP 변경 + 채팅 첨부파일 설계

- 날짜: 2026-09-13
- 상태: 사용자 승인됨 (feature-dev 워크플로: 탐색 3면 → 질의 4건 → 아키텍트 3안 비교 → "절충안 + SSE note" 채택)
- 범위: netisMaker 백엔드(Java) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt)
- 선행 스펙: `2026-08-30-question-sessions-design.md`(Q&A), `2026-09-05-question-chat-ui-design.md`(채팅 UI, "MCP 고정" 결정을 이 스펙이 뒤집음), `2026-08-16-task-attachments-design.md`(작업 첨부 — 스토리지 재사용)

## 1. 목표

질문 세션(`InterviewSession.kind=QUESTION`) 채팅에서
1. **MCP 도구를 대화 중에 바꿀 수 있다** — 관리자 카탈로그(extras)만. 운영자 `~/.claude.json` base MCP는 불변.
2. **메시지에 파일을 첨부**하고(새 질문 등록 + 추가 질문 모두), 에이전트가 그 파일을 읽어 답한다. 오피스 문서(docx/xlsx/pptx/hwp)는 서버가 텍스트를 추출해 준다.

## 2. 확정된 정책 결정 (사용자 답변)

| 결정 | 내용 |
|---|---|
| MCP 변경 반영 시점 | **다음 질문 전송 시 함께** — `ask` 바디에 동봉(모델·effort와 동일 패턴). 픽커는 "다음 질문에 쓸 초안" |
| 첨부 시점 | 새 질문 등록 + 추가 질문 **모두** |
| 오피스 문서 | **서버 측 텍스트 추출 포함** (Apache Tika, HWP v5 파서는 standard 패키지에 포함 — 3.2.3 formats 목록 확인) |
| 첨부 한도 | 메시지당 작업 첨부와 동일: 10개 / 파일당 20MB / 합계 50MB (`AttachmentStorage.validate` 재사용). 세션 합계 상한 없음(문답 상한 10턴이 자연 상한) |
| 에이전트 읽기 방식 | 원본 절대경로 + 오피스는 sidecar `.txt` 절대경로를 프롬프트에 나열 → 에이전트가 **Read**. 질문 게이트에 세션 첨부 디렉토리를 **Read 전용 두 번째 허용 루트**로 추가(Grep/Glob/Bash는 레포 한정 유지) |
| MCP 변경 노트 | `role=system,kind=note` 턴 저장 + **새 SSE `note` 이벤트**로 라이브 전달. replay에서 system 턴이 `question`으로 나가던 기존 버그도 함께 수정 |
| 등록 시 첨부 표시 | 대화 헤더 아래 칩 줄(첫 질문은 말풍선이 없음). 추가 질문 첨부는 사용자 말풍선 아래 칩 |
| 다운로드 | 본인·관리자, `GET /api/questions/{id}/attachments/{attId}` (TaskController 패턴) |

**스파이크 결과(2026-09-13, `spikes/05-resume-mcp-change.ts`, haiku 3턴)**: `resume` 세션에 턴마다 다른 `mcpServers`를 주면 추가한 서버의 도구가 init에 나타나고 실제 호출되며(PAPAYA-77 회수), 제거하면 다음 턴에서 에러 없이 사라진다 → 러너 변경 없이 claim당 재조립만으로 반영됨.

## 3. 아키텍처 요점

```
[MCP]  픽커(다음 질문 초안) ─send─▶ POST /ask {mcpCatalogIds} ─▶ QuestionService.applyMcpChange
        (집합 같으면 no-op / 다르면 resolveExtras 검증 → mcps_extra+mcp_catalog_ids 갱신 → system note 턴)
        ─▶ 컨트롤러 pushStatus + pushNote(SSE) ─▶ 다음 claim이 mcpsExtra를 새로 복사 ─▶ buildOptions 재조립

[첨부]  multipart(meta+files) ─▶ validate → 턴 생성 → 모델/MCP 검증 → 파일 쓰기(마지막; 실패 시 자기 정리)
        디스크 {attachment.dir}/question-{sid}/{create|턴seq}/{순번}-{sanitized}(+ .txt sidecar)
        ─▶ claim: attachments(등록분) + turns[].attachments(턴별) + attachmentRoot
        ─▶ 러너: 프롬프트에 경로 나열, Read 게이트가 attachmentRoot 허용
```

단일 호스트/공유 FS 전제는 작업 첨부와 동일. 파일 쓰기를 트랜잭션의 **마지막** 단계로 두어 모델·MCP 검증 400이 고아 파일을 남기지 않게 한다.

## 4. 데이터 모델 — `V23__question_attachments.sql`

```sql
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS mcp_catalog_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS com.question_attachment (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    turn_seq            INTEGER,                 -- NULL = 등록 시(킥오프), 아니면 그 답변 turn의 seq
    original_filename   VARCHAR(255) NOT NULL,
    stored_path         VARCHAR(500) NOT NULL,   -- 루트 기준 상대경로
    content_type        VARCHAR(100),
    size_bytes          BIGINT NOT NULL,
    extracted_text_path VARCHAR(500),            -- Tika sidecar(.txt) 상대경로. 오피스 문서 + 추출 성공 시만
    uploaded_by         VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_question_attachment_session ON com.question_attachment(session_id);
```

- `mcp_catalog_ids`: `mcps_extra`(런타임 계약, name/url/transport 스냅샷)와 별개로 **선택한 카탈로그 id**를 보관. 용도 = ① 프론트 `McpPicker`(v-model number[]) 시딩 ② `applyMcpChange`의 "현재와 같으면 검증 없는 no-op"(사후 비활성화된 카탈로그 항목이 무관한 다음 질문을 400으로 막지 않게 — `applyModelChange`의 박제 보호와 같은 취지). 이름 역매핑은 rename/삭제에 취약해 거절.
- `task_attachment` 미재사용: `task_id NOT NULL`이고 등록 1회·불변 모델이라 메시지 단위 추가와 맞지 않음.
- 순번은 **메시지 단위 하위 디렉토리**(`create` 또는 턴 seq)로 격리 → 턴을 넘나드는 충돌 없음.
- `uploaded_by`는 FK 없음(`task_attachment`와 동일하게 문자열만 — H2 테스트 스키마 호환).

## 5. 백엔드 계약

### 5.1 요청/응답
- `QuestionAskRequest` += `List<Long> mcpCatalogIds` — **null = 유지, 비어있는 리스트 = 전부 해제**(문자열 blank=유지 규칙과 비대칭, 주석으로 명시).
- `QuestionCreateRequest` 무변경(기존 `mcpCatalogIds`가 `mcp_catalog_ids`도 시드).
- `POST /api/questions`, `POST /api/questions/{id}/ask`: 기존 JSON 매핑은 `consumes` 미지정 그대로 두고, `consumes=multipart/form-data` 매핑을 추가(`@RequestPart("meta")` JSON + `@RequestPart(value="files", required=false)`). TaskController 선례 — 기존 매핑에 consumes를 달지 말 것.
- `GET /api/questions/{id}/attachments/{attId}` → ACL(`requireViewable`: kind 404 → 소유자/관리자) 후 스트리밍, `Content-Disposition: attachment` RFC 5987, octet-stream.
- `InterviewResponse` += `List<Long> mcpCatalogIds`, `List<AttachmentView> attachments`(등록분, turn_seq null), `TurnView.attachments`(턴별). `AttachmentView(long id, String fileName, String contentType, long sizeBytes)` — **절대경로·추출경로는 브라우저로 내보내지 않는다**.
- `InterviewClaimResponse`: `AttachmentRef` += `String extractedTextPath`(절대경로, null 가능); `Turn` += `List<AttachmentRef> attachments`(non-null); 레코드 += `String attachmentRoot`(QUESTION이면 `{dir}/question-{sid}` 절대경로, INTERVIEW면 null). 기존 세션 레벨 `attachments`는 QUESTION에서 turn_seq null 행으로 채움(INTERVIEW 경로 무변경).

### 5.2 서비스
- `InterviewService.submitAnswer` → 반환형 `AnswerOutcome(InterviewSession session, Integer turnSeq)` (중복 제출 no-op이면 `turnSeq=null`). 호출처 2곳(`InterviewController.answer`, `QuestionService.ask`).
- `InterviewService.appendSystemNote(sessionId, content)` public 추가(기존 private `appendTurn` 래핑).
- `InterviewService.claim`: kind 분기로 `attachmentRefsFor`(QUESTION: turn_seq null 행), `turnAttachmentsFor`(seq→refs 맵), `attachmentRoot` 계산.
- `QuestionService.create(req, files, requesterId)`: `validate(files)` → 기존 `create` → `mcpCatalogIds` 시드 → `writeAttachments(sid, null, files)`.
- `QuestionService.ask(id, actor, isAdmin, req, files)` → `AskResult(InterviewSession session, InterviewTurn mcpNote /*nullable*/)`. 순서: kind 가드 → 문답 상한 → `validate(files)` → `submitAnswer` → `applyModelChange` → `applyMcpChange` → (turnSeq != null이면) `writeAttachments(sid, turnSeq, files)`. 중복 제출(turnSeq null)이면 첨부는 조용히 버림(텍스트와 동일 no-op).
- `applyMcpChange(s, ids)`: `ids == null` → 무시. `Set(ids) == Set(s.mcpCatalogIds)` → **검증 없이** 무시. 아니면 `mcpCatalogService.resolveExtras(ids)`(400) → `setMcpsExtra` + `setMcpCatalogIds` → `appendSystemNote("MCP 도구 변경: a, b")` 또는 비면 `"MCP 도구 변경: 없음(전부 해제)"` → 노트 턴 반환.
- `writeAttachments`: 순번 1..N, `AttachmentStorage.relativePathForQuestion(sid, turnSeq, ordinal, name)` → `write` → 오피스 확장자면 `TikaExtractionService.extractText(path, name)` → 성공 시 `writeText(rel + ".txt")` → `QuestionAttachment.create(...)` save. `RuntimeException` 시 이미 쓴 파일(sidecar 포함) `deleteQuietly` 후 rethrow(tx 롤백).
- `AttachmentStorage` 추가 메서드(기존 시그니처 불변): `relativePathForQuestion`, `extractedTextRelativePath(rel) = rel + ".txt"`, `writeText(rel, text)`, `questionRootRelative(sid) = "question-" + sid`.
- `TikaExtractionService`(`@Component @Profile("api")`): `OFFICE_EXTENSIONS = docx, doc, xlsx, xls, pptx, ppt, hwp, hwpx`, 그 외는 `Optional.empty()`(PDF·이미지·텍스트는 에이전트 Read가 네이티브 처리). `AutoDetectParser` + `BodyContentHandler(-1)`, 결과 200,000자 초과 시 절단 + `…(truncated)`, blank면 empty. **예외는 절대 전파하지 않음**(로그 후 empty → 원본만 저장).
- 의존성: `org.apache.tika:tika-core`, `org.apache.tika:tika-parsers-standard-package` (3.2.x — gradle 해석 확인).

### 5.3 SSE
- `InterviewStreamService.pushNote(sessionId, seq, content)` → 이벤트명 `note`, 페이로드 `QuestionEvent{seq,content}`.
- `subscribe()` replay: `role=system` 턴은 `note` 이벤트로(기존엔 `question`으로 나가 프론트 hydrate dedup에만 의존).
- `QuestionController.ask*`: `pushStatus(QUEUED)` 후 `mcpNote != null`이면 `pushNote`.

## 6. 인터뷰 서비스 계약

- `types.ts`: `AttachmentRef.extractedTextPath?: string | null`, `InterviewTurn.attachments?: AttachmentRef[]`, `InterviewClaimResponse.attachmentRoot?: string | null` (전부 optional — 구버전 백엔드 호환, `attachments` 선례).
- `permissions.ts`: `buildCanUseTool(repoDir, kind, attachmentRoot: string | null = null)`. QUESTION의 `questionReadGate`만 확장 — `insideRepoReal(repoDir, fp) || (attachmentRoot && insideRepoReal(attachmentRoot, fp))`. Grep/Glob/Bash 게이트 무변경. `~` 금지 유지.
- `sessionOptions.ts`: `SessionOptionsInput.attachmentRoot?` → `buildCanUseTool(..., input.attachmentRoot ?? null)`.
- `interviewRunner.ts`: `questionAttachmentSection(atts, intro)` — 줄 형식 `- {fileName}: {absolutePath}` + 오피스면 ` (텍스트 추출본: {extractedTextPath} — 이 경로를 Read하세요)`; 안내문 "Read 도구로 위 파일을 읽고 답변에 반영. 읽을 수 없는 포맷이면 건너뛰고 사용자에게 확인". fresh: `claim.attachments`("질문에 첨부된 파일:"); resume: `claim.turns`에서 마지막 `role=user,kind=answer` 턴의 `attachments`("이 메시지에 첨부된 파일:")를 `lastAnswer` 뒤에 붙임. `runQuestionTurn`은 `attachmentRoot: claim.attachmentRoot`를 `buildOptions`에 전달.

## 7. 프론트엔드 계약

- `composables/attachmentLimits.ts`(신규): `MAX_FILES/MAX_FILE_MB/MAX_TOTAL_MB` + `validateFiles(files): string | null` — `pages/tasks/index.vue`의 인라인 상수·함수를 여기로 옮기고 import(동작 무변경, 기존 `tasks-form-attachments.spec.ts` 그대로 통과해야 함).
- `composables/questions.ts`: `QuestionDetail` += `mcpCatalogIds: number[]`, `attachments: AttachmentView[]`, `turns?: Array<{seq, role, kind, content, attachments?: AttachmentView[]}>`; `AttachmentView {id, fileName, contentType: string | null, sizeBytes}`.
- `useInterviewStream.ts`: `Turn.attachments?: AttachmentView[]`; `note` 이벤트 리스너 → `pushTurn({seq, role:'system', kind:'note', content})`; `hydrate`가 user 턴의 `attachments`를 전달.
- `QuestionComposer.vue`: `files` `defineModel<File[]>('files', {default: () => []})`; **클립 버튼은 `q-input` `#prepend` 슬롯**(마이크와 같은 이유 — 390px 툴바 포화), 숨은 `q-file multiple` 트리거; 대기 파일 칩 줄은 `<slot name="top"/>`와 입력창 사이, 칩마다 제거; `validateFiles` 위반 시 `$q.notify` + 추가 거부; `inputLocked`면 클립 비활성.
- `ChatBubble.vue`: `attachments?: AttachmentView[]` prop, `emit('download', id)`; `role==='user'`일 때만 말풍선 아래 칩. 클래스명에 Quasar 예약어(xs/sm/md/lg/xl) 금지.
- `InterviewPanel.vue`: `AskExtra = {model?, effort?, mcpCatalogIds?: number[], files?: File[]}`; 질문 세션이고 `files.length>0`이면 `FormData`(`meta` JSON Blob + `files`)로 POST(Content-Type 미지정), 아니면 기존 JSON; 낙관적 user 턴에 `attachments`(id 없음, fileName/sizeBytes) 포함; `sendAnswer`가 성공 여부 `boolean` 반환(슬롯 `send`로 노출) → 페이지가 성공 시 `files` 초기화; `ChatBubble @download` → `GET {apiBase}/{sid}/attachments/{id}` blob → objectURL `<a download>`(`pages/tasks/[id].vue` 패턴 복사).
- `pages/questions/[id].vue`: 비활성 MCP 버튼 → `index.vue`와 동일한 `q-btn + q-badge + q-menu + McpPicker` (v-model `pickedMcp: number[]`, `detail.mcpCatalogIds`로 1회 시딩, `:disable="!awaiting"`); 툴팁 "대화 중 변경 — 다음 질문부터 적용"; `send({model, effort, mcpCatalogIds: pickedMcp, files})`; 헤더 아래 등록분 첨부 칩 줄(`detail.attachments`, 비면 숨김, 클릭 다운로드).
- `pages/questions/index.vue`: `files` ref + `v-model:files`; 파일 있으면 multipart로 `POST /api/questions`.
- 문자열은 전부 한국어 하드코딩(i18n 없음).

## 8. 테스트 계획

- Java: `QuestionServiceTest` — MCP 같은 집합(순서 무관) no-op·resolveExtras 미호출 / 다른 집합 → 검증·갱신·노트 / 잘못된 id 400 → 세션 불변 / null 유지 / 빈 리스트 해제 노트; 첨부 — create가 turn_seq null 행 + 파일 / ask가 턴 seq 링크 / 중복 제출 시 0행 / 검증 실패 시 파일 없음 / 루프 중 실패 시 기록 파일 삭제. `InterviewServiceTest` — `AnswerOutcome.turnSeq` 정상·중복 / claim의 `attachmentRoot`·턴별 attachments(QUESTION만). `TikaExtractionServiceTest` — docx/xlsx 픽스처 → 텍스트, 비오피스 → empty, 손상 파일 → empty 무예외. `InterviewStreamServiceTest` — system 턴 replay가 `note`. 컨트롤러 통합(Testcontainers) — multipart create/ask, 다운로드 ACL 403/404, JSON ask 회귀.
- 인터뷰 서비스(vitest): permissions — attachmentRoot 안 Read allow / 밖 deny / Grep·Glob은 여전히 repoDir만 / attachmentRoot 없으면 기존과 동일; sessionOptions — 전달; interviewRunner — fresh/resume 프롬프트에 첨부 섹션·sidecar 경로.
- 프론트(vitest): `QuestionComposer` — 파일 추가/제거/한도 notify/잠금; `ChatBubble` — user만 칩·download emit; `useInterviewStream` — note 이벤트·hydrate attachments; `questions-detail` — MCP 버튼 활성·시딩·ask 바디 `mcpCatalogIds`·multipart 분기·헤더 칩; `questions-index` — multipart 분기; `tasks-form-attachments` 회귀.
- 수동 스모크(라이브): docx 첨부 → 답변이 내용 인용; 대화 중 MCP 추가 → 노트 즉시 표시 + 다음 턴 init 도구 목록 반영.

## 9. 배포 순서·운영

API → 인터뷰 서비스 → 프론트 `.output` 재빌드 (필드가 전부 additive/optional이라 순서 비민감). 첨부 GC는 작업 첨부와 동일하게 미정(TODOS 항목에 `question-*` 디렉토리 한 줄 추가). Tika 추출은 업로드 요청 스레드에서 동기 실행(20MB 상한, 저트래픽 내부 도구 전제).
