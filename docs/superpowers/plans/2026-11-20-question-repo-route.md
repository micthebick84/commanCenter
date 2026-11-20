# 질문 레포 추천 카드(Jev) · DB 연결 제안 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 새 질문 화면에서 레포를 고르지 않고 질문을 보내면 Jev가 카탈로그에서 레포를 추천하고, 대화 화면의 카드에서 한 번 확인받은 뒤(같은 카드에서 DB 접속 제안) 기존 방식으로 세션을 만든다.

**Architecture:** 세션을 만들기 전 단계만 새로 생긴다. API에 `POST /api/questions/route`를 더해 Jev(HTTP)에 한 번 묻고 코드가 임계값으로 상태를 정한다. 화면은 그 응답으로 카드를 그리고, 확정하면 기존 `POST /api/questions`를 `routeId`와 함께 부른다. 인터뷰 서비스·워커·세션 테이블 구조는 그대로다.

**Tech Stack:** Spring Boot(JDK 21, `java.net.http.HttpClient`, Flyway, JPA), Nuxt 3 + Quasar + vitest, TypeSafe System One HTTP API(`jev-1.14.0`).

**Spec:** `docs/superpowers/specs/2026-11-20-question-repo-route-design.md` — 실행하는 사람은 이 계획과 함께 읽는다. 목업: `C:\Users\mic\NetisMaker\.superpowers\brainstorm\1667-1795159087\content\repo-card-layout.html`(A안).

## Global Constraints

