package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * 종료 노트를 볼트에 쓴다 (스펙 2026-10-13 §4 ③). 파일 이름·진행상황 추기는 여기서 결정론으로 — claude는 본문만 만든다.
 *  - 노트: {folder}/{slug}-작업{id}-{date}.md  (같은 경로면 덮어씀 — 재시도 멱등)
 *  - 진행상황: {folder}/netisMaker-진행상황-{date}.md 끝에 "- [[노트]] — 작업 #id 종료(병합 sha7)" (이미 있으면 생략)
 */
public class CloseNoteWriter {

    public record NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha) {}

    public record Written(String relativePath, Path file) {}

    private static final int SLUG_MAX = 60;

    public Written write(Path vault, String folder, NoteMeta meta, String body, LocalDate date) throws IOException {
        Path dir = vault.resolve(folder).normalize();
        if (!dir.startsWith(vault.normalize())) throw new IOException("노트 폴더가 볼트 밖입니다: " + folder);
        Files.createDirectories(dir);

        String name = slug(meta.title()) + "-작업" + meta.taskId() + "-" + date;
        Path file = dir.resolve(name + ".md");
        Files.writeString(file, frontmatter(meta, date) + clean(body) + "\n", StandardCharsets.UTF_8);

        appendProgress(dir.resolve("netisMaker-진행상황-" + date + ".md"), name, meta, date);
        return new Written(folder.replace('\\', '/') + "/" + name + ".md", file);
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
                + "pr: " + (m.prUrl() == null ? "" : m.prUrl()) + "\n"
                + "merge_commit: " + (m.mergeCommitSha() == null ? "" : m.mergeCommitSha()) + "\n"
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
        return GitRemotes.mask(b);
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
