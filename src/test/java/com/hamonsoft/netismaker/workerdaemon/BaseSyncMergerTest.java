package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.BaseSyncMerger.Outcome;
import com.hamonsoft.netismaker.workerdaemon.BaseSyncMerger.Started;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.hamonsoft.netismaker.workerdaemon.BaseSyncFixture.git;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 git으로 보는 기준 브랜치 병합기(스펙 2026-11-13) — 병합 시작·충돌 목록·검증·병합 커밋. */
class BaseSyncMergerTest {

    @TempDir Path tmp;

    private static final Map<String, String> NONE = Map.of();

    private BaseSyncFixture fx(Map<String, String> mainEdits, Map<String, String> taskEdits) throws Exception {
        return new BaseSyncFixture(tmp, mainEdits, taskEdits).prepare();
    }

    private BaseSyncFixture conflictOnPlan() throws Exception {
        return fx(Map.of("PLAN.md", "main 제목\n본문\n"), Map.of("PLAN.md", "task 제목\n본문\n"));
    }

    @Test
    void 기준브랜치가_이미_조상이면_UP_TO_DATE() throws Exception {
        BaseSyncFixture f = fx(NONE, NONE);
        git(f.dir(), "-c", "user.name=t", "-c", "user.email=t@t", "merge", "--no-edit", f.repo.baseRef());

        assertThat(f.merger.start(f.dir(), f.repo.baseRef()).outcome()).isEqualTo(Outcome.UP_TO_DATE);
    }

    @Test
    void 겹치지_않는_변경이면_CLEAN이고_병합커밋의_부모는_둘() throws Exception {
        BaseSyncFixture f = fx(Map.of("main-only.txt", "m"), Map.of("task-only.txt", "t"));

        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(s.conflicted()).isEmpty();
        String sha = f.merger.commitMerge(f.dir(), List.of(), "merge: 기준 브랜치 main 반영 — 제목\n\ntask #42");
        assertThat(git(f.dir(), "rev-list", "--parents", "-n", "1", sha).split(" ")).hasSize(3);
        assertThat(git(f.dir(), "log", "-1", "--format=%an <%ae>")).isEqualTo("netisMaker <worker@example.com>");
        assertThat(git(f.dir(), "log", "-1", "--format=%B")).contains("task #42");
        assertThat(f.dir().toPath().resolve("main-only.txt")).exists();
        assertThat(f.dir().toPath().resolve("task-only.txt")).exists();
    }

