# 대화형 분석 인터뷰 — 브라우저 새로고침 이어가기 (설계)

- 날짜: 2026-06-14
- 상태: 승인됨 (구현 대기)
- 선행: `2026-06-13-conversational-analysis-design.md`, `2026-06-14-interview-popup-ux-design.md`
- 브랜치: `feat/interview-refresh-resume` (on `feat/interview-popup-ux`)

## 1. 문제

대화형 분석 인터뷰 팝업(`InterviewPanel.vue`)에서 인터뷰 도중 브라우저를 새로고침하면 진행 중인 세션으로 돌아갈 수 없다.

근본 원인은 **프론트엔드가 세션 핸들을 휘발성 메모리에만 보관**한다는 점이다:
- `tasks/index.vue`의 `interviewSessionId`(`:55`), `showCreate`(`:49`), `dialogPhase`(`:54`)는 모두 컴포넌트 로컬 `ref`다. URL/localStorage/스토어 어디에도 저장되지 않는다.
- 마운트 시 진행 중 세션을 복원하는 로직이 없다(`onMounted`는 작업 목록만 로드).

백엔드는 이미 새로고침 복구를 전제로 설계돼 있다:
- 세션·턴은 DB(`com.interview_session`, `com.interview_turn`)에 영속화되며 `requesterId`로 소유.
- `GET /api/interviews/{id}/stream` 재구독 시 `InterviewStreamService.subscribe()`가 DB의 어시스턴트 턴을 `question`/`design` 이벤트로 **replay**한 뒤 라이브 구독을 붙인다.
- 워커는 무상태 pull/claim이라 브라우저가 없어도 세션은 `입력대기`로 안전히 대기한다. SSE 연결 종료는 인메모리 emitter만 제거할 뿐 세션 상태를 건드리지 않는다.

즉 **데이터·복구 메커니즘은 갖춰져 있고, 빠진 것은 클라이언트가 세션을 다시 찾아 패널을 재오픈하고 상태를 완전히 복원하는 경로뿐**이다.

## 2. 추가로 드러난 제약 (구현에 직접 영향)

1. **`InterviewResponse.status`는 한글 dbValue**("입력대기")인데, 프론트 스트림(`useInterviewStream`)은 영문 enum 이름("AWAITING_INPUT")으로만 상태를 판정한다. → 스냅샷 하이드레이션을 위해 영문 상태값이 필요하다.
2. **SSE replay는 `question`/`design`만 재생**한다. `status`·`plan_ready`는 라이브 전용이고, 사용자 답변(`role=user`) 턴은 "클라가 이미 가짐"이라는 가정으로 replay에서 제외된다. 새로고침 후 클라의 turns 배열은 비어 있으므로, **replay만으로는 ① 내 답변이 사라지고 ② status/plan이 복원되지 않는다**(→ 답변창·등록 버튼 비활성). 따라서 REST 스냅샷으로 시드해야 한다.
3. **`InterviewPanel`은 현재 `GET /{id}`를 호출하지 않는다**. 모든 상태를 SSE에서만 받는다.
4. **비종료 인터뷰를 닫을 방법이 "대화 취소"(=종료)밖에 없다**(q-dialog는 `persistent`, 비종료용 "닫기" 버튼 없음). 자동 재오픈을 도입하면 사용자가 인터뷰에 "갇히는" 문제가 생기므로 비취소 닫기 수단이 필요하다.

## 3. 확정된 설계 결정

- **세션 발견**: 백엔드 디스커버리 엔드포인트 `GET /api/interviews/active` — 호출자 본인의 비종료 세션 목록을 반환. 다른 탭/기기/스토리지 초기화에도 동작하고 다중 동시 인터뷰(최대 3, `app.interview.user-concurrent-limit`)를 처리.
- **재오픈 UX (하이브리드)**:
  - 활성 0개 → 무동작
  - 활성 1개 → 패널 자동 재오픈
  - 활성 2~3개 → 선택 다이얼로그(목록에서 이어할 인터뷰 선택)
