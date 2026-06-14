package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.InterviewCreatedResponse;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.RegisterResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;

/**
 * 대화형 분석 사용자 API (JWT, ROLE_USER/ADMIN). DESIGN §8 표면 그대로.
 * ACL: 소유자 또는 관리자만 조회/스트림/답변/등록/취소.
 * 동시성 게이트(요청자별 active 한도)는 Phase 1 InterviewService.create에서 검증.
 * 모든 상태 전이는 Phase 1 InterviewService 메서드 위임 — 컨트롤러는 SSE push만 추가.
 */
@RestController
@RequestMapping("/api/interviews")
@Profile("api")
public class InterviewController {

    private final InterviewService interviewService;
    private final InterviewStreamService interviewStream;

    public InterviewController(InterviewService interviewService, InterviewStreamService interviewStream) {
        this.interviewService = interviewService;
        this.interviewStream = interviewStream;
    }

    @PostMapping
    public ResponseEntity<InterviewCreatedResponse> create(@RequestBody @Valid CreateInterviewRequest req,
                                                           JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        InterviewSession s = interviewService.create(req, userId);
        return ResponseEntity.created(URI.create("/api/interviews/" + s.getId()))
                .body(new InterviewCreatedResponse(s.getId()));
    }

    @GetMapping("/{id}")
    public InterviewResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return interviewService.getResponse(id, userId, AuthContext.isAdmin(auth));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        interviewService.getForView(id, userId, AuthContext.isAdmin(auth)); // ACL 검증 (없으면 예외)
        return interviewStream.subscribe(id);
    }

    @PostMapping("/{id}/answer")
    public InterviewResponse answer(@PathVariable Long id, @RequestBody @Valid AnswerRequest req,
                                    JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        interviewService.submitAnswer(id, userId, isAdmin, req);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED); // 영문 enum name
        return interviewService.getResponse(id, userId, isAdmin);
    }

    @PostMapping("/{id}/register")
    public RegisterResponse register(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        Long taskId = interviewService.register(id, userId, AuthContext.isAdmin(auth));
        interviewStream.pushStatus(id, InterviewStatus.REGISTERED);
        interviewStream.finish(id); // terminal → done 이벤트
        return new RegisterResponse(taskId);
    }

    @PostMapping("/{id}/cancel")
    public InterviewResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        interviewService.cancel(id, userId, isAdmin);
        interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
        interviewStream.finish(id); // terminal → done 이벤트
        return interviewService.getResponse(id, userId, isAdmin);
    }
}
