package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 워커가 배포 중에 끝나(크래시·강제 종료) 남은 설정 파일 사본(평문 DB 비밀번호)을 기동 때 지운다.
 * 진행 중 작업(API 보호 목록)의 폴더는 둔다 — 같은 PC의 다른 워커 프로세스가 배포 중일 수 있다.
 */
class DeployServiceStaleOverrideCleanupTest {

    @TempDir
    Path tmp;

    private Path taskDir(Path root, String name) throws IOException {
        Path d = Files.createDirectories(root.resolve(name).resolve("WEB-INF/classes/config"));
        Files.writeString(d.resolve("jdbc_maria.properties"), "jdbc.password=secret\n");
        return root.resolve(name);
    }

    @Test
    void 진행_중이_아닌_작업의_사본만_지운다() throws IOException {
        Path root = tmp.resolve("deploy-override");
        Path stale = taskDir(root, "task-54");
        Path active = taskDir(root, "task-60");
        Path other = Files.createDirectories(root.resolve("not-a-task"));

        int n = DeployService.cleanStaleOverrides(root, () -> Set.of(60L));

        assertThat(n).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(active.resolve("WEB-INF/classes/config/jdbc_maria.properties")).exists();
        assertThat(other).exists();   // 이름 규칙 밖은 건드리지 않는다
    }

    @Test
    void 이름_규칙_밖의_긴_숫자_폴더는_건너뛰고_나머지는_지운다() throws IOException {
        Path root = tmp.resolve("deploy-override");
        Path stale = taskDir(root, "task-54");
        Path huge = Files.createDirectories(root.resolve("task-" + "9".repeat(25)));
        Path zero = Files.createDirectories(root.resolve("task-0"));

        assertThat(DeployService.cleanStaleOverrides(root, Set::of)).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(huge).exists();
        assertThat(zero).exists();
    }

    @Test
    void 모두_지우면_빈_부모_폴더도_지운다() throws IOException {
        Path root = tmp.resolve("deploy-override");
        taskDir(root, "task-1");
        taskDir(root, "task-2");

        assertThat(DeployService.cleanStaleOverrides(root, Set::of)).isEqualTo(2);
        assertThat(root).doesNotExist();
    }

    @Test
    void 폴더가_없거나_사본이_없으면_진행_중_목록을_묻지_않는다() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        Path missing = tmp.resolve("none");
        assertThat(DeployService.cleanStaleOverrides(missing, () -> { asked.incrementAndGet(); return Set.of(); })).isZero();

        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Files.createDirectories(empty.resolve("not-a-task"));
        assertThat(DeployService.cleanStaleOverrides(empty, () -> { asked.incrementAndGet(); return Set.of(); })).isZero();

        assertThat(asked).hasValue(0);   // 원격 워커처럼 배포를 안 받는 PC는 API를 부르지 않는다
    }

    @Test
    void 진행_중_목록을_못_받으면_아무것도_지우지_않고_예외를_올린다() throws IOException {
        Path root = tmp.resolve("deploy-override");
        Path stale = taskDir(root, "task-54");

        assertThatThrownBy(() -> DeployService.cleanStaleOverrides(root, () -> { throw new IllegalStateException("down"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stale).exists();
    }
}
