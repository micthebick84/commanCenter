# 첨부 pptx 슬라이드 렌더링 — 설계

작성 2026-10-27. 상태: 초안(검토자 확인 반영, 사용자 승인 전).

## 1. 왜

요청자는 작업 등록 때 pptx로 요구사항을 준다. 예시 `261027_대시보드_업그레이드개발.pptx`(슬라이드 2장)는 이렇게 생겼다.

- 슬라이드 1: 이전 대시보드 캡처와 바뀐 대시보드 캡처를 나란히 두고, 빨간 꺾인 화살표(이전 → 이후)와 **바뀐 캡처 위에 겹친 빨간 테두리 사각형 2개**로 "새로 생긴 카드"를 가리킨다. 글은 "1. 상단 영역 2개 추가 2. 하단 영역 2개 추가"뿐이다.
- 슬라이드 2: 설정 팝업 캡처 1장 + 요구사항 4줄(5.4 → 6.8 기반 업그레이드, 상단·하단 영역 설정 기능, 장비설정·회선설정 타이틀 설정 기능).

요구사항의 절반은 그림과 그 위에 겹친 표시에 있다. 지금 Symphony는 이것을 전혀 못 본다.

- 작업 첨부: 원본만 저장하고(`TaskService.create`), 인터뷰 kickoff는 "오피스 문서는 건너뛰라"고 한다(`interviewRunner.ts` `attachmentSection`). Read 도구는 pptx를 못 연다 → **텍스트조차 못 본다.**
- 질문 첨부: Tika로 텍스트 sidecar(`.txt`)만 만든다(`QuestionService.writeAttachments`) → 글 6줄만 보이고 그림·빨간 표시는 안 보인다.
- 그림 파일(`ppt/media/*`)만 꺼내도 부족하다 — 빨간 사각형은 그림 밖의 도형이라 빠지고, "어느 캡처의 어디"인지가 사라진다.

**목표**: 인터뷰(작업 등록)와 질문 세션의 에이전트가 pptx의 각 슬라이드를 **사람이 보는 모양 그대로** 보고, 개발 요구사항과 바뀐 화면 레이아웃을 파악해 계획을 쓸 수 있게 한다.

## 2. 목표 / 비목표 / 실패 기준

목표
- pptx·ppt 첨부를 슬라이드별 PNG로 렌더링해 에이전트가 Read로 본다.
- 같은 첨부의 텍스트 추출본(Tika)과 원본 그림도 함께 준다 — 글을 정확히 읽고, 작은 글씨의 캡처를 크게 보는 보조 자료.
- 작업 첨부와 질문 첨부(등록·추가 질문) 모두 같은 규칙.
- 질문에서 "이 대화로 작업 등록"하면 질문 첨부(원본·텍스트·슬라이드 그림)가 작업 첨부로 넘어간다(§7.5).
- 렌더링이 안 돼도(LibreOffice 없음·실패·시간 초과) 업로드·인터뷰·질문은 지금처럼 진행된다.

비목표
- 화면에서 슬라이드 미리보기 보여 주기(프론트 변경 없음 — §8에 상태 표시만 선택 사항).
- 구현 워커(`claude -p`)에 첨부 전달 — 구현은 지금처럼 확정된 계획만 본다.
- docx·xlsx·hwp 렌더링(설정으로 켤 수 있게만 열어 둔다, 기본 꺼짐).
- 이미 올라온 첨부의 소급 렌더링.
- 그림 속 글자 OCR.

실패 기준(동작은 해도 실패로 본다)
- 업로드 요청이 렌더링 때문에 눈에 띄게 느려지거나, 렌더링 실패가 업로드 실패가 되는 것.
- 질문 세션이 렌더링이 끝나기 전에 시작돼 첫 답변에서 슬라이드를 못 보는 일이 흔한 것.
- 깨진·악성 pptx 하나가 API 프로세스나 다른 렌더링을 멈추는 것.

## 3. 사용자가 정한 것

- 방식: **슬라이드 렌더링**(LibreOffice로 슬라이드를 그림으로). 원본 그림 꺼내기 + 도형 설명 방식은 쓰지 않는다.
- **라이브 PC(API가 도는 Windows PC)에 LibreOffice를 설치한다.**
- **질문 → 작업 등록 때 질문 첨부를 작업으로 승계한다**(세부는 §7.5, 세부 규칙은 작성자 판단).

