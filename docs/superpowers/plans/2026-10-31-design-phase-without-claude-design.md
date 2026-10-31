# 디자인 단계에서 Claude Design 걷어내기 + 목업 크게 보기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 디자인 단계 프롬프트·PR 본문·카드에서 Claude Design(DesignSync) 의존을 없애고, 목업을 브라우저 새 탭에서 창 크기 그대로 보는 페이지를 더한다.

**Architecture:** 워커는 디자인 프롬프트 템플릿(yml)과 `WorkerMainLoop`의 프롬프트 렌더·PR 본문만 바꾼다(API·DB 무변경). 프론트는 디자인 카드에 새 탭 링크를 달고, 툴바 없는 `blank` 레이아웃의 새 페이지 `pages/designs/[id].vue`가 `GET /api/tasks/{id}`의 `design.mockupFilesJson`을 격리된 iframe 하나로 창 전체에 그린다.

**Tech Stack:** Java 21 / Spring Boot 워커(JUnit 5 + AssertJ), Nuxt 3 + Quasar(vitest + @vue/test-utils, happy-dom).

**Spec:** `docs/superpowers/specs/2026-10-31-design-phase-without-claude-design-design.md`

## Global Constraints

- API(`src/main/java/.../service`, `controller`, `dto`, `entity`)의 동작은 바꾸지 않는다. DB 마이그레이션 없음. `WorkerTaskResponse`의 `designSystemProjectId`·`designOutputProjectId`·`designUrl` 필드는 지우지 않는다.
- 목업 HTML은 언제나 `<iframe :srcdoc sandbox="allow-scripts">` 안에서만 그린다 — sandbox 토큰은 `allow-scripts` 하나뿐. blob URL·`window.open`+`document.write` 금지.
- 크게 보기 경로: `/designs/{taskId}?screen={1부터}`. `pages/tasks/[id].vue`는 옮기지 않는다.
- 프론트 코드 스타일: 작은따옴표, 세미콜론 없음, `<script setup lang="ts">`, 2-space, trailing comma. `frontend/` 전체에 prettier를 돌리지 말 것. Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`)을 클래스명에 쓰지 말 것.
- 화면 문구(정확히):
  - 카드 버튼 `크게 보기`, 페이지 돌아가기 `작업으로 돌아가기`, 빈 상태 `이 작업에는 아직 디자인 목업이 없습니다`, 불러오기 실패 기본 `작업을 불러오지 못했습니다`, 탭 제목 `#{id} 디자인 — Symphony`
  - 토글 툴팁 `구현 전에 화면 목업을 먼저 만들어 승인받습니다`
  - 확정 알림 `확정 완료 — 디자인 큐에 진입했습니다` / `확정 완료 — 구현 큐에 진입했습니다`
  - PR 디자인 줄 `- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #{id} 디자인 카드 참고)`
- 이 worktree에서 `npm ci`·`npm install`·하이드레이션 스모크 실행 금지(라이브 node_modules를 빌려 씀 — 스모크는 CI에 맡김). `gradlew --stop` 금지.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. 목업 스크립트가 `window.top`·`localStorage`·`document.cookie`에 손대려 함 → 크게 보기 iframe의 `sandbox`가 정확히 `allow-scripts`라 막혀야 한다(Task 3 `sandbox` 테스트).
2. 손으로 고친 주소 `?screen=0`·`-1`·`abc`·`99` → 첫 화면이 열리고 오류가 나지 않아야 한다(Task 3 범위 밖 테스트).
3. 예전 워커가 만든 결과(`designUrl`이 채워진 행) → 카드에 Claude Design 링크·업로드 실패 칩이 다시 보이면 안 된다(Task 2 테스트), PR 본문에도 링크가 없어야 한다(Task 1 테스트).
4. 남의 작업·지운 작업 주소를 연 경우(API 403/404) → 서버 메시지와 돌아가기 링크만 보여야 한다(Task 3 테스트).
5. 템플릿에 치환되지 않은 `{자리}`가 남아 claude에게 그대로 전달 → 렌더 결과에 `{영문_자리}` 꼴이 없어야 한다(Task 1 테스트).

