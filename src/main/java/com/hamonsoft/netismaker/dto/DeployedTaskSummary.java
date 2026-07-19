package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;

/**
 * 배포 런타임 정합(reconcile) 대상 목록 항목.
 * 컨테이너 이름은 워커가 규칙(netis-task-{id})으로 유도하므로 id만 내려준다.
 */
public record DeployedTaskSummary(Long id, TaskStatus status) {
}