## 4. 전체 흐름

```
업로드(작업 등록 / 질문 등록 / 추가 질문)
  └ 원본 저장 + (오피스면) Tika 텍스트 sidecar — 지금처럼 요청 안에서, 작업 첨부에도 새로 적용
  └ 렌더링 대상이면 render_status=PENDING 으로 메타 행 저장 (요청은 여기서 끝)

API 렌더링 잡(단일 스레드, DB 큐)
  └ PENDING 1건 → RUNNING
  └ soffice --headless --convert-to pdf  (격리된 프로필, 시간 상한, 프로세스 트리 kill)
  └ PDFBox로 페이지별 PNG (상한 장수·폭)
  └ (pptx면) ppt/media 원본 그림 복사
  └ DONE(장수 기록) | FAILED(사유) | SKIPPED(꺼짐·대상 아님)

인터뷰 서비스 claim
  └ 렌더링이 아직 PENDING/RUNNING인 첨부가 있으면 잠시 claim하지 않는다(대기 상한까지)
  └ claim 바디의 첨부마다 텍스트 추출본 경로 · 슬라이드 그림 경로 목록 · 원본 그림 경로 목록
  └ 프롬프트: "슬라이드 그림을 순서대로 Read" + 보조 자료 안내
```

## 5. 저장

### 5.1 파일 배치

원본 옆에 둔다 — 첨부 정리 잡(`AttachmentCleanupJob`)이 소유자 폴더(`task-{id}`·`question-{sid}`)째 지우므로 함께 지워지고, 질문 Read 게이트의 `attachmentRoot`(`question-{sid}`) 안이라 게이트 변경이 필요 없다.

```
task-{id}/{n}-{이름}.pptx              원본 (지금 그대로)
task-{id}/{n}-{이름}.pptx.txt          Tika 텍스트 (질문 sidecar 규칙 그대로, 작업에도)
task-{id}/{n}-{이름}.pptx.render/
    slide-001.png … slide-0NN.png     슬라이드 그림
    media/{원래 이름}                  원본 그림 (pptx만)
```

질문 첨부도 같은 규칙(`question-{sid}/{create|turnSeq}/…`). 임시 PDF는 렌더링 작업 폴더에서 만들고 끝나면 지운다(저장하지 않는다).

### 5.2 DB (V34)

`com.task_attachment`와 `com.question_attachment`에 같은 칸을 더한다.

| 칸 | 뜻 |
|---|---|
| `extracted_text_path` | (task_attachment에만 새로) Tika sidecar 상대경로, 없으면 null — question_attachment에는 이미 있다 |
| `render_status` | `NONE`(대상 아님) / `PENDING` / `RUNNING` / `DONE` / `FAILED` / `SKIPPED`. `NOT NULL DEFAULT 'NONE'` — 기존 행은 NONE(소급 없음) |
| `render_dir` | `….render` 상대경로, DONE일 때만 |
| `render_page_count` | 만든 슬라이드 그림 수 |
| `render_total_pages` | 문서 전체 페이지 수(상한으로 잘렸는지 프롬프트에 알리려고) |
| `render_media_count` | 복사한 원본 그림 수 |
| `render_error` | FAILED·SKIPPED 사유(짧게, 경로·비밀 없이) |
| `render_started_at` | RUNNING 들어간 시각 — 재기동·멈춤 회수용 |
| `render_attempts` | RUNNING에 들어간 횟수. `NOT NULL DEFAULT 0` — 회수 "1회까지" 판정용(§6) |

렌더링 큐는 이 칸들이다(별도 큐 테이블 없음). 두 테이블을 한 잡이 돈다.

## 6. 렌더링 잡 (API, `@Profile("api")`)

