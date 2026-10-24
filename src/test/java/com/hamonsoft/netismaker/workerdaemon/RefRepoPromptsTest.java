package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 참조 절 빌더 (스펙 2026-10-24 §6.1·§6.4·§6.5·§6.6). */
class RefRepoPromptsTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final File DIR_12 = new File("refs-root/_refs/task-7/12");

    private static WorktreeService.RefWorktree rw(long id, String alias, String branch, String sha, File dir) {
        return new WorktreeService.RefWorktree(id, alias, branch, sha, "_gitlab/apps+" + id, dir, List.of());
    }

    private static WorkerTaskResponse.TaskRefRepo ref(long id, String alias, String branch, String sha) {
        return new WorkerTaskResponse.TaskRefRepo(id, alias, "apps/" + id, "https://g.example/apps/" + id + ".git",
                "gitlab", branch, sha);
    }

    /** 스펙 §6.4 코드 블록 그대로 — 별칭 줄만 채운다. */
    private static String specBlock(String refLines) {
        return "## 참조 repo (읽기 전용)\n"
                + "아래 폴더는 비교·이식할 때 읽기만 하는 참조다. 구현은 현재 디렉토리(대상)에서만 한다.\n"
                + refLines
                + "규칙:\n"
                + "- 참조 폴더의 파일을 만들거나 고치거나 지우지 않는다. 빌드·설치·테스트 같은 명령도 참조 폴더에서 돌리지 않는다.\n"
                + "- 계획·설계에 적힌 `별칭:경로:줄`은 위 별칭의 폴더에서 찾는다.\n"
                + "- 참조 폴더는 커밋되지 않는다. 필요한 코드는 대상에 옮겨 적는다.\n";
    }

    @Test
    void prompt_section_is_the_spec_block_after_two_newlines() {
        String section = RefRepoPrompts.promptSection(List.of(rw(12, "옛앱", "release/6.5", SHA_A, DIR_12)));

        assertThat(section).isEqualTo("\n\n" + specBlock(
                "- 옛앱 · release/6.5@aaaaaaa : " + DIR_12.getAbsolutePath() + "\n"));
        assertThat(RefRepoPrompts.promptSection(List.of())).isEmpty();
    }

    @Test
    void prompt_section_lists_every_ref_in_order() {
        File dir13 = new File("refs-root/_refs/task-7/13");

        String section = RefRepoPrompts.promptSection(List.of(rw(12, "옛앱", "release/6.5", SHA_A, DIR_12),
                rw(13, "둘째", "release/9", SHA_B, dir13)));

        assertThat(section).isEqualTo("\n\n" + specBlock(
                "- 옛앱 · release/6.5@aaaaaaa : " + DIR_12.getAbsolutePath() + "\n"
                        + "- 둘째 · release/9@bbbbbbb : " + dir13.getAbsolutePath() + "\n"));
    }

    @Test
    void plan_header_adds_no_task_headings() {
        List<WorktreeService.RefWorktree> refs = List.of(rw(12, "옛앱", "release/6.5", SHA_A, DIR_12));

        String header = RefRepoPrompts.planFileHeader(refs);

        assertThat(header).startsWith("## 참조 repo (읽기 전용)\n")
                .isEqualTo(RefRepoPrompts.promptSection(refs).substring(2) + "\n");
        assertThat(ImplementationScratch.countTaskHeadings(header + "### Task 1: A\n### Task 2: B")).isEqualTo(2);
        assertThat(ImplementationScratch.countTaskHeadings(header)).isZero();
        assertThat(RefRepoPrompts.planFileHeader(List.of())).isEmpty();
    }

    @Test
    void pr_section_shows_pins_and_warnings_only_when_present() {
        List<WorkerTaskResponse.TaskRefRepo> refs = List.of(ref(12, "옛앱", "release/6.5", SHA_A),
                ref(13, "둘째", "release/9", SHA_B));

        assertThat(RefRepoPrompts.prSection(refs, List.of())).isEqualTo(
                "## 참조 repo (읽기 전용)\n"
                        + "- 옛앱 · release/6.5@aaaaaaa\n"
                        + "- 둘째 · release/9@bbbbbbb\n"
                        + "\n");
        assertThat(RefRepoPrompts.prSection(refs, List.of("옛앱: 파일 2개", "둘째: HEAD가 고정 커밋과 다름"))).isEqualTo(
                "## 참조 repo (읽기 전용)\n"
                        + "- 옛앱 · release/6.5@aaaaaaa\n"
                        + "- 둘째 · release/9@bbbbbbb\n"
                        + "> ⚠ 참조 repo 변경 감지(이 PR에는 포함 안 됨): 옛앱: 파일 2개, 둘째: HEAD가 고정 커밋과 다름\n"
                        + "\n");
        assertThat(RefRepoPrompts.prSection(refs, null)).isEqualTo(RefRepoPrompts.prSection(refs, List.of()));
        assertThat(RefRepoPrompts.prSection(List.of(), List.of())).isEmpty();
        assertThat(RefRepoPrompts.prSection(List.of(), List.of("옛앱: 파일 2개"))).isEmpty();
    }

    @Test
    void prep_failure_reason_prefers_the_last_fatal_line_and_masks() {
        WorkerTaskResponse.TaskRefRepo ref = ref(12, "옛앱", "release/6.5", SHA_A);

        String reason = RefRepoPrompts.prepFailureReason(ref, new IOException("git failed (128): Cloning into 'x'...\n"
                + "fatal: could not read Username for 'https://oauth2:glpat-X@g.example/p.git': terminal prompts disabled\n"));

        assertThat(reason).isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — fatal: could not read Username for "
                + "'https://***@g.example/p.git': terminal prompts disabled");
        assertThat(reason).doesNotContain("glpat-X");

        // 여러 줄이면 마지막 fatal:/error: 줄
        assertThat(RefRepoPrompts.prepFailureReason(ref, new IOException(
                "git failed (128): \n  error: first\nhint: x\n  fatal: last  \nremote: y")))
                .isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — fatal: last");
        // fatal 줄이 없으면 첫 줄
        assertThat(RefRepoPrompts.prepFailureReason(ref, new IOException("고정 커밋 정보가 없거나 올바르지 않습니다\n둘째 줄")))
                .isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — 고정 커밋 정보가 없거나 올바르지 않습니다");
        // 사유는 300자 상한
        String longReason = RefRepoPrompts.prepFailureReason(ref, new IOException("fatal: " + "가".repeat(400)));
        assertThat(longReason).startsWith("참조 repo 준비 실패: 옛앱(release/6.5) — fatal: 가가");
        assertThat(longReason.length()).isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — ".length() + 300);
        // 메시지가 없으면 예외 이름 (R16.3)
        assertThat(RefRepoPrompts.prepFailureReason(ref, new NullPointerException()))
                .isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — NullPointerException");
    }

    @Test
    void failure_suffix_joins_warnings() {
        assertThat(RefRepoPrompts.failureSuffix(List.of())).isEmpty();
        assertThat(RefRepoPrompts.failureSuffix(null)).isEmpty();
        assertThat(RefRepoPrompts.failureSuffix(List.of("옛앱: 파일 2개", "둘째: HEAD가 고정 커밋과 다름")))
                .isEqualTo(" — 참조 repo 변경 감지: 옛앱: 파일 2개, 둘째: HEAD가 고정 커밋과 다름");
    }

    @Test
    void sha7_is_the_first_seven_characters() {
        assertThat(RefRepoPrompts.sha7(SHA_A)).isEqualTo("aaaaaaa");
        assertThat(RefRepoPrompts.sha7("abc")).isEqualTo("abc");
        assertThat(RefRepoPrompts.sha7(null)).isEmpty();
    }
}
