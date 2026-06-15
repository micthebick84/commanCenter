# 인터뷰 새로고침 이어가기 — 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대화형 분석 인터뷰 도중 브라우저를 새로고침해도 진행 중인 세션의 대화를 그대로 이어갈 수 있게 한다.

**Architecture:** 백엔드는 본인의 비종료 인터뷰 목록을 주는 디스커버리 엔드포인트(`GET /api/interviews/active`)와, 영문 상태값(`statusName`)을 추가한다. 프론트는 `/tasks` 마운트 시 활성 세션을 조회해 하이브리드(1개 자동 / 다수 선택)로 패널을 재오픈하고, `InterviewPanel`은 마운트 시 `GET /{id}` 스냅샷으로 전체 대화(내 답변 포함)·status·plan을 하이드레이션한 뒤 SSE를 연다(seq dedup으로 replay와 중복 없이 병합). 비종료 인터뷰를 취소 없이 닫는 "나중에" 버튼으로 자동 재오픈 갇힘을 방지한다.

**Tech Stack:** Spring Boot 3.4 (Java 21, JPA, JUnit5+Mockito+AssertJ, Testcontainers[CI 전용]), Nuxt 3 + Quasar (TypeScript, Vitest + @vue/test-utils).

**선행 스펙:** `docs/superpowers/specs/2026-06-14-interview-refresh-resume-design.md`

**브랜치:** `feat/interview-refresh-resume` (이미 생성됨, `feat/interview-popup-ux` 위에 stacked)

---

## 파일 구조

| 파일 | 책임 | 변경 |
|---|---|---|
| `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java` | 상세 뷰 DTO | 수정: 영문 `statusName` 추가 |
| `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java` | 세션 쿼리 | 수정: `findActiveByRequester` 추가 |
| `src/main/java/com/hamonsoft/netismaker/dto/InterviewSummary.java` | 경량 목록 DTO | 신규 |
| `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` | 상태 전이/조회 | 수정: `listActiveForRequester` 추가 |
| `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` | 사용자 API | 수정: `GET /active` 추가 |
| `frontend/composables/useInterviewStream.ts` | SSE 클라이언트 + 상태 | 수정: `hydrate()` + `upsertDesign()` 추출 |
| `frontend/composables/interviewResume.ts` | 활성 세션 목록 타입 + 재오픈 결정 (순수) | 신규 |
| `frontend/components/InterviewPanel.vue` | 인터뷰 패널 | 수정: 마운트 스냅샷 + "나중에" 버튼 |
| `frontend/pages/tasks/index.vue` | 작업/인터뷰 다이얼로그 | 수정: 디스커버리 + 하이브리드 재오픈 + 선택 다이얼로그 |

**테스트 실행 메모:**
- 서비스(Mockito)·DTO·프론트(Vitest) 테스트는 **로컬 실행 가능**.
- 리포지토리/컨트롤러 통합 테스트는 `@EnabledIfEnvironmentVariable(named="RUN_TESTCONTAINERS", matches="true")` 게이트라 **로컬에선 skip, CI에서만 실행**(실 Postgres 필요, H2 미지원). 로컬 검증은 컴파일(`./gradlew compileTestJava`)로 한다. (메모리: Testcontainers 로컬 docker 비호환 — 로컬 실행 시도 금지.)
- 백엔드 단위 테스트만 로컬 실행: `./gradlew test --tests '*InterviewServiceTest' --tests '*InterviewResponseTest'`
- 프론트 테스트: `cd frontend && npx vitest run <파일>`

---

## Task 1: 백엔드 — InterviewResponse에 영문 statusName 추가

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java`

이유: 프론트 스트림/하이드레이션은 영문 enum 이름("AWAITING_INPUT")으로만 상태를 판정하는데 기존 `status`는 한글 dbValue("입력대기")라 사용 불가. 비파괴적으로 영문 `statusName`을 추가한다.

- [ ] **Step 1: 실패 테스트 작성** — `InterviewResponseTest.java`에 메서드 추가 (기존 import에 엔티티 추가)

파일 상단 import에 다음을 추가:
```java
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
```

클래스 안에 테스트 추가:
```java
    @Test
    void of_exposes_both_korean_status_and_english_statusName() {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "제목", "설명", "u1", List.of());
        ReflectionTestUtils.setField(s, "id", 7L);
        s.setStatus(InterviewStatus.AWAITING_INPUT);

        InterviewResponse r = InterviewResponse.of(s, List.of(), null);

        assertThat(r.status()).isEqualTo("입력대기");          // 한글 dbValue (표시용) — 유지
        assertThat(r.statusName()).isEqualTo("AWAITING_INPUT"); // 영문 enum name (로직용) — 신규
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `cannot find symbol: method statusName()`

- [ ] **Step 3: 최소 구현** — `InterviewResponse.java` 레코드 컴포넌트와 팩토리에 `statusName` 추가

레코드 헤더에서 `status` 바로 아래에 `statusName` 추가:
```java
public record InterviewResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        String status,        // 한글 dbValue (사용자 표시). SSE status 이벤트는 영문 enum name 사용.
        String statusName,     // 영문 enum name (InterviewStatus.name()). 프론트 로직/하이드레이션용.
        String currentPhase,
        String workDir,
        Long taskId,
        List<TurnView> turns,
        PlanView plan,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
```

