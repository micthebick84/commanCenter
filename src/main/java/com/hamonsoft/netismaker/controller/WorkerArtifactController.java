package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.service.CloseArtifactService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 워커의 종료 산출물 업로드 (스펙 2026-10-14 §5, X-Worker-API-Key 인증).
 *   PUT /worker/tasks/{id}/artifact?workerId=&name=&source=  본문 octet-stream, 헤더 X-Artifact-Sha256
 *   GET /worker/tasks/{id}/artifact                          보관돼 있으면 200, 아니면 404
 * multipart가 아니라 원시 본문이라 spring.servlet.multipart 상한(25MB)과 무관하다.
 */
@RestController
@RequestMapping("/worker/tasks/{id}/artifact")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerArtifactController {

    private final CloseArtifactService artifacts;

    public WorkerArtifactController(CloseArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ArtifactInfo upload(@PathVariable Long id, @RequestParam String workerId, @RequestParam String name,
                               @RequestParam(required = false) String source,
                               @RequestHeader("X-Artifact-Sha256") String sha256,
                               HttpServletRequest request) throws IOException {
        return artifacts.receive(id, workerId, name, source, sha256, request.getInputStream());
    }

    @GetMapping
    public ResponseEntity<ArtifactInfo> current(@PathVariable Long id) {
        return artifacts.current(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
