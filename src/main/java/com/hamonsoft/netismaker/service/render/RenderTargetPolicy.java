package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.RenderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 업로드된 첨부가 슬라이드 렌더링 대상인지, 그리고 지금 렌더링이 켜져 있는지 정한다 (스펙 2026-10-27 §4).
 * 켜짐 = 설정 enabled 이고 soffice를 찾았을 때. 시작할 때 한 번 정하고 켜짐/꺼짐 줄을 한 번만 로그로 남긴다.
 */
@Slf4j
@Component
@Profile("api")
public class RenderTargetPolicy {

    static final String OFF_REASON = "렌더링 꺼짐";

    private final Set<String> extensions;
    private final Optional<Path> soffice;
    private final boolean active;

    @Autowired
    public RenderTargetPolicy(RenderProperties props) {
        this(props, props.enabled() ? SofficeLocator.resolveFromEnvironment(props.sofficePath()) : Optional.empty());
    }

    /** 테스트용 — soffice 탐색 결과를 직접 넘긴다. */
    RenderTargetPolicy(RenderProperties props, Optional<Path> soffice) {
        this.extensions = props.extensions().stream()
                .map(e -> e.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.soffice = soffice;
        this.active = props.enabled() && soffice.isPresent();
        if (!props.enabled()) {
            log.info("슬라이드 렌더링: 꺼짐(설정 off)");
        } else if (soffice.isPresent()) {
            log.info("슬라이드 렌더링: 켜짐(soffice={})", soffice.get());
        } else {
            log.info("슬라이드 렌더링: 꺼짐(soffice 없음)");
        }
    }

    /** 새 첨부의 초기 렌더링 상태 — 대상 아님 NONE, 대상인데 꺼짐 SKIPPED, 그 외 PENDING. */
    public AttachmentRendition initial(String originalName) {
        if (!isTarget(originalName)) return AttachmentRendition.of(RenderStatus.NONE, null);
        if (!active) return AttachmentRendition.of(RenderStatus.SKIPPED, OFF_REASON);
        return AttachmentRendition.of(RenderStatus.PENDING, null);
    }

    public boolean active() {
        return active;
    }

    /** 렌더러가 쓸 soffice 경로 — 꺼짐이면 empty. */
    public Optional<Path> sofficePath() {
        return active ? soffice : Optional.empty();
    }

    private boolean isTarget(String originalName) {
        if (originalName == null) return false;
        int dot = originalName.lastIndexOf('.');
        if (dot < 0) return false;
        return extensions.contains(originalName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
