# Claude 사용량·API 환산 비용 보고 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude를 돌리는 PC 세 대의 워커가 Claude Code 기록 파일(jsonl)에서 응답별 토큰을 모아 API에 보낸다. API는 이를 모델별 API 가격으로 환산하고, 일·주·월 단위로 구독료 대비 배수를 낸다. 관리자는 작업 목록 오른쪽 위 칩과 상세 다이얼로그에서 이 값을 본다.

**Architecture:** 세 Part로 나눈다. 배포 순서는 API → 워커 → 프론트다.
- **A (API):** `com.claude_usage_event`(응답 키 UNIQUE, 토큰 칸별 `GREATEST` upsert)·`claude_usage_collector`·`usage_subscription_fee`를 둔다. 워커 수신, 가격표, 서울 시각 기준 기간·구독료 몫 계산, 관리자 집계·CSV·구독료 API를 만든다.
- **B (워커):** `ClaudeCodeUsageCollector`가 5분마다 `projects/**/*.jsonl`을 바이트 위치로 이어 읽는다. 키마다 칸별 최댓값으로 합치고, 폴더로 SYMPHONY/DIRECT를 가른 뒤 1000건씩 보낸다. 같은 PC의 워커 여러 개 중 하나만 수집하도록 파일 락을 쓴다.
- **C (프론트):** 관리자에게만 칩과 상세 다이얼로그를 보여 준다. 차트는 CSS 막대로 그린다.

**Tech Stack:** Java 21 · Spring Boot 3 · JdbcTemplate/JPA/Flyway(PostgreSQL `com`) · Jackson · Mockito/JUnit 5 · Testcontainers · Nuxt 3 + Quasar(vitest, hydration smoke)

