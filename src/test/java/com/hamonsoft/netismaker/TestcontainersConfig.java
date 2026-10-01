package com.hamonsoft.netismaker;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 PostgreSQL Testcontainer.
 *
 * com."user" 테이블이 netis-backend 소유이므로 테스트에선 별도로 미리 생성.
 * Flyway가 com.task의 FK를 검증하므로 user 테이블 + 시드 데이터 필요.
 */
public class TestcontainersConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("netismaker_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("init-test-schema.sql");

    static {
        POSTGRES.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.flyway.schemas=com",
                "spring.flyway.default-schema=com",
                "spring.jpa.properties.hibernate.default_schema=com",
                // 첨부 정리 cron 차단 — 대부분의 통합 테스트는 app.attachment.dir 기본값(운영자 실제
                // ~/netis-maker/attachments)을 그대로 쓴다. 03:30에 컨텍스트가 살아 있으면 테스트 DB 기준으로
                // 실제 디렉터리를 고아로 오판해 지울 수 있다. 잡 자체는 runOnce 직접 호출로 검증한다.
                "app.attachment.cleanup.enabled=false"
        ).applyTo(ctx.getEnvironment());
    }
}
