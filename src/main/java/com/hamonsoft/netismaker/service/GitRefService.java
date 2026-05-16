package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.BranchListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * git ls-remote 어댑터 — 작업 등록 폼에 보여줄 브랜치 목록 조회.
 *
 * 한 번의 git 호출(--symref)로 HEAD 심볼릭 ref + 모든 refs를 가져온 뒤
 * refs/heads/* 만 필터링. 태그/PR/notes는 제외.
 *
 * 캐시 없음 (Phase 1). 동일 입력 즉시 재호출 가능.
 *
 * 보안: repo 인자는 컨트롤러에서 정규식으로 사전 검증되므로 셸 주입 위험 없음.
 * ProcessBuilder는 셸을 거치지 않고 직접 exec.
 */
@Service
@Profile("api")
@Slf4j
public class GitRefService {

    private static final Pattern REPO_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final long TIMEOUT_SECONDS = 10;

    private final String githubPat;

    public GitRefService(@Value("${app.github.pat:}") String githubPat) {
        this.githubPat = githubPat;
    }

    public BranchListResponse listBranches(String repo) {
        if (repo == null || !REPO_PATTERN.matcher(repo).matches()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "'owner/repo' 형식이어야 합니다");
        }
        String url = buildUrl(repo);

        ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", "--symref", url)
                .redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "git 실행 불가: " + e.getMessage());
        }

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = readAsync(p.getInputStream(), out);
        Thread tErr = readAsync(p.getErrorStream(), err);

        boolean finished;
        try {
            finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new TaskException(HttpStatus.GATEWAY_TIMEOUT, "git ls-remote 인터럽트");
        }
        if (!finished) {
            p.destroyForcibly();
            throw new TaskException(HttpStatus.GATEWAY_TIMEOUT,
                    "git ls-remote 시간 초과 (" + TIMEOUT_SECONDS + "초)");
        }
        try { tOut.join(2000); tErr.join(2000); } catch (InterruptedException ignored) {}

        if (p.exitValue() != 0) {
            String emsg = err.toString();
            if (emsg.contains("Repository not found")
                    || emsg.contains("not found")
                    || emsg.contains("Authentication")
                    || emsg.contains("could not read Username")) {
                throw new TaskException(HttpStatus.NOT_FOUND,
                        "레포를 찾을 수 없거나 비공개 레포입니다. 비공개 레포는 사내 GitHub PAT 등록이 필요합니다.");
            }
            log.warn("git ls-remote 실패 repo={} stderr={}", repo, emsg);
            throw new TaskException(HttpStatus.BAD_GATEWAY,
                    "git ls-remote 실패: " + emsg.strip());
        }

        return parse(repo, out.toString());
    }

    private String buildUrl(String repo) {
        if (githubPat == null || githubPat.isBlank()) {
            return "https://github.com/" + repo + ".git";
        }
        return "https://oauth2:" + githubPat + "@github.com/" + repo + ".git";
    }

    private static BranchListResponse parse(String repo, String stdout) {
        String defaultBranch = null;
        List<BranchListResponse.BranchEntry> branches = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith("ref: ")) {
                // 예: "ref: refs/heads/main\tHEAD" → defaultBranch=main
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                String ref = line.substring("ref: ".length(), tab);
                if (ref.startsWith("refs/heads/")) {
                    defaultBranch = ref.substring("refs/heads/".length());
                }
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String sha = line.substring(0, tab);
            String ref = line.substring(tab + 1);
            if (!ref.startsWith("refs/heads/")) continue;
            String name = ref.substring("refs/heads/".length());
            branches.add(new BranchListResponse.BranchEntry(name, sha));
        }
        return new BranchListResponse(repo, defaultBranch, branches, OffsetDateTime.now());
    }

    private static Thread readAsync(InputStream is, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.append(line).append('\n');
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
