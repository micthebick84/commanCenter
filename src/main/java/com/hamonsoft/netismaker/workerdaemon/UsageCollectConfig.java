package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 수집 설정 등록 — 프로필 없음(바인딩만 하고 아무것도 켜지 않는다). */
@Configuration
@EnableConfigurationProperties(UsageCollectProperties.class)
public class UsageCollectConfig {
}