- 작업 위치는 worktree `.claude/worktrees/question-repo-route`(브랜치 `feature/question-repo-route`). 본 체크아웃에서는 빌드·테스트하지 않는다(라이브 JVM이 `build/classes`를 쓴다).
- 커밋은 `git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit`, 메시지 끝에 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. 푸시·PR은 사용자가 말할 때만.
- Jev 연동 코드를 쓰기 전에 TypeSafe 현재 문서를 읽는다: `https://docs.typesafe.ai/api.md`, `/primitives/choice.md`, `/primitives/noul.md`, `/patterns/companion-noul.md`, `/confidence.md`. 이 계획의 요청·응답 모양이 문서와 다르면 문서를 따른다.
- 밖으로 나가는 것은 질문 앞 **500자**(공백 정리 뒤), 카탈로그 **별칭**과 **추천 키워드**뿐이다. `description`·첨부·DB 접속정보·사용자 id는 보내지 않는다.
- 질문 본문과 `TYPESAFE_API_KEY`는 로그·예외 메시지·API 응답에 남기지 않는다. 로그는 상태·소요 시간·HTTP 코드만.
- 추천 쪽 문제는 사용자에게 오류로 보이지 않는다: `route`는 200 + `UNAVAILABLE`, 화면은 네트워크 오류에도 `UNAVAILABLE` 카드.
- 사용자가 확인하지 않은 레포로 세션을 만들지 않는다(자동 확정 없음).
- 임계값 시작값: `mentions 0.7`, `listed-yes 0.7`, `listed-no 0.3`, `top-probability 0.5`, `needs-db 0.6`. 모델 `jev-1.14.0`, 엔드포인트 `https://api.typesafe.ai/v1/systemone`, 상한 `3000`ms.
- 마이그레이션은 `V47__question_route.sql`. 병합 직전 main의 최대 번호를 다시 확인한다.
- 프론트 코드 스타일: 작은따옴표·세미콜론 없음·2칸. 클래스 이름에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`) 금지. `prettier --write`를 폴더 전체에 돌리지 않는다. ESLint 경고를 늘리지 않는다.
- 프론트 테스트 전 worktree에 정션이 필요하다: `cmd /c mklink /J frontend\node_modules <본 체크아웃>\frontend\node_modules`(인터뷰 서비스도 같다). worktree를 지우기 전에 `cmd /c rmdir`로 정션부터 지운다.
- Java 한 클래스: `./gradlew test --tests '*클래스이름'`. Testcontainers 시험은 이 PC에서 건너뛰고 CI가 돌린다.

## Review Focus

1. Jev 응답의 확률 표에 보낸 적 없는 키가 있거나 보낸 키가 빠져 있다 → 모르는 키는 버리고, 쓸 수 있는 확률이 하나도 없으면 `UNAVAILABLE`. (Task 3)
2. 질문 500자 경계가 이모지(서로게이트 쌍) 가운데다 → 깨진 글자를 보내지 않는다. (Task 5)
3. 카드가 떠 있는 동안 고른 레포가 카탈로그에서 꺼졌다 → 등록 400이면 카드에 남아 서버 문구를 알리고 다른 레포를 고를 수 있다. (Task 9)
4. 카드에서 레포를 빠르게 바꿔 고른다 → 늦게 온 이전 레포의 브랜치·DB 응답이 지금 선택을 덮지 않는다. (Task 8)
5. 같은 `routeId`로 등록이 두 번 온다(두 번 누름·다른 탭), 또는 남의 `routeId`다 → 등록은 성공하고 기록은 처음 선택만 남는다. (Task 6)

---

## File Structure

| 파일 | 책임 |
|---|---|
| `netismaker-interview-service/scripts/spikeJevRoute.ts` (새로) | 한국어 정확도 확인 스크립트 |
| `src/main/resources/db/migration/V47__question_route.sql` (새로) | `repo_catalog.route_hint`, `question_route_log` |
| `…/config/QuestionRouteProperties.java` (새로) | `app.question-route.*` 설정 |
| `…/service/route/JevClient.java` (새로) | Jev HTTP 호출 한 곳 |
| `…/service/route/RouteDecision.java` (새로) | 임계값 → 상태(순수 함수) |
| `…/service/route/QuestionRouteService.java` (새로) | 발췌·후보·최근 레포·기록·응답 조립, 선택 연결 |
| `…/entity/QuestionRouteLog.java`, `…/repository/QuestionRouteLogRepository.java` (새로) | 추천 기록 |
| `…/dto/QuestionRouteRequest.java`, `…/dto/QuestionRouteResponse.java` (새로) | `route` 바디·응답 |
| `…/controller/QuestionController.java`, `…/service/QuestionService.java`, `…/dto/QuestionCreateRequest.java` (수정) | `route` 엔드포인트, `routeId` |
| `…/entity/RepoCatalogEntry.java`, `…/dto/RepoCatalogDto.java`, `…/service/RepoCatalogService.java`, `…/controller/RepoCatalogController.java` (수정) | 추천 키워드, 등록 가능 목록 |
| `…/repository/InterviewSessionRepository.java` (수정) | 최근 쓴 레포 조회 |
| `…/util/StackSecretEnv.java`, `netismaker-interview-service/src/sdk/childEnv.ts` (수정) | `TYPESAFE_API_KEY` 제거 |
| `frontend/composables/questionRoute.ts` (새로) | 타입·호출·카드 목록·DB 기본 선택(순수) |
| `frontend/composables/repoBranches.ts` (새로) | 브랜치 조회 한 함수 |
| `frontend/components/chat/RepoRouteCard.vue` (새로) | 추천 카드 |
| `frontend/pages/questions/index.vue` (수정) | 자동/직접 모드, 카드 흐름 |
| `frontend/pages/admin/repo-catalog.vue` (수정) | 추천 키워드 입력 칸 |
| `frontend/scripts/hydration-smoke.mjs` (수정) | 새 API 목 |

`…` = `src/main/java/com/hamonsoft/netismaker`.

---

### Task 1: Jev 한국어 정확도 확인 (통과해야 다음으로)

**Files:**
- Create: `netismaker-interview-service/scripts/spikeJevRoute.ts`
- Create: `docs/superpowers/plans/2026-11-20-question-repo-route-spike-result.md`
- 레포 밖 입력: `%USERPROFILE%\netis-maker\typesafe.env`(사용자가 만든다), `%USERPROFILE%\netis-maker\jev-route-spike.json`(이 작업이 만든다)

**Interfaces:**
- Produces: Task 3이 그대로 옮겨 쓸 지시문 4개의 최종 문구와 요청·응답 JSON 모양(결과 문서에 기록).

- [ ] **Step 1: 키 파일 확인.** `typesafe.env`가 없으면 여기서 멈추고 사용자에게 알린다. 건너뛰지 않는다.
- [ ] **Step 2: 입력 파일 만들기.** 라이브 DB에서 읽기 전용으로 `select id, alias from com.repo_catalog where enabled`를 받아 `jev-route-spike.json`의 `repos: [{id, alias, hint}]`를 채운다. `hint`는 초안(고객사·사이트 별칭, 버전, 대시보드 여부 — 사람 이름 금지). `cases`는 지어낸 질문 30개 안팎: `{ q, kind, expect: { state, repoId?, db } }`, `kind`는 `site`(사이트를 말함) 8개 이상 · `version`(버전만 말함) 6개 이상 · `none`(아무것도 말하지 않음) 6개 이상 · `unlisted`(없는 사이트) 4개 이상, 그중 DB가 필요한 질문 8개 이상. 실제 사용자 질문은 쓰지 않는다. 이 파일은 커밋하지 않는다(고객사 이름).
- [ ] **Step 3: 스크립트 작성.** `npx tsx scripts/spikeJevRoute.ts`로 돈다. Node 내장 `fetch`만 쓴다(의존성 추가 없음). 요청은 한 건에 질문 4개:

```json
{
  "model": "jev-1.14.0",
  "state": { "question": "<앞 500자>", "repositories": [ { "key": "r11", "name": "<별칭>", "hint": "<키워드>" } ] },
  "questions": {
    "repo": { "type": "choice",
      "instructions": "Which repository is the user's `question` about? Pick the repository whose site, customer, product or version the question refers to.",
      "criteria": { "r11": "<별칭> — <키워드>" } },
    "mentions_target": { "type": "noul",
      "instructions": "Does `question` refer by name to a specific site, customer, product, or product version (for example a customer name or a version number)?" },
    "target_listed": { "type": "noul",
      "instructions": "Is the site, customer, product, or version that `question` refers to one of the entries in `repositories`? Answer no if the question names something that is not in `repositories`, or names nothing specific." },
    "needs_db": { "type": "noul",
      "instructions": "Would answering `question` require looking at an actual database (table structure, columns, stored rows, or query results) rather than reading source code only?" }
  }
}
```

`criteria`의 키 순서는 요청마다 섞는다. 키워드가 없으면 값은 별칭만. 키는 출력하지 않는다. 판정은 Global Constraints의 시작값으로 한다(spec §6.2).
- [ ] **Step 4: 두 번 돌린다** — 키워드 없이(별칭만), 키워드와 함께. 각각 `kind`별 상태 적중률, `site`+`version`의 추천 레포 1위 적중률, `needs_db` 적중률, `none` 질문에서 `repo` 1위 확률의 분포(최소·중앙·최대), 평균 응답 시간을 출력한다.
- [ ] **Step 5: 판정.** 키워드를 함께 보낸 쪽에서 `site`+`version`의 1위 적중률이 80% 미만이면 **멈추고 사용자에게 숫자와 틀린 사례를 알린다**(지시문을 한두 번 고쳐 다시 재 보는 것은 된다). 80% 이상이면 계속한다. `none` 분포가 고르게 퍼져 있으면(중앙값 0.25 미만) Task 7의 `비슷한 후보` 하한을 `0.25`로, 아니면 `0`으로 정한다.
- [ ] **Step 6: 결과 문서 작성.** 숫자(범주별, 두 번 다), 최종 지시문 4개, 실제 응답에서 확인한 JSON 모양(`answers.repo.probabilities`, `answers.<id>.noul`, `model`), 정한 하한값, 사용자에게 건넬 키워드 초안은 "별도 전달"로만 적는다(고객사 이름을 문서에 넣지 않는다).
- [ ] **Step 7: Commit** — 스크립트와 결과 문서만.

---

### Task 2: V47 · 카탈로그 추천 키워드 · 등록 가능 목록

**Files:**
- Create: `src/main/resources/db/migration/V47__question_route.sql`
- Modify: `…/entity/RepoCatalogEntry.java`, `…/dto/RepoCatalogDto.java`, `…/service/RepoCatalogService.java`, `…/controller/RepoCatalogController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceRouteHintTest.java`(새로)

**Interfaces:**
- Produces:
  - `RepoCatalogEntry.getRouteHint(): String`(null 가능)
  - `RepoCatalogDto.View`의 **마지막** 성분 `String routeHint`, `View withoutRouteHint()`
  - `RepoCatalogDto.UpsertRequest`의 **마지막** 성분 `@Size(max = 500) String routeHint`(기존 15개 인자 호환 생성자 추가)
  - `RepoCatalogService.listRoutable(): List<RepoCatalogEntry>` — 켜져 있고 `RepoUrlParser.parse(gitUrl, gitlabBaseUrl)`가 예외 없이 `host != "other"`·`ownerRepo != null`인 행, 별칭 순

- [ ] **Step 1: 마이그레이션 작성.**

```sql
-- 질문 레포 추천(스펙 2026-11-20 §6.4·§7): 추천 키워드 + 추천 기록.
ALTER TABLE com.repo_catalog ADD COLUMN route_hint TEXT;

