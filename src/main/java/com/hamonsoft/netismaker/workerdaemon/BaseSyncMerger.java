package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 기준 브랜치 반영(스펙 2026-11-13)의 순수 git 단계 — 작업 브랜치 끝에 detached로 놓인 worktree에서 기준 브랜치를 병합하기 시작하고,
 * 충돌 파일을 나열·검증하고, 병합 커밋을 만든다. Claude도 API도 모른다(워커 루프가 이어 붙인다). 상태가 없다.
 *
 * 목록을 읽는 git 출력은 {@code -z}(NUL 분리 — 공백·한글 경로를 따옴표 없이)이고, 표준 출력만 읽는다(stderr의 경고 줄이
 * 섞이지 않게 — ProcessRunner는 둘을 합친다). 예/아니오 질문은 종료 코드로 답한다. 경로 인자는 {@code --literal-pathspecs}로 준다.
 */
@Component
@Profile("worker")
public class BaseSyncMerger {

    /** 병합 커밋이 될 수 없는 상태 */
    public static class NotMergingException extends IOException {
        public NotMergingException(String message) { super(message); }
    }

    public enum Outcome { UP_TO_DATE, CLEAN, CONFLICTED }

    /**
     * @param conflicted       병합 안 된 경로(NUL 분리 출력 그대로의 경로)
     * @param binaryConflicted conflicted 중 바이너리로 보이는 것(텍스트 표식으로 해결할 수 없다)
     * @param markerless       conflicted 중 바이너리가 아니면서 작업 트리 파일이 없거나 {@code <<<<<<< } 줄이 없는 것(한쪽 삭제·이름 변경 충돌 등).
     *                         표식 검사로는 풀렸는지 알 수 없으므로 호출부(작업 7)는 Claude를 돌리지 않고 이 회차를 실패시킨다
     * @param indexSnapshot    충돌 경로를 뺀 스테이징 상태 — {@link #strayChanges}가 비교하는 기준
     */
    public record Started(Outcome outcome, List<String> conflicted, List<String> binaryConflicted,
                          List<String> markerless,
                          String indexSnapshot) {}

    private static final int BINARY_SNIFF_BYTES = 8000;
    private static final long TIMEOUT = GitRepoCache.GIT_TIMEOUT_SECONDS;

    private final WorkerProperties props;

    public BaseSyncMerger(WorkerProperties props) {
        this.props = props;
    }

    public Started start(File worktreeDir, String baseRef) throws IOException, InterruptedException {
        int anc = ProcessRunner.run(worktreeDir,
                List.of("git", "merge-base", "--is-ancestor", baseRef, "HEAD"), TIMEOUT).exitCode();
        if (anc == 0) return new Started(Outcome.UP_TO_DATE, List.of(), List.of(), List.of(), "");
        if (anc != 1) throw new IOException("git merge-base 실패 (" + anc + ")");

        ProcessRunner.Result merge = ProcessRunner.run(worktreeDir, withIdentity("merge", "--no-ff", "--no-commit", baseRef),
                TIMEOUT);
        List<String> conflicted = unmergedPaths(worktreeDir);
        if (conflicted.isEmpty()) {
            if (merge.exitCode() != 0) {
                throw new IOException("git merge 실패: " + GitRemotes.maskAndCut(tail(merge.stdout(), 500), 500));
            }
            return new Started(Outcome.CLEAN, List.of(), List.of(), List.of(), snapshot(worktreeDir, Set.of()));
        }
        List<String> binary = new ArrayList<>();
        for (String path : conflicted) {
            if (isBinary(worktreeDir, path)) binary.add(path);
        }
        List<String> markerless = new ArrayList<>();
        for (String path : conflicted) {
            if (!binary.contains(path) && !hasOpeningMarker(worktreeDir.toPath().resolve(path))) markerless.add(path);
        }
        return new Started(Outcome.CONFLICTED, List.copyOf(conflicted), List.copyOf(binary), List.copyOf(markerless),
                snapshot(worktreeDir, new HashSet<>(conflicted)));
    }

