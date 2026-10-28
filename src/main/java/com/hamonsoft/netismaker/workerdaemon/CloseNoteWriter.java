package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 종료 노트를 볼트에 쓴다 (스펙 2026-10-13 §4 ③). 파일 이름·진행상황 추기는 여기서 결정론으로 — claude는 본문만 만든다.
 *  - 노트: {noteFolder}/{slug}-작업{id}-{date}.md  (같은 경로면 덮어씀 — 재시도 멱등)
 *    산출물이 있으면 프런트매터에 artifact, 본문 끝에 한 줄(NoteMeta.artifactLine)을 더한다 (스펙 2026-10-14 §4)
 *  - 진행상황: {progressFolder}/netisMaker-진행상황-{date}.md 끝에 "- [[노트]] — 작업 #id 종료(병합 sha7)" (이미 있으면 생략)
 */
public class CloseNoteWriter {

    /**
     * artifact = 프런트매터 값(파일 이름 또는 '없음(관리자 건너뜀)'), artifactLine = 본문 끝 한 줄. 둘 다 null이면 산출물 표시 없음.
     */
    public record NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha,
                           String artifact, String artifactLine) {
        /** 산출물 정보 도입 전 4개 인자 — 기존 호출부·테스트 호환. */
        public NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha) {
            this(taskId, title, prUrl, mergeCommitSha, null, null);
        }
    }

    public record Written(String relativePath, Path file) {}

    private static final int SLUG_MAX = 60;

    /** URL 밖에 맨몸으로 나온 토큰(GitHub classic/fine-grained PAT, GitLab PAT, Anthropic 키). */
    private static final Pattern BARE_TOKENS = Pattern.compile(
            "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{16,}");

    /** 노트와 진행상황을 같은 폴더에 — 노트 폴더 분리 전 호출부 호환. */
    public Written write(Path vault, String folder, NoteMeta meta, String body, LocalDate date) throws IOException {
        return write(vault, folder, folder, meta, body, date);
    }

    /**
     * 노트는 레포 기준 폴더에, 진행상황 줄은 한 곳(CLOSE_NOTE_FOLDER)에 (스펙 2026-10-28 close-note-folder §4).
     * 위키링크는 이름으로 찾으므로 폴더가 달라도 열린다. relativePath는 볼트 기준.
     */
    public Written write(Path vault, String noteFolder, String progressFolder, NoteMeta meta, String body,
                         LocalDate date) throws IOException {
        Path dir = insideVault(vault, noteFolder);
        Path progressDir = insideVault(vault, progressFolder);
        Files.createDirectories(dir);
        Files.createDirectories(progressDir);

        String name = slug(meta.title()) + "-작업" + meta.taskId() + "-" + date;
        Path file = dir.resolve(name + ".md");
        String tail = meta.artifactLine() == null ? "" : "\n\n" + maskSecrets(meta.artifactLine());
        Files.writeString(file, frontmatter(meta, date) + clean(body) + tail + "\n", StandardCharsets.UTF_8);

        appendProgress(progressDir.resolve("netisMaker-진행상황-" + date + ".md"), name, meta, date);
        return new Written(noteFolder.replace('\\', '/') + "/" + name + ".md", file);
    }

    private static Path insideVault(Path vault, String folder) throws IOException {
        Path dir = vault.resolve(folder).normalize();
        if (!dir.startsWith(vault.normalize())) throw new IOException("노트 폴더가 볼트 밖입니다: " + folder);
        return dir;
    }

    /** 경로·위키링크에 문제 되는 문자를 '-'로, 공백도 '-'로. 60자(코드포인트) 상한, 비면 "작업". */
    static String slug(String title) {
        String s = (title == null ? "" : title)
                .replaceAll("[\\\\/:*?\"<>|#^\\[\\]]", " ")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (s.isEmpty()) return "작업";
        int[] cps = s.codePoints().limit(SLUG_MAX).toArray();
        return new String(cps, 0, cps.length).replaceAll("-$", "");
    }

    private static String frontmatter(NoteMeta m, LocalDate date) {
        return "---\n"
                + "task_id: " + m.taskId() + "\n"
                + "pr: " + (m.prUrl() == null ? "" : maskSecrets(m.prUrl())) + "\n"
                + "merge_commit: " + (m.mergeCommitSha() == null ? "" : m.mergeCommitSha()) + "\n"
                + (m.artifact() == null ? "" : "artifact: " + m.artifact() + "\n")
                + "closed: " + date + "\n"
                + "tags:\n  - netismaker/작업종료\n"
                + "---\n\n";
    }

    /** ```markdown … ``` 로 통째로 감싼 응답을 벗기고 자격증명을 가린다. */
    static String clean(String body) {
        String b = body == null ? "" : body.strip();
        if (b.startsWith("```")) {
            int nl = b.indexOf('\n');
            b = nl < 0 ? "" : b.substring(nl + 1);
            if (b.endsWith("```")) b = b.substring(0, b.length() - 3);
            b = b.strip();
        }
        return maskSecrets(b);
    }

    /** URL 안 자격증명(GitRemotes.mask) + 맨 토큰 패턴을 가린다. */
    static String maskSecrets(String text) {
        return BARE_TOKENS.matcher(GitRemotes.mask(text)).replaceAll("***");
    }

    private static void appendProgress(Path progress, String noteName, NoteMeta m, LocalDate date) throws IOException {
        String link = "[[" + noteName + "]]";
        String existing = Files.exists(progress) ? Files.readString(progress, StandardCharsets.UTF_8) : null;
        if (existing != null && existing.contains(link)) return;
        String sha = m.mergeCommitSha() == null ? "" : "(병합 " + m.mergeCommitSha().substring(0, Math.min(7, m.mergeCommitSha().length())) + ")";
        String line = "- " + link + " — 작업 #" + m.taskId() + " 종료" + sha + "\n";
        if (existing == null) {
            Files.writeString(progress, "# netisMaker 진행상황 " + date + "\n\n" + line, StandardCharsets.UTF_8);
        } else {
            String prefix = existing.endsWith("\n") ? "" : "\n";
            Files.writeString(progress, prefix + line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }
}
