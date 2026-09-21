package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;

import java.io.File;
import java.io.IOException;

/** push된 head 브랜치로 Draft PR(GitHub) / Draft MR(GitLab)을 만든다. */
public interface MergeRequestCreator {

    boolean supports(RepoRef ref);

    GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                String title, String body) throws IOException, InterruptedException;
}
