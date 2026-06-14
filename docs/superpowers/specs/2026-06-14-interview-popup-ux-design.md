# 대화형 인터뷰 팝업 UI/UX 개선 — 설계

- 날짜: 2026-06-14
- 브랜치: `feat/interview-popup-ux` (← `feat/conversational-analysis`)
- 대상: `netisMaker/frontend` (대화형 분석 인터뷰 팝업)

## 1. 배경 & 목표

대화형 분석(conversational analysis) 인터뷰는 작업 등록 *전* 사용자가 AI와 대화하며 개발 계획을 만드는 단계다. 그 UI인 `components/InterviewPanel.vue`는 작동은 하지만 대화 경험이 거칠다:

- 새 메시지가 와도 대화창이 자동으로 내려가지 않아 사용자가 직접 스크롤해야 한다.
- AI 응답을 기다리는 동안 대화창에 아무 피드백이 없어 "멈춘 건지" 알기 어렵다.
- 취소가 밋밋한 텍스트 버튼("취소")이라 눈에 띄지 않고, 진행 중인 분석을 실수로 날릴 위험이 있다.
- 대화가 평면 목록(좌측 컬러 보더)이라 "채팅"으로 읽히지 않는다.

**목표:** 인터뷰 팝업을 메신저다운 대화 경험으로 끌어올린다. 구체적으로 ① 자동 스크롤(스마트), ② 응답 대기 애니메이션, ③ 명확한 취소 버튼 + 확인 단계, ④ 말풍선 기반 시각 리프레시.

**비목표(범위 밖):** SSE 와이어 계약, 인터뷰 상태머신, `useInterviewStream.ts`의 스트림 로직, 부모 2단계 다이얼로그(`pages/tasks/index.vue`)의 플로우는 **변경하지 않는다**. 우측 "설계·플랜" 컬럼은 **기능 불변**(폰트·간격 일관성만 정리).

## 2. 현재 구조 (변경 전)

- `pages/tasks/index.vue` — 등록 다이얼로그(`persistent`). Phase 1=폼, Phase 2=`<InterviewPanel>`. `@registered`→닫기+목록갱신, `@close`→닫기.
- `components/InterviewPanel.vue` — 분할 뷰. 좌: 대화 트랜스크립트(`turns`) + 답변 입력. 우: 설계 섹션 + 플랜 + "작업 등록". 상단: 상태바(스피너/배지/배너/취소).
- `composables/useInterviewStream.ts` — EventSource SSE. `turns`/`status`/`designSections`/`plan`/`connState`/`error` ref 노출.
- 상태(영문 enum): `QUEUED · RUNNING · AWAITING_INPUT · PLAN_READY · REGISTERED · CANCELLED · EXPIRED · FAILED`. 표시는 한글 라벨, 비교/터미널 판정은 영문 enum 이름으로만.

## 3. 확정된 결정 (브레인스토밍 결과)

| 항목 | 결정 |
|---|---|
| 범위 | **말풍선 풀 리프레시** + 기능 3종 |
| 대기 애니메이션 | **AI 말풍선 안 타이핑 점 3개** |
| 자동 스크롤 | **스마트 스크롤 + "새 메시지" 칩** |
| 취소 버튼 | **외곽선 "대화 취소" 버튼 + 확인 단계** |
| 구조 | **컴포넌트/컴포저블 분리** |

## 4. 아키텍처 (파일 분리)

`InterviewPanel.vue`는 오케스트레이션(스트림 구독 + 답변/취소/등록 + 자식 조립)만 담당하고, 표현·스크롤 로직을 작은 단위로 분리한다.

### 4.1 신규 `components/chat/ChatBubble.vue` (순수 프레젠테이션)
- **Props:** `{ role: 'assistant' | 'user' | 'system'; content: string }`
- **렌더:** 한 턴을 행으로. `assistant`=좌측(AI 아바타 + 회색 말풍선), `user`=우측(나 아바타 + 파란 말풍선·흰 글씨), `system`=가운데 작은 노트 칩.
- 본문 `white-space: pre-wrap` 유지. 내부 상태 없음.
- **의존성:** 없음(스타일만). 부모가 `turns`를 순회하며 턴 1개당 1개 렌더.

### 4.2 신규 `components/chat/TypingIndicator.vue`
- **Props:** 없음(또는 선택 `label`). 기본은 AI 아바타 + 회색 말풍선 안 점 3개.
- 점 3개 통통 애니메이션(CSS `@keyframes`). `aria-label="AI가 응답을 준비 중"`, `role="status"`.
- `@media (prefers-reduced-motion: reduce)`에서 애니메이션 정지(정적 점 표시).
- **의존성:** 없음.

