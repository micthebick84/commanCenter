package com.hamonsoft.netismaker.dto;

/**
 * 목업 업로드 응답 (스펙 2026-11-03 §5) — 에이전트에게 돌아가는 도구 결과 "목업 {n} 게시됨"의 재료.
 *
 * @param n       세션 안 순서 번호(질문에서 '목업 n'으로 가리키는 번호)
 * @param version 그 이름의 몇 번째 버전인지(1부터)
 */
public record WorkerMockupResponse(Long id, int n, int version) {}
