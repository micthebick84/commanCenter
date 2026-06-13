package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 인터뷰 Q&A 추가전용 로그 (com.task_status_history 미러).
 * role: assistant | user | system,  kind: question | answer | design | gate | note.
 */
@Entity
@Table(name = "interview_turn", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** user answer가 응답하는 question 턴의 seq. idempotency 키 + UI 스레딩. 그 외 턴은 null. */
    @Column(name = "reply_to_seq")
    private Integer replyToSeq;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static InterviewTurn of(Long sessionId, int seq, String role, String kind,
                                   String content, Integer replyToSeq) {
        InterviewTurn t = new InterviewTurn();
        t.sessionId = sessionId;
        t.seq = seq;
        t.role = role;
        t.kind = kind;
        t.content = content;
        t.replyToSeq = replyToSeq;
        t.createdAt = OffsetDateTime.now();
        return t;
    }
}
