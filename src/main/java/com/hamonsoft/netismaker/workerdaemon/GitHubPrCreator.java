package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gh CLI로 Draft PR 생성. 사전 조건: gh 설치 + (`gh auth login` 또는 GH_TOKEN).
 *
 * 본문은 명령행 인자가 아니라 임시 파일(`--body-file`)로 넘긴다 — 인터뷰 설계 문서와 리뷰 메모가 붙은 본문이
 * Windows 명령행 한도(32,767자)를 넘으면 gh가 시작조차 못 해(error=206) 이미 push한 구현이 "PR/MR 생성 실패"로 끝난다.
 * 본문 길이 자체의 상한(GitHub 65,536자)은 본문을 조립하는 WorkerMainLoop.renderPrBody가 지킨다.
 */
@Slf4j
public class GitHubPrCreator implements MergeRequestCreator {

    /** gh 실행 — 테스트 seam. cmd는 맨 앞 "gh" 포함, stdout은 stderr 병합. */
    interface Gh {
        ProcessRunner.Result run(File workingDir, List<String> cmd, Map<String, String> env)
                throws IOException, InterruptedException;
    }

    private static final long GH_TIMEOUT_SECONDS = 120;
    private static final Pattern PR_URL_PATTERN = Pattern.compile("https?://[\\w./-]*/pull/(\\d+)");

    private final String githubPat;
    private final Gh gh;

    public GitHubPrCreator(String githubPat) {
        this(githubPat, (dir, cmd, env) -> ProcessRunner.run(dir, cmd, env, GH_TIMEOUT_SECONDS));
    }

    GitHubPrCreator(String githubPat, Gh gh) {
        this.githubPat = githubPat;
        this.gh = gh;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return !ref.isGitlab();
    }

    @Override
    public GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                       String title, String body) throws IOException, InterruptedException {
        Path bodyFile = Files.createTempFile("netismaker-pr-body-", ".md");
        try {
            Files.writeString(bodyFile, body == null ? "" : body, StandardCharsets.UTF_8);
            Map<String, String> env = new HashMap<>();
            // GitHub PAT가 있고 gh auth login을 안 했어도 GH_TOKEN으로 동작.
            if (githubPat != null && !githubPat.isBlank()) {
                env.put("GH_TOKEN", githubPat);
            }
            String stdout = gh.run(worktreeDir, command(ref, baseBranch, headBranch, title, bodyFile), env)
                    .stdout().trim();
            Matcher m = PR_URL_PATTERN.matcher(stdout);
            if (!m.find()) {
                throw new IOException("gh pr create 출력에서 PR URL 파싱 실패. 출력:\n" + GitRemotes.mask(stdout));
            }
            int prNumber = Integer.parseInt(m.group(1));
            log.info("PR 생성: #{} {}", prNumber, m.group());
            return new GitOpsService.PrInfo(m.group(), prNumber);
        } finally {
            deleteQuietly(bodyFile);
        }
    }

    /** gh 명령 — 본문은 파일 경로로만 싣는다(길이와 무관하게 명령행이 짧다). */
    static List<String> command(RepoRef ref, String baseBranch, String headBranch, String title, Path bodyFile) {
        return List.of("gh", "pr", "create",
                "--repo", ref.path(),
                "--draft",
                "--base", baseBranch,
                "--head", headBranch,
                "--title", title,
                "--body-file", bodyFile.toString());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("PR 본문 임시 파일 삭제 실패(남아도 무해): {} ({})", file, e.getMessage());
        }
    }
}
