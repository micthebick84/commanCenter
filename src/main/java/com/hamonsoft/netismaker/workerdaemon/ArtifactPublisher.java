package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.dto.CloseArtifactSpec;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 종료 산출물 꺼내기·업로드 (스펙 2026-10-14 §4). 배포 이미지에서 docker create/cp로 WAR·JAR를 꺼내
 * API에 올린다. docker는 워커 프로세스의 DOCKER_HOST(원격 데몬)를 물려받아 파일이 원격에서 워커 PC로 복사된다.
 */
@Component
@Profile("worker")
@Slf4j
public class ArtifactPublisher {

    /** docker CLI 실행 seam(테스트용). args에 "docker"는 넣지 않는다. */
    interface Docker {
        ProcessRunner.Result run(List<String> args, long timeoutSeconds) throws IOException, InterruptedException;
    }

    /** 사람이 읽는 실패 — 메시지가 그대로 종료실패 사유가 된다. */
    public static final class ArtifactException extends Exception {
        public ArtifactException(String message) {
            super(message);
        }
    }

    public record Published(ArtifactInfo info, boolean reused) {}

    private static final long SHORT_TIMEOUT_SECONDS = 120;

    private final WorkerProperties props;
    private final ArtifactProperties cfg;
    private final WorkerHttpClient http;
    private final Docker docker;

    @Autowired
    public ArtifactPublisher(WorkerProperties props, ArtifactProperties cfg, WorkerHttpClient http) {
        this(props, cfg, http, (args, timeout) -> {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.addAll(args);
            return ProcessRunner.run(null, cmd, timeout);
        });
    }

    ArtifactPublisher(WorkerProperties props, ArtifactProperties cfg, WorkerHttpClient http, Docker docker) {
        this.props = props;
        this.cfg = cfg;
        this.http = http;
        this.docker = docker;
    }

    public Published publish(WorkerTaskResponse task) throws ArtifactException, InterruptedException {
        Optional<ArtifactInfo> existing;
        try {
            existing = http.currentArtifact(task.id());
        } catch (RuntimeException e) {
            throw new ArtifactException("산출물: 보관 여부 조회 실패: " + detail(e));
        }
        if (existing.isPresent()) return new Published(existing.get(), true);   // 업로드 뒤 진행 보고 전에 죽은 재시도

        String image = DeployService.imageName(task.id(), task.headSha());
        inspect(image);
        String path = resolvePath(task, image);
        String name = fileName(task, path);
        Path work = Paths.get(props.reposDir()).resolveSibling("close").resolve("task-" + task.id()).resolve("artifact");
        try {
            Files.createDirectories(work);
            Path local = work.resolve(name);
            copyOut(task.id(), image, path, local);
            String sha = sha256(local);
            return new Published(upload(task.id(), name, image + ":" + path, local, sha), false);
        } catch (IOException e) {   // 작업 폴더·해시 계산 — 이 PC의 파일 문제 (업로드 IOException은 upload가 따로 바꾼다)
            throw new ArtifactException("산출물: 임시 파일 처리 실패 — " + detail(e));
        } finally {
            RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(work);
            if (d.failures() > 0) log.warn("산출물 작업 폴더 정리 실패 {}건: {}", d.failures(), work);
            try {
                Files.deleteIfExists(work.getParent());   // 빈 close/task-{id}만 — 노트 단계의 파일이 있으면 비어 있지 않아 남는다
            } catch (IOException ignore) { /* 비어 있지 않거나 이미 없음 — 정리는 best-effort */ }
        }
    }

    /**
     * API에 올린다. 서버가 본문을 다 읽기 전에 거절하면 Tomcat이 연결을 끊어 상태 코드 대신 IOException(broken pipe 등)이
     * 오므로, 거절 응답이든 끊김이든 같은 종료실패 사유 형식으로 바꾼다.
     */
    private ArtifactInfo upload(long taskId, String name, String source, Path local, String sha)
            throws ArtifactException, InterruptedException {
        try {
            return http.uploadArtifact(taskId, name, source, local, sha, cfg.uploadTimeout());
        } catch (HttpTimeoutException e) {
            throw new ArtifactException("산출물: 업로드 실패 — " + (e instanceof HttpConnectTimeoutException
                    ? "API 서버 연결 시간 초과" : "시간 초과(" + cfg.uploadTimeout().toSeconds() + "초)"));
        } catch (IOException e) {
            throw new ArtifactException("산출물: 업로드 실패 — " + detail(e));
        }
    }