CREATE TABLE com.question_route_log (
    id                          BIGSERIAL PRIMARY KEY,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id                     VARCHAR(20) NOT NULL,
    question_excerpt            TEXT NOT NULL,
    state                       VARCHAR(20) NOT NULL,
    model                       VARCHAR(50),
    candidates                  JSONB NOT NULL DEFAULT '[]',
    mentions_target             DOUBLE PRECISION,
    target_listed               DOUBLE PRECISION,
    needs_db                    DOUBLE PRECISION,
    recommended_repo_catalog_id BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    chosen_repo_catalog_id      BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    session_id                  BIGINT REFERENCES com.interview_session(id) ON DELETE SET NULL,
    chosen_at                   TIMESTAMPTZ,
    latency_ms                  INT
);
CREATE INDEX ix_question_route_log_created ON com.question_route_log(created_at);
```

- [ ] **Step 2: 실패하는 테스트 작성**(Mockito, `QuestionServiceTest`와 같은 손 조립 방식).

```java
@Test void 수정에서_routeHint가_null이면_유지한다()      // 기존 "키워드" → update(req.routeHint=null) → "키워드"
@Test void 수정에서_빈_문자열이면_비운다()                // update(routeHint="  ") → null
@Test void 등록에서_공백뿐이면_null로_저장한다()
@Test void listRoutable은_꺼진_행과_지원하지_않는_호스트를_뺀다() // github 1 + gitlab 1 + other 1 + 꺼진 1 → 2개
@Test void 일반_목록_View는_routeHint가_null이다()       // View.of(e).withoutRouteHint().routeHint() == null
```

- [ ] **Step 3: 실패 확인** — `./gradlew test --tests '*RepoCatalogServiceRouteHintTest'`, 컴파일 오류로 실패.
- [ ] **Step 4: 구현.** 엔티티 `@Column(name = "route_hint", columnDefinition = "TEXT")`. `RepoCatalogController.listForUsers()`만 `withoutRouteHint()`를 건다(관리자 목록·등록·수정 응답은 값을 싣는다). `View`·`UpsertRequest`에 성분을 더하면 기존 호출부가 깨지므로 호환 생성자·팩터리로 맞춘다.
- [ ] **Step 5: 통과 확인** — 위 클래스와 `./gradlew test --tests '*RepoCatalog*'`.
- [ ] **Step 6: Commit.**

---

### Task 3: 설정 · JevClient · 비밀 제거 목록

**Files:**
- Create: `…/config/QuestionRouteProperties.java`, `…/service/route/JevClient.java`
- Modify: `…/NetisMakerApplication.java`(`@EnableConfigurationProperties`에 추가), `src/main/resources/application.yml`, `…/util/StackSecretEnv.java`, `netismaker-interview-service/src/sdk/childEnv.ts`
- Test: `src/test/java/com/hamonsoft/netismaker/service/route/JevClientTest.java`(새로), `…/util/StackSecretEnvTest.java`·`netismaker-interview-service/test/childEnv.test.ts`(수정)

**Interfaces:**
- Consumes: Task 1 결과 문서의 지시문 4개와 JSON 모양.
- Produces:

```java
@ConfigurationProperties("app.question-route")
public record QuestionRouteProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("jev-1.14.0") String model,
        @DefaultValue("https://api.typesafe.ai/v1/systemone") String endpoint,
        @DefaultValue("3000") int timeoutMs,
        @DefaultValue("500") int excerptChars,
        @DefaultValue Threshold threshold) {
    public record Threshold(
            @DefaultValue("0.7") double mentions,
            @DefaultValue("0.7") double listedYes,
            @DefaultValue("0.3") double listedNo,
            @DefaultValue("0.5") double topProbability,
            @DefaultValue("0.6") double needsDb) {}
}

// JevClient (@Component @Profile("api")), 생성자 (QuestionRouteProperties props, ObjectMapper om)
public record RepoOption(long id, String alias, String hint) {}
public record JevAnswers(String model, Map<Long, Double> repoProbabilities,
                         double mentionsTarget, double targetListed, double needsDb) {}
public boolean available();                                  // enabled && apiKey가 비어 있지 않음
public Optional<JevAnswers> ask(String excerpt, List<RepoOption> repos);  // 실패는 전부 Optional.empty()
```

`application.yml`(`app:` 아래):

```yaml
  question-route:                         # 질문 레포 추천(스펙 2026-11-20). 키가 없으면 추천만 꺼진다
    enabled: true
    api-key: ${TYPESAFE_API_KEY:}
    model: jev-1.14.0
    endpoint: https://api.typesafe.ai/v1/systemone
    timeout-ms: 3000
    excerpt-chars: 500
