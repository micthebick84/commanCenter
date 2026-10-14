package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerHttpClientArtifactTest {

    @TempDir Path tmp;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WorkerHttpClient client(int status, String response, AtomicReference<String> seen) throws Exception {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/tasks/7/artifact", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI() + " key=" + ex.getRequestHeaders().getFirst("X-Worker-API-Key")
                    + " sha=" + ex.getRequestHeaders().getFirst("X-Artifact-Sha256")
                    + " upgrade=" + ex.getRequestHeaders().getFirst("Upgrade")
                    + " body=" + new String(body, StandardCharsets.UTF_8));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(props.apiKey()).thenReturn("k1");
        when(props.id()).thenReturn("w1");
        return new WorkerHttpClient(props);
    }

    @Test
    void 파일을_원시_본문으로_올리고_응답을_읽는다() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerHttpClient c = client(200, "{\"name\":\"w-task-7-abcdef1.war\",\"sizeBytes\":3,\"sha256\":\"ab\"}", seen);
        Path f = Files.writeString(tmp.resolve("x.war"), "WAR");

        ArtifactInfo info = c.uploadArtifact(7L, "w-task-7-abcdef1.war", "img:/app/x.war", f, "ab", Duration.ofSeconds(10));

        assertThat(info).isEqualTo(new ArtifactInfo("w-task-7-abcdef1.war", 3, "ab"));
        assertThat(seen.get()).startsWith("PUT /worker/tasks/7/artifact?workerId=w1&name=w-task-7-abcdef1.war&source=")
                .contains("key=k1").contains("sha=ab").endsWith("body=WAR");
        // 254MB PUT에 h2c 업그레이드 헤더가 붙지 않게 HTTP/1.1로 고정한다
        assertThat(seen.get()).contains("upgrade=null");
    }

    @Test
    void 출처가_없으면_source_쿼리를_아예_붙이지_않는다() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerHttpClient c = client(200, "{\"name\":\"w-task-7-abcdef1.war\",\"sizeBytes\":3,\"sha256\":\"ab\"}", seen);
        Path f = Files.writeString(tmp.resolve("x.war"), "WAR");

        c.uploadArtifact(7L, "w-task-7-abcdef1.war", null, f, "ab", Duration.ofSeconds(10));

        assertThat(seen.get()).startsWith("PUT /worker/tasks/7/artifact?workerId=w1&name=w-task-7-abcdef1.war key=k1")
                .doesNotContain("source");
    }

    @Test
    void 거절되면_상태와_본문으로_예외() throws Exception {
        WorkerHttpClient c = client(409, "{\"message\":\"종료중 상태가 아닙니다\"}", new AtomicReference<>());
        Path f = Files.writeString(tmp.resolve("x.war"), "WAR");
        assertThatThrownBy(() -> c.uploadArtifact(7L, "w-task-7-abcdef1.war", null, f, "ab", Duration.ofSeconds(10)))
                .hasMessageContaining("409").hasMessageContaining("종료중 상태가 아닙니다");
    }

    @Test
    void 보관된_산출물이_있으면_정보를_없으면_empty() throws Exception {
        WorkerHttpClient found = client(200, "{\"name\":\"w-task-7-abcdef1.war\",\"sizeBytes\":3,\"sha256\":\"ab\"}",
                new AtomicReference<>());
        assertThat(found.currentArtifact(7L)).contains(new ArtifactInfo("w-task-7-abcdef1.war", 3, "ab"));

        WorkerHttpClient none = client(404, "{\"message\":\"없음\"}", new AtomicReference<>());
        assertThat(none.currentArtifact(7L)).isEmpty();
    }
}
