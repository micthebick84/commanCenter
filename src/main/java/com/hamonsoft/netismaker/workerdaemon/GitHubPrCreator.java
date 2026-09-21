package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** gh CLI로 Draft PR 생성. 사전 조건: gh 설치 + (`gh auth login` 또는 GH_TOKEN). */
@Slf4j
public class GitHubPrCreator implements MergeRequestCreator {

    private static final long GH_TIMEOUT_SECONDS = 120;
    private static final Pattern PR_URL_PATTERN = Pattern.compile("https?://[\\w./-]*/pull/(\\d+)");

    private final String githubPat;

    public GitHubPrCreator(String githubPat) {
        this.githubPat = githubPat;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return !ref.isGitlab();
    }

    @Override
    public GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                       String title, String body) throws IOException, InterruptedException {
        List<String> cmd = List.of("gh", "pr", "create",
                "--repo", ref.path(),
                "--draft",
                "--base", baseBranch,
                "--head", headBranch,
                "--title", title,
                "--body", body);
        Map<String, String> env = new HashMap<>();
        // GitHub PAT가 있고 gh auth login을 안 했어도 GH_TOKEN으로 동작.
        if (githubPat != null && !githubPat.isBlank()) {
            env.put("GH_TOKEN", githubPat);
        }
        String stdout = ProcessRunner.run(worktreeDir, cmd, env, GH_TIMEOUT_SECONDS).stdout().trim();
        Matcher m = PR_URL_PATTERN.matcher(stdout);
        if (!m.find()) {
            throw new IOException("gh pr create 출력에서 PR URL 파싱 실패. 출력:\n" + GitRemotes.mask(stdout));
        }
        int prNumber = Integer.parseInt(m.group(1));
        log.info("PR 생성: #{} {}", prNumber, m.group());
        return new GitOpsService.PrInfo(m.group(), prNumber);
    }
}