**Spec:** `docs/superpowers/specs/2026-11-03-claude-usage-cost-report-design.md` 전부를 구현한다.
- 줄 번호는 이 worktree(#122 head 위 `5c62865`) 기준이다. #122 위에는 문서 커밋만 있으므로 #122 병합 뒤 main과 같다.

경로 줄임: `M/`=`src/main/java/com/hamonsoft/netismaker/`, `T/`=`src/test/java/com/hamonsoft/netismaker/`, `R/`=`src/main/resources/`, `F/`=`frontend/`.

## Global Constraints

- **브랜치:** #122(V38)가 main에 병합된 뒤 `origin/main`에서 `feat/claude-usage-cost-report`를 새로 만들고 PR을 따로 낸다. #122 병합 전에는 시작하지 않는다.
- **다른 계획과의 충돌:** 둘 중 늦게 병합되는 쪽이 `origin/main`으로 rebase하고 충돌을 푼다. Task 10은 시작할 때 `git fetch; git rebase origin/main`을 한다.
  - 작업 목록 머리줄 `F/pages/tasks/index.vue:566-603` — 작업 타입 계획(`docs/superpowers/plans/2026-11-03-task-type-db-write.md` Task 6)도 고친다. 합친 순서: 제목 → `q-space` → `UsageCostChip` → 내 작업만 → 상태 select → 타입 select → 작업 등록.
  - `F/scripts/hydration-smoke.mjs` `apiResponse` 목 구역(`:125-151`) — 작업 타입 계획 Task 17(`:1151`)·인터뷰 목업 계획도 고친다.
  - 공유 줄은 피한다: 설정 등록은 `NetisMakerApplication.java:15-16`이 아니라 각자의 `@Configuration`(Task 2·6)에서 하고, `app.usage`는 `application.yml`의 `app.request-board` 뒤에 둔다(작업 타입 `:163` 근처·배포 로그 `:136-138`과 떨어져 있다).
- **마이그레이션:** Task 1이 `R/db/migration/V{n}__claude_usage.sql`을 만든다.
  - n은 병합 직전 `origin/main`의 가장 큰 번호 + 1과 **정확히 같아야** 한다. 겹쳐도 안 되고 빈 번호를 남겨도 안 된다 — Flyway `outOfOrder`가 없어(`R/application.yml:41-47`) 낮은 미적용 번호가 있으면 API 기동이 검증 오류로 멈춘다. 다른 계획이 예약한 번호(V39~41)를 미리 쓰지 않는다.
  - 병합 직전(Task 11 Step 8)에 다시 맞추고 파일 이름만 바꾼다. 이 PR은 병합 전이므로 체크섬이 아직 굳지 않았다.
  - 표기는 V38 관례를 따른다: `BIGSERIAL PRIMARY KEY`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, 토큰 `BIGINT NOT NULL DEFAULT 0`, `ck_<표>_<칸>`, `uq_<표>_<칸>`, `idx_<표>_<칸>`, `IF NOT EXISTS` 없음.
- **시간대:** 기간 경계, "오늘", 시간·일 버킷, 세는 시작일 자정은 모두 `ZoneId.of("Asia/Seoul")`을 명시한다. JVM 기본 시간대에 기대지 않는다(CI JVM은 UTC, `.github/workflows/ci.yml:18`).
  - API 응답의 시각은 모두 `+09:00`으로 바꿔 내보낸다. 프론트는 앞 10자를 서울 날짜로 쓴다.
- **세는 시작일:** 워커 `USAGE_COLLECT_COUNT_FROM`, API `app.usage.count-from`(env `USAGE_COUNT_FROM`). 둘 다 기본 `2026-10-01`이고 서울 자정부터 센다.
- **구분 값:** `SYMPHONY` · `DIRECT`.
- **보내지 않는 것:** `message.content`, 도구 입력, `cwd`, 파일 경로. DTO(`ClaudeCodeUsageEvent`)에 그런 칸 자체가 없다.
- **권한:** `/api/usage/summary`, `/api/usage/summary.csv`, `/api/usage/subscription-fees`는 관리자 전용이다. 기존 `GET /api/usage/claude`는 지금처럼 인증 사용자 전원에게 열려 있다(`M/controller/UsageController.java:10-14`).
- **가격표:** `app.usage.prices.<모델 id>.{input,output,cache-write-5m,cache-write-1h,cache-read}`(USD / 100만 토큰). 선택 칸은 `fast-multiplier`, `long-context.{threshold,input,output,cache-write-5m,cache-write-1h,cache-read}`다.
  - 값은 Task 2 Step 3의 공식 가격 확인으로만 채운다. 이 계획에는 가격 숫자를 적지 않는다. 시험은 가짜 단가(`claude-test-*` 모델)를 쓴다.
- **워커 설정**(`netis-maker.worker.usage-collect.*` = env):
  - `enabled: ${USAGE_COLLECT_ENABLED:true}`
  - `interval-seconds: ${USAGE_COLLECT_INTERVAL_SECONDS:300}`(30 이상)
  - `include-direct: ${USAGE_COLLECT_INCLUDE_DIRECT:false}`
  - `symphony-roots: ${USAGE_COLLECT_SYMPHONY_ROOTS:}`(쉼표 구분, `~` 펼침)
  - `count-from: ${USAGE_COLLECT_COUNT_FROM:2026-10-01}`
  - `machine: ${USAGE_COLLECT_MACHINE:}`(비면 호스트 이름)
  - `max-mb-per-run: ${USAGE_COLLECT_MAX_MB_PER_RUN:256}`(1 이상)
- **수집기 파일:**
  - 락은 `{user.home}/netis-maker/claude-usage-collector.lock`, 상태는 `{user.home}/netis-maker/claude-usage-collector.state.json`이다.
  - 기록 파일은 NIO(`FileChannel.open`)로만 연다. `FileInputStream`·`RandomAccessFile`은 쓰지 않는다. Windows에서 삭제 공유가 없어 Claude Code의 30일 정리를 막을 수 있기 때문이다.
- **화면 문구:**
  - 칩 `이번 달(지금까지) $1,234 · 구독료의 6.2배`
  - 안내 `claude.ai 채팅 제외 · 기록은 Claude Code가 약 30일 보관한 것부터`
  - 그 밖에 `구독료를 입력하세요`, `구독료 적용 전 기간 포함`, `CSV 내려받기`, `구독료 설정`
  - 새 의존성은 들이지 않는다. 차트는 CSS 막대로 그린다.
- **프론트 규칙:** q-dialog 콘텐츠의 루트는 `<div>`(q-card), `.dialog-body`는 q-card의 직계 자식(`F/assets/css/main.css:74-87`). 클래스에 Quasar 반응형 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`)을 쓰지 않는다. `defineProps<T>()`의 T(`UsagePeriodValue` 등)를 `~/`에서 가져오지 않는다. 새 컴포넌트는 쓰는 곳에서 명시적으로 import한다.
- **시험:**
  - 이 worktree에서만 돌리고 `gradlew --stop`은 금지다.
  - 단위 시험은 `.\gradlew.bat test --tests <FQCN>`으로 돌린다.
  - **(CI) 표시 시험**에는 `@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true") @SpringBootTest @AutoConfigureMockMvc @ContextConfiguration(initializers = TestcontainersConfig.class)`를 단다(`T/controller/UsageApiIntegrationTest.java:29-33`). 새 initializer는 만들지 않는다.
    - 컨테이너 하나를 모든 클래스가 같이 쓴다(`T/TestcontainersConfig.java:18-27`). 그래서 세 (CI) 클래스 모두 `@BeforeEach`에서 `DELETE FROM com.claude_usage_event`·`com.claude_usage_collector`·`com.usage_subscription_fee`를 한다(`UsageApiIntegrationTest.java:39-41` 관례).
    - 그 Task의 Step 4는 (CI) 클래스를 `--tests`에 넣어 `compileTestJava` 성공과 skipped(`build.gradle:119-120` testLogging)만 본다. 실제 실행은 Task 5 Step 6(선택) 또는 Task 11 Step 7(CI)이다.
  - **수집기 시험**은 `@TempDir` 가짜 홈의 `.claude/projects/…`에 손으로 쓴 jsonl만 쓴다. 실제 `~/.claude`는 Task 11 Step 9(라이브 확인)에서만 읽는다.
  - **frontend** 준비는 Task 9 Step 0(`npm ci`·lint 기준선)이다. 라이브 `node_modules` junction은 쓰지 않는다(`playwright-core`·`@nuxt/eslint-config`가 없다).
- **커밋:** 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. 서울 자정 경계를 UTC JVM(CI)에서 보는 경우. `2026-10-31T15:30Z`(KST 11-01 00:30) 이벤트는 11월과 11-01의 0시 칸에 들어가야 한다. 같은 시각에 `date` 없이 요청하면 오늘은 11-01이어야 한다(Task 3·5).
2. `cwd`가 Git Bash형 `/c/Users/mic/netis-maker/…`이거나 대소문자·`\`가 설정과 다른 경우. Symphony로 잡혀야 한다. 반대로 `…/netis-maker-old/…`처럼 이름 앞만 같은 폴더는 직접 사용이어야 한다(Task 6).
3. jsonl 한 줄이 회차 읽기 상한보다 큰 경우(이 PC에 1MB 넘는 줄, 21MB 파일이 있다). 회차는 그 줄을 읽고 위치를 옮겨야 하며, 같은 자리에서 영원히 멈추면 안 된다(Task 7).
4. 걷는 도중 Claude Code의 30일 정리가 파일이나 폴더를 지운 경우. 그 파일만 빼고 회차가 끝까지 돌아야 하고, 상태에서도 그 파일이 빠져야 한다(Task 8).
5. 1000건 묶음 안의 이벤트 하나가 이상한 경우(키 길이 초과, 모르는 구분, 시각 없음). 나머지는 저장되고 그 하나만 `skipped`로 세야 한다. 묶음 전체가 400이 되면 워커가 같은 묶음을 영원히 다시 보낸다(Task 1).

---

# Part A — API (설계 §5·§6)

### Task 1: 마이그레이션·수신 엔드포인트·upsert

**Files:**
- Create: `R/db/migration/V{n}__claude_usage.sql`(Global Constraints "마이그레이션")
- Create: `M/dto/ClaudeCodeUsageEvent.java`, `ClaudeCodeUsageBatchRequest.java`, `ClaudeCodeUsageBatchResponse.java`
- Create: `M/usage/ClaudeCodeUsageSanitizer.java`(순수), `ClaudeCodeUsageEventStore.java`(JdbcTemplate), `ClaudeCodeUsageIngestService.java`
- Modify: `M/controller/UsageWorkerController.java:16-33`(생성자 인자와 메서드 추가)
- Test: `T/usage/ClaudeCodeUsageSanitizerTest.java`, `T/controller/UsageWorkerControllerClaudeCodeTest.java`(WebMvc 슬라이스, `T/controller/MeControllerTest.java:23-26` 하네스), `T/usage/ClaudeCodeUsageIngestIntegrationTest.java`(CI)

**Interfaces:**
- Produces:
  - V{n}
    - `com.claude_usage_event`
      - `id`, `event_key VARCHAR(200) NOT NULL`(`uq_claude_usage_event_event_key`), `occurred_at TIMESTAMPTZ NOT NULL`, `machine VARCHAR(100) NOT NULL`
      - `category VARCHAR(20) NOT NULL`(`ck_claude_usage_event_category` IN SYMPHONY·DIRECT)
      - `model VARCHAR(100) NOT NULL`, `speed VARCHAR(20)`, `service_tier VARCHAR(30)`, `entrypoint VARCHAR(30)`, `session_id VARCHAR(100)`, `is_sidechain BOOLEAN NOT NULL DEFAULT false`
      - 토큰 `input_tokens`·`output_tokens`·`cache_write_5m_tokens`·`cache_write_1h_tokens`·`cache_read_tokens`
      - `created_at`, 인덱스 `idx_claude_usage_event_occurred_at`
    - `com.claude_usage_collector`: `machine VARCHAR(100) PRIMARY KEY`, `last_reported_at TIMESTAMPTZ NOT NULL`, `last_event_at TIMESTAMPTZ`, `unreadable_lines INT NOT NULL DEFAULT 0`, `updated_at`
    - `com.usage_subscription_fee`
      - `id`, `effective_from DATE NOT NULL`(`uq_usage_subscription_fee_effective_from`)
      - `monthly_fee NUMERIC(12,2) NOT NULL`(`ck_usage_subscription_fee_monthly_fee` > 0)
      - `currency VARCHAR(3) NOT NULL DEFAULT 'USD'`(`ck_usage_subscription_fee_currency` IN 'USD')
      - `created_by VARCHAR(100) NOT NULL`(FK 없음, V38 관례), `created_at`
  - DTO (워커·API 공유)
    - `record ClaudeCodeUsageEvent(String eventKey, OffsetDateTime occurredAt, String category, String model, String speed, String serviceTier, String entrypoint, String sessionId, Boolean sidechain, long inputTokens, long outputTokens, long cacheWrite5mTokens, long cacheWrite1hTokens, long cacheReadTokens)`
    - `record ClaudeCodeUsageBatchRequest(@NotBlank @Size(max=100) String machine, @PositiveOrZero Integer unreadableLines, @Size(max=1000) List<ClaudeCodeUsageEvent> events)` — `events`에 `@NotEmpty`·`@Valid`를 달지 않는다.
    - `record ClaudeCodeUsageBatchResponse(int received, int inserted, int updated, int skipped)`
  - `ClaudeCodeUsageSanitizer`
    - `static Sanitized sanitize(List<ClaudeCodeUsageEvent> raw)` — null이면 빈 목록이다.
    - `record Sanitized(List<ClaudeCodeUsageEvent> events, int skipped, OffsetDateTime lastEventAt)` — `events`는 키마다 칸별 최댓값으로 합치고 키 순으로 정렬한다. 다른 칸은 먼저 온 값을 쓴다.
  - `ClaudeCodeUsageEventStore`(`@Repository @Profile("api")`)
    - `UpsertCounts upsert(String machine, List<ClaudeCodeUsageEvent> events)`, `record UpsertCounts(int inserted, int updated)`
    - `void touchCollector(String machine, OffsetDateTime lastEventAt, int unreadableLines)`
  - `ClaudeCodeUsageIngestService`(`@Service @Profile("api")`): `@Transactional ClaudeCodeUsageBatchResponse ingest(ClaudeCodeUsageBatchRequest req)`
  - `POST /worker/usage/claude-code-events` → `ClaudeCodeUsageBatchResponse`(200)

- [ ] **Step 1: 실패하는 시험 작성**

```java
// ClaudeCodeUsageSanitizerTest
@Test void 같은_키는_칸별_최댓값_한_건_키_순서()  // [k2(out 10, model "a"), k1, k2(out 250, model "b")] → 키 [k1,k2], k2.outputTokens()==250, k2.model()=="a"
@Test void 이상한_이벤트만_건너뛴다()            // (Review Focus 5) 키 빈 값·키 201자·occurredAt null·category "OTHER" + 정상 1 → skipped()==4, events 1
@Test void 음수는_0_긴_문자열은_칸_길이로()      // input -5 → 0; model 150자 → 100자; model null·"  " → "unknown"; sessionId 120자 → 100자; sidechain null → false
@Test void lastEventAt은_남은_이벤트의_최대_시각() // 이벤트 없음 → null
// UsageWorkerControllerClaudeCodeTest
@Test void 키가_맞으면_집계_수()                 // 200, jsonPath $.received·$.inserted·$.updated·$.skipped
@Test void 빈_묶음도_200()                       // {"machine":"pc","unreadableLines":2,"events":[]} → ingest 호출
@Test void 키가_없으면_거절()                    // 4xx, verify(ingest, never())
@Test void 천한_건이나_machine_없으면_400()
// (CI) ClaudeCodeUsageIngestIntegrationTest
@Test void 처음은_inserted_같은_묶음은_변화없음() // 3건 → (3,3,0,0); 다시 → (3,0,0,0); 행 3
@Test void 큰_값만_칸별로_올린다()               // out 10→250: updated 1; 다시 100: updated 0, out 250, model·occurred_at은 첫 값
@Test void 수집기_표()                           // 빈 묶음 unreadable 2 → 응답 (0,0,0,0)·last_event_at NULL·unreadable_lines 2; 이벤트 → 최대 시각; 더 이른 이벤트 → 그대로
@Test void 제약_이름()                           // category 'X' 직접 INSERT → "ck_claude_usage_event_category"; effective_from 중복 → "uq_usage_subscription_fee_effective_from"
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.ClaudeCodeUsageSanitizerTest --tests com.hamonsoft.netismaker.controller.UsageWorkerControllerClaudeCodeTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - 건너뛰는 조건: 키가 비었거나 200자 초과, `occurredAt` null, 구분이 두 값이 아님.
  - 문자열은 칸 길이(model 100, speed 20, service_tier 30, entrypoint 30, session_id 100)로 자른다. model이 null·공백이면 `"unknown"`이다(NOT NULL 위반으로 묶음 전체가 500이 되지 않게).
  - `upsert`는 events가 비면 SQL 없이 `(0,0)`이다(`VALUES` 없는 문장을 만들지 않는다).
  - upsert는 묶음을 한 문장으로 넣는다. 정렬하고 합친 뒤라 같은 키가 두 번 들지 않는다("cannot affect row a second time"을 피한다). 파라미터는 14×1000개로 PG 상한 안이다.
    ```sql
    INSERT INTO com.claude_usage_event AS e (event_key, occurred_at, machine, category, model, speed, service_tier, entrypoint, session_id, is_sidechain, input_tokens, output_tokens, cache_write_5m_tokens, cache_write_1h_tokens, cache_read_tokens)
    VALUES (…), (…) …
    ON CONFLICT (event_key) DO UPDATE SET input_tokens = GREATEST(e.input_tokens, EXCLUDED.input_tokens), …  -- 토큰 5칸만
    WHERE EXCLUDED.input_tokens > e.input_tokens OR …                                                       -- 5칸 중 하나라도 커질 때만
    RETURNING (xmax = 0) AS inserted
    ```
    true 행 수가 inserted, false 행 수가 updated다. 돌아오지 않은 행은 바뀐 것이 없다.
  - 수집기 표: `ON CONFLICT (machine) DO UPDATE SET last_reported_at = now(), last_event_at = GREATEST(c.last_event_at, EXCLUDED.last_event_at), unreadable_lines = EXCLUDED.unreadable_lines, updated_at = now()`. 빈 묶음에도 부른다.
  - 네이티브 SQL은 `com.`을 붙인다(`M/repository/TaskStageUsageRepository.java:17-22`). JdbcTemplate을 받는 빈에는 `@Profile("api")`를 단다. 워커 프로필에는 DataSource가 없다(`R/application-worker.yml:7-10`).
  - `received`는 원래 이벤트 수(건너뛴 것 포함)다.
  - **설계와 다름 ①:** 응답에 `skipped`를 더한다 — 이상한 이벤트 하나로 묶음 전체를 실패시키면 워커가 같은 묶음을 영원히 다시 보낸다(설계 §4).
  - **설계와 다름 ②:** `usage_subscription_fee.effective_from`을 UNIQUE로 두고 같은 적용 시작일은 덮어쓴다(Task 4, 설계에 없음).
- [ ] **Step 4: 통과 확인** — Step 2 명령에 `--tests com.hamonsoft.netismaker.usage.ClaudeCodeUsageIngestIntegrationTest`를 더해 PASS, 그 CI 클래스는 skipped
- [ ] **Step 5: 커밋** — `feat(usage): 사용량 이벤트 표와 워커 수신 upsert`

---

### Task 2: 가격표·비용 계산

**Files:**
- Create: `M/usage/UsageProperties.java`, `M/usage/UsagePriceBook.java`, `M/usage/UsageCostCalculator.java`, `M/dto/UsageTokens.java`
- Create: `M/usage/UsageConfig.java` — `@Configuration @EnableConfigurationProperties(UsageProperties.class)`, 프로필 없음(`NetisMakerApplication.java:15-16`은 고치지 않는다)
- Modify: `R/application.yml:90` 다음(`app.request-board` 블록 뒤, `cors:` `:91` 앞에 `app.usage` 블록)
- Test: `T/usage/UsagePricingTest.java`(`T/workerdaemon/WorkerPropertiesYamlBindingTest.java:24-33`의 `YamlPropertySourceLoader` + `Binder` 방식)

**Interfaces:**
- Produces:
  - `@ConfigurationProperties(prefix="app.usage") record UsageProperties(String countFrom, Map<String, ModelPrice> prices)`
    - null이면 `"2026-10-01"`·`Map.of()`로 채운다.
    - `LocalDate countFromDate()`
    - 형식 오류는 `IllegalArgumentException("app.usage.count-from 형식이 틀렸습니다(YYYY-MM-DD): {값}")`
    - 다섯 단가 중 하나라도 null이면 `IllegalArgumentException("app.usage.prices.{모델}: 다섯 단가가 모두 있어야 합니다")`
  - `record ModelPrice(BigDecimal input, BigDecimal output, BigDecimal cacheWrite5m, BigDecimal cacheWrite1h, BigDecimal cacheRead, BigDecimal fastMultiplier, LongContext longContext)`
  - `record LongContext(Long threshold, BigDecimal input, BigDecimal output, BigDecimal cacheWrite5m, BigDecimal cacheWrite1h, BigDecimal cacheRead)` — 단가가 null인 칸은 기본 단가를 쓴다.
  - `record UsageTokens(long input, long output, long cacheWrite5m, long cacheWrite1h, long cacheRead)`
    - `static final UsageTokens ZERO`
    - `UsageTokens plus(UsageTokens)`
    - `long total()`
    - `long promptTokens()` = input + cacheWrite5m + cacheWrite1h + cacheRead
  - `UsagePriceBook`(`@Component @Profile("api")`, 생성자 `(UsageProperties)`)
    - `Optional<String> resolveKey(String model)` — 정확히 일치하는 키를 먼저 찾고, 없으면 끝의 `-\d{8}`을 떼고 다시 찾는다.
    - `ModelPrice price(String key)`
    - `List<Long> thresholds()` — `long-context.threshold`의 서로 다른 값을 오름차순으로
  - `static BigDecimal UsageCostCalculator.cost(ModelPrice p, UsageTokens t, boolean fast, boolean longContext)`
    - 토큰 × 단가 ÷ 1,000,000을 칸마다 더한다. 반올림하지 않는다.
    - fast면 합에 `fastMultiplier`를 곱한다. fast인데 `fastMultiplier`가 null이면 null(가격 미등록)이다.

- [ ] **Step 1: 실패하는 시험 작성** — 단가는 모두 가짜 값이다.

```java
@Test void 가격표_바인딩()              // yml "app.usage.prices.claude-test-5-5: {input: 3, output: 15, cache-write-5m: 3.75, cache-write-1h: 6, cache-read: 0.3, long-context: {threshold: 200000, input: 6}}"
                                        //   → 키 "claude-test-5-5", cacheWrite1h()==6, longContext().threshold()==200000
@Test void 날짜_접미사를_떼고_찾는다()   // 키 claude-test-4-5 → resolveKey("claude-test-4-5-20251001")=="claude-test-4-5"; 정확한 키가 있으면 그 키; "<synthetic>" → empty
@Test void 다섯_칸을_각각의_단가로()     // tokens(1M,2M,1M,1M,10M), 단가(3,15,3.75,6,0.3) → 3+30+3.75+6+3 = 45.75 (compareTo)
@Test void fast_배수()                   // 배수 6 → ×6; 배수 null → null
@Test void 구간_단가()                   // longContext=true → input은 구간 단가 6, output은 구간 단가가 null이라 기본 15
@Test void 단가가_빠지면_기동_실패()      // output 없음 → 메시지에 "app.usage.prices.claude-test-5-5"
@Test void count_from_형식()             // "2026/10/01" → 메시지에 "app.usage.count-from"; 모두 null → countFromDate()==2026-10-01
@Test void 본_yml_가격표가_바인딩된다()   // R/application.yml을 같은 방식으로 읽어 app.usage 바인딩 → 예외 없음, countFromDate()==2026-10-01 (Step 3 뒤 통과)
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsagePricingTest` → 컴파일 실패
- [ ] **Step 3: 공식 가격 확인 → `R/application.yml`에 초기값**
  - `Skill` 도구로 `claude-api` 스킬을 불러 가격 절을 읽는다. 스킬에 없거나 오래됐으면 Anthropic 공식 문서의 Pricing 페이지를 연다. 기억이나 추측으로 숫자를 채우지 않는다.
  - 대상 모델은 `M/service/ModelEffortPolicy.java:49-54`의 다섯 id다. 종료 노트 `sonnet`(`R/application-worker.yml:73`)처럼 별칭으로 부르는 모델이나 사용자가 직접 쓴 모델은 기록에 실제 id로 남는다. 그런 모델은 라이브 확인의 "가격 미등록" 경고를 보고 같은 방법으로 채운다(Task 11 Step 9).
  - 칸 대응: Base Input → `input`, 5m Cache Writes → `cache-write-5m`, 1h Cache Writes → `cache-write-1h`, Cache Hits & Refreshes → `cache-read`, Output → `output`.
  - 공식 문서에 프롬프트 길이 구간 단가가 있는 모델만 `long-context`를, fast 모드 단가가 있는 모델만 `fast-multiplier`를 넣는다. 공식 문서에 없는 모델은 넣지 않는다.
  - `app.usage` 블록에는 `count-from: ${USAGE_COUNT_FROM:2026-10-01}`과 `prices:`를 둔다. `prices` 위 주석: `# 출처: <URL> (확인 YYYY-MM-DD). 공식 가격이 바뀌면 여기를 PR로 고쳐 병합한 뒤 API를 재기동한다(과거분도 새 가격으로 다시 계산된다).`
  - 모델 id에 `.`이 있으면 키를 `"[…]"`로 쓴다.
- [ ] **Step 4: 구현** — `UsageProperties`는 `UsageConfig`로 api·worker 두 프로필에서 바인딩된다. 생성자는 yml 값만으로 예외 없이 끝나야 한다.
  - **설계와 다름 ①:** `fast-multiplier`를 모델마다 둔다(설계 §5가 모호). 배수가 없는 fast 줄은 추측하지 않고 가격 미등록 `<모델> (fast)`로 센다(Task 5).
- [ ] **Step 5: 통과 확인** — Step 2 명령 PASS(`본_yml_가격표가_바인딩된다` 포함), `.\gradlew.bat compileJava compileTestJava` 성공
- [ ] **Step 6: 커밋** — `feat(usage): 모델별 API 가격표와 비용 계산`

---

### Task 3: 기간·구독료 몫 (순수)

**Files:**
- Create: `M/usage/UsagePeriodUnit.java`, `M/usage/UsagePeriod.java`, `M/usage/UsageWindow.java`, `M/usage/SubscriptionFeeShare.java`
- Test: `T/usage/UsagePeriodTest.java`, `T/usage/SubscriptionFeeShareTest.java`

**Interfaces:**
- Produces:
  - `static final ZoneId UsagePeriod.SEOUL = ZoneId.of("Asia/Seoul")`
  - `enum UsagePeriodUnit { DAY("day","hour"), WEEK("week","day"), MONTH("month","day") }`
    - `String wire()`, `String bucketUnit()`
    - `static UsagePeriodUnit parse(String)` — null이면 MONTH다. 소문자 세 값 밖이면 `TaskException(BAD_REQUEST, "알 수 없는 기간: {값}")`(`M/controller/WorkerController.java:91-100` 방식)
  - `static LocalDate UsagePeriod.parseDate(String raw, Clock clock)` — null·공백이면 서울 오늘이다. 형식이 틀리면 400 `날짜 형식이 틀렸습니다: {값}`
  - `record UsagePeriod(UsagePeriodUnit unit, LocalDate start, LocalDate endExclusive)`
    - `static containing(UsagePeriodUnit, LocalDate)` — 주는 ISO 월요일에 시작한다.
    - `UsagePeriod previous()`
    - `ZonedDateTime startAt()`, `endAt()`(서울)
    - `boolean inProgress(ZonedDateTime now)` = startAt ≤ now < endAt
    - `UsageWindow fullWindow(LocalDate countFrom)` = [max(startAt, countFrom 자정), endAt)
    - `UsageWindow elapsedWindow(LocalDate countFrom, ZonedDateTime now)` = [같은 from, min(endAt, now))
    - `UsageWindow previousComparable(LocalDate countFrom, ZonedDateTime now)`
      - 진행 중이면 [prev.startAt, min(prev.startAt + (now − startAt), prev.endAt))다.
      - 끝났으면 `previous().fullWindow`다.
      - 둘 다 아래쪽 경계를 countFrom으로 자른다.
    - `List<ZonedDateTime> buckets()` — DAY는 24개 시간 칸, 나머지는 날짜 칸
  - `record UsageWindow(ZonedDateTime from, ZonedDateTime to)` — `boolean isEmpty()` = !from.isBefore(to)
  - `SubscriptionFeeShare.FeeEntry(LocalDate effectiveFrom, BigDecimal monthlyFee)`(중첩 record)
  - `SubscriptionFeeShare.FeeShare(BigDecimal share, boolean missingDays, boolean anyFee)`(중첩 record)
  - `static FeeShare SubscriptionFeeShare.between(List<FeeEntry> fees, UsageWindow w)`
    - 창에 걸친 서울 날짜마다 (그날 적용되는 월 구독료 ÷ 그 달 일수) × (그날 24시간 중 창과 겹친 비율)을 더한다.
    - 적용 구독료는 effectiveFrom ≤ 날짜인 가장 늦은 행이다.
    - 겹치는데 구독료가 없는 날이 있으면 `missingDays`다(그날 몫은 0).
    - 나누기는 scale 10, HALF_UP이다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
// UsagePeriodTest
@Test void 주는_월요일_시작() { assertThat(UsagePeriod.containing(WEEK, LocalDate.of(2026,11,5))).isEqualTo(new UsagePeriod(WEEK, LocalDate.of(2026,11,2), LocalDate.of(2026,11,9))); }
@Test void 월과_일()          // MONTH 2026-02-14 → [02-01, 03-01); DAY → [d, d+1); previous(): 2026-03 → 2026-02
@Test void 경계는_서울_자정()  // (Review Focus 1) MONTH 11월 startAt().toInstant() == Instant.parse("2026-10-31T15:00:00Z")
@Test void 파싱()             // parse(null)==MONTH; "year" → "알 수 없는 기간: year"; parseDate("2026-13-01") → "날짜 형식이 틀렸습니다: 2026-13-01"
                              // parseDate(null, Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), UTC)) == 2026-11-01
@Test void 같은_경과_구간()    // 11월, now 11-16 12:00 KST → previousComparable [10-01 00:00, 10-16 12:00)
@Test void 직전_비교는_직전_기간_끝까지() // 3월, now 03-31 12:00 KST → to == 03-01 00:00 KST
@Test void count_from이_아래_경계() // 2026-09-28 주, countFrom 10-01 → fullWindow.from == 10-01 00:00 KST; 9월 → isEmpty()
@Test void 버킷()             // DAY 24개(0시~23시), MONTH 2026-02 → 28개, WEEK 7개
// SubscriptionFeeShareTest
@Test void 날짜별로_그달_일수로_나눈다() // 200 from 10-01, 창 [10-30, 11-02) → 200/31*2 + 200/30
@Test void 달_중간에_바뀐_구독료()     // 100 from 10-01, 200 from 10-16, 10월 전체 → 100*15/31 + 200*16/31
@Test void 구독료_없는_날()            // 200 from 10-16, 10월 → missingDays true; 빈 목록 → anyFee false·share 0
@Test void 오늘은_지난_시간_비율()      // 300 from 11-01, 창 [11-03 00:00, 11-03 06:00) → 300/30*0.25 == 2.5
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsagePeriodTest --tests com.hamonsoft.netismaker.usage.SubscriptionFeeShareTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 날짜 계산은 `LocalDate`·`ZonedDateTime`(SEOUL)만 쓴다.
  - **설계와 다름 ①:** 세는 시작일 하한을 구독료 몫에도 적용한다(2026-09-28 주는 10-01~10-04 몫만). 시작일 이전 구독료가 분모에 들어가 배수가 낮아지지 않게.
  - **설계와 다름 ②:** 직전 기간 비교 구간은 직전 기간 끝을 넘지 않는다(설계에 없음).
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(usage): 서울 시각 기간과 날짜별 구독료 몫`

---

### Task 4: 구독료 이력 API

**Files:**
- Create: `M/entity/UsageSubscriptionFee.java`(`@Table(name="usage_subscription_fee", schema="com")`, `M/entity/ClaudeRateLimit.java:19-24` 모양), `M/repository/UsageSubscriptionFeeRepository.java`
- Create: `M/dto/SubscriptionFeeRequest.java`, `SubscriptionFeeView.java`, `M/usage/UsageSubscriptionFeeService.java`, `M/controller/UsageReportController.java`
- Test: `T/usage/UsageSubscriptionFeeServiceTest.java`, `T/controller/UsageReportSecurityTest.java`(WebMvc 슬라이스), `T/controller/UsageSubscriptionFeeIntegrationTest.java`(CI)

**Interfaces:**
- Consumes: Task 1 V{n}, Task 3 `SubscriptionFeeShare.FeeEntry`
- Produces:
  - `UsageSubscriptionFeeRepository extends JpaRepository<UsageSubscriptionFee, Long>`
    - `List<UsageSubscriptionFee> findAllByOrderByEffectiveFromDesc()`
    - `Optional<UsageSubscriptionFee> findByEffectiveFrom(LocalDate)`
  - `record SubscriptionFeeRequest(@NotNull LocalDate effectiveFrom, @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=10, fraction=2) BigDecimal monthlyFee)`
  - `record SubscriptionFeeView(Long id, LocalDate effectiveFrom, BigDecimal monthlyFee, String currency, String createdBy, OffsetDateTime createdAt)`
  - `UsageSubscriptionFeeService`(`@Service @Profile("api")`)
    - `List<SubscriptionFeeView> list()`
    - `List<FeeEntry> entries()`
    - `@Transactional SubscriptionFeeView save(SubscriptionFeeRequest req, String userId)` — 같은 적용 시작일이 있으면 금액·작성자·시각을 바꾼다.
  - `UsageReportController`
    - `@RestController @RequestMapping("/api/usage") @PreAuthorize("hasAuthority('ROLE_ADMIN')") @Profile("api")`(`M/controller/WorkerHealthController.java:23-27` 방식)
    - `GET /subscription-fees` → `List<SubscriptionFeeView>`
    - `POST /subscription-fees` `@RequestBody @Valid` → `SubscriptionFeeView`. `createdBy`는 `AuthContext.requireUserId(jwt)`다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
// UsageSubscriptionFeeServiceTest
@Test void 새_적용시작일은_추가()            // findByEffectiveFrom empty → save 1번, 저장 엔티티 effectiveFrom 2026-10-01·monthlyFee 200·currency "USD"·createdBy "a"
@Test void 같은_적용시작일은_덮어쓴다()     // 금액 200→250, createdBy "b"; save 1번, 새 행 없음
@Test void 이력은_적용시작일_최신순()        // findAllByOrderByEffectiveFromDesc [11-01 300, 10-01 200] → list() 같은 순서·칸; entries() → FeeEntry 2개(같은 값)
// UsageReportSecurityTest
@Test void 사용자는_구독료_API_403()        // ROLE_USER GET·POST → 403
@Test void 관리자는_저장한다()              // createdBy == JWT username
@Test void 금액이_0이하이거나_소수_3자리면_400() // $.fields.monthlyFee 있음
@Test void 적용시작일_형식이_틀리면_400()     // "2026-1-1" → 400 (깨진 JSON 처리, GlobalExceptionHandler.java:29-35)
@Test void 기존_usage_claude는_사용자도_200()
// (CI) UsageSubscriptionFeeIntegrationTest
@Test void 같은_날짜_두번이면_한_행_목록은_최신순() // 엔티티·V{n} 칸 일치(ddl-auto validate)도 이 컨텍스트가 확인
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsageSubscriptionFeeServiceTest --tests com.hamonsoft.netismaker.controller.UsageReportSecurityTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 관리자 엔드포인트는 클래스 단위 권한이 없는 기존 `UsageController`(`:15-17`)에 넣지 않는다(메서드 하나의 권한 누락 = 반목표).
- [ ] **Step 4: 통과 확인** — Step 2 명령에 `--tests com.hamonsoft.netismaker.controller.UsageSubscriptionFeeIntegrationTest`를 더해 PASS, 그 CI 클래스는 skipped
- [ ] **Step 5: 커밋** — `feat(usage): 구독료 이력 API(관리자)`

---

### Task 5: 집계 `/summary`·CSV

**Files:**
- Create: `M/usage/ClaudeUsageReportRepository.java`(JdbcTemplate), `M/usage/UsageAggRow.java`, `M/usage/UsageSummaryAssembler.java`(순수), `M/usage/UsageSummaryService.java`, `M/dto/UsageSummaryResponse.java`
- Modify: `M/controller/UsageReportController.java`(Task 4)
- Test: `T/usage/UsageSummaryAssemblerTest.java`, `T/usage/UsageSummaryServiceTest.java`(시험마다 `Clock.fixed(Instant, ZoneOffset.UTC)` — UTC zone이라야 서울 변환 누락을 KST JVM에서도 잡는다. `UserProvisionerTest`의 `MutableClock`은 `service` 패키지 package-private이라 못 쓴다), `T/controller/UsageReportSecurityTest.java`(추가), `T/controller/UsageSummaryIntegrationTest.java`(CI)

**Interfaces:**
- Consumes: Task 2 `UsagePriceBook`·`UsageCostCalculator`·`UsageTokens`·`UsageProperties`, Task 3 전부, Task 4 `UsageSubscriptionFeeService.entries()`
- Produces:
  - `record UsageAggRow(LocalDateTime bucket, String category, String model, String speed, List<Boolean> over, UsageTokens tokens)` — `over.get(i)`는 `promptTokens() > thresholds.get(i)`다.
  - `ClaudeUsageReportRepository`(`@Repository @Profile("api")`)
    - `List<UsageAggRow> aggregate(UsageWindow w, String bucketUnit, List<Long> thresholds)` — 창이 비면 쿼리 없이 빈 목록
    - `List<CollectorStatus> collectors()`(machine 순)
    - `Optional<OffsetDateTime> earliestEventAt(OffsetDateTime notBefore)`
  - `UsageSummaryResponse`(필드명은 프론트 `usageReport.ts`와 맞춘다)
    - `String period`, `LocalDate date`, `OffsetDateTime start`, `OffsetDateTime end`, `LocalDate countFrom`, `boolean inProgress`
    - `UsageTokens tokens`, `BigDecimal cost`, `List<CategoryUsage> byCategory`, `List<ModelUsage> byModel`
    - `boolean feeConfigured`, `boolean feeMissing`, `BigDecimal feeShare`, `BigDecimal fullPeriodFeeShare`, `BigDecimal ratio`
    - `PreviousUsage previous`, `List<SeriesPoint> series`, `Coverage coverage`
  - 하위 레코드
    - `CategoryUsage(String category, UsageTokens tokens, BigDecimal cost)` — 늘 SYMPHONY·DIRECT 두 행
    - `ModelUsage(String model, UsageTokens tokens, BigDecimal cost)` — `model`은 fast면 `"{모델} (fast)"`, 가격 미등록이면 `cost`가 null
      - fast = `"fast".equalsIgnoreCase(speed)`. 그 밖의 값(`standard` 등)과 null은 standard다.
    - 전체 `cost`·`byCategory[].cost`·`series`·`previous.cost`는 **가격이 있는 행만** 더한다. 미등록 행의 비용은 0으로 치지 않고 빼며 `coverage.unpricedModels`로만 알린다(토큰 합계에는 들어간다).
    - `PreviousUsage(OffsetDateTime start, OffsetDateTime until, BigDecimal cost, BigDecimal feeShare, BigDecimal ratio)`
    - `SeriesPoint(OffsetDateTime at, BigDecimal cost)`
    - `Coverage(List<CollectorStatus> collectors, OffsetDateTime earliestEventAt, List<UnpricedModel> unpricedModels)`
    - `CollectorStatus(String machine, OffsetDateTime lastReportedAt, OffsetDateTime lastEventAt, int unreadableLines)`
    - `UnpricedModel(String model, UsageTokens tokens)`
  - `UsageSummaryAssembler`
    - `static Assembled assemble(List<UsageAggRow> rows, UsagePriceBook book, List<Long> thresholds)`
    - `record Assembled(UsageTokens tokens, BigDecimal cost, List<CategoryUsage> byCategory, List<ModelUsage> byModel, Map<LocalDateTime, BigDecimal> costByBucket, List<UnpricedModel> unpriced)`
    - `static List<SeriesPoint> series(UsagePeriod p, Map<LocalDateTime, BigDecimal> costByBucket)`
    - `static String csv(List<UsageAggRow> rows, UsagePriceBook book, List<Long> thresholds, UsagePeriodUnit unit)`
  - `UsageSummaryService`(`@Service @Profile("api")`, `@Autowired` 공개 생성자 → package-private `(…, Clock)`, `M/service/UserProvisioner.java:43-51` 방식)
    - `UsageSummaryResponse summary(String period, String date)`
    - `CsvFile csv(String period, String date)`, `record CsvFile(String fileName, byte[] body)`
  - `GET /api/usage/summary?period=&date=` → `UsageSummaryResponse`
  - `GET /api/usage/summary.csv?period=&date=` → `ResponseEntity<byte[]>`, `new MediaType("text","csv",UTF_8)`, `ContentDisposition.attachment().filename("claude-usage-{period}-{start}.csv", UTF_8)`. `produces`는 좁히지 않는다.

- [ ] **Step 1: 실패하는 시험 작성** — 가격은 가짜 값이다.

```java
// UsageSummaryAssemblerTest
@Test void 범주별_모델별_합과_비용()                 // claude-test-5-5(단가 input 3·output 15) SYMPHONY input 1M + DIRECT output 1M, claude-x-1 DIRECT input 1M
                                                    //   → cost 18, byCategory [SYMPHONY 3, DIRECT 15], tokens.input 2M·output 1M, byModel claude-x-1 cost null; speed "standard"·null은 같은 행
