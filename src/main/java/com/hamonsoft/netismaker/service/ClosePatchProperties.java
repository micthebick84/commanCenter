package com.hamonsoft.netismaker.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 종료 부분 패치 설정 (스펙 2026-11-10).
 *  enabled      전역 스위치. 켜도 레포 카탈로그에서 patch_enabled인 레포만 대상(기본 꺼짐)
 *  max-size-mb  패치 zip 업로드 상한(기본 1024)
 */
@ConfigurationProperties(prefix = "app.close.patch")
public record ClosePatchProperties(boolean enabled, Long maxSizeMb) {

    public ClosePatchProperties {
        if (maxSizeMb == null || maxSizeMb <= 0) maxSizeMb = 1024L;
    }

    public long maxBytes() {
        return maxSizeMb * 1024L * 1024L;
    }
}
