package com.hamonsoft.netismaker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 첨부 pptx 슬라이드 렌더링 설정 (스펙 2026-10-27 §4·§8). yml의 app.attachment.render.* — 값이 빠져도
 * 아래 기본값으로 동작한다. sofficePath가 비면 SofficeLocator가 Windows 기본 위치·PATH에서 찾는다.
 */
@ConfigurationProperties("app.attachment.render")
public record RenderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String sofficePath,
        @DefaultValue({"pptx", "ppt"}) List<String> extensions,
        @DefaultValue("120") int timeoutSeconds,
        @DefaultValue("30") int maxPages,
        @DefaultValue("1600") int widthPx,
        @DefaultValue("20") int maxMedia,
        @DefaultValue("180") int claimWaitSeconds) {
}
