# Claude Design 연동 디자인 구간 — 설계

날짜: 2026-07-19
상태: 사용자 승인 대기

## 목적

UI 작업에 대해 **분석 승인 후 → 구현 전**에 디자인 구간을 추가한다. 워커가 조직 디자인 시스템(Claude Design)에 맞는 화면 목업을 자동 생성하고, 사용자가 netisMaker에서 미리보기·승인·반려한 뒤, **확정된 디자인을 기준으로** 구현 워커가 화면을 개발한다.

핵심 결정 (브레인스토밍 확정):
- 디자인 주체: **워커 자동 생성**, 사용자는 승인/반려만
- Claude Design 역할: **입력(디자인 시스템 소스) + 출력(목업 열람처)** 양방향. 승인 게이트는 netisMaker
- 적용 범위: **등록 시 "디자인 단계 포함" 토글**로 opt-in
- 반려: **피드백 반려 루프, 최대 3회** (이후 승인 또는 취소만)
- 아키텍처: 배포 파이프라인과 동일한 일급 구간 패턴 (새 TaskStatus + Kind + 프롬프트 템플릿 + 전용 테이블)

## 전제 (운영)

- 워커 머신의 claude CLI에서 DesignSync 사용 가능해야 함: 워커 계정으로 `/design-login` 1회 인증, Pro 이상 플랜 필요.
- Claude Design에는 디자인 완성 웹훅/이벤트가 없으므로 모든 게이트는 netisMaker 버튼으로 처리.

## 1. 상태머신

새 `TaskStatus` 4개 (enum명 → 한글 dbValue, 기존 패턴 동일):

| Enum | dbValue |
|---|---|
| `DESIGN_PENDING` | 디자인대기 |
| `DESIGNING` | 디자인중 |
| `DESIGN_REVIEW` | 디자인승인대기 |
| `DESIGN_FAILED` | 디자인실패 |

전이:

```
분석완료 ──승인──> design_requested=false → 구현대기 (기존 불변)
                  design_requested=true  → 디자인대기
디자인대기 ──워커 claim──> 디자인중
디자인중 ──성공──> 디자인승인대기
디자인중 ──실패──> 디자인실패 (재시도 → 디자인대기)
디자인승인대기 ──승인──> 구현대기
디자인승인대기 ──반려(피드백 필수)──> 디자인대기   ※ reject_count 3 도달 시 반려 불가
취소: 디자인 상태 어디서든 가능 (기존과 동일)
```

- claim 쿼리(`TaskRepository.findClaimableForUpdateSkipLocked`)에 `디자인대기` 추가, claim 시 `디자인대기→디자인중`. 기존 FIFO 단일 큐 편입.
- stale 회수 쿼리에 `디자인중` 추가 → 하트비트 유실 시 `디자인대기` 복귀.
- 모든 전이 `TaskStatusHistory` 기록.

## 2. 데이터 (V15 마이그레이션)

- `com.task` + `design_requested boolean NOT NULL DEFAULT false`
- `com.repo_catalog` + `design_system_project_id varchar NULL` (입력 디자인 시스템), `design_output_project_id varchar NULL` (출력 프로젝트, 워커가 최초 생성 시 기록)
- 새 테이블 `com.task_design` (PK=task_id, task_analysis 미러):

| 컬럼 | 용도 |
|---|---|
| `design_markdown` TEXT | 디자인 문서 (구현 프롬프트로 전달) |
| `mockup_files` jsonb | `[{path, html}]` 화면별 self-contained 목업 HTML 원문 |
| `design_project_id`, `design_url` | 업로드된 Claude Design 프로젝트 ID·열람 URL (업로드 실패 시 null) |
| `feedback_history` jsonb | `[{feedback, rejectedBy, rejectedAt}]` 반려 누적 |
| `reject_count` int | 반려 횟수 (max 3) |
| `approved`, `approved_by`, `approved_at` | 승인 기록 (task_analysis 동일) |
| `claude_log`, `duration_ms`, `completed_at` | 운영 메타 |

목업을 DB에 저장하는 이유: netisMaker 미리보기가 Claude Design 가용성과 무관하게 동작하고, 구현 워커가 파일시스템 의존 없이 수신. (DesignSync 파일 한도 256KiB와도 정합)

## 3. 디자인 워커

