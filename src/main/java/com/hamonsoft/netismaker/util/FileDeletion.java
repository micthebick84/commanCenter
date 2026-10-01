package com.hamonsoft.netismaker.util;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

/**
 * 정리 잡(RepoCacheCleanupJob · AttachmentCleanupJob)이 공유하는 항목 1개 삭제.
 *
 * Windows(POSIX 권한이 없는 파일시스템)는 DOS 읽기 전용 속성이 붙은 파일을 Files.delete로 못 지운다
 * (AccessDeniedException — git pack/idx, 백업·동기화 도구가 붙인 속성 등). 그때만 속성을 풀고 한 번 더 지운다.
 * 링크를 따라가지 않도록 NOFOLLOW_LINKS 뷰로만 만진다. POSIX에선 읽기 전용이 삭제를 막지 않으므로
 * AccessDeniedException은 그대로 던진다(진짜 권한 문제).
 */
public final class FileDeletion {

    /** DOS 읽기 전용 속성이 실제 삭제를 막는 파일시스템(= POSIX 권한이 없는 Windows). */
    private static final boolean DOS_READ_ONLY_BLOCKS_DELETE =
            !FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private FileDeletion() {}

    /** Files.delete와 같은 계약(없으면 NoSuchFileException) + Windows 읽기 전용 속성 해제 후 재시도. */
    public static void deleteClearingReadOnly(Path p) throws IOException {
        try {
            Files.delete(p);
        } catch (AccessDeniedException e) {
            DosFileAttributeView dos = DOS_READ_ONLY_BLOCKS_DELETE
                    ? Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    : null;
            if (dos == null) throw e;
            dos.setReadOnly(false);
            Files.delete(p);
        }
    }
}