```

- [ ] **Step 1: 실패하는 테스트 작성** — `GitLabProjectClientTest`처럼 `com.sun.net.httpserver.HttpServer`를 `127.0.0.1:0`에 띄우고 `endpoint`를 그 주소로 준다.

```java
@Test void 정상_응답에서_확률과_noul을_읽는다()            // r3:0.9,r8:0.1 → {3:0.9, 8:0.1}, mentions 0.93 …, model은 응답값
@Test void 요청에_Bearer_키와_모델_상태_질문_4개가_실린다() // Authorization, model, state.question, state.repositories[].key="r3", questions 키 4개
@Test void 키워드가_없으면_기준은_별칭만이다()             // criteria.r8 == "Netis6.8", 있으면 "별칭 — 키워드"
@Test void 요청마다_선택지_순서가_달라질_수_있다()         // 20번 호출해 criteria 키 순서가 2가지 이상
@Test void 보낸_적_없는_키는_버리고_전부_모르는_키면_빈_값() // {"r999":1.0} → Optional.empty()
@Test void 숫자_4xx_5xx_깨진_JSON_빠진_답은_빈_값()        // 401, 500, "not json", noul 하나 빠짐
@Test void 상한을_넘기면_빈_값()                           // 서버 지연 > timeoutMs(테스트는 300ms)
@Test void 응답_429는_한_번만_다시_시도한다()              // 429 → 200 이면 값, 429 → 429 면 빈 값(요청 2번)
@Test void 응답_529도_한_번만_다시_시도한다()
@Test void 키가_없거나_꺼져_있으면_부르지_않는다()          // available()=false, ask는 빈 값, 서버 요청 0번
@Test void 후보가_없으면_부르지_않는다()                    // repos 빈 목록 → 빈 값, 요청 0번
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*JevClientTest'`.
- [ ] **Step 3: 구현.** `java.net.http.HttpClient`(필드, `connectTimeout`), 전체 상한은 `timeoutMs` 하나로 재고 재시도는 남은 시간 안에서만 한다. `log`에는 `상태 코드·소요 ms·결과(ok/timeout/http/parse)`만. 지시문은 Task 1 결과 문서의 문구를 상수로 옮긴다.
- [ ] **Step 4: 비밀 제거 목록.** `StackSecretEnv.NAMES`와 `childEnv.ts`의 `STACK_SECRET_ENV` 끝에 `"TYPESAFE_API_KEY"`를 더하고 두 가드 테스트의 기대 목록을 고친다. CLAUDE.md가 말하는 대로 두 목록은 같아야 한다.
- [ ] **Step 5: 통과 확인** — `*JevClientTest`, `*StackSecretEnvTest`, 인터뷰 서비스의 `test/childEnv.test.ts`(그 패키지의 `test` 스크립트 방식으로).
- [ ] **Step 6: Commit.**

---

### Task 4: 판정 (순수 함수)

**Files:**
- Create: `…/service/route/RouteDecision.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/route/RouteDecisionTest.java`

**Interfaces:**
- Consumes: `JevClient.JevAnswers`, `QuestionRouteProperties.Threshold`.
- Produces:

```java
public enum RouteState { CONFIDENT, AMBIGUOUS, NO_MATCH, UNAVAILABLE }
public record Candidate(Long repoCatalogId, double probability) {}
public record Decision(RouteState state, Long recommendedRepoCatalogId, List<Candidate> candidates, Boolean dbNeeded) {}
public static Decision decide(JevClient.JevAnswers a, QuestionRouteProperties.Threshold t); // candidates = 확률 내림차순 상위 5
public static Decision unavailable();   // UNAVAILABLE, null, [], null
```

- [ ] **Step 1: 실패하는 테스트 작성.**

```java
// (mentions, listed, top) → state, recommended
@Test void 셋_다_넘으면_CONFIDENT이고_1위를_추천한다()   // 0.7, 0.7, 0.5 (경계 포함) → CONFIDENT, 1위 id
@Test void 일위_확률이_모자라면_AMBIGUOUS()              // 0.9, 0.9, 0.49 → AMBIGUOUS, recommended null
@Test void 가리키는_곳이_없으면_AMBIGUOUS()              // 0.69, 0.9, 0.9 → AMBIGUOUS
@Test void 가리키는데_목록에_없으면_NO_MATCH()           // 0.7, 0.3 (경계 포함), 0.9 → NO_MATCH, recommended null
@Test void 목록_여부가_어중간하면_AMBIGUOUS()            // 0.9, 0.5, 0.9 → AMBIGUOUS
@Test void 후보는_확률_내림차순_상위_5개다()             // 7개 입력 → 5개, 정렬 확인, 동률은 id 오름차순
@Test void needsDb는_경계_포함으로_참이다()              // 0.6 → true, 0.59 → false
@Test void unavailable은_빈_후보와_null_dbNeeded()
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RouteDecisionTest'`.
- [ ] **Step 3: 구현** — spec §6.2의 규칙 그대로.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: Commit.**

---

### Task 5: 추천 기록 · 서비스 · `POST /api/questions/route`

**Files:**
- Create: `…/entity/QuestionRouteLog.java`, `…/repository/QuestionRouteLogRepository.java`, `…/service/route/QuestionRouteService.java`, `…/dto/QuestionRouteRequest.java`, `…/dto/QuestionRouteResponse.java`
- Modify: `…/repository/InterviewSessionRepository.java`, `…/controller/QuestionController.java`
- Test: `…/service/route/QuestionRouteServiceTest.java`, `…/controller/QuestionControllerRouteTest.java`(새로, `QuestionControllerAskNotesTest`처럼 컨트롤러를 직접 생성), `…/repository/QuestionRouteQueriesIntegrationTest.java`(새로, Testcontainers — CI에서만)

**Interfaces:**
- Consumes: `RepoCatalogService.listRoutable()`, `JevClient.available()/ask(...)`, `RouteDecision.decide/unavailable`.
- Produces:

```java
public record QuestionRouteRequest(@NotBlank @Size(max = 20000) String question) {}
public record QuestionRouteResponse(Long routeId, String state, Long recommendedRepoCatalogId,
        List<RouteDecision.Candidate> candidates, List<Long> recentRepoCatalogIds,
        List<Long> eligibleRepoCatalogIds, Boolean dbNeeded) {}

// QuestionRouteService (@Service @Profile("api"))
@Transactional public QuestionRouteResponse route(String question, String userId);
public static String excerpt(String question, int maxChars);   // 공백류 연속 → 한 칸, trim, 코드포인트 기준으로 자름

// InterviewSessionRepository
@Query("""
    SELECT s.repoCatalogId FROM InterviewSession s
    WHERE s.requesterId = :requesterId
      AND s.kind = com.hamonsoft.netismaker.entity.InterviewKind.QUESTION
      AND s.repoCatalogId IS NOT NULL
    GROUP BY s.repoCatalogId
    ORDER BY MAX(s.createdAt) DESC
""")
List<Long> findRecentQuestionRepoCatalogIds(@Param("requesterId") String requesterId, Pageable pageable);

// QuestionController
@PostMapping("/route")
public QuestionRouteResponse route(@RequestBody @Valid QuestionRouteRequest req, JwtAuthenticationToken auth);
```

