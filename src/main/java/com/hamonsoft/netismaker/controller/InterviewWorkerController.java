package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewPlan;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 인터뷰 워커 API (X-Worker-API-Key 인증, WorkerController 미러).
 *
 *   POST /worker/interviews/claim?workerId=…   ─► QUEUED 세션 1건 claim (SKIP LOCKED) 또는 204
 *                                                  (claim이 work_dir 할당/반환 — Phase 1)
 *   POST /worker/interviews/{id}/question       ─► assistant turn 저장 + SSE question|design/status + AWAITING_INPUT
 *   POST /worker/interviews/{id}/plan           ─► interview_plan 저장 + SSE plan_ready(객체)/status + PLAN_READY
 *   POST /worker/interviews/{id}/heartbeat?workerId=… ─► last_activity_at 갱신
 *   POST /worker/interviews/{id}/fail?workerId=…&reason=… ─► FAILED + SSE status/done
 */
@RestController
@RequestMapping("/worker/interviews")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class InterviewWorkerController {

    private final InterviewService interviewService;
    private final InterviewStreamService interviewStream;

    public InterviewWorkerController(InterviewService interviewService, InterviewStreamService interviewStream) {
        this.interviewService = interviewService;
        this.interviewStream = interviewStream;
    }

    @PostMapping("/claim")
    public ResponseEntity<InterviewClaimResponse> claim(@RequestParam String workerId) {
        Optional<InterviewClaimResponse> claimed = interviewService.claim(workerId);
        return claimed.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/question")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void question(@PathVariable Long id, @RequestParam String workerId,
                         @RequestBody @Valid WorkerQuestionRequest req) {
        InterviewTurn turn = interviewService.recordQuestion(id, workerId, req);
        if ("design".equals(turn.getKind())) interviewStream.pushDesign(id, turn.getContent());
        else interviewStream.pushQuestion(id, turn.getContent());
        interviewStream.pushStatus(id, InterviewStatus.AWAITING_INPUT); // 영문 enum name
    }

    @PostMapping("/{id}/plan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void plan(@PathVariable Long id, @RequestParam String workerId,
                     @RequestBody @Valid WorkerPlanRequest req) {
        InterviewPlan plan = interviewService.recordPlan(id, workerId, req);
        // plan_ready = {designMarkdown, planMarkdown, planJson} JSON 객체 (LOCKED CONTRACT v2)
        interviewStream.pushPlanReady(id,
                plan.getDesignMarkdown(), plan.getPlanMarkdown(), plan.getPlanJson());
        interviewStream.pushStatus(id, InterviewStatus.PLAN_READY);
    }

    @PostMapping("/{id}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable Long id, @RequestParam String workerId) {
        interviewService.heartbeat(id, workerId);
    }

    @PostMapping("/{id}/fail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fail(@PathVariable Long id, @RequestParam String workerId,
                     @RequestParam(required = false) String reason) {
        interviewService.fail(id, workerId, reason);
        interviewStream.pushStatus(id, InterviewStatus.FAILED);
        interviewStream.finish(id);
    }
}
