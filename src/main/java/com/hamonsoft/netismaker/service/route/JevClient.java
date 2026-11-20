package com.hamonsoft.netismaker.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.config.QuestionRouteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TypeSafe Jev(System One) 호출 — 질문이 어느 카탈로그 레포를 가리키는지 묻는 유일한 곳(스펙 2026-11-20).
 * Jev 요청·응답의 모양은 이 클래스만 안다. 밖으로 나가는 것은 호출자가 이미 자른 질문 발췌와 레포 별칭·추천 키워드뿐이다.
 * 질문 본문과 API 키는 로그·예외 메시지에 남기지 않는다 — 로그는 상태·소요 시간·HTTP 코드만.
 * 실패(키 없음·시간 초과·HTTP 오류·응답 해석 실패)는 전부 Optional.empty()로 돌려주고 예외를 던지지 않는다.
 */
@Slf4j
@Component
@Profile("api")
public class JevClient {

    /** 추천 후보 하나. hint는 추천 키워드(없으면 null). */
    public record RepoOption(long id, String alias, String hint) {}

    /** Jev가 돌려준 판단. repoProbabilities는 보낸 후보 중 응답에 있던 것만. */
    public record JevAnswers(String model, Map<Long, Double> repoProbabilities,
                             double mentionsTarget, double targetListed, double needsDb) {}

    // 스파이크로 확정한 지시문(docs/superpowers/plans/2026-11-20-question-repo-route-spike-result.md §4) — 그대로.
    static final String INSTR_REPO = "Which repository is the user's `question` about? "
            + "Pick the repository whose site, customer, product or version the question refers to.";
    static final String INSTR_MENTIONS = "Does `question` refer by name to a specific site, customer, product, or product version "
            + "(for example a customer name or a version number)?";
    static final String INSTR_LISTED = "Is the site, customer, product, or version that `question` refers to one of the entries in "
            + "`repositories`? Answer no if the question names something that is not in `repositories`, or names nothing specific.";
    static final String INSTR_NEEDS_DB = "Would answering `question` require looking at an actual database "
            + "(table structure, columns, stored rows, or query results) rather than reading source code only?";

    /** 남은 시간이 이보다 짧으면 429·529 재시도는 쓸모가 없어 하지 않는다. */
    private static final long MIN_RETRY_MS = 200;
    private static final long RETRY_BACKOFF_MS = 100;

    private final QuestionRouteProperties props;
    private final ObjectMapper om;
    private final HttpClient http;

