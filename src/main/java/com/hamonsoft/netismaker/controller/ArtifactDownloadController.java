package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.service.ArtifactDownloadService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GET /api/artifact-downloads/{token} — 인증 없음(SecurityConfig permitAll). 토큰이 곧 권한이다:
 * 60초 1회용, 권한 확인은 발급(download-link) 때 끝났다 (스펙 2026-10-14 §6).
 */
@RestController
@RequestMapping("/api/artifact-downloads")
@Profile("api")
public class ArtifactDownloadController {

    private final ArtifactDownloadService downloads;

    public ArtifactDownloadController(ArtifactDownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        ArtifactDownloadService.Download d = downloads.redeem(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(d.name()))
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(d.file()));
    }

    /**
     * 스펙 §6 모양 그대로 `attachment; filename*=UTF-8''{이름}`(RFC 5987). Spring의 ContentDisposition은 UTF-8일 때
     * `filename="=?UTF-8?Q?…?="` 보조 값까지 붙이므로 직접 만든다. 이름은 ArtifactStore.validName이 이미 거르지만 인코딩은 일반 규칙으로 한다.
     */
    static String contentDisposition(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
