# 디자인 단계에서 Claude Design 걷어내기 + 목업 크게 보기 — 설계

날짜: 2026-10-31 · 원 스펙: `2026-07-19-claude-design-phase-design.md`

## 1. 왜

독립형 Claude Design 사이트(claude.ai/design)가 **2026-12-14에 닫히고** 그 뒤로는 읽기 전용이 된다([공식 이전 안내](https://support.claude.com/en/articles/17440474-migrate-from-standalone-claude-design-to-claude)). Claude Design 자체는 Claude 안의 아티팩트(Design·Design System 타입)로 옮겨 가지만, 디자인 단계 프롬프트가 쓰는 `DesignSync` 도구와 `/design-login`은 닫히는 독립형 사이트를 대상으로 한다.

디자인 단계가 Claude Design에 기대는 곳은 프롬프트 두 단계뿐이다 — ① 디자인 시스템 pull(DesignSync), ⑤ 목업 업로드(DesignSync `create_project`·`write_files`). 목업 HTML·DESIGN.md는 DB(`com.task_design`)에 저장되고 작업 상세 카드가 iframe으로 그리므로, 업로드가 없어져도 단계는 돈다. 잃는 것은 카드의 'Claude Design에서 열기' 링크와 PR 본문의 목업 링크다. 대신 사람은 600px 높이 iframe으로만 목업을 보게 된다.

아티팩트로 옮기는 안은 버렸다(2026-10-31 확인):
- 워커의 headless `claude -p`에는 Artifact 도구가 없다. 공식 문서상 로컬 `claude -p`에서 기본으로 꺼져 있고 켜는 공식 방법이 없다(이슈 anthropics/claude-code#80606). 이 PC(claude.ai 로그인)·10.1.1.75(`setup-token` 토큰) 둘 다 워커와 같은 조건에서 도구가 없었다.
- 올려도 아티팩트는 기본이 올린 계정만 보기이고 공유 범위는 브라우저 UI에서만 바꾼다 — 등록자·PR 리뷰어가 링크를 못 연다.

실사용 기록: 라이브 DB에 디자인을 켠 작업 0건, `task_design` 0행, 레포 카탈로그 `design_system_project_id`·`design_output_project_id` 전부 NULL(2026-10-31 조회).

## 2. 목표 / 비목표

목표
- 디자인 단계가 Claude Design·DesignSync 없이 돈다(독립형 사이트가 닫혀도 시간 낭비·실패 없음).
- 관리자가 PC 큰 화면에서 목업을 **브라우저 새 탭에 창 크기 그대로** 본다 — 승인된 디자인을 실제 구현 화면과 창 두 개로 나란히 놓고 비교할 수 있게.
- 실제와 다른 화면 문구 2곳을 바로잡는다(§4-5).

비목표
- 아티팩트 게시. Anthropic이 `claude -p`에서 Artifact 도구를 열면 그때 따로 본다.
- 디자인 시스템 연결의 대체(아티팩트 Design System 읽기 등). 지금도 쓰이지 않는다.
- DB 컬럼 삭제·마이그레이션. `task_design.design_project_id`·`design_url`, `repo_catalog.design_system_project_id`·`design_output_project_id`는 그대로 두고 값만 안 쓴다.
- 목업과 실제 화면을 한 화면에 나란히 그리는 비교 기능, 승인·반려 흐름 변경, 목업 내려받기.
- API 동작 변경. API는 다시 띄우지 않아도 되게 한다(주석만 바뀌는 것은 괜찮다).

실패로 보는 것(기술적으로 돌아가도)
- 크게 보기 화면에서 목업 HTML이 Symphony 출처로 실행되는 것 — 생성된 HTML은 프롬프트 인젝션을 담을 수 있고 로그인 토큰이 localStorage에 있다. 지금 카드처럼 격리된 틀(`sandbox`에 `allow-same-origin` 없음) 안에서만 돌아야 한다. 같은 이유로 blob URL·`window.open` 후 `document.write` 같은 방식은 쓰지 않는다.
- 크게 보기가 목업을 창 폭보다 좁은 고정 폭이나 고정 높이로 그리는 것.

## 3. 사용자가 정한 것

- Claude Design 의존을 걷어내고, 아티팩트 대신 Symphony 안에서 크게 보기로 간다.
- 목업은 PC 큰 화면에서 본다.
- 크게 보기는 **브라우저 새 탭**(A안). 윗줄에 작업 번호·제목, 화면 탭, '작업으로 돌아가기'가 있고 그 아래를 목업이 채운다. 승인·반려는 원래 탭의 카드에서 한다.

## 4. 바뀌는 곳

### 4-1. 워커 — 디자인 프롬프트 (`application-worker.yml` `design-prompt-template`)
- ① DesignSync pull 단계를 뺀다. ②(레포의 기존 UI 관례 파악)가 스타일 기준이 된다 — "디자인 시스템이 없으면 범용 스타일" 문구는 "레포 관례를 따르고, 관례가 없으면 깔끔한 범용 스타일"로.
- ⑤ Claude Design 업로드 단계를 뺀다.
- ⑥ `result.json`은 `{"screens":[{"path","title"}]}`만 쓰게 한다(`designProjectId`·`designUrl` 제거). 화면 제목을 카드 탭에 쓰므로 `result.json`은 남긴다.
- ③(목업 생성)에 폭 지시를 한 줄 더한다: 목업은 기기 프레임·고정 폭 캔버스 없이, 대상 앱이 브라우저에서 실제로 그려지는 것처럼 창 폭에 맞춰 늘어나게 만든다(레포에 최대 폭·반응형 관례가 있으면 그대로 따른다). 크게 보기로 구현 화면과 나란히 비교하려면 목업이 창 폭을 따라야 하기 때문이다. 대상 레포가 모두 PC 웹(대시보드 등)이라 일률 지시로 충분하다. *(구현자 판단)*
- `ERROR.txt` 규칙은 DesignSync 인증 실패에만 쓰였으므로 함께 뺀다(수확기는 `ERROR.txt`가 있으면 실패로 보는 동작을 그대로 둔다 — 남겨도 해가 없다).
- 템플릿 위 주석(`DesignSync로 디자인 시스템 pull + 목업 생성 + Claude Design 업로드`)을 고친다.
- `WorkerMainLoop.renderDesignPrompt`에서 템플릿에서 사라진 자리(`{design_system_project_id}`·`{design_output_project_id}`, 그리고 ⑤에서만 쓰던 `{github_repo}`·`{task_id}`)의 치환을 정리한다. claim DTO의 두 필드(`designSystemProjectId`·`designOutputProjectId`)는 API가 계속 보내므로 DTO에서는 지우지 않는다. *(구현자 판단 — API를 건드리지 않기 위해)*

### 4-2. 워커 — PR/MR 본문 (`WorkerMainLoop.renderPrBody`)
- 디자인 줄에서 `[Claude Design 목업](url)` 링크를 뺀다. 줄은 `- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #N 디자인 카드 참고)` 꼴. 워커는 Symphony 공개 주소를 모르므로 링크는 걸지 않는다. *(구현자 판단)*

### 4-3. 워커 — 결과 수확 (`DesignResultHarvester`)
- 동작은 그대로 둔다. `result.json`의 `designProjectId`·`designUrl`이 없으면 지금도 null로 보고된다. 클래스 주석의 `result.json` 형식만 새 형식으로. *(구현자 판단 — 바꿀 이유가 없다)*

### 4-4. 프론트 — 디자인 카드 (`components/DesignReviewCard.vue`)
- 'Claude Design에서 열기' 버튼과 '업로드 실패 — 로컬 미리보기만' 칩을 없앤다(예전 행에 `designUrl`이 있어도 보이지 않는다 — 라이브에 그런 행은 없다).
- '크게 보기' 버튼을 넣는다(아이콘 + 글자, 목업이 1개 이상일 때만). 누르면 **새 탭**으로 크게 보기 화면을 연다 — 지금 카드에서 고른 화면 탭이 그대로 열린다. 일반 링크(`target="_blank"`, `rel="noopener"`)로 연다.
- 버튼은 화면 폭과 상관없이 보인다(폰에서도 새 탭으로 열릴 뿐 해가 없다). *(구현자 판단)*
- 카드 안 iframe 미리보기·탭·DESIGN.md·반려 이력·승인/반려는 그대로.

### 4-5. 프론트 — 크게 보기 화면 (새 페이지)
- 주소: `/tasks/{id}` 상세 페이지 파일(`pages/tasks/[id].vue`)을 옮기거나 중첩 라우트로 만들지 않는 경로로 한다 — Nuxt에서 `pages/tasks/[id]/design.vue`를 그냥 두면 `[id].vue`가 부모가 된다. 예: `pages/designs/[id].vue` → `/designs/12?screen=1`. 화면 번호는 쿼리 `screen`(1부터). 없거나 범위를 벗어나면 첫 화면. 탭을 바꾸면 쿼리를 `replace`로 갱신해 새로고침해도 같은 화면이 열린다. *(구현자 판단 — 사용자에게는 `/tasks/{id}/design`으로 설명했으나 상세 페이지를 건드리지 않는 쪽을 택함)*
- 레이아웃: Symphony 툴바 없이 얇은 윗줄 + 목업. 기존 `layouts/blank.vue`를 쓰고 그 주석("로그인 전용")을 고친다.
- 윗줄: `#{id} {제목} · 디자인`, 화면 탭(목업 `title`), 오른쪽에 '작업으로 돌아가기'(`/tasks/{id}`로 이동하는 링크). 반려 횟수·승인 여부 칩은 넣지 않는다. *(구현자 판단 — 최소)*
- 목업: 윗줄 아래 남은 창 영역 전체(폭 100%, 높이 = 창 높이 − 윗줄)를 iframe 하나가 채운다. 페이지 자체는 스크롤되지 않고 목업 안에서 스크롤된다. iframe은 카드와 같은 `srcdoc` + `sandbox="allow-scripts"`(그 외 sandbox 토큰 없음). 흰 배경.
- 데이터: `GET /api/tasks/{id}`의 `design.mockupFilesJson`. 상세 페이지와 같은 권한(등록자·관리자)을 그대로 따른다 — 서버가 403/404를 주면 그 메시지와 '작업으로 돌아가기'를 보여 준다. 디자인이 없거나 목업 JSON을 읽지 못하면 "이 작업에는 아직 디자인 목업이 없습니다" + 돌아가기.
- 로그인·SSR: 다른 보호 페이지와 같이 전역 미들웨어가 로그인으로 보낸다. 템플릿의 인증 분기는 `useHydrationSafeAuth()` 규칙을 따른다(CLAUDE.md "SSR 하이드레이션"). 목업 iframe은 하이드레이션 뒤(클라이언트)에서만 그려도 된다.
- 탭 제목(`<title>`): `#{id} 디자인 — Symphony` 꼴.
- 자동 갱신은 하지 않는다 — 탭을 열어 둔 채 반려·재생성이 일어나면 새로고침으로 새 목업을 본다(재생성은 수 분 걸리고, 승인·반려는 원래 탭에서 하므로). *(구현자 판단)*

### 4-6. 프론트 — 실제와 다른 문구 2곳 (`components/InterviewPanel.vue`)
- '디자인 단계 포함' 토글 툴팁: `분석 승인 후 화면 목업을 생성해 승인받습니다` → 실제 시점(플랜 확정 = '구현 진행')에 맞게, 예: `구현 전에 화면 목업을 먼저 만들어 승인받습니다`.
- 확정 알림: 디자인을 켜고 확정했으면 `확정 완료 — 디자인 큐에 진입했습니다`, 끄고 확정했으면 지금처럼 `확정 완료 — 구현 큐에 진입했습니다`.

### 4-7. 설정·문서
- `application.yml`의 디자인 stale 판정 주석(`DesignSync 업로드` 언급)을 고친다 — 주석만.
- 원 스펙 `2026-07-19-claude-design-phase-design.md` 맨 위에 "Claude Design 업로드·디자인 시스템 pull은 2026-10-31 이 스펙으로 대체됨" 한 줄.
- `CLAUDE.md`: 디자인 단계가 Claude Design 없이 돈다는 것, 크게 보기 페이지(경로·격리 규칙), 배포 순서를 짧게. 자주 보는 코드 표에 크게 보기 페이지 추가.

## 5. 테스트

- 워커: 디자인 프롬프트 렌더 결과에 `DesignSync`·`Claude Design`·`design-login`·사라진 치환 자리(`{design_system_project_id}` 등)가 남지 않고, ③의 창 폭 지시가 들어 있다. PR 본문 디자인 줄에 링크가 없고 작업 번호 안내가 있다(`designUrl`이 있는 입력이어도). 기존 수확기·결과 테스트는 그대로 통과.
- 프론트 단위(vitest):
  - 카드: 'Claude Design에서 열기'·'업로드 실패' 칩이 없다. '크게 보기'가 목업이 있을 때만 보이고, 고른 탭에 맞는 주소·`target="_blank"`를 가진다.
  - 크게 보기 페이지: 쿼리 `screen`에 맞는 탭·iframe `srcdoc`, 범위 밖이면 첫 화면, 탭 바꾸면 쿼리 갱신, iframe `sandbox`가 정확히 `allow-scripts`, 디자인 없음·403 안내, 돌아가기 링크.
  - InterviewPanel: 디자인 켜고 확정 → '디자인 큐' 알림, 끄고 확정 → '구현 큐' 알림.
- 하이드레이션 스모크(`frontend/scripts/hydration-smoke.mjs`): 새 경로를 로그인 경로 목록에 넣고, 목업이 있는 디자인을 가진 스모크 작업(또는 기존 작업에 `design`)과 그 API 목을 더한다. CI frontend 잡에서 경고 0건.

## 6. 배포

1. 워커(이 PC): 진행 중인 디자인·구현이 없을 때 재기동 — 새 프롬프트·PR 본문.
2. 원격 워커(10.1.1.75, Mac 10.1.3.6 복구 뒤): jar 갱신 — 둘 다 DESIGN을 받으므로, 갱신 전에는 예전 프롬프트(업로드 시도, 비치명)가 돈다. 12-14 전에 끝내면 된다.
3. 프론트: `.output` 재빌드.
4. API: 재기동 불필요(주석만 바뀜).

순서가 바뀌어도 깨지지 않는다 — 새 화면은 예전 워커가 만든 목업도 그대로 보여 주고, 새 워커의 결과는 예전 화면에서 '업로드 실패' 칩만 뜬다.
