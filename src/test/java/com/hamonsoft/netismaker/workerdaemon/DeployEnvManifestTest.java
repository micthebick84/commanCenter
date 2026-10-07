package com.hamonsoft.netismaker.workerdaemon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 구현 세션이 남긴 .netis-deploy-env.json 회수 (스펙 2026-10-07 §4.2). */
class DeployEnvManifestTest {

    @TempDir Path wt;

    private Path manifest(String json) throws IOException {
        return Files.writeString(wt.resolve(DeployEnvManifest.FILE_NAME), json);
    }

    @Test
    void missing_file_yields_an_empty_template() {
        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
    }

    @Test
    void valid_file_is_parsed_and_then_deleted() throws Exception {
        Path f = manifest("""
                {"vars":[
                  {"key":"SPRING_DATASOURCE_URL","description":"DB 접속 JDBC URL","secret":false,"required":true},
                  {"key":"JWT_SECRET","description":"토큰 서명 키","secret":true,"required":true}
                ]}
                """);

        List<EnvTemplateItem> out = DeployEnvManifest.harvest(wt);

        assertThat(out).containsExactly(
                new EnvTemplateItem("SPRING_DATASOURCE_URL", "DB 접속 JDBC URL", false, true),
                new EnvTemplateItem("JWT_SECRET", "토큰 서명 키", true, true));
        assertThat(f).doesNotExist();   // 커밋(git add -A)에 섞이면 안 된다
    }

    @Test
    void values_written_by_mistake_are_never_carried() throws Exception {
        manifest("{\"vars\":[{\"key\":\"DB_PASSWORD\",\"value\":\"s3cret\",\"secret\":true}]}");

        List<EnvTemplateItem> out = DeployEnvManifest.harvest(wt);

        assertThat(out).containsExactly(new EnvTemplateItem("DB_PASSWORD", "", true, false));
        assertThat(out.toString()).doesNotContain("s3cret");
    }

    @Test
    void a_top_level_array_is_accepted() throws Exception {
        manifest("[{\"key\":\"API_BASE_URL\",\"required\":true}]");

        assertThat(DeployEnvManifest.harvest(wt))
                .containsExactly(new EnvTemplateItem("API_BASE_URL", "", false, true));
    }

    @Test
    void broken_json_yields_empty_and_still_deletes_the_file() throws Exception {
        Path f = manifest("{\"vars\":[{\"key\":");

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
        assertThat(f).doesNotExist();
    }

    @Test
    void a_broken_file_never_writes_its_content_to_the_log() throws Exception {
        // 지시를 어기고 값을 적은 데다 JSON까지 깨진 경우 — 파서 예외 메시지에 토큰 원문(값)이 들어간다
        Logger logger = (Logger) LoggerFactory.getLogger(DeployEnvManifest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            manifest("{\"vars\":[{\"key\":\"DB_PASSWORD\",\"value\":s3cret}]}");

            assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("s3cret"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void unexpected_shape_yields_empty() throws Exception {
        manifest("{\"variables\":{\"A\":1}}");

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
    }

    @Test
    void entries_are_sanitized() throws Exception {
        manifest("{\"vars\":[{\"key\":\"BAD-KEY\"},{\"key\":\"GOOD\"},{\"key\":\"GOOD\"},\"not-an-object\"]}");

        assertThat(DeployEnvManifest.harvest(wt)).extracting(EnvTemplateItem::key).containsExactly("GOOD");
    }

    @Test
    void a_symlink_is_not_followed_and_only_the_link_is_removed() throws Exception {
        Path target = Files.writeString(wt.resolve("elsewhere.json"), "{\"vars\":[{\"key\":\"LEAK\"}]}");
        Path link = wt.resolve(DeployEnvManifest.FILE_NAME);
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경: " + e);
        }

        assertThat(DeployEnvManifest.harvest(wt)).isEmpty();
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(target).exists();
    }
}
