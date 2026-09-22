package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 질문 세션 첨부파일 메타 (1 session : N attachment, 메시지 단위로 추가 — 스펙 2026-09-13 §4).
 * 파일 본체는 app.attachment.dir 하위 stored_path(상대경로)에 있다.
 * stored_path = question-{sid}/{create|turnSeq}/{ordinal}-{sanitized} — 메시지별 하위 디렉터리로 순번을 격리.
 * turn_seq null = 등록 시(킥오프) 첨부, 아니면 그 답변(user answer) 턴의 seq.
 * extracted_text_path = Tika sidecar(.txt) 상대경로 — 오피스 문서 + 추출 성공 시만, 그 외 null.
 */
@Entity
@Table(name = "question_attachment", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "turn_seq")
    private Integer turnSeq;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    /** 클라이언트 신고값 — 신뢰하지 않음(표시·프롬프트 참고용). null 가능. */
    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "extracted_text_path", length = 500)
    private String extractedTextPath;

    @Column(name = "uploaded_by", nullable = false, length = 20)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static QuestionAttachment create(Long sessionId, Integer turnSeq, String originalFilename,
                                            String storedPath, String contentType, long sizeBytes,
                                            String extractedTextPath, String uploadedBy) {
        QuestionAttachment a = new QuestionAttachment();
        a.sessionId = sessionId;
        a.turnSeq = turnSeq;
        a.originalFilename = originalFilename;
        a.storedPath = storedPath;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.extractedTextPath = extractedTextPath;
        a.uploadedBy = uploadedBy;
        a.createdAt = OffsetDateTime.now();
        return a;
    }
}
