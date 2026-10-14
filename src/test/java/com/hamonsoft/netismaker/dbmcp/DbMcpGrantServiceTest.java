package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
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
import static org.mockito.Mockito.*;

class DbMcpGrantServiceTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final DbMcpTokenService tokens = mock(DbMcpTokenService.class);
    private final TaskStatusHistoryRepository history = mock(TaskStatusHistoryRepository.class);
    private final DbMcpGrantService svc = new DbMcpGrantService(taskRepo, db, tokens, history);
    private Task task;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        task.setDbConnectionIds(new ArrayList<>(List.of(12L, 13L)));
        when(taskRepo.findById(5L)).thenReturn(Optional.of(task));
        when(tokens.issue(task)).thenReturn("tok");
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
        DbMcpGrant g = svc.grantFor(5L);
        assertThat(g.token()).isNull();
        assertThat(g.servers()).isEmpty();
        verify(tokens, never()).issue(any());
        // 재claim 때 이전 토큰(같은 단계·만료 전·최신)이 다시 유효해지지 않도록 이 작업의 토큰을 모두 무효화한다
        verify(tokens).revokeAll(5L);
    }

    @Test
    void usable_connection_does_not_revoke_before_issue() {
        var ref = new DbConnectionRef("db-12", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(any())).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        svc.grantFor(5L);
        verify(tokens).issue(task);
        verify(tokens, never()).revokeAll(any());
    }

    @Test
    void no_selection_or_other_stage_is_null() {
        task.setDbConnectionIds(new ArrayList<>());
        assertThat(svc.grantFor(5L)).isNull();
        task.setDbConnectionIds(new ArrayList<>(List.of(12L)));
        task.setStatus(TaskStatus.DEPLOYING);
        assertThat(svc.grantFor(5L)).isNull();
        verifyNoInteractions(db, tokens);
    }
}
