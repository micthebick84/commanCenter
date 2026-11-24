package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dbmcp.DbWriteProperties;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Choice;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbConnectionServiceDeployChoicesTest {

    private DbConnectionRepository repo;
    private DbSecretCipher cipher;
    private DbConnectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(DbConnectionRepository.class);
        cipher = new DbSecretCipher("test-key", "0123456789abcdef");
        service = new DbConnectionService(repo, cipher, mock(DbConnectionTester.class), mock(RepoCatalogRepository.class),
                mock(TaskDbObjectRepository.class), new DbWriteProperties(false, 1000, 10000));
    }

    private DbConnection row(long id, DbConnectionScope scope, String owner, DbType type, boolean enabled, long repoId) {
        DbConnection c = DbConnection.create(scope, repoId, owner, "conn" + id, type, "db.local", 5432,
                "app", "reader", cipher.encrypt("pw"), "x");
        ReflectionTestUtils.setField(c, "id", id);
        c.setEnabled(enabled);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void 공용과_본인것만_종류로_거른다() {
        List<DbConnection> rows = List.of(
                row(1, DbConnectionScope.REPO, null, DbType.POSTGRESQL, true, 5),
                row(2, DbConnectionScope.USER, "admin", DbType.POSTGRESQL, true, 5),
                row(3, DbConnectionScope.USER, "other", DbType.POSTGRESQL, true, 5),
                row(4, DbConnectionScope.REPO, null, DbType.MYSQL, true, 5),
                row(5, DbConnectionScope.REPO, null, DbType.POSTGRESQL, false, 5));
        when(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(5L)).thenReturn(rows);

        List<Choice> out = service.deployChoices(5L, "admin", Set.of(DbType.POSTGRESQL), null);

        assertThat(out).extracting(Choice::id).containsExactly(1L, 2L);
        assertThat(out.get(0).scope()).isEqualTo("REPO");
        assertThat(out.get(0).dbType()).isEqualTo("POSTGRESQL");
        assertThat(out.get(0).database()).isEqualTo("app");
        assertThat(out.get(0).host()).isEqualTo("db.local");
        assertThat(out.get(0).port()).isEqualTo(5432);
    }

    @Test
    void 저장된_다른관리자_개인연결은_덧붙인다() {
        List<DbConnection> rows = List.of(
                row(1, DbConnectionScope.REPO, null, DbType.POSTGRESQL, true, 5),
                row(3, DbConnectionScope.USER, "other", DbType.POSTGRESQL, true, 5));
        when(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(5L)).thenReturn(rows);

        List<Choice> out = service.deployChoices(5L, "admin", Set.of(DbType.POSTGRESQL), 3L);

        assertThat(out).extracting(Choice::id).containsExactly(1L, 3L);
        assertThat(out.get(1).scope()).isEqualTo("USER");
    }
}
