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
 *    1) git status --porcelain → 변경도 베이스 이후 커밋도 없으면 IOException("nothing to commit")
 *    2) git add -A
 *    3) git -c user.name=... -c user.email=... commit -m {msg}
 *    4) git -c credential.helper= push {인증 URL} HEAD:refs/heads/{branch}
 *       (upstream 추적은 쓰는 곳이 없어 -u 없음 / 헬퍼 비활성으로 토큰 저장 방지)
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

    /** 예전 동작 — 변경이 꼭 있어야 커밋한다(에이전트 커밋을 모르는 호출부용). */
    public String commitAndPush(File worktreeDir, RepoRef ref, String branchName, String commitMessage)
            throws IOException, InterruptedException {
        return commitAndPush(worktreeDir, ref, branchName, commitMessage, null);
    }

    /**
     * worktree에 있는 변경을 commit + push.
     *
     * baseSha가 있으면 에이전트가 이미 만든 커밋(SDD 태스크 커밋 등)도 받아들인다 (스펙 2026-10-15 §6.1): 남은 변경만
     * 한 커밋으로 더하고, 베이스 이후 커밋도 변경도 없을 때만 nothing to commit. baseSha가 null이면 예전처럼 변경 필수.
     *
     * push는 origin이 아니라 ref의 인증 URL로 보낸다 — 캐시/워크트리 config에 자격증명을
     * 남기지 않기 위해(GitRepoCache 불변식) origin은 평문 URL이기 때문.
     *
     * @return push한 commit의 SHA
     */
    public String commitAndPush(File worktreeDir, RepoRef ref, String branchName, String commitMessage,
                                String baseSha) throws IOException, InterruptedException {
        boolean changes = hasChanges(worktreeDir);
        if (!changes && (baseSha == null || commitsSince(worktreeDir, baseSha) == 0)) {
            throw new IOException("nothing to commit — 구현 시도 후 파일 변경이 없음");
        }
        if (changes) commitAll(worktreeDir, commitMessage);
        push(worktreeDir, ref, branchName);
        return headSha(worktreeDir);
    }

    /**
     * 중단된 구현(시간 초과·비정상 종료)의 남은 변경을 WIP 커밋으로 묶고, 베이스 이후 커밋이 있으면 작업 브랜치를
     * push한다 (스펙 2026-10-15 §6.6). PR/MR은 만들지 않는다. 실패는 예외 대신 결과로 돌려준다 — 이미 실패 보고 중이다.
     */
    public PartialWork preservePartialWork(File worktreeDir, RepoRef ref, String branchName, String baseSha,
                                           String wipMessage) {
        int commits;
        String sha;
        try {
            if (hasChanges(worktreeDir)) commitAll(worktreeDir, wipMessage);
            commits = commitsSince(worktreeDir, baseSha);
            if (commits == 0) return new PartialWork(0, null, null);
            sha = headSha(worktreeDir);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new PartialWork(0, null, GitRemotes.mask(String.valueOf(e.getMessage())));
        }
        try {
            push(worktreeDir, ref, branchName);
            return new PartialWork(commits, sha, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new PartialWork(commits, sha, GitRemotes.mask(String.valueOf(e.getMessage())));
        }
    }

    /** 중단 보존 결과. commits = 베이스 이후 커밋 수, error = 마스킹한 실패 메시지(없으면 null). */
    public record PartialWork(int commits, String headSha, String error) {
        public boolean pushed() {
            return commits > 0 && error == null;
        }
    }

    private void commitAll(File worktreeDir, String message) throws IOException, InterruptedException {
        ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "add", "-A"), GIT_TIMEOUT_SECONDS);
        List<String> commitCmd = List.of(
                "git",
                "-c", "user.name=" + props.gitUserName(),
                "-c", "user.email=" + props.gitUserEmail(),
                "commit", "-m", message
        );
        ProcessRunner.requireSuccess(worktreeDir, commitCmd, GIT_TIMEOUT_SECONDS);
    }

    private void push(File worktreeDir, RepoRef ref, String branchName) throws IOException, InterruptedException {
        try {
            // credential.helper=(빈 값): 운영자 머신의 헬퍼가 인증 URL의 토큰을 저장하지 못하게 한다.
            ProcessRunner.requireSuccess(worktreeDir,
                    List.of("git", "-c", "credential.helper=",
                            "push", remotes.authenticatedUrl(ref),
                            "HEAD:refs/heads/" + branchName),
                    GIT_TIMEOUT_SECONDS);
        } catch (IOException e) {
            // ProcessRunner가 이미 마스킹하지만, 이 호출부는 토큰이 확실히 실리는 유일한 push라
            // 방어적으로 한 번 더 가린다(mask는 멱등). cause로 원본을 달지는 않는다.
            throw new IOException(GitRemotes.mask(e.getMessage()));
        }
    }

    private static String headSha(File worktreeDir) throws IOException, InterruptedException {
        return ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT_SECONDS).trim();
    }

    private static int commitsSince(File worktreeDir, String baseSha) throws IOException, InterruptedException {
        String out = ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "rev-list", "--count", baseSha + "..HEAD"), GIT_TIMEOUT_SECONDS).trim();
        return Integer.parseInt(out);
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