### 4.3 신규 `composables/useAutoScroll.ts`
- **시그니처:** `useAutoScroll(scrollEl: Ref<HTMLElement | null>, opts?: { threshold?: number })` (기본 threshold=80px)
- **반환:** `{ nearBottom: Ref<boolean>; unread: Ref<number>; onScroll: () => void; scrollToBottom: (behavior?: ScrollBehavior) => void; notifyNewContent: (opts?: { force?: boolean }) => void }`
- **로직:**
  - `onScroll()` — 컨테이너 `@scroll`에 바인딩. `nearBottom = (scrollHeight - scrollTop - clientHeight) <= threshold`. nearBottom이면 `unread=0`.
  - `scrollToBottom(behavior='smooth')` — `scrollTop = scrollHeight`, `unread=0`, `nearBottom=true`.
  - `notifyNewContent({ force })` — 새 콘텐츠 추가 직후(부모가 `nextTick`에서 호출) `force || nearBottom`이면 `scrollToBottom('smooth')`, 아니면 `unread++`.
- **테스트성:** 가짜 el(`{scrollHeight, scrollTop, clientHeight}`)을 주입해 threshold/unread 분기를 순수 단위 테스트.

### 4.4 `components/InterviewPanel.vue` (오케스트레이션)
변경: 트랜스크립트를 `<ChatBubble>` 루프 + `<TypingIndicator>`로 교체, `useAutoScroll` 연결, 취소 확인 `<q-dialog>` 추가, 종료 시 "닫기" 버튼 추가. 답변/등록/취소 비즈니스 로직과 SSE 구독은 그대로.

## 5. 기능 상세

### 5.1 말풍선 리프레시 (좌측 대화 컬럼)
- `turns`를 순회하며 턴마다 `<ChatBubble :role="t.role" :content="t.content" />`.
- 빈 상태(턴 0개·비종료): `<TypingIndicator>` + "첫 질문을 준비 중입니다" 캡션.
- 우측 설계·플랜 컬럼, 좁은 화면 탭(`lt-md`) 폴백은 구조 유지.

### 5.2 응답 대기 애니메이션 (타이핑 점 3개)
- 트랜스크립트 하단에 `<TypingIndicator v-if="waitingForAi">` 렌더.
- **표시 조건** `waitingForAi`(computed):
  ```
  !isTerminal && (
    status === 'QUEUED' || status === 'RUNNING' ||
    (turns.length === 0 && connState !== 'closed' && connState !== 'idle')
  )
  ```
  → `AWAITING_INPUT`(입력 차례)·`PLAN_READY`·종료 상태에서는 숨김.
- 답변 전송 시 낙관적으로 `status='QUEUED'`가 되므로(아래 5.4 흐름 유지) 전송 직후 자연스럽게 점이 뜨고, 다음 질문 도착 시 `AWAITING_INPUT`으로 사라진다.

### 5.3 스마트 자동 스크롤 + "새 메시지" 칩
- 트랜스크립트 컨테이너에 `ref="transcriptEl"` + `@scroll="onScroll"`.
- `useAutoScroll(transcriptEl)` 사용. `turns.length`와 `waitingForAi`를 watch → `nextTick` → `notifyNewContent()`.
- **내가 보낸 답변**(낙관적 추가)일 때는 `notifyNewContent({ force: true })`로 항상 하단 이동.
- 칩: `<div v-if="unread > 0" class="new-msg-pill" @click="scrollToBottom()">↓ 새 메시지 {{ unread }}</div>` — 하단 중앙 floating.
- `onMounted`에서 초기 1회 `scrollToBottom('auto')`로 하단 정렬(리플레이로 과거 턴이 한꺼번에 올 때 대비).

### 5.4 취소 버튼 → "대화 취소" + 확인 단계
- 상태바의 텍스트 취소 → **외곽선 버튼**: `<q-btn data-test="cancel-interview" outline icon=stop_circle label="대화 취소">` (평소 회색, hover 빨강). `v-if="!isTerminal"`.
- 클릭 → 로컬 `showCancelConfirm=true` (imperative `$q.dialog` 대신 **템플릿 `<q-dialog v-model="showCancelConfirm">`**, 테스트 용이):
  - 제목: "진행 중인 분석을 취소할까요?"
  - 본문: "지금까지의 대화와 분석 진행 상황이 사라집니다. 작업은 등록되지 않습니다."
  - 버튼: `data-test="cancel-keep"` "계속하기"(닫기) / `data-test="cancel-confirm"` "취소하기"(negative) → 기존 `cancelInterview()` 실행.
- `cancelInterview()` 본문은 현행 유지(`/cancel` POST → `status='CANCELLED'` → `stream.close()` → `emit('close')`).

### 5.5 버그 수정 — 종료 상태에서 다이얼로그를 닫을 수 없음
- 현재: 다이얼로그가 `persistent`이고 취소 버튼이 `v-if="!isTerminal"`이라, SSE로 `EXPIRED`/`FAILED`가 도착하면 닫을 수단이 사라진다.
- 수정: `<q-btn v-if="isTerminal && status !== 'REGISTERED'" data-test="close-interview" flat label="닫기" @click="emit('close')">`를 상태바에 노출(REGISTERED는 `register()`가 자동으로 닫음).

