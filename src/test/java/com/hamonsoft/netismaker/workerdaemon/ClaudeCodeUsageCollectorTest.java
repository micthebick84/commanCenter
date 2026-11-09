package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageCollector.Result;
import com.hamonsoft.netismaker.workerdaemon.UsageCollectorState.FileMark;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Claude Code 사용량 수집 회차(스펙 2026-11-03 §4). 기록은 모두 @TempDir 가짜 홈의 `.claude/projects/…`에
 * 손으로 쓴 jsonl이다 — 실제 ~/.claude는 읽지 않는다. API는 Mockito WorkerHttpClient(응답 (n, n, 0, 0)).
 */
class ClaudeCodeUsageCollectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TS = "2026-11-01T00:00:00Z";

    @TempDir
    Path home;

    private WorkerProperties props;
    private WorkerHttpClient http;
    private Path projects;
    private Path worktreeRoot;

    @BeforeEach
    void setUp() {
        projects = home.resolve(".claude").resolve("projects");
        worktreeRoot = home.resolve("wt");
        props = mock(WorkerProperties.class);
        when(props.worktreeRoot()).thenReturn(worktreeRoot.toString());
        when(props.reposDir()).thenReturn(home.resolve("netis-maker").resolve("repos").toString());
        http = mock(WorkerHttpClient.class);
        answerOk();
    }

    private void answerOk() {
        when(http.postClaudeCodeUsage(any())).thenAnswer(inv -> {
            int n = inv.<ClaudeCodeUsageBatchRequest>getArgument(0).events().size();
            return new ClaudeCodeUsageBatchResponse(n, n, 0, 0);
        });
    }

    private static UsageCollectProperties cfg(boolean includeDirect, String machine, int maxMb) {
        return new UsageCollectProperties(true, 300, includeDirect, List.of(), "2026-10-01", machine, maxMb);
    }

    private ClaudeCodeUsageCollector collector() {
        return collector(cfg(false, "pc-test", 256), Map.of());
    }

    private ClaudeCodeUsageCollector collector(UsageCollectProperties cfg, Map<String, String> env) {
        return new ClaudeCodeUsageCollector(props, cfg, http, home, env);
    }

    /** 이번 회차까지 보낸 묶음 전부(부를 때마다 호출 기록을 비운다). */
    private List<ClaudeCodeUsageBatchRequest> sent() {
        ArgumentCaptor<ClaudeCodeUsageBatchRequest> c = ArgumentCaptor.forClass(ClaudeCodeUsageBatchRequest.class);
        verify(http, atLeast(0)).postClaudeCodeUsage(c.capture());
        clearInvocations(http);
        return c.getAllValues();
    }

    private static List<ClaudeCodeUsageEvent> events(Collection<ClaudeCodeUsageBatchRequest> reqs) {
        return reqs.stream().flatMap(r -> r.events().stream()).toList();
    }

    private static Set<String> keys(Collection<ClaudeCodeUsageBatchRequest> reqs) {
        return events(reqs).stream().map(ClaudeCodeUsageEvent::eventKey).collect(Collectors.toSet());
    }

    private Path stateFile() {
        return home.resolve("netis-maker").resolve(ClaudeCodeUsageCollector.STATE_FILE);
    }

    // ---- 기록 줄 ----

    private String cwd() {
        return worktreeRoot.resolve("task-1").toString();
    }

    private String line(String id, String req, long output) {
        return line(id, req, TS, cwd(), output);
    }

    private static String line(String id, String req, String ts, String cwd, long output) {
        return json(node(id, req, ts, cwd, output));
    }

    private static ObjectNode node(String id, String req, String ts, String cwd, long output) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "assistant");
        root.put("timestamp", ts);
        root.put("cwd", cwd);
        root.put("sessionId", "s-1");
        if (req != null) root.put("requestId", req);
        ObjectNode msg = root.putObject("message");
        msg.put("id", id);
        msg.put("model", "claude-test-1");
        msg.putNull("stop_reason");
        ArrayNode content = msg.putArray("content");
        content.addObject().put("type", "text").put("text", "SECRET-CONTENT");
        ObjectNode tool = content.addObject().put("type", "tool_use");
        tool.putObject("input").put("command", "SECRET-TOOL-INPUT");
        ObjectNode usage = msg.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", output);
        usage.put("cache_read_input_tokens", 5);
        return root;
    }

    private static String json(JsonNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 개행까지 정확히 1024바이트인 줄. */
    private String kiloLine(String id) {
        ObjectNode n = node(id, "req", TS, cwd(), 1);
        n.put("pad", "");
        int bytes = json(n).getBytes(StandardCharsets.UTF_8).length;
        n.put("pad", "x".repeat(1023 - bytes));
        String s = json(n);
        assertThat(s.getBytes(StandardCharsets.UTF_8)).hasSize(1023);
        return s;
    }

    private Path write(String rel, String... lines) throws IOException {
        Path f = projects.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, lines.length == 0 ? "" : String.join("\n", lines) + "\n");
        return f;
    }

    private static void append(Path f, String text) throws IOException {
        Files.writeString(f, text, StandardOpenOption.APPEND);
    }

    // ---- 시험 ----

    @Test
    void 같은_응답_여러_줄은_칸별_최댓값_한_건() throws Exception {
        write("p/s.jsonl", line("msg_1", "req_1", 12), line("msg_1", "req_1", 250));

        Result r = collector().runOnce();

        List<ClaudeCodeUsageEvent> ev = events(sent());
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).eventKey()).isEqualTo("msg_1:req_1");
        assertThat(ev.get(0).outputTokens()).isEqualTo(250);
        assertThat(ev.get(0).inputTokens()).isEqualTo(10);
        assertThat(ev.get(0).category()).isEqualTo("SYMPHONY");
        assertThat(r.sent()).isTrue();
        assertThat(r.newLines()).isEqualTo(2);
        assertThat(r.symphony()).isEqualTo(2);
        assertThat(r.inserted()).isEqualTo(1);
    }

    @Test
    void subagents와_workflows까지_읽는다() throws Exception {
        write("p/s.jsonl", line("m_main", "r", 1));
        write("p/s/subagents/a.jsonl", line("m_sub", "r", 1));
        write("p/s/subagents/workflows/wf_1/b.jsonl", line("m_wf", "r", 1));
        write("p/s/notes.txt", line("m_txt", "r", 1));

        Result r = collector().runOnce();

        assertThat(keys(sent())).containsExactlyInAnyOrder("m_main:r", "m_sub:r", "m_wf:r");
        assertThat(r.files()).isEqualTo(3);
    }

    @Test
    void 옮겨진_사본은_키로_한_건() throws Exception {
        String same = line("msg_1", "req_1", 40);
        write("p1/s.jsonl", same);
        write("p2/s.jsonl", same);

        collector().runOnce();

        assertThat(events(sent())).hasSize(1);
    }

    @Test
    void 부분_줄은_다음_회차로() throws Exception {
        Path f = write("p/s.jsonl", line("k1", "r", 1));
        append(f, line("k2", "r", 1));   // 개행 없음 — 아직 쓰이는 중
        ClaudeCodeUsageCollector c = collector();

        c.runOnce();
        assertThat(keys(sent())).containsExactly("k1:r");

        append(f, "\n");
        c.runOnce();
        assertThat(keys(sent())).containsExactly("k2:r");
    }

    @Test
    void 덧붙은_줄만_새_줄이_없어도_빈_묶음() throws Exception {
        Path f = write("p/s.jsonl", line("k1", "r", 1));
        ClaudeCodeUsageCollector c = collector();

        c.runOnce();
        assertThat(keys(sent())).containsExactly("k1:r");

        Result second = c.runOnce();
        List<ClaudeCodeUsageBatchRequest> reqs = sent();
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).events()).isEmpty();
        assertThat(reqs.get(0).unreadableLines()).isZero();
        assertThat(reqs.get(0).machine()).isEqualTo("pc-test");
        assertThat(second.sent()).isTrue();
        assertThat(second.newLines()).isZero();

        append(f, line("k2", "r", 1) + "\n");
        c.runOnce();
        assertThat(keys(sent())).containsExactly("k2:r");
    }

    @Test
    void 파일이_줄면_처음부터() throws Exception {
        Path f = write("p/s.jsonl", line("k1", "r", 1), line("k2", "r", 1));
        ClaudeCodeUsageCollector c = collector();
        c.runOnce();
        sent();

        // 상태가 기억하는 크기·위치가 실제 파일보다 크다(잘렸다가 다시 쓰인 것처럼)
        UsageCollectorState state = UsageCollectorState.load(stateFile());
        FileMark prev = state.mark(f.toString());
        assertThat(prev).isNotNull();
        long bigger = Files.size(f) + 1000;
        state.put(f.toString(), new FileMark(bigger, bigger, prev.mtimeMillis()));
        state.saveAtomically(stateFile());

        c.runOnce();

        assertThat(keys(sent())).containsExactlyInAnyOrder("k1:r", "k2:r");
        assertThat(UsageCollectorState.load(stateFile()).mark(f.toString()).offset()).isEqualTo(Files.size(f));
    }

    @Test
    void 구분과_include_direct() throws Exception {
        String closeDir = (home.toString() + "/NETIS-MAKER/close/task-3").replace('\\', '/');
        if (home.toString().contains("/")) closeDir = closeDir.replace('/', '\\');
        Path f = write("p/s.jsonl",
                line("k_wt", "r", TS, worktreeRoot.resolve("task-9").toString(), 1),
                line("k_close", "r", TS, closeDir, 1),
                line("k_direct", "r", TS, home.resolve("my-project").toString(), 1));
        ClaudeCodeUsageCollector c = collector(cfg(false, "pc-test", 256), Map.of());

        Result r = c.runOnce();
        List<ClaudeCodeUsageEvent> ev = events(sent());
        assertThat(ev).extracting(ClaudeCodeUsageEvent::eventKey).containsExactlyInAnyOrder("k_wt:r", "k_close:r");
        assertThat(ev).extracting(ClaudeCodeUsageEvent::category).containsOnly("SYMPHONY");
        assertThat(r.symphony()).isEqualTo(2);
        assertThat(r.direct()).isZero();
        assertThat(r.directDropped()).isEqualTo(1);

        // 위치는 DIRECT 줄 너머로 옮겨졌다 — 다음 회차에도 보내지 않는다
        c.runOnce();
        assertThat(events(sent())).isEmpty();
        assertThat(UsageCollectorState.load(stateFile()).mark(f.toString()).offset()).isEqualTo(Files.size(f));

        // include-direct=true면 DIRECT로 보낸다(상태를 지우고 처음부터)
        Files.delete(stateFile());
        Result withDirect = collector(cfg(true, "pc-test", 256), Map.of()).runOnce();
        List<ClaudeCodeUsageEvent> all = events(sent());
        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(e -> e.eventKey().equals("k_direct:r"))
                .extracting(ClaudeCodeUsageEvent::category).containsExactly("DIRECT");
        assertThat(withDirect.direct()).isEqualTo(1);
        assertThat(withDirect.directDropped()).isZero();
    }

    @Test
    void count_from_이전은_보내지_않는다() throws Exception {
        write("p/s.jsonl",
                line("k_before", "r", "2026-09-30T14:59:59Z", cwd(), 1),
                line("k_from", "r", "2026-09-30T15:00:00Z", cwd(), 1));

        Result r = collector().runOnce();

        assertThat(keys(sent())).containsExactly("k_from:r");
        assertThat(r.beforeCountFrom()).isEqualTo(1);
    }

    @Test
    void 천_건씩_나눈다() throws Exception {
        write("p/s.jsonl", IntStream.range(0, 2500).mapToObj(i -> line("m" + i, "r", 1)).toArray(String[]::new));
        append(projects.resolve("p/s.jsonl"), "{깨진 줄\n");

        Result r = collector().runOnce();

        List<ClaudeCodeUsageBatchRequest> reqs = sent();
        assertThat(reqs).extracting(q -> q.events().size()).containsExactly(1000, 1000, 500);
        assertThat(reqs).extracting(ClaudeCodeUsageBatchRequest::unreadableLines).containsOnly(1);
        assertThat(reqs).extracting(ClaudeCodeUsageBatchRequest::machine).containsOnly("pc-test");
        assertThat(keys(reqs)).hasSize(2500);
        assertThat(r.unreadable()).isEqualTo(1);
        assertThat(r.inserted()).isEqualTo(2500);
    }

    @Test
    void 보내기가_실패하면_상태를_저장하지_않는다() throws Exception {
        Path f = write("p/s.jsonl", line("first", "r", 1));
        ClaudeCodeUsageCollector c = collector();
        c.runOnce();
        sent();
        byte[] before = Files.readAllBytes(stateFile());

        append(f, IntStream.range(0, 1500).mapToObj(i -> line("m" + i, "r", 1) + "\n").collect(Collectors.joining()));
        reset(http);
        when(http.postClaudeCodeUsage(any()))
                .thenReturn(new ClaudeCodeUsageBatchResponse(1000, 1000, 0, 0))
                .thenThrow(new ResourceAccessException("연결 거부"));

        Result failed = c.runOnce();
        assertThat(failed.sent()).isFalse();
        assertThat(sent()).hasSize(2);
        assertThat(Files.readAllBytes(stateFile())).isEqualTo(before);

        reset(http);
        answerOk();
        Result retried = c.runOnce();
        assertThat(retried.sent()).isTrue();
        Set<String> again = keys(sent());
        assertThat(again).hasSize(1500).contains("m0:r", "m1499:r").doesNotContain("first:r");
    }

    @Test
    void 구_API_404도_다음_회차에_다시() throws Exception {
        write("p/s.jsonl", line("k1", "r", 1));
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        reset(http);
        when(http.postClaudeCodeUsage(any())).thenThrow(notFound);
        ClaudeCodeUsageCollector c = collector();

        Result r = c.runOnce();
        assertThat(r.sent()).isFalse();
        assertThat(ClaudeCodeUsageCollector.failureReason(notFound)).isEqualTo("404(구버전 API)");
        assertThat(Files.exists(stateFile())).isFalse();

        reset(http);
        answerOk();
        assertThat(c.runOnce().sent()).isTrue();
        assertThat(keys(sent())).containsExactly("k1:r");
    }

    @Test
    void 내용_도구입력_cwd는_보내지_않는다() throws Exception {
        String secretCwd = worktreeRoot.resolve("SECRET-CWD-FOLDER").toString();
        write("p/s.jsonl", line("k1", "r", TS, secretCwd, 1));

        collector().runOnce();

        List<ClaudeCodeUsageBatchRequest> reqs = sent();
        assertThat(events(reqs)).hasSize(1);
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(reqs);
        assertThat(body).contains("k1:r", "claude-test-1");   // 직렬화가 실제로 이벤트를 담았다
        assertThat(body).doesNotContain("SECRET-CONTENT", "SECRET-TOOL-INPUT", "SECRET-CWD-FOLDER");
        assertThat(Files.readString(projects.resolve("p/s.jsonl")))
                .contains("SECRET-CONTENT", "SECRET-TOOL-INPUT", "SECRET-CWD-FOLDER");   // 원본에는 있었다
    }

    @Test
    void 다른_워커가_락을_쥐면_건너뛴다() throws Exception {
        write("p/s.jsonl", line("k1", "r", 1));
        Path base = Files.createDirectories(home.resolve("netis-maker"));

        Result r;
        try (FileChannel ch = FileChannel.open(base.resolve(ClaudeCodeUsageCollector.LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            r = collector().runOnce();
        }

        assertThat(r.skipped()).isTrue();
        verify(http, never()).postClaudeCodeUsage(any());
        assertThat(Files.exists(stateFile())).isFalse();
    }

    /** Review Focus 4 · Ruling F5: 걷기 목록에 있었지만 지워진 파일은 빼고 계속하고, 이전 표시도 지운다. */
    @Test
    void 걷는_도중_지워진_파일은_빼고_계속() throws Exception {
        Path a = write("p/a.jsonl", line("a1", "r", 1));
        Path b = write("p/b.jsonl", line("b1", "r", 1));
        Path cFile = write("p/c.jsonl", line("c1", "r", 1));
        ClaudeCodeUsageCollector c = spy(collector());
        c.runOnce();
        sent();
        assertThat(UsageCollectorState.load(stateFile()).mark(b.toString())).isNotNull();

        // 걷기는 b를 보았는데 그 뒤 Claude Code의 30일 정리가 지웠다
        Files.delete(b);
        append(a, line("a2", "r", 1) + "\n");
        append(cFile, line("c2", "r", 1) + "\n");
        doReturn(List.of(a, b, cFile)).when(c).listJsonl(any());

        Result r = c.runOnce();

        assertThat(r.sent()).isTrue();
        assertThat(keys(sent())).containsExactlyInAnyOrder("a2:r", "c2:r");
        UsageCollectorState state = UsageCollectorState.load(stateFile());
        assertThat(state.fresh()).isFalse();
        assertThat(state.mark(b.toString())).isNull();
        assertThat(state.mark(a.toString())).isNotNull();
        assertThat(state.mark(cFile.toString())).isNotNull();
        assertThat(r.files()).isEqualTo(2);
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 못_여는_폴더는_건너뛰고_계속() throws Exception {
        write("ok/s.jsonl", line("ok1", "r", 1));
        write("locked/s.jsonl", line("locked1", "r", 1));
        Path locked = projects.resolve("locked");
        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assumeFalse(Files.isReadable(locked), "root로 돌면 권한 000도 읽혀 시험이 무의미하다");
            ClaudeCodeUsageCollector c = collector();

            assertThatCode(c::runOnce).doesNotThrowAnyException();

            assertThat(keys(sent())).containsExactly("ok1:r");
            assertThat(Files.exists(stateFile())).isTrue();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Ruling F6(OS 무관): 걷기 뒤 같은 이름이 폴더로 바뀌어 열기·읽기가 NoSuchFile이 아닌 IOException이 되면
     * 그 경로만 건너뛰고 이전 표시를 그대로 둔 채 나머지를 보내고 상태를 저장한다.
     */
    @Test
    void 읽기_오류가_난_파일은_표시를_둔_채_건너뛴다() throws Exception {
        Path a = write("p/a.jsonl", line("a1", "r", 1));
        Path b = write("p/b.jsonl", line("b1", "r", 1));
        ClaudeCodeUsageCollector c = spy(collector());
        c.runOnce();
        sent();
        FileMark before = UsageCollectorState.load(stateFile()).mark(b.toString());
        assertThat(before).isNotNull();

        Files.delete(b);
        Files.createDirectory(b);   // FileChannel로 읽으면 IOException(Windows는 AccessDenied, Linux는 Is a directory)
        append(a, line("a2", "r", 1) + "\n");
        doReturn(List.of(a, b)).when(c).listJsonl(any());

        Result r = c.runOnce();

        assertThat(r.sent()).isTrue();
        assertThat(keys(sent())).containsExactly("a2:r");
        UsageCollectorState state = UsageCollectorState.load(stateFile());
        assertThat(state.mark(b.toString())).isEqualTo(before);
        assertThat(state.mark(a.toString()).offset()).isEqualTo(Files.size(a));
        assertThat(r.files()).isEqualTo(2);
    }

    /** Ruling F6: NoSuchFile이 아닌 읽기 오류는 그 파일만 건너뛰고 이전 표시를 둔 채 회차를 계속한다. */
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 못_읽는_파일은_건너뛰고_계속() throws Exception {
        Path ok = write("p/a.jsonl", line("ok1", "r", 1));
        Path bad = write("p/b.jsonl", line("bad1", "r", 1));
        Files.setPosixFilePermissions(bad, Set.of());
        try {
            assumeFalse(Files.isReadable(bad), "root로 돌면 권한 000도 읽혀 시험이 무의미하다");

            Result r = collector().runOnce();

            assertThat(r.sent()).isTrue();
            assertThat(keys(sent())).containsExactly("ok1:r");
            UsageCollectorState state = UsageCollectorState.load(stateFile());
            assertThat(state.mark(ok.toString())).isNotNull();
            assertThat(state.mark(bad.toString())).isNull();
        } finally {
            Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void 상태_파일이_깨졌으면_처음부터() throws Exception {
        write("p/s.jsonl", line("k1", "r", 1), line("k2", "r", 1));
        ClaudeCodeUsageCollector c = collector();
        c.runOnce();
        sent();

        Files.writeString(stateFile(), "{not json");
        c.runOnce();

        assertThat(keys(sent())).containsExactlyInAnyOrder("k1:r", "k2:r");
        JsonNode saved = JSON.readTree(Files.readAllBytes(stateFile()));
        assertThat(saved.path("version").asInt()).isEqualTo(UsageCollectorState.VERSION);
        assertThat(saved.path("files").size()).isEqualTo(1);
    }

    @Test
    void 상한에_닿으면_다음_회차에_이어_읽는다() throws Exception {
        for (String name : List.of("a", "b", "c")) {
            write("p/" + name + ".jsonl",
                    IntStream.range(0, 600).mapToObj(i -> kiloLine("m_" + name + i)).toArray(String[]::new));
        }
        ClaudeCodeUsageCollector c = collector(cfg(false, "pc-test", 1), Map.of());

        Result first = c.runOnce();
        Set<String> firstKeys = keys(sent());
        assertThat(first.budgetHit()).isTrue();
        assertThat(firstKeys).hasSizeLessThan(1800).hasSize(1024);

        Result second = c.runOnce();
        Set<String> secondKeys = keys(sent());
        assertThat(second.budgetHit()).isFalse();
        assertThat(secondKeys).hasSize(776).doesNotContainAnyElementsOf(firstKeys);
        Set<String> union = new HashSet<>(firstKeys);
        union.addAll(secondKeys);
        assertThat(union).hasSize(1800);

        Result third = c.runOnce();
        List<ClaudeCodeUsageBatchRequest> last = sent();
        assertThat(last).hasSize(1);
        assertThat(last.get(0).events()).isEmpty();
        assertThat(third.newLines()).isZero();
    }

    @Test
    void CLAUDE_CONFIG_DIR를_따른다() throws Exception {
        write("p/s.jsonl", line("home_key", "r", 1));   // 가짜 홈의 .claude — 읽히면 안 된다
        Path cfgDir = home.resolve("cfg");
        Path f = cfgDir.resolve("projects").resolve("p").resolve("s.jsonl");
        Files.createDirectories(f.getParent());
        Files.writeString(f, line("cfg_key", "r", 1) + "\n");

        collector(cfg(false, "pc-test", 256), Map.of("claude_config_dir", cfgDir.toString())).runOnce();

        assertThat(keys(sent())).containsExactly("cfg_key:r");
    }

    @Test
    void env가_없으면_가짜_홈() throws Exception {
        write("p/s.jsonl", line("k1", "r", 1));
        ClaudeCodeUsageCollector c = spy(collector(cfg(false, "pc-test", 256), Map.of()));
        List<Path> walked = new ArrayList<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Path> real = (List<Path>) inv.callRealMethod();
            walked.addAll(real);
            return real;
        }).when(c).listJsonl(any());

        c.runOnce();

        ArgumentCaptor<Path> dir = ArgumentCaptor.forClass(Path.class);
        verify(c).listJsonl(dir.capture());
        assertThat(dir.getValue()).isEqualTo(home.resolve(".claude").resolve("projects"));
        assertThat(walked).isNotEmpty().allSatisfy(p -> assertThat(p.startsWith(home)).isTrue());
        assertThat(keys(sent())).containsExactly("k1:r");
    }

    @Test
    void 등록과_끄기() {
        ScheduledTaskRegistrar on = new ScheduledTaskRegistrar();
        collector().configureTasks(on);
        List<IntervalTask> tasks = on.getFixedDelayTaskList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(300));
        assertThat(tasks.get(0).getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(ClaudeCodeUsageCollector.INITIAL_DELAY).isEqualTo(Duration.ofSeconds(60));

        UsageCollectProperties off = new UsageCollectProperties(false, 300, false, List.of(), null, null, null);
        ScheduledTaskRegistrar none = new ScheduledTaskRegistrar();
        ClaudeCodeUsageCollector disabled = collector(off, Map.of());
        disabled.configureTasks(none);
        assertThat(none.getFixedDelayTaskList()).isEmpty();

        disabled.collect();   // 꺼져 있으면 회차를 돌지 않는다
        verify(http, never()).postClaudeCodeUsage(any());
    }

    @Test
    void collect는_예외를_밖으로_내지_않는다() throws Exception {
        ClaudeCodeUsageCollector c = spy(collector());
        Files.createDirectories(projects);
        doThrow(new IOException("디스크 오류")).when(c).listJsonl(any());

        assertThatCode(c::collect).doesNotThrowAnyException();
        verify(http, never()).postClaudeCodeUsage(any());
    }

    @Test
    void machine() {
        assertThat(collector(cfg(false, "pc-a", 256), Map.of()).machineName()).isEqualTo("pc-a");
        assertThat(collector(cfg(false, null, 256), Map.of()).machineName()).isNotBlank();
        assertThat(collector(cfg(false, "x".repeat(150), 256), Map.of()).machineName()).hasSize(100);
    }

    @Test
    void 회차_로그_문구() {
        Result r = new Result(false, 3, 120, 40, 5, 2, 1, 1, false, true, 30, 10, 0);
        assertThat(ClaudeCodeUsageCollector.summary(r, 256)).isEqualTo(
                "Claude 사용량 수집: 파일 3 · 새 줄 120 · Symphony 40 · 직접 5 · 직접 제외 2 · 시작일 이전 1"
                        + " · 못 읽은 줄 1 · 새로 30 · 갱신 10 · 거절 0");
        Result hit = new Result(false, 3, 120, 40, 5, 2, 1, 1, true, true, 30, 10, 0);
        assertThat(ClaudeCodeUsageCollector.summary(hit, 256))
                .endsWith(" · 거절 0 · 읽기 상한 256MB 도달 — 다음 회차에 이어 읽음");
    }
}