---

### Task 1: 워커 — 디자인 프롬프트와 PR 본문에서 Claude Design 제거

**Files:**
- Modify: `src/main/resources/application-worker.yml:149-190` (주석 + `design-prompt-template`)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java:603` (호출부), `:637-649` (`renderDesignPrompt`), `:851-861` (`renderPrBody` 디자인 줄)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DesignResultHarvester.java:24` (주석의 result.json 형식만)
- Modify: `src/main/resources/application.yml:114` (디자인 stale 주석의 `DesignSync 업로드` 언급만)
- Modify: `docs/superpowers/specs/2026-07-19-claude-design-phase-design.md` 맨 위 한 줄
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDesignPromptTest.java` (새 파일)

**Interfaces:**
- Produces: `static String WorkerMainLoop.renderDesignPrompt(String template, WorkerTaskResponse task)` (package-private). 호출부는 `renderDesignPrompt(props.designPromptTemplate(), task)`.

- [ ] **Step 1: 실패하는 테스트 작성** — `WorkerMainLoopDesignPromptTest`. yml은 `WorkerPropertiesYamlBindingTest.loadYaml()`과 같은 방식(YamlPropertySourceLoader → `Binder.get(env).bind("netis-maker.worker", WorkerProperties.class)`)으로 읽는다. 작업 픽스처는 31인자 생성자로 `designMarkdown`·`mockupFilesJson`·`feedbackHistoryJson`·`designUrl`(`"https://claude.ai/design/p/abc"`)·`designSystemProjectId`(`"ds-1"`)·`designOutputProjectId`(`"out-1"`)을 채운다(`WorkerMainLoopImplementationModeTest.taskWithAnalysis` 참고).

```java
@Test
void the_design_prompt_no_longer_mentions_claude_design_and_leaves_no_placeholder() {
    String prompt = WorkerMainLoop.renderDesignPrompt(props().designPromptTemplate(), designTask());
    assertThat(prompt).doesNotContain("DesignSync").doesNotContain("Claude Design")
            .doesNotContain("design-login").doesNotContain("ERROR.txt")
            .doesNotContain("ds-1").doesNotContain("out-1");
    assertThat(prompt).doesNotContainPattern("\\{[a-z_]+\\}");
    assertThat(prompt).contains(".design-out/screens/").contains(".design-out/DESIGN.md")
            .contains(".design-out/result.json")
            .contains("기기 프레임·고정 폭 캔버스 없이");
}

@Test
void the_design_prompt_still_carries_the_task_and_the_rejection_history() {
    String prompt = WorkerMainLoop.renderDesignPrompt(props().designPromptTemplate(), designTask());
    assertThat(prompt).contains("Add login").contains("desc")          // {title}, {description}
            .contains("# 이전 설계")                                    // {previous_design_markdown}
            .contains("버튼을 더 크게");                                 // {feedback_history}
}

@Test
void the_pr_design_line_points_to_the_symphony_card_without_a_link() {
    String body = WorkerMainLoop.renderPrBody(designTask(), 12_000, "abcdef1234567", null, null);
    assertThat(body).contains("- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #7 디자인 카드 참고)\n")
            .doesNotContain("Claude Design").doesNotContain("claude.ai/design");
}
```

픽스처 값: id `7L`, title `"Add login"`, description `"desc"`, designMarkdown `"# 이전 설계"`, feedbackHistoryJson `"[{\"feedback\":\"버튼을 더 크게\"}]"`.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDesignPromptTest"`
Expected: 컴파일 실패(`renderDesignPrompt(String, WorkerTaskResponse)` 없음).

