package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 *  사내 백엔드 워커 API 호출 (외부망 → HTTPS).
 */
@Component
@Profile("worker")
public class WorkerHttpClient {

    private final RestClient http;
    private final WorkerProperties props;

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

    public Optional<WorkerTaskResponse> nextTask() {
        try {
            WorkerTaskResponse body = http.get()
                    .uri(uri -> uri.path("/worker/next-task").queryParam("workerId", props.id()).build())
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
}