- **대상 판정**: 원본 확장자가 `app.attachment.render.extensions`(기본 `pptx,ppt`)에 있고 기능이 켜져 있으면 PENDING, 아니면 NONE. 기능이 꺼진 상태(soffice 없음 포함)에서 대상 확장자면 SKIPPED(`렌더링 꺼짐`).
- **잡**: 고정 지연 폴링(예: 5초) + 업로드 커밋 뒤 즉시 깨우기(트랜잭션 커밋 후 이벤트). **한 번에 1건**, 전용 단일 스레드 — API의 스케줄러 스레드(풀 1, 다른 잡과 공유)를 붙잡지 않는다. PENDING을 `FOR UPDATE SKIP LOCKED`로 1건 잡아 RUNNING으로 바꾸고 커밋한 뒤 트랜잭션 밖에서 변환한다.
- **변환 1단계 — PDF**: `soffice --headless --norestore --nologo --convert-to pdf --outdir <작업폴더> <원본 사본>`.
  - 실행 파일: `SOFFICE_PATH` 환경변수, 없으면 `C:\Program Files\LibreOffice\program\soffice.com`(Windows) → PATH의 `soffice`. 못 찾으면 기능 꺼짐 + 기동 로그 한 줄.
  - 실행마다 새 사용자 프로필 `-env:UserInstallation=file:///<작업폴더>/profile` — 동시 실행·잠금 파일 충돌과 운영자 프로필 오염을 막는다. 작업폴더는 `{app.attachment.dir}/.render-work/{uuid}`, 끝나면 지운다.
  - 시간 상한 `app.attachment.render.timeout-seconds`(기본 120). 넘기면 **Windows는 `taskkill /PID … /T /F`로 프로세스 트리째** 끝낸다(인터뷰 서비스 `execFileWithTreeKill`과 같은 교훈 — soffice.com도 런처다). FAILED `렌더링 시간 초과(120초)`.
  - 원본 파일명은 명령줄에 그대로 싣지 않는다 — 작업폴더에 `input.<확장자>`로 복사해 변환한다(한글·공백·따옴표 문제와 인자 주입 회피).
  - 매크로는 실행하지 않는다(LibreOffice headless 변환 기본값 — 설정 문서에 확인 절차를 적는다).
- **변환 2단계 — PNG**: PDFBox(`tika-parsers-standard-package`가 이미 끌어온다 — `build.gradle`에 직접 의존성으로 명시할 것)로 페이지마다 그린다.
  - 폭 `app.attachment.render.width-px`(기본 1600)에 맞춰 DPI를 계산, PNG.
  - 장수 상한 `app.attachment.render.max-pages`(기본 30). 넘는 페이지는 그리지 않고 `render_total_pages`로 알린다.
- **원본 그림(pptx만)**: zip의 `ppt/media/*` 중 png·jpg·jpeg·gif·bmp만, 10KB 미만(아이콘)은 빼고, 최대 `app.attachment.render.max-media`(기본 20)개를 `media/`에 복사. emf·wmf 같은 벡터는 건너뛴다(슬라이드 그림에 이미 보인다). zip 항목 이름의 경로(`../`)는 무시하고 파일명만 쓴다. 압축 해제 크기 상한(예: 항목당 30MB)을 둔다.
- **결과**: DONE(장수 기록) / FAILED(사유). 실패해도 원본·텍스트는 그대로다. 같은 파일을 자동 재시도하지 않는다.
- **재기동·멈춤 회수**: 기동 때와 주기적으로, `render_started_at`이 상한의 2배보다 오래된 RUNNING은 PENDING으로 되돌린다(`render_attempts`가 1이면 되돌리고, 2 이상이면 FAILED `렌더링 중단(재기동)`). 남은 `.render-work/*`는 기동 때 지운다.
- **동시 처리**: 직렬 1건. 렌더링은 길어야 수십 초이고 업로드 빈도가 낮다. soffice 동시 실행은 메모리를 많이 쓴다.

## 7. 인터뷰 서비스에 넘기기

### 7.1 claim 바디 (`InterviewClaimResponse.AttachmentRef`)

지금 필드(`id, fileName, absolutePath, contentType, sizeBytes, extractedTextPath`)에 더한다. 모두 optional — 구버전 러너는 무시한다.

- `slideImagePaths: string[]` — DONE일 때 슬라이드 그림 절대경로(순서대로). 그 외 빈 목록.
- `slideTotalPages: number | null` — 전체 페이지 수(잘렸을 때 알리려고).
- `mediaImagePaths: string[]` — 원본 그림 절대경로.
- `renderNote: string | null` — FAILED·SKIPPED·대기 상한 초과 때 한 줄 사유(예: `슬라이드 그림 없음: 렌더링 시간 초과`).

작업 첨부의 `extractedTextPath`도 이제 채운다(지금은 늘 null).

### 7.2 claim 대기

