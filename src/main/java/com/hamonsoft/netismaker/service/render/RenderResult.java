package com.hamonsoft.netismaker.service.render;

/**
 * 렌더링 결과 (스펙 2026-10-27 §5.2). pageCount = 그린 슬라이드 수(max-pages 상한 적용),
 * totalPages = PDF 전체 페이지 수, mediaCount = 복사한 원본 그림 수(pptx만, 아니면 0).
 */
public record RenderResult(int pageCount, int totalPages, int mediaCount) {
}
