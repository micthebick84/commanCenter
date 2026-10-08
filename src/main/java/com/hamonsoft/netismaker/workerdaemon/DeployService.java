package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.git.GitRemotes;
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
            GitRepoCache.CheckedOutRepo repo = repos.fetchOnly(task.repoRef(), task.headBranch());
            File wt = worktrees.createForDeploy(repo.dir(), GitRemotes.localKey(task.repoRef()),
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
            // 컨테이너는 이미지로 돈다 — 빌드 컨텍스트(worktree)는 더 쓰지 않는다. 재배포는 createForDeploy가 새로 만든다.
            worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.DEPLOY, task.id());
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

    /**
     * Dockerfile 생성 프롬프트 끝에 코드에서 붙이는 규칙 — 운영자가 yml 템플릿을 바꿔도 빠지지 않는다.
     * 생성 claude는 --dangerously-skip-permissions라 Bash로 검증 빌드를 돌릴 수 있고, 워커의 DOCKER_HOST를
     * 물려받아 원격에서 빌드가 돌다 timeout에 걸렸다(2026-10-08 작업 9).
     */
    static final String DOCKERFILE_NO_BUILD_RULE = """

            ## 금지 (필수)
            - docker build, docker run, docker compose 등 docker 명령과 gradle·mvn·npm 빌드를 실행하지 마세요.
              빌드와 실행은 워커가 빌드 로그를 남기며 직접 합니다. 파일을 읽고 Dockerfile을 쓰는 것으로 끝내세요.
            - 워커는 BuildKit이 없는 구형 빌더(docker build, buildx 없음)로 빌드합니다. BuildKit 전용 문법은 쓰지 마세요:
              RUN --mount(캐시·시크릿 마운트), COPY --link, COPY/ADD --chmod, heredoc(RUN <<EOF), ADD --checksum,
              TARGETARCH·BUILDPLATFORM 같은 자동 빌드 인자. 파일 권한은 RUN chmod로, 소유자는 COPY --chown으로 지정하세요.
            """;

    private static final String DEFAULT_DOCKERFILE_PROMPT = """
            현재 디렉토리 프로젝트를 컨테이너로 실행할 production용 Dockerfile을
            현재 디렉토리 루트에 생성하세요. 멀티스테이지 빌드, EXPOSE 포트 명시,
            Dockerfile 외 파일 생성 금지. 완료 후 내용을 출력하세요.
            """;

    /** 템플릿(비면 기본값)의 placeholder를 채우고 끝에 빌드 금지 규칙을 붙인다. */
    static String buildDockerfilePrompt(String tpl, String githubRepo, String branch) {
        String base = (tpl == null || tpl.isBlank()) ? DEFAULT_DOCKERFILE_PROMPT : tpl;
        return base
                .replace("{github_repo}", githubRepo)
                .replace("{branch}", branch)
                + DOCKERFILE_NO_BUILD_RULE;
    }

    private void generateDockerfile(WorkerTaskResponse task, File worktree, StringBuilder log)
            throws Exception {
        String prompt = buildDockerfilePrompt(props.deploy().dockerfilePromptTemplate(),
                task.githubRepo(), task.headBranch());
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
