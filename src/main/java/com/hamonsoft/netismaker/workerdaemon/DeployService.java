package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 배포 단계 오케스트레이션 (워커):
 *   1) head 브랜치 fetch + deploy worktree 체크아웃
 *   1.5) 카탈로그에 미리 빌드가 켜졌으면(task.prebuild) 워커에서 빌드해 산출물을 .netis-prebuild/로 (스펙 2026-10-28 §5.2)
 *   2) Dockerfile 있으면 사용, 없으면 claude로 생성(미리 빌드면 산출물 안내 포함 + .dockerignore를 산출물만 보내도록 씀)
 *      — 생성했으면 FROM 이미지가 레지스트리에 있는지 확인해 없으면 한 번 다시 생성, 그래도 없으면 배포실패
 *      — 생성했으면 끝에 JVM 기본 로케일 ko_KR(JAVA_TOOL_OPTIONS)을 붙인다(Dockerfile이 정했으면 그대로)
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
    /** 미리 빌드 실행기. null이면(실행기 없이 만든 서비스) prebuild 지시가 온 배포를 명확히 실패시킨다. */
    private final DeployPrebuilder prebuilder;

    /** 미리 빌드 실행기 없이 만든다 — 미리 빌드가 필요 없는 구성·테스트용. */
    public DeployService(WorkerProperties props, GitRepoCache repos,
                         WorktreeService worktrees, ClaudeExecAdapter claude,
                         DeployTarget target) {
        this(props, repos, worktrees, claude, target, null);
    }

    @Autowired
    public DeployService(WorkerProperties props, GitRepoCache repos,
                         WorktreeService worktrees, ClaudeExecAdapter claude,
                         DeployTarget target, DeployPrebuilder prebuilder) {
        this.props = props;
        this.repos = repos;
        this.worktrees = worktrees;
        this.claude = claude;
        this.target = target;
        this.prebuilder = prebuilder;
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

            // 미리 빌드 — Dockerfile 확인·생성보다 먼저(생성 프롬프트가 산출물 이름을 쓴다).
            // 출력 줄은 전부 화면 배포 로그(logSink)로 흘리고, 결과 로그(log → deploy_log)에는 실행기의 요약 줄([미리 빌드…)만 남긴다
            // — 빌드 출력 전체는 상한이 없다. 실패하면 PrebuildException의 꼬리 8000자를 한 번만 붙인다.
            WorkerTaskResponse.PrebuildSpec prebuild = task.prebuild();
            DeployPrebuilder.Staged staged = null;
            if (prebuild != null) {
                if (prebuilder == null) {
                    throw new DeployException("미리 빌드 실행기가 없음", log.toString());
                }
                try {
                    staged = prebuilder.run(prebuild, wt.toPath(), line -> {
                        if (line.startsWith("[미리 빌드")) log.append(line).append('\n');
                        logSink.accept(line);
                    });
                } catch (DeployPrebuilder.PrebuildException e) {
                    throw new DeployException(e.getMessage(), log + e.getLogTail());
                }
            }

            if (!Files.exists(dockerfile)) {
                log.append("[Dockerfile 없음 → claude 생성]\n");
                String section = staged == null ? null
                        : DeployPrebuilder.promptSection(staged.fileName(), prebuild.artifact());
                String prompt = buildDockerfilePrompt(props.deploy().dockerfilePromptTemplate(),
                        task.githubRepo(), task.headBranch(), section);
                // 재생성까지 합쳐 dockerfile-timeout 한 번 — API 배포중 회수 판정(70분)이 이 상한을 한 번만 센다.
                Duration budget = props.deploy().dockerfileTimeout();
                long started = System.nanoTime();
                generateDockerfile(prompt, wt, log, budget);
                List<String> missing = missingBaseImages(dockerfile, log);
                if (!missing.isEmpty()) {
                    String names = String.join(", ", missing);
                    Duration left = budget.minusNanos(System.nanoTime() - started);
                    if (left.compareTo(MIN_REGENERATE_TIME) < 0) {
                        throw new DeployException("베이스 이미지가 레지스트리에 없음: " + names
                                + " (다시 생성할 시간이 남지 않음)", log.toString());
                    }
                    log.append("[베이스 이미지 없음] ").append(names).append(" → claude 다시 생성\n");
                    generateDockerfile(prompt + regenerateSection(missing), wt, log, left);
                    missing = missingBaseImages(dockerfile, log);
                    if (!missing.isEmpty()) {
                        throw new DeployException("베이스 이미지가 레지스트리에 없음: " + String.join(", ", missing),
                                log.toString());
                    }
                }
                // 생성(재생성 포함)이 끝난 뒤에 붙인다 — 다시 만든 Dockerfile에도 들어가게.
                var localized = DockerfileSupport.appendJavaLocale(Files.readString(dockerfile));
                if (localized.isPresent()) {
                    Files.writeString(dockerfile, localized.get());
                    log.append("[로케일] JAVA_TOOL_OPTIONS=-Duser.language=ko -Duser.country=KR 추가\n");
                } else {
                    log.append("[로케일] Dockerfile이 이미 정함 — 그대로 둠\n");
                }
                if (staged != null) {
                    // 생성이 끝난 뒤에 쓴다 — 생성 claude가 만든 .dockerignore보다 워커 것이 이긴다.
                    Path ignore = wt.toPath().resolve(".dockerignore");
                    boolean replaced = Files.exists(ignore);
                    Files.writeString(ignore, DeployPrebuilder.DOCKERIGNORE);
                    log.append("[빌드 컨텍스트] .dockerignore를 미리 빌드 산출물만 보내도록 씀")
                            .append(replaced ? " (레포의 .dockerignore 교체)" : "").append('\n');
                }
            } else {
                log.append("[기존 Dockerfile 사용]\n");
            }
            if (staged != null && !DeployPrebuilder.dockerfileUsesStage(Files.readString(dockerfile))) {
                log.append("[경고] Dockerfile이 ").append(DeployPrebuilder.STAGE_DIR)
                        .append("/를 쓰지 않음 — 미리 빌드 산출물이 이미지에 들어가지 않을 수 있음\n");
            }

            int containerPort = DockerfileSupport.parseExposedPort(Files.readString(dockerfile))
                    .orElse(props.deploy().defaultContainerPort());
            log.append("[containerPort=").append(containerPort).append("]\n");

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
                    imageName(task.id(), task.headSha()),
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

    /** 이미지 태그·산출물 파일 이름 공용 — head_sha 앞 7자, 없으면 latest. */
    public static String shortSha(String headSha) {
        return headSha == null ? "latest" : headSha.substring(0, Math.min(7, headSha.length()));
    }

    /** 배포 이미지 이름 netis-task-{id}:{head_sha 7자|latest} — 종료 산출물 단계(ArtifactPublisher)가 같은 이름을 쓴다. */
    public static String imageName(long taskId, String headSha) {
        return "netis-task-" + taskId + ":" + shortSha(headSha);
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
     * 물려받아 원격에서 빌드가 돌다 timeout에 걸렸다(2026-10-08 작업 9). `docker manifest inspect`만 예외로 연다 —
     * 이미지를 받지 않는 레지스트리 조회라 데몬을 쓰지 않고, 없는 태그를 지어내는 것(작업 18·23)을 막는 데 필요하다.
     */
    static final String DOCKERFILE_NO_BUILD_RULE = """

            ## 금지 (필수)
            - docker build, docker run, docker compose 등 docker 명령과 gradle·mvn·npm 빌드를 실행하지 마세요.
              빌드와 실행은 워커가 빌드 로그를 남기며 직접 합니다. 파일을 읽고 Dockerfile을 쓰는 것으로 끝내세요.
              예외는 하나 — FROM에 쓸 이미지:태그가 레지스트리에 있는지 `docker manifest inspect <이미지:태그>`로 확인하는 것만 됩니다.
            - 워커는 BuildKit이 없는 구형 빌더(docker build, buildx 없음)로 빌드합니다. BuildKit 전용 문법은 쓰지 마세요:
              RUN --mount(캐시·시크릿 마운트), COPY --link, COPY/ADD --chmod, heredoc(RUN <<EOF), ADD --checksum,
              TARGETARCH·BUILDPLATFORM 같은 자동 빌드 인자. 파일 권한은 RUN chmod로, 소유자는 COPY --chown으로 지정하세요.

            ## 베이스 이미지 (필수)
            - FROM의 이미지:태그는 레지스트리에 실제로 있는 것만 쓰세요. 버전 번호를 조합해 태그를 지어내지 말고
              (예: 없는 태그 `tomcat:7.0.109-jre8`), 확실하지 않으면 위 명령으로 확인하세요.
            - 워커가 빌드 전에 FROM 이미지를 확인합니다. 없으면 한 번 다시 만들게 하고, 그래도 없으면 배포가 실패합니다.
            """;

    private static final String DEFAULT_DOCKERFILE_PROMPT = """
            현재 디렉토리 프로젝트를 컨테이너로 실행할 production용 Dockerfile을
            현재 디렉토리 루트에 생성하세요. 멀티스테이지 빌드, EXPOSE 포트 명시,
            Dockerfile 외 파일 생성 금지. 완료 후 내용을 출력하세요.
            """;

    /** 템플릿(비면 기본값)의 placeholder를 채우고 끝에 빌드 금지 규칙을 붙인다. */
    static String buildDockerfilePrompt(String tpl, String githubRepo, String branch) {
        return buildDockerfilePrompt(tpl, githubRepo, branch, null);
    }

    /** prebuildSection(미리 빌드 안내)이 null·빈 값이면 3인자와 같은 문자열, 아니면 템플릿 뒤·빌드 금지 규칙 앞에 넣는다. */
    static String buildDockerfilePrompt(String tpl, String githubRepo, String branch, String prebuildSection) {
        String base = (tpl == null || tpl.isBlank()) ? DEFAULT_DOCKERFILE_PROMPT : tpl;
        String filled = base
                .replace("{github_repo}", githubRepo)
                .replace("{branch}", branch);
        if (prebuildSection != null && !prebuildSection.isBlank()) {
            filled += (filled.endsWith("\n") ? "" : "\n") + "\n" + prebuildSection
                    + (prebuildSection.endsWith("\n") ? "" : "\n");
        }
        return filled + DOCKERFILE_NO_BUILD_RULE;
    }

    /**
     * 베이스 이미지가 레지스트리에 없을 때 같은 프롬프트 끝에 붙이는 재생성 지시 — 생성 claude가 버전 조합으로 태그를
     * 지어내 docker build가 첫 줄에서 실패했다(2026-11-02 작업 23 `tomcat:7.0.109-jre8-openjdk`, 10-28 작업 18).
     */
    static String regenerateSection(List<String> missing) {
        return """

                ## 다시 생성 (필수)
                - 방금 쓴 Dockerfile의 FROM 이미지가 레지스트리에 없습니다: %s
                  (워커의 `docker manifest inspect` 결과 no such manifest — 그 태그가 없다는 뜻입니다.)
                - 실제로 있는 이미지:태그로 FROM을 고쳐 Dockerfile을 다시 쓰세요. 다른 내용은 필요할 때만 바꾸세요.
                - 고른 태그는 `docker manifest inspect <이미지:태그>`로 있는지 확인하고 쓰세요.
                """.formatted(String.join(", ", missing));
    }

    /** 다시 생성할 남은 시간이 이보다 짧으면 재생성하지 않고 실패시킨다 — 짧은 상한으로 부른 claude는 대개 시간 초과로 끝난다. */
    static final Duration MIN_REGENERATE_TIME = Duration.ofMinutes(1);

    private void generateDockerfile(String prompt, File worktree, StringBuilder log,
                                    Duration timeout) throws Exception {
        ClaudeExecAdapter.ExecResult exec = claude.exec(prompt, worktree, timeout, List.of(), true);
        log.append(tail(exec.stdout(), 2000)).append('\n');
        if (exec.exitCode() != 0) {
            throw new DeployException("Dockerfile 생성 claude exit=" + exec.exitCode(),
                    log.toString());
        }
        if (!Files.exists(worktree.toPath().resolve("Dockerfile"))) {
            throw new DeployException("claude가 Dockerfile을 생성하지 못함", log.toString());
        }
    }

    /**
     * 생성한 Dockerfile의 FROM 이미지를 배포 타깃에 확인해 한 줄로 남기고, 레지스트리에 없는 것이 확실한 이미지만 돌려준다.
     * 확인 못 함(인증·네트워크·null)은 통과 — 판단을 docker build에 맡긴다.
     */
    private List<String> missingBaseImages(Path dockerfile, StringBuilder log) throws java.io.IOException {
        List<String> images = DockerfileSupport.parseBaseImages(Files.readString(dockerfile));
        List<String> missing = new ArrayList<>();
        if (images.isEmpty()) return missing;
        StringJoiner line = new StringJoiner(", ", "[베이스 이미지] ", "\n");
        for (String image : images) {
            DeployTarget.ImageAvailability a = target.checkImage(image);
            if (a == null) a = DeployTarget.ImageAvailability.UNKNOWN;
            if (a == DeployTarget.ImageAvailability.MISSING) missing.add(image);
            line.add(image + "=" + switch (a) {
                case PRESENT -> "있음";
                case MISSING -> "없음";
                case UNKNOWN -> "확인 못 함";
            });
        }
        log.append(line);
        return missing;
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