- `WorkerTaskResponse.Kind.DESIGN` + `forDesign(task, analysis, design)` — 분석 markdown·subtasks + (반려 재실행 시) `feedback_history`·이전 `design_markdown`/목업 포함 → 백지 재생성이 아닌 수정.
- `WorkerMainLoop.processDesign()`:
  1. `GitRepoCache`+`WorktreeService`로 스크래치 워크트리 (push 없음, 기존 프론트 코드 참조용)
  2. `application-worker.yml`의 `design-prompt-template` 렌더. 치환자: `{title, description, analysis_markdown, subtasks_json, feedback_history, previous_design_markdown}`
  3. `claude -p --dangerously-skip-permissions` 세션:
     - DesignSync로 `design_system_project_id`의 토큰·컴포넌트 pull (null이면 skip)
     - 화면별 self-contained HTML(+인라인 CSS) → `.design-out/` + `manifest.json` + `DESIGN.md`
     - DesignSync `write_files`로 출력 프로젝트에 업로드 (레포당 1개 프로젝트, `task-{id}/` 프리픽스, 최초엔 `create_project` 후 카탈로그에 ID 기록)
  4. `.design-out/` 수확 → `POST /worker/tasks/{id}/design-result` → `task_design` 저장 + `디자인승인대기`

## 4. API

- `POST /api/tasks/{id}/design/approve` — ROLE_ADMIN, `디자인승인대기`에서만. 승인 기록 후 `구현대기`.
- `POST /api/tasks/{id}/design/reject` — body `{feedback}` 필수·공백불가, `reject_count < 3` 검증, history append 후 `디자인대기`.
- `POST /worker/tasks/{id}/design-result` — 워커 성공 보고 (실패는 기존 fail 경로 재사용). 기존 `recordResult` 미러, API-key 인증.
- 작업 상세 DTO에 디자인 필드 포함 (목록 응답에는 목업 HTML 미포함).
- `TaskCreateRequest.designRequested` 추가. 인터뷰 경유 등록에도 동일 필드 적용 (인터뷰 등록 작업은 분석완료 시작 → 승인 시 토글에 따라 디자인 구간 합류).

## 5. 프론트엔드

- 등록 다이얼로그(일반+인터뷰): "디자인 단계 포함" 토글 + 힌트.
- 작업 상세 `[id].vue`:
  - `statusClass` 맵에 4개 상태 추가.
  - 디자인 카드: DESIGN.md 마크다운, 화면 탭 + iframe 미리보기(`srcdoc` + `sandbox="allow-scripts"` — same-origin 차단으로 생성 HTML 격리), "Claude Design에서 열기" 링크(없으면 업로드 실패 뱃지).
  - `디자인승인대기`: 승인 버튼 + 반려 버튼(피드백 다이얼로그) + 반려 이력 타임라인. 3회 도달 시 반려 비활성 + 안내.
  - `디자인실패`: 로그 + 재시도 (기존 실패 UX 동일).
- 통계 카드 행: 서버 enum 기반 카운트에 디자인 상태 자동 반영.

## 6. 구현 단계 전달

- `forImplementation()`에 승인된 `task_design` 동봉. 구현 프롬프트 템플릿에 `{design_section}` 치환자 — 디자인 존재 시 "확정 디자인 문서 + 목업 경로 + 이 디자인 기준으로 구현" 블록, 없으면 빈 문자열(기존 작업 불변).
- 워커가 claude 실행 전 목업을 워크트리 `.design/task-{id}/`에 배치, 커밋 제외.
- PR 본문에 Claude Design URL 포함.

## 7. 에러 처리

| 상황 | 정책 |
|---|---|
| 디자인 시스템 pull 실패 (ID 설정됨) | `디자인실패` — 준수 기대를 조용히 무시하지 않음 |
| 디자인 시스템 미설정 (null) | pull skip, 범용 스타일 생성 |
| Claude Design 업로드 실패 | **비치명** — `design_url=null`로 성공 처리, UI 뱃지 표시 (DB 목업이 진실원본) |
| DesignSync 인증 실패 | 프롬프트에 "즉시 중단+사유 출력" 지시 → `디자인실패`, UI에서 사유 확인 |
| 워커 사망/하트비트 유실 | stale 회수 → `디자인대기` |
| 보고 유실 | 기존 ResultReporter/silent-loss 3계층 체계 편입 |

## 8. 테스트

- 단위: 승인 시 토글 분기, 반려 한도, claim 전이, 피드백 누적, `processDesign` 수확 파싱(가짜 ClaudeExecAdapter).
- 통합(MockMvc/H2): approve/reject ACL(ROLE_ADMIN), design-result API-key 인증, 상태 위반 4xx. Testcontainers류는 CI 전용(기존 방침).
- 프론트(vitest): 디자인 카드 렌더, 반려 다이얼로그 검증, 한도 비활성.
- 라이브 스모크: 토글 등록 → 분석 승인 → 디자인 생성 → 반려 1회 → 재생성 → 승인 → 구현 프롬프트에 디자인 포함 + claude.ai/design 열람 확인.

## 범위 제외 (YAGNI)

- 디자인 완성 자동 감지(웹훅 부재), 사용자가 Claude Design에서 수정한 내용의 역방향 pull(반려 루프로 대체), 작업별 디자인 프로젝트 분리, 디자인 버전 히스토리 UI.