@Test void 가격_미등록은_비용을_비우고_경고()        // "claude-x-1" → byModel cost null, unpriced에 있음; "<synthetic>"과 토큰 합 0 모델은 unpriced에 없음
@Test void fast_배수가_없으면_fast_라벨로_미등록()   // "claude-test-5-5 (fast)"
@Test void 구간_단가는_그_모델의_기준_칸()           // thresholds [100000, 200000], 그 모델 기준 200000 → over.get(1)로 판정
@Test void series는_빈_버킷도_0()                   // DAY 24점, 2026-02 MONTH 28점, at은 +09:00
@Test void CSV() { assertThat(csv).startsWith("\uFEFF날짜,구분,모델,입력,출력,캐시쓰기5분,캐시쓰기1시간,캐시읽기,비용USD\n"); } // DAY 행 날짜 "2026-10-31 23:00", 미등록 비용 칸은 빈 값
// UsageSummaryServiceTest
@Test void 진행중_달은_지금까지_구독료로_나눈_속도()   // now 2026-11-16T03:00Z, 월 300 from 10-01, 비용 100 → feeShare 155.0000, fullPeriodFeeShare 300.0000, ratio 0.65, inProgress true
@Test void 직전_기간은_같은_경과_구간()               // aggregate 두 번째 호출 창 [2026-10-01T00:00+09:00, 2026-10-16T12:00+09:00)
@Test void 서울_자정_경계()                          // (Review Focus 1) now 2026-10-31T15:30Z, date 없음 → date 2026-11-01, start "2026-11-01T00:00+09:00", 창 from Instant 2026-10-31T15:00Z
@Test void 구독료_없음_일부없음_시작일이전()          // 행 없음 → ratio null·feeConfigured false; 10-16부터 → 10월 ratio null·feeMissing true; 9월 → cost 0·aggregate 안 부름·ratio null
@Test void 끝난_기간()                              // 10월 at 11-16 → inProgress false, feeShare == fullPeriodFeeShare, previous = 9월 전체(비어 cost 0, ratio null)
@Test void 응답_시각은_모두_서울()                    // start·end·series[].at·coverage 시각의 offset == +09:00
@Test void 잘못된_기간과_날짜는_400()                // summary("year", null) → TaskException BAD_REQUEST "알 수 없는 기간: year"; ("month","2026-13-01") → "날짜 형식이 틀렸습니다: 2026-13-01"; aggregate 안 부름
// UsageReportSecurityTest 추가
@Test void 사용자는_summary와_csv_403()               // 관리자 200, CSV Content-Type "text/csv;charset=UTF-8"
// (CI) UsageSummaryIntegrationTest — @TestPropertySource로 claude-test-5-5 가짜 가격
@Test void 서울_시각으로_묶는다()   // 2026-10-31T14:30Z(KST 23:30)·2026-10-31T15:30Z(KST 11-01 00:30) 이벤트
                                   // → month date=2026-10-15는 앞 것만; day date=2026-10-31은 series[23] > 0, series[0] == 0
