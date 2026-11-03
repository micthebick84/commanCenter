package com.hamonsoft.netismaker.entity;

/**
 * 수정 요청의 결정 상태 (스펙 2026-11-03 §6). DB·API가 enum 이름을 그대로 주고받는다.
 *  PENDING   — 등록자가 요청했고 관리자 승인을 기다림 (작업은 수정승인대기)
 *  APPROVED  — 승인됨(관리자 직접 요청은 곧바로 이 값) — 수정 회차가 열림
 *  REJECTED  — 관리자가 반려 (작업은 직전 상태로)
 *  WITHDRAWN — 등록자가 철회 (작업은 직전 상태로)
 */
public enum FixRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
