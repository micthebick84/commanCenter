package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.ProcessRunner;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 워커 로컬 docker 데몬으로 build/run/stop. DeployTarget MVP 구현.
 *
 *  target=local 일 때만 빈 등록 (기본값). 멀티 워커가 같은 머신이면 docker 데몬을
 *  공유하므로 컨테이너 stop/replace가 어느 워커에서나 일관 동작.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "netis-maker.worker.deploy", name = "target",
        havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalDockerTarget implements DeployTarget {

    private static final String DOCKER = "docker";

    private final WorkerProperties.Deploy cfg;
    private final long buildTimeoutSec;

    public LocalDockerTarget(WorkerProperties props) {
        this.cfg = props.deploy();
        this.buildTimeoutSec = cfg.buildTimeout().toSeconds();
    }

    @Override
    public DeployResult deploy(DeploySpec spec) throws Exception {
        StringBuilder logBuf = new StringBuilder();

        // 1. build
        logBuf.append("$ docker build -t ").append(spec.imageName()).append('\n');
        logBuf.append(ProcessRunner.requireSuccess(spec.contextDir().toFile(),
                List.of(DOCKER, "build", "-t", spec.imageName(), "."),
                buildTimeoutSec));

        // 2. 기존 동일 컨테이너 제거 (교체)
        try {
            ProcessRunner.run(spec.contextDir().toFile(),
                    List.of(DOCKER, "rm", "-f", spec.containerName()), 60);
        } catch (Exception ignore) { /* 없으면 무시 */ }

        // 3. 포트 할당
        int hostPort = PortAllocator.allocate(cfg.portFrom(), cfg.portTo(), dockerPublishedPorts());

        // 4. run
        List<String> run = new ArrayList<>(List.of(
                DOCKER, "run", "-d",
                "--name", spec.containerName(),
                "-p", hostPort + ":" + spec.containerPort()));
        spec.labels().forEach((k, v) -> { run.add("--label"); run.add(k + "=" + v); });
        spec.env().forEach((k, v) -> { run.add("-e"); run.add(k + "=" + v); });
        run.add(spec.imageName());

        logBuf.append("\n$ ").append(String.join(" ", run)).append('\n');
        String runOut = ProcessRunner.requireSuccess(spec.contextDir().toFile(), run, 120);
        logBuf.append(runOut);
        String containerId = runOut.trim();

        String url = "http://" + cfg.publicHost() + ":" + hostPort;
        log.info("배포 완료: container={} url={}", spec.containerName(), url);
        return new DeployResult(url, containerId, hostPort, spec.imageName(),
                tail(logBuf.toString(), 8000));
    }

    @Override
    public void stop(String containerName) throws Exception {
        ProcessRunner.run(new File("."),
                List.of(DOCKER, "rm", "-f", containerName), 60);
        log.info("컨테이너 중지/제거: {}", containerName);
    }

    @Override
    public DeployStatus status(String containerName) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "inspect", "-f", "{{.State.Running}}", containerName), 30);
            if (r.exitCode() != 0) return DeployStatus.STOPPED;
            return r.stdout().trim().equals("true") ? DeployStatus.RUNNING : DeployStatus.STOPPED;
        } catch (Exception e) {
            return DeployStatus.UNKNOWN;
        }
    }

    /** 현재 docker가 호스트에 게시 중인 포트 집합 (할당 충돌 회피). */
    private Set<Integer> dockerPublishedPorts() {
        Set<Integer> ports = new HashSet<>();
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "ps", "--format", "{{.Ports}}"), 30);
            if (r.exitCode() != 0) return ports;
            // 예: "0.0.0.0:19000->8080/tcp, :::19000->8080/tcp"
            for (String line : r.stdout().split("\\R")) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile(":(\\d+)->").matcher(line);
                while (m.find()) ports.add(Integer.parseInt(m.group(1)));
            }
        } catch (Exception e) {
            log.warn("docker ps 포트 조회 실패 (계속): {}", e.getMessage());
        }
        return ports;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
