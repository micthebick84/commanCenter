package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.RenderStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RenderTargetPolicyTest {

    private static RenderProperties props(boolean enabled) {
        return new RenderProperties(enabled, "", List.of("pptx", "ppt"), 120, 30, 1600, 20, 180);
    }

    private static RenderTargetPolicy on() {
        return new RenderTargetPolicy(props(true), Optional.of(Path.of("/usr/bin/soffice")));
    }

    @Test
    void 대문자_확장자도_대상이면_PENDING() {
        AttachmentRendition r = on().initial("deck.PPTX");
        assertThat(r.getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(on().active()).isTrue();
    }

    @Test
    void 대상이_아닌_확장자는_NONE() {
        assertThat(on().initial("a.docx").getStatus()).isEqualTo(RenderStatus.NONE);
        assertThat(on().initial("noext").getStatus()).isEqualTo(RenderStatus.NONE);
        assertThat(on().initial(null).getStatus()).isEqualTo(RenderStatus.NONE);
    }

    @Test
    void 설정이_꺼지면_대상은_SKIPPED_렌더링_꺼짐() {
        RenderTargetPolicy off = new RenderTargetPolicy(props(false), Optional.of(Path.of("/usr/bin/soffice")));
        AttachmentRendition r = off.initial("deck.pptx");
        assertThat(off.active()).isFalse();
        assertThat(r.getStatus()).isEqualTo(RenderStatus.SKIPPED);
        assertThat(r.getError()).isEqualTo("렌더링 꺼짐");
        assertThat(off.initial("a.docx").getStatus()).isEqualTo(RenderStatus.NONE);
    }

    @Test
    void soffice가_없으면_꺼짐() {
        RenderTargetPolicy off = new RenderTargetPolicy(props(true), Optional.empty());
        assertThat(off.active()).isFalse();
        assertThat(off.initial("deck.pptx").getStatus()).isEqualTo(RenderStatus.SKIPPED);
    }
}
