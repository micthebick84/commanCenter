package com.hamonsoft.netismaker.workerdaemon.patch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 시험용 압축 파일 도우미. 항목 순서는 맵 순회 순서(LinkedHashMap)를 따르고 시각은 직접 정한다. */
final class PatchTestZips {

    private PatchTestZips() {
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zipBytes(Map<String, byte[]> entries, long entryTime) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(entryTime);
                zos.putNextEntry(ze);
                if (!e.getKey().endsWith("/")) {
                    zos.write(e.getValue());
                }
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static Path zip(Path file, Map<String, byte[]> entries, long entryTime) throws IOException {
        Files.write(file, zipBytes(entries, entryTime));
        return file;
    }
}
