package com.hamonsoft.netismaker.workerdaemon.patch;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 부분 패치 zip 쓰기: PATCH-INFO.txt, DELETED.txt, patch/&lt;경로&gt;. */
public final class PatchZipWriter {

    private static final String NONE = "(없음)";

    private PatchZipWriter() {
    }

    public static void write(Path zip, Path patchDir, PatchInfoDoc doc) throws IOException {
        long time = doc.createdAt().toInstant().toEpochMilli();
        List<Path> files;
        try (Stream<Path> s = Files.walk(patchDir)) {
            files = s.filter(Files::isRegularFile)
                    .sorted((a, b) -> entryName(patchDir, a).compareTo(entryName(patchDir, b)))
                    .toList();
        }
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            putText(zos, "PATCH-INFO.txt", infoText(doc), time);
            putText(zos, "DELETED.txt", deletedText(doc.delta().deleted()), time);
            for (Path f : files) {
                ZipEntry e = new ZipEntry("patch/" + entryName(patchDir, f));
                e.setTime(time);
                zos.putNextEntry(e);
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    private static String entryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void putText(ZipOutputStream zos, String name, String text, long time) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        zos.putNextEntry(e);
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String deletedText(List<String> deleted) {
        if (deleted.isEmpty()) {
            return NONE + "\n";
        }
        return String.join("\n", deleted) + "\n";
    }

    public static String infoText(PatchInfoDoc doc) {
        PatchDelta d = doc.delta();
        StringBuilder sb = new StringBuilder();
        sb.append("작업: #").append(doc.taskId()).append(' ').append(doc.title()).append('\n');
        sb.append("레포: ").append(doc.repoAlias()).append('\n');
        sb.append("패치 전 커밋: ").append(doc.beforeSha()).append('\n');
        sb.append("패치 후 커밋: ").append(doc.afterSha()).append('\n');
        sb.append("※ 이 패치는 '패치 전' 커밋 상태의 배포본에만 적용한다.\n");
        sb.append("만든 시각: ").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(doc.createdAt())).append('\n');
        sb.append("빌드 명령: ").append(doc.command()).append('\n');
        sb.append("추가 ").append(d.added().size()).append(" · 변경 ").append(d.changed().size())
                .append(" · 삭제 ").append(d.deleted().size()).append('\n');
        section(sb, "[추가]", d.added());
        section(sb, "[변경]", d.changed());
        section(sb, "[삭제]", d.deleted());
        section(sb, "[소스 변경(참고)]", doc.sourceChanges());
        return sb.toString();
    }

    private static void section(StringBuilder sb, String header, List<String> items) {
        sb.append(header).append('\n');
        if (items == null || items.isEmpty()) {
            sb.append(NONE).append('\n');
            return;
        }
        for (String item : items) {
            sb.append(item).append('\n');
        }
    }
}
