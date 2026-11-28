package com.hamonsoft.netismaker.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.util.StackSecretEnv;
import com.hamonsoft.netismaker.workerdaemon.PromptResultParser;
import com.hamonsoft.netismaker.workerdaemon.WorkerHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker-rs 계약 골든 파일(worker-rs/contract/)을 지키는 시험 (스펙 2026-11-28-rust-worker-skeleton §9).
 * 실제 Java 클래스에서 만든 내용 == 파일을 단언한다. Rust는 같은 파일을 내장하거나 시험에서 읽는다.
 * 다시 쓰기: ./gradlew test --tests '*WorkerRsContractTest' -Dcontract.update=true --no-daemon
 * 파일은 항상 UTF-8·LF로 쓰고 읽는다(.gitattributes가 체크아웃 때도 LF를 고정한다).
 */
class WorkerRsContractTest {

    private static final Path DIR = Path.of("worker-rs", "contract");
    private static final boolean UPDATE = Boolean.parseBoolean(System.getProperty("contract.update", "false"));
    private static final String HINT =
            "계약 파일이 Java와 다르다. 의도한 변경이면 Rust 쪽도 맞춘 뒤 다시 쓴다: "
                    + "./gradlew test --tests '*WorkerRsContractTest' -Dcontract.update=true --no-daemon";

    /** API와 같은 매퍼 — 이 API는 사용자 정의 ObjectMapper 설정이 없어 스프링 부트 기본이다(빌더 + JacksonAutoConfiguration이 끄는 날짜·기간 타임스탬프 쓰기). */
    private final ObjectMapper api = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .build();
    /** Rust가 보내는 모양 — null 칸 없음. */
    private final ObjectMapper nonNull = api.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ---------- constants ----------

    @Test
    void constantsMatchJavaSources() throws Exception {
        ObjectNode c = api.createObjectNode();
        ArrayNode secrets = c.putArray("secretEnvNames");
        StackSecretEnv.NAMES.forEach(secrets::add);
        ObjectNode statuses = c.putObject("taskStatuses");
        for (TaskStatus s : TaskStatus.values()) statuses.put(s.name(), s.dbValue());
        ArrayNode kinds = c.putArray("kinds");
        for (WorkerTaskResponse.Kind k : WorkerTaskResponse.Kind.values()) kinds.add(k.name());
        ArrayNode supports = c.putArray("supports");
        // 패키지 전용 상수 — 운영 코드를 바꾸지 않고 리플렉션으로 읽는다
        String supportsConst = (String) ReflectionTestUtils.getField(WorkerHttpClient.class, "SUPPORTS");
        assertThat(supportsConst).isNotBlank();
        Arrays.stream(supportsConst.split(",")).forEach(supports::add);
        checkJson("constants.json", c);
    }

    @Test
    void analysisPromptMatchesWorkerYml() throws Exception {
        String yml = Files.readString(Path.of("src", "main", "resources", "application-worker.yml"), StandardCharsets.UTF_8);
        Map<?, ?> root = new Yaml().load(yml);
        Object template = ((Map<?, ?>) ((Map<?, ?>) root.get("netis-maker")).get("worker")).get("prompt-template");
        assertThat(template).isInstanceOf(String.class);
        checkText("analysis-prompt.txt", ((String) template).replace("\r\n", "\n"));
    }

    // ---------- API response shapes ----------