- [ ] **Step 3: 템플릿 교체** — `application-worker.yml`의 주석을 `# 디자인 단계 — 레포 관례를 참고해 화면 목업(HTML)과 디자인 문서 생성 (Claude Design 미사용, 스펙 2026-10-31)`로, `## 절차 (순서 엄수)` 아래를 아래 4단계로 바꾼다(머리말~`## 반려 피드백 이력`은 그대로).

```
      ## 절차 (순서 엄수)
      1. **기존 코드 참조**: 현재 디렉토리의 프론트엔드 코드(컴포넌트/스타일)를 훑어 기존 UI 관례(레이아웃·색·타이포·컴포넌트)를
         파악하세요. 이 관례가 스타일 기준입니다. 관례가 없으면 깔끔한 범용 스타일을 쓰세요.
      2. **목업 생성**: 작업에 필요한 화면별로 self-contained HTML(인라인 CSS, 외부 요청 없음)을
         `.design-out/screens/` 아래에 만드세요 (예: `.design-out/screens/main.html`).
         각 파일은 실제 데이터 예시를 채운 완성된 화면이어야 합니다. 파일당 200KB 이하.
         기기 프레임·고정 폭 캔버스 없이, 대상 앱이 브라우저에서 실제로 그려지는 것처럼 창 폭에 맞춰 늘어나게 만드세요
         (레포에 최대 폭·반응형 관례가 있으면 그대로 따르세요).
      3. **디자인 문서**: `.design-out/DESIGN.md`에 디자인 의사결정(레이아웃/색/타이포/컴포넌트 매핑,
         화면별 설명)을 정리하세요.
      4. **결과 메타**: `.design-out/result.json`에 다음 JSON을 쓰세요:
         {"screens":[{"path":"screens/main.html","title":"메인 화면"}]}
```

- [ ] **Step 4: `renderDesignPrompt`를 `static String renderDesignPrompt(String template, WorkerTaskResponse task)`로 바꾼다** — 치환은 `{title}`·`{description}`·`{analysis_markdown}`·`{subtasks_json}`·`{previous_design_markdown}`·`{feedback_history}` 여섯 개만(기본값은 지금과 같음). `{github_repo}`·`{task_id}`·`{design_system_project_id}`·`{design_output_project_id}` 치환은 지운다. 603행 호출부를 맞춘다.

- [ ] **Step 5: `renderPrBody`의 디자인 줄** — `hasDesignUrl` 계산과 링크 조각을 지우고 `"- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #" + task.id() + " 디자인 카드 참고)\n"`로.

- [ ] **Step 6: 주석·문서 정리** — `DesignResultHarvester` 클래스 주석의 result.json 줄을 `{screens:[{path,title}]}` (designProjectId·designUrl은 예전 워커 호환으로만 읽음)으로. `application.yml:114` 주석에서 DesignSync 업로드 언급을 목업 생성으로. `2026-07-19-claude-design-phase-design.md` 맨 위(제목 다음)에 `> 2026-10-31: Claude Design 업로드·디자인 시스템 pull(DesignSync)은 \`2026-10-31-design-phase-without-claude-design-design.md\`로 대체됨 — 목업은 DB와 Symphony 화면에서만 본다.`

- [ ] **Step 7: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDesignPromptTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopImplementationModeTest" --tests "com.hamonsoft.netismaker.workerdaemon.DesignResultHarvesterTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesYamlBindingTest"`
Expected: 모두 PASS.

- [ ] **Step 8: 커밋** — `feat(worker): 디자인 프롬프트·PR 본문에서 Claude Design 제거`

---

### Task 2: 프론트 — 디자인 카드의 '크게 보기'

**Files:**
- Modify: `frontend/components/DesignReviewCard.vue:117-127` (링크·칩 제거, 버튼 추가)
- Test: `frontend/components/DesignReviewCard.spec.ts`