    private void inspect(String image) throws ArtifactException, InterruptedException {
        ProcessRunner.Result r = run(List.of("image", "inspect", "--format", "{{.Id}}", image), SHORT_TIMEOUT_SECONDS);
        if (r.exitCode() != 0) {
            throw new ArtifactException("산출물: 이미지 없음(" + image + ") — 재시도하거나 '산출물 없이 마무리'");
        }
    }

    private String resolvePath(WorkerTaskResponse task, String image) throws ArtifactException, InterruptedException {
        CloseArtifactSpec spec = task.closeArtifact();
        String path;
        if (spec != null && spec.path() != null && !spec.path().isBlank()) {
            path = spec.path().trim();
        } else {
            List<String> args = new ArrayList<>(List.of("run", "--rm", "--entrypoint", "find", image));
            args.addAll(cfg.searchDirs());
            args.addAll(List.of("-maxdepth", "2", "-type", "f", "(", "-name", "*.war", "-o", "-name", "*.jar", ")"));
            List<String> found = candidates(run(args, SHORT_TIMEOUT_SECONDS).stdout());
            if (found.size() != 1) {
                throw new ArtifactException("산출물: 경로를 정할 수 없습니다 — 레포 카탈로그에 이미지 안 산출물 경로를 지정하세요(후보: "
                        + (found.isEmpty() ? "없음" : String.join(", ", found)) + ")");
            }
            path = found.get(0);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".war") && !lower.endsWith(".jar")) {
            throw new ArtifactException("산출물: .war 또는 .jar만 보관합니다: " + path);
        }
        return path;
    }

    private void copyOut(long taskId, String image, String path, Path local) throws ArtifactException, InterruptedException {
        String box = "netis-artifact-" + taskId;
        run(List.of("rm", "-f", box), SHORT_TIMEOUT_SECONDS);   // 지난 시도의 잔여 — 없으면 실패하지만 무시
        try {
            ProcessRunner.Result c = run(List.of("create", "--name", box, "--entrypoint", "true", image), SHORT_TIMEOUT_SECONDS);
            if (c.exitCode() != 0) throw new ArtifactException("산출물: 임시 컨테이너 생성 실패: " + tail(c.stdout()));
            ProcessRunner.Result cp = run(List.of("cp", box + ":" + path, local.toString()), cfg.copyTimeout().toSeconds());
            if (cp.exitCode() != 0 || !Files.isRegularFile(local)) {
                throw new ArtifactException("산출물: 이미지에서 꺼내기 실패(" + path + "): " + tail(cp.stdout()));
            }
        } finally {
            try {
                run(List.of("rm", "-f", box), SHORT_TIMEOUT_SECONDS);
            } catch (ArtifactException e) {
                log.warn("임시 컨테이너 정리 실패: {} {}", box, e.getMessage());
            }
        }
    }

    private ProcessRunner.Result run(List<String> args, long timeoutSeconds) throws ArtifactException, InterruptedException {
        try {
            return docker.run(args, timeoutSeconds);
        } catch (IOException e) {
            throw new ArtifactException("산출물: docker " + args.get(0) + " 실패: " + detail(e));
        }
    }

    /** {레포 경로 마지막 조각}-task-{id}-{sha7}.{war|jar}, 허용 밖 문자는 '_' (API ArtifactStore.validName과 같은 규칙). */
    static String fileName(WorkerTaskResponse task, String path) {
        String repo = task.repoRef().path();
        String last = repo.substring(repo.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.isBlank() || last.startsWith(".")) last = "repo" + last;
        if (last.length() > 100) last = last.substring(0, 100);
        String ext = path.toLowerCase(Locale.ROOT).endsWith(".jar") ? "jar" : "war";
        return last + "-task-" + task.id() + "-" + DeployService.shortSha(task.headSha()) + "." + ext;
    }

    /** find 출력에서 산출물 경로 줄만 — 없는 폴더 오류("find: ...")·빈 줄은 버린다. 종료 코드는 보지 않는다. */
    static List<String> candidates(String stdout) {
        if (stdout == null) return List.of();
        return stdout.lines().map(String::strip)
                .filter(l -> l.startsWith("/") && (l.endsWith(".war") || l.endsWith(".jar")))
                .distinct().toList();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 예외 사유 — 메시지가 없으면 클래스 이름, 자격증명은 가린다. */
    private static String detail(Throwable e) {
        String m = e.getMessage();
        return GitRemotes.mask(m == null || m.isBlank() ? e.getClass().getSimpleName() : m.strip());
    }

    private static String tail(String s) {
        if (s == null) return "";
        String m = GitRemotes.mask(s.strip());
        return m.length() <= 300 ? m : "…" + m.substring(m.length() - 300);
    }
}
