package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 요청 답글 또는 시스템 기록. 요청과는 id 컬럼으로만 잇는다(연관 매핑 없음, 삭제는 DB CASCADE). */
@Entity
@Table(name = "request_comment", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestCommentKind kind;

    /** SYSTEM 기록도 행위자(관리자) id를 남긴다. */
    @Column(name = "author_id", length = 20)
    private String authorId;

    /** 작성 시점 관리자 여부 박제. */
    @Column(name = "author_admin", nullable = false)
    private boolean authorAdmin;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static RequestComment create(Long requestId, RequestCommentKind kind, String authorId,
                                        boolean authorAdmin, String body) {
        RequestComment c = new RequestComment();
        c.requestId = requestId;
        c.kind = kind;
        c.authorId = authorId;
        c.authorAdmin = authorAdmin;
        c.body = body;
        c.createdAt = OffsetDateTime.now();
        return c;
    }
}
