package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 배포 런타임 정합(reconcile) 잡: DB의 배포완료/배포중단됨 상태를 실제 컨테이너 생존과 대사한다.
 *
 *  배포완료   인데 컨테이너 비실행 → running=false 보고 → 배포중단됨
 *  배포중단됨 인데 컨테이너 실행 중 → running=true 보고 → 배포완료 복귀
 *
 * 배포 시점 1회성 헬스체크만으로는 이후 컨테이너 사망(도커 데몬 재시작, 수동 stop 등)이
 * DB에 반영되지 않아 '배포완료인데 404' 괴리가 영구화되는 문제의 근본 대책.
 * UNKNOWN(도커 데몬 접근 불가 등) 관측은 보고하지 않는다 — 오탐 전이 방지.
 * 관측·보고 사이 재배포/중지 경합은 서버(recordRuntimeStatus)가 no-op으로 흡수한다.
 */
@Component
@Profile("worker")
@Slf4j
public class DeployReconcileJob {

    private final WorkerHttpClient http;
    private final DeployTarget target;
    private final WorkerProperties props;

    public DeployReconcileJob(WorkerHttpClient http, DeployTarget target, WorkerProperties props) {
        this.http = http;
        this.target = target;
        this.props = props;
    }

    @Scheduled(initialDelayString = "#{${netis-maker.worker.deploy.reconcile-interval-seconds:300} * 1000}",
               fixedRateString = "#{${netis-maker.worker.deploy.reconcile-interval-seconds:300} * 1000}")
    public void reconcile() {
        if (Boolean.FALSE.equals(props.deploy().reconcileEnabled())) return;

        List<DeployedTaskSummary> tasks;
        try {
            tasks = http.deployedTasks();
        } catch (Exception e) {
            log.warn("reconcile 대상 조회 실패 (다음 주기 재시도): {}", e.getMessage());
            return;
        }

        for (DeployedTaskSummary t : tasks) {
            // 목록엔 GC 보호용 in-flight 상태(배포대기~중지중)도 포함 — 대사는 두 상태만
            if (t.status() != TaskStatus.DEPLOYED && t.status() != TaskStatus.DEPLOY_LOST) continue;
            String containerName = DockerGcPlanner.OWN_PREFIX + t.id();
            DeployTarget.DeployStatus st = target.status(containerName);
            if (st == DeployTarget.DeployStatus.UNKNOWN) continue; // 관측 불가 — 전이 보류

            boolean running = st == DeployTarget.DeployStatus.RUNNING;
            try {
                if (t.status() == TaskStatus.DEPLOYED && !running) {
                    http.postRuntimeStatus(t.id(), new WorkerRuntimeStatusRequest(
                            props.id(), false, "docker 컨테이너 " + containerName + " 비실행"));
                    log.info("reconcile: task={} 컨테이너 소실 보고 ({})", t.id(), containerName);
                } else if (t.status() == TaskStatus.DEPLOY_LOST && running) {
                    http.postRuntimeStatus(t.id(), new WorkerRuntimeStatusRequest(
                            props.id(), true, "docker 컨테이너 " + containerName + " 실행 확인"));
                    log.info("reconcile: task={} 컨테이너 복구 보고 ({})", t.id(), containerName);
                }
            } catch (Exception e) {
                log.warn("reconcile 보고 실패 task={} (다음 주기 재시도): {}", t.id(), e.getMessage());
            }
        }
    }
}
