package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.InterviewCreatedResponse;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.InterviewSummary;
import com.hamonsoft.netismaker.dto.RegisterResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;

/**
 * 대화형 분석 API (JWT). DESIGN §8 표면 그대로.
 * ACL: 조회/스트림(get, stream)은 소유자 또는 관리자. 답변/확정/취소(answer, confirm, cancel)는
 * 관리자가 승인 시점에 인터뷰를 진행시키는 구조이므로 ROLE_ADMIN 전용(@PreAuthorize).
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

    /** 본인 비종료 인터뷰 목록 — 새로고침 후 '이어할 인터뷰' 디스커버리. 리터럴 /active가 /{id}보다 우선. */
    @GetMapping("/active")
    public List<InterviewSummary> listActive(JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return interviewService.listActiveForRequester(userId);
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public InterviewResponse answer(@PathVariable Long id, @RequestBody @Valid AnswerRequest req,
                                    JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        interviewService.submitAnswer(id, adminId, true, req);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED);
        return interviewService.getResponse(id, adminId, true);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RegisterResponse confirm(@PathVariable Long id,
                                    @RequestBody(required = false) InterviewConfirmRequest body,
                                    JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        Long taskId = interviewService.confirm(id, adminId,
                body != null && Boolean.TRUE.equals(body.designRequested()));
        interviewStream.pushStatus(id, InterviewStatus.REGISTERED);
        interviewStream.finish(id); // terminal → done 이벤트
        return new RegisterResponse(taskId);
    }

    /** confirm 요청 바디 — designRequested 미지정 시 false 취급. */
    public record InterviewConfirmRequest(Boolean designRequested) {}

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public InterviewResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        interviewService.cancel(id, adminId, true);
        interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
        interviewStream.finish(id); // terminal → done 이벤트
        return interviewService.getResponse(id, adminId, true);
    }
}
