package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeKindTest {

    @Test
    void parsesPatchKinds() {
        assertThat(WorktreeKind.parse("patch-before-12")).contains(new WorktreeKind.Parsed(WorktreeKind.PATCH_BEFORE, 12));
        assertThat(WorktreeKind.parse("patch-after-7")).contains(new WorktreeKind.Parsed(WorktreeKind.PATCH_AFTER, 7));
        assertThat(WorktreeKind.PATCH_BEFORE.dirName(5)).isEqualTo("patch-before-5");
        assertThat(WorktreeKind.PATCH_AFTER.dirName(5)).isEqualTo("patch-after-5");
        for (String bad : new String[]{"patch-before-", "patch-before-0", "patch-before-01", "patch-after-1x",
                "patch-12", "patch-before-after-3", "Patch-before-1"}) {
            assertThat(WorktreeKind.parse(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void existingKindsUnchanged() {
        assertThat(WorktreeKind.parse("task-3")).contains(new WorktreeKind.Parsed(WorktreeKind.TASK, 3));
        assertThat(WorktreeKind.parse("deploy-3")).contains(new WorktreeKind.Parsed(WorktreeKind.DEPLOY, 3));
        assertThat(WorktreeKind.parse("design-3")).contains(new WorktreeKind.Parsed(WorktreeKind.DESIGN, 3));
    }

    @Test
    void noKindReadsAnotherKindsFolderName() {
        for (WorktreeKind k : WorktreeKind.values()) {
            assertThat(WorktreeKind.parse(k.dirName(9))).contains(new WorktreeKind.Parsed(k, 9));
        }
    }
}