@Test void 수집기_상태와_가장_이른_기록()   // 2026-09-30T14:00Z(시작일 이전) 이벤트를 더해도 earliestEventAt == "2026-10-31T23:30+09:00"; collectors는 machine 순
@Test void CSV_본문()                       // 관리자 day date=2026-10-31 → 머리줄 다음 행 1개, "2026-10-31 23:00,SYMPHONY,claude-test-5-5,"로 시작
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.usage.UsageSummaryAssemblerTest --tests com.hamonsoft.netismaker.usage.UsageSummaryServiceTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - 집계 SQL은 범위 필터를 Java에서 계산한 `OffsetDateTime`으로 건다(`occurred_at` 인덱스를 탄다). 버킷은 서울 시각으로 묶고 `LocalDateTime`으로 읽는다.
    ```sql
    SELECT date_trunc(?, occurred_at AT TIME ZONE 'Asia/Seoul') AS bucket, category, model, speed,
           (input_tokens + cache_write_5m_tokens + cache_write_1h_tokens + cache_read_tokens) > ? AS over_0, …  -- thresholds 수만큼
           SUM(input_tokens), SUM(output_tokens), SUM(cache_write_5m_tokens), SUM(cache_write_1h_tokens), SUM(cache_read_tokens)
    FROM com.claude_usage_event WHERE occurred_at >= ? AND occurred_at < ?
    GROUP BY bucket, category, model, speed, over_0, …
    ```
  - `summary`의 흐름
    1. `parse`로 기간을 정하고 `containing`으로 기간을 만든다.
    2. `inProgress`면 비용 창은 `elapsedWindow`, 아니면 `fullWindow`다. 몫 창도 같다. `fullPeriodFeeShare`는 늘 `fullWindow`로 계산한다.
    3. 직전 기간은 `previousComparable`로 정한다.
    4. `ratio`는 `feeMissing`이거나 행이 없거나 몫이 0이면 null이고, 아니면 cost ÷ share다.
  - 반올림: 비용·몫은 `setScale(4, HALF_UP)`, ratio는 반올림 전 값으로 나눈 뒤 `setScale(2, HALF_UP)`이다.
  - `coverage.earliestEventAt`은 count-from 이후에서 찾는다. `unpricedModels`는 이 기간 행에서 낸다.
  - CSV 행은 시각·구분·모델 순이고 토큰이 0인 조합은 뺀다. 일 기간은 시간별(`yyyy-MM-dd HH:00`), 주·월은 일별(`yyyy-MM-dd`)이다.
  - **설계와 다름 ①:** 구간 단가는 이벤트마다 계산하지 않고 기준값별 불린 칸을 GROUP BY에 더해 모델별 기준 칸으로 판정한다(결과는 같고 행을 Java로 끌어오지 않는다).
  - **설계와 다름 ②:** 응답에 `feeConfigured`를 더해 "구독료를 입력하세요"(이력 없음)와 "구독료 적용 전 기간 포함"(일부 날짜 없음)을 가른다. `period`가 없으면 month, `coverage.earliestEventAt`에도 count-from 하한(설계 미정).
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.usage.*" --tests "com.hamonsoft.netismaker.controller.Usage*"` PASS(세 CI 클래스는 skipped)
- [ ] **Step 5: 커밋** — `feat(usage): 일·주·월 집계·구독료 대비 배수·CSV`
- [ ] **Step 6(선택): Part A CI 먼저 보기** — 사용자 승인이 있으면 브랜치를 draft PR로 push해 CI `Backend` 잡에서 세 (CI) 클래스(V{n}·upsert `RETURNING (xmax = 0)`·불린 GROUP BY·`ddl-auto validate`)가 PASS인지 보고 Part B로 간다. 승인이 없으면 Task 11 Step 7에서 본다.

