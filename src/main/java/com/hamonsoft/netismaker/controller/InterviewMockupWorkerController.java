package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.WorkerMockupRequest;
import com.hamonsoft.netismaker.dto.WorkerMockupResponse;
import com.hamonsoft.netismaker.service.InterviewMockupService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인터뷰 목업 워커 API (X-Worker-API-Key 인증, {@link InterviewWorkerController}와 같은 경로 접두·권한; 스펙 2026-11-03 §5).
 *
 *   POST /worker/interviews/{id}/mockups?workerId=… ─► 목업 저장 + SSE mockup → {id, n, version}
 *
 * 바디에 {@code @Valid}를 달지 않는다 — 실패하면 사유가 "검증 오류"로만 가서 에이전트가 고칠 수 없다(검사는 서비스).
 */
@RestController
@RequestMapping("/worker/interviews")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class InterviewMockupWorkerController {

    private final InterviewMockupService mockupService;
    private final InterviewStreamService interviewStream;

    public InterviewMockupWorkerController(InterviewMockupService mockupService, InterviewStreamService interviewStream) {
        this.mockupService = mockupService;
        this.interviewStream = interviewStream;
    }

    @PostMapping("/{id}/mockups")
    public WorkerMockupResponse upload(@PathVariable Long id, @RequestParam String workerId,
                                       @RequestBody WorkerMockupRequest req) {
        InterviewMockupSummary m = mockupService.upload(id, workerId, req); // 서비스 트랜잭션은 여기서 이미 커밋됐다
        interviewStream.pushMockup(id, m); // 커밋 뒤에 보내야 화면이 받은 목업을 곧바로 조회할 수 있다
        return new WorkerMockupResponse(m.id(), m.n(), m.version());
    }
}
