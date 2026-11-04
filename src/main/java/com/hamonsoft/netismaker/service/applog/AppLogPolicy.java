package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * 앱 로그를 볼 수 있는 작업인지의 판정 (스펙 2026-11-03 §4 허용 상태). 세 조건을 모두 만족해야 한다.
 * (1) 상태가 아래 허용 상태이거나 열린 수정 회차가 있다 (2) 재배포·중지가 진행 중인 상태가 아니다 (3) 배포 기록이 있다.
 * 허용 상태와 진행 상태를 합치면 DeployReconcilable GC 보호 목록(TaskRepository.findDeployReconcilable)과 같다 —
 * 컨테이너를 살려 두는 상태 중 재배포·중지 중이 아닌 것에서만 로그를 연다. AppLogPolicyTest가 두 목록을 대조한다.
 * 프론트(composables/appLog.ts)가 같은 규칙을 쓴다 — 프론트 시험이 아래 두 선언의 소스 문자열을 읽어 대조하므로
 * 선언은 한 문장으로, 상수마다 접두를 붙여 쓰고 static import를 쓰지 않는다.
 */
public final class AppLogPolicy {

    public static final Set<TaskStatus> VIEWABLE_STATUSES = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.FIX_REVIEW, TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING, TaskStatus.CLOSE_FAILED);

    public static final Set<TaskStatus> IN_FLIGHT_EXCLUDED = EnumSet.of(TaskStatus.DEPLOY_PENDING, TaskStatus.DEPLOYING, TaskStatus.UNDEPLOY_PENDING, TaskStatus.UNDEPLOYING);

    private AppLogPolicy() {
    }

    /** 이 작업의 앱 컨테이너 로그를 지금 볼 수 있는가. */
    public static boolean isViewable(Task t) {
        TaskStatus status = t.getStatus();
        if (IN_FLIGHT_EXCLUDED.contains(status)) return false;
        boolean openRound = t.getActiveFixRequestId() != null;
        if (!VIEWABLE_STATUSES.contains(status) && !openRound) return false;
        return hasDeployRecord(t);
    }

    /** 배포한 적이 있는가 — 한 번도 배포하지 않은 작업은 열린 회차여도 볼 컨테이너가 없다. */
    static boolean hasDeployRecord(Task t) {
        return t.getDeployUrl() != null || t.getDeployedAt() != null;
    }

    /**
     * 작업의 배포 컨테이너 이름. 워커 DockerGcPlanner.OWN_PREFIX와 같은 값이어야 하지만 workerdaemon 패키지를 import하지
     * 않으려고 리터럴로 둔다(지금 service·controller·config에는 워커 import가 없다) — 같은지는 AppLogPolicyTest가 대조한다.
     */
    public static String containerName(long taskId) {
        return "netis-task-" + taskId;
    }
}
