package com.hamonsoft.netismaker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 디렉터리 트리 삭제 — 워커 레포 캐시 정리(RepoCacheCleanupJob)와 API 체크아웃 정리(InterviewCheckoutCleanupJob)가
 * 함께 쓴다. 링크를 따라가지 않는 것이 이 클래스의 계약이다.
 */
@Slf4j
public final class FileTrees {

    private FileTrees() {}

    /** 해제한 바이트(일반 파일 크기 합)와 지우지 못한 항목 수. failures가 0이 아니면 일부가 남아 있다. */
    public record DeleteResult(long freedBytes, int failures) {}

    /**
     * root 이하를 지운다. 링크를 따라가지 않는다 — FOLLOW_LINKS 없이 걸으면 심볼릭 링크는 visitFile로 와서
     * 링크 자체만 지워지고, Windows 정션(리파스 포인트)은 isOther라 안으로 들어가지 않고 링크만 지운다.
     * 실패 항목이 있어도 나머지는 계속 지워 최대한 비운다. root가 이미 없으면 실패가 아니다.
     */
    public static DeleteResult deleteTree(Path root) {
        long[] freed = {0};
        int[] failures = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // root 자신이 정션이어도 마찬가지 — 대상 쪽으로 내려가지 않는다
                    if (attrs.isOther()) {
                        if (!deleteEntry(dir)) failures[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (deleteEntry(file)) {
                        if (attrs.isRegularFile()) freed[0] += attrs.size();
                    } else {
                        failures[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (!(exc instanceof NoSuchFileException)) {
                        log.debug("삭제 대상 읽기 실패: {} ({})", file, exc.toString());
                        failures[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!deleteEntry(dir)) failures[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failures[0]++;
        }
        return new DeleteResult(freed[0], failures[0]);
    }

    /** 항목 1개 삭제. 이미 없으면 성공으로 본다. Windows: git pack/idx는 읽기 전용 속성이라 FileDeletion이 풀고 지운다. */
    private static boolean deleteEntry(Path p) {
        try {
            FileDeletion.deleteClearingReadOnly(p);
            return true;
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException e) {
            log.debug("삭제 실패: {} ({})", p, e.toString());
            return false;
        }
    }
}
