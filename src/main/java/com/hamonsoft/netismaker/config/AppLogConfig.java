package com.hamonsoft.netismaker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 앱 로그 설정 등록. 앱 클래스(NetisMakerApplication)의 @EnableConfigurationProperties 목록에 넣지 않고 여기서 하는 이유:
 * 같은 줄을 고치는 다른 작업과 충돌하지 않게 하려는 것이고, API 프로필에서만 쓰므로 워커 프로필은 이 설정을 검증하지 않는다.
 */
@Configuration
@Profile("api")
@EnableConfigurationProperties(AppLogProperties.class)
public class AppLogConfig {
}
