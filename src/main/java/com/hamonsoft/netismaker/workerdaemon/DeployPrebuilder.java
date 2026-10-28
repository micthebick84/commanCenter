package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.util.StackSecretEnv;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 배포 전 워커 미리 빌드 (스펙 2026-10-28 §5.2 2–3단계). 관리자가 카탈로그에 둔 빌드 명령을 deploy worktree에서
 * 워커 셸로 돌리고(스택 비밀 env 제거, 설정된 툴체인의 JAVA_HOME·PATH), 산출물 정확히 1개를 {@link #STAGE_DIR}로 옮긴다.
 * 배포 흐름에 끼우는 것(Dockerfile 생성 프롬프트·.dockerignore)은 DeployService가 한다.
 */
@Slf4j
@Component
@Profile("worker")
public class DeployPrebuilder {

    /** worktree 루트 기준 스테이징 폴더. deploy worktree는 커밋하지 않으므로 info/exclude에 넣지 않는다. */
    static final String STAGE_DIR = ".netis-prebuild";
    /** Dockerfile을 생성했을 때 빌드 컨텍스트를 스테이징 폴더로만 좁히는 .dockerignore. */
    static final String DOCKERIGNORE = "*\n!.netis-prebuild/\n!.netis-prebuild/**\n";

    /** deploy_log에 남길 출력 꼬리 — LocalDockerTarget의 build 실패와 같은 8000자. */
    private static final int LOG_TAIL_CHARS = 8000;
    /** 출력 버퍼 상한 — 긴 mvn 출력을 통째로 들고 있지 않게, 넘으면 앞을 버리고 꼬리 여유분만 남긴다. */
    private static final int BUFFER_MAX_CHARS = 64_000;
    private static final int MAX_LISTED = 5;

    private final PrebuildProperties props;

    public DeployPrebuilder(PrebuildProperties props) {
        this.props = props;
    }

    /** 기동 시 한 번 — 설정된 툴체인만 보여 준다(경로가 비어 꺼진 항목은 빼고). */
    @PostConstruct
    void logToolchains() {
        String configured = props.toolchains().keySet().stream().sorted()
                .flatMap(name -> props.configured(name).stream()
                        .map(tc -> name + "(java=" + tc.javaHome() + ")"))
                .collect(Collectors.joining(", "));
        log.info("미리 빌드 도구: {}", configured.isEmpty() ? "없음" : configured);
    }

    public record Staged(String fileName, long sizeBytes, String sourceRelative) {}

    /** 미리 빌드 실패. 메시지 = 배포실패 사유, logTail = deploy_log에 남길 출력 꼬리. */
    public static class PrebuildException extends Exception {
        private final String logTail;

        public PrebuildException(String message, String logTail) {
            super(message);
            this.logTail = logTail == null ? "" : logTail;
        }

        PrebuildException(String message) {
            this(message, "");
        }

        public String getLogTail() { return logTail; }
    }

    /**
     * 명령을 돌리고 산출물을 {@link #STAGE_DIR}로 복사한다. 명령 출력은 sink로 그대로 흘린다(배포 로그 실시간 표시).
     * 실패 사유의 앞부분은 고정(스펙 §5.2) — DeployService가 그대로 배포실패 사유로 쓴다.
     */
    public Staged run(WorkerTaskResponse.PrebuildSpec spec, Path worktree, Consumer<String> sink)
            throws PrebuildException {
        String toolName = spec.toolchain() == null || spec.toolchain().isBlank() ? null : spec.toolchain().trim();
        Map<String, String> extraEnv = Map.of();
        if (toolName != null) {
            Optional<PrebuildProperties.Toolchain> tc = props.configured(toolName);
            if (tc.isEmpty()) throw new PrebuildException("미리 빌드 도구 '" + toolName + "'가 이 워커에 설정되지 않음");
            extraEnv = toolchainEnv(tc.get(), System.getenv("PATH"), File.pathSeparator);
        }

        StringBuilder buf = new StringBuilder();
        Consumer<String> both = line -> {
            synchronized (buf) {
                buf.append(line).append('\n');
                if (buf.length() > BUFFER_MAX_CHARS) buf.delete(0, buf.length() - 2 * LOG_TAIL_CHARS);
            }
            sink.accept(line);
        };
        // 머리줄은 화면(sink)으로만 — DeployService가 "[미리 빌드" 줄을 deploy_log에 남기므로 꼬리에도 넣으면 두 번 적힌다.
        sink.accept("[미리 빌드] $ " + spec.command() + " (도구=" + (toolName == null ? "워커 env" : toolName) + ")");

        Duration timeout = props.timeout();
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.runStreaming(worktree.toFile(), shellCommand(spec.command(), isWindows()),
                    extraEnv, removedEnv(), timeout.toSeconds(), both);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.startsWith("process timeout")) {
                throw new PrebuildException("미리 빌드 시간 초과(" + describe(timeout) + ")", tail(buf));
            }
            throw new PrebuildException("미리 빌드 실행 실패: " + msg, tail(buf));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrebuildException("미리 빌드 중단(인터럽트)", tail(buf));
        }
        if (result.exitCode() != 0) {
            throw new PrebuildException("미리 빌드 실패 (exit=" + result.exitCode() + ")", tail(buf));
        }

        Path artifact;
        try {
            artifact = resolveArtifact(worktree, spec.artifact());
        } catch (PrebuildException e) {
            throw new PrebuildException(e.getMessage(), tail(buf));
        }
        String fileName = artifact.getFileName().toString();
        String sourceRelative = worktree.relativize(artifact).toString().replace('\\', '/');
        long size;
        try {
            Path stage = Files.createDirectories(worktree.resolve(STAGE_DIR));
            Path target = stage.resolve(fileName);
            Files.copy(artifact, target, StandardCopyOption.REPLACE_EXISTING);
            size = Files.size(target);
        } catch (IOException e) {
            throw new PrebuildException("미리 빌드 산출물 복사 실패: " + e.getMessage(), tail(buf));
        }
        sink.accept("[미리 빌드 산출물] " + sourceRelative + " ("
                + String.format(Locale.ROOT, "%.1f", size / (1024.0 * 1024.0)) + "MB) → " + STAGE_DIR + "/" + fileName);
        return new Staged(fileName, size, sourceRelative);
    }

    /**
     * 관리자 명령을 워커 OS 셸로. /d: AutoRun 끔, /s: 따옴표 규칙 고정(명령 앞뒤 따옴표만 벗긴다).
     * Windows는 명령을 따옴표 한 쌍으로 직접 감싼다 — cmd /s가 벗길 바깥 쌍. 감싸지 않으면 앞뒤가 모두 {@code "}인 명령
     * ({@code "C:\x\where.exe" "cmd.exe"})을 Java가 이미 따옴표 친 인자로 보고 그대로 넘겨, cmd가 명령 자체의 따옴표를 벗긴다.
     */
    static List<String> shellCommand(String command, boolean windows) {
        return windows ? List.of("cmd.exe", "/d", "/s", "/c", "\"" + command + "\"") : List.of("sh", "-c", command);
    }

    /** 명령 env에서 뺄 이름 — 스택 비밀(대소문자 무시, ProcessRunner.applyEnv). */
    static Set<String> removedEnv() {
        return Set.copyOf(StackSecretEnv.NAMES);
    }

    /**
     * 툴체인 env: JAVA_HOME=javaHome, PATH = javaHome/bin + tc.path의 조각 + 현재 PATH(빈 조각은 버린다).
     * tc.path는 pathSeparator로만 나눈다 — Windows에서 ':'로 나누면 드라이브 문자(C:)가 쪼개진다.
     */
    static Map<String, String> toolchainEnv(PrebuildProperties.Toolchain tc, String currentPath, String pathSeparator) {
        String home = tc.javaHome().trim();
        boolean backslash = home.contains("\\") && !home.contains("/");
        String sep = backslash ? "\\" : "/";
        while (home.length() > 1 && (home.endsWith("/") || home.endsWith("\\"))) home = home.substring(0, home.length() - 1);

        List<String> parts = new ArrayList<>();
        parts.add(home + sep + "bin");
        if (tc.path() != null) {
            Arrays.stream(tc.path().split(Pattern.quote(pathSeparator)))
                    .map(String::trim).filter(s -> !s.isEmpty()).forEach(parts::add);
        }
        if (currentPath != null && !currentPath.isBlank()) parts.add(currentPath);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", home);
        env.put("PATH", String.join(pathSeparator, parts));
        return env;
    }

    /**
     * 산출물 경로를 펼친다. '\'는 '/'로 바꾸고, 마지막 조각만 glob(상위 폴더는 그대로). 링크를 따라가지 않고 일반 파일만 센다
     * — 같은 이름의 폴더(target/hm-dash/)가 함께 맞아도 파일만 고른다. 정확히 1개가 아니면 사유와 함께 실패.
     */
    static Path resolveArtifact(Path root, String pattern) throws PrebuildException {
        String normalized = pattern == null ? "" : pattern.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String parent = slash < 0 ? "" : normalized.substring(0, slash);
        String name = normalized.substring(slash + 1);
        if (name.isEmpty()) throw new PrebuildException("미리 빌드 산출물 없음: " + normalized);

        Path base = root.toAbsolutePath().normalize();
        Path dir;
        try {
            dir = (parent.isEmpty() ? base : base.resolve(parent)).normalize();
        } catch (InvalidPathException e) {   // Windows에서 상위 조각에 ?<>|" 등
            throw new PrebuildException("미리 빌드 산출물 없음: " + normalized);
        }
        if (!dir.startsWith(base) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PrebuildException("미리 빌드 산출물 없음: " + normalized);
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + name);
        } catch (RuntimeException e) {   // 잘못된 glob(닫히지 않은 '[' 등)
            throw new PrebuildException("미리 빌드 산출물 없음: " + normalized);
        }
        List<Path> files = new ArrayList<>();
        boolean anyMatch = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (!matcher.matches(entry.getFileName())) continue;
                anyMatch = true;
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) files.add(entry);
            }
        } catch (IOException e) {
            throw new PrebuildException("미리 빌드 산출물 없음: " + normalized + " (" + e.getMessage() + ")");
        }
        if (files.size() == 1) return files.get(0);
        if (files.isEmpty()) {
            throw new PrebuildException((anyMatch ? "미리 빌드 산출물이 일반 파일이 아님: " : "미리 빌드 산출물 없음: ")
                    + normalized);
        }
        List<String> names = files.stream().map(p -> p.getFileName().toString()).sorted().toList();
        String listed = String.join(", ", names.subList(0, Math.min(MAX_LISTED, names.size())))
                + (names.size() > MAX_LISTED ? ", …" : "");
        throw new PrebuildException("미리 빌드 산출물이 여러 개: " + listed);
    }

    /** Dockerfile 생성 프롬프트 덧붙임(스펙 §5.3 본문 그대로) — 미리 빌드가 켜졌을 때만 DeployService가 붙인다. */
    static String promptSection(String stagedName, String artifactPattern) {
        return "## 미리 빌드된 산출물 (필수)\n"
                + "- 이 절은 위의 Dockerfile 작성 지침(멀티스테이지 빌드 등)보다 우선합니다.\n"
                + "- 워커가 이미 빌드했습니다: `" + STAGE_DIR + "/" + stagedName + "` (원래 위치 `" + artifactPattern + "`).\n"
                + "- Dockerfile에 빌드 단계(mvn·gradle·npm 등)와 빌드 도구 이미지를 넣지 마세요. 런타임 이미지 하나에 이 파일을 COPY만 하세요.\n"
                + "- 빌드 컨텍스트에는 `.netis-prebuild/`만 들어갑니다. 레포의 다른 파일을 COPY하지 마세요.\n"
                + "- war라면 Tomcat `webapps/ROOT.war`로 복사하고 이미지 안에서 풀지 마세요(JRE 이미지에는 jar 명령이 없습니다).\n"
                + "- 런타임 버전(JRE·Tomcat 등)은 레포의 빌드 설정(pom 등)을 읽고 맞추세요.\n";
    }

    /** Dockerfile이 스테이징 폴더를 쓰는지 — 안 쓰면 DeployService가 경고한다(미리 빌드 산출물이 이미지에 안 들어감). */
    static boolean dockerfileUsesStage(String dockerfileText) {
        return dockerfileText != null && dockerfileText.contains(STAGE_DIR);
    }

    /** 출력 꼬리 — 잘렸으면 "…" + 끝 8000자(LocalDockerTarget.tail과 같은 규칙). */
    static String tail(StringBuilder buf) {
        synchronized (buf) {
            return buf.length() <= LOG_TAIL_CHARS ? buf.toString()
                    : "…" + buf.substring(buf.length() - LOG_TAIL_CHARS);
        }
    }

    /** 시간 상한 표기 — 분 단위면 "N분", 아니면 "N초"(시험용 짧은 상한). */
    private static String describe(Duration d) {
        long s = d.toSeconds();
        return s % 60 == 0 ? (s / 60) + "분" : s + "초";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