질문 세션은 등록 즉시 QUEUED라 렌더링보다 claim이 먼저 올 수 있다. 그래서 **claim 후보에서 "렌더링이 PENDING/RUNNING인 첨부가 있고, 그 첨부가 올라온 지 `app.attachment.render.claim-wait-seconds`(기본 180초) 안인" 세션을 뺀다.** 대상 첨부 = 그 claim이 프롬프트에 싣는 첨부(작업 인터뷰는 작업 첨부 전부, 질문 신규는 등록 첨부, 질문 resume는 마지막 답변 턴 첨부).

- 상한이 지나면 그대로 claim하고 그 첨부는 `renderNote: 슬라이드 그림 준비 중(대기 시간 초과)`로 간다.
- 작업 인터뷰는 승인 뒤에 claim되므로 보통 대기가 생기지 않는다.
- 화면의 "답변 대기중" 상태는 그대로 — 별도 상태를 만들지 않는다.

### 7.3 프롬프트

작업 kickoff `attachmentSection`과 질문 `questionAttachmentSection` 둘 다, 첨부 한 줄 아래에 들여 써서 붙인다.

```
- 261027_대시보드_업그레이드개발.pptx: C:\…\task-120\1-261027_….pptx
  - 텍스트 추출본: C:\…\1-261027_….pptx.txt
  - 슬라이드 그림(2장, 순서대로): C:\…\.render\slide-001.png, C:\…\.render\slide-002.png
  - 원본 그림(작은 글씨를 크게 볼 때): C:\…\.render\media\image1.png, …
```

안내문(작업 kickoff 기준):
- 지금의 "읽을 수 없는 포맷(docx/xlsx 등 오피스 문서)이면 건너뛰고" 문구를 바꾼다: "오피스 원본은 Read로 열 수 없습니다. 슬라이드 그림을 **모두 순서대로** Read해 화면 레이아웃·빨간 표시·화살표 같은 주석이 가리키는 변경점을 파악하고, 글은 텍스트 추출본으로 확인하세요. 캡처 속 글씨가 작으면 원본 그림을 보세요. 그림이 없다고 적힌 첨부는 텍스트 추출본만 보고, 모르는 부분은 질문으로 확인하세요."
- 잘렸으면 `(전체 45장 중 30장만 그림 — 나머지는 텍스트 추출본 참고)`.
- 계획에는 슬라이드에서 읽은 변경점을 슬라이드 번호와 함께 적게 한다("슬라이드 1: 상단 카드 2개 추가" 식) — 승인자가 계획이 그림을 반영했는지 확인할 수 있게.

질문 세션 안내문도 같은 뜻으로 짧게.

### 7.4 Read 게이트

- 질문 세션: 렌더링 산출물이 `attachmentRoot` 안이므로 변경 없음.
- 작업 인터뷰: INTERVIEW Read는 경로 제한이 없어 변경 없음.

## 7.5 질문 → 작업 첨부 승계

지금 "이 대화로 작업 등록"은 대화 전문 `question-{id}-대화.md` 하나만 작업 첨부로 넣는다(`TaskService.attachTranscript`). 전문에는 첨부 이름만 적혀 있어(`QuestionTranscript` — 내용·경로 없음) 질문에 올린 pptx를 작업 인터뷰가 못 본다. **사용자 결정: 질문 첨부를 작업으로 넘긴다.**

