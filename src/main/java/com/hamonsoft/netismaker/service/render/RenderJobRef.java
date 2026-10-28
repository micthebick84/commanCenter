package com.hamonsoft.netismaker.service.render;

/**
 * 렌더링 잡 한 건 — 큐가 claim한 첨부 행을 트랜잭션 밖으로 넘기는 값 (스펙 2026-10-27 §6).
 * storedPath는 첨부 루트 기준 상대경로, originalFilename은 렌더러에 넘길 확장자의 출처.
 */
public record RenderJobRef(Kind kind, long attachmentId, String storedPath, String originalFilename) {

    public enum Kind { TASK, QUESTION }
}
