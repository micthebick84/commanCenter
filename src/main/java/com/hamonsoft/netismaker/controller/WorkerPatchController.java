package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.PatchInfo;
import com.hamonsoft.netismaker.service.ClosePatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 워커의 종료 부분 패치 업로드 (스펙 2026-11-10, X-Worker-API-Key 인증).
 *   PUT /worker/tasks/{id}/patch?workerId=&amp;fileCount=  본문 octet-stream, 헤더 X-Artifact-Sha256
 *   GET /worker/tasks/{id}/patch                       보관돼 있으면 200 PatchInfo, 아니면 404
 */
@RestController
@RequestMapping("/worker/tasks/{id}/patch")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerPatchController {

    private final ClosePatchService patches;

    public WorkerPatchController(ClosePatchService patches) {
        this.patches = patches;
    }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public PatchInfo upload(@PathVariable Long id, @RequestParam String workerId, @RequestParam int fileCount,
                            @RequestHeader("X-Artifact-Sha256") String sha256,
                            HttpServletRequest request) throws IOException {
        return patches.receive(id, workerId, sha256, fileCount, request.getInputStream());
    }

    @GetMapping
    public ResponseEntity<PatchInfo> current(@PathVariable Long id) {
        return patches.current(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