---

# Part B — 워커 수집 (설계 §4)

### Task 6: 수집 설정·경로 구분·줄 해석 (순수)

**Files:**
- Create: `M/workerdaemon/UsageCollectProperties.java`, `UsagePathClassifier.java`, `ClaudeCodeUsageLineParser.java`
- Create: `M/workerdaemon/UsageCollectConfig.java` — `@Configuration @EnableConfigurationProperties(UsageCollectProperties.class)`, 프로필 없음
- Modify: `R/application-worker.yml:64` 다음(`usage-collect` 블록 — 값은 Global Constraints "워커 설정"과 같다, 한국어 주석 3줄)
- Test: `T/workerdaemon/UsageCollectPropertiesTest.java`(`WorkerPropertiesYamlBindingTest.java:24-33,135-153` 하네스), `UsagePathClassifierTest.java`, `ClaudeCodeUsageLineParserTest.java`

**Interfaces:**
- Consumes: Task 1 `ClaudeCodeUsageEvent`
- Produces:
  - `@ConfigurationProperties(prefix="netis-maker.worker.usage-collect") record UsageCollectProperties(Boolean enabled, Integer intervalSeconds, Boolean includeDirect, List<String> symphonyRoots, String countFrom, String machine, Integer maxMbPerRun)`
    - null만으로 기본값을 채우고 예외를 내지 않는다. 프로필 없는 `UsageCollectConfig`라 api 프로필에서도 바인딩되기 때문이다.
    - 목록은 trim하고 빈 값을 뺀다(`M/workerdaemon/ArtifactProperties.java:16-19`). 빈 machine은 null로 둔다.
    - `LocalDate countFromDate()`, `long maxBytesPerRun()`
    - 잘못된 값은 env 이름을 넣은 `IllegalArgumentException`이다: `USAGE_COLLECT_INTERVAL_SECONDS`(<30), `USAGE_COLLECT_COUNT_FROM`(형식), `USAGE_COLLECT_MAX_MB_PER_RUN`(<1).
  - `UsagePathClassifier`
    - `enum Category { SYMPHONY, DIRECT }`
    - `static String normalize(String path)` — 순수 문자열이다(`Path` 쓰지 않음).
      - trim → `\`를 `/`로 → `//?/` 접두 제거 → `/c/…`를 `c:/…`로 → 겹 `/` 합치기 → 끝 `/` 제거 → `toLowerCase(Locale.ROOT)`
    - `static boolean isUnder(String normPath, String normRoot)` = 같거나 `normRoot + "/"`로 시작
    - `static String expandHome(String raw, String home)` — `~`, `~/…`, `~\…`
    - `static Roots roots(String worktreeRoot, String reposDir, String home, List<String> configured)`, `record Roots(List<String> accepted, List<String> ignored)`
      - 후보: worktreeRoot, reposDir, `{reposDir 부모}/close`, `{home}/netis-maker`, configured(펼친 뒤)
      - 정규화 결과가 빈 값·`/`·드라이브 루트(`c:`)·홈이면 `ignored`로 보낸다.
    - `static Category classify(String cwd, List<String> acceptedRoots)` — cwd가 null·공백이면 DIRECT
  - `ClaudeCodeUsageLineParser`
    - `static ParsedLine parse(byte[] utf8Line)`
    - `record ParsedLine(Kind kind, UsageLine line)`, `enum Kind { SKIP, UNREADABLE, EVENT }`
    - `record UsageLine(String eventKey, OffsetDateTime occurredAt, String cwd, String model, String speed, String serviceTier, String entrypoint, String sessionId, boolean sidechain, long input, long output, long cacheWrite5m, long cacheWrite1h, long cacheRead)`
      - `UsageLine maxWith(UsageLine o)` — 토큰은 칸별 최댓값, 나머지는 this
      - `ClaudeCodeUsageEvent toEvent(Category c)` — cwd는 싣지 않는다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
// UsageCollectPropertiesTest
@Test void 모두_null이면_기본값()       // enabled true, intervalSeconds 300, includeDirect false, symphonyRoots [], countFromDate 2026-10-01, machine null, maxBytesPerRun 256*1024*1024
@Test void yml과_env_재정의()           // USAGE_COLLECT_INCLUDE_DIRECT=true, USAGE_COLLECT_SYMPHONY_ROOTS=" C:\\netis-worker , ,~/netis-remote-worker" → [C:\netis-worker, ~/netis-remote-worker]
@Test void 빈_env는_기본값()             // USAGE_COLLECT_INTERVAL_SECONDS="" → 300, USAGE_COLLECT_MACHINE="" → null
@Test void 잘못된_값은_env_이름과_함께()  // 10 → "USAGE_COLLECT_INTERVAL_SECONDS"; "2026/10/01" → "USAGE_COLLECT_COUNT_FROM"; 0 → "USAGE_COLLECT_MAX_MB_PER_RUN"
// UsagePathClassifierTest
@Test void 정규화() { assertThat(normalize("C:\\Users\\Mic\\netis-maker\\")).isEqualTo("c:/users/mic/netis-maker");
    assertThat(normalize("/c/Users/mic/x")).isEqualTo("c:/users/mic/x"); assertThat(normalize("\\\\?\\C:\\a")).isEqualTo("c:/a");
    assertThat(normalize("/Users/Mic/x//y/")).isEqualTo("/users/mic/x/y"); }
@Test void 폴더_경계()   // (Review Focus 2) 루트 "C:\\Users\\mic\\netis-maker": "/c/Users/MIC/netis-maker/interviews/s" SYMPHONY,
                        //   "C:\\Users\\mic\\netis-maker\\close\\task-10" SYMPHONY, 루트 자신 SYMPHONY, "C:\\Users\\mic\\netis-maker-old\\x" DIRECT
@Test void cwd가_없으면_DIRECT()  // classify(null, roots)·classify("  ", roots) == DIRECT
@Test void 기본_루트()   // roots("C:\\w\\wt","C:\\d\\repos","C:\\Users\\mic",["~/netis-remote-worker"]).accepted()
                        //   ⊇ [c:/w/wt, c:/d/repos, c:/d/close, c:/users/mic/netis-maker, c:/users/mic/netis-remote-worker]
@Test void 홈과_루트는_무시()  // configured ["~","C:\\","/",""] → ignored에 들어가고 accepted에는 c:/users/mic·c:·/ 없음
// ClaudeCodeUsageLineParserTest — 줄은 시험 안 문자열(실제 기록 아님)
@Test void assistant_usage_줄()      // 키 "msg_1:req_1"; cache_creation {ephemeral_5m 100, ephemeral_1h 50} → 5m 100·1h 50; model·speed·serviceTier·entrypoint·sessionId·sidechain·cwd
@Test void 분리가_없으면_전체를_5분() // cache_creation_input_tokens 300, cache_creation 없음(또는 두 값 합 0) → 5m 300·1h 0
@Test void requestId가_없으면_message_id만()
@Test void 다른_줄은_SKIP()           // type user, usage 없는 assistant, summary 줄
@Test void 못_읽은_줄()               // 깨진 JSON, message.id 없음, timestamp 없음·"어제", 키 201자 → UNREADABLE
@Test void 모르는_칸과_빠진_칸을_견딘다() // 새 칸 "foo", output_tokens 없음 → EVENT·output 0; 음수 → 0; model 없음 → "unknown"
@Test void maxWith는_칸별_최댓값()
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.UsageCollect*" --tests com.hamonsoft.netismaker.workerdaemon.UsagePathClassifierTest --tests com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParserTest` → 컴파일 실패
- [ ] **Step 3: 구현** — JSON은 Jackson `readTree(byte[])`로 읽는다. `type`이 `assistant`가 아니거나 `message.usage` 객체가 없으면 SKIP이다.
  - **설계와 다름 ①:** Symphony 폴더 기본값에 repos-dir과 `{repos-dir 부모}/close`를 더한다(종료 노트 claude의 cwd, `M/workerdaemon/TaskCloser.java:174` — Mac처럼 데이터가 `~/netis-maker` 밖일 때).
  - **설계와 다름 ②:** 홈·파일시스템 루트로 정규화되는 설정 폴더는 무시하고 경고한다(모든 사용이 Symphony가 되는 것을 막는다).
  - **설계와 다름 ③:** 정규화(§9 위임)에 Git Bash형 `/c/…`(이 PC 1,374줄)를 넣고, 대소문자 무시는 Mac에도 적용한다(APFS 기본). cwd가 없으면 DIRECT.
  - **설계와 다름 ④:** `message.id` 없음·`timestamp` 해석 불가·키 200자 초과인 assistant+usage 줄은 "못 읽은 줄"이다(설계 §11). model이 없으면 `"unknown"`.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS, `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesYamlBindingTest` PASS
- [ ] **Step 5: 커밋** — `feat(usage): 수집 설정·경로 구분·기록 줄 해석`

---

### Task 7: 이어 읽기·상태 파일

**Files:**
- Create: `M/workerdaemon/JsonlTail.java`, `M/workerdaemon/UsageCollectorState.java`
- Test: `T/workerdaemon/JsonlTailTest.java`, `T/workerdaemon/UsageCollectorStateTest.java`

**Interfaces:**
- Produces:
  - `JsonlTail`
    - `static Chunk read(Path file, long offset, long budgetBytes) throws IOException` — 버퍼 1MB
    - 시험용 `static Chunk read(Path file, long offset, long budgetBytes, int bufferSize)`
    - `record Chunk(List<byte[]> lines, long nextOffset)`
    - `FileChannel.open(file, READ)`으로 연다. 0x0A 바이트로 줄을 나누고, 끝 `\r`은 뗀다.
    - 개행이 없는 마지막 조각은 돌려주지 않는다. `nextOffset`은 마지막 완성 줄의 개행 다음 바이트다.
    - 읽은 바이트가 `budgetBytes` 이상이 되면 그 줄 경계에서 멈춘다. 단, 완성된 줄이 있으면 상한보다 커도 적어도 한 줄은 돌려준다.
    - 파일이 없으면 `NoSuchFileException`을 그대로 던진다.
  - `UsageCollectorState.FileMark(long offset, long size, long mtimeMillis)`(중첩 record) — `offset`은 읽은 뒤의 `nextOffset`, `size`·`mtimeMillis`는 읽기 직전에 본 파일 크기·수정 시각이다. 상한에 걸려 중간에서 멈춘 파일은 `offset < size`로 남는다.
  - `UsageCollectorState`
    - `static UsageCollectorState load(Path file)` — 없거나 깨졌으면 빈 상태다. `boolean fresh()`는 그때 true다.
    - `FileMark mark(String path)`, `void put(String path, FileMark m)`, `void retainOnly(Set<String> paths)`
    - `void saveAtomically(Path file) throws IOException` — temp → `ATOMIC_MOVE`+`REPLACE_EXISTING`(`M/workerdaemon/SilentLossTracker.java:197-202`)
    - 형식 `{"version":1,"files":{"<경로>":{"offset":…,"size":…,"mtimeMillis":…}}}`
    - `static boolean unchanged(FileMark prev, long size, long mtimeMillis)` = `prev != null && prev.offset == size && size == prev.size && mtimeMillis == prev.mtimeMillis` — 끝까지 읽었고 그 뒤로 바뀌지 않은 파일만 건너뛴다. 개행 없는 끝 조각이 남은 파일은 회차마다 그 조각만 다시 읽는다(작다).
    - `static long startOffset(FileMark prev, long size, long mtimeMillis)` — 다음이면 0, 아니면 `prev.offset`
      - prev 없음
      - size < prev.size(줄었음)
      - size == prev.size인데 mtime이 다름(바뀌었음)

