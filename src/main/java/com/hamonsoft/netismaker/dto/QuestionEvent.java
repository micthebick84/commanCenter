package com.hamonsoft.netismaker.dto;

/**
 * SSE event:question 페이로드 — {seq, content} JSON 객체.
 * 프론트(useInterviewStream)가 seq로 dedup(재연결 replay 중복 방지)하므로 bare 문자열이 아니라 객체로 전달.
 */
public record QuestionEvent(int seq, String content) {}
