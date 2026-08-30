package com.hamonsoft.netismaker.entity;

/**
 * 세션 종류. INTERVIEW = 작업 등록용 플랜 인터뷰(기존), QUESTION = Q&A 전용 —
 * 플랜/등록 전이가 불가능하고 인터뷰 서비스가 읽기 전용 Q&A 모드로 실행한다.
 * DB에는 enum 이름 그대로 VARCHAR(20) 저장 (V20).
 */
public enum InterviewKind {
    INTERVIEW,
    QUESTION
}
