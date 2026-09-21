package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * worktree에서 구현 산출물을 commit/push하고 Draft PR(GitHub) 또는 Draft MR(GitLab)을 생성한다.
 *
 *  사전 조건 (운영자가 워커 머신에 셋업):
 *   1) git push 권한 — GitRepoCache가 PAT URL로 clone했다면 origin에 인증 박혀있음
 *   2) gh CLI 설치 + `gh auth login` 또는 GH_TOKEN 환경변수. GitLab은 GITLAB_TOKEN(scope: api)만 있으면 됨 — CLI 불필요
 *
 *  흐름:
 *    1) git status --porcelain → 변경 없음이면 IOException("nothing to commit")
 *    2) git add -A
 *    3) git -c user.name=... -c user.email=... commit -m {msg}
 *    4) git push -u origin {branch}
 *    5) git rev-parse HEAD → headSha
 *    6) Draft PR(gh CLI) 또는 Draft MR(GitLab REST API) 생성 → url/number
 */
@Component
@Profile("worker")
@Slf4j
public class GitOpsService {

    private static final long GIT_TIMEOUT_SECONDS = 600;

    private final WorkerProperties props;
    private final List<MergeRequestCreator> creators;

    public GitOpsService(WorkerProperties props) {
        this.props = props;
        this.creators = List.of(new GitHubPrCreator(props.githubPat()),
                new GitLabMrCreator(props.gitlabToken()));
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
     * Draft PR(GitHub) / Draft MR(GitLab) 생성.
     * @return (url, number) — GitLab은 MR의 web_url과 iid
     */
    public PrInfo createDraftPr(File worktreeDir, RepoRef ref,
                                String baseBranch, String headBranch,
                                String title, String body)
            throws IOException, InterruptedException {
        for (MergeRequestCreator c : creators) {
            if (c.supports(ref)) {
                return c.create(worktreeDir, ref, baseBranch, headBranch, title, body);
            }
        }
        throw new IOException("지원하지 않는 호스트: " + ref.host());
    }

    public record PrInfo(String url, int number) {}
}
