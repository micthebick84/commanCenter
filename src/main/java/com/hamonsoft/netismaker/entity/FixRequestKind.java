package com.hamonsoft.netismaker.entity;

/**
 * 수정 회차의 종류 (스펙 2026-11-13). DB·API·워커가 enum 이름을 그대로 주고받는다.
 *  FIX       — 수정 요청(기존 회차)
 *  BASE_SYNC — 기준 브랜치 반영(종료 전 충돌 해결)
 */
public enum FixRequestKind {
    FIX,
    BASE_SYNC
}
