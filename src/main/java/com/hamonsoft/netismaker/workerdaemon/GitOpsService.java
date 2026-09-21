package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
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
 *   1) git push 권한 — GITHUB_PAT / GITLAB_TOKEN. origin에는 자격증명이 없으므로(캐시 불변식)
 *      push는 매번 인증 URL을 인자로 준다.
 *   2) gh CLI 설치 + `gh auth login` 또는 GH_TOKEN 환경변수. GitLab은 GITLAB_TOKEN(scope: api)만 있으면 됨 — CLI 불필요
 *
 *  흐름:
 *    1) git status --porcelain → 변경 없음이면 IOException("nothing to commit")
 *    2) git add -A
 *    3) git -c user.name=... -c user.email=... commit -m {msg}
 *    4) git push {인증 URL} HEAD:refs/heads/{branch}   (upstream 추적은 쓰는 곳이 없어 -u 없음)
 *    5) git rev-parse HEAD → headSha
 *    6) Draft PR(gh CLI) 또는 Draft MR(GitLab REST API) 생성 → url/number
 */
@Component
@Profile("worker")
@Slf4j
public class GitOpsService {

    private static final long GIT_TIMEOUT_SECONDS = 600;

    private final WorkerProperties props;
    private final GitRemotes remotes;
    private final List<MergeRequestCreator> creators;

    public GitOpsService(WorkerProperties props) {
        this.props = props;
        this.remotes = new GitRemotes(props.githubPat(), props.gitlabToken());
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
     *
     * push는 origin이 아니라 ref의 인증 URL로 보낸다 — 캐시/워크트리 config에 자격증명을
     * 남기지 않기 위해(GitRepoCache 불변식) origin은 평문 URL이기 때문.
     *
     * @return push한 commit의 SHA
     */
    public String commitAndPush(File worktreeDir, RepoRef ref, String branchName, String commitMessage)
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
        try {
            ProcessRunner.requireSuccess(worktreeDir,
                    List.of("git", "push", remotes.authenticatedUrl(ref),
                            "HEAD:refs/heads/" + branchName),
                    GIT_TIMEOUT_SECONDS);
        } catch (IOException e) {
            // ProcessException은 실패한 명령 원문(=인증 URL 포함 argv)을 메시지에 담는다.
            // cause로 원본을 달면 마스킹되지 않은 메시지가 같이 따라가므로 달지 않는다.
            throw new IOException(GitRemotes.mask(e.getMessage()));
        }
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
