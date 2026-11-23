package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 질문 세션 대화 중 제안 (스펙 2026-11-22 §6.2). 답변 중 Claude가 남긴 DB 연결·참조 레포 제안과 그 결과.
 * 연결·카탈로그 칸은 FK ON DELETE SET NULL이라 대상이 지워져도 제안 기록은 남는다.
 */
@Entity
@Table(name = "question_suggestion", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "turn_seq", nullable = false)
    private Integer turnSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionKind kind;

    @Column(name = "db_connection_id")
    private Long dbConnectionId;

    @Column(name = "repo_catalog_id")
    private Long repoCatalogId;

    @Column(nullable = false, length = 200)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "targets", nullable = false, columnDefinition = "jsonb")
    private List<String> targets = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    static final int DECIDED_BY_MAX = 20;

    @Column(name = "decided_by", length = 20)
    private String decidedBy;

    // DB 기본값(now())이 채운다 — 저장 직후 재조회에서 읽힌다
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** dbConnectionId가 null이면 연결은 사용자가 고른다(대상 테이블 이름만 제안). */
    public static QuestionSuggestion db(long sessionId, int turnSeq, Long dbConnectionId, String reason, List<String> targets) {
        QuestionSuggestion s = base(sessionId, turnSeq, SuggestionKind.DB, reason);
        s.dbConnectionId = dbConnectionId;
        s.targets = new ArrayList<>(targets);
        return s;
    }

    public static QuestionSuggestion refRepo(long sessionId, int turnSeq, long repoCatalogId, String reason) {
        QuestionSuggestion s = base(sessionId, turnSeq, SuggestionKind.REF_REPO, reason);
        s.repoCatalogId = repoCatalogId;
        return s;
    }

    private static QuestionSuggestion base(long sessionId, int turnSeq, SuggestionKind kind, String reason) {
        QuestionSuggestion s = new QuestionSuggestion();
        s.sessionId = sessionId;
        s.turnSeq = turnSeq;
        s.kind = kind;
        s.reason = reason;
        return s;
    }

    /** PENDING일 때만 바꾸고 true. 이미 결정됐으면 그대로 두고 false(동시 결정의 뒤쪽은 무시). */
    public boolean decide(SuggestionStatus next, String actorId, OffsetDateTime now) {
        if (next == SuggestionStatus.PENDING) throw new IllegalArgumentException("PENDING으로는 결정할 수 없습니다");
        if (status != SuggestionStatus.PENDING) return false;
        this.status = next;
        // decided_by는 VARCHAR(20) - com."user" 행이 없는 긴 이름의 관리자도 결정할 수 있어 잘라 둔다(500 방지)
        this.decidedBy = actorId == null || actorId.length() <= DECIDED_BY_MAX ? actorId : actorId.substring(0, DECIDED_BY_MAX);
        this.decidedAt = now;
        return true;
    }
}
