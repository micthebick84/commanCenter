package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse.Recovered;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskRecovery.RecoveryCause;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 워커 기동 정합 (api 프로파일) — 워커 프로세스가 다시 뜰 때 첫 claim 전에 호출한다.
 *
 * 워커는 직렬 처리라 같은 workerId의 이전 프로세스가 쥐고 있던 in-flight 작업은 이미 죽은 것이다.
 * stale 잡의 하트비트 판정을 기다리지 않고 바로 회수한다. 단, 워커 dead-letter에 결과 보고가 남은
 * 작업(pendingTaskIds)은 곧 보고되므로 건드리지 않는다.
 */
@Service
@Profile("api")
public class WorkerStartupService {

    private final TaskRepository taskRepo;
    private final TaskRecovery recovery;

    public WorkerStartupService(TaskRepository taskRepo, TaskRecovery recovery) {
        this.taskRepo = taskRepo;
        this.recovery = recovery;
    }

    @Transactional
    public WorkerStartupResponse reconcile(String workerId, Collection<Long> pendingTaskIds) {
        Set<Long> pending = pendingTaskIds == null ? Set.of() : new HashSet<>(pendingTaskIds);
        RecoveryCause cause = RecoveryCause.workerRestart(workerId);
        List<Recovered> recovered = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        // 결과 보고 등 다른 트랜잭션이 쥔 행은 기다렸다가 재판정한 상태로 읽는다(SKIP LOCKED 아님).
        for (Task t : taskRepo.findInFlightClaimedByWorkerForUpdate(workerId)) {
            if (pending.contains(t.getId())) {
                skipped.add(t.getId());
                continue;
            }
            TaskStatus from = t.getStatus();
            TaskStatus to = recovery.recover(t, cause);
            recovered.add(new Recovered(t.getId(), from, to));
        }
        return new WorkerStartupResponse(recovered, skipped);
    }
}
