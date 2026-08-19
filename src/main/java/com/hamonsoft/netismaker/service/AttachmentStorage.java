package com.hamonsoft.netismaker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 첨부파일의 파일시스템 전담 컴포넌트 (스펙 §6). DB 메타는 TaskService가,
 * 디스크(검증·경로·쓰기·resolve·삭제)는 여기서만 다룬다.
 *
 * 저장 상대경로 = task-{taskId}/{ordinal}-{sanitized}. ordinal은 업로드 순번(1..N) —
 * 첨부는 등록 트랜잭션에서만 생성되고 추가/삭제가 없어(스펙 §2) 순번이 영구히 유일하다.
 */
@Slf4j
@Component
@Profile("api")
public class AttachmentStorage {

    /** 실행파일류만 차단 — 종류 제한 없음이 요구사항 (스펙 §2). */
    static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "so", "dylib", "bat", "cmd", "sh", "ps1",
            "msi", "scr", "com", "pif", "vbs", "app");

    private final Path root;
    private final int maxFiles;
    private final int maxFileSizeMb;
    private final int maxTotalSizeMb;

    public AttachmentStorage(
            @Value("${app.attachment.dir:${user.home}/netis-maker/attachments}") String dir,
            @Value("${app.attachment.max-files:10}") int maxFiles,
            @Value("${app.attachment.max-file-size-mb:20}") int maxFileSizeMb,
            @Value("${app.attachment.max-total-size-mb:50}") int maxTotalSizeMb) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxFiles = maxFiles;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxTotalSizeMb = maxTotalSizeMb;
    }

    /** 한도·확장자·빈파일 검증. 위반 시 400 + 한국어 메시지 (스펙 §6.1 순서). */
    public void validate(List<MultipartFile> files) {
        if (files.size() > maxFiles) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "첨부는 최대 " + maxFiles + "개까지 가능합니다");
        }
        long totalBytes = 0;
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (f.isEmpty()) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "빈 파일(0바이트)은 첨부할 수 없습니다: " + name);
            }
            if (f.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "파일당 " + maxFileSizeMb + "MB 이하만 첨부할 수 있습니다: " + name);
            }
            // 검증은 반드시 "디스크에 실제로 남을 이름"에서 확장자를 뽑는다. 원본 이름으로
            // 뽑으면 "evil.sh " / "evil.exe<제어문자>" / "evil.sh." 처럼 sanitize가 되살리는
            // 차단 확장자가 검증을 통과해 버린다(우회).
            String ext = extensionOf(sanitize(name));
            if (BLOCKED_EXTENSIONS.contains(ext)) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "허용되지 않는 파일 형식입니다: ." + ext);
            }
            totalBytes += f.getSize();
        }
        if (totalBytes > (long) maxTotalSizeMb * 1024 * 1024) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "첨부 합계는 " + maxTotalSizeMb + "MB 이하여야 합니다");
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        // Locale.ROOT 고정 — 기본 로케일이 터키어면 'I'가 점 없는 'ı'로 내려가
        // "MSI"/"PIF"/"DYLIB"가 블록리스트와 어긋나 통과한다.
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 파일명 정리: 경로 구분자 뒤만 취하고, '..'/제어문자 제거, 뒤쪽 공백·마침표 제거,
     * UTF-8 240바이트 제한(확장자가 뒤에 있으므로 뒤쪽 보존), 빈 결과는 "file".
     */
    static String sanitize(String name) {
        String base = name == null ? "" : name;
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replace("..", "");
        base = base.replaceAll("\\p{Cntrl}", "");
        base = base.trim();
        // 끝의 공백·마침표는 함께 제거 — "evil.sh." 처럼 확장자를 감추는 형태를 없애고,
        // Windows에서 실제 파일명이 되지 못하는 표기도 정리한다. 마침표만 떼면 그 밑에 깔린
        // 공백이 다시 꼬리로 드러나("evil.exe ." → "evil.exe ") 확장자가 "exe "가 되어
        // 블록리스트를 빠져나가므로, 둘을 한 번에 제거해야 한다.
        base = stripTrailingBlanksAndDots(base);
        base = truncateTailToBytes(base, MAX_FILENAME_BYTES);
        if (base.isBlank()) base = "file";
        return base;
    }

    /**
     * 꼬리의 공백·마침표를 제거한다. 정규식 "[\s.]+$" 와 결과는 같지만 시간이 선형이다.
     *
     * 정규식을 쓰면 안 되는 이유: 매치에 실패할 때마다 시작 위치를 한 칸 옮겨 런 전체를 다시
     * 훑어 2차식이 된다. 파일명 길이는 multipart part 헤더 상한(10,240바이트)까지 열려 있고
     * 첨부 1건당 sanitize 가 3회 호출되므로(TaskService: validate/relativePath/저장명), 최대
     * 10개 첨부면 30회 — 공백을 길게 채운 요청 하나로 워커 스레드를 수 초 점유할 수 있다.
     *
     * 문자 집합은 Java 정규식 \s 와 동일하게 유지한다([ \t\n\x0B\f\r]).
     * NBSP(U+00A0) 같은 비ASCII 공백은 여기서도, \p{Cntrl} 치환에서도 걸리지 않는다 —
     * 수정 전부터 같았고 이 메서드로 좁히거나 넓히지 않는다(후속 하드닝 대상).
     */
    private static String stripTrailingBlanksAndDots(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '.' || c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** 파일명 상한(바이트). ext4/xfs는 255바이트 — "{ordinal}-" 접두사 여유를 두고 240. */
    static final int MAX_FILENAME_BYTES = 240;

    /**
     * UTF-8 바이트 기준으로 뒤쪽(확장자 포함)을 보존하며 자른다. 글자수가 아니라 바이트여야
     * 하는 이유: 한글은 UTF-8 3바이트라 200"자"는 최대 600바이트 — Linux(ext4) 255바이트
     * 한도를 넘어 macOS 개발기에서만 통과하고 서버에서 500이 난다.
     * 코드포인트 경계에서만 잘라 서로게이트 페어(이모지 등)가 쪼개지지 않는다.
     */
    private static String truncateTailToBytes(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
        int bytes = 0;
        int idx = s.length();
        while (idx > 0) {
            int cp = s.codePointBefore(idx);
            int cpBytes = utf8Length(cp);
            if (bytes + cpBytes > maxBytes) break;
            bytes += cpBytes;
            idx -= Character.charCount(cp);
        }
        return s.substring(idx);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    public String relativePath(long taskId, int ordinal, String originalFilename) {
        return "task-" + taskId + "/" + ordinal + "-" + sanitize(originalFilename);
    }

    public void write(String relativePath, MultipartFile file) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "첨부 파일 저장에 실패했습니다: " + e.getMessage());
        }
    }

    /** 루트 하위임을 재확인(디렉터리 탈출 이중 방어, 스펙 §5.3). */
    public Path resolve(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) throw TaskException.notFound();
        return p;
    }

    public String absolutePathOf(String relativePath) {
        return resolve(relativePath).toString();
    }

    /** 롤백 시 best-effort 정리 — 실패는 무시하되 원인은 남긴다 (tx는 이미 롤백 경로). */
    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | RuntimeException e) {
            // best-effort: 잔존 파일은 무해(메타가 롤백되어 참조 불가)하지만, 운영자가
            // 고아 파일을 추적할 수 있도록 원인은 로그로 남긴다.
            log.warn("첨부 파일 삭제 실패(무시): relativePath={}", relativePath, e);
        }
    }
}
