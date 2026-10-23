package com.hamonsoft.netismaker.dto;

import java.util.List;

/** 인터뷰 서비스 기동 정합 결과 — released = QUEUED로 되돌린 세션 id, failed = 자동 재개 상한 소진으로 FAILED 처리한 세션 id. */
public record InterviewStartupResponse(List<Long> released, List<Long> failed) {}
