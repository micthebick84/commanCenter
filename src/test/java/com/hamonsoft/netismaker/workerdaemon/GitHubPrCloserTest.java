package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;

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

    private GitHubPrCloser closer() {
        return new GitHubPrCloser(args -> {
            calls.add(args);
            return replies.isEmpty() ? new ProcessRunner.Result(0, "") : replies.poll();
        });
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
}
