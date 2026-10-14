package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;
import com.hamonsoft.netismaker.service.ArtifactDownloadService;
import com.hamonsoft.netismaker.service.TaskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 산출물 1회용 내려받기 (스펙 2026-10-14 §6) — 인증 없는 GET은 토큰 경로 하나에서만 열리고,
 * 주소 발급은 로그인이 필요하며, 응답 헤더가 스펙대로인지 본다.
 */
@WebMvcTest(controllers = {ArtifactDownloadController.class, CloseArtifactController.class})
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class ArtifactDownloadControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private ArtifactDownloadService downloads;

    @TempDir
    Path tmp;

    @Test
    void 인증_없이_토큰으로_파일을_받고_헤더는_스펙대로() throws Exception {
        Path f = tmp.resolve("repo-task-7-abcdef1.war");
        Files.writeString(f, "WAR");
        when(downloads.redeem("tok")).thenReturn(new ArtifactDownloadService.Download(f, "repo-task-7-abcdef1.war", 3));

        mvc.perform(get("/api/artifact-downloads/tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''repo-task-7-abcdef1.war"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes("WAR".getBytes()));
    }

    @Test
    void 쓴_토큰이나_만료_토큰은_404() throws Exception {
        when(downloads.redeem("used")).thenThrow(TaskException.notFound());

        mvc.perform(get("/api/artifact-downloads/used"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 토큰_경로_말고는_인증_없이_열리지_않는다() throws Exception {
        // 토큰 한 칸만 허용 — 하위 경로·토큰 없는 경로
        mvc.perform(get("/api/artifact-downloads/a/b")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/artifact-downloads")).andExpect(status().isUnauthorized());
        // GET만 허용 — 다른 메서드는 로그인이 필요하다
        mvc.perform(post("/api/artifact-downloads/tok")).andExpect(status().isUnauthorized());
        // 주소 발급은 로그인이 필요하다
        mvc.perform(post("/api/tasks/7/artifact/download-link")).andExpect(status().isUnauthorized());

        verifyNoInteractions(downloads);
    }

    @Test
    void 주소_발급은_등록자_아이디와_관리자_여부를_서비스에_넘긴다() throws Exception {
        when(downloads.link(7L, "user1", false)).thenReturn(new ArtifactDownloadService.Link("/api/artifact-downloads/abc", 60));
        when(downloads.link(7L, "admin", true)).thenReturn(new ArtifactDownloadService.Link("/api/artifact-downloads/def", 60));

        mvc.perform(post("/api/tasks/7/artifact/download-link").with(jwt()
                        .jwt(b -> b.claim("username", "user1"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/artifact-downloads/abc"))
                .andExpect(jsonPath("$.expiresInSec").value(60));

        mvc.perform(post("/api/tasks/7/artifact/download-link").with(jwt()
                        .jwt(b -> b.claim("username", "admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/artifact-downloads/def"));

        verify(downloads).link(7L, "user1", false);
        verify(downloads).link(7L, "admin", true);
    }

    @Test
    void 권한이_없으면_403이_그대로_나간다() throws Exception {
        when(downloads.link(7L, "user2", false)).thenThrow(TaskException.forbidden());

        mvc.perform(post("/api/tasks/7/artifact/download-link").with(jwt()
                        .jwt(b -> b.claim("username", "user2"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().is(HttpStatus.FORBIDDEN.value()));
    }
}
