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

        // 1. build (이미지에도 라벨 부착 → GC가 소유 이미지를 식별 가능)
        List<String> build = new ArrayList<>(List.of(DOCKER, "build", "-t", spec.imageName()));
        spec.labels().forEach((k, v) -> { build.add("--label"); build.add(k + "=" + v); });
        build.add(".");
        logBuf.append("$ ").append(String.join(" ", build)).append('\n');
        logBuf.append(ProcessRunner.requireSuccess(spec.contextDir().toFile(), build, buildTimeoutSec));

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

        // 5. 헬스체크: (a) 컨테이너 생존(크래시 감지) + (b) HTTP readiness(실제 서빙 확인).
        //    매초 컨테이너 생존을 확인하다 기동 직후 종료되면 배포실패로 간주하고 로그를 첨부한다.
        //    동시에 readinessPath로 HTTP 요청을 보내, 응답이 오면(어떤 상태코드든) 즉시 배포완료.
        //    NOTE: bare TCP 연결 체크가 아니라 실제 HTTP 라운드트립을 쓴다 — docker-proxy가 컨테이너
        //    앱 LISTEN 전에 호스트 포트를 바인딩해도, 업스트림 포트가 닫혀 있으면 HTTP는 reset되어
        //    false-positive가 아니다. 끝까지 HTTP 응답이 없어도 컨테이너가 살아있으면(비-HTTP 앱
        //    가능성) liveness 기준으로 배포완료 처리한다(false-negative 회피).
        int graceSec = cfg.healthCheckSeconds();
        int readySec = cfg.readinessSeconds();
        String readyPath = cfg.readinessPath();
        int limitSec = Math.max(graceSec, readySec);
        if (limitSec > 0) {
            logBuf.append("\n[헬스체크: 최대 ").append(limitSec)
                  .append("초 — 생존 확인 + HTTP readiness(").append(readyPath).append(")]\n");
            boolean httpReady = false;
            for (int i = 0; i < limitSec; i++) {
                Thread.sleep(1000);
                ContainerState st = inspectState(spec.containerName());
                if (!st.running()) {
                    String clog = dockerLogsTail(spec.containerName(), 4000);
                    logBuf.append("[헬스체크 실패: 컨테이너가 기동 직후 종료 (exit=")
                          .append(st.exitCode()).append(", ").append(i + 1).append("초)]\n")
                          .append("--- container logs ---\n").append(clog).append('\n');
                    throw new DeployFailedException(
                            "컨테이너가 기동 직후 종료됨 (exit=" + st.exitCode()
                                    + "). 앱 부팅 실패 가능 — 컨테이너 로그 확인.",
                            tail(logBuf.toString(), 8000));
                }
                if (httpProbe(hostPort, readyPath)) {
                    httpReady = true;
                    logBuf.append("[헬스체크 통과: ").append(i + 1)
                          .append("초 — HTTP readiness 확인(").append(readyPath).append(")]\n");
                    break;
                }
            }
            if (!httpReady) {
                logBuf.append("[헬스체크: ").append(limitSec)
                      .append("초간 생존했으나 HTTP 응답 미확인 — liveness 기준으로 배포완료 처리(비-HTTP 앱 가능)]\n");
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

    /**
     * 호스트 포트에서 실제 HTTP 응답이 오는지(=서빙 준비됨) 확인.
     * 어떤 상태코드든(200/302/401/404/500…) 응답을 받으면 서버가 떠서 서빙 중이라는 뜻.
     * 연결 거부/리셋/타임아웃은 아직 준비 안 됨으로 본다(예외 → false).
     */
    private boolean httpProbe(int hostPort, String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) java.net.URI
                    .create("http://" + cfg.publicHost() + ":" + hostPort + p).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            return c.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
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