- **대상**: 출처 질문의 첨부 전부 — 등록 첨부(`turn_seq` null)와 추가 질문 턴 첨부, `id` 순. 요청자가 고르는 단계는 없다.
- **순번**: 사용자 파일 1..N → 대화 전문 N+1 → 승계 첨부 N+2…. 경로 규칙은 지금 작업 첨부 그대로(`task-{id}/{순번}-{이름}`) — 순번 접두로 같은 이름도 겹치지 않는다.
- **복사**: 원본 + Tika sidecar(`.txt`) + `.render/` 폴더를 새 경로로 복사한다(질문 쪽 정리 잡이 90일 뒤 질문 폴더를 지워도 작업 쪽은 남도록 — 하드링크 대신 복사). 새 `task_attachment` 행은 `original_filename`·`content_type`·`size_bytes`를 질문 첨부에서, `uploaded_by`는 질문 첨부를 올린 사람으로 둔다.
- **렌더링 상태 승계**: DONE·FAILED·SKIPPED·NONE은 칸 값(장수·사유 포함, 경로는 새 경로로)을 그대로 옮긴다. 질문 쪽이 아직 PENDING·RUNNING이면 작업 행은 **PENDING으로 새로 넣어** 렌더링 잡이 작업 쪽을 따로 렌더링한다(질문 쪽 결과를 기다려 옮기는 연동은 두지 않는다).
- **한도**: 승계 첨부는 사용자 첨부 한도(`validate` — 개수·크기)에 세지 않는다(대화 전문과 같은 규칙). 질문 첨부는 이미 질문 쪽 한도(턴당 개수·크기, `max-qa-turns`)로 묶여 있다. 다만 디스크 폭주를 막으려고 승계 총량 상한 `app.attachment.inherit-max-total-mb`(기본 200)를 두고, 넘으면 `id` 순으로 넘친 것부터 뺀다.
- **빠진 파일**: 질문 첨부 폴더가 이미 정리됐거나(질문 다운로드 410과 같은 경우) 원본이 없으면 그 첨부만 건너뛴다. sidecar·`.render`만 없으면 원본만 옮기고 렌더링 상태는 대상 확장자면 PENDING으로 다시 넣는다.
- **트랜잭션**: 지금 `create`와 같이 복사한 파일을 정리 목록에 쓰기보다 먼저 넣고, 실패하면 지우고 롤백한다(작업 등록 자체가 실패 — 반쯤 승계된 상태를 남기지 않는다). `.render` 폴더는 폴더째 지우는 정리 항목으로 넣는다. 복사는 등록 요청 안에서 동기로 한다(질문 첨부는 보통 수 MB — 200MB 상한 안).
- **이력**: 등록 이력 옆에 `질문 #{id} 첨부 승계: N개` (+ 뺀 것이 있으면 `/ 제외 M개(정리됨·용량 상한)`).
- **프롬프트**: 승계 첨부는 줄 끝에 `(질문 #{id}에서 넘어옴)`을 붙인다. 대화 전문에 적힌 첨부 이름과 같은 이름이라 에이전트가 대화 속 언급과 파일을 짝지을 수 있다.
- **화면**: 등록 창(`/tasks?fromQuestion=…`)에 "질문 첨부 N개가 함께 등록됩니다" 한 줄(목록은 접어서). 요청자가 빼는 기능은 넣지 않는다.
- 출처 질문이 없는 등록은 동작이 지금과 같다.

## 8. 화면 (선택, 작게)

필수 아님. 넣는다면 작업 상세·질문 대화의 첨부 칩 옆에 작은 글씨로 `슬라이드 그림 2장` / `그림 준비 중` / `그림 없음(사유)`. 다운로드·미리보기는 넣지 않는다. 넣으면 하이드레이션 스모크 목 응답도 같이 고친다.

## 9. 설정 (`application.yml` `app.attachment.render.*`)

| 키 | 기본 | |
|---|---|---|
| `enabled` | `true` | soffice를 못 찾으면 켜져 있어도 꺼진 것으로 동작 |
| `soffice-path` | `${SOFFICE_PATH:}` | 비면 자동 탐색 |
| `extensions` | `pptx,ppt` | docx 등은 운영자가 넣어 켤 수 있다(Tika 대상이 아닌 확장자는 텍스트 추출본이 없다) |
| `timeout-seconds` | 120 | |
| `max-pages` | 30 | |
| `width-px` | 1600 | |
| `max-media` | 20 | |
| `claim-wait-seconds` | 180 | 0이면 기다리지 않음 |

통합 테스트 initializer(`TestcontainersConfig`)는 렌더링 잡을 끈다(실제 첨부 루트 보호 — 첨부 정리 잡과 같은 관례).

## 10. 운영

