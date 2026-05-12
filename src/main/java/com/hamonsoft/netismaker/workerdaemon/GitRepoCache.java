package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 *  레포 영구 캐시.
 *
 *   ~/netis-maker/repos/{owner}/{repo}/  (DESIGN P8)
 *
 *   첫 회: git clone https://oauth2:$PAT@github.com/{owner}/{repo}.git
 *   이후: cd <dir> && git fetch && git checkout <branch> && git pull
 *
 *  반환값: ensureFresh가 작업 디렉토리 + 현재 commit SHA를 반환.
 */
@Component
@Profile("worker")
@Slf4j
public class GitRepoCache {

    private static final long GIT_TIMEOUT_SECONDS = 600;

    private final WorkerProperties props;

    public GitRepoCache(WorkerProperties props) {
        this.props = props;
    }

    public CheckedOutRepo ensureFresh(String githubRepo, String branch) throws IOException, InterruptedException {
        if (!githubRepo.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) {
            throw new IllegalArgumentException("invalid github_repo: " + githubRepo);
        }
        Path target = Paths.get(props.reposDir(), githubRepo);
        Files.createDirectories(target.getParent());

        if (!Files.exists(target.resolve(".git"))) {
            String url = repoUrl(githubRepo);
            run(target.getParent().toFile(), "git", "clone", "--depth=1", "--branch", branch, url, target.getFileName().toString());
        } else {
            run(target.toFile(), "git", "fetch", "--all", "--prune");
            run(target.toFile(), "git", "checkout", branch);
            run(target.toFile(), "git", "reset", "--hard", "origin/" + branch);
        }

        String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
        return new CheckedOutRepo(target.toFile(), sha);
    }

    // PAT가 있으면 인증 URL, 없으면 익명 URL (public repo).
    // 빈 비밀번호 형태 'https://oauth2:@github.com/...'는 GitHub가 거부하므로 plain URL로 폴백.
    private String repoUrl(String githubRepo) {
        String pat = props.githubPat();
        if (pat == null || pat.isBlank()) {
            return "https://github.com/" + githubRepo + ".git";
        }
        return "https://oauth2:" + pat + "@github.com/" + githubRepo + ".git";
    }

    private void run(File dir, String... command) throws IOException, InterruptedException {
        log.debug("git exec ({}): {}", dir, String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("git timeout: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git failed (" + p.exitValue() + "): " + out);
        }
    }

    private String capture(File dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(false).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return out.toString();
    }

    public record CheckedOutRepo(File dir, String commitSha) {}
}