- [ ] **Step 1: 실패하는 시험 작성**

```java
// JsonlTailTest
@Test void 완성된_줄만_부분_줄은_남긴다()  // "a\nb\nc" → [a,b], nextOffset 4
@Test void 위치부터_읽는다()              // offset 2 → [b]
@Test void CRLF는_CR을_뗀다()               // "a\r\nb\r\n" → [a,b], nextOffset 6
@Test void 상한에_닿으면_줄_경계에서_멈춘다() // 100바이트 줄 10개, budget 250 → 3줄, nextOffset 300
@Test void 상한보다_큰_한_줄도_넘어간다()   // (Review Focus 3) 5000바이트 한 줄+"\n"+"b\n", budget 1024 → [그 줄], nextOffset 5001; 이어서 → [b]
@Test void 한글이_버퍼_경계에_걸려도_바이트_위치()  // bufferSize 7, 한글 줄 3개 → nextOffset == Files.size, 줄 내용 UTF-8 그대로
@Test void 없는_파일은_NoSuchFileException()
// UsageCollectorStateTest
@Test void 저장과_읽기()                    // put 두 경로 → saveAtomically → load: fresh() false, mark(경로) 값 같음, 없는 경로 → null
@Test void 깨진_파일이면_빈_상태()          // "{not json" → fresh() true
@Test void 원자적_저장()                    // 기존 파일 교체, 폴더에 .tmp 없음
@Test void 시작_위치()                      // prev null → 0; prev(400,600,m)+(500,m2) 줄었음 → 0; (600,m2) 크기 같고 mtime 다름 → 0; (900,m2) 덧붙음 → 400
                                            // prev(400,600,m)+(600,m) → unchanged false·startOffset 400(상한에 걸려 멈춘 파일); prev(600,600,m)+(600,m) → unchanged true
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.JsonlTailTest --tests com.hamonsoft.netismaker.workerdaemon.UsageCollectorStateTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 위치는 바이트 단위다. `BufferedReader`의 문자 수로는 계산할 수 없다.
  - **설계와 다름:** 설계의 "바뀌었으면"을 "크기는 같은데 수정 시각이 다르면"으로 정한다(다시 읽어도 중복은 API가 막는다).
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(usage): jsonl 바이트 위치 이어 읽기와 상태 파일`

---

### Task 8: 수집 작업·보내기·스케줄

**Files:**
- Create: `M/workerdaemon/ClaudeCodeUsageCollector.java`
- Modify: `M/workerdaemon/WorkerHttpClient.java`(`startup` `:105-116` 다음에 메서드), `M/workerdaemon/ClaudeAuthProbe.java:136-142`(`claudeConfigDir` 추출), `R/application-worker.yml:15-24`(풀 7→8, 주석 "9개"에 `ClaudeCodeUsageCollector` 추가)
- Test: `T/workerdaemon/ClaudeCodeUsageCollectorTest.java`, `T/workerdaemon/WorkerHttpClientUsageTest.java`(`WorkerHttpClientNextTaskTest.java:28-41`의 JDK `HttpServer` 스텁), `T/workerdaemon/ClaudeAuthProbeTest.java`(추가)

**Interfaces:**
- Consumes: Task 1 DTO, Task 6 전부, Task 7 전부
- Produces:
  - `static Path ClaudeAuthProbe.claudeConfigDir(Map<String,String> env, Path home)` — 대소문자 무시 `CLAUDE_CONFIG_DIR` 또는 `{home}/.claude`. `credentialsFile(env)`는 `Path.of(System.getProperty("user.home"))`를 넘겨 이것에 위임한다. 수집기는 주입받은 home을 넘긴다(시험이 실제 `~/.claude`를 읽지 않게).
  - `ClaudeCodeUsageBatchResponse WorkerHttpClient.postClaudeCodeUsage(ClaudeCodeUsageBatchRequest req)`
    - `POST /worker/usage/claude-code-events`
    - 본문이 null이면 `IllegalStateException("응답 본문 없음")`
    - 4xx·5xx(404 포함)는 `RestClientException` 그대로 던진다.
  - `ClaudeCodeUsageCollector`(`@Component @Profile("worker") implements SchedulingConfigurer`)
    - `@Autowired public ClaudeCodeUsageCollector(WorkerProperties props, UsageCollectProperties cfg, WorkerHttpClient http)` — 홈은 `user.home`, env는 `System.getenv()`
    - 시험용 package-private 생성자: `(WorkerProperties, UsageCollectProperties, WorkerHttpClient, Path home, Map<String,String> env)`
    - 상수: `LOCK_FILE = "claude-usage-collector.lock"`, `STATE_FILE = "claude-usage-collector.state.json"`(둘 다 `{home}/netis-maker` 아래), `BATCH_SIZE = 1000`, `INITIAL_DELAY = Duration.ofSeconds(60)`
    - `configureTasks`: enabled면 `registrar.addFixedDelayTask(new FixedDelayTask(this::collect, Duration.ofSeconds(interval), INITIAL_DELAY))`
    - `public void collect()` — enabled를 다시 보고, 예외는 모두 잡아 warn 한 줄로 끝낸다(`M/workerdaemon/RepoCacheCleanupJob.java:109-133` 방식).
    - `Result runOnce() throws IOException`
    - `List<Path> listJsonl(Path projectsDir) throws IOException`(package-private, 시험 seam) — `walkFileTree`, `visitFileFailed`면 CONTINUE, 경로 순 정렬
    - `String machineName()` — 설정값, 없으면 `InetAddress.getLocalHost().getHostName()`, 실패하면 `"unknown"`. 100자로 자른다.
    - `record Result(boolean skipped, int files, long newLines, int symphony, int direct, int directDropped, int beforeCountFrom, int unreadable, boolean budgetHit, boolean sent, int inserted, int updated, int rejected)`
  - 회차 로그
    - 성공: `Claude 사용량 수집: 파일 {files} · 새 줄 {newLines} · Symphony {symphony} · 직접 {direct} · 직접 제외 {directDropped} · 시작일 이전 {beforeCountFrom} · 못 읽은 줄 {unreadable} · 새로 {inserted} · 갱신 {updated} · 거절 {rejected}`
      - 상한에 닿았으면 끝에 ` · 읽기 상한 {N}MB 도달 — 다음 회차에 이어 읽음`
    - 실패: `Claude 사용량 보내기 실패 — 위치를 그대로 두고 다음 회차에 다시 보냄: {사유}`. 404면 사유는 `404(구버전 API)`다.
    - 무시한 루트: `Symphony 폴더 설정 무시(홈·파일시스템 루트): {값}`

- [ ] **Step 1: 실패하는 시험 작성** — `@TempDir` 가짜 홈과 Mockito `WorkerHttpClient`. 응답은 `(n, n, 0, 0)`이고 `ArgumentCaptor`로 요청을 잡는다.

```java
@Test void 같은_응답_여러_줄은_칸별_최댓값_한_건()   // msg_1/req_1 output 12(stop_reason null) → 250 → 이벤트 1건, outputTokens 250
@Test void subagents와_workflows까지_읽는다()       // p/s.jsonl · p/s/subagents/a.jsonl · p/s/subagents/workflows/wf_1/b.jsonl → 키 3개
@Test void 옮겨진_사본은_키로_한_건()                // 같은 줄이 두 세션 폴더에 → 1건
@Test void 부분_줄은_다음_회차로()                   // 끝 줄 개행 없음 → 그 키 없음; 개행을 붙인 뒤 다음 회차에 보냄
@Test void 덧붙은_줄만_새_줄이_없어도_빈_묶음()      // 2회차: postClaudeCodeUsage 1번, events [], unreadableLines 0; 줄 추가 → 새 키만
@Test void 파일이_줄면_처음부터()                    // 상태 size(와 offset)가 실제 크기보다 큼 → 그 파일을 0부터 읽어 키를 다시 보냄
@Test void 구분과_include_direct()                   // worktreeRoot 아래 SYMPHONY, "{가짜 홈}/NETIS-MAKER/close/task-3"(구분자 바꿈) SYMPHONY, 다른 폴더 DIRECT;
                                                     //   includeDirect=false → DIRECT 안 보내고 다음 회차에도 안 보냄(위치 전진)
@Test void count_from_이전은_보내지_않는다()          // "2026-09-30T14:59:59Z" 없음, "2026-09-30T15:00:00Z" 있음
@Test void 천_건씩_나눈다()                          // 키 2500 → 크기 1000·1000·500
@Test void 보내기가_실패하면_상태를_저장하지_않는다()   // 두 번째 묶음 RestClientException → 상태 파일 그대로, 다음 회차에 같은 키 전부
@Test void 구_API_404도_다음_회차에_다시()            // HttpClientErrorException.NotFound → Result.sent false
@Test void 내용_도구입력_cwd는_보내지_않는다()        // 잡은 요청을 ObjectMapper로 직렬화 → "SECRET-CONTENT"·"SECRET-TOOL-INPUT"·cwd 폴더 이름이 없음
@Test void 다른_워커가_락을_쥐면_건너뛴다()           // RepoCacheCleanupJobTest.java:590-606처럼 FileChannel.lock() → skipped, http never
@Test void 걷는_도중_지워진_파일은_빼고_계속()        // (Review Focus 4) spy(listJsonl)가 없는 경로를 섞어 돌려줌 → 나머지 보냄, 상태에 그 경로 없음
@DisabledOnOs(OS.WINDOWS) @Test void 못_여는_폴더는_건너뛰고_계속()  // 권한 000 폴더 하나 → 나머지 파일의 키는 보냄, 예외 없음
@Test void 상태_파일이_깨졌으면_처음부터()             // 상태 파일 "{not json" → 모든 파일을 0부터 읽어 키 전부 보냄, 저장 뒤 상태 파일이 정상 JSON
@Test void 상한에_닿으면_다음_회차에_이어_읽는다()     // maxMbPerRun 1, 각 줄 1KB(키 다름) 600줄 파일 3개(합 1800키)
                                                     //   → 1회차 budgetHit, 키 < 1800; 2회차에 두 번째 파일의 남은 키 전부와 세 번째 파일 → 두 회차 합집합 1800; 3회차 빈 묶음
@Test void CLAUDE_CONFIG_DIR를_따른다()               // env {"claude_config_dir": tmp/cfg} → tmp/cfg/projects
@Test void env가_없으면_가짜_홈()                     // env {} → listJsonl에 넘긴 경로가 {가짜 홈}/.claude/projects, 걸은 경로가 모두 @TempDir 아래
@Test void 등록과_끄기()                              // getFixedDelayTaskList(): 1개, interval 300s, initialDelay 60s; enabled=false → 0개
@Test void machine()                                  // 설정 "pc-a" → "pc-a"; 없으면 비어 있지 않은 호스트 이름
// WorkerHttpClientUsageTest
@Test void 경로_헤더_본문()                           // POST /worker/usage/claude-code-events, X-Worker-API-Key, body machine·events[0].eventKey, 응답 파싱
@Test void 404는_RestClientException()
// ClaudeAuthProbeTest 추가
@Test void claude_config_dir()                         // claudeConfigDir(Map.of(), home) == home/.claude; {"CLAUDE_CONFIG_DIR": x} → x; 기존 credentials_file_honors_claude_config_dir(:177-183) 유지
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageCollectorTest --tests com.hamonsoft.netismaker.workerdaemon.WorkerHttpClientUsageTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `runOnce`는 다음 순서로 돈다.
  1. `{home}/netis-maker`를 `createDirectories`한다. 락 채널을 열고 `tryLock`이 null이면 `skipped`다(`RepoCacheCleanupJob.java:167-174,186-193`). 이후 전부 락 안에서 한다.
  2. 상태를 읽는다. `projects`가 없으면 파일 0개다.
  3. 파일마다 크기와 수정 시각을 본다. `NoSuchFileException`이면 그 파일을 빼고 계속한다. `unchanged`면 건너뛴다. 그렇지 않으면 `startOffset`부터 남은 상한으로 `JsonlTail.read`를 하고 `FileMark(nextOffset, 본 크기, 본 시각)`을 회차 목록에 둔다. 상한이 다 차면 남은 파일은 이번 회차에 손대지 않는다.
  4. 파일 하나의 Chunk를 읽으면 곧바로 줄을 해석·병합하고 바이트 목록은 버린다(회차에 남는 것은 키별 `UsageLine` 맵뿐). 시작일 이전 줄과, include-direct가 false일 때 DIRECT 줄은 세기만 하고 버린다. 남은 줄은 키마다 `maxWith`로 합친다.
  5. 1000건씩 보낸다. 0건이면 빈 묶음을 한 번 보낸다. 모든 묶음에 `unreadableLines`를 싣는다.
  6. 모두 성공하면 새 위치를 `put`하고, 걷기에 없던 경로는 `retainOnly`로 지운 뒤 `saveAtomically`한다. 하나라도 실패하면 저장하지 않는다.
  - 풀 7→8: poll이 스레드 하나를 오래 잡으므로 나머지 8개가 한꺼번에 겹쳐도 heartbeat가 밀리지 않게 한다.
  - RestClient에 타임아웃은 더하지 않는다(`WorkerHttpClient.java:70-73` 공유). API가 멈춰도 묶이는 스레드는 수집 스레드 하나다.
  - **설계와 다름 ①:** "성공한 만큼만 위치를 옮긴다"를 회차 단위로 한다 — 모든 묶음이 성공해야 상태를 저장한다(재전송은 upsert가 흡수, 회차 양은 상한으로 묶임).
  - **설계와 다름 ②:** 회차 상한 `max-mb-per-run`(256MB)과 첫 실행 60초 지연을 둔다(§9 위임, 이 PC 첫 회차 1.2GB 대비).
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*"` PASS(`WorkerDaemonConstructorContractTest`가 `@Autowired` 1개를 확인)
- [ ] **Step 5: 커밋** — `feat(usage): 워커 Claude Code 사용량 수집 작업`