    @Test
    void claimAnalysisMatchesForAnalysis() throws Exception {
        Task t = Task.create("acme/widgets", "main", "위젯 목록 검색 추가",
                "목록 화면에 검색 상자를 추가합니다.\n두 번째 줄: 한글과 English 혼합", "requester-1", 3,
                List.of(new TaskMcpSpec("docs", "http://localhost:9/mcp", "http")), "sonnet", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setGitUrl(null);
        checkJson("claim-analysis.json", api.valueToTree(WorkerTaskResponse.forAnalysis(t)));
    }

    @Test
    void startupResponseMatchesDto() throws Exception {
        var dto = new WorkerStartupResponse(
                List.of(new WorkerStartupResponse.Recovered(3L, TaskStatus.IN_PROGRESS, TaskStatus.PENDING)),
                List.of(7L));
        checkJson("startup-response.json", api.valueToTree(dto));
        String content = Files.readString(DIR.resolve("startup-response.json"), StandardCharsets.UTF_8);
        assertThat(api.readValue(content, WorkerStartupResponse.class)).isEqualTo(dto);
    }

    // ---------- request bodies (what Rust sends) ----------

    @Test
    void requestBodiesAreReadableAndStable() throws Exception {
        var usage = new WorkerResultRequest.UsageReport(new BigDecimal("0.12"), 100L, 50L, 0L, 0L);
        var ok = new WorkerResultRequest("w-1", TaskStatus.COMPLETED,
                "## 1. 요구사항 요약\n내용", "[]", null, 1234L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, usage);
        var failed = new WorkerResultRequest("w-1", TaskStatus.FAILED,
                null, null, null, 500L, "분석 시간 초과",
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        var heartbeat = new WorkerHeartbeatRequest("w-1", "host-a", "0.1.0", true, "UP", List.of("docs"), 0,
                "env-token", OffsetDateTime.of(2026, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC), 0);
        var startup = new WorkerStartupRequest(List.of(3L, 7L));

        requestBody("result-analysis-ok.json", ok, WorkerResultRequest.class);
        requestBody("result-analysis-failed.json", failed, WorkerResultRequest.class);
        requestBody("heartbeat.json", heartbeat, WorkerHeartbeatRequest.class);
        requestBody("startup-request.json", startup, WorkerStartupRequest.class);
    }

    private <T> void requestBody(String file, T expected, Class<T> type) throws IOException {
        // 기대 DTO를 null 제외로 쓴 것이 파일이다. usage.empty는 isEmpty()가 만드는 속성이라 Rust가 보내지 않는다 — 뺀다.
        checkJson(file, withoutUsageEmpty(nonNull.valueToTree(expected)));

        String content = Files.readString(DIR.resolve(file), StandardCharsets.UTF_8);
        T read = api.readValue(content, type);
        assertThat(read).as(file + " 을 API 매퍼로 읽은 DTO").isEqualTo(expected);
        assertThat(api.readTree(nonNull.writeValueAsString(withoutUsageEmpty(nonNull.valueToTree(read))))).as(file + " 재직렬화")
                .isEqualTo(api.readTree(content));
    }

    private static JsonNode withoutUsageEmpty(JsonNode node) {
        if (node.has("usage") && node.get("usage").isObject()) ((ObjectNode) node.get("usage")).remove("empty");
        return node;
    }

    // ---------- parser / mask cases ----------

    private static final String S1 = "## 1. 요구사항 요약\n목록 화면에 검색 상자를 추가한다.\n\n";
    private static final String S2 = "## 2. 영향 범위\n- 수정 예상 파일: 목록 화면\n\n";
    private static final String S3 = "## 3. 접근 방법\n입력값으로 서버 쪽 조회를 거른다.\n\n";
    private static final String S4 = "## 4. Subtask 분해\n"
            + "- **검색 API 추가**: 조회 조건을 받는다\n"
            + "  - 대상 파일: `src/api/search.ts`, `src/api/index.ts`\n"
            + "  - 예상 LoC: 약 30줄\n"
            + "  - 위험도: M\n"
            + "  - 설명: 쿼리 매개변수를 추가한다\n"
            + "- **검색 상자 화면**: 입력 상자를 붙인다\n"
            + "  - 대상 파일: `src/ui/List.vue`\n"
            + "  - 예상 LoC: 약 20줄\n"
            + "  - 위험도: L\n"
            + "  - 설명: 입력 지연을 둔다\n\n";
    private static final String S5 = "## 5. 위험 요소 및 미해결 질문\n- 없음\n";
    private static final String NBSP = " ";
    private static final String IDEOGRAPHIC_SPACE = "　";

    @Test
    void parserCasesMatchJavaParser() throws Exception {
        String full = S1 + S2 + S3 + S4 + S5;
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("normal", full);
        inputs.put("no-section-4", S1 + S2 + S3 + S5);
        inputs.put("section-4-empty-body", S1 + S2 + S3 + "## 4. Subtask 분해\n본문이 비어 있다\n\n" + S5);
        inputs.put("no-section-5", S1 + S2 + S3 + S4);
        inputs.put("missing-section-2", S1 + S3 + S4 + S5);
        inputs.put("empty-input", "");
        inputs.put("duplicate-section-number",
                S1 + S2 + S3 + S4 + "## 4. Subtask 분해\n- **뒤쪽 항목**: 나중 것이 이긴다\n\n" + S5);
        inputs.put("low-korean-ratio", "## 1. Summary\nAdd a search box to the list screen.\n\n"
                + "## 2. Scope\n- files: list view\n\n## 3. Approach\nFilter on the server side.\n\n"
                + "## 4. Subtasks\n- **Search API**: accept a filter\n  - 위험도: L\n\n## 5. Risks\n- none\n");
        inputs.put("crlf-line-endings", full.replace("\n", "\r\n"));
        inputs.put("ideographic-space-after-hashes", full.replace("## 2.", "##" + IDEOGRAPHIC_SPACE + "2."));
        inputs.put("nbsp-after-hashes", full.replace("## 3.", "##" + NBSP + "3."));
        inputs.put("fullwidth-section-number", full.replace("## 1.", "## １."));
        inputs.put("nbsp-after-subtask-colon", full.replace("**검색 API 추가**: ", "**검색 API 추가**:" + NBSP));

        PromptResultParser parser = new PromptResultParser();
        ArrayNode cases = api.createArrayNode();
        inputs.forEach((name, input) -> {
            ObjectNode c = cases.addObject();
            c.put("name", name);
            c.put("input", input);
            try {
                PromptResultParser.ParseResult r = parser.parse(input);
                c.put("ok", true);
                c.put("subtasksJson", r.subtasksJson());
                ArrayNode w = c.putArray("warnings");
                r.warnings().forEach(w::add);
            } catch (PromptResultParser.ParseException e) {
                c.put("ok", false);
                c.put("error", e.getMessage());
            }
        });
        // 시험 입력이 의도한 분기를 지나가는지 최소한만 본다 (나머지 결과는 Java 파서가 기준)
        assertThat(cases.get(0).get("ok").asBoolean()).isTrue();
        assertThat(api.readTree(cases.get(0).get("subtasksJson").asText())).hasSize(2);
        assertThat(cases.get(4).get("ok").asBoolean()).isFalse();
        assertThat(cases.get(5).get("ok").asBoolean()).isFalse();
        checkJson("parser-cases.json", cases);
    }

    @Test
    void maskCasesMatchGitRemotes() throws Exception {
        List<String> inputs = new ArrayList<>(List.of(
                "https://oauth2:tok@host/x",
                "http://user:pw@h:8080/a",
                "https://host/plain/path.git",
                "clone https://oauth2:aaa@h1/x.git and http://u:bbb@h2/y.git done",
                "://user@host",
                "https://oauth2:tok@" + NBSP + "host/x",
                "https://u:pw" + NBSP + "x@h/x",
                "line one https://oauth2:tok@h/x\nline two no secret\nline three http://a:b@c/d\n"));
        ArrayNode cases = api.createArrayNode();
        for (String in : inputs) {
            ObjectNode c = cases.addObject();
            c.put("input", in);
            c.put("output", GitRemotes.mask(in));
        }
        assertThat(cases.get(0).get("output").asText()).isEqualTo("https://***@host/x");
        assertThat(cases.get(4).get("output").asText()).isEqualTo("://user@host");
        checkJson("mask-cases.json", cases);
    }

    // ---------- file helpers ----------

    private void checkText(String file, String expected) throws IOException {
        Path p = DIR.resolve(file);
        if (UPDATE) write(p, expected);
        assertThat(p).as(file + " 없음 — " + HINT).exists();
        String actual = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertThat(actual).as(file + " — " + HINT).isEqualTo(expected);
        assertThat(actual).as(file + " 에 CR이 있다 — LF만 써야 한다").doesNotContain("\r");
    }

    private void checkJson(String file, JsonNode expected) throws IOException {
        Path p = DIR.resolve(file);
        if (UPDATE) write(p, render(expected));
        assertThat(p).as(file + " 없음 — " + HINT).exists();
        String actual = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        // 문자열 안의 CR은 \r 이스케이프로만 있어야 한다 — 파일 자체는 LF
        assertThat(actual).as(file + " 에 CR이 있다 — LF만 써야 한다").doesNotContain("\r");
        // 숫자 노드 종류(Int/Long/Decimal)가 달라도 같은 JSON 값이면 같게 — 텍스트를 거쳐 다시 읽어 비교한다
        assertThat(api.readTree(actual)).as(file + " — " + HINT).isEqualTo(api.readTree(api.writeValueAsString(expected)));
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 들여쓰기 2칸·LF·끝 줄바꿈. 눈에 안 보이는 공백류(NBSP·전각 공백·전각 숫자)는 \\uXXXX로 적어 파일에서도 보이게 한다. */
    private String render(JsonNode node) throws IOException {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.AFTER)).withObjectIndenter(indenter).withArrayIndenter(indenter);
        String json = api.writer(printer).writeValueAsString(node);
        StringBuilder sb = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == ' ' || ch == '　' || (ch >= '０' && ch <= '９')) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.append('\n').toString();
    }
}
