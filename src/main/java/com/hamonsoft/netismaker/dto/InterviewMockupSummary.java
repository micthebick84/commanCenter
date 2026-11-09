package com.hamonsoft.netismaker.dto;

import java.time.OffsetDateTime;

/**
 * 인터뷰 목업 목록 한 줄 (스펙 2026-11-03 §5) — SSE {@code mockup} 이벤트와 {@code GET /api/interviews/{sid}/mockups} 응답이 같은 모양을 쓴다.
 * html은 싣지 않는다(목록·SSE·로그에 본문이 새지 않게) — 본문은 단건 조회({@link InterviewMockupView})로만 나간다.
 *
 * @param n        세션 안 순서 번호(질문에서 '목업 n'으로 가리키는 번호)
 * @param turnSeq  묶인 턴의 seq. 아직 안 묶였으면 null
 * @param turnKind 묶인 턴의 종류(question | design). 아직 안 묶였으면 null
 */
public record InterviewMockupSummary(Long id, int n, String name, int version, String title, String description,
                                     Integer turnSeq, String turnKind, OffsetDateTime createdAt) {}
