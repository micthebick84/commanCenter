package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 워커 기동 정합 요청 본문 — 워커 dead-letter에 아직 보고 대기 중인 작업 id.
 * 이 작업들은 회수하지 않고 건너뛴다(워커가 곧 결과를 다시 보고한다). null은 빈 목록으로 본다.
 */
public record WorkerStartupRequest(List<Long> pendingTaskIds) {
}
