package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloseNoteWriterTest {

    @TempDir Path vault;
    private final CloseNoteWriter writer = new CloseNoteWriter();
    private final LocalDate day = LocalDate.of(2026, 10, 13);
    private final CloseNoteWriter.NoteMeta meta =
            new CloseNoteWriter.NoteMeta(12, "로그인/세션: 만료 수정", "https://github.com/a/b/pull/3", "abcdef1234");

    @Test
    void 제목의_경로문자는_슬러그에서_빠지고_폴더_밖으로_새지_않는다() throws Exception {
        CloseNoteWriter.Written w = writer.write(vault, "HamonSoft/netisMaker", meta, "# 본문", day);
        assertThat(w.relativePath()).isEqualTo("HamonSoft/netisMaker/로그인-세션-만료-수정-작업12-2026-10-13.md");
        assertThat(w.file().getParent()).isEqualTo(vault.resolve("HamonSoft/netisMaker"));
        String text = Files.readString(w.file());
        assertThat(text).startsWith("---\ntask_id: 12\n").contains("merge_commit: abcdef1234").contains("netismaker/작업종료")
                .contains("# 본문");
    }

    @Test
    void 재시도는_같은_파일을_덮어쓰고_진행상황_줄은_한번만() throws Exception {
        writer.write(vault, "HamonSoft/netisMaker", meta, "첫 본문", day);
        CloseNoteWriter.Written w = writer.write(vault, "HamonSoft/netisMaker", meta, "둘째 본문", day);
        assertThat(Files.readString(w.file())).contains("둘째 본문").doesNotContain("첫 본문");
        Path progress = vault.resolve("HamonSoft/netisMaker/netisMaker-진행상황-2026-10-13.md");
        String p = Files.readString(progress);
        assertThat(p.split("\\[\\[로그인-세션-만료-수정-작업12-2026-10-13]]", -1)).hasSize(2);
        assertThat(p).contains("작업 #12 종료(병합 abcdef1)");
    }

    @Test
    void 기존_진행상황_노트에는_끝에_덧붙인다() throws Exception {
        Path dir = Files.createDirectories(vault.resolve("HamonSoft/netisMaker"));
        Files.writeString(dir.resolve("netisMaker-진행상황-2026-10-13.md"), "# 기존\n- 다른 일");
        writer.write(vault, "HamonSoft/netisMaker", meta, "본문", day);
        String p = Files.readString(dir.resolve("netisMaker-진행상황-2026-10-13.md"));
        assertThat(p).startsWith("# 기존\n- 다른 일\n- [[");
    }

    @Test
    void 코드펜스로_감싼_응답과_비밀은_정리한다() throws Exception {
        CloseNoteWriter.Written w = writer.write(vault, "N", meta,
                "```markdown\n# 제목\nhttps://oauth2:glpat-SECRET@gitlab.hamon.vip/x.git\n```", day);
        String text = Files.readString(w.file());
        assertThat(text).doesNotContain("```markdown").doesNotContain("glpat-SECRET").contains("# 제목");
    }

    @Test
    void 본문의_맨_토큰_패턴도_가린다() {
        String ghp = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";
        String fine = "github_pat_" + "11ABCDEFG0_abcdefghijklmnopqrstuvwxyz0123";
        String glpat = "glpat-" + "xY_z-1234567890abcd";
        String ant = "sk-ant-" + "api03-AbCdEf_GhIj-KlMnOp";
        String out = CloseNoteWriter.clean("토큰 " + ghp + "\nPAT=" + fine + " gl:" + glpat + " (" + ant + ")");
        assertThat(out).doesNotContain(ghp).doesNotContain(fine).doesNotContain(glpat).doesNotContain(ant)
                .contains("토큰 ***").contains("PAT=***").contains("gl:***").contains("(***)");
    }

    @Test
    void 짧은_접두어_문자열은_토큰으로_보지_않는다() {
        assertThat(CloseNoteWriter.clean("ghp_short sk-ant-x glpat-abc")).isEqualTo("ghp_short sk-ant-x glpat-abc");
    }

    @Test
    void 프론트매터의_PR_URL도_자격증명을_가린다() throws Exception {
        CloseNoteWriter.NoteMeta m = new CloseNoteWriter.NoteMeta(12, "제목",
                "https://oauth2:glpat-SECRETSECRETSECRET@gitlab.hamon.vip/a/b/-/merge_requests/3", "abcdef1234");
        String text = Files.readString(writer.write(vault, "N", m, "본문", day).file());
        assertThat(text).doesNotContain("SECRETSECRET").contains("pr: https://***@gitlab.hamon.vip/a/b/-/merge_requests/3");
    }

    @Test
    void 볼트_밖으로_나가는_폴더는_거부한다() {
        assertThatThrownBy(() -> writer.write(vault, "../outside", meta, "본문", day))
                .isInstanceOf(IOException.class);
        assertThat(vault.getParent().resolve("outside")).doesNotExist();
    }

    @Test
    void 빈_제목은_작업으로() {
        assertThat(CloseNoteWriter.slug(" / : ")).isEqualTo("작업");
        assertThat(CloseNoteWriter.slug("a".repeat(100))).hasSize(60);
    }

    @Test
    void 산출물이_있으면_프런트매터와_본문_끝에_남긴다() throws Exception {
        CloseNoteWriter.NoteMeta m = new CloseNoteWriter.NoteMeta(12, "제목", null, "abcdef1234",
                "w-task-12-abcdef1.war", "산출물: w-task-12-abcdef1.war (253.7 MB, 배포 커밋 abcdef1) — Symphony 작업 화면에서 로그인 후 내려받기: https://a.test/tasks/12");
        CloseNoteWriter.Written w = writer.write(vault, "N", m, "# 본문", day);
        String text = Files.readString(w.file());
        assertThat(text).contains("merge_commit: abcdef1234\nartifact: w-task-12-abcdef1.war\n")
                .endsWith("# 본문\n\n산출물: w-task-12-abcdef1.war (253.7 MB, 배포 커밋 abcdef1) — Symphony 작업 화면에서 로그인 후 내려받기: https://a.test/tasks/12\n");
    }

    @Test
    void 산출물_표시가_없으면_프런트매터와_본문_끝에_아무것도_더하지_않는다() throws Exception {
        String text = Files.readString(writer.write(vault, "N", meta, "# 본문", day).file());
        assertThat(text).doesNotContain("artifact:").doesNotContain("산출물").endsWith("# 본문\n");
    }

    @Test
    void 산출물_줄의_URL_자격증명도_가린다() throws Exception {
        CloseNoteWriter.NoteMeta m = new CloseNoteWriter.NoteMeta(12, "제목", null, "abcdef1234", "a.war",
                "산출물: a.war — 내려받기: https://oauth2:glpat-SECRETSECRETSECRET@a.test/tasks/12");
        String text = Files.readString(writer.write(vault, "N", m, "본문", day).file());
        assertThat(text).doesNotContain("SECRETSECRET").contains("https://***@a.test/tasks/12");
    }
}
