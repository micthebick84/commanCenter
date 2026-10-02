package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
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
}
