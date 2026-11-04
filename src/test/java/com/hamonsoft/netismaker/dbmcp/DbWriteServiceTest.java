package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbObject;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 구현 단계 쓰기 실행 흐름 (스펙 2026-11-03 §6.2~§6.5, 계획 Task 11·Task 7 리뷰 반영 ①~④).
 * 작업 42는 구현중(시도 2, 수정 회차 5)이고 연결 7(개발DB, PostgreSQL)을 골랐다. 판정(gate)은 기본 허용이다.
 * 소유 기록 저장소는 메모리 목록으로 흉내 내 save·조회·삭제가 다음 호출에 이어진다.
 */
class DbWriteServiceTest {

    private final DbWriteGate gate = mock(DbWriteGate.class);
    private final DbConnectionService connections = mock(DbConnectionService.class);
    private final DbWriteExecutor executor = mock(DbWriteExecutor.class);
    private final DbWriteSession session = mock(DbWriteSession.class);
    private final TaskDbObjectRepository objects = mock(TaskDbObjectRepository.class);
    private final TaskDbWriteLogRepository logs = mock(TaskDbWriteLogRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);

    /** 소유 기록(task_db_object) 흉내. */
    private final List<TaskDbObject> store = new ArrayList<>();
    /** logs.save가 불린 시점의 칸 값 — 같은 객체를 두 번 저장하므로 호출 시점에 복사해 둔다. */
    private final List<Saved> saved = new ArrayList<>();

    private Task task;
    private DbConnection dev;
    private DbConnectionRef ref;
    private DbWriteService service;

    record Saved(Long id, Long connectionId, String connectionName, int attempt, Long fixRequestId, String sql,
                 WriteClassification classification, String reason, Long affectedRows, String error,
                 OffsetDateTime executedAt) {
        static Saved of(TaskDbWriteLog l) {
            return new Saved(l.getId(), l.getConnectionId(), l.getConnectionName(), l.getAttempt(), l.getFixRequestId(),
                    l.getSqlText(), l.getClassification(), l.getReason(), l.getAffectedRows(), l.getError(),
                    l.getExecutedAt());
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 42L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        task.setDbConnectionIds(new ArrayList<>(List.of(7L)));
        task.setImplementationAttempt(2);
        task.setActiveFixRequestId(5L);

        dev = DbConnection.create(DbConnectionScope.REPO, 1L, null, "개발DB", DbType.POSTGRESQL, "db.local", 5432,
                "app", "writer", "enc", "admin1");
        ReflectionTestUtils.setField(dev, "id", 7L);
        dev.setAllowWrite(true);
        ref = new DbConnectionRef("db-7", "개발DB (PostgreSQL)", "POSTGRESQL", "db.local", 5432, "app", "writer", "S3cret!");

        when(connections.findForWrite(7L)).thenReturn(Optional.of(dev));
        when(connections.writeRefOf(dev)).thenReturn(Optional.of(ref));
        when(gate.check(any(Task.class), anyBoolean(), any(DbConnection.class))).thenReturn(new DbWriteGate.Check(true, null));
        when(executor.open(ref)).thenReturn(session);
        when(session.defaultSchema()).thenReturn("public");
        when(tasks.findOpenByRepo(any(), any())).thenReturn(List.of());

        when(logs.save(any(TaskDbWriteLog.class))).thenAnswer(i -> {
            TaskDbWriteLog l = i.getArgument(0);
            if (l.getId() == null) ReflectionTestUtils.setField(l, "id", 100L + saved.size());
            saved.add(Saved.of(l));
            return l;
        });
        when(objects.save(any(TaskDbObject.class))).thenAnswer(i -> {
            TaskDbObject o = i.getArgument(0);
            if (o.getId() == null) {
                ReflectionTestUtils.setField(o, "id", 500L + store.size());
                store.add(o);
            }
            return o;
        });
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
                new DbWriteProperties(true, 1000, 10000));
    }

    private TaskDbObject own(DbObjectType type, String schema, String name, long taskId) {
        TaskDbObject o = TaskDbObject.of(7L, schema, name, type, taskId);
        ReflectionTestUtils.setField(o, "id", 500L + store.size());
        store.add(o);
        return o;
    }

    private DbWriteService.ExecuteResult run(String sql) {
        return service.execute(task, true, 7L, sql);
    }

    private Saved last() {
        return saved.get(saved.size() - 1);
    }