    public JevClient(QuestionRouteProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(1, props.timeoutMs()))).build();
    }

    /** 켜져 있고 API 키가 비어 있지 않을 때만 true. */
    public boolean available() {
        return props.enabled() && props.apiKey() != null && !props.apiKey().isBlank();
    }

    public Optional<JevAnswers> ask(String excerpt, List<RepoOption> repos) {
        if (!available() || repos == null || repos.isEmpty()) return Optional.empty();
        long start = System.nanoTime();
        long budgetMs = Math.max(1, props.timeoutMs());
        String body;
        try {
            body = om.writeValueAsString(buildBody(excerpt, repos));
        } catch (IOException e) {
            log.warn("질문 레포 추천 Jev 요청 만들기 실패 result=parse");
            return Optional.empty();
        }
        Set<Long> sent = new HashSet<>();
        for (RepoOption r : repos) sent.add(r.id());

        int attempts = 0;
        while (true) {
            attempts++;
            long remaining = budgetMs - elapsedMs(start);
            if (remaining <= 0) return fail("timeout", -1, start);
            HttpResponse<String> res;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(props.endpoint()))
                        .header("Authorization", "Bearer " + props.apiKey().strip())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(remaining))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                return fail("timeout", -1, start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail("timeout", -1, start);
            } catch (IOException | IllegalArgumentException e) {
                // 예외 메시지에 URL·헤더가 섞일 수 있어 종류만 남긴다.
                log.warn("질문 레포 추천 Jev I/O 오류 type={}", e.getClass().getSimpleName());
                return fail("http", -1, start);
            }
            int status = res.statusCode();
            if ((status == 429 || status == 529) && attempts == 1) {
                if (budgetMs - elapsedMs(start) - RETRY_BACKOFF_MS >= MIN_RETRY_MS) {
                    sleepQuietly(RETRY_BACKOFF_MS);
                    continue;
                }
                return fail("http", status, start);
            }
            if (status != 200) return fail("http", status, start);

            Optional<JevAnswers> parsed = parse(res.body(), sent);
            if (parsed.isEmpty()) return fail("parse", status, start);
            log.info("질문 레포 추천 Jev 호출 result=ok status={} ms={}", status, elapsedMs(start));
            return parsed;
        }
    }

    private ObjectNode buildBody(String excerpt, List<RepoOption> repos) {
        ObjectNode root = om.createObjectNode();
        root.put("model", props.model());

        ObjectNode state = root.putObject("state");
        state.put("question", excerpt == null ? "" : excerpt);
        var repositories = state.putArray("repositories");
        for (RepoOption r : repos) {
            ObjectNode n = repositories.addObject();
            n.put("key", key(r.id()));
            n.put("name", r.alias());
            if (hasHint(r)) n.put("hint", r.hint());
        }

        // 선택지 순서가 판단에 쏠림을 주지 않도록 요청마다 섞는다.
        List<RepoOption> shuffled = new ArrayList<>(repos);
        Collections.shuffle(shuffled);
        ObjectNode questions = root.putObject("questions");
        ObjectNode repo = questions.putObject("repo");
        repo.put("type", "choice");
        repo.put("instructions", INSTR_REPO);
        ObjectNode criteria = repo.putObject("criteria");
        for (RepoOption r : shuffled) {
            criteria.put(key(r.id()), hasHint(r) ? r.alias() + " — " + r.hint() : r.alias());
        }
        noul(questions, "mentions_target", INSTR_MENTIONS);
        noul(questions, "target_listed", INSTR_LISTED);
        noul(questions, "needs_db", INSTR_NEEDS_DB);
        return root;
    }

    private static void noul(ObjectNode questions, String name, String instructions) {
        ObjectNode q = questions.putObject(name);
        q.put("type", "noul");
        q.put("instructions", instructions);
    }

    private Optional<JevAnswers> parse(String raw, Set<Long> sent) {
        JsonNode root;
        try {
            root = om.readTree(raw);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        JsonNode answers = root.path("answers");

        JsonNode probs = answers.path("repo").path("probabilities");
        if (!probs.isObject()) return Optional.empty();
        Map<Long, Double> out = new LinkedHashMap<>();
        probs.fields().forEachRemaining(e -> {
            Long id = parseKey(e.getKey());
            if (id != null && sent.contains(id) && e.getValue().isNumber()) out.put(id, e.getValue().asDouble());
        });
        if (out.isEmpty()) return Optional.empty();

        Double mentions = noulOf(answers, "mentions_target");
        Double listed = noulOf(answers, "target_listed");
        Double needsDb = noulOf(answers, "needs_db");
        if (mentions == null || listed == null || needsDb == null) return Optional.empty();

        JsonNode m = root.path("model");
        String model = m.isTextual() && !m.asText().isBlank() ? m.asText() : props.model();
        return Optional.of(new JevAnswers(model, Map.copyOf(out), mentions, listed, needsDb));
    }

    private static Double noulOf(JsonNode answers, String name) {
        JsonNode v = answers.path(name).path("noul");
        return v.isNumber() ? v.asDouble() : null;
    }

    private static String key(long id) {
        return "r" + id;
    }

    /** "r12" → 12, 그 밖의 모양은 null. */
    private static Long parseKey(String k) {
        if (k == null || k.length() < 2 || k.charAt(0) != 'r') return null;
        try {
            return Long.parseLong(k.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasHint(RepoOption r) {
        return r.hint() != null && !r.hint().isBlank();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<JevAnswers> fail(String result, int status, long start) {
        log.warn("질문 레포 추천 Jev 호출 result={} status={} ms={}", result, status, elapsedMs(start));
        return Optional.empty();
    }
}
