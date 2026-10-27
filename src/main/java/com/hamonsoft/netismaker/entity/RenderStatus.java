package com.hamonsoft.netismaker.entity;

/** 첨부 슬라이드 렌더링 상태 (스펙 2026-10-27 §4). NONE = 렌더링 대상 아님/아직 판단 전. */
public enum RenderStatus {
    NONE, PENDING, RUNNING, DONE, FAILED, SKIPPED
}
