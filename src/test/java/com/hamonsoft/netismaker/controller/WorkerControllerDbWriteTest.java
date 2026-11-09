package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.WorkerCapabilities;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * next-task의 supports=dbWrite (스펙 2026-11-03 §6.1 조건 6). 쓰기를 아는 워커의 구현 claim에만 grant가 쓰기를 판정하고,
 * 큐는 거르지 않는다 — 옛 워커도 작업은 받고 쓰기 도구만 못 받는다.
 */
class WorkerControllerDbWriteTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final DbMcpGrantService grants = mock(DbMcpGrantService.class);
    private final DbWriteService writes = mock(DbWriteService.class);
    private final WorkerController controller = new WorkerController(workerService, grants, writes);

    private static WorkerTaskResponse implementation() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 42L);
        return WorkerTaskResponse.forImplementation(t, null, null);
    }

    @Test
    void supports에_dbWrite가_있으면_grantFor_true() {
        WorkerCapabilities all = new WorkerCapabilities(true, true, true);
        when(workerService.claimNextTask("w1", all, null)).thenReturn(Optional.of(implementation()));
        DbMcpGrant g = new DbMcpGrant("tok", List.of(new DbMcpGrant.Server("db-7", "/mcp/db/7", "개발DB (PostgreSQL)",
                "POSTGRESQL", true)), List.of());
        when(grants.grantFor(42L, true)).thenReturn(g);

        assertThat(controller.nextTask("w1", "refRepos,fixRounds,dbWrite", null).getBody().dbMcp()).isEqualTo(g);

        verify(grants).grantFor(42L, true);
        verify(grants, never()).grantFor(42L, false);
    }

    @Test
    void dbWrite가_없는_워커도_작업은_받고_쓰기만_없다() {
        // 조건 6은 큐 필터가 아니다 — 옛 워커(refRepos,fixRounds)의 능력은 dbWrite=false이고 그대로 claim된다
        when(workerService.claimNextTask("old", new WorkerCapabilities(true, true), null))
                .thenReturn(Optional.of(implementation()));
        when(grants.grantFor(42L, false)).thenReturn(new DbMcpGrant("tok", List.of(), List.of()));

        assertThat(controller.nextTask("old", "refRepos,fixRounds", null).getBody().id()).isEqualTo(42L);

        verify(workerService).claimNextTask("old", new WorkerCapabilities(true, true, false), null);
        verify(grants).grantFor(42L, false);
    }

    @Test
    void dbWrite_토큰은_정확히_일치해야_한다() {
        assertThat(WorkerController.SUPPORTS_DB_WRITE).isEqualTo("dbWrite");
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("a", " refRepos , dbWrite ", null);
        controller.nextTask("b", "dbwrite", null);
        controller.nextTask("c", "dbWriteX", null);

        verify(workerService).claimNextTask("a", new WorkerCapabilities(true, false, true), null);
        verify(workerService).claimNextTask("b", new WorkerCapabilities(false, false, false), null);
        verify(workerService).claimNextTask("c", new WorkerCapabilities(false, false, false), null);
    }

    @Test
    void 디자인_claim도_워커_능력을_grant에_넘긴다() {
        // 디자인 단계 쓰기 거부는 판정(조건 2)이 한다 — 컨트롤러는 능력만 넘긴다
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 43L);
        when(workerService.claimNextTask("w1", new WorkerCapabilities(true, true, true), null))
                .thenReturn(Optional.of(WorkerTaskResponse.forDesign(t, null, null, null, null)));

        controller.nextTask("w1", "refRepos,fixRounds,dbWrite", null);

        verify(grants).grantFor(43L, true);
    }

    @Test
    void grant_서버의_writable은_JSON으로_오가고_옛_JSON은_false() throws Exception {
        ObjectMapper lenient = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DbMcpGrant g = new DbMcpGrant("tok", List.of(
                new DbMcpGrant.Server("db-7", "/mcp/db/7", "개발DB (PostgreSQL)", "POSTGRESQL", true),
                new DbMcpGrant.Server("db-8", "/mcp/db/8", "조회DB (MySQL)", "MYSQL")), List.of());

        assertThat(lenient.readValue(lenient.writeValueAsString(g), DbMcpGrant.class)).isEqualTo(g);
        DbMcpGrant old = lenient.readValue("{\"token\":\"tok\",\"servers\":[{\"name\":\"db-7\",\"path\":\"/mcp/db/7\","
                + "\"label\":\"개발DB (PostgreSQL)\",\"dbType\":\"POSTGRESQL\"}],\"notices\":[]}", DbMcpGrant.class);
        assertThat(old.servers()).extracting(DbMcpGrant.Server::writable).containsExactly(false);
    }

    @Test
    void 워커는_작업의_DB_변경_기록을_받는다() {
        DbChangeView v = new DbChangeView(13L, 7L, "개발DB (PostgreSQL)", 1, null, "CREATE TABLE t (id int)", "EXECUTED",
                null, null, null, java.time.OffsetDateTime.parse("2026-11-04T00:00:00Z"), null, null);
        when(writes.list(42L)).thenReturn(List.of(v));

        assertThat(controller.dbChanges(42L)).containsExactly(v);
        verify(writes).list(42L);
    }
}
