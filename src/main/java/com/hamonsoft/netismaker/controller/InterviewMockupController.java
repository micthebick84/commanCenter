package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.InterviewMockupView;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.service.InterviewMockupService;
import com.hamonsoft.netismaker.service.InterviewService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * 인터뷰 목업 조회 API (JWT, 스펙 2026-11-03 §5) — {@link InterviewController}와 같은 경로 접두를 쓰되 생성자를 건드리지 않으려고 따로 둔다.
 *
 *   GET /api/interviews/{sid}/mockups            ─► 목록 (html 없음, 버린 것 제외, n 순)
 *   GET /api/interviews/{sid}/mockups/{mockupId} ─► 단건 (html·taskId·createdAt 포함, JSON만)
 *
 * ACL은 {@link InterviewController#get}과 같은 순서다: 질문 세션은 404 → 사용자 id → 세션 조회 권한(요청자·관리자, 아니면 403) → 그제야 목업.
 * 단건은 {@code produces = application/json}으로 못 박는다 — 에이전트가 만든 HTML을 {@code text/html}로 내보내면 앱 출처로 실행되기 때문이다
 * (화면은 이 JSON의 html을 iframe srcdoc으로만 그린다).
 */
@RestController
@RequestMapping("/api/interviews")
@Profile("api")
public class InterviewMockupController {

    private final InterviewService interviewService;
    private final InterviewMockupService mockupService;

    public InterviewMockupController(InterviewService interviewService, InterviewMockupService mockupService) {
        this.interviewService = interviewService;
        this.mockupService = mockupService;
    }

    @GetMapping("/{sid}/mockups")
    public List<InterviewMockupSummary> list(@PathVariable Long sid, JwtAuthenticationToken auth) {
        requireViewable(sid, auth);
        return mockupService.list(sid);
    }

    @GetMapping(value = "/{sid}/mockups/{mockupId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InterviewMockupView get(@PathVariable Long sid, @PathVariable Long mockupId, JwtAuthenticationToken auth) {
        requireViewable(sid, auth);
        return mockupService.get(sid, mockupId);
    }

    /** {@code InterviewController.get}과 같은 순서 — 질문 세션 404 → 사용자 id → 조회 권한(없으면 예외). */
    private void requireViewable(Long sid, JwtAuthenticationToken auth) {
        interviewService.requireKind(sid, InterviewKind.INTERVIEW); // 질문 세션은 이 API로 조회 불가 (404)
        String userId = AuthContext.requireUserId(auth);
        interviewService.getForView(sid, userId, AuthContext.isAdmin(auth)); // ACL 검증 (없으면 예외)
    }
}
