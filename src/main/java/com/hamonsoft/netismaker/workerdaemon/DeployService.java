package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 배포 단계 오케스트레이션 (워커):
 *   1) head 브랜치 fetch + deploy worktree 체크아웃
 *   2) Dockerfile 있으면 사용, 없으면 claude로 생성
 *   3) EXPOSE 포트 파싱
 *   4) DeployTarget.deploy 호출 (build + run)
 *
 * docker 직접 호출 없음 — 전부 DeployTarget 뒤로. 원격 호스트로 교체해도 이 클래스 불변.
 */
@Component
@Profile("worker")
@Slf4j
public class DeployService {

    private final WorkerProperties props;
    private final GitRepoCache repos;
    private final WorktreeService worktrees;
    private final ClaudeExecAdapter claude;
    private final DeployTarget target;

    public DeployService(WorkerProperties props, GitRepoCache repos,
                         WorktreeService worktrees, ClaudeExecAdapter claude,
                         DeployTarget target) {
        this.props = props;
        this.repos = repos;
        this.worktrees = worktrees;
        this.claude = claude;
        this.target = target;
    }

    /** 배포 수행. 실패 시 DeployException(로그 포함). */
    public DeployTarget.DeployResult deploy(WorkerTaskResponse task,
                                            java.util.function.Consumer<String> logSink) throws DeployException {
        if (task.headBranch() == null || task.headBranch().isBlank()) {
            throw new DeployException("head 브랜치 정보 없음 (PR생성 안 된 task?)", null);
        }
        StringBuilder log = new StringBuilder();
        try {
            // fetch만 — head 브랜치 checkout은 구현 worktree와 충돌하므로 금지.
            // createForDeploy가 origin/{head}를 --detach로 분리 체크아웃한다.
            GitRepoCache.CheckedOutRepo repo = repos.fetchOnly(task.githubRepo(), task.headBranch());
            File wt = worktrees.createForDeploy(repo.dir(), task.githubRepo(),
                    task.headBranch(), task.id());
            Path dockerfile = wt.toPath().resolve("Dockerfile");

            if (!Files.exists(dockerfile)) {
                log.append("[Dockerfile 없음 → claude 생성]\n");
                generateDockerfile(task, wt, log);
                if (!Files.exists(dockerfile)) {
                    throw new DeployException("claude가 Dockerfile을 생성하지 못함", log.toString());
                }
            } else {
                log.append("[기존 Dockerfile 사용]\n");
            }

            int containerPort = DockerfileSupport.parseExposedPort(Files.readString(dockerfile))
                    .orElse(props.deploy().defaultContainerPort());
            log.append("[containerPort=").append(containerPort).append("]\n");

            String shortSha = task.headSha() == null ? "latest"
                    : task.headSha().substring(0, Math.min(7, task.headSha().length()));

            // env_vars → docker -e 주입용 Map. 빈 key는 제외. LocalDockerTarget이 spec.env()를 -e로 푼다.
            Map<String, String> env = new LinkedHashMap<>();
            if (task.envVars() != null) {
                for (EnvVar ev : task.envVars()) {
                    if (ev.key() != null && !ev.key().isBlank()) env.put(ev.key(), ev.value());
                }
            }
            log.append("[env 주입: ").append(env.size()).append("개 키]\n");

            DeployTarget.DeploySpec spec = new DeployTarget.DeploySpec(
                    task.id(),
                    wt.toPath(),
                    "netis-task-" + task.id() + ":" + shortSha,
                    "netis-task-" + task.id(),
                    containerPort,
                    env,
                    Map.of("netis-maker.task", String.valueOf(task.id())));

            DeployTarget.DeployResult r = target.deploy(spec, logSink);
            return new DeployTarget.DeployResult(r.url(), r.containerId(), r.hostPort(),
                    r.image(), log + r.log());
        } catch (DeployException e) {
            throw e;
        } catch (com.hamonsoft.netismaker.workerdaemon.deploy.DeployFailedException e) {
            // 타깃이 빌드/헬스체크 실패를 진단 로그와 함께 보고 — 컨테이너 로그까지 보존.
            throw new DeployException(e.getMessage(),
                    log + (e.getLog() == null ? "" : e.getLog()));
        } catch (Exception e) {
            throw new DeployException(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    log.toString());
        }
    }

    /** 컨테이너 중지. */
    public void undeploy(long taskId) throws DeployException {
        try {
            target.stop("netis-task-" + taskId);
        } catch (Exception e) {
            throw new DeployException("컨테이너 중지 실패: " + e.getMessage(), null);
        }
    }

    private void generateDockerfile(WorkerTaskResponse task, File worktree, StringBuilder log)
            throws Exception {
        String tpl = props.deploy().dockerfilePromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = """
                  현재 디렉토리 프로젝트를 컨테이너로 실행할 production용 Dockerfile을
                  현재 디렉토리 루트에 생성하세요. 멀티스테이지 빌드, EXPOSE 포트 명시,
                  Dockerfile 외 파일 생성 금지. 완료 후 내용을 출력하세요.
                  """;
        }
        String prompt = tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{branch}", task.headBranch());
        ClaudeExecAdapter.ExecResult exec = claude.exec(
                prompt, worktree, props.deploy().buildTimeout(), java.util.List.of(), true);
        log.append(tail(exec.stdout(), 2000)).append('\n');
        if (exec.exitCode() != 0) {
            throw new DeployException("Dockerfile 생성 claude exit=" + exec.exitCode(),
                    log.toString());
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    /** 배포 실패를 로그와 함께 전달. */
    public static class DeployException extends Exception {
        private final String deployLog;
        public DeployException(String message, String deployLog) {
            super(message);
            this.deployLog = deployLog;
        }
        public String getDeployLog() { return deployLog; }
    }
}
