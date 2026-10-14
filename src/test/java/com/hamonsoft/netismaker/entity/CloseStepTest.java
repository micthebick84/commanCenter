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
    void allDone은_4단계가_모두_있을때만_true() {
        assertThat(CloseStep.allDone("MERGED,UNDEPLOYED,NOTE_WRITTEN")).isFalse();
        assertThat(CloseStep.allDone("BRANCH_DELETED,NOTE_WRITTEN,UNDEPLOYED,MERGED")).isTrue();
    }
}
