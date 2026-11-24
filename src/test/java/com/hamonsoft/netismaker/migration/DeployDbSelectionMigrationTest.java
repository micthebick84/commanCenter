package com.hamonsoft.netismaker.migration;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V50 배포 DB 선택 스키마 통합 테스트 (스펙 2026-11-24-deploy-db-connection-select).
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DeployDbSelectionMigrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RepoCatalogRepository catalogRepo;
    @Autowired private TaskRepository taskRepo;

    private Map<String, Object> column(String table, String name) {
        return jdbc.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = 'com' AND table_name = ? AND column_name = ?", table, name);
    }

    @Test
    void 컬럼이_생긴다() {
        Map<String, Object> config = column("repo_catalog", "deploy_config");
        assertThat(config.get("data_type")).isEqualTo("jsonb");
        assertThat(config.get("is_nullable")).isEqualTo("YES");

        Map<String, Object> selection = column("task", "deploy_db_selection");
        assertThat(selection.get("data_type")).isEqualTo("jsonb");
        assertThat(selection.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void 카탈로그_배포설정이_왕복된다() {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("jdbc.url", "url");
        entries.put("jdbc.username", "user");
        entries.put("jdbc.password", "pw");
        DeployConfig config = new DeployConfig(
                List.of(new DeployConfig.DbSlot("main", "메인 DB", List.of(DbType.POSTGRESQL),
                        new DeployConfig.Target(DeployConfig.TargetKind.FILE, "WEB-INF/db.properties", entries))),
                List.of());

        long nano = System.nanoTime();
        RepoCatalogEntry cat = RepoCatalogEntry.create("배포설정-it-" + nano,
                "https://github.com/owner/deploycfg-" + nano, "github", "owner/deploycfg-" + nano,
                "main", null, "admin");
        cat.setDeployConfig(config);
        Long id = catalogRepo.save(cat).getId();
        try {
            RepoCatalogEntry back = catalogRepo.findById(id).orElseThrow();
            assertThat(back.getDeployConfig()).isEqualTo(config);
            assertThat(back.getDeployConfig().databases().get(0).target().entries().keySet())
                    .containsExactly("jdbc.url", "jdbc.username", "jdbc.password");
        } finally {
            catalogRepo.deleteById(id);
        }
    }

    @Test
    void 작업_DB선택이_왕복된다() {
        Task t = Task.create("owner/repo-" + System.nanoTime(), "main", "t", "d", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        List<DeployDbChoice> choices = List.of(new DeployDbChoice("main", 43L), new DeployDbChoice("extra", null));
        t.setDeployDbSelection(new java.util.ArrayList<>(choices));
        Long id = taskRepo.save(t).getId();
        try {
            assertThat(taskRepo.findById(id).orElseThrow().getDeployDbSelection()).isEqualTo(choices);
        } finally {
            taskRepo.deleteById(id);
        }
    }
}
