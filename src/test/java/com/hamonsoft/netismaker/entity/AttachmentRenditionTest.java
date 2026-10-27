package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentRenditionTest {

    @Test
    void markRunning_두_번이면_시도가_2() {
        AttachmentRendition r = AttachmentRendition.of(RenderStatus.PENDING, null);
        r.markRunning(OffsetDateTime.now());
        r.markRunning(OffsetDateTime.now());
        assertThat(r.getAttempts()).isEqualTo(2);
        assertThat(r.getStatus()).isEqualTo(RenderStatus.RUNNING);
    }

    @Test
    void markDone은_DONE과_숫자를_기록() {
        AttachmentRendition r = AttachmentRendition.of(RenderStatus.PENDING, null);
        r.markDone("a.render", 2, 2, 2);
        assertThat(r.getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(r.getDir()).isEqualTo("a.render");
        assertThat(r.getPageCount()).isEqualTo(2);
    }

    @Test
    void copyWithDir은_상태_숫자_사유를_옮기고_시도와_시작시각을_비운다() {
        AttachmentRendition r = AttachmentRendition.of(RenderStatus.PENDING, "사유");
        r.markRunning(OffsetDateTime.now());
        r.markDone("a.render", 2, 3, 1);
        AttachmentRendition c = r.copyWithDir("b.render");
        assertThat(c.getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(c.getDir()).isEqualTo("b.render");
        assertThat(c.getPageCount()).isEqualTo(2);
        assertThat(c.getTotalPages()).isEqualTo(3);
        assertThat(c.getMediaCount()).isEqualTo(1);
        assertThat(c.getAttempts()).isZero();
        assertThat(c.getStartedAt()).isNull();
    }

    @Test
    void of는_사유를_기록() {
        assertThat(AttachmentRendition.of(RenderStatus.SKIPPED, "렌더링 꺼짐").getError()).isEqualTo("렌더링 꺼짐");
    }
}
