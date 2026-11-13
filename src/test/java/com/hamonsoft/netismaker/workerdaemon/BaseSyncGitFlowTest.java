package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.BaseSyncMerger.Started;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.hamonsoft.netismaker.workerdaemon.BaseSyncFixture.git;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준 브랜치 반영의 git 끝에서 끝(스펙 2026-11-13 §11) — 목 없이 실제 git: 얕은 캐시에서 두 브랜치를 받고 → detached worktree에서
 * 병합하고 → 충돌을 풀어 병합 커밋을 만들고 → 작업 브랜치에 fast-forward push.
 */
class BaseSyncGitFlowTest {

    @TempDir Path tmp;

    @Test
    void 깊이1_캐시에서_받아_병합하고_이어서_push한다() throws Exception {
        BaseSyncFixture f = new BaseSyncFixture(tmp,
                Map.of("PLAN.md", "main 제목\n본문\n", "main-file.txt", "main 쪽 파일"),
                Map.of("PLAN.md", "task 제목\n본문\n", "task-file.txt", "task 쪽 파일"), 3);
        // 캐시: main --depth=1 clone
        f.cache.ensureFresh(f.ref, "main");

        GitRepoCache.BaseSyncRepo r = f.cache.fetchForBaseSync(f.ref, "task", "main");
        WorktreeService.CreatedWorktree wt = f.worktrees.createOnBranch(r.dir(), f.repoKey, "task", 42L);
        String oldTip = f.gitOps.headSha(wt.dir());
        Started s = f.merger.start(wt.dir(), r.baseRef());
        assertThat(s.outcome()).isEqualTo(BaseSyncMerger.Outcome.CONFLICTED);
        assertThat(s.conflicted()).containsExactly("PLAN.md");
        Files.writeString(wt.dir().toPath().resolve("PLAN.md"), "main 내용\n\ntask 내용\n");   // 해결 흉내
        assertThat(f.merger.unresolved(wt.dir(), s.conflicted())).isEmpty();
        assertThat(f.merger.strayChanges(wt.dir(), s)).isEmpty();
        f.merger.commitMerge(wt.dir(), s.conflicted(), "merge: 기준 브랜치 main 반영 — t", oldTip, r.baseRef());

        String pushed = f.gitOps.pushHead(wt.dir(), f.ref, "task");

        String remoteTask = git(f.bare.toFile(), "rev-parse", "refs/heads/task");
        String remoteMain = git(f.bare.toFile(), "rev-parse", "refs/heads/main");
        assertThat(pushed).hasSize(40).isEqualTo(remoteTask);
        String[] parents = git(f.bare.toFile(), "rev-list", "--parents", "-n", "1", pushed).split(" ");
        assertThat(parents).hasSize(3);
        assertThat(parents[1]).isEqualTo(oldTip);
        assertThat(parents[2]).isEqualTo(remoteMain);
        assertThat(git(f.bare.toFile(), "show", pushed + ":main-file.txt")).isEqualTo("main 쪽 파일");
        assertThat(git(f.bare.toFile(), "show", pushed + ":task-file.txt")).isEqualTo("task 쪽 파일");
        assertThat(git(f.bare.toFile(), "show", pushed + ":PLAN.md")).isEqualTo("main 내용\n\ntask 내용");
    }
}