---

# Part C — 화면 (설계 §7)

### Task 9: 응답 미러·상세 다이얼로그·구독료 설정

**Files:**
- Create: `F/composables/usageReport.ts` + `.spec.ts`
- Create: `F/components/tasks/UsageCostDialog.vue` + `.spec.ts`, `F/components/tasks/UsageFeeSettings.vue` + `.spec.ts`
- Test: 위 spec 셋

**Interfaces:**
- Consumes: Task 4·5 응답 계약
- Produces (`usageReport.ts`, `F/composables/claudeUsage.ts`처럼 "Java 응답 계약 미러 + 표시 규칙"):
  - 타입
    - `UsageSummary`·`UsageTokens`·`CategoryUsage`·`ModelUsage`·`PreviousUsage`·`SeriesPoint`·`CollectorStatus`·`UnpricedModel`·`SubscriptionFee` — Task 5·4 필드명 그대로
    - `type UsagePeriodValue = 'day' | 'week' | 'month'`
    - `PERIOD_OPTIONS = [{ label: '일', value: 'day' }, { label: '주', value: 'week' }, { label: '월', value: 'month' }]`
  - URL
    - `summaryUrl(period, date?: string): string` → `/api/usage/summary?period=…[&date=…]`
    - `csvUrl(period, date): string`
  - 표기
    - `formatUsd(v): string` — 100 이상이면 `$1,234`, 미만이면 `$12.35`, null이면 `—`. Intl을 쓰지 않는다(`claudeUsage.ts:53-56`).
    - `formatRatio(v): string | null` → `6.2배`
    - `formatTokens(n): string` → `1.23M`·`45.6K`·`789`
    - `totalTokens(t: UsageTokens | null | undefined): number` — 다섯 칸의 합(Java `total()`은 JSON에 실리지 않는다), null이면 0
    - `periodLabel(s): string`
      - 진행 중: `오늘(지금까지)`·`이번 주(지금까지)`·`이번 달(지금까지)`
      - 끝난 기간: `2026-10-31`·`2026-10-26 ~ 2026-11-01`·`2026-10`
    - `chipText(s: UsageSummary | null | undefined): string`
  - 날짜
    - `shiftDate(period, startDate: string, dir: -1 | 1): string` — `Date.UTC`·`getUTC*`만 쓴다.
    - `defaultFeeStart(s): string` — `earliestEventAt`의 달 1일, 없으면 `countFrom`의 달 1일. 문자열 앞 7자로 만든다.
  - `isStale(lastReportedAt, now, minutes = 60): boolean`
  - `UsageCostDialog.vue`
    - `defineModel<boolean>()`, prop `initialPeriod: UsagePeriodValue`, emit `fee-saved`
    - 열릴 때마다 date 없이 한 번 부른다(`F/components/InterviewHistoryDialog.vue:52-58` 방식).
  - `UsageFeeSettings.vue`: prop `defaultFrom: string`, emit `saved`. 마운트 때 `GET /api/usage/subscription-fees`를 부른다.
  - data-test
    - 다이얼로그: `cost-dialog`, `cost-close`, `cost-prev`, `cost-next`, `cost-period`, `cost-tokens`, `cost-total`, `cost-fee-share`, `cost-ratio`, `cost-previous`, `cost-category-bar`, `cost-model-row`, `cost-series-bar`, `cost-collector-row`, `cost-unpriced`, `cost-csv`, `fee-toggle`
    - 구독료 설정: `fee-from`, `fee-amount`, `fee-save`, `fee-row`

- [ ] **Step 0: 프론트 준비** — junction이 있으면 `cmd /c rmdir frontend\node_modules`로 걷는다. `cd frontend; npm ci`(npm 11 이상 — `npm -v`가 낮으면 `npx -y npm@11 ci`, postinstall이 `nuxi prepare`를 돈다) → 성공. 이어서 `npm run lint` → 오류 0, 경고 수 N을 기준선으로 PR 초안에 적는다.
- [ ] **Step 1: 실패하는 시험 작성**

```ts
// usageReport.spec.ts
it('금액·배수·토큰', () => { expect(formatUsd(1234.4)).toBe('$1,234'); expect(formatUsd(99.994)).toBe('$99.99'); expect(formatUsd(0)).toBe('$0.00'); expect(formatUsd(null)).toBe('—')
  expect(formatRatio(6.24)).toBe('6.2배'); expect(formatRatio(null)).toBeNull(); expect(formatTokens(1234567)).toBe('1.23M'); expect(formatTokens(45600)).toBe('45.6K') })
it('칩 문구', () => { expect(chipText(month({ cost: 1234, ratio: 6.24 }))).toBe('이번 달(지금까지) $1,234 · 구독료의 6.2배')
  expect(chipText(month({ ratio: null, feeConfigured: false }))).toBe('이번 달(지금까지) $1,234 · 구독료를 입력하세요')
  expect(chipText(month({ ratio: null, feeConfigured: true, feeMissing: true }))).toBe('이번 달(지금까지) $1,234 · 구독료 적용 전 기간 포함')
  expect(chipText(month({ cost: 0, ratio: null, feeConfigured: true, feeMissing: false }))).toBe('이번 달(지금까지) $0.00')  // 사용 없음·몫 0 — 꼬리 없음
  expect(chipText(null)).toBe('Claude 사용량 —') })
it('토큰 합')          // totalTokens({ input: 1, output: 2, cacheWrite5m: 3, cacheWrite1h: 4, cacheRead: 5 }) == 15; totalTokens(null) == 0
it('기간 이름')        // 진행 중 day·week → '오늘(지금까지)'·'이번 주(지금까지)'; 끝난 week start '2026-10-26T00:00:00+09:00' end '2026-11-02T00:00:00+09:00' → '2026-10-26 ~ 2026-11-01'
it('날짜 옮기기는 시간대와 무관', () => { expect(shiftDate('month', '2026-03-01', -1)).toBe('2026-02-01'); expect(shiftDate('week', '2026-11-02', 1)).toBe('2026-11-09'); expect(shiftDate('day', '2026-03-01', -1)).toBe('2026-02-28') })
it('구독료 시작일 기본값')  // earliestEventAt '2026-10-03T09:12:00+09:00' → '2026-10-01'; null·countFrom '2026-10-01' → '2026-10-01'
// UsageCostDialog.spec.ts
it('열면 요약을 한 번 부르고 큰 숫자')      // useApiMock 첫 호출 '/api/usage/summary?period=month'; cost-tokens '1.23M'(토큰 합 1,234,567), cost-total '$1,234', cost-ratio '6.2배'
it('이전·다음은 시작일로 date를 옮긴다')    // cost-prev → '/api/usage/summary?period=month&date=2026-10-01'; inProgress면 cost-next disabled
it('일·주·월 전환은 date 없이 다시')        // cost-prev 뒤 cost-period에서 '주' → 마지막 호출 '/api/usage/summary?period=week'(date 없음)
it('범주 막대·모델 표·기간 막대')           // cost-category-bar 칸 두 개(style width %), cost-model-row 2개, day면 cost-series-bar 24개
it('미등록 경고·수집 상태·안내 문구')       // cost-unpriced 'claude-x-1'; cost-collector-row 3개, 2시간 전 보고 행은 text-negative; 'claude.ai 채팅 제외 · 기록은 Claude Code가 약 30일 보관한 것부터'
it('CSV 내려받기는 blob')                   // useApiMock toHaveBeenCalledWith('/api/usage/summary.csv?period=month&date=2026-11-01', { responseType: 'blob' }), a.download 'claude-usage-month-2026-11-01.csv'
it('구독료 설정은 열 때만 이력을 부른다')    // 열기 전 '/api/usage/subscription-fees' 호출 없음; fee-toggle 뒤 1번
it('390px에서는 전체 화면, 닫기 바')         // document.body '.q-dialog__inner--maximized', cost-close aria-label '닫기'
it('null 응답에도 깨지지 않는다')            // useApiMock → null: cost-total '—', cost-tokens '0', cost-model-row 0개, 예외 없음
// UsageFeeSettings.spec.ts
it('이력과 기본 시작일')                     // fee-row 2개(최신순), fee-from '2026-10-01'
it('저장 바디', async () => expect(useApiMock).toHaveBeenCalledWith('/api/usage/subscription-fees', { method: 'POST', body: { effectiveFrom: '2026-10-01', monthlyFee: 200 } }))   // 뒤에 이력 다시 부르고 saved emit
it('금액이 비었거나 0 이하·날짜 형식이 틀리면 저장 막힘')  // fee-amount '' · '0' · '-5', 또는 fee-from '2026-1' → fee-save disabled, POST 호출 없음
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run composables/usageReport.spec.ts components/tasks/UsageCostDialog.spec.ts components/tasks/UsageFeeSettings.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - 다이얼로그 셸
    - `q-dialog :maximized="fullscreen"`(`useFullscreenDialog`), `q-card style="width: min(760px, 100vw)"`, `.dialog-body`
    - 폰 상단 닫기 바는 `F/components/AdminFormDialog.vue:38-50` 모양이다.
    - 제목은 `Claude 사용량 · API 환산 비용`이다.
  - 큰 숫자
    - 라벨은 `토큰 합계`(`formatTokens(totalTokens(s.tokens))`)·`API 환산 비용`·`구독료 몫`·`배수`·`직전 기간 대비`다(토큰 합계는 설계 §3 "표시").
    - 진행 중이면 `기간 전체 구독료 {fullPeriodFeeShare}`를 덧붙인다.
    - 직전 비교 문구는 진행 중이면 `직전 같은 구간 $X · N배`, 아니면 `직전 기간 $X · N배`다.
  - 범주 막대: `Symphony 작업 N%` / `직접 사용 N%`. 비율은 비용 기준이고, 비용이 0이면 토큰 합 기준이다.
  - 모델 표: 칸은 모델·입력·출력·캐시 쓰기(5분)·캐시 쓰기(1시간)·캐시 읽기·비용이다. 가로 스크롤 래퍼로 감싼다.
  - 기간 막대: 막대 높이는 최댓값 대비 %다.
  - 수집 상태
    - 행은 `{machine} · formatAgo(lastReportedAt, now)`(`F/composables/claudeUsage.ts:83-92`)이고, 못 읽은 줄이 있으면 `못 읽은 줄 N`을 붙인다.
    - `isStale`이면 `text-negative`로 칠한다.
    - `가장 이른 기록 YYYY-MM-DD HH:mm`을 보여 준다.
  - CSV: `downloadAttachment(csvUrl(…), 'claude-usage-{period}-{start 앞 10자}.csv')`(`F/composables/attachmentDownload.ts:5-26`)를 쓰고, 실패하면 `$q.notify` negative다.
  - 구독료 설정
    - `구독료 설정` 버튼이 v-if 영역을 연다. q-expansion-item은 쓰지 않는다(접혀 있어도 마운트된다).
    - 입력은 `적용 시작일 (YYYY-MM-DD)`(mask `####-##-##`)·`월 구독료 (USD)`이고, 버튼은 `저장`이다. 폰에서는 입력 글자 16px(`F/components/AdminFormDialog.vue:88-91`, iOS 확대 방지).
    - 이력 표 칸은 적용 시작일·월 구독료·입력·입력 시각이다.
  - 기간 선택은 `q-btn-toggle`(`F/components/ModelEffortFields.vue:104-116`)이다.
  - **설계와 다름 ①:** "다음"은 진행 중인 기간에서 막는다(설계 미정).
  - **설계와 다름 ②:** 1시간 넘게 보고가 없는 PC를 빨갛게 표시한다(설계 §11 "마지막 보고 시각으로 알아챈다").
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(usage): 사용량 상세 다이얼로그와 구독료 설정`

---

### Task 10: 작업 목록 칩·배치·스모크

**Files:**
- Create: `F/components/tasks/UsageCostChip.vue` + `.spec.ts`
- Modify: `F/pages/tasks/index.vue`
  - import(`:11-19` 근처)
  - 모바일: `:551` `<TaskListMobile v-if>` **앞**에 `<UsageCostChip v-if="isAdmin && $q.screen.lt.md" class="q-mb-sm" />`
  - 데스크톱: `:568` `<q-space />` 다음에 `<UsageCostChip v-if="isAdmin" class="q-mr-md" />`
- Modify: `F/scripts/hydration-smoke.mjs:140`(`/api/usage/claude` 목 옆에 `/api/usage/summary` 목 — 진행 중 월, 범주 2·모델 1·수집기 1 행)
- Test: `F/test/tasks-index-usage-cost.spec.ts`(기존 `tasks-index-usage.spec.ts`와 이름을 겹치지 않게), `F/test/hydration-auth-gate.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 9 `summaryUrl`·`chipText`·`PERIOD_OPTIONS`·`UsageCostDialog`
- Produces
  - `UsageCostChip.vue`: props 없음. 상태는 `period`(기본 `'month'`)다. `useApi`로 마운트 때 한 번, 기간을 바꿀 때 한 번 부른다. `useTaskPolling`은 쓰지 않는다.
  - data-test: `cost-chip`, `cost-chip-period`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
