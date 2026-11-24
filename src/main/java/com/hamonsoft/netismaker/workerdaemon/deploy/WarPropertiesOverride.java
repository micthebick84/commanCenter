package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.dto.DeployOverrides.FileOverride;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 미리 빌드한 WAR 안의 {@code WEB-INF/classes/**}{@code /*.properties}를 꺼내 덮어쓰기 줄을 덧붙인 사본을 만든다.
 * 사본은 컨테이너의 클래스패스 앞쪽에 놓여 WAR의 원본보다 먼저 읽힌다.
 * 값은 로그에 남기지 않는다.
 */
public final class WarPropertiesOverride {

    private static final char BS = 92; // 역슬래시
    static final String COMMENT = "# Symphony 배포 덮어쓰기";

    private WarPropertiesOverride() {
    }

    /** WAR 안 항목을 ISO-8859-1로 읽는다(바이트를 잃지 않는 1:1 문자열). 없으면 {@link NoSuchFileException}. */
    public static String readEntry(Path war, String entryPath) throws IOException {
        return new String(readEntryBytes(war, entryPath), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readEntryBytes(Path war, String entryPath) throws IOException {
        try (ZipFile zip = new ZipFile(war.toFile())) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null || entry.isDirectory()) {
                throw new NoSuchFileException(entryPath);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /** 원본 뒤에 덮어쓰기 블록을 붙인 문자열(시험·확인용). */
    public static String append(String original, Map<String, String> entries) {
        return original + block(original, entries);
    }

    private static String block(String original, Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        if (!original.isEmpty() && !original.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(COMMENT).append('\n');
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append(escape(e.getKey(), true)).append('=').append(escape(e.getValue(), false)).append('\n');
        }
        return sb.toString();
    }

    /** 원본 바이트는 그대로, 덧붙인 블록은 UTF-8로 쓴다(v6.7이 UTF-8로 읽는다). */
    public static void writeOverrides(Path war, List<FileOverride> files, Path overrideRoot) throws IOException {
        Path root = overrideRoot.toAbsolutePath().normalize();
        for (FileOverride f : files) {
            Path target = root.resolve(f.path()).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                throw new IOException("덮어쓰기 경로가 루트 밖입니다: " + f.path());
            }
            byte[] original = readEntryBytes(war, f.path());
            String originalText = new String(original, StandardCharsets.ISO_8859_1);
            byte[] block = block(originalText, f.entries()).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(target.getParent());
            byte[] out = new byte[original.length + block.length];
            System.arraycopy(original, 0, out, 0, original.length);
            System.arraycopy(block, 0, out, original.length, block.length);
            Files.write(target, out);
        }
    }

    /** {@code Properties.store}와 같은 규칙. */
    private static String escape(String s, boolean key) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ' ' -> {
                    if (key || i == 0) {
                        sb.append(BS);
                    }
                    sb.append(' ');
                }
                case 92 -> sb.append(BS).append(BS);
                case 9 -> sb.append(BS).append('t');
                case 10 -> sb.append(BS).append('n');
                case 13 -> sb.append(BS).append('r');
                case 12 -> sb.append(BS).append('f');
                case '=', ':', '#', '!' -> {
                    if (key) {
                        sb.append(BS);
                    }
                    sb.append(c);
                }
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(BS).append(String.format("u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
