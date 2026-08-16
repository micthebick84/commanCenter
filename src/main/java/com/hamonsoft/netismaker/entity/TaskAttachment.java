package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 작업 첨부파일 메타 (1 task : N attachment, 등록 시점에만 생성 — 추가/삭제 없음).
 * 파일 본체는 app.attachment.dir 하위 stored_path(상대경로)에 있다.
 * stored_path = task-{taskId}/{ordinal}-{sanitized} — ordinal은 업로드 순번(1..N).
 * DB id가 아닌 이유: IDENTITY 전략이라 INSERT 전에 id를 알 수 없는데 stored_path는 NOT NULL.
 */
@Entity
@Table(name = "task_attachment", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    /** 클라이언트 신고값 — 신뢰하지 않음(표시·프롬프트 참고용). null 가능. */
    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false, length = 20)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TaskAttachment create(Long taskId, String originalFilename, String storedPath,
                                        String contentType, long sizeBytes, String uploadedBy) {
        TaskAttachment a = new TaskAttachment();
        a.taskId = taskId;
        a.originalFilename = originalFilename;
        a.storedPath = storedPath;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.uploadedBy = uploadedBy;
        a.createdAt = OffsetDateTime.now();
        return a;
    }
}
