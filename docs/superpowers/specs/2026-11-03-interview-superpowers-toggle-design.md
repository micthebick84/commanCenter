# 작업 인터뷰 — superpowers 켜기/끄기(간단 인터뷰) 설계

- 날짜: 2026-11-03
- 상태: 초안 — 사용자 검토 대기 (brainstorming: 동기 확인 → 사용자가 방식 결정을 위임 → 제안 1건 확인, 아래 §3)
- 범위: 인터뷰 서비스(Node, Agent SDK) + API(승인·claim) + 프론트(승인 다이얼로그·상세). 워커는 바꾸지 않는다.
- 선행: `2026-11-03-task-type-db-write-design.md`(작업 타입)를 먼저 구현한다. 기본값이 타입(DB 작업)에 따라 정해지기 때문이다. 마이그레이션은 그다음 번호인 **V40**이다. (Claude 판단)
- 표기: "(Claude 판단)"은 사용자가 정하지 않았고 설계자가 정한 것이다. 이 문서의 방식 결정은 사용자가 위임해 전부 Claude 판단이며, 사용자는 §3 전체를 확인했다.

## 1. 목표

DB 작업처럼 등록 설명만으로 할 일이 거의 정해지는 간단한 작업에서 superpowers brainstorming 인터뷰를 건너뛸 수 있게 한다. 지금 인터뷰는 한 번에 한 질문씩 여러 턴을 주고받기 때문에, 간단한 작업에는 과하다(사용자 동기).

관리자가 작업마다 **superpowers 인터뷰 켬/끔**을 고른다. 끄면 **간단 인터뷰**로 진행한다.
- 첫 턴에 자료를 보고, 모호하지 않으면 바로 계획을 낸다.
- 꼭 물어야 할 게 있으면 질문을 한 번에 모아 딱 한 번만 묻고, 답을 받으면 계획을 낸다.

## 2. 배경 — 현재 동작 (PR #122 head `c87f82d` 기준)

- **플러그인**: INTERVIEW는 superpowers 하나를 로컬 플러그인으로 붙인다.
  - `plugins: question ? [] : [{type:'local', path}]`이다(`netismaker-interview-service/src/sdk/sessionOptions.ts:120`).
  - `allowedTools`에 `'Skill'`을 사전 승인한다(`:128-139`). QUESTION은 플러그인이 없다.
- **경로 설정**: 플러그인 경로는 env `SUPERPOWERS_PLUGIN_PATH`(필수, `src/config.ts:89`)이고 모든 세션이 공유한다.
- **kickoff**(`src/runner/interviewRunner.ts:150-176`)
  - 첫 문장은 "I want to add a feature…"다.
  - "PLANNING-ONLY … (via brainstorming → writing-plans)"와 정규 계획 형식 안내가 들어 있다.
  - 끝은 "Use the brainstorming skill: … ask me one clarifying question at a time."로 끝난다.
  - 두 번째 턴부터는 답변 본문만 보낸다(`:174`).
- **계획 완료 판정은 플러그인과 무관하다.** 헤딩 정규식(`planHarvest.ts`)으로 판정한다. 정규 형식 재요청 프롬프트는 `buildPlanReformatSplice`(`skillDispatch.ts:39-47`)다.
- **예외**: 핸드오프 보정 `buildWritingPlansSplice`(`skillDispatch.ts:53-`)는 플러그인 폴더의 `skills/writing-plans/SKILL.md`를 직접 읽어 같은 세션에 붙인다(`interviewRunner.ts:633-690` 부근).
- `buildOptions` 호출은 4곳이다(`interviewRunner.ts:587,643,689,784`).
- 작업 인터뷰는 관리자 승인(`TaskService.startInterview`) 때 만들어진다(`InterviewService.createForTask`). 모델·effort·MCP·DB·참조 repo도 이때 정한다.
- 워커는 평소 플러그인을 로드하지 않는다(`--setting-sources project`). 구현 방식이 SDD일 때만 `--plugin-dir`을 붙인다.

## 3. 결정 (사용자 확인, 2026-11-03)

