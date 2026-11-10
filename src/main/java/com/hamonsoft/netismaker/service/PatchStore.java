package com.hamonsoft.netismaker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 부분 패치 zip 파일 (스펙 2026-11-10). 위치 {app.attachment.dir}/task-{id}/patch/patch.zip — 사람이 보는 이름은 DB의
 * patch_name이고 디스크 이름은 고정이다. 산출물 폴더와 따로라 서로 지우지 않는다. 작업을 소프트 삭제하면 30일 뒤
 * AttachmentCleanupJob이 task-{id} 폴더째 지운다.
 */
@Component
@Profile("api")
public class PatchStore {

    static final String FILE_NAME = "patch.zip";

    private final Path root;

    public PatchStore(@Value("${app.attachment.dir:${user.home}/netis-maker/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public Path file(long taskId) {
        return root.resolve("task-" + taskId).resolve("patch").resolve(FILE_NAME);
    }

    public boolean exists(long taskId) {
        return Files.isRegularFile(file(taskId));
    }

    /**
     * 본문을 patch.zip.part로 받으며 sha256·크기를 센다. 상한을 넘거나 해시가 다르면 .part를 지우고 예외(기존 patch.zip은 그대로).
     * 맞으면 patch.zip으로 옮겨 이전 파일을 바꾼다. 다른 폴더·파일은 건드리지 않는다. 받은 바이트 수를 돌려준다.
     */
    public long write(long taskId, InputStream body, String expectedSha256, long maxBytes) throws IOException {
        Path target = file(taskId);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(FILE_NAME + ".part");
        MessageDigest md = sha256();
        long total = 0;
        try (InputStream in = body; OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                total += n;
                if (total > maxBytes) throw new ArtifactStore.TooLarge(maxBytes);
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            ArtifactStore.discard(part, e);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            ArtifactStore.HashMismatch mismatch = new ArtifactStore.HashMismatch(expectedSha256, actual);
            ArtifactStore.discard(part, mismatch);
            throw mismatch;
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 기존 파일에 막히거나 원자 이동이 안 되는 파일시스템 — 덮어쓰는 이동으로 대체한다(ArtifactStore와 같은 규칙).
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                e2.addSuppressed(e);
                ArtifactStore.discard(part, e2);
                throw e2;
            }
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
