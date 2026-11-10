package com.hamonsoft.netismaker.workerdaemon.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PatchDiffTest {

    private static PatchTree tree(String shape, Map<String, String> m) {
        return new PatchTree(shape, new TreeMap<>(m));
    }

    @Test
    void addedChangedDeletedAreSeparatedAndSorted() throws Exception {
        PatchTree before = tree("single:war", Map.of("b", "1", "c", "1", "d", "1", "z", "1"));
        PatchTree after = tree("single:war", Map.of("b", "1", "c", "2", "a", "9", "y", "9", "x", "2"));

        PatchDelta d = PatchDiff.diff(before, after);

        assertThat(d.added()).containsExactly("a", "x", "y");
        assertThat(d.changed()).containsExactly("c");
        assertThat(d.deleted()).containsExactly("d", "z");
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.fileCount()).isEqualTo(4);
    }

    @Test
    void identicalTreesGiveEmptyDelta() throws Exception {
        PatchTree t = tree("single:war", Map.of("a", "1"));

        PatchDelta d = PatchDiff.diff(t, tree("single:war", Map.of("a", "1")));

        assertThat(d.isEmpty()).isTrue();
        assertThat(d.fileCount()).isZero();
    }

    @Test
    void innerClassFilesAreListedIndividually() throws Exception {
        PatchTree before = tree("single:war", Map.of("p/A.class", "1"));
        PatchTree after = tree("single:war", Map.of("p/A.class", "1", "p/A$1.class", "2"));

        assertThat(PatchDiff.diff(before, after).added()).containsExactly("p/A$1.class");
    }

    @Test
    void shapeMismatchThrowsFixedMessage() {
        assertThatThrownBy(() -> PatchDiff.diff(tree("single:war", Map.of()), tree("single:jar", Map.of())))
                .isInstanceOf(PatchException.class)
                .hasMessage("패치 전·후 빌드 결과의 구성이 다릅니다");
    }
}
