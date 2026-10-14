package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubPrCloserTest {

    private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";
    private final List<List<String>> calls = new ArrayList<>();
    private final Deque<ProcessRunner.Result> replies = new ArrayDeque<>();
    private final List<Long> sleeps = new ArrayList<>();

    private GitHubPrCloser closer() {
        return new GitHubPrCloser(args -> {
            calls.add(args);
            return replies.isEmpty() ? new ProcessRunner.Result(0, "") : replies.poll();
        }, sleeps::add);
    }

    private static ProcessRunner.Result view(String state, boolean draft, String head, String mss, String mergeOid) {
        String mc = mergeOid == null ? "null" : "{\"oid\":\"" + mergeOid + "\"}";
        return new ProcessRunner.Result(0, "{\"state\":\"" + state + "\",\"isDraft\":" + draft
                + ",\"headRefOid\":\"" + head + "\",\"mergeStateStatus\":\"" + mss + "\",\"mergeCommit\":" + mc + "}");
    }

    private static RepoRef ref() {
        return RepoRef.fromSnapshot("acme/widgets", null);
    }

    @Test
    void draft를_해제하고_head에_고정해_병합한다() throws Exception {
        replies.add(view("OPEN", true, SHA, "CLEAN", null));
        replies.add(new ProcessRunner.Result(0, ""));                          // pr ready
        replies.add(new ProcessRunner.Result(0, "merged"));                    // pr merge
        replies.add(view("MERGED", false, SHA, "UNKNOWN", "m3rg3"));           // 재조회

        assertThat(closer().merge(ref(), 5, SHA, "squash")).isEqualTo("m3rg3");
        assertThat(calls.get(1)).containsExactly("pr", "ready", "5", "--repo", "acme/widgets");
        assertThat(calls.get(2)).containsExactly("pr", "merge", "5", "--repo", "acme/widgets", "--squash",
                "--match-head-commit", SHA);
    }

    @Test
    void 이미_병합된_PR은_그_커밋을_돌려준다() throws Exception {
        replies.add(view("MERGED", false, SHA, "UNKNOWN", "already"));
        assertThat(closer().merge(ref(), 5, SHA, "merge")).isEqualTo("already");
        assertThat(calls).hasSize(1);
    }

    @Test
    void head가_바뀌었으면_아무것도_안_하고_거부() {
        replies.add(view("OPEN", true, "ffff", "CLEAN", null));
        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("head 불일치");
        assertThat(calls).hasSize(1);
    }

    @Test
    void 충돌이면_아무것도_안_하고_거부() {
        replies.add(view("OPEN", false, SHA, "DIRTY", null));
        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge")).hasMessageContaining("충돌");
        assertThat(calls).hasSize(1);
    }

    @Test
    void 닫힌_PR은_거부() {
        replies.add(view("CLOSED", false, SHA, "UNKNOWN", null));
        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge")).hasMessageContaining("닫혀");
    }

    @Test
    void 병합이_거부되면_draft를_되돌리고_CI_사유로_분류한다() {
        replies.add(view("OPEN", true, SHA, "BLOCKED", null));
        replies.add(new ProcessRunner.Result(0, ""));                                         // ready
        replies.add(new ProcessRunner.Result(1, "X Pull request is not mergeable: required status check \"ci\" is expected."));
        replies.add(new ProcessRunner.Result(0, ""));                                         // ready --undo

        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("CI");
        assertThat(calls.get(3)).containsExactly("pr", "ready", "5", "--repo", "acme/widgets", "--undo");
    }

    @Test
    void 브랜치_삭제는_없는_ref를_성공으로_본다() throws Exception {
        replies.add(new ProcessRunner.Result(1, "gh: Reference does not exist (HTTP 422)"));
        closer().deleteBranch(ref(), "netismaker/task-5-x");
        assertThat(calls.get(0)).containsExactly("api", "-X", "DELETE",
                "repos/acme/widgets/git/refs/heads/netismaker/task-5-x");
    }

    @Test
    void 브랜치_삭제의_다른_실패는_예외() {
        replies.add(new ProcessRunner.Result(1, "HTTP 403: Resource not accessible"));
        assertThatThrownBy(() -> closer().deleteBranch(ref(), "b")).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void 병합_예외_시에도_draft를_되돌린다() throws Exception {
        GitHubPrCloser closerWithException = new GitHubPrCloser(args -> {
            calls.add(args);
            if (args.contains("merge")) {
                throw new IOException("Network timeout");
            }
            if (calls.size() == 1) return view("OPEN", true, SHA, "BLOCKED", null);  // pr view
            if (args.contains("ready")) return new ProcessRunner.Result(0, "");       // pr ready or ready --undo
            return new ProcessRunner.Result(0, "");
        });

        assertThatThrownBy(() -> closerWithException.merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Network timeout");
        // Should have called: view, ready, merge (throws), ready --undo
        assertThat(calls.size()).isGreaterThanOrEqualTo(3);
        boolean hasUndo = calls.stream().anyMatch(c -> c.contains("--undo"));
        assertThat(hasUndo).isTrue();
    }

    @Test
    void 병합_거부_후_undo도_실패하면_메시지에_표시() {
        replies.add(view("OPEN", true, SHA, "BLOCKED", null));
        replies.add(new ProcessRunner.Result(0, ""));                                         // ready
        replies.add(new ProcessRunner.Result(1, "X Pull request is not mergeable: required status check \"ci\" is expected."));
        replies.add(new ProcessRunner.Result(1, "gh: NOT FOUND"));                            // ready --undo fails

        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class)
                .hasMessageContaining("draft 원복 실패 — 수동으로 draft 전환 필요");
    }

    @Test
    void 브랜치_삭제는_HTTP_404_만_있으면_예외() {
        replies.add(new ProcessRunner.Result(1, "gh: Not Found (HTTP 404)"));
        assertThatThrownBy(() -> closer().deleteBranch(ref(), "b")).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void draft_아닌_병합_거부는_undo_호출_안_함() {
        replies.add(view("OPEN", false, SHA, "BLOCKED", null));
        replies.add(new ProcessRunner.Result(1, "X Pull request is not mergeable: required status check \"ci\" is expected."));

        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("CI");
        // calls는 view와 merge만 있어야 함
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsExactly("pr", "view", "5", "--repo", "acme/widgets", "--json",
                "state,isDraft,headRefOid,mergeStateStatus,mergeCommit");
        assertThat(calls.get(1)).containsExactly("pr", "merge", "5", "--repo", "acme/widgets", "--merge",
                "--match-head-commit", SHA);
    }

    @Test
    void classify_BEHIND_상태() {
        String result = GitHubPrCloser.classify("BEHIND", "error: not up to date");
        assertThat(result).contains("base보다 뒤처짐");
    }

    @Test
    void classify_head_branch_modified_출력() {
        String result = GitHubPrCloser.classify("UNKNOWN", "error: head branch was modified");
        assertThat(result).contains("head 불일치");
    }

    @Test
    void 병합_대기열에_들어갔다가_병합되면_그_커밋을_돌려준다() throws Exception {
        replies.add(view("OPEN", false, SHA, "CLEAN", null));
        replies.add(new ProcessRunner.Result(0, "queued"));                    // pr merge (대기열)
        replies.add(view("OPEN", false, SHA, "CLEAN", null));                  // 재조회: 아직
        replies.add(view("OPEN", false, SHA, "CLEAN", null));                  // 폴링 1
        replies.add(view("MERGED", false, SHA, "UNKNOWN", "q1"));              // 폴링 2

        assertThat(closer().merge(ref(), 5, SHA, "merge")).isEqualTo("q1");
        assertThat(sleeps).containsExactly(2000L, 2000L);
    }

    @Test
    void 끝내_병합되지_않으면_draft를_되돌리지_않고_거부한다() {
        replies.add(view("OPEN", true, SHA, "CLEAN", null));
        replies.add(new ProcessRunner.Result(0, ""));                          // pr ready
        replies.add(new ProcessRunner.Result(0, "queued"));                    // pr merge (대기열)
        for (int i = 0; i < 6; i++) replies.add(view("OPEN", false, SHA, "CLEAN", null));

        assertThatThrownBy(() -> closer().merge(ref(), 5, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class)
                .hasMessageContaining("병합 대기열/진행 중 — 병합이 끝난 뒤 재시도하세요");
        assertThat(sleeps).hasSize(5);
        assertThat(calls).noneMatch(c -> c.contains("--undo"));
        assertThat(calls).hasSize(3 + 6);
    }
}