    /**
     * 아직 풀리지 않은 경로. 충돌 파일 중 줄 머리가 {@code <<<<<<< }·{@code >>>>>>> }인 줄이 남은 것과, 인덱스에 병합 안 됨으로
     * 남았는데 {@code conflicted} 목록에 없는 경로(커밋할 수 없다). {@code =======}만 있는 줄은 보지 않는다(Markdown 제목 밑줄).
     * 작업 트리에서 사라진 충돌 파일은 미해결이다 — 표식 없는 충돌은 Claude를 돌리기 전에 거절되므로 여기 오는 경로는 모두
     * 표식 있는 파일이었고, 지워 버린 세션은 병합한 것이 아니다. 표식 없이 남은 파일은 미해결이 아니다 — 스테이징은
     * {@link #commitMerge}가 한다.
     */
    public List<String> unresolved(File worktreeDir, List<String> conflicted) throws IOException, InterruptedException {
        Set<String> out = new LinkedHashSet<>();
        for (String path : conflicted) {
            Path file = worktreeDir.toPath().resolve(path);
            if (!Files.exists(file) || hasMarkers(file)) out.add(path);
        }
        Set<String> known = new HashSet<>(conflicted);
        for (String path : unmergedPaths(worktreeDir)) {
            if (!known.contains(path) || hasMarkers(worktreeDir.toPath().resolve(path))) out.add(path);
        }
        return new ArrayList<>(out);
    }

