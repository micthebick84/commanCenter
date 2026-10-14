package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
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
import java.util.regex.Pattern;

/**
 * 종료 산출물 파일 (스펙 2026-10-14 §5). 위치 {app.attachment.dir}/task-{id}/artifact/{이름} —
 * 작업을 소프트 삭제하면 30일 뒤 AttachmentCleanupJob이 task-{id} 폴더째 지운다(새 정리 코드 없음).
 */
@Component
@Profile("api")
public class ArtifactStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    private final Path root;

    public ArtifactStore(@Value("${app.attachment.dir:${user.home}/netis-maker/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && (name.endsWith(".war") || name.endsWith(".jar"))
                && !name.startsWith(".");
    }

    public Path dir(long taskId) {
        return root.resolve("task-" + taskId).resolve("artifact");
    }

    public Path file(long taskId, String name) {
        if (!validName(name)) throw new IllegalArgumentException("산출물 이름이 올바르지 않습니다: " + name);
        return dir(taskId).resolve(name);
    }

    public boolean exists(long taskId, String name) {
        return validName(name) && Files.isRegularFile(file(taskId, name));
    }

    public static final class TooLarge extends IOException {
        TooLarge(long maxBytes) {
            super("산출물이 상한(" + (maxBytes / 1024 / 1024) + "MB)을 넘습니다");
        }
    }

    public static final class HashMismatch extends IOException {
        HashMismatch(String expected, String actual) {
            super("sha256 불일치 (보낸 값 " + expected + ", 받은 값 " + actual + ")");
        }
    }

    /**
     * 본문을 {이름}.part로 받으며 sha256·크기를 센다. 상한을 넘거나 해시가 다르면 .part를 지우고 예외.
     * 맞으면 {이름}으로 옮기고 같은 폴더의 다른 파일을 지운다(작업당 하나).
     */
    public ArtifactInfo write(long taskId, String name, InputStream body, String expectedSha256, long maxBytes)
            throws IOException {
        Path target = file(taskId, name);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(name + ".part");
        MessageDigest md = sha256();
        long total = 0;
        try (InputStream in = body; OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                total += n;
                if (total > maxBytes) throw new TooLarge(maxBytes);
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(part);
            throw new HashMismatch(expectedSha256, actual);
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Windows 등에서 원자적 이동이 안 되거나 기존 파일에 막히면(스펙: 실패 시 REPLACE_EXISTING) 덮어쓰는 이동으로 대체한다.
            // 이것도 실패하면 그 예외가 그대로 올라간다.
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target.getParent())) {
            for (Path p : ds) {
                if (!p.getFileName().equals(target.getFileName())) Files.deleteIfExists(p);
            }
        }
        return new ArtifactInfo(name, total, actual);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
