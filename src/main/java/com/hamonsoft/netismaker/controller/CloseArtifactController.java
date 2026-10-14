package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.service.ArtifactDownloadService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** POST /api/tasks/{id}/artifact/download-link — 등록자 본인 또는 관리자 (스펙 2026-10-14 §6). */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class CloseArtifactController {

    private final ArtifactDownloadService downloads;

    public CloseArtifactController(ArtifactDownloadService downloads) {
        this.downloads = downloads;
    }

    @PostMapping("/{id}/artifact/download-link")
    public ArtifactDownloadService.Link downloadLink(@PathVariable Long id, JwtAuthenticationToken auth) {
        return downloads.link(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }
}
