package com.hamonsoft.netismaker.workerdaemon.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchBaseTest {

    private static final String MERGE = "m".repeat(40);
    private static final String P1 = "abc1234" + "0".repeat(33);
    private static final String P2 = "def5678" + "0".repeat(33);

    @Test
    void recordedBaseEqualToFirstParentIsAccepted() throws Exception {
        // merge 커밋(부모 2)
        assertThat(PatchBase.resolve(MERGE, P1, List.of(P1, P2))).isEqualTo(P1);
        // squash 커밋(부모 1)
        assertThat(PatchBase.resolve(MERGE, P1, List.of(P1))).isEqualTo(P1);
    }

    @Test
    void recordedBaseDifferentFromFirstParentFails() {
        assertThatThrownBy(() -> PatchBase.resolve(MERGE, P1, List.of(P2)))
                .isInstanceOf(PatchException.class)
                .hasMessage("패치 기준 커밋을 확인할 수 없습니다: 병합 직전 기준 브랜치 끝(abc1234)과 병합 커밋의 부모(def5678)가 다릅니다");
    }

    @Test
    void noRecordedBaseAndTwoParentsUsesFirstParent() throws Exception {
        assertThat(PatchBase.resolve(MERGE, null, List.of(P1, P2))).isEqualTo(P1);
        assertThat(PatchBase.resolve(MERGE, " ", List.of(P1, P2))).isEqualTo(P1);
    }

    @Test
    void noRecordedBaseAndSingleParentFails() {
        assertThatThrownBy(() -> PatchBase.resolve(MERGE, null, List.of(P1)))
                .isInstanceOf(PatchException.class)
                .hasMessage("패치 기준 커밋을 확인할 수 없습니다: 병합 직전 기준 브랜치를 기록하지 못했고 병합 커밋이 아닙니다");
    }

    @Test
    void blankMergeShaFails() {
        for (String blank : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> PatchBase.resolve(blank, P1, List.of(P1)))
                    .isInstanceOf(PatchException.class)
                    .hasMessage("패치 기준 커밋을 확인할 수 없습니다: 병합 커밋이 기록되지 않았습니다");
        }
    }

    @Test
    void emptyParentsFails() {
        for (List<String> parents : new List[]{List.<String>of(), null}) {
            assertThatThrownBy(() -> PatchBase.resolve(MERGE, P1, parents))
                    .isInstanceOf(PatchException.class)
                    .hasMessage("패치 기준 커밋을 확인할 수 없습니다: 병합 커밋의 부모를 읽지 못했습니다");
        }
    }
}
