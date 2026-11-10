package com.hamonsoft.netismaker.workerdaemon.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영자가 부분 패치를 켜기 전에 손으로 돌리는 확인. 같은 커밋을 두 번 빌드한 산출물(war·jar·zip) 둘의
 * 차이가 0이어야 패치가 변경 없는 파일을 싣지 않는다. 환경변수가 없으면 건너뛴다(CI·일반 시험에서 안 돈다).
 *
 * <pre>
 * PATCH_CHECK_A=경로1 PATCH_CHECK_B=경로2 [PATCH_CHECK_IGNORE=glob1,glob2] \
 *   ./gradlew test --tests '*PatchRealArtifactCheck'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "PATCH_CHECK_A", matches = ".+")
class PatchRealArtifactCheck {
    private static final int MAX_LISTED = 50;

    @Test
    void sameCommitBuiltTwiceHasNoDifference() throws Exception {
        Path a = Path.of(System.getenv("PATCH_CHECK_A"));
        Path b = Path.of(requireB());
        PatchScanner scanner = new PatchScanner(ignoreGlobs());

        PatchDelta delta = PatchDiff.diff(scanner.scan(a), scanner.scan(b));

        assertThat(delta.isEmpty()).as(() -> describe(delta)).isTrue();
    }

    private static String requireB() {
        String b = System.getenv("PATCH_CHECK_B");
        if (b == null || b.isBlank()) {
            throw new IllegalStateException("PATCH_CHECK_B(두 번째 빌드 산출물 경로)가 필요합니다");
        }
        return b;
    }

    private static List<String> ignoreGlobs() {
        String env = System.getenv("PATCH_CHECK_IGNORE");
        if (env == null || env.isBlank()) {
            return ClosePatchWorkerProperties.DEFAULT_IGNORE;
        }
        return Arrays.stream(env.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String describe(PatchDelta d) {
        List<String> lines = new ArrayList<>();
        d.added().forEach(p -> lines.add("추가 " + p));
        d.changed().forEach(p -> lines.add("변경 " + p));
        d.deleted().forEach(p -> lines.add("삭제 " + p));
        StringBuilder sb = new StringBuilder("같은 커밋의 두 빌드가 다릅니다(" + lines.size() + "개):");
        lines.stream().limit(MAX_LISTED).forEach(l -> sb.append("\n  ").append(l));
        if (lines.size() > MAX_LISTED) {
            sb.append("\n  … 외 ").append(lines.size() - MAX_LISTED).append("개");
        }
        return sb.toString();
    }
}
