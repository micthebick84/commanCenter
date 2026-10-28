package com.hamonsoft.netismaker.service.render;

/**
 * 렌더링 대기(PENDING) 첨부가 생겼으니 깨어나라는 신호 — 업로드 한 번에 한 번만 낸다.
 * 리스너는 커밋 뒤(@TransactionalEventListener AFTER_COMMIT)에 받는다 (스펙 2026-10-27 §5.1).
 */
public record AttachmentRenderRequested() {
}
