package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbObject;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.TaskException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 승인 대기 SQL의 관리자 실행·실행 안 함·목록 (스펙 2026-11-03 §6.5, 계획 Task 12·Task 7 리뷰 반영 4-1).
 * 작업 42는 구현중이고 연결 7(개발DB, PostgreSQL, 쓰기 허용 REPO)에 승인 대기 행 13이 있다. 기록 저장소는 행 하나의 상태 전이를
 * 흉내 낸다(transition·되돌리기·완료 칸이 실제 저장소처럼 그 칸만 바꾼다). 소유 기록은 메모리 목록이다.
 */
class DbWriteServiceDeferredTest {

    private static final String DROP_COLUMN = "ALTER TABLE legacy DROP COLUMN old_col";

    private final DbWriteGate gate = mock(DbWriteGate.class);
    private final DbConnectionService connections = mock(DbConnectionService.class);
    private final DbWriteExecutor executor = mock(DbWriteExecutor.class);
    private final DbWriteSession session = mock(DbWriteSession.class);
    private final TaskDbObjectRepository objects = mock(TaskDbObjectRepository.class);
    private final TaskDbWriteLogRepository logs = mock(TaskDbWriteLogRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskStatusHistoryRepository history = mock(TaskStatusHistoryRepository.class);

    private final List<TaskDbObject> store = new ArrayList<>();

    private Task task;
    private DbConnection dev;
    private DbConnectionRef ref;
    private TaskDbWriteLog row;
    private DbWriteService service;

    @BeforeEach
    void setUp() throws SQLException {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 42L);
        task.setStatus(TaskStatus.PR_CREATED);
        task.setDbConnectionIds(new ArrayList<>(List.of(7L)));

        dev = DbConnection.create(DbConnectionScope.REPO, 1L, null, "개발DB", DbType.POSTGRESQL, "db.local", 5432,
                "app", "writer", "enc", "admin1");
        ReflectionTestUtils.setField(dev, "id", 7L);
        dev.setAllowWrite(true);
        ref = new DbConnectionRef("db-7", "개발DB (PostgreSQL)", "POSTGRESQL", "db.local", 5432, "app", "writer", "S3cret!");

        when(tasks.findActiveById(42L)).thenReturn(Optional.of(task));
        when(tasks.findOpenByRepo(any(), any())).thenReturn(List.of());
        when(connections.findForWrite(7L)).thenReturn(Optional.of(dev));
        when(connections.writeRefOf(dev)).thenReturn(Optional.of(ref));
        when(gate.connectionCheck(dev)).thenReturn(new DbWriteGate.Check(true, null));
        when(executor.open(ref)).thenReturn(session);
        when(session.defaultSchema()).thenReturn("public");

        deferred(DROP_COLUMN);
        wireLogs();

        when(objects.findByConnectionIdAndTaskId(anyLong(), anyLong())).thenAnswer(i -> store.stream()
                .filter(o -> o.getConnectionId().equals(i.getArgument(0)) && o.getTaskId().equals(i.getArgument(1)))
                .toList());
        when(objects.findByConnectionIdAndSchemaNameAndObjectNameAndObjectType(anyLong(), anyString(), anyString(),
                any(DbObjectType.class))).thenAnswer(i -> store.stream()
                .filter(o -> o.getConnectionId().equals(i.getArgument(0)) && o.getSchemaName().equals(i.getArgument(1))
                        && o.getObjectName().equals(i.getArgument(2)) && o.getObjectType() == i.getArgument(3))
                .findFirst());
        doAnswer(i -> store.remove(i.<TaskDbObject>getArgument(0))).when(objects).delete(any(TaskDbObject.class));

        service = new DbWriteService(gate, connections, executor, objects, logs, tasks,
                new DbWriteProperties(true, 1000, 10000), history);
    }

    /** 행 13을 승인 대기로 되돌려 놓는다(sql만 바꿈). */
    private void deferred(String sql) {
        row = TaskDbWriteLog.create(42L, 7L, "개발DB (PostgreSQL)", 2, 5L, sql, WriteClassification.DEFERRED);
        ReflectionTestUtils.setField(row, "id", 13L);
        row.setReason("남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요");
    }

