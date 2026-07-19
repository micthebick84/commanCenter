package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
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

    public void postDeployLog(Long taskId, int seq, String content) {
        try {
            http.post().uri("/worker/tasks/{id}/deploy-log", taskId)
                    .body(new com.hamonsoft.netismaker.dto.DeployLogChunkRequest(seq, content))
                    .retrieve().toBodilessEntity();
        } catch (Exception ignore) { /* 로그 업로드 실패가 배포를 막지 않음 */ }
    }

    /** 배포 런타임 정합 대상(배포완료+배포중단됨) 목록. 실패 시 예외 전파 — 호출부가 스킵 판단. */
    public List<DeployedTaskSummary> deployedTasks() {
        List<DeployedTaskSummary> body = http.get().uri("/worker/deployed-tasks")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return body == null ? List.of() : body;
    }

    /** 컨테이너 생존 관측 보고 (reconcile). 실패 시 예외 전파 — 다음 주기에 재관측되므로 재시도 불필요. */
    public void postRuntimeStatus(Long taskId, WorkerRuntimeStatusRequest req) {
        http.post().uri("/worker/tasks/{id}/runtime-status", taskId)
                .body(req).retrieve().toBodilessEntity();
    }
}
