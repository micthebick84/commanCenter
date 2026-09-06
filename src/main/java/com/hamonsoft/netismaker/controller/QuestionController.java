package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;

/**
 * 질문 세션(Q&A) API — 스펙 2026-08-30 §5. JWT 인증만(USER+ADMIN 공용, @PreAuthorize 없음).
 * ACL은 세션 소유자 OR 관리자(InterviewService.requireOwner) — InterviewController와 달리 isAdmin을
 * 하드코딩하지 않고 AuthContext.isAdmin(auth) 실값을 넘긴다. kind 불일치는 404, 타인 소유는 403.
 * SSE 이벤트/상태 전이는 InterviewStreamService·InterviewService를 그대로 공유한다.
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
     * 선택 {model, effort}(대화 중 변경 → 다음 턴부터 적용). 응답의 model/effort는 갱신된 세션 값.
     */
    @PostMapping("/{id}/ask")
    public InterviewResponse ask(@PathVariable Long id, @RequestBody @Valid QuestionAskRequest req,
                                 JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        questionService.ask(id, userId, isAdmin, req);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED);
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
}
