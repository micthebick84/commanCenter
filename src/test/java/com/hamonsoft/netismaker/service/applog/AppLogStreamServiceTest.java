package com.hamonsoft.netismaker.service.applog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.config.AppLogProperties;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.applog.RecordingSink.Rec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 앱 로그 스트림 본체 (스펙 2026-11-03 §4). docker는 실행하지 않는다 — 가짜 실행기가 {@link FakeDockerProcess}를 돌려준다.
 * 시험의 시각은 docker `--timestamps` 모양(9자리, Z)이다.
 */
class AppLogStreamServiceTest {

    private static final String TS = "2026-11-03T01:02:03.000000001Z";
    private static final Path DOCKER = Path.of("/usr/bin/docker");
    private static final String TIME_LIMIT_MESSAGE = "30분이 지나 연결을 닫았습니다";

    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<FakeDockerProcess> processes = new CopyOnWriteArrayList<>();
    private final List<RecordingSink> sinks = new CopyOnWriteArrayList<>();
    private final List<AppLogStreamService> services = new CopyOnWriteArrayList<>();

    private final DockerLogsLauncher fakeLauncher = command -> {
        commands.add(command);
        FakeDockerProcess p = new FakeDockerProcess();
        processes.add(p);
        return p;
    };

    @AfterEach
    void tearDown() {
        sinks.forEach(RecordingSink::release);
        services.forEach(AppLogStreamService::shutdown);
        processes.forEach(p -> p.exit(0));
    }

    // ---- 도우미 ----

    private static AppLogProperties props(int pingSeconds, int batchMillis) {
        return new AppLogProperties(true, "", 500, 6, 3, 30, pingSeconds, batchMillis, 200, 4000);
    }

    private static AppLogProperties props() {
        return props(10, 300);
    }

    private AppLogStreamService service(AppLogProperties p, AppLogAvailability availability, DockerLogsLauncher launcher,
                                        Map<String, String> processEnv, Duration timeLimit) {
        AppLogStreamService s = new AppLogStreamService(p, availability, launcher, processEnv, timeLimit);
        services.add(s);
        return s;
    }

    private AppLogStreamService service(AppLogProperties p, Duration timeLimit) {
        return service(p, on(p), fakeLauncher, Map.of(), timeLimit);
    }

    private AppLogStreamService service() {
        return service(props(), Duration.ofMinutes(30));
    }

    private static AppLogAvailability on(AppLogProperties p) {
        return new AppLogAvailability(p, Optional.of(DOCKER), null);
    }

    private RecordingSink sink() {
        RecordingSink s = new RecordingSink();
        sinks.add(s);
        return s;
    }

    private static Task task(long id, TaskStatus status, List<EnvVar> envVars) {
        Task t = Task.create("owner/repo", "main", "제목", "설명", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setDeployUrl("http://10.1.1.75:19042");
        t.setEnvVars(new ArrayList<>(envVars));
        return t;
    }

    private static Task deployed(long id) {
        return task(id, TaskStatus.DEPLOYED, List.of());
    }

    private static Rec end(String reason, String message) {
        return Rec.event(AppLogStream.EVENT_END, Map.of("reason", reason, "message", message));
    }

    private static List<String> texts(List<AppLogLine> lines) {
        return lines.stream().map(AppLogLine::text).toList();
    }

    // ---- 거절 ----

    @Test
    void 꺼져_있으면_end_disabled_프로세스_없음() {
        AppLogProperties p = props();
        AppLogProperties off = new AppLogProperties(false, "", 500, 6, 3, 30, 10, 300, 200, 4000);
        for (AppLogAvailability availability : List.of(
                new AppLogAvailability(p, Optional.empty(), null),          // docker 없음
                new AppLogAvailability(off, Optional.of(DOCKER), null))) {  // 설정 꺼짐
            AppLogStreamService s = service(p, availability, fakeLauncher, Map.of(), Duration.ofMinutes(30));
            RecordingSink sink = sink();

            AppLogStream stream = s.open(deployed(42), sink);

            assertThat(stream.closed()).isTrue();
            assertThat(sink.records()).containsExactly(
                    end("disabled", "앱 로그 기능이 꺼져 있습니다(docker 없음 또는 설정 꺼짐)"), Rec.complete());
            assertThat(s.activeCount()).isZero();
        }
        assertThat(commands).isEmpty();
    }

    @Test
    void 허용_상태가_아니면_end_not_allowed_status() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();

        AppLogStream stream = s.open(task(42, TaskStatus.DEPLOYING, List.of()), sink);

        assertThat(stream.closed()).isTrue();
        assertThat(sink.records()).containsExactly(
                end("not-allowed-status", "지금 상태에서는 앱 로그를 볼 수 없습니다"), Rec.complete());
        assertThat(commands).isEmpty();
        assertThat(s.activeCount()).isZero();
    }

