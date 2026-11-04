package com.hamonsoft.netismaker.dto;

import java.time.OffsetDateTime;

/**
 * 인터뷰 목업 단건 (스펙 2026-11-03 §5) — {@code GET /api/interviews/{sid}/mockups/{id}} 응답. 목록 한 줄({@link InterviewMockupSummary})에
 * html과 소속(세션·작업)이 더해진 모양이다. 새 탭 화면이 이전·다음·"인터뷰로" 링크에 쓴다. JSON으로만 응답한다(text/html 금지).
 *
 * @param taskId 세션이 속한 작업 id (세션에 작업이 없으면 null)
 */
public record InterviewMockupView(Long id, Long sessionId, Long taskId, int n, String name, int version, String title,
                                  String description, Integer turnSeq, String turnKind, OffsetDateTime createdAt,
                                  String html) {

    /** html 본문은 싣지 않는다(로그에 새지 않게) — 길이만. */
    @Override
    public String toString() {
        return "InterviewMockupView{id=" + id + ", sessionId=" + sessionId + ", n=" + n + ", name=" + name
                + ", version=" + version + ", htmlLength=" + (html == null ? 0 : html.length()) + "}";
    }
}
