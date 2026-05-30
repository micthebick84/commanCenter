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

        // 5. 헬스체크: grace 기간 동안 컨테이너 생존 확인.
        //    기동 직후(앱 부팅 실패 등) 종료되면 배포실패로 간주하고 컨테이너 로그를 첨부한다.
        //    grace 안에 포트가 열리면 정상 기동으로 보고 조기 종료. 끝까지 살아있으면(느린 기동)
        //    배포완료로 처리(포트 미확인은 false-negative 회피).
        int graceSec = cfg.healthCheckSeconds();
        if (graceSec > 0) {
            logBuf.append("\n[헬스체크: 최대 ").append(graceSec).append("초 컨테이너 생존 확인]\n");
            for (int i = 0; i < graceSec; i++) {
                Thread.sleep(1000);
                ContainerState st = inspectState(spec.containerName());
                if (!st.running()) {
                    String clog = dockerLogsTail(spec.containerName(), 4000);
                    logBuf.append("[헬스체크 실패: 컨테이너가 기동 직후 종료 (exit=")
                          .append(st.exitCode()).append(")]\n")
                          .append("--- container logs ---\n").append(clog).append('\n');
                    throw new DeployFailedException(
                            "컨테이너가 기동 직후 종료됨 (exit=" + st.exitCode()
                                    + "). 앱 부팅 실패 가능 — 컨테이너 로그 확인.",
                            tail(logBuf.toString(), 8000));
                }
                if (isPortOpen(hostPort)) {
                    logBuf.append("[포트 ").append(hostPort).append(" 응답 — 정상 기동 확인]\n");
                    break;
                }
            }
        }

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

    /** docker inspect로 컨테이너 실행 상태 + 종료코드 조회. */
    private ContainerState inspectState(String name) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "inspect", "-f", "{{.State.Running}} {{.State.ExitCode}}", name), 30);
            if (r.exitCode() != 0) return new ContainerState(false, -1);
            String[] parts = r.stdout().trim().split("\\s+");
            boolean running = parts.length > 0 && parts[0].equals("true");
            int exit = parts.length > 1 ? parseIntSafe(parts[1]) : -1;
            return new ContainerState(running, exit);
        } catch (Exception e) {
            return new ContainerState(false, -1);
        }
    }

    /** hostPort에 TCP 연결 가능한지 (앱이 LISTEN 시작했는지) 빠르게 확인. */
    private boolean isPortOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String dockerLogsTail(String name, int max) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "logs", "--tail", "100", name), 30);
            return tail(r.stdout(), max);
        } catch (Exception e) {
            return "(docker logs 조회 실패: " + e.getMessage() + ")";
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private record ContainerState(boolean running, int exitCode) {}

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