| 결정 | 내용 |
|---|---|
| 고르는 곳 | 관리자가 **승인 다이얼로그**에서 작업마다 고른다(모델·MCP·DB와 같은 자리) |
| 기본값 | 타입이 **DB 작업이면 끔**, 그 밖의 타입과 타입 없음은 켬. 다이얼로그가 미리 고르고 관리자가 바꿀 수 있다. 이미 있는 작업은 켬이다 |
| 단위 | 작업당 켬/끔 하나. 스킬별 선택은 하지 않는다. SDK 0.2.117에 메인 세션 스킬 허용 목록이 없고, 스킬끼리의 참조(brainstorming → writing-plans)가 깨지기 때문이다 |
| 끄면 | 간단 인터뷰. 첫 턴에 등록 설명·첨부·코드·DB(읽기)를 살펴본다. 모호하지 않으면 질문 없이 바로 계획을 낸다. 꼭 필요하면 질문을 **한 번에 모아 한 번만** 묻고, 답을 받으면 계획을 낸다 |
| 계획 | 지금과 같은 정규 형식이다. 구현 단계가 그대로 읽는다. 계획이 나온 뒤에는 지금처럼 관리자가 "구현 진행"을 누르거나 답변으로 고쳐 달라고 할 수 있다 |
| 바꾸지 않는 것 | 구현 단계의 SDD 선택(인터뷰를 꺼도 SDD 구현 가능), 질문 세션(원래 superpowers 없음) |

## 4. 저장·입력 (API)

- **저장**
  - `com.interview_session.superpowers_enabled BOOLEAN NOT NULL DEFAULT true`(V40).
  - 세션 단위로 저장하는 이유: 승인 때마다 세션이 새로 만들어지고, 인터뷰 실패 뒤 다시 승인하면 다시 고를 수 있기 때문이다. 기존 세션과 질문 세션은 true로 둔다(질문 세션은 러너가 이 값을 보지 않는다). (Claude 판단)
- **입력**
  - `ApproveRequest.superpowersEnabled`(Boolean, 선택)를 추가한다. null이면 true(옛 화면·다른 호출자는 지금과 같음).
  - `TaskService.startInterview` → `InterviewService.createForTask`에서 세션에 저장한다.
  - 끔일 때만 이력 `인터뷰 방식: 간단(superpowers 끔)`을 남긴다.
- **claim**: `InterviewClaimResponse.superpowersEnabled`(Boolean)를 추가한다. 인터뷰 서비스 `types.ts`에는 optional로 두고, 없거나 true면 지금과 같다.
- **응답**: 작업 상세에서 보여 주기 위해 `TaskResponse`에 지금 인터뷰 세션의 `interviewSuperpowersEnabled`(Boolean, 세션이 없으면 null)를 싣는다. 값을 어디서 가져올지는 빌더가 정한다(이미 세션을 조회하는 곳이 있으면 그곳).

## 5. 간단 인터뷰 (인터뷰 서비스)

`claim.superpowersEnabled === false`이고 INTERVIEW일 때만 아래가 적용된다. 그 밖에는 지금과 같다.

1. **플러그인과 Skill**: `buildOptions`에 `superpowers: boolean` 입력을 더한다. 끔이면 `plugins: []`로 두고 `allowedTools`에서 `'Skill'`을 뺀다. 나머지(Read·Grep·Glob·MCP 사전 승인, INTERVIEW 게이트, settingSources 격리)는 그대로 둔다. 호출 4곳이 같은 값을 쓰게 한다.
2. **kickoff**
   - 첫 문장(작업 타입별 문구, 작업 타입 설계 §5.1)과 첨부 절, PLANNING-ONLY 금지 사항, 정규 형식 안내는 공통으로 쓴다.
   - "(via brainstorming → writing-plans)"와 마지막 "Use the brainstorming skill…" 줄만 간단 인터뷰 지시로 바꾼다. 지시 요지:
     - "superpowers 스킬은 없다. 먼저 등록 설명·첨부·레포 코드·(있으면) DB를 읽기 도구로 살펴라."
     - "할 일과 범위가 분명하면 질문하지 말고 바로 정규 형식의 계획을 내라."
     - "꼭 사람이 정해야 하는 것이 있을 때만, 그 질문들을 한 메시지에 번호를 붙여 모두 담아 한 번만 물어라. 추측으로 정할 수 있는 것은 묻지 말고 계획에 '가정'으로 적어라."