    /**
     * 저장소 쿼리가 하는 일만 흉내 낸다 — 조건이 맞을 때 그 칸만 바꾸고 바뀐 행 수를 돌려준다.
     * 상태를 바꾸는 답이라 doAnswer().when() 형태로만 건다(when(mock.call()) 형태는 거는 동안 호출되어 상태가 바뀐다).
     */
    private void wireLogs() {
        doAnswer(i -> Optional.of(row)).when(logs).findById(13L);
        doAnswer(i -> {
            if (!row.getId().equals(i.getArgument(0)) || !row.getTaskId().equals(i.getArgument(1))
                    || row.getClassification() != i.getArgument(2)) {
                return 0;
            }
            row.setClassification(i.getArgument(3));
            row.setExecutedBy(i.getArgument(4));
            row.setExecutedAt(OffsetDateTime.now());
            return 1;
        }).when(logs).transition(anyLong(), anyLong(), any(WriteClassification.class), any(WriteClassification.class),
                anyString());
        doAnswer(i -> {
            if (row.getClassification() != WriteClassification.EXECUTED) return 0;
            row.setClassification(WriteClassification.DEFERRED);
            row.setExecutedBy(null);
            row.setExecutedAt(null);
            row.setError(i.getArgument(2));
            return 1;
        }).when(logs).revertToDeferred(anyLong(), anyLong(), anyString());
        doAnswer(i -> {
            if (row.getClassification() != WriteClassification.EXECUTED) return 0;
            row.setAffectedRows(i.getArgument(2));
            row.setError(null);
            return 1;
        }).when(logs).completeExecution(anyLong(), anyLong(), any());
    }

    private TaskDbObject own(DbObjectType type, String schema, String name, long taskId) {
        TaskDbObject o = TaskDbObject.of(7L, schema, name, type, taskId);
        ReflectionTestUtils.setField(o, "id", 500L + store.size());
        store.add(o);
        return o;
    }

    private static TaskException conflict(Throwable t) {
        assertThat(t).isInstanceOf(TaskException.class);
        TaskException e = (TaskException) t;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        return e;
    }

    /** 실행 쪽 부작용이 하나도 없었는지 — 상태를 바꾸지 않았고 DB에 쓰지 않았고 이력도 없다. */
    private void assertUntouched() throws SQLException {
        verify(logs, never()).transition(anyLong(), anyLong(), any(), any(), any());
        verify(logs, never()).revertToDeferred(anyLong(), anyLong(), any());
        verify(logs, never()).completeExecution(anyLong(), anyLong(), any());
        verify(session, never()).executeUpdate(anyString());
        verify(session, never()).commit();
        verify(history, never()).save(any());
        assertThat(row.getClassification()).isEqualTo(WriteClassification.DEFERRED);
        assertThat(row.getExecutedAt()).isNull();
        assertThat(row.getExecutedBy()).isNull();
    }

    // ───────────────────────── 실행 ─────────────────────────

    @Test
    void 승인대기만_실행() throws SQLException {
        for (WriteClassification other : List.of(WriteClassification.EXECUTED, WriteClassification.REJECTED,
                WriteClassification.FAILED, WriteClassification.DISMISSED)) {
            row.setClassification(other);

            assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                    .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("승인 대기 항목만 실행할 수 있습니다"));
        }
        verify(logs, never()).transition(anyLong(), anyLong(), any(), any(), any());
        verify(executor, never()).open(any());
        verify(session, never()).executeUpdate(anyString());

        // 없는 기록·다른 작업의 기록은 404로 숨긴다(IDOR)
        when(logs.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.executeDeferred(42L, 99L, "admin"))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        row.setClassification(WriteClassification.DEFERRED);
        ReflectionTestUtils.setField(row, "taskId", 77L);
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        // 없거나 지워진 작업도 404
        ReflectionTestUtils.setField(row, "taskId", 42L);
        when(tasks.findActiveById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(executor, never()).open(any());
    }

    /** Review Focus 2 — 두 관리자가 동시에 눌러 둘 다 승인 대기를 읽은 경우: 선점(transition)이 0이면 실행하지 않는다. */
    @Test
    void 두번_누르면_한번만_실행() throws SQLException {
        doReturn(0).when(logs).transition(13L, 42L, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "admin");

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("이미 처리된 항목입니다"));

