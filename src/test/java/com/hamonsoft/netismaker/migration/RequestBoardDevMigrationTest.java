package com.hamonsoft.netismaker.migration;

import com.hamonsoft.netismaker.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V46 요청 게시판 개발 요청 스키마 통합 테스트 (스펙 2026-11-19 §6).
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class RequestBoardDevMigrationTest {

    @Autowired private JdbcTemplate jdbc;

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'com' AND table_name = ?",
                String.class, table);
    }

    @Test
    void request_post에_다섯_칸이_생긴다() {
        assertThat(columns("request_post")).contains(
                "issue_type", "priority", "due_date", "target_repo_catalog_id", "task_id");
    }

    @Test
    void request_attachment_테이블이_생긴다() {
        assertThat(columns("request_attachment")).contains(
                "id", "request_id", "ordinal", "original_name", "stored_path", "mime_type", "size_bytes", "created_at");
    }

    @Test
    void task에_source_request_id가_생긴다() {
        assertThat(columns("task")).contains("source_request_id");
    }

    @Test
    void 모르는_이슈_유형은_CHECK로_거절된다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO com.request_post (kind, title, content, status, requester_id, issue_type,"
                        + " last_requester_activity_at, requester_read_at, created_at, updated_at)"
                        + " VALUES ('DEV', 't', 'c', 'RECEIVED', 'user1', 'X', now(), now(), now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_request_post_issue_type");
    }
}
