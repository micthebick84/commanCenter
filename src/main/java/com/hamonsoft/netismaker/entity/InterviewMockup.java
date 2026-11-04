package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * 인터뷰 중 에이전트가 올린 화면 목업 한 건 (스펙 2026-11-03 §5). 같은 name으로 다시 올리면 새 버전이고, n은 세션 안 순서 번호다.
 *
 * 만들 때는 어느 턴에도 안 묶인 상태(turnSeq null)다 — 묶기(bindUnbound)와 버리기(discardUnbound)는 저장소의 한 문장 UPDATE로만 하므로
 * 이 엔티티에는 setter가 없다. 목록·SSE는 html 없이 {@code InterviewMockupSummary}로 읽는다(이 클래스를 목록에 쓰지 말 것).
 */
@Entity
@Table(name = "interview_mockup", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewMockup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 세션 안에서 올라온 순서 번호 — 버전마다 새로 매긴다. 버린 행의 번호는 비어 있는 채로 둔다. */
    @Column(nullable = false)
    private int n;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 120)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String html;

    /** html의 UTF-8 바이트 수 (글자 수 아님 — 상한이 바이트 기준). */
    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /** 묶인 턴의 seq. null = 아직 안 묶임(턴 도중 올라온 목업). */
    @Column(name = "turn_seq")
    private Integer turnSeq;

    /** 묶인 턴의 종류 — question | design. turnSeq와 함께 묶일 때 정해진다. */
    @Column(name = "turn_kind", length = 20)
    private String turnKind;

    /** 끊긴 턴(재기동 재큐·턴 실패)에서 안 묶인 채 남아 버려진 목업. */
    @Column(nullable = false)
    private boolean discarded;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static InterviewMockup create(Long sessionId, int n, String name, int version,
                                         String title, String description, String html) {
        InterviewMockup m = new InterviewMockup();
        m.sessionId = sessionId;
        m.n = n;
        m.name = name;
        m.version = version;
        m.title = title;
        m.description = description;
        m.html = html;
        m.sizeBytes = html.getBytes(StandardCharsets.UTF_8).length;
        m.createdAt = OffsetDateTime.now();
        return m;
    }
}
