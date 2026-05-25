package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * worktree에서 구현 산출물을 commit/push하고 GitHub PR을 생성한다.
 *
 *  사전 조건 (운영자가 워커 머신에 셋업):
 *   1) git push 권한 — GitRepoCache가 PAT URL로 clone했다면 origin에 인증 박혀있음
 *   2) gh CLI 설치 + `gh auth login` 또는 GH_TOKEN 환경변수
 *
 *  흐름:
 *    1) git status --porcelain → 변경 없음이면 IOException("nothing to commit")
 *    2) git add -A
 *    3) git -c user.name=... -c user.email=... commit -m {msg}
 *    4) git push -u origin {branch}
 *    5) git rev-parse HEAD → headSha
 *    6) gh pr create --draft --base {baseBranch} --head {branch} --title --body → URL
 *    7) URL 파싱 → PR number
 */
@Component
@Profile("worker")
@Slf4j
public class GitOpsService {

    private static final long GIT_TIMEOUT_SECONDS = 600;
    private static final long GH_TIMEOUT_SECONDS = 120;
    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("https?://[\\w./-]*/pull/(\\d+)");

    private final WorkerProperties props;

    public GitOpsService(WorkerProperties props) {
        this.props = props;
    }

    /** 변경 없음을 명시적으로 알리고 싶을 때 (전체 흐름을 단계적으로 분리할 수 있게). */
    public boolean hasChanges(File worktreeDir) throws IOException, InterruptedException {
        String porcelain = ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "status", "--porcelain"), GIT_TIMEOUT_SECONDS);
        return !porcelain.isBlank();
    }

    /**
     * worktree에 있는 변경을 commit + push.
     * @return push한 commit의 SHA
     */
    public String commitAndPush(File worktreeDir, String branchName, String commitMessage)
            throws IOException, InterruptedException {
        if (!hasChanges(worktreeDir)) {
            throw new IOException("nothing to commit — 구현 시도 후 파일 변경이 없음");
        }
        ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "add", "-A"), GIT_TIMEOUT_SECONDS);
        List<String> commitCmd = List.of(
                "git",
                "-c", "user.name=" + props.gitUserName(),
                "-c", "user.email=" + props.gitUserEmail(),
                "commit", "-m", commitMessage
        );
        ProcessRunner.requireSuccess(worktreeDir, commitCmd, GIT_TIMEOUT_SECONDS);
        ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "push", "-u", "origin", branchName), GIT_TIMEOUT_SECONDS);
        String sha = ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT_SECONDS).trim();
        return sha;
    }

    /**
     * gh CLI로 draft PR 생성.
     * @return (prUrl, prNumber)
     */
    public PrInfo createDraftPr(File worktreeDir, String githubRepo,
                                String baseBranch, String headBranch,
                                String title, String body)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                "gh", "pr", "create",
                "--repo", githubRepo,
                "--draft",
                "--base", baseBranch,
                "--head", headBranch,
                "--title", title,
                "--body", body
        ));
        Map<String, String> env = new HashMap<>();
        // GitHub PAT가 있고 gh auth login을 안 했어도 GH_TOKEN으로 동작.
        // (gh auth login 했으면 ~/.config/gh/hosts.yml의 토큰을 우선 사용)
        if (props.githubPat() != null && !props.githubPat().isBlank()) {
            env.put("GH_TOKEN", props.githubPat());
        }
        String stdout = ProcessRunner.run(worktreeDir, cmd, env, GH_TIMEOUT_SECONDS)
                .stdout()
                .trim();
        // stdout이 비어있으면 ProcessRunner.run은 exit code 안 봤음 — 명시 확인 필요
        // 실패 시 stdout에 에러 메시지 (gh는 stderr 합본).
        Matcher m = PR_URL_PATTERN.matcher(stdout);
        if (!m.find()) {
            throw new IOException("gh pr create 출력에서 PR URL 파싱 실패. 출력:\n" + stdout);
        }
        String prUrl = m.group();
        int prNumber = Integer.parseInt(m.group(1));
        log.info("PR 생성: #{} {}", prNumber, prUrl);
        return new PrInfo(prUrl, prNumber);
    }

    public record PrInfo(String url, int number) {}
}