        verify(session, never()).executeUpdate(anyString());
        verify(session, never()).commit();
        verify(logs, never()).revertToDeferred(anyLong(), anyLong(), any());
        verify(history, never()).save(any());
        verify(session).close();

        // 순서대로 누른 두 번째는 첫 번째가 바꾼 상태를 보고 막힌다
        reset(session);
        when(session.defaultSchema()).thenReturn("public");
        wireLogs();
        service.executeDeferred(42L, 13L, "admin");
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("승인 대기 항목만 실행할 수 있습니다"));
        verify(session, times(1)).executeUpdate(DROP_COLUMN);
    }

    @Test
    void 다시_분류해_거부면_관리자도_409() throws SQLException {
        deferred("DROP TABLE legacy");   // 남의 테이블 DROP TABLE은 거부 분류다

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).startsWith("거부 분류는 관리자도 실행할 수 없습니다"));
        assertUntouched();
        verify(session).close();

        // 분석할 수 없는 문장(여러 문장)도 같다 — 접속하기 전에 막는다
        deferred("ALTER TABLE a DROP COLUMN b; DROP TABLE c");
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).startsWith("거부 분류는 관리자도 실행할 수 없습니다"));
        assertUntouched();
        verify(executor, times(1)).open(any());
    }

    /**
     * 최종 리뷰 Important 1 — 대상 경계 밖(시스템 스키마, pg_catalog에 닿는 비한정 이름)은 거부 분류다. 남의 테이블 컬럼 삭제라 승인
     * 대기로 기록됐던 문장이라도 관리자 실행으로 우회할 수 없다(반목표 "거부 분류를 관리자 실행으로 우회").
     */
    @Test
    void 다시_분류해_대상이_경계_밖이면_관리자도_409() throws SQLException {
        deferred("ALTER TABLE pg_catalog.pg_class DROP COLUMN relname");

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo(
                        "거부 분류는 관리자도 실행할 수 없습니다 — 시스템 스키마의 객체는 쓸 수 없습니다: pg_catalog.pg_class"));
        assertUntouched();

        deferred("ALTER TABLE pg_authid DROP COLUMN rolsuper");
        when(session.exists(new DbObjectName("pg_catalog", "pg_authid"))).thenReturn(true);
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).startsWith(
                        "거부 분류는 관리자도 실행할 수 없습니다 — 시스템 카탈로그와 같은 이름입니다: pg_catalog.pg_authid"));
        assertUntouched();
        verify(session, times(2)).close();
    }

    @Test
    void 쓰기허용_꺼짐_스위치_off_USER_비활성은_409() throws SQLException {
        DbConnectionRepository connectionRepo = mock(DbConnectionRepository.class);
        RepoCatalogRepository catalogs = mock(RepoCatalogRepository.class);
        DbConnection notAllowed = connection(7L, DbConnectionScope.REPO, false, true);
        DbConnection user = connection(7L, DbConnectionScope.USER, true, true);
        DbConnection disabled = connection(7L, DbConnectionScope.REPO, true, false);

        record Case(String name, boolean writeSwitch, DbConnection connection, String reason) {}
        List<Case> cases = List.of(
                new Case("스위치 꺼짐", false, dev, "개발 DB 쓰기 스위치가 꺼져 있습니다"),
                new Case("쓰기 허용 꺼짐", true, notAllowed, "쓰기 허용 연결이 아닙니다"),
                new Case("USER 연결", true, user, "쓰기 허용 연결이 아닙니다"),
                new Case("비활성 연결", true, disabled, "쓰기 허용 연결이 아닙니다"),
                new Case("연결 행 없음", true, null, "쓰기 허용 연결이 아닙니다"));
        for (Case c : cases) {
            DbWriteProperties props = new DbWriteProperties(c.writeSwitch(), 1000, 10000);
            // 판정은 실물이다 — 조건 1·4를 DbWriteGate.connectionCheck 한 곳에서 본다(F6)
            DbWriteService real = new DbWriteService(new DbWriteGate(props, connectionRepo, catalogs), connections,
                    executor, objects, logs, tasks, props, history);
            when(connections.findForWrite(7L)).thenReturn(Optional.ofNullable(c.connection()));

            assertThatThrownBy(() -> real.executeDeferred(42L, 13L, "admin")).as(c.name())
                    .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo(c.reason()));
            assertUntouched();
        }
        verify(executor, never()).open(any());

        // 연결을 지워 connection_id가 NULL인 기록도 같다
        ReflectionTestUtils.setField(row, "connectionId", null);
        DbWriteProperties on = new DbWriteProperties(true, 1000, 10000);
        DbWriteService real = new DbWriteService(new DbWriteGate(on, connectionRepo, catalogs), connections, executor,
                objects, logs, tasks, on, history);
        assertThatThrownBy(() -> real.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("쓰기 허용 연결이 아닙니다"));
        verify(executor, never()).open(any());
        assertUntouched();
    }

    private static DbConnection connection(long id, DbConnectionScope scope, boolean allowWrite, boolean enabled) {
        DbConnection c = DbConnection.create(scope, 1L, scope == DbConnectionScope.USER ? "user1" : null, "개발DB",
                DbType.POSTGRESQL, "db.local", 5432, "app", "writer", "enc", "admin1");
        ReflectionTestUtils.setField(c, "id", id);
        c.setAllowWrite(allowWrite);
        c.setEnabled(enabled);
        return c;
    }

    @Test
    void 연결정보를_풀_수_없거나_접속이_안_되면_409이고_상태_그대로() throws SQLException {
        when(connections.writeRefOf(dev)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("연결 정보를 풀 수 없습니다"));
        verify(executor, never()).open(any());
        assertUntouched();

        when(connections.writeRefOf(dev)).thenReturn(Optional.of(ref));
        when(executor.open(ref)).thenThrow(new SQLException("connection refused for S3cret! user writer"));
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage())
                        .contains("connection refused for **** user writer").doesNotContain("S3cret!"));
        assertUntouched();
    }

    @Test
    void 기본스키마를_모르면_409() throws SQLException {
        for (String unknown : new String[]{null, ""}) {
            when(session.defaultSchema()).thenReturn(unknown);

            assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                    .satisfies(t -> assertThat(conflict(t).getMessage())
                            .isEqualTo("기본 스키마를 알 수 없습니다 — 연결의 기본 스키마를 정하세요"));
            assertUntouched();
        }
        verify(session, times(2)).close();
    }

    @Test
    void 다시_분류해_소유_EXECUTE인데_객체가_없으면_409와_기록정리() throws SQLException {
        String sql = "ALTER TABLE t_new DROP COLUMN c";
        deferred(sql);
        own(DbObjectType.TABLE, "public", "t_new", 42L);   // 지금은 이 작업의 테이블이라 EXECUTE로 다시 분류된다
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenReturn(false);

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage())
                        .isEqualTo("소유 기록의 객체가 DB에 없습니다: public.t_new — 기록을 정리했습니다"));

        assertThat(store).isEmpty();
        assertUntouched();
        verify(session).close();

        // 확인이 예외여도 같다
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenThrow(new SQLException("timeout"));
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).startsWith("소유 기록의 객체가 DB에 없습니다: public.t_new"));
        assertThat(store).isEmpty();
        assertUntouched();
    }

    @Test
    void 소유_덕에_EXECUTE이고_객체가_있으면_실행한다() throws SQLException {
        deferred("ALTER TABLE t_new DROP COLUMN c");
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenReturn(true);

        DbChangeView v = service.executeDeferred(42L, 13L, "admin");

        verify(session).executeUpdate("ALTER TABLE t_new DROP COLUMN c");
        assertThat(v.classification()).isEqualTo("EXECUTED");
        assertThat(store).hasSize(1);   // 소유 기록은 그대로
    }

    @Test
    void 성공하면_EXECUTED_실행자_이력() throws SQLException {
        DbChangeView v = service.executeDeferred(42L, 13L, "admin");

        // 선점 → 실행 → 커밋 → 완료 칸, 세션은 마지막에 닫힌다
        InOrder o = inOrder(logs, session, history);
        o.verify(logs).transition(13L, 42L, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "admin");
        o.verify(session).executeUpdate(DROP_COLUMN);
        o.verify(session).commit();
        o.verify(session).close();
        o.verify(logs).completeExecution(13L, 42L, null);   // DDL은 영향 행 없음
        ArgumentCaptor<TaskStatusHistory> h = ArgumentCaptor.forClass(TaskStatusHistory.class);
        o.verify(history).save(h.capture());
        assertThat(h.getValue().getTaskId()).isEqualTo(42L);
        assertThat(h.getValue().getFromStatus()).isEqualTo(h.getValue().getToStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(h.getValue().getActorType()).isEqualTo("user");
        assertThat(h.getValue().getActorId()).isEqualTo("admin");
        assertThat(h.getValue().getReason()).isEqualTo("DB 변경 실행: #13 (개발DB (PostgreSQL))");

        assertThat(v.id()).isEqualTo(13L);
        assertThat(v.classification()).isEqualTo("EXECUTED");
        assertThat(v.executedBy()).isEqualTo("admin");
        assertThat(v.executedAt()).isNotNull();
        assertThat(v.error()).isNull();
        assertThat(v.sqlText()).isEqualTo(DROP_COLUMN);
        verify(logs, never()).save(any());   // 미리 읽은 행을 다시 저장하지 않는다 — 칸 단위 UPDATE만
    }

    @Test
    void 영향_행_상한은_걸지_않고_DML은_영향_행을_남긴다() throws SQLException {
        deferred("DELETE FROM legacy WHERE a > 0");   // 지금 분류로는 상한 1000이 걸리는 문장
        when(session.executeUpdate("DELETE FROM legacy WHERE a > 0")).thenReturn(5000L);

        DbChangeView v = service.executeDeferred(42L, 13L, "admin");

        verify(session).commit();
        verify(logs).completeExecution(13L, 42L, 5000L);
        assertThat(v.classification()).isEqualTo("EXECUTED");
        assertThat(v.affectedRows()).isEqualTo(5000L);
    }

    @Test
    void 실행실패는_승인대기로_되돌리고_오류저장_409() throws SQLException {
        when(session.executeUpdate(anyString())).thenThrow(new SQLException("permission denied for S3cret! user writer"));

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage())
                        .contains("permission denied for **** user writer").doesNotContain("S3cret!"));

        InOrder o = inOrder(logs, session);
        o.verify(logs).transition(13L, 42L, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "admin");
        o.verify(session).executeUpdate(DROP_COLUMN);
        o.verify(logs).revertToDeferred(13L, 42L, "permission denied for **** user writer");
        verify(session, never()).commit();
        verify(session).close();
        verify(logs, never()).completeExecution(anyLong(), anyLong(), any());
        verify(history, never()).save(any());
        // 기록은 승인 대기 그대로 — 처리한 시각·사람이 남지 않고 오류만 남는다
        assertThat(row.getClassification()).isEqualTo(WriteClassification.DEFERRED);
        assertThat(row.getExecutedAt()).isNull();
        assertThat(row.getExecutedBy()).isNull();
        assertThat(row.getError()).isEqualTo("permission denied for **** user writer").doesNotContain("S3cret!");

        // 커밋이 실패해도 같다. 되돌린 뒤 다시 누를 수 있다
        reset(session);
        when(session.defaultSchema()).thenReturn("public");
        doThrow(new SQLException("commit failed")).when(session).commit();
        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).contains("commit failed"));
        verify(logs).revertToDeferred(13L, 42L, "commit failed");
        assertThat(row.getClassification()).isEqualTo(WriteClassification.DEFERRED);
        verify(session).close();
    }

    @Test
    void 되돌리기가_실패해도_원래_오류로_409() throws SQLException {
        when(session.executeUpdate(anyString())).thenThrow(new SQLException("boom"));
        doThrow(new IllegalStateException("app db down")).when(logs).revertToDeferred(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.executeDeferred(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).contains("boom"));
        verify(session).close();
    }

    @Test
    void 커밋_뒤_완료칸_이력_저장이_실패해도_실행_결과는_그대로() throws SQLException {
        doThrow(new IllegalStateException("app db down")).when(logs).completeExecution(anyLong(), anyLong(), any());
        doThrow(new IllegalStateException("app db down")).when(history).save(any());

        DbChangeView v = service.executeDeferred(42L, 13L, "admin");

        verify(session).commit();
        verify(logs, never()).revertToDeferred(anyLong(), anyLong(), any());
        assertThat(v.classification()).isEqualTo("EXECUTED");
    }

    // ───────────────────────── 실행 안 함 ─────────────────────────

    @Test
    void 실행안함은_DISMISSED와_이력() throws SQLException {
        DbChangeView v = service.dismiss(42L, 13L, "admin");

        verify(logs).transition(13L, 42L, WriteClassification.DEFERRED, WriteClassification.DISMISSED, "admin");
        ArgumentCaptor<TaskStatusHistory> h = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(history).save(h.capture());
        assertThat(h.getValue().getReason()).isEqualTo("DB 변경 실행 안 함: #13");
        assertThat(h.getValue().getActorType()).isEqualTo("user");
        assertThat(h.getValue().getActorId()).isEqualTo("admin");
        assertThat(h.getValue().getFromStatus()).isEqualTo(h.getValue().getToStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(v.classification()).isEqualTo("DISMISSED");
        assertThat(v.executedBy()).isEqualTo("admin");
        assertThat(v.executedAt()).isNotNull();
        // DB에는 닿지 않는다 — 스위치·연결 상태와 무관하게 정리할 수 있다
        verify(executor, never()).open(any());
        verify(connections, never()).findForWrite(anyLong());
        verify(gate, never()).connectionCheck(any());
    }

    @Test
    void 실행안함도_승인대기만_한번만() {
        row.setClassification(WriteClassification.EXECUTED);
        assertThatThrownBy(() -> service.dismiss(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("승인 대기 항목만 실행 안 함으로 처리할 수 있습니다"));

        deferred(DROP_COLUMN);
        doReturn(0).when(logs).transition(13L, 42L, WriteClassification.DEFERRED, WriteClassification.DISMISSED, "admin");
        assertThatThrownBy(() -> service.dismiss(42L, 13L, "admin"))
                .satisfies(t -> assertThat(conflict(t).getMessage()).isEqualTo("이미 처리된 항목입니다"));
        verify(history, never()).save(any());

        when(logs.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dismiss(42L, 99L, "admin"))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ───────────────────────── 목록 ─────────────────────────

    @Test
    void 목록은_시간순이고_뷰로_옮긴다() {
        TaskDbWriteLog executed = TaskDbWriteLog.create(42L, 7L, "개발DB (PostgreSQL)", 1, null, "CREATE TABLE t (id int)",
                WriteClassification.EXECUTED);
        ReflectionTestUtils.setField(executed, "id", 12L);
        executed.setExecutedAt(OffsetDateTime.parse("2026-11-04T01:02:03Z"));
        executed.setAffectedRows(0L);
        when(logs.findByTaskIdOrderByCreatedAtAscIdAsc(42L)).thenReturn(List.of(executed, row));

        List<DbChangeView> views = service.list(42L);

        assertThat(views).extracting(DbChangeView::id).containsExactly(12L, 13L);
        DbChangeView first = views.get(0);
        assertThat(first.connectionId()).isEqualTo(7L);
        assertThat(first.connectionName()).isEqualTo("개발DB (PostgreSQL)");
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.fixRequestId()).isNull();
        assertThat(first.sqlText()).isEqualTo("CREATE TABLE t (id int)");
        assertThat(first.classification()).isEqualTo("EXECUTED");
        assertThat(first.executedAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T01:02:03Z"));
        assertThat(first.createdAt()).isNotNull();
        DbChangeView second = views.get(1);
        assertThat(second.classification()).isEqualTo("DEFERRED");
        assertThat(second.fixRequestId()).isEqualTo(5L);
        assertThat(second.reason()).startsWith("남의 테이블 컬럼 삭제는 승인 대기");
        // SQL 전문과 오류는 toString에 싣지 않는다
        assertThat(second.toString()).contains("id=13").doesNotContain("old_col").doesNotContain("승인 대기로 기록됨");
        // 분류가 NULL인 행도 뷰로 옮겨진다
        row.setClassification(null);
        assertThat(DbChangeView.of(row).classification()).isNull();
    }
}