- **상태 복원**: `InterviewPanel` 마운트 시 `GET /{id}` 스냅샷으로 전체 대화(내 답변 포함)+status+plan을 하이드레이션한 뒤 SSE를 연다. SSE replay는 seq dedup으로 중복 없이 합쳐진다.
- **갇힘 방지**: 패널에 비취소 "나중에"(닫기) 버튼 추가 — 세션을 활성 유지한 채 다이얼로그만 닫는다. 다음 새로고침/방문 시 다시 발견된다.

## 4. 데이터 흐름

```
새로고침 → /tasks onMounted
   └─ GET /api/interviews/active  →  [InterviewSummary...]
        ├─ 0개 → 무동작
        ├─ 1개 → interviewSessionId=id, dialogPhase='interview', showCreate=true (자동)
        └─ 2~3개 → 선택 다이얼로그 → 선택 시 위와 동일 오픈

InterviewPanel 마운트 (props.sessionId)
   ├─ GET /api/interviews/{id}  →  stream.hydrate(snapshot)
   │        (전체 turns[내 답변 포함] + statusName + plan 복원)
   └─ stream.open(id)  →  SSE 라이브 + replay (pushTurn seq dedup)
```

## 5. 변경 단위

### 5.1 백엔드

**(B1) `InterviewResponse`에 영문 `statusName` 추가** — `dto/InterviewResponse.java`
- 레코드에 `String statusName` 추가(= `s.getStatus().name()`). 기존 한글 `status`는 유지(비파괴적 추가).
- `of(...)` 팩토리에서 채움.
- 영향: `InterviewResponseTest` 갱신.

**(B2) 활성 세션 조회 쿼리** — `repository/InterviewSessionRepository.java`
- `List<InterviewSession> findActiveByRequester(@Param("requesterId") String requesterId)`
- `countActiveByRequester`와 동일한 IN절(QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY), `ORDER BY s.lastActivityAt DESC`(최근 활동 우선 → 자동 재오픈 시 가장 최근 세션 선택).

**(B3) 경량 요약 DTO** — `dto/InterviewSummary.java` (신규)
- `record InterviewSummary(Long id, String statusName, String statusLabel, String title, String githubRepo, String githubBranch, String currentPhase, OffsetDateTime lastActivityAt, OffsetDateTime createdAt)`
- `statusName` = 영문 enum, `statusLabel` = 한글 dbValue.
- `static of(InterviewSession s)`.
- 무거운 `InterviewResponse`(turns+plan 전체)를 리스트에 쓰지 않는다.

**(B4) 서비스 메서드** — `service/InterviewService.java`
- `@Transactional(readOnly = true) List<InterviewSummary> listActiveForRequester(String requesterId)`
- `sessionRepo.findActiveByRequester(requesterId)` → `InterviewSummary.of` 매핑. (turns/plan 미조회 → 경량)

**(B5) 컨트롤러 엔드포인트** — `controller/InterviewController.java`
- `@GetMapping("/active") List<InterviewSummary> listActive(JwtAuthenticationToken auth)`
- `String userId = AuthContext.requireUserId(auth); return interviewService.listActiveForRequester(userId);`
- 본인 스코프(관리자 여부 무관 — "내 인터뷰 이어가기"). `/{id}`와 충돌 없음(리터럴 경로 `/active`가 `/{id}` 템플릿보다 매칭 우선).

### 5.2 프론트엔드

**(F1) `useInterviewStream`에 `hydrate(snapshot)` 추가** — `composables/useInterviewStream.ts`
- 입력: `GET /{id}` 응답(`InterviewResponse` 형태) 타입.
- 매핑 규칙(라이브/replay 표현과 일치 + 사용자 답변 포함):
  - `kind === 'design'` → `designSections` upsert (key=`design-${seq}`, title='설계', body=content, approved=false) — 백엔드 replay의 `DesignEvent`와 동일.
  - `role === 'user'` → `turns` push (role='user', kind='answer').
  - 그 외(어시스턴트 비-design) → `turns` push (role='assistant', kind='question').
  - `plan`(있으면) → `plan` 세팅. `planJson`이 문자열이면 `JSON.parse`(실패 시 null), `onPlanReady`와 동일 처리.
  - `statusName` → `status` 세팅(영문, `KNOWN_STATUSES` 검증).