    @Test
    void 명령과_첫_핑() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();

        AppLogStream stream = s.open(deployed(42), sink);

        List<String> expected = List.of(DOCKER.toString(), "logs", "--tail", "500", "--timestamps", "--follow", "netis-task-42");
        assertThat(commands).containsExactly(expected);
        assertThat(AppLogStreamService.command(DOCKER, 500, 42)).isEqualTo(expected);
        assertThat(sink.records()).first().isEqualTo(Rec.comment("ping"));
        assertThat(stream.closed()).isFalse();
        assertThat(s.activeCount()).isEqualTo(1);
        assertThat(s.activeCount(42)).isEqualTo(1);
    }

    @Test
    void 핑은_pingSeconds마다() {
        AppLogStreamService s = service(props(1, 300), Duration.ofMinutes(30));
        RecordingSink sink = sink();

        s.open(deployed(42), sink);

        // 접속 핑 1 + 전송 스레드의 주기 핑 2 이상 — 줄은 한 줄도 쓰지 않는다
        await().atMost(3, SECONDS).until(() -> sink.pings() >= 3);
        assertThat(sink.records()).allMatch(r -> r.equals(Rec.comment("ping")));
    }

    @Test
    void subscribe_emitter_타임아웃은_상한_더하기_1분() {
        AppLogProperties p = props();
        AppLogStreamService s = service(p, new AppLogAvailability(p, Optional.empty(), null), fakeLauncher, Map.of(),
                Duration.ofMinutes(30));

        SseEmitter emitter = s.subscribe(deployed(42));

        assertThat(emitter.getTimeout()).isEqualTo(31 * 60_000L);
        assertThat(commands).isEmpty();
    }

    @Test
    void 전체_6_작업당_3을_넘으면_too_many() {
        Rec tooMany = end("too-many", "동시에 볼 수 있는 앱 로그 수를 넘었습니다");

        AppLogStreamService s = service();
        for (long id = 1; id <= 6; id++) {
            assertThat(s.open(deployed(id), sink()).closed()).as("작업 %d", id).isFalse();
        }
        RecordingSink seventh = sink();
        assertThat(s.open(deployed(7), seventh).closed()).isTrue();
        assertThat(seventh.records()).containsExactly(tooMany, Rec.complete());
        assertThat(s.activeCount()).isEqualTo(6);
        assertThat(commands).hasSize(6);

        AppLogStreamService perTask = service();
        for (int i = 0; i < 3; i++) {
            assertThat(perTask.open(deployed(42), sink()).closed()).isFalse();
        }
        RecordingSink fourth = sink();
        assertThat(perTask.open(deployed(42), fourth).closed()).isTrue();
        assertThat(fourth.records()).containsExactly(tooMany, Rec.complete());
        assertThat(perTask.activeCount(42)).isEqualTo(3);
        assertThat(commands).hasSize(9); // 실행 수 == 성공 수
    }

    // ---- 줄 ----

    @Test
    void 줄은_200줄씩_묶고_순서를_지킨다() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);

        StringBuilder out = new StringBuilder();
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 450; i++) {
            String text = "line-" + i;
            expected.add(text);
            out.append(TS).append(' ').append(text).append('\n');
        }
        processes.get(0).writeOut(out.toString());

        await().atMost(2, SECONDS).until(() -> sink.lines().size() == 450);
        assertThat(sink.lineBatches()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(200));
        assertThat(sink.lineBatches().size()).isGreaterThanOrEqualTo(3);
        assertThat(texts(sink.lines())).containsExactlyElementsOf(expected);
        assertThat(sink.lines()).allSatisfy(l -> {
            assertThat(l.ts()).isEqualTo(TS);
            assertThat(l.stream()).isEqualTo("out");
        });
    }

    @Test
    void stdout_stderr를_둘다_읽고_stream_표시() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        p.writeOut(TS + " from-out\n");
        p.writeErr(TS + " from-err\n");

        await().atMost(2, SECONDS).until(() -> sink.lines().size() == 2);
        assertThat(sink.lines()).containsExactlyInAnyOrder(
                new AppLogLine(TS, "out", "from-out"), new AppLogLine(TS, "err", "from-err"));
    }

    @Test
    void stderr가_가득_차도_stdout이_막히지_않는다() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        String errLine = TS + " " + "e".repeat(96) + "\n"; // 30 + 1 + 96 + 1 = 128바이트
        String errBulk = errLine.repeat(2048);              // 256KB — 64KB 파이프를 네 번 채운다
        assertThat(errBulk.length()).isEqualTo(256 * 1024);
        // 한 스레드가 err를 다 쓴 "뒤에" out을 쓴다 — stderr를 안 읽는 구현이면 writeErr에서 막혀 out 줄이 영영 안 온다
        Thread writer = new Thread(() -> {
            p.writeErr(errBulk);
            p.writeOut(TS + " after-err\n");
        }, "test-writer");
        writer.setDaemon(true);
        writer.start();

        await().atMost(2, SECONDS).until(() -> sink.lines().contains(new AppLogLine(TS, "out", "after-err")));
    }

    @Test
    void 시각_없는_줄은_info() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);

        processes.get(0).writeErr("WARNING: ssh host key not verified\n");

        await().atMost(2, SECONDS).until(() -> !sink.events(AppLogStream.EVENT_INFO).isEmpty());
        assertThat(sink.events(AppLogStream.EVENT_INFO))
                .containsExactly(Map.of("message", "WARNING: ssh host key not verified"));
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void 이벤트_JSON_모양() throws Exception {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        p.writeOut(TS + " hello\n");
        await().atMost(2, SECONDS).until(() -> sink.lines().size() == 1);
        p.writeErr("Error response from daemon: No such container: netis-task-42\n");
        p.exit(1);
        await().atMost(2, SECONDS).until(sink::completed);

        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(sink.events(AppLogStream.EVENT_LINES).get(0)))
                .isEqualTo("{\"lines\":[{\"ts\":\"" + TS + "\",\"stream\":\"out\",\"text\":\"hello\"}]}");
        assertThat(json.writeValueAsString(sink.events(AppLogStream.EVENT_INFO).get(0)))
                .isEqualTo("{\"message\":\"Error response from daemon: No such container: netis-task-42\"}");
        assertThat(json.writeValueAsString(sink.events(AppLogStream.EVENT_END).get(0)))
                .isEqualTo("{\"reason\":\"no-container\",\"message\":\"컨테이너가 없어 로그를 볼 수 없습니다\"}");
    }

    // ---- docker가 끝날 때 ----

    @Test
    void exit0이면_남은_줄_뒤_container_stopped() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        p.writeOut(TS + " a\n" + TS + " b\n" + TS + " c\n");
        p.exit(0);

        await().atMost(2, SECONDS).until(sink::completed);
        List<Rec> records = sink.records();
        Rec stopped = end("container-stopped", "컨테이너가 멈췄습니다");
        assertThat(texts(sink.lines())).containsExactly("a", "b", "c");
        int lastLines = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).kind() == RecordingSink.Kind.EVENT && records.get(i).name().equals("lines")) lastLines = i;
        }
        assertThat(lastLines).isGreaterThanOrEqualTo(0).isLessThan(records.indexOf(stopped));
        assertThat(records.subList(records.size() - 2, records.size())).containsExactly(stopped, Rec.complete());
        assertThat(sink.events(AppLogStream.EVENT_END)).hasSize(1);
        assertThat(s.activeCount()).isZero();
    }

    @Test
    void No_such_container로_끝나면_no_container() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        p.writeErr("Error response from daemon: No such container: netis-task-42\n");
        p.exit(1);

        await().atMost(2, SECONDS).until(sink::completed);
        List<Rec> records = sink.records();
        assertThat(records.subList(records.size() - 3, records.size())).containsExactly(
                Rec.event("info", Map.of("message", "Error response from daemon: No such container: netis-task-42")),
                end("no-container", "컨테이너가 없어 로그를 볼 수 없습니다"),
                Rec.complete());
        assertThat(s.activeCount()).isZero();
    }

    @Test
    void 그밖의_비정상_종료는_docker_error() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        p.writeErr("error during connect: ssh: connect to host 10.1.1.75 port 22: Connection timed out\n");
        p.exit(1);

        await().atMost(2, SECONDS).until(sink::completed);
        List<Rec> records = sink.records();
        assertThat(records.subList(records.size() - 2, records.size()))
                .containsExactly(end("docker-error", "docker 로그를 읽지 못했습니다"), Rec.complete());
        assertThat(s.activeCount()).isZero();
    }

    @Test
    void 실행_실패는_docker_error_자리_반납() {
        AppLogProperties p = props();
        AppLogStreamService s = service(p, on(p), command -> {
            throw new IOException("CreateProcess error=2");
        }, Map.of(), Duration.ofMinutes(30));
        RecordingSink sink = sink();

        AppLogStream stream = s.open(deployed(42), sink);

        assertThat(stream.closed()).isTrue();
        assertThat(sink.records()).containsExactly(
                Rec.comment("ping"), end("docker-error", "docker 로그를 읽지 못했습니다"), Rec.complete());
        assertThat(s.activeCount()).isZero();
        assertThat(s.activeCount(42)).isZero();
    }

    // ---- 끊김·시간 상한·느린 소비자 ----

    @Test
    void 핑_전송이_실패하면_트리를_끝내고_자리를_반납() {
        AppLogStreamService s = service(props(1, 300), Duration.ofMinutes(30));
        RecordingSink sink = sink().failFrom(2); // 접속 핑은 성공, 첫 주기 핑부터 실패(끊긴 상대)

        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);

        await().atMost(3, SECONDS).until(() -> p.destroyForciblyCount() == 1 && s.activeCount() == 0 && sink.completed());
        assertThat(sink.records()).containsExactly(Rec.comment("ping"), Rec.complete());
        assertThat(p.destroyForciblyCount()).isEqualTo(1);
    }

    @Test
    void 시간_상한은_예약돼_end_time_limit() {
        AppLogStreamService s = service(props(), Duration.ofMillis(300));
        RecordingSink sink = sink();

        s.open(deployed(42), sink); // timeLimit()을 직접 부르지 않는다
        FakeDockerProcess p = processes.get(0);

        await().atMost(2, SECONDS).until(sink::completed);
        List<Rec> records = sink.records();
        assertThat(records.subList(records.size() - 2, records.size()))
                .containsExactly(end("time-limit", TIME_LIMIT_MESSAGE), Rec.complete());
        assertThat(p.destroyForciblyCount()).isEqualTo(1);
        assertThat(s.activeCount()).isZero();
    }

    @Test
    void 막힌_sink는_다른_스트림의_핑과_줄을_막지_않는다() {
        AppLogStreamService s = service(props(1, 300), Duration.ofMinutes(30));
        RecordingSink a = sink().blockFrom(2);
        s.open(deployed(1), a);
        processes.get(0).writeOut(TS + " a-line\n");
        await().atMost(2, SECONDS).until(() -> a.attempts() >= 2); // A의 전송 스레드가 lines에서 막혔다

        RecordingSink b = sink();
        s.open(deployed(2), b);
        processes.get(1).writeOut(TS + " b-line\n");

        await().atMost(2, SECONDS).until(() -> texts(b.lines()).contains("b-line"));
        await().atMost(3, SECONDS).until(() -> b.pings() >= 2);
        assertThat(a.completed()).isFalse();
    }

    @Test
    void 막힌_sink여도_시간_상한이면_트리를_끝내고_자리를_반납() {
        // batchMillis 50 — 줄이 시간 상한(300ms)보다 먼저 전송돼 전송 스레드가 막힌 채로 상한을 맞게 한다
        AppLogStreamService s = service(props(10, 50), Duration.ofMillis(300));
        RecordingSink sink = sink().blockFrom(2);
        s.open(deployed(42), sink);
        FakeDockerProcess p = processes.get(0);
        p.writeOut(TS + " stuck\n");
        await().atMost(2, SECONDS).until(() -> sink.attempts() >= 2);

        await().atMost(2, SECONDS).until(() -> p.destroyForciblyCount() == 1 && s.activeCount() == 0);
        assertThat(sink.completed()).isFalse(); // release() 전 — 전송 스레드는 아직 막혀 있다

        sink.release();
        await().atMost(2, SECONDS).until(sink::completed);
        List<Rec> records = sink.records();
        assertThat(records.subList(records.size() - 2, records.size()))
                .containsExactly(end("time-limit", TIME_LIMIT_MESSAGE), Rec.complete());
        assertThat(texts(sink.lines())).containsExactly("stuck");
    }

    @Test
    void 정리는_한번만() throws Exception {
        AppLogStreamService s = service();
        for (int round = 0; round < 20; round++) {
            RecordingSink sink = sink();
            AppLogStream stream = s.open(deployed(42), sink);
            FakeDockerProcess p = processes.get(processes.size() - 1);

            CountDownLatch go = new CountDownLatch(1);
            List<Thread> racers = List.of(
                    racer(go, stream::clientGone),
                    racer(go, stream::timeLimit),
                    racer(go, () -> p.exit(0)));
            racers.forEach(Thread::start);
            go.countDown();
            for (Thread t : racers) t.join(5_000);

            await().atMost(2, SECONDS).until(sink::completed);
            assertThat(sink.events(AppLogStream.EVENT_END)).as("round %d", round).hasSizeLessThanOrEqualTo(1);
            assertThat(sink.completes()).as("round %d", round).isEqualTo(1);
            assertThat(p.destroyForciblyCount()).as("round %d", round).isLessThanOrEqualTo(1);
            assertThat(s.activeCount()).as("round %d", round).isZero();
            assertThat(s.activeCount(42)).as("round %d", round).isZero();
        }

        int before = commands.size();
        for (int i = 0; i < 3; i++) {
            assertThat(s.open(deployed(42), sink()).closed()).isFalse();
        }
        assertThat(commands).hasSize(before + 3);
        assertThat(s.activeCount(42)).isEqualTo(3);
    }

    private static Thread racer(CountDownLatch go, Runnable action) {
        Thread t = new Thread(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        });
        t.setDaemon(true);
        return t;
    }

    // ---- 가리기·자르기 ----

    @Test
    void 거대한_한줄도_자르고_가린다() {
        AppLogStreamService s = service();
        RecordingSink sink = sink();
        s.open(task(42, TaskStatus.DEPLOYED, List.of(new EnvVar("DB_PASSWORD", "dbPassw0rd", true))), sink);

        // 비밀은 원시 줄(시각 접두 포함) 3995자 지점 — 원시 4000자에서 잘랐다면 "dbPas"만 남아 가려지지 않는다
        String prefix = TS + " ";
        int before = 3995 - prefix.length();
        String body = "a".repeat(before) + "dbPassw0rd" + "b".repeat(1_000_000 - before - "dbPassw0rd".length());
        assertThat(body).hasSize(1_000_000);
        assertThat((prefix + body).indexOf("dbPassw0rd")).isEqualTo(3995);
        processes.get(0).writeOut(prefix + body + "\n" + TS + " next-line\n");

        await().atMost(2, SECONDS).until(() -> sink.lines().size() == 2);
        AppLogLine first = sink.lines().get(0);
        assertThat(first.text()).hasSize(4000 + AppLogLines.CUT_MARK.length())
                .endsWith(AppLogLines.CUT_MARK)
                .contains(SecretMasker.MASK)
                .doesNotContain("dbPa");
        assertThat(sink.lines().get(1).text()).isEqualTo("next-line");
    }

    @Test
    void 비밀_env는_가려서_보낸다() {
        AppLogProperties p = props();
        AppLogStreamService s = service(p, on(p), fakeLauncher, Map.of("SPRING_DATASOURCE_PASSWORD", "stackPw99"),
                Duration.ofMinutes(30));
        RecordingSink sink = sink();
        s.open(task(42, TaskStatus.DEPLOYED, List.of(new EnvVar("DB_PASSWORD", "dbPassw0rd", true))), sink);
        FakeDockerProcess proc = processes.get(0);

        proc.writeOut(TS + " pw=dbPassw0rd stack=stackPw99\n");
        await().atMost(2, SECONDS).until(() -> sink.lines().size() == 1);
        proc.writeErr("connect pw=dbPassw0rd\n");
        await().atMost(2, SECONDS).until(() -> !sink.events(AppLogStream.EVENT_INFO).isEmpty());

        assertThat(sink.lines().get(0).text()).isEqualTo("pw=•••• stack=••••");
        assertThat(sink.events(AppLogStream.EVENT_INFO)).containsExactly(Map.of("message", "connect pw=••••"));
    }

    // ---- API 종료 ----

    @Test
    void PreDestroy는_모든_스트림을_끝낸다() {
        AppLogStreamService s = service();
        RecordingSink first = sink();
        RecordingSink second = sink();
        s.open(deployed(1), first);
        s.open(deployed(2), second);

        s.shutdown();

        assertThat(processes).hasSize(2).allSatisfy(p -> assertThat(p.destroyForciblyCount()).isEqualTo(1));
        assertThat(s.activeCount()).isZero();
        await().atMost(2, SECONDS).until(() -> first.completed() && second.completed());
        assertThat(first.events(AppLogStream.EVENT_END)).isEmpty();
        assertThat(second.events(AppLogStream.EVENT_END)).isEmpty();
    }

    @Test
    void 스프링이_생성자가_둘이어도_세_인자짜리로_만든다() {
        // 로컬 @SpringBootTest는 RUN_TESTCONTAINERS로 건너뛰어 CI에서야 드러나던 기동 실패(@Autowired 누락)를 여기서 잡는다
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("api");
            AppLogProperties p = new AppLogProperties(false, "", 500, 6, 3, 30, 10, 300, 200, 4000);
            ctx.registerBean(AppLogProperties.class, () -> p);
            ctx.registerBean(AppLogAvailability.class, () -> new AppLogAvailability(p, Optional.empty(), null));
            ctx.registerBean(DockerLogsLauncher.class, () -> fakeLauncher);
            ctx.register(AppLogStreamService.class);
            ctx.refresh();

            AppLogStreamService s = ctx.getBean(AppLogStreamService.class);
            RecordingSink sink = sink();
            s.open(deployed(42), sink);
            assertThat(sink.records()).first().isEqualTo(
                    end("disabled", "앱 로그 기능이 꺼져 있습니다(docker 없음 또는 설정 꺼짐)"));
        }
    }
}