`QuestionRouteLog`: V47의 칸 그대로(`candidates`는 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`, `InterviewSession.refRepos`와 같은 방식). 정적 팩터리 `of(userId, excerpt, decision, answers /*null 가능*/, latencyMs)`와 `markChosen(Long repoCatalogId, Long sessionId)`(이미 `chosenAt`이 있으면 아무것도 하지 않는다).

- [ ] **Step 1: 실패하는 서비스 테스트 작성**(Mockito).

```java
@Test void 추천이_있으면_CONFIDENT와_후보_최근_등록가능_목록을_돌려주고_기록한다()
      // listRoutable=[3,8,11], recent=[8,99,3] → recent는 [8,3](등록 가능한 것만, 최대 3), eligible=[3,8,11], routeId=저장된 id
@Test void 클라이언트를_쓸_수_없으면_UNAVAILABLE이고_Jev를_부르지_않지만_기록은_남긴다() // available=false → state/model null/candidates []
@Test void Jev가_빈_값이면_UNAVAILABLE()
@Test void 등록_가능한_레포가_없으면_UNAVAILABLE이고_Jev를_부르지_않는다()
@Test void Jev에는_발췌와_별칭_키워드만_넘긴다()            // ArgumentCaptor: excerpt 길이 ≤ 500, RepoOption(id, alias, routeHint)
@Test void 기록의_발췌는_Jev에_보낸_것과_같다()
@Test void excerpt는_공백을_한_칸으로_줄이고_앞에서_자른다() // "a \n\t b" → "a b"; 600자 → 500자
@Test void excerpt는_서로게이트_쌍_가운데서_자르지_않는다()  // 499자 + "😀" + "x" → 끝이 온전한 "😀"(코드포인트 500개)
@Test void 후보와_추천은_등록_가능한_id만_담는다()
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성.**

```java
@Test void route는_사용자_id로_서비스를_부르고_응답을_그대로_돌려준다()  // claim username=user1
```

- [ ] **Step 3: Testcontainers 테스트 작성**(`@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")`, 기존 통합 테스트의 설정을 따른다).

```java
@Test void V47이_적용되고_추천_기록을_저장하고_읽는다()          // candidates JSONB 왕복
@Test void 최근_레포는_마지막으로_쓴_순서이고_질문_세션만_센다()  // 같은 사용자 QUESTION 3건(레포 8,3,8) + INTERVIEW 1건 → [8,3]
@Test void 카탈로그_행을_지우면_기록의_레포_칸이_NULL이_된다()
```

- [ ] **Step 4: 실패 확인** — `*QuestionRouteServiceTest`, `*QuestionControllerRouteTest`.
- [ ] **Step 5: 구현.** 기동 로그는 `QuestionRouteService` 생성자에서 한 줄: `질문 레포 추천: 켜짐(model=…)` / `꺼짐(키 없음)` / `꺼짐(설정 off)`(`RenderTargetPolicy` 선례). 최근 레포는 `PageRequest.of(0, 10)`로 받아 등록 가능한 것만 3개. 소요 시간은 Jev 호출 구간만 잰다.
- [ ] **Step 6: 통과 확인** — 위 두 클래스 + `./gradlew test --tests '*Question*'`.
- [ ] **Step 7: Commit.**

---

### Task 6: 질문 등록에 `routeId` 연결

**Files:**
- Modify: `…/dto/QuestionCreateRequest.java`, `…/service/QuestionService.java`, `…/service/route/QuestionRouteService.java`
- Test: `…/service/QuestionServiceTest.java`(수정 — 생성자 인자 추가 + 새 테스트), `…/service/route/QuestionRouteServiceTest.java`(추가)

**Interfaces:**
- Consumes: `QuestionRouteLog.markChosen`, `QuestionRouteLogRepository`.
- Produces:
  - `QuestionCreateRequest`의 **마지막** 성분 `Long routeId`(기존 9개·8개·7개 인자 생성자는 `null`로 위임)
  - `QuestionRouteService.linkChoice(Long routeId, String userId, Long chosenRepoCatalogId, Long sessionId): void` — `@Transactional`을 달지 않는다(부르는 쪽 트랜잭션에 참여). `routeId == null`·기록 없음·`user_id` 다름·이미 선택됨이면 조용히 끝낸다. 예외를 밖으로 내지 않는다.
  - `QuestionService` 생성자에 `QuestionRouteService` 추가.

- [ ] **Step 1: 실패하는 테스트 작성.**

```java
// QuestionRouteServiceTest
@Test void linkChoice는_고른_레포와_세션과_시각을_적는다()
@Test void linkChoice는_routeId가_null이면_저장소를_건드리지_않는다()
@Test void linkChoice는_없는_기록과_남의_기록을_조용히_무시한다()
@Test void linkChoice는_이미_선택된_기록을_덮지_않는다()              // 두 번째 호출 뒤에도 처음 레포·세션 그대로
@Test void linkChoice는_저장소가_예외를_던져도_밖으로_내지_않는다()
// QuestionServiceTest
@Test void 등록은_routeId가_있으면_저장된_세션_id로_linkChoice를_부른다()
@Test void 등록은_routeId가_없어도_linkChoice를_null로_부르고_세션은_예전과_같다() // 늘 부른다 — null 처리는 linkChoice 안에서
```

- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.** `QuestionService.create(req, requesterId)`에서 `sessionRepo.save(s)` 뒤에 한 줄. 검증·순서·다른 동작은 바꾸지 않는다. `QuestionService`를 직접 생성하는 다른 테스트도 함께 고친다.
- [ ] **Step 4: 통과 확인** — `./gradlew test --tests '*Question*'`, 이어서 `./gradlew test`(전체, Testcontainers 제외분).
- [ ] **Step 5: Commit.**

---

### Task 7: 프론트 — 호출과 순수 로직

**Files:**
- Create: `frontend/composables/questionRoute.ts`, `frontend/composables/repoBranches.ts`
- Test: `frontend/composables/questionRoute.spec.ts`, `frontend/composables/repoBranches.spec.ts`

**Interfaces:**
- Produces:

```ts
// questionRoute.ts
export type RouteState = 'CONFIDENT' | 'AMBIGUOUS' | 'NO_MATCH' | 'UNAVAILABLE'
export interface QuestionRoute {
  routeId: number | null
  state: RouteState
  recommendedRepoCatalogId: number | null
  candidates: { repoCatalogId: number; probability: number }[]
  recentRepoCatalogIds: number[]
  eligibleRepoCatalogIds: number[] | null   // null = 서버에 못 물어봄(화면 대체값)
  dbNeeded: boolean | null
}
export interface RouteCatalogEntry { id: number; alias: string; description: string | null; host: string; defaultBranch: string | null }
export const SIMILAR_MIN_PROBABILITY = 0    // Task 1 결과로 0 또는 0.25
export const UNAVAILABLE_ROUTE: QuestionRoute
export async function fetchQuestionRoute(question: string): Promise<QuestionRoute>   // 절대 던지지 않는다 — 실패·모양 이상은 UNAVAILABLE_ROUTE
export interface RouteCardModel {
  recommended: RouteCatalogEntry | null     // CONFIDENT만
  others: RouteCatalogEntry[]               // CONFIDENT: 추천 다음 후보 최대 2
  recent: RouteCatalogEntry[]               // AMBIGUOUS·UNAVAILABLE: 최대 3
  similar: RouteCatalogEntry[]              // AMBIGUOUS: 후보 중 recent에 없고 확률 ≥ 하한, 최대 3
  searchable: RouteCatalogEntry[]           // 등록 가능한 전체(별칭 순)
}
export function routeCardModel(route: QuestionRoute, catalog: RouteCatalogEntry[]): RouteCardModel
export function searchRepos(list: RouteCatalogEntry[], text: string): RouteCatalogEntry[]  // 별칭·설명 부분 일치, 대소문자 무시, 빈 문자열이면 전체
export function seedDbSelection(items: DbConnectionView[], dbNeeded: boolean | null): number[]

// repoBranches.ts
export interface RepoBranches { defaultBranch: string | null; branches: { name: string; sha: string }[] }
export async function fetchRepoBranches(catalogId: number): Promise<RepoBranches>   // 실패는 Error(message) — message는 직접 고르기 모드와 같은 문구
export function initialBranch(res: RepoBranches, catalogDefault: string | null): string  // catalogDefault ?? res.defaultBranch ?? 첫 브랜치 ?? ''
```

- [ ] **Step 1: 실패하는 테스트 작성.**

```ts
// questionRoute.spec.ts
it('fetchQuestionRoute는 POST /api/questions/route에 { question }을 보낸다')
it('호출이 던지면 UNAVAILABLE_ROUTE를 돌려준다')
it('응답에 state가 없거나 모르는 값이면 UNAVAILABLE_ROUTE')
it('CONFIDENT: 추천 1 + 다른 후보 2(추천 제외, 확률 순), recent·similar는 비어 있다')
it('AMBIGUOUS: recent 최대 3, similar는 recent와 겹치지 않고 최대 3')
it('AMBIGUOUS: 하한보다 낮은 후보는 similar에서 뺀다')
it('NO_MATCH: 추천·칩 없이 searchable만')
it('UNAVAILABLE: recent만 있고 similar는 없다')
it('eligible에 없는 id는 어느 목록에도 나오지 않는다')
it('eligible이 null이면 host가 other가 아닌 카탈로그 전체가 searchable이다')
it('카탈로그에 없는 id(그새 꺼짐)는 조용히 빠진다')
it('searchRepos는 별칭과 설명에서 대소문자 없이 찾는다')
it('seedDbSelection: true면 공용 먼저 최대 3개(꺼진 접속 제외)')   // REPO 2 + USER 2 → [repo1, repo2, user1]
it('seedDbSelection: false면 빈 배열')
it('seedDbSelection: null이면 켜진 공용만 최대 3개')               // 지금의 seedDefaults와 같다
// repoBranches.spec.ts
it('initialBranch는 카탈로그 기본 → 조회 기본 → 첫 브랜치 → 빈 문자열 순')
it('fetchRepoBranches는 404면 "레포를 찾을 수 없거나 접근 권한이 없습니다"로 던진다')
it('fetchRepoBranches는 서버 message가 있으면 그 문구로 던진다')
```

- [ ] **Step 2: 실패 확인** — `npx vitest run composables/questionRoute.spec.ts composables/repoBranches.spec.ts`.
- [ ] **Step 3: 구현.** `useApi` 목은 `test/mocks/nuxt`의 `useApiMock` 방식을 따른다. 그 밖의 오류 문구는 `failMsg('브랜치 동기화')`.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: Commit.**

---

### Task 8: 추천 카드 `RepoRouteCard.vue`

**Files:**
- Create: `frontend/components/chat/RepoRouteCard.vue`
- Test: `frontend/components/chat/RepoRouteCard.spec.ts`

**Interfaces:**
- Consumes: Task 7 전부, `fetchDbConnections`·`MAX_DB_SELECTION`·`dbTypeLabel`(`composables/dbConnections`), `DbConnectionDialog`(props `modelValue`, `scope: 'USER'`, `repoCatalogId`, `editing: null`; emit `saved(view)`).
- Produces:

```ts
defineProps<{ route: QuestionRoute; catalog: RouteCatalogEntry[]; starting: boolean }>()
defineEmits<{ start: [payload: { repoCatalogId: number; githubBranch: string; dbConnectionIds: number[] | null }] }>()
// dbConnectionIds === null : DB 기능 꺼짐(등록 바디에 싣지 않는다)
defineExpose({ pick, selectedId, branch, dbIds })   // 테스트용 — pick(id: number)
```

고정 문구(spec §5.3·§5.4):
- 제목: `이 레포에 대한 질문이 맞나요?` / `어느 버전·사이트에 대한 질문인가요?` / `등록된 레포 중에 맞는 곳을 찾지 못했어요`
- 표시·줄 이름: `추천`, `다른 후보`, `최근 사용`, `비슷한 후보`, `🔍 다른 레포`, `🔍 전체 N개에서 찾기`, 검색 자리 `레포 이름·설명으로 찾기`
- 버튼: `이 레포로 질문 시작` / 비활성 `레포를 골라 주세요`
- 없음 안내: `찾는 레포가 없다면 사이트 추가 요청 →`(`/requests`, 새 탭)
- DB: `DB 조회가 필요한 질문으로 보여 미리 골랐어요` / `이 레포에 연결할 DB 접속이 없습니다` + `내 DB 접속 추가`
- 등록 가능한 레포가 0개: `등록된 레포가 없습니다 — 관리자에게 문의`

`data-test`: `route-card`, `route-title`, `route-hero`, `route-chip-{id}`, `route-search`, `route-branch`, `route-branch-error`, `route-db-row`, `route-db-{id}`, `route-db-add`, `route-start`.

- [ ] **Step 1: 실패하는 테스트 작성.**

```ts
it('CONFIDENT: 추천 레포가 강조되고 추천 표시·설명이 보이며 브랜치를 받은 뒤 시작 버튼이 켜진다')
it('CONFIDENT: 다른 후보 칩을 누르면 그 레포가 강조되고 추천 표시는 사라진다')
it('AMBIGUOUS: 고르기 전에는 버튼이 "레포를 골라 주세요"로 꺼져 있다')
it('AMBIGUOUS: 최근 사용·비슷한 후보 칩과 "전체 N개에서 찾기"가 보인다')
it('NO_MATCH: 검색과 사이트 추가 요청 링크(/requests, target=_blank)가 보인다')
it('UNAVAILABLE: 비슷한 후보 줄이 없고 실패 안내 문구도 없다')
it('검색해서 고른 레포로 시작할 수 있다')
it('시작을 누르면 { repoCatalogId, githubBranch, dbConnectionIds }를 낸다')
it('브랜치 조회 중에는 시작 버튼이 꺼져 있다')
it('브랜치 조회가 실패하면 오류 문구를 보여 주고 시작을 막되 다른 레포는 고를 수 있다')
it('레포를 연달아 고르면 늦게 온 이전 레포의 브랜치·DB 응답이 지금 선택을 덮지 않는다')   // Review Focus 4
it('DB: 접속 있음 + dbNeeded=true → 미리 체크되고 안내 문구가 보인다')
it('DB: 접속 있음 + dbNeeded=false → 체크 없이 보인다')
it('DB: 접속 있음 + dbNeeded=null → 공용만 체크된다')
it('DB: 접속 없음 + dbNeeded=true → 등록 안내와 "내 DB 접속 추가"가 보인다')
it('DB: 접속 없음 + dbNeeded=false → DB 줄이 없다')
it('DB: 기능이 꺼져 있으면 줄이 없고 dbConnectionIds는 null로 낸다')
it('DB: 4번째 접속은 체크할 수 없다(상한 3)')
it('DB: 접속을 새로 등록하면 목록을 다시 받고 그 접속이 체크된다')
it('starting이면 시작 버튼이 로딩이고 칩·검색이 잠긴다')
it('등록 가능한 레포가 없으면 안내 문구만 보인다')
it('폰 폭(390px)에서도 강조 영역·칩·버튼이 모두 그려진다')        // setViewportWidth
```

- [ ] **Step 2: 실패 확인** — `npx vitest run components/chat/RepoRouteCard.spec.ts`.
- [ ] **Step 3: 구현.** 모양은 목업 A안을 따른다(카드 = AI 아바타 + 테두리 카드, 말풍선과 같은 폭 규칙). 레포가 정해질 때마다 `fetchRepoBranches`와 `fetchDbConnections`를 함께 부르고, 응답이 올 때 지금 선택과 같은 레포인지 확인한다. 브랜치는 `q-select`(`use-input`, 받은 브랜치 목록에서 걸러 고름)이고 기본값은 `initialBranch`. 루트 요소와 클래스 이름은 Global Constraints를 지킨다.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: Commit.**

---

### Task 9: 새 질문 화면 — 자동/직접 모드와 카드 흐름

**Files:**
- Modify: `frontend/pages/questions/index.vue`, `frontend/scripts/hydration-smoke.mjs`
- Test: `frontend/test/questions-index.spec.ts`(수정 + 추가)

**Interfaces:**
- Consumes: `fetchQuestionRoute`, `RepoRouteCard`(`@start`), `ChatBubble`(`role="user"`).
- Produces: `defineExpose`에 `mode`, `phase`, `route`, `startFromCard` 추가.

정하는 것:
- 이 파일 안의 `RepoCatalogEntry` 타입에 `description: string | null`을 더한다(서버는 이미 내려 준다) — 카드에 `catalog`로 그대로 넘긴다.
- `mode: 'auto' | 'manual'`, 기본 `'auto'`. `localStorage` 키 `netis-maker-question-repo-mode`에 기억하고 `onMounted`에서 읽는다(SSR·하이드레이션은 `auto`로 그린다). 읽기·쓰기는 `try/catch`.
- `phase: 'compose' | 'routing' | 'card'`.
- 자동 모드의 선택 줄(`#top` 자리): `레포: 질문을 보면 찾아 드려요` + 글자 버튼 `직접 고르기`(`data-test="repo-mode-manual"`). 직접 모드에는 선택 상자 위에 `자동으로 찾기`(`data-test="repo-mode-auto"`). 자동으로 돌아가면 `onRepoSelected(null)`로 레포·브랜치·DB 선택을 비우고 참조 선택도 비운다.
- 자동 모드의 안내문: `질문을 보내면 맞는 레포를 찾아 확인받은 뒤 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.` 직접 모드는 지금 문구.
- 자동 모드 `canSubmit` = 본문이 비어 있지 않고 `submitting`이 아님. `submit()`은 자동이면 `phase='routing'` → `fetchQuestionRoute(draft.question)` → `phase='card'`. 직접 모드의 `submit()`은 한 글자도 바꾸지 않는다.
- `phase !== 'compose'`이면 화면을 대화 모양으로: 내 말풍선(`ChatBubble role="user"`, 첨부는 이름·크기 칩) → `✎ 질문 고치기`(`data-test="route-edit"`) → `routing`이면 점 세 개 자리, `card`면 `RepoRouteCard`. 입력창은 `disabled`이고 자리 문구는 `레포를 확정하면 답변이 시작됩니다`.
- `startFromCard(payload)`: 바디 = `{ repoCatalogId, githubBranch, question, model, effort, mcpCatalogIds }` + `dbConnectionIds`(payload가 null이 아니면) + `routeId`(null이 아니면). `refRepos`는 싣지 않는다. 첨부가 있으면 지금과 같은 multipart. 성공하면 지금과 같은 알림·목록 갱신·이동. 실패하면 **카드에 남아** 지금과 같은 알림(429는 경고 톤).
- `질문 고치기`: `phase='compose'`, 본문·첨부·모델·MCP 선택 유지, `route`는 버린다.
- `hydration-smoke.mjs`: `if (path === '/api/questions/route') return { routeId: 1, state: 'UNAVAILABLE', recommendedRepoCatalogId: null, candidates: [], recentRepoCatalogIds: [], eligibleRepoCatalogIds: [], dbNeeded: null }`.

- [ ] **Step 1: 기존 테스트 고정.** 기존 `describe`의 `beforeEach`에 `localStorage.setItem('netis-maker-question-repo-mode', 'manual')`을 넣어 지금 테스트가 직접 모드로 그대로 통과하게 한다. 다른 단언은 고치지 않는다.
- [ ] **Step 2: 실패하는 테스트 추가**(새 `describe('자동 모드 — 레포 추천 카드')`, `beforeEach`에서 그 키를 지운다).

```ts
it('기본은 자동 모드다 — 레포·브랜치 선택 상자와 참조 칩 줄이 없고 본문만 있으면 보낼 수 있다')
it('보내면 /api/questions/route를 부르고 내 말풍선과 카드를 그린다 — 이때 /api/questions POST는 없다')
it('route 호출 중에는 입력창이 잠기고 카드 자리에 대기 표시가 있다')
it('카드에서 시작하면 routeId·고른 레포·브랜치·dbConnectionIds로 POST하고 대화로 이동한다')
      // body: { repoCatalogId: 1, githubBranch: 'main', question, model, effort, mcpCatalogIds: [], dbConnectionIds: [7], routeId: 55 }
it('DB 기능이 꺼져 있으면 dbConnectionIds를 싣지 않는다')
it('routeId가 null이면(추천 호출 실패) routeId를 싣지 않는다')
it('자동 모드의 등록 바디에는 refRepos가 없다')
it('첨부가 있으면 multipart(meta + files)로 보낸다')
it('질문 고치기를 누르면 입력창으로 돌아가고 본문·첨부가 남아 있다')
it('등록이 400으로 실패하면 카드에 남아 서버 문구를 알린다')            // Review Focus 3
it('등록이 429면 경고 톤으로 알리고 카드에 남는다')
it('직접 고르기로 바꾸면 지금의 선택 상자가 보이고 선택이 저장된다(localStorage)')
it('직접 모드에서 보내면 route를 부르지 않고 예전과 같은 바디로 POST한다')
it('자동으로 찾기로 돌아가면 고른 레포·브랜치·DB·참조가 비워진다')
it('폰 폭(390px)에서 카드와 잠긴 입력창이 함께 그려진다')
```

- [ ] **Step 3: 실패 확인** — `npx vitest run test/questions-index.spec.ts`.
- [ ] **Step 4: 구현.**
- [ ] **Step 5: 통과 확인** — 위 파일 + `npx vitest run`(프론트 전체) + `npm run lint`(오류 0, 경고가 늘지 않음).
- [ ] **Step 6: Commit.**

---

### Task 10: 관리 화면 추천 키워드 칸

**Files:**
- Modify: `frontend/pages/admin/repo-catalog.vue`
- Test: `frontend/test/repo-catalog-admin.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 2의 `View.routeHint`, `UpsertRequest.routeHint`(null = 유지, 빈 문자열 = 비움).

- [ ] **Step 1: 실패하는 테스트 작성.**

```ts
it('폼에 추천 키워드 칸과 외부 전송 안내 문구가 있다')
it('수정 창을 열면 기존 추천 키워드가 채워진다')
it('저장 바디에 routeHint를 싣는다 — 비우면 빈 문자열')
it('켜기/끄기 토글 바디에는 routeHint가 없다')                      // 서버가 유지한다
it('추천 키워드는 500자를 넘길 수 없다')
```

- [ ] **Step 2: 실패 확인** — `npx vitest run test/repo-catalog-admin.spec.ts`.
- [ ] **Step 3: 구현.** 설명 칸 아래에 `q-input`(textarea, `label="추천 키워드 (선택)"`, `maxlength="500"`, `counter`). 안내 문구: `질문에서 이 레포를 찾을 때 쓰는 말(고객사·사이트 별칭, 버전, 주요 화면). 외부 추천 서비스로 전송되니 사람 이름·접속정보는 적지 마세요.` 목록·카드에는 보여 주지 않는다. 검색 대상에는 넣는다.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: Commit.**

---

### Task 11: 문서 · 전체 검증

**Files:**
- Modify: `CLAUDE.md`("질문 세션(Q&A)" 항목에 한 단락)

- [ ] **Step 1: CLAUDE.md에 추가.** 담을 것: 자동/직접 모드, `POST /api/questions/route`(실패하지 않음·`UNAVAILABLE`), 밖으로 나가는 것(질문 앞 500자·별칭·추천 키워드뿐), 판정 임계값과 설정 이름, `question_route_log`와 `routeId`, `TYPESAFE_API_KEY`가 두 비밀 제거 목록에 있다는 것, 기동 로그 문구, 배포 순서(spec §9), 스펙·계획 경로.
- [ ] **Step 2: 전체 실행.** `./gradlew test`, 프론트 `npx vitest run`, `npm run lint`, 인터뷰 서비스 테스트. 하이드레이션 스모크와 Testcontainers는 CI에 맡긴다고 결과에 적는다.
- [ ] **Step 3: 손으로 확인(worktree에서 프론트 dev 서버를 띄울 수 있을 때).** 키 없이: 질문 전송 → `UNAVAILABLE` 카드 → 검색으로 레포 선택 → 시작. 폰 폭 390px에서 같은 흐름. 직접 고르기 모드가 예전과 같은지. 못 띄우면 하지 못했다고 보고한다.
- [ ] **Step 4: Commit.**
- [ ] **Step 5: 사용자에게 보고.** 통과·건너뛴 검증, 사용자 몫(`public.env`에 키, 추천 키워드 채우기 — Task 1의 초안 전달), 배포 순서, 남은 것(2단계).
