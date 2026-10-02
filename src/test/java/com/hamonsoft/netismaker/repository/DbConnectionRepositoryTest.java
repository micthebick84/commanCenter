package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V24 com.db_connection — 조회 순서, owner CHECK, 세션 db_connection_ids jsonb 왕복. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DbConnectionRepositoryTest {

    @Autowired private DbConnectionRepository repo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    private Long repoId;

    @BeforeEach void setUp() {
        repo.deleteAll();
        sessionRepo.deleteAll();
        // repo_catalog의 alias·git_url은 UNIQUE — 테스트마다 다른 값을 쓴다.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        repoId = repoCatalogRepo.save(RepoCatalogEntry.create("DB테스트레포-" + suffix,
                "https://github.com/acme/dbtest-" + suffix + ".git",
                "github", "acme/dbtest-" + suffix, "main", null, "admin1")).getId();
    }

    @Test
    void REPO가_먼저_이름순으로_조회된다() {
        repo.save(DbConnection.create(DbConnectionScope.USER, repoId, "user1", "가 개인", DbType.MYSQL,
                "h", 3306, "app", "u", "enc", "user1"));
        repo.save(DbConnection.create(DbConnectionScope.REPO, repoId, null, "나 공용", DbType.POSTGRESQL,
                "h", 5432, "app", "u", "enc", "admin1"));
        assertThat(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(repoId))
                .extracting(DbConnection::getName).containsExactly("나 공용", "가 개인");
    }

    @Test
    void USER인데_owner가_없으면_CHECK_위반() {
        assertThatThrownBy(() -> repo.saveAndFlush(DbConnection.create(DbConnectionScope.USER, repoId, null, "x",
                DbType.ORACLE, "h", 1521, "svc", "u", "enc", "user1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 세션_db_connection_ids_왕복() {
        InterviewSession s = InterviewSession.createQuestion("acme/dbtest", "main", "t", "q", "user1",
                List.of(), "claude-opus-5-5", "high");
        s.setDbConnectionIds(List.of(3L, 9L));
        Long id = sessionRepo.save(s).getId();
        // jsonb 역직렬화 시 원소가 Integer일 수 있어 Object 경유로 Number 캐스팅한다.
        assertThat(sessionRepo.findById(id).orElseThrow().getDbConnectionIds())
                .extracting(o -> ((Number) (Object) o).longValue()).containsExactly(3L, 9L);
    }
}
