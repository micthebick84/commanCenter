package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 요청 게시판 개발 요청 첨부 메타. 파일 본체는 app.attachment.dir 아래 stored_path(상대경로)에 있다. */
@Entity
@Table(name = "request_attachment", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_path", nullable = false, columnDefinition = "TEXT")
    private String storedPath;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static RequestAttachment create(Long requestId, int ordinal, String originalName,
                                           String storedPath, String mimeType, long sizeBytes) {
        RequestAttachment a = new RequestAttachment();
        a.requestId = requestId;
        a.ordinal = ordinal;
        a.originalName = originalName;
        a.storedPath = storedPath;
        a.mimeType = mimeType;
        a.sizeBytes = sizeBytes;
        a.createdAt = OffsetDateTime.now();
        return a;
    }
}
