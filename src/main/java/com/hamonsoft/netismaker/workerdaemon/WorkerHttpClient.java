package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 *  사내 백엔드 워커 API 호출 (외부망 → HTTPS).
 */
@Component
@Profile("worker")
public class WorkerHttpClient {

    private final RestClient http;
    private final WorkerProperties props;

    /** 산출물 업로드 전용 — RestClient는 본문을 버퍼링할 수 있어 254MB를 파일에서 바로 흘리려고 JDK HttpClient를 쓴다. */
    private final HttpClient uploader = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** 응답 필드가 늘어도(롤링 배포) 읽도록 RestClient의 Jackson과 같게 모르는 속성은 무시한다. */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public WorkerHttpClient(WorkerProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("X-Worker-API-Key", props.apiKey())
                .build();
    }

    public void heartbeat(WorkerHeartbeatRequest req) {
        http.post().uri("/worker/heartbeat").body(req).retrieve().toBodilessEntity();
    }

    /**
     * 기동 알림 — 이전 프로세스가 들고 있던 진행 중 작업을 API가 회수하게 한다.
     * 이 API가 엔드포인트를 모르면(404, 구버전) empty. 그 밖의 실패는 호출자가 재시도하도록 전파한다.
     */
    public Optional<WorkerStartupResponse> startup(List<Long> pendingTaskIds) {
        try {
            WorkerStartupResponse body = http.post()
                    .uri(uri -> uri.path("/worker/startup").queryParam("workerId", props.id()).build())
                    .body(new WorkerStartupRequest(pendingTaskIds))
                    .retrieve()
                    .body(WorkerStartupResponse.class);
            return Optional.ofNullable(body);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * next-task의 supports — 이 워커가 아는 기능(쉼표 구분). API는 refRepos가 없는 요청(구버전 워커)에게 참조 repo가 있는
     * 구현을 넘기지 않는다 (스펙 2026-10-24 §4.7).
     */
    static final String SUPPORTS = "refRepos";

    public Optional<WorkerTaskResponse> nextTask() {
        try {
            WorkerTaskResponse body = http.get()
                    .uri(uri -> uri.path("/worker/next-task").queryParam("workerId", props.id())
                            .queryParam("supports", SUPPORTS).build())
                    .retrieve()
                    .body(WorkerTaskResponse.class);
            return Optional.ofNullable(body);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NO_CONTENT) return Optional.empty();
            throw e;
        }
    }

    public void postResult(Long taskId, WorkerResultRequest req) {
        http.post().uri("/worker/tasks/{id}/result", taskId).body(req).retrieve().toBodilessEntity();
    }

    /**
     * 스트리밍 배포 로그 청크. 작업 상세 화면에 실시간 렌더되므로 전송 직전에 자격증명을 가린다
     * (결과 페이로드는 ResultReporter가, 스트림 청크는 이 한 곳이 경계).
     */
    public void postDeployLog(Long taskId, int seq, String content) {
        try {
            http.post().uri("/worker/tasks/{id}/deploy-log", taskId)
                    .body(maskedChunk(seq, content))
                    .retrieve().toBodilessEntity();
        } catch (Exception ignore) { /* 로그 업로드 실패가 배포를 막지 않음 */ }
    }

    /** 전송 직전 변환(순수 함수 — 테스트 seam). postDeployLog는 이 결과만 body로 쓴다. */
    static com.hamonsoft.netismaker.dto.DeployLogChunkRequest maskedChunk(int seq, String content) {
        return new com.hamonsoft.netismaker.dto.DeployLogChunkRequest(
                seq, com.hamonsoft.netismaker.git.GitRemotes.mask(content));
    }

    /** 배포 런타임 정합 대상(배포완료+배포중단됨) 목록. 실패 시 예외 전파 — 호출부가 스킵 판단. */
    public List<DeployedTaskSummary> deployedTasks() {
        List<DeployedTaskSummary> body = http.get().uri("/worker/deployed-tasks")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return body == null ? List.of() : body;
    }

    /**
     * worktree 정리 보호 목록(구현중·디자인중·배포중·배포중지중). 실패 시 예외 전파 — 호출부가 회차를 건너뛴다.
     * 응답 본문이 없는(null) 경우도 조회 실패로 본다: 본문 없는 2xx(프록시·게이트웨이 오동작 등)를 '진행 중 작업 없음'으로
     * 읽으면 보호 없이 삭제가 진행되므로 fail-closed. 빈 JSON 배열 []은 정상적인 '진행 중 없음'이다.
     */
    public List<ActiveWorktreeTaskSummary> activeWorktreeTasks() {
        List<ActiveWorktreeTaskSummary> body = http.get().uri("/worker/active-worktree-tasks")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return requireActiveBody(body);
    }

    /** 응답 본문 검증 seam(HTTP 없이 단위 테스트). null이면 조회 실패. */
    static List<ActiveWorktreeTaskSummary> requireActiveBody(List<ActiveWorktreeTaskSummary> body) {
        if (body == null) {
            throw new IllegalStateException("진행 중 worktree 작업 목록 응답 본문이 없다 — 조회 실패로 간주");
        }
        return body;
    }

    /** 컨테이너 생존 관측 보고 (reconcile). 실패 시 예외 전파 — 다음 주기에 재관측되므로 재시도 불필요. */
    public void postRuntimeStatus(Long taskId, WorkerRuntimeStatusRequest req) {
        http.post().uri("/worker/tasks/{id}/runtime-status", taskId)
                .body(req).retrieve().toBodilessEntity();
    }

    /** 종료 단계 완료 보고. 실패는 호출자(TaskCloser)가 종료실패로 처리한다 - 재시도 때 그 단계가 다시 돈다(멱등). */
    public void postCloseProgress(Long taskId, com.hamonsoft.netismaker.dto.CloseProgressRequest req) {
        http.post().uri("/worker/tasks/{id}/close-progress", taskId).body(req).retrieve().toBodilessEntity();
    }

    /** 이미 보관된 종료 산출물. 404면 empty — 재시도에서 이미지를 다시 꺼내지 않으려고 먼저 묻는다 (스펙 2026-10-14 §4). */
    public Optional<ArtifactInfo> currentArtifact(Long taskId) {
        try {
            return Optional.ofNullable(http.get().uri("/worker/tasks/{id}/artifact", taskId)
                    .retrieve().body(ArtifactInfo.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * 종료 산출물 업로드 — PUT 원시 본문(octet-stream) + X-Artifact-Sha256. 2xx가 아니면 상태·본문으로 IOException.
     * 서버가 본문을 다 읽기 전에 400/409/413으로 거절하면 Tomcat이 연결을 끊어 상태 코드 대신 IOException(broken pipe 등)이
     * 올 수 있다 — 호출부(ArtifactPublisher)가 둘 다 종료실패 사유로 바꾼다. source가 null이면 쿼리에 싣지 않는다.
     */
    public ArtifactInfo uploadArtifact(Long taskId, String name, String source, Path file, String sha256,
                                       Duration timeout) throws IOException, InterruptedException {
        // 값은 전부 URI 변수로 엄격 인코딩한다 — source에 '+'·'&'·'{'가 있어도 그대로 한 값으로 간다.
        Map<String, Object> vars = new HashMap<>();
        vars.put("id", taskId);
        vars.put("workerId", props.id());
        vars.put("name", name);
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(props.apiBaseUrl())
                .path("/worker/tasks/{id}/artifact")
                .queryParam("workerId", "{workerId}")
                .queryParam("name", "{name}");
        if (source != null) {
            b.queryParam("source", "{source}");
            vars.put("source", source);
        }
        URI uri = b.encode().buildAndExpand(vars).toUri();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)   // 254MB PUT에 h2c 업그레이드 헤더가 붙지 않게
                .timeout(timeout)
                .header("X-Worker-API-Key", props.apiKey())
                .header("X-Artifact-Sha256", sha256)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<String> res = uploader.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            // 먼저 가리고 나서 자른다 — 자른 뒤에 가리면 경계에 걸친 "://user:secret@"가 정규식에 안 잡혀 일부가 남는다
            String body = res.body() == null ? "" : com.hamonsoft.netismaker.git.GitRemotes.mask(res.body());
            throw new IOException("서버 응답 " + res.statusCode() + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
        }
        return mapper.readValue(res.body(), ArtifactInfo.class);
    }
}