- `pushTurn`의 seq dedup을 그대로 사용 → 직후 SSE replay와 중복 없이 합쳐진다.
- export에 `hydrate` 추가.

**(F2) `InterviewPanel` 마운트 시 스냅샷 + 비취소 닫기** — `components/InterviewPanel.vue`
- `onMounted`: `GET /api/interviews/${props.sessionId}` → `stream.hydrate(res)` → `stream.open(props.sessionId)` → `scrollToBottom('auto')`. 스냅샷 실패는 비치명적(스트림만으로 진행).
- **"나중에" 버튼**: 비종료(`!isTerminal`) 상태에서 표시, `closePanel()`(emit 'close') 호출. "대화 취소"는 그대로 유지(세션 종료). "나중에"는 세션을 건드리지 않고 다이얼로그만 닫는다. 라벨 예: "나중에 이어하기".

**(F3) `tasks/index.vue` 디스커버리 + 하이브리드 재오픈** — `pages/tasks/index.vue`
- `onMounted`: `GET /api/interviews/active` 호출.
  - 0개 → 무동작.
  - 1개 → `openInterview(list[0].id)`(=`interviewSessionId=id; dialogPhase='interview'; showCreate=true`).
  - 2~3개 → 선택 다이얼로그 표시(`resumeCandidates=list; showResumePicker=true`).
- **선택 다이얼로그**(신규 q-dialog): 각 항목에 제목/레포·브랜치/상태 라벨/최근 활동 시각 + "이어하기" 버튼. "닫기"로 무시 가능.
- 디스커버리 실패는 조용히 무시(콘솔/무동작) — 작업 목록 로드를 방해하지 않는다.

## 6. 테스트 전략

- **백엔드 (JUnit)**:
  - `InterviewSessionRepository`: `findActiveByRequester`가 비종료만, lastActivityAt DESC 정렬, 타 요청자 제외.
  - `InterviewService.listActiveForRequester`: 매핑·스코프.
  - `InterviewController`: `GET /api/interviews/active` 200 + 본인 스코프 + 미인증 401. (`InterviewApiIntegrationTest` 확장)
  - `InterviewResponse.of`에 `statusName` 단언 추가(`InterviewResponseTest`).
- **프론트 (Vitest)**:
  - `useInterviewStream.hydrate`: 스냅샷 → turns(내 답변 포함)/designSections/plan/status 매핑; 이어지는 replay가 seq dedup으로 중복 안 만듦.
  - `InterviewPanel`: 마운트 시 `GET /{id}` 호출 + hydrate + open 순서; 비종료에서 "나중에" 버튼 노출·emit 'close'; 종료 상태에선 미노출.
  - `tasks/index.vue`(가능 범위): 활성 1개 자동 오픈, 2~3개 선택 다이얼로그, 0개 무동작.

## 7. 범위 제외 (YAGNI)

- 항상 떠 있는 "이어하기" 배너(닫은 후 재진입은 새로고침 재발견으로 충분).
- 다른 탭 간 실시간 동기화.
- SSE 와이어 계약 변경, 워커 변경 — **없음**.

## 8. 위험 / 주의

- **단일 인스턴스 전제**: 라이브 SSE fan-out과 활성 조회는 API 단일 인스턴스 전제(기존 제약 동일). DB 기반 replay·스냅샷은 인스턴스 무관 동작.
- **자동 재오픈 빈도**: 활성 1개일 때 매 `/tasks` 로드마다 자동 오픈된다. "나중에" 버튼으로 즉시 닫을 수 있어 갇히지 않는다. (v1 수용; 거슬리면 후속에서 per-tab dismiss 가드 추가 가능.)
- **status 영문/한글 이원화**: `statusName`(영문, 로직)·`status`(한글, 표시) 둘 다 노출. 프론트는 `statusName`만 판정에 사용.
