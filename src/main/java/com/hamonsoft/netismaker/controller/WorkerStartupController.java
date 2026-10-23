package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.WorkerStartupRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.service.WorkerStartupService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 워커 기동 정합 API (X-Worker-API-Key 인증).
 *
 *   POST /worker/startup?workerId=..  ─► 그 워커가 쥔 in-flight 작업 회수 (본문 pendingTaskIds는 건너뜀, 본문 생략 가능)
 */
@RestController
@RequestMapping("/worker")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerStartupController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerStartupController.class);

    private final WorkerStartupService startupService;

    public WorkerStartupController(WorkerStartupService startupService) {
        this.startupService = startupService;
    }

    @PostMapping("/startup")
    public WorkerStartupResponse startup(@RequestParam String workerId,
                                         @RequestBody(required = false) WorkerStartupRequest req) {
        List<Long> pending = (req == null || req.pendingTaskIds() == null) ? List.of() : req.pendingTaskIds();
        WorkerStartupResponse res = startupService.reconcile(workerId, pending);
        log.info("워커 기동 정합: workerId={} 회수 {}건, 건너뜀 {}건", workerId, res.recovered().size(), res.skipped().size());
        return res;
    }
}