// UsageCostChip.spec.ts
it('마운트 때 이번 달 요약을 한 번')     // '/api/usage/summary?period=month', 텍스트 '이번 달(지금까지) $1,234 · 구독료의 6.2배'
it('일·주·월 전환은 다시 부른다')        // 'day' → '/api/usage/summary?period=day', '오늘(지금까지) …'
it('자동 갱신하지 않는다')               // vi.useFakeTimers(); advanceTimersByTime(600_000) 뒤 호출 수 그대로
it('null·실패면 Claude 사용량 —')
it('누르면 같은 기간으로 상세가 열리고, 구독료 저장 뒤 다시 부른다')
// tasks-index-usage-cost.spec.ts (tasks-index-mobile.spec.ts:12-15 QLayout 래퍼)
it('관리자 1280px: q-space 뒤 칩, 첫 QSelect는 상태 select 그대로')  // tasks-index-mobile.spec.ts:236 전제 유지
it('관리자가 아니면 칩도 요약 호출도 없다')                         // useApiMock 호출 중 '/api/usage/summary'로 시작하는 것 없음
it('관리자 390px: 칩이 summary-strip 위')                           // compareDocumentPosition → FOLLOWING
// hydration-auth-gate.spec.ts '작업 목록(모바일)'(:106-135)에 추가: 하이드레이션 전 cost-chip 없음, 뒤에 있음
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run components/tasks/UsageCostChip.spec.ts test/tasks-index-usage-cost.spec.ts test/hydration-auth-gate.spec.ts` → FAIL
- [ ] **Step 3: 구현** — 시작 전에 `git fetch; git rebase origin/main`(Global Constraints "작업 타입 기능과의 충돌").
  - 칩과 세그먼트는 한 줄에 두되 좁으면 줄바꿈된다(`row items-center wrap`). 폰(lt.md)에서는 누르는 높이를 44px로 한다(`TaskListMobile.vue`의 `.chip-row :deep(.q-chip)`과 같다).
  - `index.vue:551-563`의 v-if/v-else 짝 사이에는 아무것도 넣지 않는다.
  - **설계와 다름:** 모바일 칩(설계 §7 "`TaskListMobile.vue` 위")을 `TaskListMobile.vue`가 아니라 `index.vue`에서 그 앞에 둔다 — 작업 타입 계획의 `TaskListMobile.vue:24-54,94-162`와 충돌을 피하고 `TaskListMobile.spec.ts`를 건드리지 않는다.
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 PASS(`tasks-index-mobile.spec.ts`·`tasks-index-usage.spec.ts` 포함), `npx nuxi prepare; npm run typecheck` 오류 0
- [ ] **Step 5: 커밋** — `feat(usage): 작업 목록 사용량 칩(관리자)`

---

### Task 11: 문서·배포 순서·마지막 검증

**Files:**
- Modify: `CLAUDE.md`
  - `:198` "구독 사용량" 행에 새 파일들
  - `:249` "워커 스케줄 풀 7(워커 스케줄 작업 8개 …)" → 풀 8·작업 9개(`ClaudeCodeUsageCollector` 추가)
  - "다중 워커 운영"에 단락 하나: 수집 규칙, env 키, 원격은 Symphony만, 세는 시작일, 가격표 고치는 법(PR로), 복구법 — "수집 설정(include-direct·symphony-roots·count-from)을 바꿨거나 틀린 채 돌았으면 워커를 멈추고 `~/netis-maker/claude-usage-collector.state.json`을 지운 뒤 재기동한다. 다음 회차가 처음부터 다시 읽고 중복은 API upsert가 막는다. 버려진 줄은 위치만 전진하므로 이 방법뿐이고, 기록이 30일 뒤 지워지기 전에 해야 한다."

- [ ] **Step 1: 전체 시험**
  - `.\gradlew.bat test` → PASS(Testcontainers는 skipped)
  - `cd frontend; npx vitest run; npx nuxi prepare; npm run typecheck` → PASS, 오류 0
- [ ] **Step 2: 하이드레이션 스모크** — Chrome이 있는지 확인한다(없으면 `$env:SMOKE_BROWSER_CHANNEL='msedge'`). `cd frontend; npm run build; npm run test:hydration` → 경고·예외 0, "목 응답이 없어 null로 답한 API" 없음
- [ ] **Step 3: lint** — `cd frontend; npm run lint` → 오류 0, 경고 수가 기준선 이하
- [ ] **Step 4: CLAUDE.md** — Files의 세 곳을 고친다.
- [ ] **Step 5: 커밋** — `docs(usage): Claude 사용량 보고 흐름과 배포 순서`
- [ ] **Step 6: PR 본문에 배포 순서를 적는다**(설계 §8, 실행자는 배포하지 않는다). 수집 env는 **새 코드가 뜨기 전에** 넣는다 — 이 PC 워커는 main 체크아웃을 그대로 `bootRun`하므로(`scripts/start-public.ps1:328`) API용 pull 뒤 재기동·자동 기동만으로도 새 수집기가 뜨고, env 없이 돈 첫 회차의 버린 줄은 위치만 전진한다.
  0. **env 먼저:** 이 PC `public.env`에 `USAGE_COLLECT_INCLUDE_DIRECT=true`(API에는 영향 없음). 원격은 `worker.env`에 `USAGE_COLLECT_SYMPHONY_ROOTS`(10.1.1.75 `C:\netis-worker`, Mac `~/netis-remote-worker`)·`USAGE_COLLECT_MACHINE=<사용자와 정한 이름>`, `INCLUDE_DIRECT`는 넣지 않는다.
  1. **API**(V{n}): `.\scripts\stop-public.ps1 -Only api` → `Start-ScheduledTask -TaskName netisMaker-public`, Flyway 적용 줄 확인.
  2. **워커 3대**(진행 중 작업이 없을 때): 이 PC는 1과 같은 방법으로 `-Only worker`, 원격은 jar 교체 → `stop-worker.ps1`/`stop-worker.sh` → 재기동. 60초 뒤 첫 회차부터 과거분을 회차당 256MB씩 채운다.
  3. **프론트:** `.output` 재빌드.
  4. **구독료:** 관리자가 "구독료 설정"에서 입력.
  - 복구법(CLAUDE.md 단락과 같은 문장)을 본문에 한 줄 둔다.
- [ ] **Step 7: CI 확인** — 사용자 승인 뒤 draft PR을 push한다(Task 5 Step 6에서 이미 열었으면 그 PR에 push — Task 10의 rebase 뒤라면 `--force-with-lease`, 이것도 사용자 승인 뒤).
  - push 전에 세 (CI) 클래스가 모두 `RUN_TESTCONTAINERS` 애너테이션을 다는지 본다: `ClaudeCodeUsageIngestIntegrationTest`·`UsageSubscriptionFeeIntegrationTest`·`UsageSummaryIntegrationTest`. `rg -l 'named = "RUN_TESTCONTAINERS"' src/test` 결과에 셋이 모두 있어야 한다.
  - CI `Backend`·`Frontend` 잡이 성공해야 한다. 실패하면 `backend-test-reports`를 본다. PR 본문에 run id를 적는다.
- [ ] **Step 8: 병합 직전** — `git fetch; git rebase origin/main`. V{n}을 `origin/main`의 `R/db/migration` 최대 번호+1과 **정확히 같게** 파일 이름만 바꾼다(겹침·빈 번호 모두 안 됨, Global Constraints "마이그레이션"). 다른 계획이 먼저 병합됐으면 머리줄 순서·`hydration-smoke.mjs` 목을 확인한다. rebase했으면 사용자 승인 뒤 `git push --force-with-lease` → CI 3개(main 보호 필수)가 다시 통과하는지 본다.
- [ ] **Step 9: 라이브 확인**(배포 뒤 사용자와 함께. 실제 `~/.claude`를 읽는 단계는 여기뿐이고, 읽기만 한다)
  - **워커 로그:** `Claude 사용량 수집:` 줄을 본다. `읽기 상한 … 도달`이 사라질 때까지 회차가 이어져야 하고, `못 읽은 줄`은 0이거나 소수여야 한다. 원격 두 대에서도 같은 줄이 나와야 한다.
    - 이 PC 첫 회차의 `직접 제외`가 0이어야 한다(0이 아니면 include-direct가 빠진 채 돈 것 — 복구법대로 상태 파일을 지우고 재기동).
  - **중복 대조(이 PC):** scratchpad에 읽기 전용 스크립트를 둔다(커밋하지 않는다). 이 스크립트는 `~/.claude/projects/**/*.jsonl`의 assistant+usage 줄을 Task 6 규칙(키, 칸별 최댓값, `2026-10-01T00:00+09:00` 이후)으로 센다. 그 결과를 PowerShell에서 전체 경로로 실행한 psql의 `SELECT count(*), sum(output_tokens) FROM com.claude_usage_event WHERE machine='<이 PC>'`와 비교한다. 수집 뒤에 새로 쓰인 줄만큼의 차이만 허용한다.
  - **구분:** `SELECT machine, category, entrypoint, count(*) FROM com.claude_usage_event GROUP BY 1,2,3`
    - 원격 두 대는 DIRECT가 0이어야 한다.
    - 이 PC의 `claude-desktop`은 대부분 DIRECT다(Symphony 폴더 안 직접 사용은 설계 §11 기준 5건 안팎).
    - `sdk-cli`·`sdk-ts`는 SYMPHONY여야 한다.
  - **화면:**
    - 관리자 `/tasks`의 칩, 상세의 수집 상태에 PC 세 대가 보여야 한다.
    - "가격 미등록 모델"에 실제 모델이 뜨면 모델 이름만 기록한다. 공식 가격을 Task 2 Step 3과 같은 방법으로 확인 → `origin/main`에서 새 브랜치로 yml만 고친 작은 PR → 병합 뒤 API 재기동. 라이브 체크아웃의 파일은 고치지 않는다(API도 main 체크아웃을 그대로 `bootRun`한다, `scripts/start-public.ps1:247`).
    - 관리자가 아닌 계정에서는 칩이 없고 `/api/usage/summary`가 403이어야 한다.
