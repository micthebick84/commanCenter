package com.hamonsoft.netismaker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
            String ext = extensionOf(name);
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
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * 파일명 정리: 경로 구분자 뒤만 취하고, '..'/제어문자 제거, 200자 제한(확장자가 뒤에
     * 있으므로 뒤쪽 보존), 빈 결과는 "file".
     */
    static String sanitize(String name) {
        String base = name == null ? "" : name;
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replace("..", "");
        base = base.replaceAll("\\p{Cntrl}", "");
        base = base.trim();
        if (base.length() > 200) base = base.substring(base.length() - 200);
        if (base.isBlank()) base = "file";
        return base;
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
