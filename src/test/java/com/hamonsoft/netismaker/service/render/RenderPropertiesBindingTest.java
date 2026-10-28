package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** yml 모양(쉼표 목록·빈 soffice-path)과 값이 빠졌을 때의 기본값이 바인딩되는지. */
class RenderPropertiesBindingTest {

    private static RenderProperties bind(Map<String, String> src) {
        return new Binder(new MapConfigurationPropertySource(src))
                .bindOrCreate("app.attachment.render", RenderProperties.class);
    }

    @Test
    void 값이_없으면_기본값() {
        RenderProperties p = bind(Map.of());
        assertThat(p.enabled()).isTrue();
        assertThat(p.sofficePath()).isEmpty();
        assertThat(p.extensions()).containsExactly("pptx", "ppt");
        assertThat(p.timeoutSeconds()).isEqualTo(120);
        assertThat(p.maxPages()).isEqualTo(30);
        assertThat(p.widthPx()).isEqualTo(1600);
        assertThat(p.maxMedia()).isEqualTo(20);
        assertThat(p.claimWaitSeconds()).isEqualTo(180);
    }

    @Test
    void yml_값을_읽는다() {
        RenderProperties p = bind(Map.of("app.attachment.render.enabled", "false",
                "app.attachment.render.soffice-path", "",
                "app.attachment.render.extensions", "pptx,ppt,odp",
                "app.attachment.render.timeout-seconds", "60"));
        assertThat(p.enabled()).isFalse();
        assertThat(p.extensions()).containsExactly("pptx", "ppt", "odp");
        assertThat(p.timeoutSeconds()).isEqualTo(60);
    }
}
