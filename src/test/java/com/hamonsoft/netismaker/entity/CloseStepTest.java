package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CloseStepTest {

    @Test
    void parse는_null_빈값_모르는값을_건너뛴다() {
        assertThat(CloseStep.parse(null)).isEmpty();
        assertThat(CloseStep.parse("")).isEmpty();
        assertThat(CloseStep.parse("MERGED, BOGUS ,NOTE_WRITTEN"))
                .containsExactly(CloseStep.MERGED, CloseStep.NOTE_WRITTEN);
    }

    @Test
    void append는_중복없이_enum_순서로_기록한다() {
        String csv = CloseStep.append(null, CloseStep.NOTE_WRITTEN);
        csv = CloseStep.append(csv, CloseStep.MERGED);
        csv = CloseStep.append(csv, CloseStep.MERGED);
        assertThat(csv).isEqualTo("MERGED,NOTE_WRITTEN");
    }

    @Test
    void allDone은_모드에_따라_필요한_단계가_다르다() {
        String four = "BRANCH_DELETED,NOTE_WRITTEN,UNDEPLOYED,MERGED";
        assertThat(CloseStep.allDone("MERGED,UNDEPLOYED,NOTE_WRITTEN", null)).isFalse();
        assertThat(CloseStep.allDone(four, null)).isTrue();
        assertThat(CloseStep.allDone(four, "SKIPPED")).isTrue();
        assertThat(CloseStep.allDone(four, "REQUIRED")).isFalse();
        assertThat(CloseStep.allDone(four + ",ARTIFACT_UPLOADED", "REQUIRED")).isTrue();
    }

    @Test
    void append는_산출물_단계를_배포_중지와_노트_사이에_둔다() {
        assertThat(CloseStep.append("MERGED,UNDEPLOYED,NOTE_WRITTEN", CloseStep.ARTIFACT_UPLOADED))
                .isEqualTo("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED,NOTE_WRITTEN");
    }
}