3. **답변 뒤 턴**: 끔이면 resume 프롬프트 앞에 다음 줄을 붙인다. "이 답변으로 질문은 끝입니다. 더 묻지 말고 이 답을 반영해 정규 형식의 최종 계획을 내세요." 계획 형식은 `buildPlanReformatSplice`와 같은 문구를 재사용한다.
4. **마무리 보정**
   - 끔인 세션에서 답변 뒤 턴이 계획 없이 다시 질문으로 끝나면, 러너가 같은 세션에서 `buildPlanReformatSplice()`로 한 번 더 요청한다(지금 near-miss·force-finish 경로와 같은 방식).
   - 그래도 계획이 없으면 지금처럼 질문으로 올린다. 사람이 답하면 3번 규칙이 다시 적용된다.
   - 끔에서는 `buildWritingPlansSplice`(writing-plans SKILL.md 주입)를 절대 쓰지 않는다. 플러그인을 붙이지 않았는데 그 스킬 본문을 붙이는 것은 모순이다. (Claude 판단)
5. **첨부 안내**(`interviewRunner.ts:136` 부근)에 brainstorming을 전제한 말이 있으면 끔에서는 빼거나 중립 문구로 바꾼다.

## 6. 화면 (프론트)

- **승인 다이얼로그**(`ApproveDialog.vue`)
  - 모델·추론 아래에 토글 "superpowers 인터뷰"(켬/끔)를 둔다. 끔일 때 보조 문구는 "간단 인터뷰 — 질문을 최대 한 번 모아서 묻고 바로 계획"이다.
  - 기본값은 작업 타입이 DB 작업이면 끔, 아니면 켬이다.
  - 다시 승인할 때 "지난 인터뷰 프리필"이 있으면 지난 세션 값을 쓴다.
  - 바디에 `superpowersEnabled`를 싣는다.
- **작업 상세**(데스크톱 `[id].vue`·모바일 `TaskDetailMobile.vue`): 인터뷰 정보 줄에 `간단 인터뷰` 칩을 단다. 끔일 때만 보인다.
- hydration-smoke 목: 바뀐 응답 필드가 있으면 목에 넣는다.

## 7. 배포 순서

1. **인터뷰 서비스**: 새 claim 필드가 없으면 지금처럼 켬으로 동작한다. 구 API와도 맞물린다.
2. **API**: V40, 승인·claim·응답 필드.
3. **프론트**: `.output`을 재빌드한다.

인터뷰 서비스가 먼저인 이유: 구버전 러너는 새 필드를 버리고 superpowers를 계속 로드하므로 "끔"이 조용히 무시된다. 질문 세션을 도입할 때의 "인터뷰 서비스 먼저" 규칙과 같다. 워커는 바꾸지 않는다.

## 8. 빌더에게 맡기는 것

- 간단 인터뷰 지시문의 정확한 문장. §5의 요지를 지키고, 기존 kickoff처럼 영어·한국어를 섞어도 된다.
- `buildOptions` 입력 이름과 시험 구성. `sessionOptions.test.ts`에 끔·켬·QUESTION 세 경우를 가드로 넣는다.
- 마무리 보정(§5-4)을 기존 force-finish 코드에 끼우는 위치.
- 응답 필드를 채우는 조회 위치(§4).

## 9. 비목표 · 반목표

- **비목표**
  - 스킬별 선택.
  - 인터뷰 도중 켬/끔 변경.
  - 등록자가 등록 때 고르기.
  - 구현·디자인·배포·종료 단계의 superpowers 제어.
  - 질문 세션에 superpowers 켜기.
  - 인터뷰를 아예 건너뛰고 바로 구현하기.
- **반목표**(기술적으로 동작해도 실패인 것)
  - 끔인데 brainstorming이 돌아 질문을 여러 번 하는 것.
  - 끔인데 계획이 정규 형식이 아니어서 인터뷰가 끝나지 않는 것.
  - 켬인 작업(기본·기존)의 동작이 조금이라도 바뀌는 것.

## 10. 위험

- **간단 인터뷰 계획의 품질은 실측한 적이 없다.** brainstorming 없이 바로 쓴 계획이 범위를 놓칠 수 있다. 첫 몇 건은 관리자가 계획을 보고 "구현 진행" 전에 답변으로 보완한다. 배포 뒤 DB 작업 2~3건으로 확인한다.
- **목업 보기 기능(별도 요청 3번)과의 관계**: 목업 보기는 brainstorming의 visual companion을 대신하는 기능이라, 끔인 인터뷰에서는 목업이 나오지 않는다. 간단 인터뷰의 의도와 맞다.
- **SessionStart 훅**: 플러그인을 붙이면 superpowers의 SessionStart 훅이 using-superpowers 내용을 주입할 수 있다. 끔에서는 플러그인 자체를 붙이지 않으므로 해당 없다.