    /** 충돌 파일 밖에서 바뀐 경로 — 스테이징된 변경·미스테이징 변경·새 파일(워커 exclude는 {@code --exclude-standard}가 거른다). */
    public List<String> strayChanges(File worktreeDir, Started started) throws IOException, InterruptedException {
        Set<String> conflicted = new HashSet<>(started.conflicted());
        Set<String> out = new TreeSet<>();
        Map<String, String> before = parseSnapshot(started.indexSnapshot());
        Map<String, String> now = parseSnapshot(snapshot(worktreeDir, conflicted));
        for (var e : now.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) out.add(e.getKey());
        }
        for (String path : before.keySet()) {
            if (!now.containsKey(path)) out.add(path);
        }
        out.addAll(nulList(worktreeDir, "diff", "--name-only", "-z"));
        out.addAll(nulList(worktreeDir, "ls-files", "--others", "--exclude-standard", "-z"));
        out.removeAll(conflicted);
        return new ArrayList<>(out);
    }

    /**
     * 충돌 경로를 스테이징하고 병합 커밋을 만든다(트리가 HEAD와 같아도 병합 중이면 커밋된다). 새 HEAD sha.
     * 커밋 전에 아직 병합 중인지(MERGE_HEAD가 있고 HEAD가 처음 받은 끝 {@code tipSha}인지), 커밋 뒤에 진짜 병합 커밋인지
     * (첫 부모가 {@code tipSha}이고 {@code baseRef}가 조상인지) 확인한다 — Claude 세션이 병합을 취소하고 손으로 고쳐
     * 한 부모 커밋이 만들어지는 것을 막는다. 아니면 {@link NotMergingException}.
     */
    public String commitMerge(File worktreeDir, List<String> conflicted, String message, String tipSha, String baseRef)
            throws IOException, InterruptedException {
        int mergeHead = ProcessRunner.run(worktreeDir, List.of("git", "rev-parse", "-q", "--verify", "MERGE_HEAD"), TIMEOUT)
                .exitCode();
        if (mergeHead != 0 || !sameSha(revParse(worktreeDir, "HEAD"), tipSha)) {
            throw new NotMergingException("병합 상태가 아님 — 병합이 취소됐거나 다른 커밋이 만들어짐");
        }
        if (!conflicted.isEmpty()) {
            List<String> add = new ArrayList<>(List.of("git", "--literal-pathspecs", "add", "-A", "--"));
            add.addAll(conflicted);
            ProcessRunner.requireSuccess(worktreeDir, add, TIMEOUT);
        }
        ProcessRunner.requireSuccess(worktreeDir, withIdentity("commit", "--no-edit", "-m", message), TIMEOUT);
        String head = GitOpsService.revParseHead(worktreeDir);
        boolean baseMerged = ProcessRunner.run(worktreeDir,
                List.of("git", "merge-base", "--is-ancestor", baseRef, "HEAD"), TIMEOUT).exitCode() == 0;
        if (!sameSha(revParse(worktreeDir, "HEAD^1"), tipSha) || !baseMerged) {
            throw new NotMergingException("병합 커밋 확인 실패 — 기준 브랜치가 반영되지 않음");
        }
        return head;
    }

    private static String revParse(File dir, String rev) throws IOException, InterruptedException {
        return ProcessRunner.requireSuccess(dir, List.of("git", "rev-parse", rev), TIMEOUT).trim();
    }

    private static boolean sameSha(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    // ── 내부 ──

    private List<String> withIdentity(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git",
                "-c", "user.name=" + props.gitUserName(),
                "-c", "user.email=" + props.gitUserEmail()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static List<String> unmergedPaths(File dir) throws IOException, InterruptedException {
        return nulList(dir, "diff", "--name-only", "--diff-filter=U", "-z");
    }

    /** 바이너리 판정 — numstat의 추가·삭제가 {@code -}이거나 작업 트리 파일 앞 8000바이트에 NUL. */
    private static boolean isBinary(File dir, String path) throws IOException, InterruptedException {
        Out numstat = gitOut(dir, List.of("git", "--literal-pathspecs", "diff", "--numstat", "--", path));
        if (numstat.exit == 0 && numstat.text.lines().anyMatch(l -> l.startsWith("-\t-\t"))) return true;
        Path file = dir.toPath().resolve(path);
        if (!Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(BINARY_SNIFF_BYTES);
            for (byte b : head) if (b == 0) return true;
        }
        return false;
    }

    private static boolean hasOpeningMarker(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("<<<<<<< ")) return true;
        }
        return false;
    }

    private static boolean hasMarkers(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("<<<<<<< ") || line.startsWith(">>>>>>> ")) return true;
        }
        return false;
    }

    /** {@code git ls-files -s -z}에서 {@code excluded} 경로의 항목을 뺀 문자열(NUL 분리). */
    private static String snapshot(File dir, Set<String> excluded) throws IOException, InterruptedException {
        return String.join("\0", snapshotEntries(dir, excluded).values());
    }

    private static Map<String, String> snapshotEntries(File dir, Set<String> excluded)
            throws IOException, InterruptedException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String entry : nulList(dir, "ls-files", "-s", "-z")) {
            int tab = entry.indexOf('\t');
            if (tab < 0) continue;
            String path = entry.substring(tab + 1);
            if (!excluded.contains(path)) entries.merge(path, entry, (a, b) -> a + "\u0001" + b);
        }
        return entries;
    }

    private static Map<String, String> parseSnapshot(String snapshot) {
        Map<String, String> m = new LinkedHashMap<>();
        if (snapshot.isEmpty()) return m;
        for (String entry : snapshot.split("\0")) {
            int tab = entry.indexOf('\t');
            if (tab < 0) continue;
            m.merge(entry.substring(tab + 1), entry, (a, b) -> a + "\u0001" + b);
        }
        return m;
    }

    private static List<String> nulList(File dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "--literal-pathspecs"));
        cmd.addAll(List.of(args));
        Out out = gitOut(dir, cmd);
        if (out.exit != 0) throw new IOException("git " + args[0] + " 실패 (" + out.exit + ")");
        List<String> list = new ArrayList<>();
        for (String s : out.text.split("\0")) if (!s.isEmpty()) list.add(s);
        return list;
    }

    private record Out(int exit, String text) {}

    /** 표준 출력만(UTF-8, 줄 변환 없음) 읽는 git 실행 — stderr는 버린다. 시간 상한에서 프로세스를 끊는다. */
    private static Out gitOut(File dir, List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            } catch (IOException ignore) { /* 프로세스를 죽이면 끊긴다 */ }
        }, "base-sync-git-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(TIMEOUT, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("process timeout (" + TIMEOUT + "s): git");
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        synchronized (buf) {
            return new Out(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
        }
    }

    private static String tail(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(s.length() - max);
    }

}