    @Test
    void 같은_파일을_양쪽이_고치면_CONFLICTED와_파일목록() throws Exception {
        BaseSyncFixture f = conflictOnPlan();

        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.outcome()).isEqualTo(Outcome.CONFLICTED);
        assertThat(s.conflicted()).containsExactly("PLAN.md");
        assertThat(s.binaryConflicted()).isEmpty();
        assertThat(s.markerless()).isEmpty();
        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).containsExactly("PLAN.md");
    }

    @Test
    void 한쪽이_지운_파일을_다른쪽이_고치면_markerless에_잡힌다() throws Exception {
        // main이 지우고 task가 고침
        BaseSyncFixture f = fx(Map.of("gone.txt", BaseSyncFixture.DELETE), Map.of("gone.txt", "task가 고침\n"));
        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.outcome()).isEqualTo(Outcome.CONFLICTED);
        assertThat(s.conflicted()).contains("gone.txt");
        assertThat(s.markerless()).containsExactly("gone.txt");
        assertThat(s.binaryConflicted()).isEmpty();
    }

    @Test
    void 반대로_task가_지우고_main이_고쳐도_markerless에_잡힌다() throws Exception {
        BaseSyncFixture f = fx(Map.of("gone.txt", "main이 고침\n"), Map.of("gone.txt", BaseSyncFixture.DELETE));
        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.outcome()).isEqualTo(Outcome.CONFLICTED);
        assertThat(s.conflicted()).contains("gone.txt");
        assertThat(s.markerless()).containsExactly("gone.txt");
        assertThat(s.binaryConflicted()).isEmpty();
    }

    @Test
    void 표식을_지우면_해결로_보고_제목_밑줄_등호줄은_표식이_아니다() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Files.writeString(f.dir().toPath().resolve("PLAN.md"), "제목\n=======\n본문\n");   // add 안 함

        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).isEmpty();
        String sha = f.merger.commitMerge(f.dir(), s.conflicted(), "merge: 기준 브랜치 main 반영 — t");

        assertThat(git(f.dir(), "status", "--porcelain")).isEmpty();
        assertThat(git(f.dir(), "rev-list", "--parents", "-n", "1", sha).split(" ")).hasSize(3);
        assertThat(Files.readString(f.dir().toPath().resolve("PLAN.md"))).isEqualTo("제목\n=======\n본문\n");
    }

    @Test
    void 표식이_남아_있으면_미해결() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Files.writeString(f.dir().toPath().resolve("PLAN.md"), "<<<<<<< HEAD\n내 것\n=======\n남의 것\n>>>>>>> x\n");

        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).containsExactly("PLAN.md");
    }

    @Test
    void 한쪽만_남은_표식_줄도_미해결() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Files.writeString(f.dir().toPath().resolve("PLAN.md"), "내 것\n>>>>>>> origin/main\n");

        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).containsExactly("PLAN.md");
    }

    @Test
    void 한쪽_삭제를_받아들여_파일이_사라져도_미해결이_아니다() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Files.delete(f.dir().toPath().resolve("PLAN.md"));

        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).isEmpty();
        f.merger.commitMerge(f.dir(), s.conflicted(), "merge: x");
        assertThat(git(f.dir(), "status", "--porcelain")).isEmpty();
        assertThat(f.dir().toPath().resolve("PLAN.md")).doesNotExist();
    }

    @Test
    void 공백과_한글이_든_경로도_그대로_나온다() throws Exception {
        BaseSyncFixture f = fx(Map.of("docs/작업 기록.md", "main 기록\n"), Map.of("docs/작업 기록.md", "task 기록\n"));

        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.conflicted()).containsExactly("docs/작업 기록.md");
        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).containsExactly("docs/작업 기록.md");
        Files.writeString(f.dir().toPath().resolve("docs/작업 기록.md"), "합친 기록\n");
        assertThat(f.merger.unresolved(f.dir(), s.conflicted())).isEmpty();
        assertThat(f.merger.strayChanges(f.dir(), s)).isEmpty();
        f.merger.commitMerge(f.dir(), s.conflicted(), "merge: x");
        assertThat(git(f.dir(), "status", "--porcelain")).isEmpty();
    }

    @Test
    void 바이너리_충돌은_binaryConflicted에_잡힌다() throws Exception {
        BaseSyncFixture f = fx(Map.of("img.bin", "A\0M\0C"), Map.of("img.bin", "A\0T\0C"));

        Started s = f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(s.outcome()).isEqualTo(Outcome.CONFLICTED);
        assertThat(s.conflicted()).containsExactly("img.bin");
        assertThat(s.binaryConflicted()).containsExactly("img.bin");
        assertThat(s.markerless()).doesNotContain("img.bin");
    }

    @Test
    void 충돌파일_밖을_고치면_strayChanges에_나온다() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Path root = f.dir().toPath();
        Files.writeString(root.resolve("README.md"), "고침\n");                   // 미스테이징
        Files.writeString(root.resolve("other.txt"), "고침\n");                   // 스테이징
        git(f.dir(), "add", "other.txt");
        Files.writeString(root.resolve("new.txt"), "새 파일\n");

        assertThat(f.merger.strayChanges(f.dir(), s)).containsExactlyInAnyOrder("README.md", "other.txt", "new.txt");
    }

    @Test
    void 충돌파일만_고치면_strayChanges는_비어_있다() throws Exception {
        // main 쪽 새 파일은 병합이 스테이징해 온 것 — 기준선에 들어 있어 stray가 아니다
        BaseSyncFixture f = fx(Map.of("PLAN.md", "main 제목\n본문\n", "main-only.txt", "m"),
                Map.of("PLAN.md", "task 제목\n본문\n"));
        Started s = f.merger.start(f.dir(), f.repo.baseRef());
        Files.writeString(f.dir().toPath().resolve("PLAN.md"), "합침\n");
        git(f.dir(), "add", "PLAN.md");

        assertThat(f.merger.strayChanges(f.dir(), s)).isEmpty();
    }

    @Test
    void 병합_안_된_경로가_conflicted_목록에_없으면_미해결() throws Exception {
        BaseSyncFixture f = conflictOnPlan();
        f.merger.start(f.dir(), f.repo.baseRef());

        assertThat(f.merger.unresolved(f.dir(), List.of())).containsExactly("PLAN.md");
    }
}
