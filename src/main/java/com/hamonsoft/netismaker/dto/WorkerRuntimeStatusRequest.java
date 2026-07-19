package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 워커의 배포 컨테이너 런타임 관측 보고 (터미널 결과 보고와 별개, 멱등).
 *
 *  running=false : 배포완료 task의 컨테이너가 비실행 → 배포중단됨 전이
 *  running=true  : 배포중단됨 task의 컨테이너가 실행 중 → 배포완료 복귀
 *
 * 그 외 상태(재배포/중지 진행 중 등)에서는 서버가 no-op 처리한다.
 */
public record WorkerRuntimeStatusRequest(
        @NotNull String workerId,
        @NotNull Boolean running,
        String detail
) {
}
