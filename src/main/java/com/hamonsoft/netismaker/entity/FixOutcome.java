package com.hamonsoft.netismaker.entity;

/**
 * 승인된 수정 회차가 어떻게 닫혔는지 (스펙 2026-11-03 §4·§7.1). 열려 있는 동안은 null.
 *  DEPLOYED      — 같은 브랜치에 push한 구현이 자동 재배포까지 성공
 *  DEPLOY_FAILED — 구현은 끝났지만 자동 재배포가 실패
 *  ABORTED       — 관리자가 수정 중단(구현실패 뒤 PR생성으로 되돌림)
 */
public enum FixOutcome {
    DEPLOYED,
    DEPLOY_FAILED,
    ABORTED
}