    @Test
    void 바로실행은_기록을_먼저_쓰고_실행뒤_완료() throws SQLException {
        when(session.executeUpdate("UPDATE legacy SET a = 1 WHERE id < 4")).thenReturn(3L);

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id < 4;");

        InOrder o = inOrder(logs, session);
        o.verify(logs).save(any(TaskDbWriteLog.class));
        o.verify(session).executeUpdate("UPDATE legacy SET a = 1 WHERE id < 4");
        o.verify(session).commit();
        o.verify(logs).save(any(TaskDbWriteLog.class));
        o.verify(session).close();
        assertThat(saved).hasSize(2);
        Saved before = saved.get(0);
        assertThat(before.classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(before.executedAt()).isNull();
        assertThat(before.affectedRows()).isNull();
        assertThat(before.attempt()).isEqualTo(2);
        assertThat(before.fixRequestId()).isEqualTo(5L);
        assertThat(before.sql()).isEqualTo("UPDATE legacy SET a = 1 WHERE id < 4;");
        Saved after = saved.get(1);
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(after.executedAt()).isNotNull();
        assertThat(after.affectedRows()).isEqualTo(3L);
        assertThat(r).isEqualTo(new DbWriteService.ExecuteResult(WriteClassification.EXECUTED, null, 3L, 100L, null));
    }

    @Test
    void 기록_사전저장이_실패하면_실행하지_않는다() throws SQLException {
        when(logs.save(any(TaskDbWriteLog.class))).thenThrow(new IllegalStateException("app db down"));

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        verify(session, never()).executeUpdate(anyString());
        verify(session, never()).commit();
        verify(session).close();
        assertThat(r.classification()).isEqualTo(WriteClassification.FAILED);
        assertThat(r.error()).isEqualTo("기록 저장 실패 — 실행하지 않았습니다");
        assertThat(r.logId()).isNull();
    }

    @Test
    void 남의테이블_상한초과는_롤백후_거부() throws SQLException {
        when(session.executeUpdate("DELETE FROM legacy WHERE a > 0")).thenReturn(1001L);

        DbWriteService.ExecuteResult r = run("DELETE FROM legacy WHERE a > 0");

        verify(session, never()).commit();
        verify(session).close();
        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).isEqualTo("영향 행 상한 초과: 1001 > 1000");
        assertThat(last().classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(last().reason()).isEqualTo("영향 행 상한 초과: 1001 > 1000");
        assertThat(last().id()).isEqualTo(saved.get(0).id());
        assertThat(last().executedAt()).isNull();

        // INSERT(INSERT…SELECT 포함)는 남의 테이블에 10000행
        saved.clear();
        when(session.executeUpdate("INSERT INTO legacy SELECT * FROM src")).thenReturn(10_001L);
        assertThat(run("INSERT INTO legacy SELECT * FROM src").reason()).isEqualTo("영향 행 상한 초과: 10001 > 10000");
        verify(session, never()).commit();
    }

    @Test
    void SQL오류는_FAILED_비밀번호없음() throws SQLException {
        when(session.executeUpdate(anyString())).thenThrow(new SQLException("permission denied for S3cret! user writer"));

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        verify(session, never()).commit();
        assertThat(r.classification()).isEqualTo(WriteClassification.FAILED);
        assertThat(r.error()).isEqualTo("permission denied for **** user writer");
        assertThat(r.logId()).isEqualTo(saved.get(0).id());
        assertThat(last().classification()).isEqualTo(WriteClassification.FAILED);
        assertThat(last().error()).doesNotContain("S3cret!");
        assertThat(last().executedAt()).isNull();
    }

    @Test
    void 새_테이블은_소유기록() throws SQLException {
        DbWriteService.ExecuteResult r = run("CREATE TABLE t_new (id int)");

        verify(session).exists(new DbObjectName("public", "t_new"));
        verify(session).executeUpdate("CREATE TABLE t_new (id int)");
        assertThat(r.classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(r.affectedRows()).isNull();
        assertThat(store).singleElement().satisfies(o -> {
            assertThat(o.getConnectionId()).isEqualTo(7L);
            assertThat(o.getObjectType()).isEqualTo(DbObjectType.TABLE);
            assertThat(o.getSchemaName()).isEqualTo("public");
            assertThat(o.getObjectName()).isEqualTo("t_new");
            assertThat(o.getTaskId()).isEqualTo(42L);
        });
    }

    @Test
    void 이미_있으면_실행하지_않는다() throws SQLException {
        when(session.exists(new DbObjectName("public", "legacy"))).thenReturn(true);

        DbWriteService.ExecuteResult r = run("CREATE TABLE IF NOT EXISTS legacy (id int)");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).startsWith("이미 있음: public.legacy");
        assertThat(saved).singleElement().satisfies(s -> assertThat(s.classification()).isEqualTo(WriteClassification.REJECTED));
        verify(session, never()).executeUpdate(anyString());
        verify(objects, never()).save(any());

        // 확인이 예외면 애매하다 — 거부(fail closed)
        when(session.exists(new DbObjectName("public", "t_x"))).thenThrow(new SQLException("timeout S3cret!"));
        DbWriteService.ExecuteResult failed = run("CREATE TABLE t_x (id int)");
        assertThat(failed.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(failed.reason()).startsWith("존재 확인 실패").doesNotContain("S3cret!");
        verify(session, never()).executeUpdate(anyString());
        verify(objects, never()).save(any());
    }

    /**
     * PostgreSQL은 search_path보다 pg_catalog를 먼저 찾는다 — public.pg_class를 만들어 소유하면 비한정 DELETE FROM pg_class가
     * 소유 확인(public.pg_class 있음)을 통과하고 시스템 카탈로그에 닿는다. 그 이름은 만들지 않는다(스키마를 적어도).
     */
    @Test
    void PostgreSQL_시스템_카탈로그와_같은_이름은_만들지_않는다() throws SQLException {
        when(session.exists(new DbObjectName("pg_catalog", "pg_class"))).thenReturn(true);

        for (String sql : List.of("CREATE TABLE pg_class (id int)", "CREATE TABLE public.pg_class (id int)")) {
            DbWriteService.ExecuteResult r = run(sql);
            assertThat(r.classification()).as(sql).isEqualTo(WriteClassification.REJECTED);
            assertThat(r.reason()).as(sql).startsWith("이미 있음: pg_catalog.pg_class");
        }
        verify(session, never()).executeUpdate(anyString());
        assertThat(store).isEmpty();
    }

    @Test
    void 소유_OR_REPLACE_VIEW는_존재확인없이_실행() throws SQLException {
        own(DbObjectType.VIEW, "public", "v_new", 42L);

        DbWriteService.ExecuteResult r = run("CREATE OR REPLACE VIEW v_new AS SELECT 1");

        assertThat(r.classification()).isEqualTo(WriteClassification.EXECUTED);
        verify(session, never()).exists(any());
        verify(session, never()).exists(any(), any());
        verify(session).executeUpdate("CREATE OR REPLACE VIEW v_new AS SELECT 1");
        verify(objects, never()).save(any());
        assertThat(store).hasSize(1);
    }

    @Test
    void 판정과_접속은_한번_읽은_연결행으로() throws SQLException {
        run("UPDATE legacy SET a = 1 WHERE id = 1");

        verify(connections, times(1)).findForWrite(7L);
        verify(connections, never()).resolveForClaim(any());
        ArgumentCaptor<DbConnection> judged = ArgumentCaptor.forClass(DbConnection.class);
        verify(gate).check(eq(task), eq(true), judged.capture());
        ArgumentCaptor<DbConnection> opened = ArgumentCaptor.forClass(DbConnection.class);
        verify(connections).writeRefOf(opened.capture());
        assertThat(judged.getValue()).isSameAs(dev);
        assertThat(opened.getValue()).isSameAs(dev);
        verify(executor).open(ref);
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.connectionName()).isEqualTo("개발DB (PostgreSQL)");
            assertThat(s.connectionId()).isEqualTo(7L);
        });
    }

    @Test
    void 소유_DROP은_기록삭제_소유_RENAME은_이름변경() throws SQLException {
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenReturn(true);

        assertThat(run("DROP TABLE t_new").classification()).isEqualTo(WriteClassification.EXECUTED);
        verify(session).executeUpdate("DROP TABLE t_new");
        assertThat(store).isEmpty();

        own(DbObjectType.TABLE, "public", "t_a", 42L);
        own(DbObjectType.TABLE, "public", "t_b", 7L);   // 이름 변경이 성공했으니 t_b 자리의 기록은 낡은 것이다
        when(session.exists(new DbObjectName("public", "t_a"), DbObjectType.TABLE)).thenReturn(true);

        assertThat(run("ALTER TABLE t_a RENAME TO t_b").classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(store).singleElement().satisfies(o -> {
            assertThat(o.getSchemaName()).isEqualTo("public");
            assertThat(o.getObjectName()).isEqualTo("t_b");
            assertThat(o.getTaskId()).isEqualTo(42L);
        });
    }

    @Test
    void 스키마를_적은_소유_테이블_이름변경은_원래_스키마에_남는다() throws SQLException {
        own(DbObjectType.TABLE, "work", "t_a", 42L);
        when(session.exists(new DbObjectName("work", "t_a"), DbObjectType.TABLE)).thenReturn(true);

        // PostgreSQL ALTER TABLE … RENAME TO의 새 이름은 원래 스키마에 생긴다(기본 스키마 public이 아니다)
        assertThat(run("ALTER TABLE work.t_a RENAME TO t_b").classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(store).singleElement().satisfies(o -> {
            assertThat(o.getSchemaName()).isEqualTo("work");
            assertThat(o.getObjectName()).isEqualTo("t_b");
        });
    }

    @Test
    void 조건불충족은_기록만_남기고_접속안함() throws SQLException {
        when(gate.check(any(Task.class), anyBoolean(), any(DbConnection.class)))
                .thenReturn(new DbWriteGate.Check(false, "쓰기 허용 연결이 아닙니다"));

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        verify(executor, never()).open(any());
        verify(connections, never()).writeRefOf(any());
        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).isEqualTo("쓰기 허용 연결이 아닙니다");
        assertThat(saved).singleElement().satisfies(s -> {
            assertThat(s.classification()).isEqualTo(WriteClassification.REJECTED);
            assertThat(s.reason()).isEqualTo("쓰기 허용 연결이 아닙니다");
            assertThat(s.sql()).isEqualTo("UPDATE legacy SET a = 1 WHERE id = 1");
        });
        assertThat(r.logId()).isEqualTo(saved.get(0).id());
    }

    @Test
    void 연결행이_없으면_이름은_번호이고_연결칸은_비운다() throws SQLException {
        when(connections.findForWrite(7L)).thenReturn(Optional.empty());
        when(gate.check(eq(task), eq(true), (DbConnection) isNull()))
                .thenReturn(new DbWriteGate.Check(false, "쓰기 허용 연결이 아닙니다"));

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        // connection_id는 db_connection FK라 없는 연결 번호를 넣을 수 없다
        assertThat(saved).singleElement().satisfies(s -> {
            assertThat(s.connectionName()).isEqualTo("#7");
            assertThat(s.connectionId()).isNull();
        });
        verify(executor, never()).open(any());
    }

    @Test
    void 승인대기는_실행없이_DEFERRED() throws SQLException {
        DbWriteService.ExecuteResult r = run("ALTER TABLE legacy DROP COLUMN a");

        verify(session, never()).executeUpdate(anyString());
        assertThat(r.classification()).isEqualTo(WriteClassification.DEFERRED);
        assertThat(r.reason()).isEqualTo(SqlWriteClassifier.DROP_COLUMN);
        assertThat(saved).singleElement().satisfies(s -> {
            assertThat(s.classification()).isEqualTo(WriteClassification.DEFERRED);
            assertThat(s.executedAt()).isNull();
        });
    }

    @Test
    void 분석할_수_없는_문장은_접속없이_거부() throws SQLException {
        DbWriteService.ExecuteResult r = run("GRANT ALL ON legacy TO someone");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).isNotBlank();
        verify(executor, never()).open(any());
        assertThat(saved).singleElement().satisfies(s -> assertThat(s.classification()).isEqualTo(WriteClassification.REJECTED));
    }

    @Test
    void SQL_길이는_1에서_100000자() {
        for (String bad : new String[]{null, "", "x".repeat(100_001)}) {
            DbWriteService.ExecuteResult r = service.execute(task, true, 7L, bad);
            assertThat(r).isEqualTo(new DbWriteService.ExecuteResult(WriteClassification.REJECTED,
                    "sql은 1~100000자 문자열이어야 합니다", null, null, null));
        }
        verify(connections, never()).findForWrite(anyLong());
        verify(logs, never()).save(any());
    }

    @Test
    void 연결정보를_풀_수_없으면_FAILED() throws SQLException {
        when(connections.writeRefOf(dev)).thenReturn(Optional.empty());

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        assertThat(r.classification()).isEqualTo(WriteClassification.FAILED);
        assertThat(r.error()).isEqualTo("연결 정보를 풀 수 없습니다");
        verify(executor, never()).open(any());
        assertThat(saved).singleElement().satisfies(s -> assertThat(s.classification()).isEqualTo(WriteClassification.FAILED));
    }

    @Test
    void 접속_실패는_FAILED_비밀번호없음() throws SQLException {
        when(executor.open(ref)).thenThrow(new SQLException("FATAL: password authentication failed S3cret!"));

        DbWriteService.ExecuteResult r = run("UPDATE legacy SET a = 1 WHERE id = 1");

        assertThat(r.classification()).isEqualTo(WriteClassification.FAILED);
        assertThat(r.error()).isEqualTo("FATAL: password authentication failed ****");
        assertThat(saved).singleElement().satisfies(s -> assertThat(s.error()).doesNotContain("S3cret!"));
    }

    @Test
    void 재시도중_옛브랜치_기준_의존작업이면_소유예외_꺼짐() throws SQLException {
        task.setHeadBranch(null);   // 재시도(-rN)는 head_branch를 비운다
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenReturn(true);
        Task dependent = other(25L, TaskStatus.APPROVED, "netismaker/task-42-login", null);
        Task cancelled = other(26L, TaskStatus.CANCELLED, "netismaker/task-42-login", null);
        Task otherHost = other(27L, TaskStatus.APPROVED, "netismaker/task-42-login", "https://gitlab.hamonsoft.co.kr/a/b.git");
        Task similarId = other(28L, TaskStatus.APPROVED, "netismaker/task-420-x", null);
        when(tasks.findOpenByRepo("a/b", 42L)).thenReturn(List.of(dependent, cancelled, otherHost, similarId));

        DbWriteService.ExecuteResult r = run("DROP TABLE t_new");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).contains("작업 #25").doesNotContain("#26").doesNotContain("#27").doesNotContain("#28");
        verify(session, never()).executeUpdate(anyString());
        assertThat(store).hasSize(1);

        // 취소됐거나 다른 호스트·다른 작업 번호뿐이면 소유 예외가 그대로다
        when(tasks.findOpenByRepo("a/b", 42L)).thenReturn(List.of(cancelled, otherHost, similarId));
        assertThat(run("DROP TABLE t_new").classification()).isEqualTo(WriteClassification.EXECUTED);
    }

    @Test
    void 의존작업은_head_브랜치가_같아도_찾는다() {
        task.setHeadBranch("feature/login");
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(tasks.findOpenByRepo("a/b", 42L)).thenReturn(List.of(other(31L, TaskStatus.AWAITING_APPROVAL, "feature/login", null)));

        assertThat(service.ownershipFor(task, 7L).dependentNote()).isEqualTo("작업 #31");
    }

    @Test
    void 소유기록이_없으면_의존작업을_보지_않는다() {
        assertThat(service.ownershipFor(task, 7L)).isEqualTo(Ownership.none());
        verify(tasks, never()).findOpenByRepo(any(), any());
    }

    private static Task other(long id, TaskStatus status, String baseBranch, String gitUrl) {
        Task t = Task.create("a/b", baseBranch, "o", "d", "user2", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setGitUrl(gitUrl);
        return t;
    }

    // ── Task 7 리뷰 반영 ①~④ ──────────────────────────────────────────────────────────────────

    @Test
    void 소유기록이_있어도_객체가_없으면_거부하고_기록정리() throws SQLException {
        String missing = "소유 기록의 객체가 DB에 없습니다: public.t_new — 기록을 정리했습니다";
        for (String sql : List.of("DROP TABLE t_new", "DELETE FROM t_new")) {
            own(DbObjectType.TABLE, "public", "t_new", 42L);
            when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenReturn(false);

            DbWriteService.ExecuteResult r = run(sql);

            assertThat(r.classification()).as(sql).isEqualTo(WriteClassification.REJECTED);
            assertThat(r.reason()).as(sql).isEqualTo(missing);
            assertThat(last().classification()).isEqualTo(WriteClassification.REJECTED);
            assertThat(store).as(sql).isEmpty();
        }
        // 확인이 예외여도 같다
        own(DbObjectType.TABLE, "public", "t_new", 42L);
        when(session.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).thenThrow(new SQLException("timeout"));
        assertThat(run("TRUNCATE t_new").reason()).isEqualTo(missing);
        assertThat(store).isEmpty();
        verify(session, never()).executeUpdate(anyString());
    }

    @Test
    void 낡은_기록_위_새_CREATE는_소유자_교체() throws SQLException {
        own(DbObjectType.TABLE, "public", "t_new", 7L);   // 작업 7이 만들었다가 사라진 테이블의 기록

        DbWriteService.ExecuteResult r = run("CREATE TABLE t_new (id int)");

        assertThat(r.classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(store).singleElement().satisfies(o -> {
            assertThat(o.getObjectName()).isEqualTo("t_new");
            assertThat(o.getTaskId()).isEqualTo(42L);
        });
    }

    @Test
    void 기본스키마를_모르면_거부() throws SQLException {
        for (String unknown : new String[]{null, ""}) {
            when(session.defaultSchema()).thenReturn(unknown);

            DbWriteService.ExecuteResult r = run("CREATE TABLE t_new (id int)");

            assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
            assertThat(r.reason()).isEqualTo("기본 스키마를 알 수 없습니다 — 연결의 기본 스키마를 정하세요");
        }
        verify(session, never()).executeUpdate(anyString());
        verify(session, never()).exists(any());
        assertThat(store).isEmpty();
    }

    private void useMysql(boolean lowerCaseNames) throws SQLException {
        dev.setDbType(DbType.MYSQL);
        ref = new DbConnectionRef("db-7", "개발DB (MySQL)", "MYSQL", "db.local", 3306, "app", "writer", "S3cret!");
        when(connections.writeRefOf(dev)).thenReturn(Optional.of(ref));
        when(executor.open(ref)).thenReturn(session);
        when(session.defaultSchema()).thenReturn("app");
        when(session.lowerCaseNames()).thenReturn(lowerCaseNames);
    }

    @Test
    void MySQL_소문자_서버_IF_NOT_EXISTS_인용이름이_기존객체면_거부() throws SQLException {
        useMysql(true);
        when(session.exists(new DbObjectName("app", "legacy"))).thenReturn(true);

        DbWriteService.ExecuteResult r = run("CREATE TABLE IF NOT EXISTS `LEGACY` (id int)");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).startsWith("이미 있음: app.legacy");
        verify(objects, never()).save(any());
        verify(session, never()).executeUpdate(anyString());
    }

    @Test
    void MySQL_소문자_서버_인용이름_생성은_소문자로_기록() throws SQLException {
        useMysql(true);

        assertThat(run("CREATE TABLE `T_New` (id int)").classification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(store).singleElement().satisfies(o -> {
            assertThat(o.getObjectType()).isEqualTo(DbObjectType.TABLE);
            assertThat(o.getSchemaName()).isEqualTo("app");
            assertThat(o.getObjectName()).isEqualTo("t_new");
        });

        when(session.exists(new DbObjectName("app", "t_new"), DbObjectType.TABLE)).thenReturn(true);
        assertThat(run("DROP TABLE t_new").classification()).isEqualTo(WriteClassification.EXECUTED);
        verify(session).executeUpdate("DROP TABLE t_new");
        assertThat(store).isEmpty();
    }

    @Test
    void MySQL_소문자_서버_기본스키마_접기가_어긋날_수_있으면_거부() throws SQLException {
        useMysql(true);
        when(session.defaultSchema()).thenReturn("Ꭰpp");   // 체로키 대문자 — JVM과 서버의 소문자 접기 표가 다를 수 있다

        DbWriteService.ExecuteResult r = run("CREATE TABLE t_new (id int)");

        assertThat(r.classification()).isEqualTo(WriteClassification.REJECTED);
        assertThat(r.reason()).startsWith("기본 스키마 이름");
        verify(session, never()).executeUpdate(anyString());
    }

    @Test
    void 세션은_늘_닫는다() throws SQLException {
        run("ALTER TABLE legacy DROP COLUMN a");               // 승인 대기
        run("DROP TABLE legacy");                              // 거부
        when(session.executeUpdate(anyString())).thenReturn(1L);
        run("UPDATE legacy SET a = 1 WHERE id = 1");           // 실행
        verify(session, times(3)).close();
        assertThat(saved.stream().map(Saved::id).filter(Objects::nonNull)).isNotEmpty();
    }
}
