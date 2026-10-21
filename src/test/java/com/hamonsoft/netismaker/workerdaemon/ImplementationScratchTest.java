package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 구현 세션의 워커 임시 파일 (스펙 2026-10-15 §6.3·§6.4·§6.6·§6.7). */
class ImplementationScratchTest {

    @TempDir Path wt;

    @Test
    void korean_task_headings_outside_code_blocks_become_task_headings() {
        String plan = String.join("\n",
                "# 계획",
                "### 작업 1: 공통 모듈",
                "본문 — 작업 2는 나중에",
                "```markdown",
                "### 작업 9: 예시 안의 제목은 그대로",
                "```",
                "#### 작업 12: 깊은 제목",
                "### 작업 내용 정리",
                "### Task 3: 이미 영어",
                "");

        String out = ImplementationScratch.normalizeTaskHeadings(plan);

        assertThat(out).contains("### Task 1: 공통 모듈")
                .contains("본문 — 작업 2는 나중에")
                .contains("### 작업 9: 예시 안의 제목은 그대로")
                .contains("#### Task 12: 깊은 제목")
                .contains("### 작업 내용 정리")
                .contains("### Task 3: 이미 영어")
                .endsWith("\n");
        assertThat(ImplementationScratch.countTaskHeadings(out)).isEqualTo(3);
        assertThat(ImplementationScratch.countTaskHeadings(plan)).isEqualTo(1);
        assertThat(ImplementationScratch.countTaskHeadings(null)).isZero();
    }

    /** 예시 마크다운 안의 `### Task N`은 SDD 스크립트도 태스크로 읽지 않는다 — 세지 않아야 "태스크 0개" 판정이 맞다. */
    @Test
    void task_headings_inside_code_blocks_are_not_counted() {
        String plan = String.join("\n",
                "### Task 1: 실제 제목",
                "```markdown",
                "### Task 2: 예시 안의 제목",
                "```",
                "### Task 3: 코드 블록 뒤의 제목",
                "");

        assertThat(ImplementationScratch.countTaskHeadings(plan)).isEqualTo(2);
    }

    @Test
    void plan_files_are_written_under_netis_plan() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(13), "# 플랜");

        assertThat(Files.readString(wt.resolve(".netis/plan/task-13-plan.md"))).isEqualTo("# 플랜");
        assertThat(ImplementationScratch.designPath(13)).isEqualTo(".netis/plan/task-13-design.md");
    }

    @Test
    void review_notes_are_masked_trimmed_and_capped() throws Exception {
        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES, "  \n ");
        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES,
                "결론: 수정 2건\nclone https://oauth2:glpat-SECRET@gitlab.example/g/p.git\n");
        assertThat(ImplementationScratch.readReviewNotes(wt)).hasValueSatisfying(n -> assertThat(n)
                .startsWith("결론: 수정 2건")
                .doesNotContain("glpat-SECRET")
                .contains("***@gitlab.example"));

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES, "가".repeat(20_500));
        String capped = ImplementationScratch.readReviewNotes(wt).orElseThrow();
        assertThat(capped).hasSize(20_000 + "\n\n…(이하 생략)".length()).endsWith("…(이하 생략)");
    }

    /** 상한 경계에 걸친 인증 URL — 자른 뒤에 가리면 `@`가 잘려 나가 비밀이 그대로 새므로, 가린 다음에 잘라야 한다. */
    @Test
    void a_credential_url_straddling_the_cap_is_masked_before_it_is_cut() throws Exception {
        // 앞을 채워 20,000자 경계가 비밀번호 한가운데("glpat-SE|CRET")에 오게 한다
        String filler = "가".repeat(ImplementationScratch.REVIEW_NOTES_MAX_CHARS - "https://oauth2:glpat-SE".length());
        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES,
                filler + "https://oauth2:glpat-SECRET@gitlab.example/g/p.git\n끝");

        String notes = ImplementationScratch.readReviewNotes(wt).orElseThrow();

        assertThat(notes).doesNotContain("glpat").doesNotContain("oauth2").endsWith("…(이하 생략)");
    }

    @Test
    void a_symlinked_review_notes_file_is_not_read() throws Exception {
        Path secret = Files.writeString(wt.resolve("secret.txt"), "id_rsa");
        Files.createDirectories(wt.resolve(".netis"));
        try {
            Files.createSymbolicLink(wt.resolve(ImplementationScratch.REVIEW_NOTES), secret);
        } catch (Exception e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경");
        }

        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();
    }

    @Test
    void delete_root_removes_only_netis() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(1), "p");
        Files.createDirectories(wt.resolve(".design"));

        ImplementationScratch.deleteRoot(wt);

        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(wt.resolve(".design")).exists();
    }

    @Test
    void delete_all_removes_every_worker_scratch_file_but_nothing_else() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(1), "p");
        Files.createDirectories(wt.resolve(".design"));
        Files.writeString(wt.resolve(".design/a.html"), "x");
        Files.writeString(wt.resolve(".netis-deploy-env.json"), "{}");
        Files.writeString(wt.resolve("keep.txt"), "k");

        ImplementationScratch.deleteAll(wt);

        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(wt.resolve(".design")).doesNotExist();
        assertThat(wt.resolve(".netis-deploy-env.json")).doesNotExist();
        assertThat(wt.resolve("keep.txt")).exists();
    }
}