## 6. 비주얼 스펙 (Quasar 기본 primary `#1976D2`)

| 요소 | 값 |
|---|---|
| AI 말풍선 | bg `#eef2f8`, text `#25303f`, radius 14px(꼬리쪽 4px), 좌측 |
| 나 말풍선 | bg `#1976D2`, text `#fff`, radius 14px(꼬리쪽 4px), 우측 |
| AI 아바타 | 26px 원, bg `#E3F2FD`, text `#1565c0`, "AI" |
| 나 아바타 | 26px 원, bg `#ECEFF3`, text `#5b6b7d`, "나" |
| 시스템 노트 | 가운데, bg `#f0f2f5`, text `#8a97a8`, 11px |
| 말풍선 | padding 9px 12px, font 12.5–13px, max-width 80%, `pre-wrap` |
| 타이핑 점 | 7px 원, color `#7f8ea3`, bounce 1.2s(delay .18/.36) |
| 새 메시지 칩 | 하단중앙, bg `#1976D2`, white, radius 16px, shadow |
| 대화 취소 버튼 | text `#6b7787`/border `#d4dae2`, hover `#c0392b`/bg `#fdf3f2` |
| 스크롤 threshold | 80px |

스타일은 `InterviewPanel.vue`/자식 컴포넌트의 `<style scoped>`로. 폰트는 기존 전역(`-apple-system, Pretendard, Noto Sans KR …`) 상속.

## 7. 접근성
- `TypingIndicator`: `role="status"`, `aria-label="AI가 응답을 준비 중"`.
- 트랜스크립트 컨테이너: `aria-live="polite"`로 새 메시지 낭독.
- `prefers-reduced-motion: reduce` 시 타이핑 점·칩 floating 애니메이션 정지(정적 표시), 스크롤은 `behavior:'auto'`로.

## 8. 테스트

**기존 `components/InterviewPanel.spec.ts` 그린 보장** — 다음 훅/텍스트를 모두 유지: `textarea`, `[data-test="send-answer"]`, `[data-test="register"]`, 한글 배지 라벨("입력 대기" 등), 배너 텍스트("만료"/"인터뷰 실패"), 설계 섹션 `check_circle`, 답변 낙관적 추가. (말풍선 전환 후에도 `w.text()` 기반 단언은 동일하게 통과해야 함 — 텍스트 콘텐츠 보존.)

**신규 테스트**
- `components/chat/TypingIndicator.spec.ts` — 렌더 + `aria-label`.
- `composables/useAutoScroll.spec.ts` — 가짜 el로 nearBottom threshold 경계, `notifyNewContent` 분기(near→scroll, far→unread++, force→scroll), `scrollToBottom`이 unread 리셋.
- `InterviewPanel.spec.ts` 보강:
  - `waitingForAi`: `status='RUNNING'`/`'QUEUED'`에서 타이핑 표시 노출, `AWAITING_INPUT`·`PLAN_READY`·`EXPIRED`에서 숨김.
  - 취소 확인: `[data-test="cancel-interview"]` 클릭 → 확인 다이얼로그 노출 → `[data-test="cancel-confirm"]` 클릭 시 `/cancel` POST 호출 + `close` emit. `[data-test="cancel-keep"]`는 API 미호출.
  - 종료 닫기: `status='FAILED'`에서 `[data-test="close-interview"]` 클릭 시 `close` emit.

테스트 환경: `test/setup.ts`가 Quasar(Notify/Dialog/Loading 포함)와 전 컴포넌트를 전역 등록 → `<q-dialog>`/`$q`는 실제 동작. 새 컴포넌트(`ChatBubble`/`TypingIndicator`)는 전역 등록 대상이 아니므로 `InterviewPanel`에서 정식 import.

## 9. 변경 파일 목록

| 파일 | 변경 |
|---|---|
| `components/chat/ChatBubble.vue` | **신규** — 턴 1개 말풍선 |
| `components/chat/TypingIndicator.vue` | **신규** — 타이핑 점 3개 |
| `composables/useAutoScroll.ts` | **신규** — 스마트 스크롤 로직 |
| `components/InterviewPanel.vue` | 트랜스크립트 말풍선화 + 타이핑/스크롤/취소확인/종료닫기 배선 |
| `composables/useAutoScroll.spec.ts` | **신규** 단위 테스트 |
| `components/chat/TypingIndicator.spec.ts` | **신규** 단위 테스트 |
| `components/InterviewPanel.spec.ts` | 신규 케이스 보강(기존 유지) |

`pages/tasks/index.vue`, `useInterviewStream.ts`, 백엔드/SSE는 변경 없음.

## 10. 미해결 질문
없음(브레인스토밍에서 4개 시각 결정 + 파일 분리 모두 확정).