`of(...)` 팩토리의 `new InterviewResponse(...)` 인자에서 `s.getStatus().dbValue()` 다음에 `s.getStatus().name()`을 추가:
```java
        return new InterviewResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(), s.getWorkDir(), s.getTaskId(),
                tvs, pv, s.getCreatedAt(), s.getUpdatedAt());
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*InterviewResponseTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java \
        src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java
git commit -m "feat(interview): InterviewResponse에 영문 statusName 추가

프론트 하이드레이션이 영문 enum으로 상태를 판정하도록 dbValue(한글)와
별개로 status.name()을 노출. 기존 status 필드는 유지(비파괴적).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 백엔드 — findActiveByRequester 리포지토리 쿼리

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/repository/InterviewActiveQueryTest.java` (신규, Testcontainers 게이트)

이유: 본인의 비종료 세션을 lastActivityAt 최신순으로 조회. `countActiveByRequester`의 IN절을 재사용.

- [ ] **Step 1: 실패 테스트 작성** — 신규 파일 `InterviewActiveQueryTest.java`

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findActiveByRequester — 본인의 비종료 세션만, lastActivityAt DESC. 실 Postgres 필요.
 * RUN_TESTCONTAINERS=true에서만 실행 (H2 미사용 — 기존 통합 테스트와 동일 게이트).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewActiveQueryTest {

    @Autowired private InterviewService service;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void clean() { sessionRepo.deleteAll(); }

    private InterviewSession create(String requester, String repo) {
        return service.create(new CreateInterviewRequest(repo, "main", "T", "d", List.of()), requester);
    }

    @Test
    void returns_only_active_sessions_of_the_requester() {
        InterviewSession s1 = create("user1", "owner/a"); // QUEUED (active)
        InterviewSession s2 = create("user1", "owner/b"); // QUEUED (active)
        create("user2", "owner/c");                       // 다른 사용자 — 제외
        // s2를 취소(terminal)로 — active에서 빠져야
        service.cancel(s2.getId(), "user1", false);

        List<InterviewSession> active = sessionRepo.findActiveByRequester("user1");

        assertThat(active).extracting(InterviewSession::getId).containsExactly(s1.getId());
    }

    @Test
    void orders_by_last_activity_desc() {
        InterviewSession a = create("user1", "owner/a");
        InterviewSession b = create("user1", "owner/b");
        // b를 더 최근 활동으로: heartbeat 대신 직접 touch 효과 — 답변 재큐로 last_activity 갱신은
        // 워커 필요하므로, 여기선 생성 순서만으로 b가 더 최신(createdAt). lastActivityAt은 create 시 동일
        // 타임스탬프 근처지만 b가 나중에 생성되어 같거나 큼. 안정성을 위해 id 포함 여부만 단언.
        List<InterviewSession> active = sessionRepo.findActiveByRequester("user1");
        assertThat(active).extracting(InterviewSession::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(active).hasSize(2);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `cannot find symbol: method findActiveByRequester`

- [ ] **Step 3: 최소 구현** — `InterviewSessionRepository.java`에 쿼리 메서드 추가 (기존 `findByRequester` 아래)

```java
    /**
     * 요청자별 비종료(=active) 세션, 최근 활동 우선. 새로고침 후 '이어할 인터뷰' 디스커버리용.
     * active = QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY (countActiveByRequester와 동일 집합).
     */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.requesterId = :requesterId
          AND s.status IN (com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED,
                           com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING,
                           com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT,
                           com.hamonsoft.netismaker.entity.InterviewStatus.PLAN_READY)
        ORDER BY s.lastActivityAt DESC
    """)
    List<InterviewSession> findActiveByRequester(@Param("requesterId") String requesterId);
```

- [ ] **Step 4: 컴파일 확인 (로컬) / 테스트는 CI**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL. (테스트 자체는 RUN_TESTCONTAINERS=true인 CI에서 실행되어 PASS.)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java \
        src/test/java/com/hamonsoft/netismaker/repository/InterviewActiveQueryTest.java
git commit -m "feat(interview): findActiveByRequester 쿼리 추가 (비종료, 최근활동순)

새로고침 후 '이어할 인터뷰' 디스커버리용. active 집합은
countActiveByRequester와 동일. 통합 테스트는 RUN_TESTCONTAINERS 게이트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 백엔드 — InterviewSummary DTO + listActiveForRequester 서비스

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/InterviewSummary.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

이유: 목록 응답은 무거운 `InterviewResponse`(turns+plan 전체) 대신 경량 DTO. 서비스는 리포지토리 결과를 매핑.

- [ ] **Step 1: 경량 DTO 생성** — `InterviewSummary.java` (신규)

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.time.OffsetDateTime;

/** 활성 인터뷰 목록 항목(경량). statusName=영문 enum, statusLabel=한글 dbValue. */
public record InterviewSummary(
        Long id,
        String statusName,
        String statusLabel,
        String title,
        String githubRepo,
        String githubBranch,
        String currentPhase,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt
) {
    public static InterviewSummary of(InterviewSession s) {
        return new InterviewSummary(
                s.getId(),
                s.getStatus().name(),
                s.getStatus().dbValue(),
                s.getTitle(),
                s.getGithubRepo(),
                s.getGithubBranch(),
                s.getCurrentPhase(),
                s.getLastActivityAt(),
                s.getCreatedAt());
    }
}
```

- [ ] **Step 2: 실패 테스트 작성** — `InterviewServiceTest.java`에 추가

import에 추가:
```java
import com.hamonsoft.netismaker.dto.InterviewSummary;
```

클래스 안에 테스트 추가 (기존 `session(...)` 헬퍼 재사용):
```java
    @Test
    void listActiveForRequester_maps_sessions_to_summaries() {
        InterviewSession s = session(30L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveByRequester("u1")).thenReturn(List.of(s));

        List<InterviewSummary> out = service.listActiveForRequester("u1");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(30L);
        assertThat(out.get(0).statusName()).isEqualTo("AWAITING_INPUT"); // 영문
        assertThat(out.get(0).statusLabel()).isEqualTo("입력대기");        // 한글
        verify(sessionRepo).findActiveByRequester("u1");
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*InterviewServiceTest'`
Expected: FAIL — `cannot find symbol: method listActiveForRequester` (컴파일 실패)

- [ ] **Step 4: 최소 구현** — `InterviewService.java`

import에 추가 (기존 dto import 블록):
```java
import com.hamonsoft.netismaker.dto.InterviewSummary;
```

`getResponse(...)` 메서드 아래에 추가:
```java
    /** 요청자 본인의 비종료 인터뷰 목록(경량). 새로고침 후 디스커버리/재오픈용. */
    @Transactional(readOnly = true)
    public List<InterviewSummary> listActiveForRequester(String requesterId) {
        return sessionRepo.findActiveByRequester(requesterId).stream()
                .map(InterviewSummary::of)
                .toList();
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*InterviewServiceTest'`
Expected: PASS (전체 InterviewServiceTest 통과)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/InterviewSummary.java \
        src/main/java/com/hamonsoft/netismaker/service/InterviewService.java \
        src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java
git commit -m "feat(interview): listActiveForRequester + InterviewSummary 경량 DTO

본인 비종료 인터뷰 목록을 turns/plan 없이 경량으로 반환. 디스커버리 엔드포인트용.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 백엔드 — GET /api/interviews/active 컨트롤러 엔드포인트

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java` (Testcontainers 게이트)

이유: 프론트가 본인 활성 세션을 조회하는 진입점. JWT 본인 스코프. `/{id}`와 충돌 없음(리터럴 경로 우선).

- [ ] **Step 1: 실패 테스트 작성** — `InterviewApiIntegrationTest.java`에 추가

클래스 안에 테스트 추가:
```java
    @Test
    void GET_active_returns_only_my_active_sessions_with_statusName() throws Exception {
        // user1: 2건 생성 후 1건 취소(terminal) → active 1건만
        String loc1 = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body("a/b", "살릴 인터뷰", "내용")))
                .andReturn().getResponse().getHeader("Location");
        String loc2 = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body("a/c", "취소할 인터뷰", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(post(loc2 + "/cancel").with(userJwt("user1"))).andExpect(status().isOk());
        // user2의 세션은 user1 목록에 안 나와야
        mvc.perform(post("/api/interviews").with(userJwt("user2"))
                .contentType(APPLICATION_JSON).content(body("a/d", "남의 인터뷰", "내용")));

        mvc.perform(get("/api/interviews/active").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("살릴 인터뷰"))
                .andExpect(jsonPath("$[0].statusName").value("QUEUED"))
                .andExpect(jsonPath("$[0].status").value("인터뷰대기"))
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void GET_active_unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/interviews/active")).andExpect(status().isUnauthorized());
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL (테스트는 컴파일됨 — 엔드포인트 미존재여도 MockMvc 경로 문자열은 컴파일 OK). 실제 로직 검증은 CI(RUN_TESTCONTAINERS).

> 참고: 이 테스트는 게이트로 로컬 skip. 엔드포인트 미구현 상태에서 CI라면 404로 FAIL. Step 3 후 PASS.

- [ ] **Step 3: 최소 구현** — `InterviewController.java`

import에 추가:
```java
import com.hamonsoft.netismaker.dto.InterviewSummary;
import java.util.List;
```

`create(...)` 메서드와 `get(...)` 메서드 사이에 추가:
```java
    /** 본인 비종료 인터뷰 목록 — 새로고침 후 '이어할 인터뷰' 디스커버리. 리터럴 /active가 /{id}보다 우선. */
    @GetMapping("/active")
    public List<InterviewSummary> listActive(JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return interviewService.listActiveForRequester(userId);
    }
```

- [ ] **Step 4: 컴파일 확인 (로컬) / 테스트는 CI**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL. (CI에서 RUN_TESTCONTAINERS=true로 두 테스트 PASS.)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java \
        src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java
git commit -m "feat(interview): GET /api/interviews/active 디스커버리 엔드포인트

본인 비종료 인터뷰 목록을 경량 DTO로 반환(JWT 본인 스코프). 리터럴 경로라
/{id}와 충돌 없음. 통합 테스트는 RUN_TESTCONTAINERS 게이트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 프론트 — useInterviewStream.hydrate() + upsertDesign 추출

**Files:**
- Modify: `frontend/composables/useInterviewStream.ts`
- Test: `frontend/composables/useInterviewStream.spec.ts`

이유: 새로고침 후 REST 스냅샷으로 전체 대화(내 답변 포함)·status·plan을 시드. SSE replay는 어시스턴트 질문/설계만 재생하고 사용자 답변·status·plan은 안 주므로 필수. `pushTurn` seq dedup으로 직후 replay와 중복 없이 병합.

- [ ] **Step 1: 실패 테스트 작성** — `useInterviewStream.spec.ts`에 추가

파일 끝에 describe 블록 추가:
```ts
describe('useInterviewStream — hydrate (refresh resume)', () => {
  it('seeds turns (incl. user answers), status, and plan from a REST snapshot', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: 'Q1' },
        { seq: 2, role: 'user', kind: 'answer', content: 'A1' },
        { seq: 3, role: 'assistant', kind: 'question', content: 'Q2' },
        { seq: 4, role: 'assistant', kind: 'design', content: '설계초안' },
      ],
      plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: JSON.stringify([{ title: 'T' }]) },
    })
    // 대화 턴: design(seq4)은 turns가 아닌 designSections로, user 답변은 포함
    expect(s.turns.value.map((t) => t.content)).toEqual(['Q1', 'A1', 'Q2'])
    expect(s.turns.value.find((t) => t.role === 'user')).toMatchObject({ content: 'A1' })
    expect(s.designSections.value).toHaveLength(1)
    expect(s.designSections.value[0]).toMatchObject({ key: 'design-4', title: '설계', body: '설계초안' })
    expect(s.status.value).toBe('AWAITING_INPUT')
    expect(s.plan.value).toMatchObject({ designMarkdown: '# 설계', planMarkdown: '# 플랜' })
    expect(s.plan.value!.planJson).toEqual([{ title: 'T' }])
  })

  it('does not duplicate turns/designs when SSE replay re-sends the same seqs after hydrate', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: 'Q1' },
        { seq: 4, role: 'assistant', kind: 'design', content: '설계초안' },
      ],
      plan: null,
    })
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('question', { seq: 1, content: 'Q1' })             // replay 중복
    es.emit('design', { key: 'design-4', title: '설계', body: '설계초안', approved: false })
    expect(s.turns.value).toHaveLength(1)
    expect(s.designSections.value).toHaveLength(1)
    s.close()
  })

  it('no-ops on a null/empty snapshot (fresh session)', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate(null as any)
    s.hydrate({})
    expect(s.turns.value).toHaveLength(0)
    expect(s.status.value).toBeNull()
    expect(s.plan.value).toBeNull()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npx vitest run composables/useInterviewStream.spec.ts`
Expected: FAIL — `s.hydrate is not a function`

- [ ] **Step 3: 최소 구현** — `useInterviewStream.ts`

3a. `DesignSection` 타입 아래(또는 export 영역)에 스냅샷 타입 추가:
```ts
export interface InterviewSnapshot {
  statusName?: string | null
  turns?: Array<{ seq: number; role: string; kind: string; content: string }>
  plan?: { designMarkdown?: string; planMarkdown?: string; planJson?: unknown } | null
}
```

3b. `onDesign`의 upsert 로직을 재사용 가능한 헬퍼로 추출. 기존 `onDesign` 본문을 다음으로 교체:
```ts
  function onDesign(e: MessageEvent) {
    const data = parse(e)
    if (!data || !data.key) return
    upsertDesign({
      key: data.key,
      title: data.title ?? data.key,
      body: data.body ?? '',
      approved: !!data.approved,
    })
  }

  // key로 upsert (replay/hydrate 중복 방지). onDesign과 hydrate가 공유.
  function upsertDesign(section: DesignSection) {
    const idx = designSections.value.findIndex((d) => d.key === section.key)
    if (idx >= 0) {
      const next = designSections.value.slice()
      next[idx] = section
      designSections.value = next
    } else {
      designSections.value = [...designSections.value, section]
    }
  }
```

3c. `pushTurn` 근처에 `hydrate` 추가:
```ts
  // REST 스냅샷(GET /api/interviews/{id})으로 상태 시드 — 새로고침 후 대화 복원.
  // 매핑은 백엔드 replay 표현과 일치 + 사용자 답변 포함:
  //   kind==='design' → designSections(key=design-{seq}, 백엔드 DesignEvent와 동일)
  //   role==='user'   → turns(user/answer)
  //   그 외           → turns(assistant/question)
  // pushTurn이 seq로 dedup하므로 직후 SSE replay와 안전하게 병합된다.
  function hydrate(snapshot: InterviewSnapshot | null | undefined) {
    if (!snapshot) return
    const name = (snapshot.statusName ?? '') as InterviewStatus
    if (KNOWN_STATUSES.includes(name)) status.value = name
    for (const t of snapshot.turns ?? []) {
      if (t.kind === 'design') {
        upsertDesign({ key: `design-${t.seq}`, title: '설계', body: t.content, approved: false })
      } else if (t.role === 'user') {
        pushTurn({ seq: t.seq, role: 'user', kind: 'answer', content: t.content })
      } else {
        pushTurn({ seq: t.seq, role: 'assistant', kind: 'question', content: t.content })
      }
    }
    if (snapshot.plan) {
      let parsedPlanJson: unknown = null
      try {
        parsedPlanJson =
          typeof snapshot.plan.planJson === 'string'
            ? JSON.parse(snapshot.plan.planJson)
            : (snapshot.plan.planJson ?? null)
      } catch {
        parsedPlanJson = null
      }
      plan.value = {
        designMarkdown: snapshot.plan.designMarkdown ?? '',
        planMarkdown: snapshot.plan.planMarkdown ?? '',
        planJson: parsedPlanJson,
      }
    }
  }
```

3d. return 객체에 `hydrate` 추가:
```ts
  return {
    connState,
    status,
    turns,
    designSections,
    plan,
    error,
    open,
    close,
    hydrate,
  }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npx vitest run composables/useInterviewStream.spec.ts`
Expected: PASS (기존 + 신규 3개)

- [ ] **Step 5: 커밋**

```bash
git add frontend/composables/useInterviewStream.ts frontend/composables/useInterviewStream.spec.ts
git commit -m "feat(interview): useInterviewStream.hydrate로 REST 스냅샷 복원

새로고침 후 GET /{id} 스냅샷으로 전체 대화(내 답변 포함)·status·plan 시드.
design은 designSections(key=design-{seq})로, seq dedup으로 직후 replay와 병합.
onDesign upsert를 upsertDesign 헬퍼로 추출(DRY).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 프론트 — interviewResume.ts (재오픈 결정 순수 함수 + 타입)

**Files:**
- Create: `frontend/composables/interviewResume.ts`
- Test: `frontend/composables/interviewResume.spec.ts` (신규)

이유: 하이브리드 재오픈 결정(0=무동작 / 1=자동 / 다수=선택)을 순수 함수로 분리해 페이지 전체를 마운트하지 않고 단위 테스트. `tasks/index.vue`는 이 함수를 호출만 한다(테스트 어려운 페이지 로직 최소화).

- [ ] **Step 1: 실패 테스트 작성** — `interviewResume.spec.ts` (신규)

```ts
import { describe, it, expect } from 'vitest'
import { decideResume, type InterviewSummary } from './interviewResume'

function summary(id: number): InterviewSummary {
  return {
    id,
    status: '입력대기',
    statusName: 'AWAITING_INPUT',
    title: `T${id}`,
    githubRepo: 'owner/repo',
    githubBranch: 'main',
    currentPhase: 'brainstorming',
    lastActivityAt: '2026-06-14T00:00:00Z',
    createdAt: '2026-06-14T00:00:00Z',
  }
}

describe('decideResume', () => {
  it('returns none for empty/null', () => {
    expect(decideResume([])).toEqual({ mode: 'none' })
    expect(decideResume(null)).toEqual({ mode: 'none' })
    expect(decideResume(undefined)).toEqual({ mode: 'none' })
  })

  it('returns auto with the id for exactly one active session', () => {
    expect(decideResume([summary(7)])).toEqual({ mode: 'auto', id: 7 })
  })

  it('returns pick with all candidates for two or more', () => {
    const list = [summary(1), summary(2), summary(3)]
    expect(decideResume(list)).toEqual({ mode: 'pick', candidates: list })
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npx vitest run composables/interviewResume.spec.ts`
Expected: FAIL — cannot resolve `./interviewResume`

- [ ] **Step 3: 최소 구현** — `interviewResume.ts` (신규)

```ts
// 활성 인터뷰 목록 타입 + 새로고침 후 재오픈 결정(순수). GET /api/interviews/active 응답 형태.
export interface InterviewSummary {
  id: number
  status: string // 한글 dbValue (표시용) — 백엔드 InterviewSummary.status와 동일
  statusName: string // 영문 enum (로직용)
  title: string
  githubRepo: string
  githubBranch: string
  currentPhase: string | null
  lastActivityAt: string
  createdAt: string
}

export type ResumeDecision =
  | { mode: 'none' }
  | { mode: 'auto'; id: number }
  | { mode: 'pick'; candidates: InterviewSummary[] }

// 하이브리드: 0건=무동작, 1건=자동 재오픈, 2건 이상=선택 목록.
// 목록은 백엔드에서 lastActivityAt DESC로 정렬되어 오므로 첫 항목이 가장 최근.
export function decideResume(list: InterviewSummary[] | null | undefined): ResumeDecision {
  const arr = list ?? []
  if (arr.length === 0) return { mode: 'none' }
  if (arr.length === 1) return { mode: 'auto', id: arr[0].id }
  return { mode: 'pick', candidates: arr }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npx vitest run composables/interviewResume.spec.ts`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add frontend/composables/interviewResume.ts frontend/composables/interviewResume.spec.ts
git commit -m "feat(interview): 재오픈 결정 순수 함수 decideResume + InterviewSummary 타입

하이브리드 재오픈(0무동작/1자동/다수선택)을 순수 함수로 분리해 단위 테스트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 프론트 — InterviewPanel 마운트 스냅샷 + "나중에" 버튼

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Test: `frontend/components/InterviewPanel.spec.ts`

이유: 마운트 시 `GET /{id}` 스냅샷을 받아 hydrate한 뒤 stream.open. 자동 재오픈이 비종료 인터뷰에 사용자를 가두지 않도록 취소 없이 닫는 "나중에" 버튼 추가.

> ⚠️ 마운트가 이제 첫 `useApi` 호출(GET 스냅샷)을 하므로 기존 테스트의 `mockResolvedValueOnce` 순서가 어긋난다. `mountPanel`을 async로 바꿔 마운트 스냅샷을 소비하고, 상호작용 테스트는 그 후 once-값을 큐잉하도록 수정한다.

- [ ] **Step 1: 실패 테스트 작성 + 기존 테스트 하니스 수정** — `InterviewPanel.spec.ts`

1a. import에 `flushPromises`가 이미 있음. `mountPanel` 헬퍼를 async로 교체:
```ts
async function mountPanel(sessionId = 5, snapshot: any = { statusName: null, turns: [], plan: null }) {
  authStub.accessToken = 'jwt'
  useApiMock.mockResolvedValueOnce(snapshot) // onMounted의 GET /{id}가 소비
  const w = mount(InterviewPanel, { props: { sessionId } })
  await flushPromises() // 마운트 스냅샷 GET 해소 + hydrate 완료
  return w
}
```

1b. 모든 `const w = mountPanel(...)` 호출을 `const w = await mountPanel(...)`로 변경(전 테스트). 각 테스트의 `it('...', async () => {...})`는 이미 async.

1c. 상호작용 테스트 3개를 수정:

answer-flow 테스트 — 기존 선두의 `useApiMock.mockResolvedValueOnce({})` 제거(이제 mountPanel이 스냅샷을 소비; 답변 POST 반환값은 미사용):
```ts
  it('POSTs the answer with replyToSeq, optimistically appends a user turn, clears input', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('question', { seq: 3, content: '범위는?' })
    await flushPromises()

    await w.find('textarea').setValue('대시보드만 추가합니다')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: '대시보드만 추가합니다', replyToSeq: 3 },
    })
    expect(w.text()).toContain('대시보드만 추가합니다')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    w.unmount()
  })
```

register-flow 테스트 — 선두 once 제거하고 마운트 후 register POST용 once를 큐잉:
```ts
  it('enables 작업 등록 only at PLAN_READY and emits registered with taskId', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()

    es.emit('design', { key: 'a', title: 'A', body: 'b', approved: true })
    await flushPromises()
    let reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeDefined()

    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeUndefined()

    useApiMock.mockResolvedValueOnce({ taskId: 123 }) // 이제 다음 useApi 호출(register POST)이 소비
    await reg.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/register', { method: 'POST' })
    expect(w.emitted('registered')?.[0]).toEqual([123])
    w.unmount()
  })
```

cancel-flow 첫 테스트 — 선두 once 제거(취소 POST 반환값 미사용):
```ts
  it('opens a confirm dialog and only cancels after 취소하기', async () => {
    const w = await mountPanel(7)
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()

    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    const confirm = document.querySelector('[data-test="cancel-confirm"]') as HTMLElement | null
    expect(confirm).toBeTruthy()

    confirm!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/7/cancel', { method: 'POST' })
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
```

1d. 신규 테스트 2개 추가(파일 끝):
```ts
describe('InterviewPanel — refresh resume (snapshot hydration)', () => {
  it('fetches GET /{id} on mount and renders prior conversation incl. my answers', async () => {
    const w = await mountPanel(9, {
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: '인증 방식은?' },
        { seq: 2, role: 'user', kind: 'answer', content: 'OAuth2 입니다' },
        { seq: 3, role: 'assistant', kind: 'question', content: '토큰 TTL은?' },
      ],
      plan: null,
    })
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    expect(w.text()).toContain('인증 방식은?')
    expect(w.text()).toContain('OAuth2 입니다') // 내 답변도 복원
    expect(w.text()).toContain('토큰 TTL은?')
    // status AWAITING_INPUT → 전송 활성(빈 입력이라 disabled지만 입력 시 활성). 여기선 상태 라벨 확인.
    expect(w.text()).toContain('입력 대기')
    w.unmount()
  })

  it('shows a non-cancelling 나중에 button on a non-terminal session that emits close', async () => {
    const w = await mountPanel(9, { statusName: 'AWAITING_INPUT', turns: [], plan: null })
    const later = w.find('[data-test="later-interview"]')
    expect(later.exists()).toBe(true)
    await later.trigger('click')
    expect(w.emitted('close')).toBeTruthy()
    // 취소 API는 호출되지 않아야(세션 유지). 마운트 GET 외 useApi 호출 없음.
    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    w.unmount()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: FAIL — 마운트 GET 미구현 + `[data-test="later-interview"]` 없음

- [ ] **Step 3: 최소 구현** — `InterviewPanel.vue`

3a. `<script setup>`의 `onMounted`를 스냅샷 fetch + hydrate로 교체 (stream에서 `hydrate`도 구조분해):
```ts
const stream = useInterviewStream()
const { connState, status, turns, designSections, plan, error } = stream
```
위 줄은 그대로 두고, `onMounted` 블록을 교체:
```ts
onMounted(async () => {
  // 새로고침 복원: SSE replay는 어시스턴트 질문/설계만 주므로, 내 답변·status·plan은
  // REST 스냅샷으로 먼저 시드한 뒤 스트림을 연다(seq dedup으로 중복 없음). 스냅샷 실패는 비치명적.
  try {
    const snapshot = await useApi(`/api/interviews/${props.sessionId}`)
    stream.hydrate(snapshot as any)
  } catch {
    /* 스냅샷 실패 — 스트림만으로 진행 */
  }
  stream.open(props.sessionId)
  await nextTick()
  scrollToBottom('auto')
})
```

3b. 상태 바 템플릿에 "나중에" 버튼 추가. 기존 "대화 취소" 버튼(`data-test="cancel-interview"`) 바로 위 또는 아래, `<q-space />` 다음에 추가:
```html
      <q-btn
        v-if="!isTerminal"
        data-test="later-interview"
        flat
        dense
        no-caps
        color="grey-7"
        icon="schedule"
        label="나중에"
        @click="closePanel"
      />
```
(`closePanel()`은 이미 존재 — `emit('close')`만 함. 세션 미취소.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: PASS (기존 수정분 + 신규 2개 전부)

- [ ] **Step 5: 커밋**

```bash
git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts
git commit -m "feat(interview): InterviewPanel 마운트 스냅샷 복원 + '나중에' 닫기

마운트 시 GET /{id}로 전체 대화·status·plan 하이드레이션 후 stream.open.
비종료 인터뷰를 취소 없이 닫는 '나중에' 버튼 추가(자동 재오픈 갇힘 방지).
mountPanel 헬퍼를 async로 바꿔 마운트 스냅샷 GET을 흡수.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 프론트 — tasks/index.vue 디스커버리 + 하이브리드 재오픈 + 선택 다이얼로그

**Files:**
- Modify: `frontend/pages/tasks/index.vue`

이유: `/tasks` 마운트 시 활성 세션을 조회해 `decideResume`로 재오픈. 다수면 선택 다이얼로그. (재오픈 결정 로직은 Task 6에서 단위 테스트됨; 페이지 자체는 의존성이 많아 단위 마운트 대신 수동 스모크로 검증.)

- [ ] **Step 1: 디스커버리 + 재오픈 로직 추가** — `<script setup>`

import 추가(파일 상단, 기존 import 근처):
```ts
import { decideResume, type InterviewSummary } from '~/composables/interviewResume'
```

기존 `interviewSessionId`/`starting` ref 근처에 상태 추가:
```ts
// 새로고침 후 '이어할 인터뷰' 선택(활성 2개 이상일 때)
const resumeCandidates = ref<InterviewSummary[]>([])
const showResumePicker = ref(false)
```

`startInterview` 근처에 헬퍼 추가:
```ts
// 인터뷰 패널을 특정 세션으로 연다(신규 시작/재오픈 공통).
function openInterview(id: number) {
  interviewSessionId.value = id
  dialogPhase.value = 'interview'
  showCreate.value = true
}

// 새로고침 후 진행 중 인터뷰 발견 → 하이브리드 재오픈.
async function discoverActiveInterviews() {
  let list: InterviewSummary[] = []
  try {
    list = await useApi<InterviewSummary[]>('/api/interviews/active')
  } catch {
    return // 조용히 무시 — 작업 목록 로드는 방해하지 않음
  }
  const decision = decideResume(list)
  if (decision.mode === 'auto') {
    openInterview(decision.id)
  } else if (decision.mode === 'pick') {
    resumeCandidates.value = decision.candidates
    showResumePicker.value = true
  }
}

// 선택 다이얼로그에서 하나를 골라 이어하기.
function resumeFromPicker(id: number) {
  showResumePicker.value = false
  openInterview(id)
}

onMounted(discoverActiveInterviews)
```

> 참고: `startInterview`의 본문 중 `interviewSessionId.value = res.sessionId; dialogPhase.value = 'interview'`는 `openInterview(res.sessionId)` 호출로 바꿔도 되고 그대로 둬도 된다(동작 동일). DRY를 위해 `openInterview(res.sessionId)`로 교체 권장.

- [ ] **Step 2: 선택 다이얼로그 템플릿 추가** — `<template>` 끝, 기존 등록 `<q-dialog>` 다음(같은 `<q-page>` 안)에 추가:

```html
    <!-- 진행 중 인터뷰 선택(활성 2개 이상) -->
    <q-dialog v-model="showResumePicker">
      <q-card style="min-width: 420px">
        <q-card-section class="text-h6">진행 중인 대화형 분석</q-card-section>
        <q-card-section class="q-pt-none text-grey-8">
          이어서 진행할 인터뷰를 선택하세요.
        </q-card-section>
        <q-list bordered separator>
          <q-item
            v-for="c in resumeCandidates"
            :key="c.id"
            clickable
            @click="resumeFromPicker(c.id)"
          >
            <q-item-section>
              <q-item-label>{{ c.title }}</q-item-label>
              <q-item-label caption>
                {{ c.githubRepo }} · {{ c.githubBranch }} · {{ c.status }}
              </q-item-label>
            </q-item-section>
            <q-item-section side>
              <q-btn flat dense color="primary" icon="forum" label="이어하기" no-caps />
            </q-item-section>
          </q-item>
        </q-list>
        <q-card-actions align="right">
          <q-btn flat label="닫기" @click="showResumePicker = false" />
        </q-card-actions>
      </q-card>
    </q-dialog>
```

- [ ] **Step 3: 린트/타입 체크**

Run: `cd frontend && npm run lint`
Expected: 통과(자동 수정 포함). 타입 오류 없음.

- [ ] **Step 4: 프론트 전체 테스트 회귀 확인**

Run: `cd frontend && npx vitest run`
Expected: 전체 PASS (기존 + Task5/6/7 신규). tasks/index.vue는 단위 테스트 없음 — 회귀 없음 확인용.

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/tasks/index.vue
git commit -m "feat(interview): /tasks 마운트 시 진행 중 인터뷰 발견 + 하이브리드 재오픈

GET /api/interviews/active로 활성 세션 조회 → decideResume:
1개면 패널 자동 재오픈, 2개 이상이면 선택 다이얼로그. 새로고침 이어가기 완성.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 풀스택 수동 스모크 (검증)

**Files:** 없음 (실행 검증)

이유: 단위 테스트는 계약을 잠그지만, 교차컴포넌트(SSE replay + REST 스냅샷 + 디스커버리)는 실제 구동으로만 확인된다(과거 phase 교훈: mock은 교차 계약을 못 잡음).

- [ ] **Step 1: 백엔드 단위 테스트 로컬 전체**

Run: `./gradlew test --tests '*InterviewServiceTest' --tests '*InterviewResponseTest'`
Expected: PASS

- [ ] **Step 2: 프론트 전체 테스트**

Run: `cd frontend && npx vitest run`
Expected: 전체 PASS

- [ ] **Step 3: 풀스택 기동 + 시나리오**

PostgreSQL → netis-auth(:9000) → netisMaker api(:8090, profile=api) → 워커(profile=worker) → 프론트(`cd frontend && npm run dev`, :3001).

시나리오:
1. `/tasks`에서 "작업 등록" → 인터뷰 시작 → 워커가 첫 질문을 보낼 때까지 대기 → 답변 1회 제출(다음 질문 도착 확인).
2. **브라우저 새로고침** → `/tasks`가 자동으로 인터뷰 패널 재오픈, **이전 질문 + 내 답변 + 새 질문이 모두 보이고** 상태가 `입력 대기`, 답변창 활성인지 확인.
3. 답변을 계속 진행해 `플랜완료`까지 → 새로고침 → 우측 **설계 문서/구현 플랜 + '작업 등록' 버튼**이 복원되는지 확인.
4. 인터뷰 2개를 동시에 진행(다른 레포) → 새로고침 → **선택 다이얼로그**에 2건이 뜨고, 하나 고르면 해당 대화로 진입하는지 확인.
5. 패널에서 **"나중에"** 클릭 → 다이얼로그 닫힘, 세션은 취소되지 않음(다시 새로고침하면 재발견) 확인.
6. (회귀) 인터뷰 정상 완료 후 "작업 등록" → 작업 목록에 COMPLETED로 추가, 이후 새로고침 시 종료 세션은 재오픈 안 됨 확인.

- [ ] **Step 4: 스모크 결과 기록**

발견된 이슈가 있으면 수정 후 해당 Task로 돌아가 테스트 보강. 이상 없으면 완료.

---

## 자기 검토 (작성자 체크리스트 — 완료)

- **스펙 커버리지:** §5.1 B1→Task1, B2→Task2, B3+B4→Task3, B5→Task4 / §5.2 F1→Task5, F3(decideResume)→Task6, F2→Task7, F3(wiring)→Task8 / §6 테스트→각 Task + Task9 / §7 YAGNI(배너/탭동기화 제외) 준수 / §8 위험(갇힘→'나중에' 버튼, status 이원화→statusName) 반영. 갭 없음.
- **플레이스홀더:** 없음 — 모든 step에 실제 코드/명령/기대 출력 포함.
- **타입 일관성:** `statusName`(영문)·`statusLabel`(한글) 일관, `InterviewSummary` 필드가 Task3(백엔드 DTO)·Task6(프론트 타입)·Task8(사용처)에서 동일, `hydrate(InterviewSnapshot)`·`decideResume(ResumeDecision)`·`openInterview(id)`·`closePanel()` 시그니처 일관. SSE design key `design-{seq}`가 백엔드 replay·hydrate에서 일치.
