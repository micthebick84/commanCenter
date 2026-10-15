package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 구현 세션의 워커 임시 파일 (스펙 2026-10-15 §6) — .netis/ 아래 계획·설계 파일(리뷰어·SDD가 읽음)과 리뷰 메모(PR 본문으로
 * 회수), 그리고 커밋·WIP 보존 전에 지우는 정리. 이 이름들은 WorktreeService.WORKER_EXCLUDES로 커밋에서도 빠진다.
 */
@Slf4j
final class ImplementationScratch {

    static final String ROOT = ".netis";
    static final String REVIEW_NOTES = ".netis/review-notes.md";
    static final int REVIEW_NOTES_MAX_CHARS = 20_000;

    /** SDD task-brief(awk)가 찾는 제목 — `^#+[ \t]+Task[ \t]+N`. */
    private static final Pattern TASK_HEADING = Pattern.compile("^#+[ \\t]+Task[ \\t]+[0-9]+");
    /** 인터뷰 플랜의 한국어 제목 `### 작업 N`. */
    private static final Pattern KOREAN_TASK_HEADING = Pattern.compile("^(#+[ \\t]+)작업([ \\t]+[0-9]+)");

    private ImplementationScratch() {}

    static String planPath(long taskId) {
        return ROOT + "/plan/task-" + taskId + "-plan.md";
    }

    static String designPath(long taskId) {
        return ROOT + "/plan/task-" + taskId + "-design.md";
    }

    static void write(Path worktree, String relPath, String content) throws IOException {
        Path target = worktree.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** 코드 블록(```) 밖의 `### 작업 N` 제목을 `### Task N`으로 — SDD 스크립트는 `Task N`만 찾는다 (스펙 §6.4). */
    static String normalizeTaskHeadings(String plan) {
        if (plan == null) return "";
        String[] lines = plan.split("\n", -1);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) lines[i] = KOREAN_TASK_HEADING.matcher(lines[i]).replaceFirst("$1Task$2");
        }
        return String.join("\n", lines);
    }

    /** task-brief와 같은 규칙(코드 블록 밖)으로 센 `Task N` 제목 수 — 0이면 SDD가 태스크를 못 찾는다. */
    static int countTaskHeadings(String plan) {
        if (plan == null) return 0;
        int count = 0;
        boolean inFence = false;
        for (String line : plan.split("\n", -1)) {
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && TASK_HEADING.matcher(line).find()) count++;
        }
        return count;
    }

    /**
     * 리뷰 메모 회수 — 없거나 비었거나 읽지 못하면 empty. 심볼릭 링크는 따라가지 않는다(에이전트가 다른 파일을 가리키게
     * 해 PR 본문으로 빼내지 못하게). 인증 URL은 가리고 상한에서 자른다 (스펙 §6.7).
     */
    static Optional<String> readReviewNotes(Path worktree) {
        Path notes = worktree.resolve(REVIEW_NOTES);
        try {
            if (!Files.isRegularFile(notes, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            String text = GitRemotes.mask(Files.readString(notes, StandardCharsets.UTF_8)).strip();
            if (text.isEmpty()) return Optional.empty();
            if (text.length() > REVIEW_NOTES_MAX_CHARS) {
                text = text.substring(0, REVIEW_NOTES_MAX_CHARS) + "\n\n…(이하 생략)";
            }
            return Optional.of(text);
        } catch (Exception e) {
            log.warn("리뷰 메모 읽기 실패 (PR 본문에서 생략): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 성공 경로 — .netis/만 지운다(.design/·배포 env 목록은 기존 코드가 따로 처리). 링크는 따라가지 않는다. */
    static void deleteRoot(Path worktree) {
        deleteTreeQuietly(worktree.resolve(ROOT));
    }

    /** 중단 경로(WIP 보존 전) — 워커 임시 파일 전부. */
    static void deleteAll(Path worktree) {
        deleteTreeQuietly(worktree.resolve(ROOT));
        deleteTreeQuietly(worktree.resolve(".design"));
        try {
            Files.deleteIfExists(worktree.resolve(DeployEnvManifest.FILE_NAME));
        } catch (IOException e) {
            log.warn("배포 env 목록 삭제 실패: {}", e.getMessage());
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
        if (d.failures() > 0) log.warn("구현 임시 파일 삭제 미완료: {} ({}개 항목 실패)", dir, d.failures());
    }
}
