package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.InterviewStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerActivityRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewPlan;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 인터뷰 워커 API (X-Worker-API-Key 인증, WorkerController 미러).
 *
 *   POST /worker/interviews/claim?workerId=…   ─► QUEUED 세션 1건 claim (SKIP LOCKED) 또는 204
 *                                                  (claim이 work_dir 할당/반환 — Phase 1)
 *   POST /worker/interviews/startup?workerId=… ─► 재기동으로 중단된 RUNNING 세션을 QUEUED로 되돌림(턴당 2회, 소진 시 FAILED)
 *   POST /worker/interviews/{id}/question       ─► assistant turn 저장 + SSE question|design/status + AWAITING_INPUT
 *   POST /worker/interviews/{id}/plan           ─► interview_plan 저장 + SSE plan_ready(객체)/status + PLAN_READY
 *   POST /worker/interviews/{id}/activity?workerId=… ─► SSE activity fan-out (transient, DB 미접근)
 *   POST /worker/interviews/{id}/heartbeat?workerId=… ─► last_activity_at 갱신
 *   POST /worker/interviews/{id}/fail?workerId=…&reason=… ─► FAILED + SSE status/done
 */
@RestController
@RequestMapping("/worker/interviews")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class InterviewWorkerController {

    private static final Logger log = LoggerFactory.getLogger(InterviewWorkerController.class);

    private final InterviewService interviewService;
    private final InterviewStreamService interviewStream;
    private final DbConnectionService dbConnectionService;

    public InterviewWorkerController(InterviewService interviewService, InterviewStreamService interviewStream,
                                     DbConnectionService dbConnectionService) {
        this.interviewService = interviewService;
        this.interviewStream = interviewStream;
        this.dbConnectionService = dbConnectionService;
    }

    @PostMapping("/claim")
    public ResponseEntity<InterviewClaimResponse> claim(@RequestParam String workerId) {
        Optional<InterviewClaimResponse> claimed = interviewService.claim(workerId).map(this::withDb);
        return claimed.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 질문·인터뷰 세션이면 DB 접속정보를 복호화해 싣는다 (스펙 2026-10-02 §5.4, 2026-10-14 §5). claim 트랜잭션은 이미 커밋됐으므로
     * 여기서 실패해도 응답을 깨지 않는다 — 500이면 세션이 RUNNING에 묶여 stale 회수까지 멈춘다.
     * ⚠️ 이 응답 본문(평문 비밀번호 포함)을 로그로 남기지 말 것.
     */
    private InterviewClaimResponse withDb(InterviewClaimResponse c) {
        if (c.dbConnectionIds().isEmpty()) return c;
        try {
            DbConnectionService.ClaimDb db = dbConnectionService.resolveForClaim(c.dbConnectionIds());
            return c.withDb(db.refs(), db.notices());
        } catch (RuntimeException e) {
            log.warn("claim DB 접속정보 조회 실패 session={}: {}", c.sessionId(), e.getClass().getSimpleName());
            String notice = "QUESTION".equals(c.kind())
                    ? "DB 접속정보를 불러오지 못해 DB 도구 없이 답합니다"
                    : "DB 접속정보를 불러오지 못해 DB 도구 없이 진행합니다";
            return c.withDb(List.of(), List.of(notice));
        }
    }

    /**
     * 인터뷰 서비스 기동 정합 — 첫 claim 전에 호출된다. 이 워커가 RUNNING으로 잡고 있던 세션(재기동으로 끊긴 턴)을
     * 세션마다 별도 트랜잭션으로 QUEUED로 되돌리거나(자동 재개 2회 소진이면 FAILED) SSE로 알린다.
     * 한 세션의 예외는 로그만 남기고 다음 세션으로 넘어간다(InterviewStaleRecoveryJob과 같은 독립 try/catch).
     */
    @PostMapping("/startup")
    public InterviewStartupResponse startup(@RequestParam String workerId) {
        List<Long> released = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long id : interviewService.runningSessionIdsOf(workerId)) {
            try {
                Optional<InterviewService.RestartOutcome> out = interviewService.releaseForRestart(id, workerId);
                if (out.isEmpty()) continue;
                InterviewTurn note = out.get().note();
                // 노트가 먼저 — 프론트는 FAILED status를 받는 즉시 스트림을 닫는다.
                interviewStream.pushNote(id, note.getSeq(), note.getContent());
                if (out.get().released()) {
                    interviewStream.pushStatus(id, InterviewStatus.QUEUED);
                    released.add(id);
                } else {
                    interviewStream.pushStatus(id, InterviewStatus.FAILED);
                    interviewStream.finish(id);
                    failed.add(id);
                }
            } catch (Exception e) {
                log.error("인터뷰 기동 정합 실패: interview={} worker={}", id, workerId, e);
            }
        }
        if (!released.isEmpty() || !failed.isEmpty()) {
            log.warn("인터뷰 기동 정합: worker={} 재개 {} 실패 {}", workerId, released, failed);
        }
        return new InterviewStartupResponse(released, failed);
    }

    @PostMapping("/{id}/question")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void question(@PathVariable Long id, @RequestParam String workerId,
                         @RequestBody @Valid WorkerQuestionRequest req) {
        InterviewTurn turn = interviewService.recordQuestion(id, workerId, req);
        if ("design".equals(turn.getKind())) interviewStream.pushDesign(id, turn.getSeq(), turn.getContent());
        else interviewStream.pushQuestion(id, turn.getSeq(), turn.getContent());
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

    /**
     * 활동 스트림 중계 (transient) — DB 미접근이 계약: 300ms 배치 고빈도 호출이라
     * 커넥션 풀을 만지지 않는다(과거 SSE+OSIV 풀 고갈 사고 재발 방지). 세션 소유 검증도
     * 생략 — 신뢰 경계는 X-Worker-API-Key(다른 워커 엔드포인트와 동일).
     */
    @PostMapping("/{id}/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activity(@PathVariable Long id, @RequestParam String workerId,
                         @RequestBody @Valid WorkerActivityRequest req) {
        interviewStream.pushActivity(id, req);
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
        InterviewService.FailOutcome out = interviewService.failFromWorker(id, workerId, reason);
        // 사유 노트가 먼저 — 프론트는 FAILED status를 받는 즉시 스트림을 닫는다.
        interviewStream.pushNote(id, out.note().getSeq(), out.note().getContent());
        interviewStream.pushStatus(id, InterviewStatus.FAILED);
        interviewStream.finish(id);
    }
}
