package com.hamonsoft.netismaker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
     * 대상이 없는 정션(끊긴 정션, 같은 트리 안 대상이 먼저 지워진 정션)도 링크 항목만 지운다(visitFileFailed).
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
                    // 항목을 NOFOLLOW로 다시 읽는다. 정션은 NOFOLLOW로 읽어도 디렉터리라 walker가 preVisitDirectory보다 먼저
                    // 대상 쪽 디렉터리 스트림을 연다. 대상이 없으면(끊긴 정션, 같은 트리 안 대상이 이름순으로 먼저 지워진
                    // pnpm식 node_modules/react → .pnpm/…) 그 열기가 NoSuchFileException으로 실패해 여기로 온다.
                    // 이걸 '이미 없음'으로 넘기면 정션 항목은 남고 부모 폴더 삭제가 DirectoryNotEmpty로 계속 실패한다.
                    BasicFileAttributes attrs = null;
                    try {
                        attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException e) {
                        return FileVisitResult.CONTINUE;   // 항목 자체가 없다 — 이미 지워진 것
                    } catch (IOException e) {
                        // 다시 읽지도 못하면 아래에서 원래 오류로 판단한다
                    }
                    if (attrs != null && (attrs.isSymbolicLink() || attrs.isOther())) {
                        // 링크 항목이 아직 있다 — 그 항목만 지운다(대상은 건드리지 않는다). 이게 실패할 때만 실패로 센다.
                        if (!deleteEntry(file)) failures[0]++;
                    } else if (!(exc instanceof NoSuchFileException)) {
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
