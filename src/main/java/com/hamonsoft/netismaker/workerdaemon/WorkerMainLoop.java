package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

/**
 *  워커 메인 루프 (DESIGN §6).
 *
 *   매 5초:  백엔드 폴링 → 대기 작업이 있으면 처리
 *   매 10초: heartbeat
 *
 *   처리 흐름:
 *     1) GET /worker/next-task → claim
 *     2) git fetch/clone
 *     3) 프롬프트 치환 + claude -p exec
 *     4) 결과 파싱 (§11 엄격)
 *     5) POST /worker/tasks/{id}/result
 *
 *   exec 또는 파싱 실패 → status=FAILED로 보고 (백엔드가 retry 처리)
 */
@Component
@Profile("worker")
@Slf4j
public class WorkerMainLoop {

    private final WorkerProperties props;
    private final WorkerHttpClient http;
    private final GitRepoCache repos;
    private final ClaudeExecAdapter claude;
    private final PromptResultParser parser;

    public WorkerMainLoop(WorkerProperties props,
                          WorkerHttpClient http,
                          GitRepoCache repos,
                          ClaudeExecAdapter claude,
                          PromptResultParser parser) {
        this.props = props;
        this.http = http;
        this.repos = repos;
        this.claude = claude;
        this.parser = parser;
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.heartbeat-interval-seconds:10} * 1000}")
    public void sendHeartbeat() {
        try {
            http.heartbeat(new WorkerHeartbeatRequest(
                    props.id(), hostname(), props.version(), null, null));
        } catch (RestClientException e) {
            log.warn("heartbeat 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.poll-interval-seconds:5} * 1000}")
    public void pollAndProcess() {
        Optional<WorkerTaskResponse> maybeTask;
        try {
            maybeTask = http.nextTask();
        } catch (RestClientException e) {
            log.warn("next-task 폴링 실패: {}", e.getMessage());
            return;
        }
        if (maybeTask.isEmpty()) return;

        WorkerTaskResponse task = maybeTask.get();
        log.info("작업 claim: id={} repo={}/{}", task.id(), task.githubRepo(), task.githubBranch());
        try {
            processTask(task);
        } catch (Throwable t) {
            log.error("작업 처리 중 예외 task={}", task.id(), t);
            safePostFailure(task.id(), "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void processTask(WorkerTaskResponse task) throws Exception {
        // 1. 레포 캐시 fresh
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.githubRepo(), task.githubBranch());
        } catch (Exception e) {
            safePostFailure(task.id(), "레포 fetch 실패: " + e.getMessage());
            return;
        }

        // 2. 프롬프트 치환
        String prompt = props.promptTemplate()
                .replace("{github_repo}", task.githubRepo())
                .replace("{github_branch}", task.githubBranch())
                .replace("{commit_sha}", repo.commitSha())
                .replace("{title}", task.title())
                .replace("{description}", task.description());

        // 3. claude exec
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, repo.dir(), props.analysisTimeout());
        } catch (Exception e) {
            safePostFailure(task.id(), "claude exec 실패: " + e.getMessage());
            return;
        }
        if (exec.exitCode() != 0) {
            safePostFailure(task.id(), "claude exit=" + exec.exitCode());
            return;
        }

        // 4. 결과 파싱
        PromptResultParser.ParseResult parsed;
        try {
            parsed = parser.parse(exec.stdout());
        } catch (PromptResultParser.ParseException e) {
            safePostFailure(task.id(), "파싱 실패: " + e.getMessage());
            return;
        }

        // 5. 결과 업로드
        http.postResult(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.COMPLETED,
                parsed.markdown(),
                parsed.subtasksJson(),
                exec.stdout(),
                exec.durationMs(),
                null
        ));
        log.info("작업 완료: id={} duration={}ms warnings={}",
                task.id(), exec.durationMs(), parsed.warnings());
    }

    private void safePostFailure(Long taskId, String reason) {
        try {
            http.postResult(taskId, new WorkerResultRequest(
                    props.id(),
                    TaskStatus.FAILED,
                    null, null, null, null, reason
            ));
        } catch (RestClientException e) {
            log.error("실패 보고도 실패함 task={} reason={}", taskId, reason, e);
        }
    }

    private String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
