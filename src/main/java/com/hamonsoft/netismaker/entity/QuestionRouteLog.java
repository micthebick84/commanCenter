package com.hamonsoft.netismaker.entity;

import com.hamonsoft.netismaker.service.route.JevClient;
import com.hamonsoft.netismaker.service.route.RouteDecision;
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
 * 질문 레포 추천 기록 (스펙 2026-11-20 §6.4·§7). question_excerpt는 모델에 보낸(보낼) 발췌뿐이고 질문 전문은 저장하지 않는다.
 * 레포·세션 칸은 FK ON DELETE SET NULL이라 카탈로그·세션이 지워져도 기록은 남는다.
 */
@Entity
@Table(name = "question_route_log", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionRouteLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "question_excerpt", nullable = false, columnDefinition = "TEXT")
    private String questionExcerpt;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(length = 50)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidates", nullable = false, columnDefinition = "jsonb")
    private List<RouteDecision.Candidate> candidates = new ArrayList<>();

    @Column(name = "mentions_target")
    private Double mentionsTarget;

    @Column(name = "target_listed")
    private Double targetListed;

    @Column(name = "needs_db")
    private Double needsDb;

    @Column(name = "recommended_repo_catalog_id")
    private Long recommendedRepoCatalogId;

    @Column(name = "chosen_repo_catalog_id")
    private Long chosenRepoCatalogId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "chosen_at")
    private OffsetDateTime chosenAt;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** answers가 null이면(Jev를 부르지 않았거나 응답 없음) 모델·세 수치는 비운다. latencyMs는 Jev 호출을 했을 때만. */
    public static QuestionRouteLog of(String userId, String excerpt, RouteDecision.Decision decision,
                                      JevClient.JevAnswers answers, Integer latencyMs) {
        QuestionRouteLog l = new QuestionRouteLog();
        l.createdAt = OffsetDateTime.now();
        l.userId = userId;
        l.questionExcerpt = excerpt;
        l.state = decision.state().name();
        l.candidates = new ArrayList<>(decision.candidates());
        l.recommendedRepoCatalogId = decision.recommendedRepoCatalogId();
        if (answers != null) {
            l.model = answers.model();
            l.mentionsTarget = answers.mentionsTarget();
            l.targetListed = answers.targetListed();
            l.needsDb = answers.needsDb();
        }
        l.latencyMs = latencyMs;
        return l;
    }

    /** 사용자가 실제로 고른 레포·만든 세션을 기록한다. 이미 기록돼 있으면(chosenAt) 아무것도 하지 않는다. */
    public void markChosen(Long repoCatalogId, Long sessionId) {
        if (chosenAt != null) return;
        this.chosenRepoCatalogId = repoCatalogId;
        this.sessionId = sessionId;
        this.chosenAt = OffsetDateTime.now();
    }
}