- **설치**: 라이브 PC에 LibreOffice(안정판) 설치. 한글 글꼴은 Windows 시스템 글꼴(맑은 고딕)을 그대로 쓴다 — 설치 뒤 예시 pptx로 글자 깨짐이 없는지 확인.
- `public.env`에 `SOFFICE_PATH`(기본 위치면 생략 가능). API 재기동 후 기동 로그 `슬라이드 렌더링: 켜짐(soffice=…)` 확인.
- 보안: 사내 인증 사용자만 올린다는 전제. LibreOffice는 외부 파일 파서라 취약점 패치가 잦다 → 업데이트 대상 목록(INFRA-CHECKLIST 또는 TODOS)에 넣는다. 매크로 비실행 확인. 원격 링크 이미지 가져오기는 막지 못할 수 있다(남은 위험으로 적는다).
- 디스크: 슬라이드 그림 1장 ~200–600KB, 파일당 최대 30장 + 원본 그림. 첨부 정리 잡이 원본과 함께 지운다. 백업 zip(`backup-public.ps1`, 첨부 폴더 전체를 묶는다)에서 `*.render`와 `.render-work`는 뺀다 — 다시 만들 수 있는 사본이고 작업 폴더는 변환 도중의 임시 파일이다(이 기능의 작업 하나로 넣는다).
- **배포 순서**: API(V34, 렌더링 잡) → 인터뷰 서비스(새 필드를 프롬프트에 싣기) → (화면을 넣었다면) 프론트. 반대 순서도 깨지지 않는다 — 새 러너는 필드가 없으면 지금 문구로, 구 러너는 새 필드를 버린다. 다만 API가 먼저 가야 의미가 있다.
- CLAUDE.md "질문 세션" 첨부 설명과 "자주 보는 코드" 표에 렌더링 항목을 더한다.

## 11. 테스트

- 렌더러: soffice가 있는 PC에서만 도는 통합 테스트(`assumeTrue`) — 예시와 같은 모양의 작은 pptx 픽스처(그림 2장 + 빨간 사각형)로 PNG 2장·원본 그림 2장이 나오는지. CI에는 LibreOffice가 없으므로 건너뛴다.
- 단위: 대상 판정, 상한(장수·원본 그림 수·크기), zip 경로 무시, 시간 초과 → 트리 kill 호출, RUNNING 회수 규칙, claim 대기 조건(쿼리), DTO 직렬화.
- 승계(통합 테스트): 등록·턴 첨부가 순번 N+2…로 복사되는지, DONE 상태·경로 승계, 질문 쪽 PENDING → 작업 PENDING, 정리된 질문 폴더는 건너뛰고 이력에 제외 수, 200MB 초과분 제외, 복사 실패 시 작업 등록 롤백 + 복사본 삭제, 출처 질문 없는 등록은 그대로.
- 인터뷰 서비스: 두 섹션의 프롬프트 문자열(그림 있음·잘림·실패·필드 없음 구버전) 스냅샷.
- 사람 확인: 예시 pptx로 작업을 등록 → 승인 → 인터뷰 첫 질문 전 Read 로그에 slide-001/002.png가 보이고, 계획에 "상단 카드 2개·하단 차트 2개 추가", "설정 팝업 탭·타이틀 설정"이 슬라이드 번호와 함께 들어가는지.

## 12. 작성자가 정한 것 (사용자 확인 필요)

- 렌더링은 업로드 요청 밖(DB 큐 + 단일 스레드 잡). 질문 세션은 claim을 최대 180초 미룬다.
- 기본 대상은 pptx·ppt만(odp는 Tika 텍스트 추출 대상이 아니라 뺐다). docx 등은 설정으로.
- 파일당 30장, 폭 1600px, 원본 그림 20개 상한.
- 작업 첨부에도 Tika 텍스트 추출을 새로 적용.
- 원본 그림도 함께 준다(보조).
- 계획에 슬라이드 번호로 근거를 적게 한다.
- 소급 렌더링 없음, 자동 재시도 없음, 화면 표시는 선택.
- 첨부 승계: 질문 첨부 전부를 복사(하드링크 아님), 사용자 한도에 안 셈, 승계 총량 200MB 상한, 요청자가 빼는 기능 없음, 정리된 파일은 건너뜀, 질문 쪽 렌더링 중이면 작업 쪽에서 다시 렌더링.

## 13. 구현자에게 맡기는 것

- 렌더링 잡 클래스 구성, 커밋 후 깨우기 방식.
- PDFBox 렌더링 세부(DPI 계산, 메모리 — 페이지 하나씩 그리고 버린다).
- claim 대기 조건을 지금 JPQL claim 쿼리(`InterviewSessionRepository` — PESSIMISTIC_WRITE + SKIP LOCKED 힌트, `PageRequest.of(0,1)`)에 NOT EXISTS로 넣을지, 네이티브로 바꿀지.
- 프롬프트 문구의 최종 표현(뜻은 §7.3대로).
