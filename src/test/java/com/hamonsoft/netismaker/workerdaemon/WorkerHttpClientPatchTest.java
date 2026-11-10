package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.PatchInfo;
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

class WorkerHttpClientPatchTest {

    @TempDir Path tmp;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WorkerHttpClient client(int status, String response, AtomicReference<String> seen) throws Exception {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/tasks/7/patch", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI() + " key=" + ex.getRequestHeaders().getFirst("X-Worker-API-Key")
                    + " sha=" + ex.getRequestHeaders().getFirst("X-Artifact-Sha256")
                    + " type=" + ex.getRequestHeaders().getFirst("Content-Type")
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
    void uploadPatchSendsShaHeaderAndFileCount() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerHttpClient c = client(200,
                "{\"name\":\"w-task-7-patch.zip\",\"sizeBytes\":3,\"sha256\":\"ab\",\"fileCount\":2}", seen);
        Path f = Files.writeString(tmp.resolve("patch.zip"), "ZIP");

        PatchInfo info = c.uploadPatch(7L, 2, f, "ab", Duration.ofSeconds(10));

        assertThat(info).isEqualTo(new PatchInfo("w-task-7-patch.zip", 3, "ab", 2));
        assertThat(seen.get()).startsWith("PUT /worker/tasks/7/patch?workerId=w1&fileCount=2 ")
                .contains("key=k1").contains("sha=ab").contains("type=application/octet-stream")
                .contains("upgrade=null").endsWith("body=ZIP");
    }

    @Test
    void uploadPatchRejectionThrowsWithStatusAndMaskedBody() throws Exception {
        String body = "x".repeat(285) + " https://oauth2:secretpassword@gitlab.example/g/p.git";
        WorkerHttpClient c = client(500, body, new AtomicReference<>());
        Path f = Files.writeString(tmp.resolve("patch.zip"), "ZIP");
        assertThatThrownBy(() -> c.uploadPatch(7L, 1, f, "ab", Duration.ofSeconds(10)))
                .hasMessageContaining("500").hasMessageContaining("https://***@")
                .hasMessageNotContaining("secret");
    }

    @Test
    void currentPatch404IsEmpty() throws Exception {
        WorkerHttpClient found = client(200,
                "{\"name\":\"w-task-7-patch.zip\",\"sizeBytes\":3,\"sha256\":\"ab\",\"fileCount\":4}",
                new AtomicReference<>());
        assertThat(found.currentPatch(7L)).contains(new PatchInfo("w-task-7-patch.zip", 3, "ab", 4));

        WorkerHttpClient none = client(404, "{\"message\":\"없음\"}", new AtomicReference<>());
        assertThat(none.currentPatch(7L)).isEmpty();
    }
}
