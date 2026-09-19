package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import com.hamonsoft.netismaker.service.TaskException;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 질문 세션(Q&A) API — 스펙 2026-08-30 §5. JWT 인증만(USER+ADMIN 공용, @PreAuthorize 없음).
 * ACL은 세션 소유자 OR 관리자(InterviewService.requireOwner) — InterviewController와 달리 isAdmin을
 * 하드코딩하지 않고 AuthContext.isAdmin(auth) 실값을 넘긴다. kind 불일치는 404, 타인 소유는 403.
 * SSE 이벤트/상태 전이는 InterviewStreamService·InterviewService를 그대로 공유한다.
 *
 * 2026-09-13(스펙 question-mcp-attachments): 등록/추가질문에 multipart 매핑(meta JSON + files) 추가,
 * 대화 중 MCP 변경 시 note SSE, 첨부 다운로드. 기존 JSON 매핑은 consumes 미지정 그대로 — 달지 말 것.
 */
@RestController
@RequestMapping("/api/questions")
@Profile("api")
public class QuestionController {

    private final QuestionService questionService;
    private final InterviewStreamService interviewStream;

    public QuestionController(QuestionService questionService, InterviewStreamService interviewStream) {
        this.questionService = questionService;
        this.interviewStream = interviewStream;
    }

    @PostMapping
    public ResponseEntity<QuestionSummaryResponse> create(@RequestBody @Valid QuestionCreateRequest req,
                                                          JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        InterviewSession s = questionService.create(req, userId);
        return ResponseEntity.created(URI.create("/api/questions/" + s.getId()))
                .body(QuestionSummaryResponse.of(s));
    }

    /**
     * 파일 첨부 등록 (스펙 2026-09-13 §5.1). TaskController.createMultipart 선례 — multipart 요청만
     * 이 더 구체적인 매핑으로 라우팅된다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionSummaryResponse> createMultipart(
            @RequestPart("meta") @Valid QuestionCreateRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        InterviewSession s = questionService.create(req, files, userId);
        return ResponseEntity.created(URI.create("/api/questions/" + s.getId()))
                .body(QuestionSummaryResponse.of(s));
    }

    @GetMapping
    public List<QuestionSummaryResponse> list(@RequestParam(defaultValue = "false") boolean all,
                                              JwtAuthenticationToken auth) {
        return questionService.list(AuthContext.requireUserId(auth), AuthContext.isAdmin(auth), all);
    }

    @GetMapping("/{id}")
    public InterviewResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        return questionService.get(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, JwtAuthenticationToken auth) {
        questionService.requireViewable(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        return interviewStream.subscribe(id);
    }

    /**
     * 추가 질문 — 바디 {answer, replyToSeq}는 AnswerRequest와 동일(프론트 InterviewPanel 재사용) +
     * 선택 {model, effort, mcpCatalogIds}(대화 중 변경 → 다음 턴부터 적용). 응답의 model/effort/mcpCatalogIds는
     * 갱신된 세션 값. MCP가 실제로 바뀌면 status 다음에 note 이벤트를 밀어 프론트가 즉시 표시한다.
     */
    @PostMapping("/{id}/ask")
    public InterviewResponse ask(@PathVariable Long id, @RequestBody @Valid QuestionAskRequest req,
                                 JwtAuthenticationToken auth) {
        return doAsk(id, req, null, auth);
    }

    /** 첨부 포함 추가 질문 (스펙 2026-09-13 §5.1) — meta JSON 파트 + files 파트. */
    @PostMapping(value = "/{id}/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InterviewResponse askMultipart(@PathVariable Long id,
                                          @RequestPart("meta") @Valid QuestionAskRequest req,
                                          @RequestPart(value = "files", required = false) List<MultipartFile> files,
                                          JwtAuthenticationToken auth) {
        return doAsk(id, req, files, auth);
    }

    private InterviewResponse doAsk(Long id, QuestionAskRequest req, List<MultipartFile> files,
                                    JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        QuestionService.AskResult result = questionService.ask(id, userId, isAdmin, req, files);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED);
        InterviewTurn note = result.mcpNote();
        if (note != null) interviewStream.pushNote(id, note.getSeq(), note.getContent());
        return questionService.get(id, userId, isAdmin);
    }

    @PostMapping("/{id}/close")
    public InterviewResponse close(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        questionService.close(id, userId, isAdmin);
        interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
        interviewStream.finish(id); // terminal → done 이벤트
        return questionService.get(id, userId, isAdmin);
    }

    /**
     * 첨부 다운로드 (스펙 2026-09-13 §2). ACL = requireViewable(kind 404 → 소유자/관리자 403) —
     * TaskController.downloadAttachment 패턴. 한글 파일명은 RFC 5987 filename*으로.
     */
    @GetMapping("/{id}/attachments/{attId}")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id,
                                                       @PathVariable Long attId,
                                                       JwtAuthenticationToken auth) {
        questionService.requireViewable(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        QuestionAttachment att = questionService.getAttachment(id, attId);
        Path file = questionService.resolveAttachmentPath(att);
        if (!Files.exists(file)) {
            throw new TaskException(HttpStatus.NOT_FOUND, "첨부 파일이 서버에 존재하지 않습니다");
        }
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(att.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(att.getSizeBytes())
                .body(new FileSystemResource(file));
    }
}
