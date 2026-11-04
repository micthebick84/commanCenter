package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpTokenService;
import com.hamonsoft.netismaker.dbmcp.DbMcpTools;
import com.hamonsoft.netismaker.dbmcp.DbWriteExecutor;
import com.hamonsoft.netismaker.dbmcp.DbWriteGate;
import com.hamonsoft.netismaker.dbmcp.DbWriteProperties;
import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.dbmcp.DbWriteSession;
import com.hamonsoft.netismaker.dbmcp.QueryResult;
import com.hamonsoft.netismaker.dbmcp.ReadOnlyQueryExecutor;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * DB MCP execute 도구 (스펙 2026-11-03 §6.1) — tools/list와 tools/call execute는 같은 판정(토큰 쓰기 허가 && 조건 1~5)을 쓴다.
 * 판정·쓰기 서비스는 실물이고 저장소·접속만 흉내 낸다. 작업 42는 구현중이고 연결 12(REPO, 쓰기 허용)를 골랐다.
 * 토큰 "write"는 쓰기 허가로, "read"는 허가 없이 발급된 것이다.
 */
class DbMcpControllerExecuteTest {

    private final DbMcpTokenService tokens = mock(DbMcpTokenService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final DbConnectionRepository connectionRepo = mock(DbConnectionRepository.class);
    private final RepoCatalogRepository catalogs = mock(RepoCatalogRepository.class);
    private final DbWriteExecutor executor = mock(DbWriteExecutor.class);
    private final DbWriteSession session = mock(DbWriteSession.class);
    private final TaskDbObjectRepository objects = mock(TaskDbObjectRepository.class);
    private final TaskDbWriteLogRepository logs = mock(TaskDbWriteLogRepository.class);
    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final ReadOnlyQueryExecutor readOnly = (ref, sql, params, maxRows) -> new QueryResult(List.of(), List.of(), false);
    private final DbConnectionRef ref =
            new DbConnectionRef("db-12", "개발DB (PostgreSQL)", "POSTGRESQL", "h", 5432, "app", "u", "pw");
    private DbConnection row;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        t.setDbConnectionIds(new ArrayList<>(List.of(12L)));
        when(tokens.authenticateWithGrant("write")).thenReturn(Optional.of(new DbMcpTokenService.TokenAuth(t, true)));
        when(tokens.authenticateWithGrant("read")).thenReturn(Optional.of(new DbMcpTokenService.TokenAuth(t, false)));

        row = DbConnection.create(DbConnectionScope.REPO, 1L, null, "개발DB", DbType.POSTGRESQL, "h", 5432, "app", "u",
                "enc", "admin1");
        ReflectionTestUtils.setField(row, "id", 12L);
        row.setAllowWrite(true);
        when(connectionRepo.findById(12L)).thenReturn(Optional.of(row));
        when(db.findForWrite(12L)).thenReturn(Optional.of(row));
        when(db.writeRefOf(row)).thenReturn(Optional.of(ref));
        when(db.resolveForClaim(List.of(12L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        when(executor.open(ref)).thenReturn(session);
        when(session.defaultSchema()).thenReturn("public");
        when(taskRepo.findOpenByRepo(any(), any())).thenReturn(List.of());
        when(logs.save(any(TaskDbWriteLog.class))).thenAnswer(i -> {
            TaskDbWriteLog l = i.getArgument(0);
            if (l.getId() == null) ReflectionTestUtils.setField(l, "id", 77L);
            return l;
        });

        DbWriteProperties props = new DbWriteProperties(true, 1000, 10000);
        DbWriteGate gate = new DbWriteGate(props, connectionRepo, catalogs);
        DbWriteService writes = new DbWriteService(gate, db, executor, objects, logs, taskRepo, props,
                mock(TaskStatusHistoryRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(new DbMcpController(tokens, db, new DbMcpTools(readOnly), gate, writes)).build();
    }

    private ResultActions rpc(String token, String body) throws Exception {
        return mvc.perform(post("/mcp/db/12").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions list(String token) throws Exception {
        return rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    }

    private ResultActions execute(String token, String sql) throws Exception {
        return rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"execute\","
                + "\"arguments\":{\"sql\":\"" + sql + "\"}}}");
    }

    @Test
    void 쓰기가능일때만_execute가_보이고_직접호출은_isError와_REJECTED_기록() throws Exception {
        // 쓰기 허가 토큰 + 조건 충족 → execute가 보인다
        list("write").andExpect(jsonPath("$.result.tools[*].name", hasItem("execute")));

        // 쓰기 허가 없이 발급된 토큰(옛 워커) → 목록에 없고, 직접 불러도 서버가 거부하고 기록한다
        list("read").andExpect(jsonPath("$.result.tools[*].name", not(hasItem("execute"))))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("query")));
        execute("read", "UPDATE legacy SET a = 1 WHERE id = 1")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text", containsString("분류: REJECTED")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("이 워커는 DB 쓰기를 지원하지 않습니다")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("기록: #77")));

        // 쓰기 허용을 끈 연결 → 허가 토큰이어도 목록에 없고 직접 호출은 거부
        row.setAllowWrite(false);
        list("write").andExpect(jsonPath("$.result.tools[*].name", not(hasItem("execute"))));
        execute("write", "UPDATE legacy SET a = 1 WHERE id = 1")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text", containsString("쓰기 허용 연결이 아닙니다")));

        verify(executor, never()).open(any());
        ArgumentCaptor<TaskDbWriteLog> saved = ArgumentCaptor.forClass(TaskDbWriteLog.class);
        verify(logs, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(l -> {
            assertThat(l.getClassification()).isEqualTo(WriteClassification.REJECTED);
            assertThat(l.getTaskId()).isEqualTo(42L);
        });
    }

    @Test
    void 쓰기가능한_연결의_execute는_실행결과를_돌려준다() throws Exception {
        when(session.executeUpdate("UPDATE legacy SET a = 1 WHERE id = 1")).thenReturn(1L);

        execute("write", "UPDATE legacy SET a = 1 WHERE id = 1")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("분류: EXECUTED")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("영향 행: 1")));
        verify(session).commit();
    }

    @Test
    void 승인대기는_도구오류가_아니다() throws Exception {
        execute("write", "ALTER TABLE legacy DROP COLUMN a")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("분류: DEFERRED")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("승인 대기")));
        verify(session, never()).executeUpdate(any());
    }
}
