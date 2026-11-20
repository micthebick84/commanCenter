package com.hamonsoft.netismaker.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.config.QuestionRouteProperties;
import com.hamonsoft.netismaker.service.route.JevClient.JevAnswers;
import com.hamonsoft.netismaker.service.route.JevClient.RepoOption;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 TypeSafe API는 부르지 않는다 — 127.0.0.1의 가짜 서버만 쓴다. */
class JevClientTest {

    private static final String KEY = "tsk-test-key";
    private static final ObjectMapper OM = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auths = new CopyOnWriteArrayList<>();
    /** 호출 순번(0부터)마다 응답을 돌려주는 규칙. 기본은 항상 정상 응답. */
    private volatile java.util.function.IntFunction<Reply> script = n -> new Reply(200, ok("{\"r3\":0.9,\"r8\":0.1}"));
    private volatile long delayMs = 0;

    record Reply(int status, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = calls.getAndIncrement();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auths.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            Reply r = script.apply(n);
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            try {
                ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) ex.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 클라이언트가 먼저 끊은 경우(시간 초과 시험)
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
    }

    private JevClient client(boolean enabled, String key, int timeoutMs) {
        QuestionRouteProperties p = new QuestionRouteProperties(enabled, key, "jev-1.14.0", endpoint(), timeoutMs, 500,
                new QuestionRouteProperties.Threshold(0.7, 0.7, 0.3, 0.5, 0.6));
        return new JevClient(p, OM);
    }

    private JevClient client() {
        return client(true, KEY, 3000);
    }

    private static String ok(String probabilities) {
        return "{\"model\":\"jev-1.14.0\",\"answers\":{"
                + "\"repo\":{\"type\":\"choice\",\"choice\":\"r3\",\"confidence\":0.9,\"probabilities\":" + probabilities + "},"
                + "\"mentions_target\":{\"type\":\"noul\",\"noul\":0.93},"
                + "\"target_listed\":{\"type\":\"noul\",\"noul\":0.88},"
                + "\"needs_db\":{\"type\":\"noul\",\"noul\":0.21}},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    private static List<RepoOption> repos() {
        return List.of(new RepoOption(3, "강원 대시보드", "강원 dash 현장"), new RepoOption(8, "Netis6.8", null));
    }

    @Test
    void 정상_응답에서_확률과_noul을_읽는다() {
        Optional<JevAnswers> r = client().ask("강원 대시보드 질문", repos());

        assertThat(r).isPresent();
        JevAnswers a = r.get();
        assertThat(a.model()).isEqualTo("jev-1.14.0");
        assertThat(a.repoProbabilities()).containsOnly(
                org.assertj.core.api.Assertions.entry(3L, 0.9), org.assertj.core.api.Assertions.entry(8L, 0.1));
        assertThat(a.mentionsTarget()).isEqualTo(0.93);
        assertThat(a.targetListed()).isEqualTo(0.88);
        assertThat(a.needsDb()).isEqualTo(0.21);
    }