**Interfaces:**
- Consumes: 크게 보기 경로 규칙 `/designs/{taskId}?screen={selectedTab + 1}` (Task 3이 그 페이지를 만든다).

- [ ] **Step 0 (한 번만): worktree에서 vitest 준비** — PowerShell: `New-Item -ItemType Junction -Path frontend\node_modules -Target C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules` (이미 있으면 건너뜀. worktree를 지우기 전 `cmd //c "rmdir frontend\\node_modules"`로 링크만 먼저 지울 것).

- [ ] **Step 1: 실패하는 테스트 작성** — `describe('DesignReviewCard — 크게 보기')`. 목업 2개 픽스처: `JSON.stringify([{ path: 'screens/a.html', title: '목록', html: '<p>A</p>' }, { path: 'screens/b.html', title: '상세', html: '<p>B</p>' }])`.

```ts
it('links 크게 보기 to the selected screen in a new tab', async () => {
  const w = mount(DesignReviewCard, { props: { taskId: 12, status: 'DESIGN_REVIEW', design: baseDesign({ mockupFilesJson: TWO }), isAdmin: true } })
  const btn = () => w.find('[data-test="design-open-large"]')
  expect(btn().attributes('href')).toBe('/designs/12?screen=1')
  expect(btn().attributes('target')).toBe('_blank')
  expect(btn().attributes('rel')).toContain('noopener')
  expect(btn().text()).toContain('크게 보기')
  await w.findAll('.q-tab')[1].trigger('click')
  expect(btn().attributes('href')).toBe('/designs/12?screen=2')
  w.unmount()
})

it('shows no 크게 보기 without mockups', () => { /* mockupFilesJson '[]' → design-open-large 없음 */ })

it('never shows the Claude Design link or the upload-failed chip, even for old rows with a designUrl', () => {
  // design: baseDesign({ mockupFilesJson: TWO, designUrl: 'https://claude.ai/design/p/1', designProjectId: 'p1' })
  // expect(w.text()).not.toContain('Claude Design'); expect(w.text()).not.toContain('업로드 실패')
})
```

- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npx vitest run components/DesignReviewCard.spec.ts` / Expected: 새 3건 FAIL.

- [ ] **Step 3: 구현** — `q-btn`(`flat dense icon="open_in_new" label="크게 보기" type="a" target="_blank" rel="noopener" data-test="design-open-large"`, `v-if="mockups.length"`, `:href`는 위 경로 규칙)을 `<q-space />` 뒤, 승인·반려 버튼 앞에 둔다. 'Claude Design에서 열기' 버튼과 '업로드 실패' 칩은 지운다(`DesignView` 타입의 `designUrl`·`designProjectId` 필드는 그대로 둔다 — API 응답 모양).

- [ ] **Step 4: 통과 확인** — 같은 명령, Expected: 파일 전체 PASS.

- [ ] **Step 5: 커밋** — `feat(frontend): 디자인 카드에 크게 보기, Claude Design 링크 제거`

---

### Task 3: 프론트 — 크게 보기 페이지 `/designs/{id}` + 문서

**Files:**
- Create: `frontend/pages/designs/[id].vue`
- Modify: `frontend/layouts/blank.vue:2` (주석: 로그인 화면과 디자인 크게 보기가 쓰는 툴바 없는 레이아웃)
- Modify: `frontend/test/setup.ts` (전역 `useHead: vi.fn()` 추가)
- Modify: `frontend/scripts/hydration-smoke.mjs` (`AUTH_PATHS`에 `'/designs/1'`, `TASKS`의 작업 1에 `design` 목)
- Modify: `CLAUDE.md` (작업 상태머신 절의 디자인 설명 한 줄 + 자주 보는 코드 표 한 행)
- Test: `frontend/test/design-large-view.spec.ts` (새 파일)

**Interfaces:**
- Consumes: `GET /api/tasks/{id}` → `{ id, title, design: { mockupFilesJson: string, ... } | null }` (전역 `useApi` 목), `useRoute()` → `{ params: { id }, query: { screen } }`, 전역 `navigateTo`.
- Produces: 경로 `/designs/{id}?screen=N`, `data-test` 훅 `design-frame`·`design-back`·`design-empty`·`design-error`.

- [ ] **Step 1: 실패하는 테스트 작성** — `request-detail.spec.ts`처럼 `QLayout`/`QPageContainer`로 감싸 마운트하고, 모듈 단계에서 `(globalThis as any).useRoute = () => ({ params: { id: '12' }, query: currentQuery })`, `navigateTo` 스파이를 둔다. 픽스처: `{ id: 12, title: '회원 목록 개선', design: { mockupFilesJson: TWO, ... } }`(TWO는 Task 2와 같은 모양).

```ts
it('opens the screen from ?screen=2 in a sandboxed frame', async () => {
  currentQuery = { screen: '2' }
  const w = await mountPage(task())
  const f = w.find('[data-test="design-frame"]')
  expect(f.attributes('srcdoc')).toBe('<p>B</p>')
  expect(f.attributes('sandbox')).toBe('allow-scripts')
  expect(w.text()).toContain('#12 회원 목록 개선 · 디자인')
})

