package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DbMcpGrantServiceTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final DbMcpTokenService tokens = mock(DbMcpTokenService.class);
    private final TaskStatusHistoryRepository history = mock(TaskStatusHistoryRepository.class);
    private final DbConnectionRepository connections = mock(DbConnectionRepository.class);
    private final RepoCatalogRepository catalogs = mock(RepoCatalogRepository.class);
    /** 판정은 진짜 DbWriteGate로 — 연결 행·카탈로그만 목. 스위치는 켜 둔다. */
    private final DbWriteGate gate = new DbWriteGate(new DbWriteProperties(true, 1000, 10000), connections, catalogs);
    private final DbMcpGrantService svc = new DbMcpGrantService(taskRepo, db, tokens, history, gate);
    private Task task;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        task.setDbConnectionIds(new ArrayList<>(List.of(12L, 13L)));
        task.setRepoCatalogId(1L);
        when(taskRepo.findById(5L)).thenReturn(Optional.of(task));
        when(tokens.issue(task, false)).thenReturn("tok");
        when(tokens.issue(task, true)).thenReturn("tok-w");
        RepoCatalogEntry cat = RepoCatalogEntry.create("앱", "https://github.com/a/b", "github", "a/b", "main", null, "admin");
        ReflectionTestUtils.setField(cat, "id", 1L);
        when(catalogs.findById(1L)).thenReturn(Optional.of(cat));
    }

    private void connection(long id, boolean allowWrite) {
        DbConnection c = DbConnection.create(DbConnectionScope.REPO, 1L, null, "개발DB", DbType.POSTGRESQL,
                "db.local", 5432, "app", "writer", "enc", "admin1");
        ReflectionTestUtils.setField(c, "id", id);
        c.setAllowWrite(allowWrite);
        when(connections.findById(id)).thenReturn(Optional.of(c));
    }

    private void bothUsable() {
        var r12 = new DbConnectionRef("db-12", "개발DB (PostgreSQL)", "POSTGRESQL", "h", 5432, "app", "u", "pw");
        var r13 = new DbConnectionRef("db-13", "조회DB (PostgreSQL)", "POSTGRESQL", "h", 5432, "app", "u", "pw");
        when(db.resolveForClaim(List.of(12L, 13L))).thenReturn(new DbConnectionService.ClaimDb(List.of(r12, r13), List.of()));
        connection(12L, true);
        connection(13L, false);
    }

    @Test
    void grant_lists_servers_without_password_and_issues_token() {
        var ref = new DbConnectionRef("db-12", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(12L, 13L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref),
                List.of("DB 연결 #13를 사용할 수 없습니다 — 삭제되었거나 비활성화됨")));

        DbMcpGrant g = svc.grantFor(5L);

        assertThat(g.token()).isEqualTo("tok");
        assertThat(g.servers()).containsExactly(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영 (MySQL)", "MYSQL"));
        assertThat(g.notices()).hasSize(1);
        assertThat(g.toString()).contains("token=****").doesNotContain("=tok").doesNotContain("pw");
        verify(history).save(any(TaskStatusHistory.class));
    }

    @Test
    void no_usable_connection_means_no_token() {
        when(db.resolveForClaim(any())).thenReturn(new DbConnectionService.ClaimDb(List.of(), List.of("꺼짐")));
        DbMcpGrant g = svc.grantFor(5L, true);
        assertThat(g.token()).isNull();
        assertThat(g.servers()).isEmpty();
        verify(tokens, never()).issue(any(), anyBoolean());
        // 재claim 때 이전 토큰(같은 단계·만료 전·최신)이 다시 유효해지지 않도록 이 작업의 토큰을 모두 무효화한다
        verify(tokens).revokeAll(5L);
    }

    @Test
    void usable_connection_does_not_revoke_before_issue() {
        var ref = new DbConnectionRef("db-12", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(any())).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        svc.grantFor(5L);
        verify(tokens).issue(task, false);
        verify(tokens, never()).revokeAll(any());
    }

    @Test
    void no_selection_or_other_stage_is_null() {
        task.setDbConnectionIds(new ArrayList<>());
        assertThat(svc.grantFor(5L, true)).isNull();
        task.setDbConnectionIds(new ArrayList<>(List.of(12L)));
        task.setStatus(TaskStatus.DEPLOYING);
        assertThat(svc.grantFor(5L, true)).isNull();
        verifyNoInteractions(db, tokens, connections);
    }

    @Test
    void grant는_연결별_writable과_토큰허가() {
        bothUsable();

        DbMcpGrant g = svc.grantFor(5L, true);

        assertThat(g.servers()).extracting(DbMcpGrant.Server::writable).containsExactly(true, false);
        assertThat(g.servers().get(0)).isEqualTo(
                new DbMcpGrant.Server("db-12", "/mcp/db/12", "개발DB (PostgreSQL)", "POSTGRESQL", true));
        assertThat(g.token()).isEqualTo("tok-w");
        verify(tokens).issue(task, true);
        verify(tokens, never()).issue(task, false);
    }

    @Test
    void 디자인_단계는_쓰기허용_연결도_전부_읽기() {
        bothUsable();
        task.setStatus(TaskStatus.DESIGNING);

        DbMcpGrant g = svc.grantFor(5L, true);

        assertThat(g.servers()).extracting(DbMcpGrant.Server::writable).containsExactly(false, false);
        verify(tokens).issue(task, false);
    }

    @Test
    void 쓰기를_모르는_워커는_판정없이_전부_읽기() {
        bothUsable();

        DbMcpGrant g = svc.grantFor(5L, false);

        assertThat(g.servers()).extracting(DbMcpGrant.Server::writable).containsExactly(false, false);
        verify(tokens).issue(task, false);
        verify(connections, never()).findById(anyLong());
        // 인자 하나 판은 쓰기를 모르는 워커와 같다
        assertThat(svc.grantFor(5L).servers()).extracting(DbMcpGrant.Server::writable).containsExactly(false, false);
    }
}