    @Test
    void 요청에_Bearer_키와_모델_상태_질문_4개가_실린다() throws Exception {
        client().ask("강원 대시보드 질문", repos());

        assertThat(auths).containsExactly("Bearer " + KEY);
        JsonNode body = OM.readTree(bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("jev-1.14.0");
        assertThat(body.path("state").path("question").asText()).isEqualTo("강원 대시보드 질문");
        JsonNode reps = body.path("state").path("repositories");
        assertThat(reps).hasSize(2);
        assertThat(reps.get(0).path("key").asText()).isEqualTo("r3");
        assertThat(reps.get(0).path("name").asText()).isEqualTo("강원 대시보드");
        assertThat(reps.get(0).path("hint").asText()).isEqualTo("강원 dash 현장");
        assertThat(reps.get(1).has("hint")).isFalse();
        JsonNode qs = body.path("questions");
        Set<String> names = new HashSet<>();
        qs.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("repo", "mentions_target", "target_listed", "needs_db");
        assertThat(qs.path("repo").path("type").asText()).isEqualTo("choice");
        assertThat(qs.path("repo").path("instructions").asText()).startsWith("Which repository is the user's `question` about?");
        assertThat(qs.path("mentions_target").path("type").asText()).isEqualTo("noul");
        assertThat(qs.path("target_listed").path("instructions").asText()).startsWith("Is the site, customer, product, or version");
        assertThat(qs.path("needs_db").path("instructions").asText()).startsWith("Would answering `question` require");
    }

    @Test
    void 키워드가_없으면_기준은_별칭만이다() throws Exception {
        client().ask("질문", repos());

        JsonNode criteria = OM.readTree(bodies.get(0)).path("questions").path("repo").path("criteria");
        assertThat(criteria.path("r8").asText()).isEqualTo("Netis6.8");
        assertThat(criteria.path("r3").asText()).isEqualTo("강원 대시보드 — 강원 dash 현장");
    }

    @Test
    void 요청마다_선택지_순서가_달라질_수_있다() throws Exception {
        List<RepoOption> five = List.of(new RepoOption(1, "a", null), new RepoOption(2, "b", null), new RepoOption(3, "c", null),
                new RepoOption(4, "d", null), new RepoOption(5, "e", null));
        script = n -> new Reply(200, ok("{\"r1\":1.0}"));
        JevClient c = client();
        for (int i = 0; i < 20; i++) c.ask("질문", five);

        Set<List<String>> orders = new HashSet<>();
        for (String b : bodies) {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = OM.readTree(b).path("questions").path("repo").path("criteria").fieldNames();
            it.forEachRemaining(keys::add);
            orders.add(keys);
        }
        assertThat(orders.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 보낸_적_없는_키는_버리고_전부_모르는_키면_빈_값() {
        script = n -> new Reply(200, ok("{\"r999\":1.0}"));
        assertThat(client().ask("질문", repos())).isEmpty();

        script = n -> new Reply(200, ok("{\"r999\":0.5,\"r3\":0.5}"));
        Optional<JevAnswers> r = client().ask("질문", repos());
        assertThat(r).isPresent();
        assertThat(r.get().repoProbabilities()).containsOnlyKeys(3L);
    }

    @Test
    void 보낸_키가_응답에_빠져_있어도_하나라도_있으면_값이다() {
        script = n -> new Reply(200, ok("{\"r8\":1.0}"));
        Optional<JevAnswers> r = client().ask("질문", repos());
        assertThat(r).isPresent();
        assertThat(r.get().repoProbabilities()).containsOnlyKeys(8L);
    }

    @Test
    void 숫자_4xx_5xx_깨진_JSON_빠진_답은_빈_값() {
        for (int status : new int[] {401, 500}) {
            script = n -> new Reply(status, "{\"error\":\"x\"}");
            assertThat(client().ask("질문", repos())).as("status " + status).isEmpty();
        }
        script = n -> new Reply(200, "not json");
        assertThat(client().ask("질문", repos())).isEmpty();

        script = n -> new Reply(200, ok("{\"r3\":1.0}").replace("\"needs_db\":{\"type\":\"noul\",\"noul\":0.21}", "\"needs_db\":{\"type\":\"noul\"}"));
        assertThat(client().ask("질문", repos())).isEmpty();

        script = n -> new Reply(200, ok("{\"r3\":1.0}").replace("\"noul\":0.93", "\"noul\":\"high\""));
        assertThat(client().ask("질문", repos())).isEmpty();
    }

    @Test
    void 상한을_넘기면_빈_값() {
        delayMs = 1500;
        long t0 = System.nanoTime();
        assertThat(client(true, KEY, 300).ask("질문", repos())).isEmpty();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1200);
    }

    @Test
    void 응답_429는_한_번만_다시_시도한다() {
        script = n -> n == 0 ? new Reply(429, "{}") : new Reply(200, ok("{\"r3\":1.0}"));
        assertThat(client().ask("질문", repos())).isPresent();
        assertThat(calls.get()).isEqualTo(2);

        calls.set(0);
        script = n -> new Reply(429, "{}");
        assertThat(client().ask("질문", repos())).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 응답_529도_한_번만_다시_시도한다() {
        script = n -> n == 0 ? new Reply(529, "{}") : new Reply(200, ok("{\"r3\":1.0}"));
        assertThat(client().ask("질문", repos())).isPresent();
        assertThat(calls.get()).isEqualTo(2);

        calls.set(0);
        script = n -> new Reply(529, "{}");
        assertThat(client().ask("질문", repos())).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 남은_시간이_200ms_미만이면_다시_시도하지_않는다() {
        script = n -> new Reply(429, "{}");
        assertThat(client(true, KEY, 150).ask("질문", repos())).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 키가_없거나_꺼져_있으면_부르지_않는다() {
        JevClient noKey = client(true, "  ", 3000);
        JevClient off = client(false, KEY, 3000);

        assertThat(noKey.available()).isFalse();
        assertThat(off.available()).isFalse();
        assertThat(client().available()).isTrue();
        assertThat(noKey.ask("질문", repos())).isEmpty();
        assertThat(off.ask("질문", repos())).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void 후보가_없으면_부르지_않는다() {
        assertThat(client().ask("질문", List.of())).isEmpty();
        assertThat(calls.get()).isZero();
    }
}