it.each(['0', '-1', 'abc', '99', undefined])('falls back to the first screen for screen=%s', ...)   // srcdoc === '<p>A</p>'

it('switching a tab replaces the screen query', async () => {
  // 두 번째 탭 클릭 → navigateTo({ query: { screen: '2' } }, { replace: true }), srcdoc === '<p>B</p>'
})

it('shows the empty notice and the back link when the task has no design', ...)
// design: null → [data-test="design-empty"] 텍스트 '이 작업에는 아직 디자인 목업이 없습니다', [data-test="design-back"] href '/tasks/12', design-frame 없음
// mockupFilesJson 'not json' → 같은 결과

it('shows the server message on 403/404', ...)
// useApiMock.mockRejectedValueOnce({ statusCode: 403, data: { message: '권한이 없습니다' } }) → design-error '권한이 없습니다' + design-back
// 메시지 없는 거부 → '작업을 불러오지 못했습니다'
```

- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npx vitest run test/design-large-view.spec.ts` / Expected: 모듈을 찾지 못해 FAIL.

- [ ] **Step 3: 페이지 구현** — `pages/designs/[id].vue`:
  - `definePageMeta({ layout: 'blank' })`, `useHead({ title: \`#${id} 디자인 — Symphony\` })`.
  - `onMounted`에서 `useApi(\`/api/tasks/${id}\`)` 한 번(자동 갱신 없음). 거부되면 `e?.data?.message ?? '작업을 불러오지 못했습니다'`.
  - 목업 파싱은 카드와 같은 규칙(`JSON.parse` 실패 → 빈 배열). 선택 화면 = `Number(query.screen)`이 1..목업 수의 정수면 그 값−1, 아니면 0.
  - 탭을 바꾸면 선택을 바꾸고 `navigateTo({ query: { screen: String(i + 1) } }, { replace: true })`.
  - 구조: 높이 48px 고정 윗줄(`#{id} {title} · 디자인`, `q-tabs`(목업 `title`), 오른쪽 `q-btn flat type="a" :href="/tasks/{id}" label="작업으로 돌아가기" data-test="design-back"`) + 그 아래 `iframe`(`data-test="design-frame"`, `:srcdoc`, `sandbox="allow-scripts"`, `width:100%; height:calc(100vh - 48px); border:0; display:block; background:#fff`). 페이지 루트는 `height:100vh; overflow:hidden`.
  - 빈 상태·오류는 윗줄 아래 가운데 안내 + 돌아가기.
  - 템플릿에 인증·관리자 분기를 두지 않는다(데이터는 마운트 뒤에만 오므로 SSR과 첫 렌더가 같다).

