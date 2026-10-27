package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 첨부 pptx 슬라이드 렌더링 칸 — task_attachment·question_attachment 공용 (스펙 2026-10-27 §4).
 * dir = 첨부 루트 기준 상대 폴더(슬라이드 PNG가 있는 곳). pageCount = 실제 렌더한 장수, totalPages = 원본 장수.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttachmentRendition {

    private static final int MAX_ERROR = 500;

    @Enumerated(EnumType.STRING)
    @Column(name = "render_status", nullable = false, length = 16)
    private RenderStatus status = RenderStatus.NONE;

    @Column(name = "render_dir", length = 500)
    private String dir;

    @Column(name = "render_page_count")
    private Integer pageCount;

    @Column(name = "render_total_pages")
    private Integer totalPages;

    @Column(name = "render_media_count")
    private Integer mediaCount;

    @Column(name = "render_error", length = MAX_ERROR)
    private String error;

    @Column(name = "render_started_at")
    private OffsetDateTime startedAt;

    @Column(name = "render_attempts", nullable = false)
    private int attempts;

    public static AttachmentRendition of(RenderStatus initial, String error) {
        AttachmentRendition r = new AttachmentRendition();
        r.status = initial;
        r.error = cut(error);
        return r;
    }

    public void markRunning(OffsetDateTime now) {
        this.status = RenderStatus.RUNNING;
        this.startedAt = now;
        this.attempts++;
    }

    public void markDone(String dir, int pageCount, int totalPages, int mediaCount) {
        this.status = RenderStatus.DONE;
        this.dir = dir;
        this.pageCount = pageCount;
        this.totalPages = totalPages;
        this.mediaCount = mediaCount;
        this.error = null;
    }

    public void markFailed(String error) {
        this.status = RenderStatus.FAILED;
        this.error = cut(error);
    }

    public void resetToPending() {
        this.status = RenderStatus.PENDING;
        this.startedAt = null;
    }

    /** 승계용 복사 — 상태·숫자·사유는 옮기고 dir만 교체, 시작 시각 null·시도 0. */
    public AttachmentRendition copyWithDir(String newDir) {
        AttachmentRendition r = new AttachmentRendition();
        r.status = this.status;
        r.dir = newDir;
        r.pageCount = this.pageCount;
        r.totalPages = this.totalPages;
        r.mediaCount = this.mediaCount;
        r.error = this.error;
        r.startedAt = null;
        r.attempts = 0;
        return r;
    }

    private static String cut(String s) {
        return s != null && s.length() > MAX_ERROR ? s.substring(0, MAX_ERROR) : s;
    }
}
