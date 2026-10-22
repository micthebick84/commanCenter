package com.hamonsoft.netismaker.util;

import com.hamonsoft.netismaker.util.FileTrees.DeleteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 공용 트리 삭제 — 워커 레포 캐시 정리(RepoCacheCleanupJob)와 API 체크아웃 정리(InterviewCheckoutCleanupJob)가 함께 쓴다.
 * 링크·정션을 따라가지 않는 것이 핵심 계약이다(따라가면 루트 밖 파일이 지워진다).
 */
class FileTreesTest {

    @TempDir Path tmp;

    /** Windows는 DOS 읽기 전용 속성(git pack/idx와 같은 모양), POSIX는 쓰기 권한 제거. */
    private static void readOnly(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("dos")) {
            Files.setAttribute(file, "dos:readonly", true);
        } else {
            assertThat(file.toFile().setWritable(false)).isTrue();
        }
    }

    /** 심볼릭 링크는 권한이 없는 환경(Windows 개발 PC 등)이 있다 — 못 만들면 테스트를 건너뛴다. */
    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경: " + e);
        }
    }

    /** mklink /J는 관리자 권한 없이 만들 수 있다. 실패하면(정책 등) 테스트를 건너뛴다. */
    private static void junction(Path link, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        assumeTrue(done && p.exitValue() == 0, "mklink /J 실패 — 정션을 만들 수 없는 환경");
        BasicFileAttributes a = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assumeTrue(a.isOther() && !Files.isSymbolicLink(link), "정션이 isOther로 보이지 않는 환경");
    }

    /** 루트 밖에 둘 "지우면 안 되는" 파일들: outside/keep.txt, outside/sub/keep2.txt. */
    private Path precious() throws IOException {
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("keep.txt"), "지우면 안 됨");
        Files.writeString(outside.resolve("sub/keep2.txt"), "지우면 안 됨 2");
        return outside;
    }

    @Test
    void deletes_a_tree_including_read_only_files() throws Exception {
        Path root = tmp.resolve("tree");
        Files.createDirectories(root.resolve("sub/deeper"));
        Files.createDirectories(root.resolve("empty"));
        readOnly(Files.writeString(root.resolve("a.txt"), "0123456789"));
        readOnly(Files.writeString(root.resolve("sub/b.pack"), "01234567890123456789"));
        Files.writeString(root.resolve("sub/deeper/c.txt"), "01234");

        DeleteResult r = FileTrees.deleteTree(root);

        assertThat(root).doesNotExist();
        assertThat(r.failures()).isZero();
        assertThat(r.freedBytes()).isEqualTo(35);   // 10 + 20 + 5 — 일반 파일 크기만 합친다
    }

    @Test
    void a_missing_root_is_not_a_failure() {
        // 이미 지워진 폴더(다른 회차·수동 삭제)는 실패가 아니다 — 호출부가 failures만 보고 재시도를 판단한다
        assertThat(FileTrees.deleteTree(tmp.resolve("nope"))).isEqualTo(new DeleteResult(0, 0));
    }

    @Test
    void deletes_a_single_file_root() throws Exception {
        Path file = Files.writeString(tmp.resolve("one.txt"), "0123456789");

        assertThat(FileTrees.deleteTree(file)).isEqualTo(new DeleteResult(10, 0));
        assertThat(file).doesNotExist();
    }

    // ── 링크: 링크 자체만 지우고 대상은 건드리지 않는다 ──

    @Test
    void does_not_follow_a_symlink() throws Exception {
        Path outside = precious();
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("a.txt"), "0123456789");
        symlink(root.resolve("dir-link"), outside);
        symlink(root.resolve("file-link"), outside.resolve("keep.txt"));

        DeleteResult r = FileTrees.deleteTree(root);

        assertThat(root).doesNotExist();
        assertThat(outside.resolve("keep.txt")).hasContent("지우면 안 됨");
        assertThat(outside.resolve("sub/keep2.txt")).exists();
        assertThat(r.failures()).isZero();
        assertThat(r.freedBytes()).isEqualTo(10);   // 링크 대상 파일 크기는 해제 용량에 넣지 않는다
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void does_not_follow_a_junction() throws Exception {
        Path outside = precious();
        Path root = tmp.resolve("tree");
        Files.createDirectories(root.resolve("inner"));
        Files.writeString(root.resolve("a.txt"), "0123456789");
        Path link = root.resolve("inner/link");
        junction(link, outside);
        try {
            DeleteResult r = FileTrees.deleteTree(root);

            assertThat(root).doesNotExist();
            assertThat(outside.resolve("keep.txt")).hasContent("지우면 안 됨");
            assertThat(outside.resolve("sub/keep2.txt")).exists();
            assertThat(r.failures()).isZero();
            assertThat(r.freedBytes()).isEqualTo(10);
        } finally {
            Files.deleteIfExists(link);   // 정션이 남으면 @TempDir 정리가 대상으로 따라 들어갈 수 있다 — 링크만 지운다
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void does_not_follow_a_junction_given_as_the_root() throws Exception {
        Path outside = precious();
        Path link = tmp.resolve("link");
        junction(link, outside);
        try {
            DeleteResult r = FileTrees.deleteTree(link);

            assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(outside.resolve("keep.txt")).hasContent("지우면 안 됨");
            assertThat(outside.resolve("sub/keep2.txt")).exists();
            assertThat(r.failures()).isZero();
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ── 끊긴 정션·트리 안 정션 ──
    // 정션은 NOFOLLOW로 읽어도 디렉터리라 walker가 preVisitDirectory 전에 대상 쪽 디렉터리 스트림부터 연다. 대상이 없으면
    // 그 열기가 NoSuchFileException으로 실패한다 — 이걸 '이미 없음'으로 넘기면 정션 항목이 남아 부모 삭제가 계속 실패한다.

    /** 디렉터리 나열 순서(= walker가 방문하는 순서)상 first가 second보다 앞인가. */
    private static boolean listedBefore(Path dir, String first, String second) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            List<String> names = children.map(p -> p.getFileName().toString()).toList();
            return names.indexOf(first) >= 0 && names.indexOf(first) < names.indexOf(second);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removes_a_dangling_junction_instead_of_leaving_it_behind() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("a.txt"), "0123456789");
        Path gone = Files.createDirectories(tmp.resolve("gone"));
        Path link = root.resolve("link");
        junction(link, gone);
        Files.delete(gone);   // 대상이 사라져 정션이 끊긴다
        try {
            DeleteResult r = FileTrees.deleteTree(root);

            assertThat(root).doesNotExist();
            assertThat(r).isEqualTo(new DeleteResult(10, 0));
        } finally {
            Files.deleteIfExists(link);   // 못 지웠다면 @TempDir 정리가 막히지 않게 정션만 치운다
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removes_a_dangling_junction_given_as_the_root() throws Exception {
        Path gone = Files.createDirectories(tmp.resolve("gone"));
        Path link = tmp.resolve("link");
        junction(link, gone);
        Files.delete(gone);
        try {
            DeleteResult r = FileTrees.deleteTree(link);

            assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(r.failures()).isZero();
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removes_an_in_tree_junction_whose_target_was_deleted_first() throws Exception {
        // pnpm식 node_modules: react → .pnpm/pkg. 나열 순서상 .pnpm이 먼저 지워져 react 정션이 끊긴 채로 방문된다.
        // 같은 트리 안에는 루트 밖으로 가는 정션도 하나 둔다 — 끊긴 정션을 치우는 길이 대상을 건드리지 않는지 함께 본다.
        Path outside = precious();
        Path root = tmp.resolve("tree");
        Path pkg = Files.createDirectories(root.resolve("node_modules/.pnpm/pkg"));
        Files.writeString(pkg.resolve("index.js"), "0123456789");
        Path react = root.resolve("node_modules/react");
        Path ext = root.resolve("node_modules/ext");
        junction(react, pkg);
        junction(ext, outside);
        try {
            assumeTrue(listedBefore(root.resolve("node_modules"), ".pnpm", "react"),
                    "디렉터리 나열 순서가 .pnpm → react가 아니라 이 시나리오를 만들 수 없는 환경");

            DeleteResult r = FileTrees.deleteTree(root);

            assertThat(root).doesNotExist();
            assertThat(r).isEqualTo(new DeleteResult(10, 0));   // 정션을 거쳐 같은 파일을 두 번 세지 않는다
            assertThat(outside.resolve("keep.txt")).hasContent("지우면 안 됨");
            assertThat(outside.resolve("sub/keep2.txt")).exists();
        } finally {
            Files.deleteIfExists(react);
            Files.deleteIfExists(ext);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removes_an_in_tree_junction_that_is_listed_before_its_target() throws Exception {
        // 반대 순서(정션이 먼저 방문돼 대상이 아직 있다)는 원래도 됐다 — 대상은 한 번만 지우고 용량도 한 번만 센다
        Path root = tmp.resolve("tree");
        Path target = Files.createDirectories(root.resolve("z-target"));
        Files.writeString(target.resolve("file.txt"), "0123456789");
        Path link = root.resolve("a-link");
        junction(link, target);
        try {
            assumeTrue(listedBefore(root, "a-link", "z-target"),
                    "디렉터리 나열 순서가 a-link → z-target이 아니라 이 시나리오를 만들 수 없는 환경");

            DeleteResult r = FileTrees.deleteTree(root);

            assertThat(root).doesNotExist();
            assertThat(r).isEqualTo(new DeleteResult(10, 0));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ── 실패 항목이 있어도 나머지는 계속 지운다 ──

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keeps_deleting_the_rest_when_one_file_is_locked() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Path locked = Files.writeString(root.resolve("locked.txt"), "x");
        Path free = Files.writeString(root.resolve("free.txt"), "y");

        // java.io 스트림은 FILE_SHARE_DELETE 없이 파일을 열어 Windows에서 삭제를 막는다
        try (FileInputStream in = new FileInputStream(locked.toFile())) {
            DeleteResult r = FileTrees.deleteTree(root);

            assertThat(r.failures()).isPositive();   // locked.txt, 그리고 비지 않은 root
            assertThat(free).doesNotExist();
            assertThat(locked).exists();
            assertThat(in.read()).isEqualTo('x');
        }

        // 잠금이 풀리면 같은 호출로 마저 지워진다 — 다음 회차 재시도의 전제
        assertThat(FileTrees.deleteTree(root).failures()).isZero();
        assertThat(root).doesNotExist();
    }
}
