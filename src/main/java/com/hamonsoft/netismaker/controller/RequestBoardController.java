package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.service.RequestBoardService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 요청 게시판 API (스펙 2026-10-19, 개발 요청 2026-11-19). 볼 수 없는 요청은 존재 자체를 숨겨 404로 답한다(서비스 규칙).
 * 열람 범위: 개발 요청(DEV)은 로그인 사용자 누구나 열람·답글할 수 있고, 그 밖의 종류(REPO = 사이트 추가, 예전 FEATURE·MCP)는
 * 본인·관리자만. 새로 등록할 수 있는 종류는 DEV·REPO뿐이다(그 밖은 400).
 *
 *   GET    /api/requests?all=&kind=&status=&includeUnread= — 인증. 관리자이고 all=true면 전체(필터 적용), 그 밖에는 내 요청만
 *   GET    /api/requests?scope=dev[&issueType=][&status=]  — 인증. 개발 요청 전원 공개 목록(scope가 있으면 all·kind는 무시, dev 밖은 400)
 *   POST   /api/requests                                   — 인증 (201). JSON 또는 multipart(meta + files, 첨부는 DEV만)
 *   GET    /api/requests/{id}                              — 인증 (DEV는 누구나, 그 밖은 본인·관리자. 작성자·관리자만 읽음 처리)
 *   PUT    /api/requests/{id}                              — 인증 (본인, 접수 상태에서만). JSON 또는 multipart(첨부 추가·삭제는 DEV만)
 *   DELETE /api/requests/{id}                              — 인증 (본인, 접수 상태에서만; 작업에 연결됐거나 남의 답글(DEV)·
 *                                                            관리자 답글(그 밖)이 있으면 409) (204)
 *   GET    /api/requests/{id}/attachments/{attId}          — 인증 (요청을 볼 수 있는 사람, 정리된 파일은 410)
 *   POST   /api/requests/{id}/comments                     — 인증 (DEV는 누구나, 그 밖은 본인·관리자) (201)
 *   POST   /api/admin/requests/{id}/status                 — ADMIN (상태 변경)
 *   GET    /api/admin/requests/summary                     — ADMIN (배지용 건수)
 */
@RestController
@Profile("api")
public class RequestBoardController {

    private final RequestBoardService service;

    public RequestBoardController(RequestBoardService service) {
        this.service = service;
    }

    @GetMapping("/api/requests")
    @PreAuthorize("isAuthenticated()")
    public List<RequestBoardDto.ListItem> list(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) RequestKind kind,
            @RequestParam(required = false) Set<RequestStatus> status,
            @RequestParam(required = false, defaultValue = "true") boolean includeUnread,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) RequestIssueType issueType,
            JwtAuthenticationToken auth) {
        if (scope != null && !scope.isBlank()) {
            if (!"dev".equals(scope)) throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 scope: " + scope);
            return service.listDev(issueType, status, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        }
        // 비관리자가 보낸 all=true는 무시한다.
        if (all && AuthContext.isAdmin(auth)) {
            return service.listForAdmin(kind, status, includeUnread);
        }
        return service.listMine(AuthContext.requireUserId(auth));
    }

    @PostMapping("/api/requests")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestBoardDto.ListItem create(@RequestBody @Valid RequestBoardDto.CreateRequest req,
                                           JwtAuthenticationToken auth) {
        return service.create(req, AuthContext.requireUserId(auth));
    }

    /**
     * 첨부가 있는 등록(multipart: meta JSON + files). 위의 JSON 매핑에는 consumes를 달지 않는다 —
     * consumes가 있으면 Content-Type이 없거나 다른 요청이 405/415로 바뀐다(TaskController.createMultipart와 같은 이유).
     */
    @PostMapping(value = "/api/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestBoardDto.ListItem createMultipart(
            @RequestPart("meta") @Valid RequestBoardDto.CreateRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            JwtAuthenticationToken auth) {
        return service.create(req, files, AuthContext.requireUserId(auth));
    }

    @PutMapping(value = "/api/requests/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RequestBoardDto.Detail updateMultipart(
            @PathVariable Long id,
            @RequestPart("meta") @Valid RequestBoardDto.UpdateRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            JwtAuthenticationToken auth) {
        return service.update(id, req, files, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    /** 첨부 내려받기 — 요청을 볼 수 있는 사람만(없으면 404, 정리된 파일은 410). QuestionController와 같은 헤더. */
    @GetMapping("/api/requests/{id}/attachments/{attId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id, @PathVariable Long attId,
                                                       JwtAuthenticationToken auth) {
        RequestBoardService.AttachmentDownload d = service.download(id, attId,
                AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        ContentDisposition cd = ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(d.path()));
    }

    @GetMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public RequestBoardDto.Detail get(@PathVariable Long id, JwtAuthenticationToken auth) {
        return service.getAndMarkRead(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PutMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public RequestBoardDto.Detail update(@PathVariable Long id,
                                         @RequestBody @Valid RequestBoardDto.UpdateRequest req,
                                         JwtAuthenticationToken auth) {
        return service.update(id, req, List.of(), AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @DeleteMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        service.cancel(id, AuthContext.requireUserId(auth));
    }

    @PostMapping("/api/requests/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestBoardDto.CommentView addComment(@PathVariable Long id,
                                                  @RequestBody @Valid RequestBoardDto.CommentRequest req,
                                                  JwtAuthenticationToken auth) {
        return service.addComment(id, req, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping("/api/admin/requests/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RequestBoardDto.Detail changeStatus(@PathVariable Long id,
                                               @RequestBody @Valid RequestBoardDto.StatusChangeRequest req,
                                               JwtAuthenticationToken auth) {
        return service.changeStatus(id, req, AuthContext.requireUserId(auth));
    }

    @GetMapping("/api/admin/requests/summary")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RequestBoardDto.Summary summary() {
        return service.summary();
    }
}
