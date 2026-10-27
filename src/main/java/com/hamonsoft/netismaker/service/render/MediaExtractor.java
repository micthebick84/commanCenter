package com.hamonsoft.netismaker.service.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * pptx(zip)의 ppt/media/ 원본 그림을 mediaDir로 복사한다 (스펙 2026-10-27 §6 원본 그림).
 * 래스터 형식만(emf·wmf 같은 벡터는 슬라이드 그림에 이미 보인다), 10KB 미만(아이콘)은 빼고, 이름순으로 최대 maxMedia개.
 * zip 항목 이름의 경로(../ 등)는 버리고 파일명만 쓴다. 항목당 압축 해제 30MB를 넘으면 그 항목은 건너뛴다(zip 폭탄 방어).
 */
public final class MediaExtractor {

    static final String MEDIA_PREFIX = "ppt/media/";
    static final Set<String> RASTER_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    static final long MIN_BYTES = 10L * 1024;
    static final long MAX_ENTRY_BYTES = 30L * 1024 * 1024;

    private MediaExtractor() {}

    public static int extract(Path pptx, Path mediaDir, int maxMedia) throws IOException {
        if (maxMedia <= 0) return 0;
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            // 파일명 → 첫 항목. 경로를 버려 같은 이름이 둘이면 앞의 것만 쓴다.
            TreeMap<String, ZipEntry> candidates = new TreeMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(MEDIA_PREFIX)) continue;
                String name = fileNameOf(e.getName());
                if (name == null) continue;
                candidates.putIfAbsent(name, e);
            }
            int copied = 0;
            for (var c : candidates.entrySet()) {
                if (copied >= maxMedia) break;
                if (copyBounded(zip, c.getValue(), mediaDir, c.getKey())) copied++;
            }
            return copied;
        }
    }

    /** 마지막 구분자 뒤 파일명 — 허용 확장자가 아니거나 쓸 수 없는 이름이면 null. */
    static String fileNameOf(String entryName) {
        String name = entryName.substring(Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\')) + 1);
        if (name.isBlank() || name.startsWith(".")) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !RASTER_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) return null;
        return name;
    }

    /** 상한까지만 읽어 쓴다. 너무 작거나(아이콘) 상한을 넘으면 쓴 것을 지우고 false. */
    private static boolean copyBounded(ZipFile zip, ZipEntry entry, Path dir, String name) throws IOException {
        Path mediaDir = dir.toAbsolutePath().normalize();
        // 압축 해제 전에 알려진 크기로 먼저 거른다(헤더 값은 믿을 수 없으니 아래에서 실제 바이트로 다시 본다)
        if (entry.getSize() >= 0 && (entry.getSize() < MIN_BYTES || entry.getSize() > MAX_ENTRY_BYTES)) return false;
        Path target;
        try {
            target = mediaDir.resolve(name).normalize();
        } catch (InvalidPathException e) {
            return false;
        }
        if (!mediaDir.equals(target.getParent())) return false;
        Files.createDirectories(mediaDir);
        long written = 0;
        boolean tooBig = false;
        try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                written += n;
                if (written > MAX_ENTRY_BYTES) { tooBig = true; break; }
                out.write(buf, 0, n);
            }
        }
        if (tooBig || written < MIN_BYTES) {
            Files.deleteIfExists(target);
            return false;
        }
        return true;
    }
}