- [ ] **Step 4: 주변 정리** — `blank.vue` 주석, `test/setup.ts`에 `useHead: vi.fn()`, 하이드레이션 스모크: `AUTH_PATHS`에 `'/designs/1'`, 작업 1에 `design: { designMarkdown: '# 디자인', mockupFilesJson: JSON.stringify([{ path: 'screens/main.html', title: '메인', html: '<p>목업</p>' }]), designProjectId: null, designUrl: null, rejectCount: 0, feedbackHistoryJson: '[]', approved: true, approvedBy: 'admin', approvedAt: NOW, completedAt: NOW }`.

- [ ] **Step 5: CLAUDE.md** — 작업 상태머신 절 "**확정**" 줄 다음에: `- **디자인**(\`design_requested\`, 플랜 확정 토글): 워커가 레포 관례를 보고 \`.design-out/screens/*.html\`·DESIGN.md를 만들고 DB(\`task_design\`)에만 저장 — Claude Design·DesignSync는 쓰지 않는다(2026-10-31, 스펙 \`docs/superpowers/specs/2026-10-31-design-phase-without-claude-design-design.md\`). 관리자는 상세 카드에서 승인·반려(최대 3회), 카드 '크게 보기'는 새 탭 \`/designs/{id}?screen=N\`(툴바 없는 \`blank\` 레이아웃, iframe \`sandbox="allow-scripts"\`만 — 목업 HTML을 앱 출처로 실행하지 말 것). 배포 순서: 워커(원격 워커 jar 포함) → 프론트(\`.output\` 재빌드), API 무변경.` 자주 보는 코드 표에 행 `| 디자인 목업 크게 보기 | \`frontend/pages/designs/[id].vue\`, \`frontend/components/DesignReviewCard.vue\`, 프롬프트 \`application-worker.yml\` \`design-prompt-template\` |`.

- [ ] **Step 6: 통과 확인** — Run: `cd frontend && npx vitest run test/design-large-view.spec.ts components/DesignReviewCard.spec.ts` / Expected: PASS. 이어서 `npm run lint` / Expected: 오류 0(경고 수는 늘지 않음).

- [ ] **Step 7: 커밋** — `feat(frontend): 디자인 목업 크게 보기 페이지 /designs/{id}`

---

### Task 4: 프론트 — 디자인 토글 툴팁·확정 알림 문구

**Files:**
- Modify: `frontend/components/InterviewPanel.vue:287` (알림), `:563` (툴팁)
- Test: `frontend/components/InterviewPanel.spec.ts` (`describe('InterviewPanel — confirm flow')`)

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 확정 테스트 두 개(토글 끔·켬)에 알림 단언을 더한다. 스파이는 `vi.spyOn(w.vm.$q, 'notify')`(`task-detail-close.spec.ts:293` 방식).

```ts
expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 구현 큐에 진입했습니다' })   // 토글 끔
expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 디자인 큐에 진입했습니다' }) // 토글 켬
```

- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts` / Expected: 토글 켬 케이스 FAIL.

- [ ] **Step 3: 구현** — 알림 메시지를 `designRequested.value`로 가르고, 툴팁을 `구현 전에 화면 목업을 먼저 만들어 승인받습니다`로.

- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: 파일 전체 PASS.

- [ ] **Step 5: 커밋** — `fix(frontend): 디자인 토글 툴팁·확정 알림 문구를 실제 동작에 맞춤`

---

### 마무리 확인 (모든 작업 뒤)

- [ ] `./gradlew test` 전체 / Expected: BUILD SUCCESSFUL (Testcontainers 테스트는 `RUN_TESTCONTAINERS` 없이 건너뜀).
- [ ] `cd frontend && npx vitest run` 전체 / Expected: 실패 0.
- [ ] `grep -rn -E "DesignSync|Claude Design|design-login" src/main frontend/components frontend/pages` / Expected: 결과 없음(문서·테스트 제외).
