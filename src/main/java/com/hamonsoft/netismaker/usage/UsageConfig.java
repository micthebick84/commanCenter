package com.hamonsoft.netismaker.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** app.usage 바인딩. 프로필 없음 — api·worker 둘 다 같은 설정을 읽는다. */
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfig {
}
