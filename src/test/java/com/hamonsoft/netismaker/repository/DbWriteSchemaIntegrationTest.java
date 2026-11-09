package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbObject;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import com.hamonsoft.netismaker.entity.WriteClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V39의 쓰기 칸·테이블 (스펙 2026-11-03 §6.3·§6.5·§7) — USER 연결 allow_write CHECK, 연결 삭제 때 소유 기록 CASCADE·쓰기 기록
 * connection_id NULL(connection_name 유지), 소유 기록 UNIQUE, 그리고 승인 대기 → 실행/실행 안 함 전이의 원자성
 * (Review Focus 2: 두 번 누르거나 다른 작업 id로 누르거나 이미 처리된 행은 0행).
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DbWriteSchemaIntegrationTest {

    @Autowired private DbConnectionRepository connRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDbObjectRepository objectRepo;
    @Autowired private TaskDbWriteLogRepository logRepo;
    @Autowired private TaskDbMcpTokenRepository tokenRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long repoId;

    @BeforeEach
    void setUp() {
        // task를 지우면 쓰기 기록·소유 기록·토큰은 CASCADE. interview_session.task_id FK는 non-cascade라 먼저 비운다.
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        connRepo.deleteAll();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        repoId = repoCatalogRepo.save(RepoCatalogEntry.create("쓰기테스트-" + suffix,
                "https://github.com/acme/dbwrite-" + suffix + ".git", "github", "acme/dbwrite-" + suffix,
                "main", null, "admin1")).getId();
    }

    private DbConnection repoConn(boolean allowWrite) {
        DbConnection c = DbConnection.create(DbConnectionScope.REPO, repoId, null, "개발DB", DbType.POSTGRESQL,
                "h", 5432, "app", "u", "enc", "admin1");
        c.setAllowWrite(allowWrite);
        return connRepo.saveAndFlush(c);
    }

    private Task task() {
        return taskRepo.save(Task.create("acme/dbwrite", "main", "쓰기", "d", "user1", 3, List.of(), "claude-opus-5-5", "high"));
    }

    private TaskDbWriteLog log(Long taskId, DbConnection c, WriteClassification cls) {
        return logRepo.saveAndFlush(TaskDbWriteLog.create(taskId, c.getId(), "개발DB (PostgreSQL)", 1, null,
                "ALTER TABLE t DROP COLUMN x", cls));
    }

    @Test
    void 쓰기_칸의_기본값() {
        DbConnection c = repoConn(false);
        assertThat(connRepo.findById(c.getId()).orElseThrow().isAllowWrite()).isFalse();
        RepoCatalogEntry e = repoCatalogRepo.findById(repoId).orElseThrow();
        assertThat(e.isDbMigrationManaged()).isFalse();
        assertThat(e.getDbScriptDir()).isNull();
        Long taskId = task().getId();
        tokenRepo.saveAndFlush(TaskDbMcpToken.issue(taskId, UUID.randomUUID().toString().replace("-", ""), "IMPLEMENTING",
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(1)));
        assertThat(tokenRepo.findFirstByTaskIdOrderByIdDesc(taskId).orElseThrow().isWriteGranted()).isFalse();
    }

    @Test
    void USER_연결의_allow_write는_CHECK가_거절() {
        DbConnection c = DbConnection.create(DbConnectionScope.USER, repoId, "user1", "내 DB", DbType.MYSQL,
                "h", 3306, "app", "u", "enc", "user1");
        c.setAllowWrite(true);
        assertThatThrownBy(() -> connRepo.saveAndFlush(c)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_db_connection_allow_write_repo");
        assertThat(repoConn(true).isAllowWrite()).as("REPO 연결은 켤 수 있다").isTrue();
    }

    @Test
    void 연결을_지우면_쓰기기록은_connection_id만_NULL_소유기록은_사라진다() {
        DbConnection c = repoConn(true);
        Long taskId = task().getId();
        objectRepo.saveAndFlush(TaskDbObject.of(c.getId(), "public", "t_new", DbObjectType.TABLE, taskId));
        TaskDbWriteLog l = log(taskId, c, WriteClassification.EXECUTED);

        connRepo.deleteById(c.getId());
        connRepo.flush();

        TaskDbWriteLog after = logRepo.findById(l.getId()).orElseThrow();
        assertThat(after.getConnectionId()).isNull();
        assertThat(after.getConnectionName()).isEqualTo("개발DB (PostgreSQL)");
        assertThat(objectRepo.findAll()).isEmpty();
    }

    @Test
    void 작업을_지우면_기록이_함께_지워진다() {
        DbConnection c = repoConn(true);
        Long taskId = task().getId();
        objectRepo.saveAndFlush(TaskDbObject.of(c.getId(), "public", "t_new", DbObjectType.TABLE, taskId));
        log(taskId, c, WriteClassification.DEFERRED);

        taskRepo.deleteById(taskId);
        taskRepo.flush();

        assertThat(logRepo.findAll()).isEmpty();
        assertThat(objectRepo.findAll()).isEmpty();
    }

    @Test
    void 소유기록은_연결_스키마_이름_종류가_유일하다() {
        DbConnection c = repoConn(true);
        Long a = task().getId();
        Long b = task().getId();
        objectRepo.saveAndFlush(TaskDbObject.of(c.getId(), "public", "t_new", DbObjectType.TABLE, a));

        assertThatThrownBy(() -> objectRepo.saveAndFlush(TaskDbObject.of(c.getId(), "public", "t_new", DbObjectType.TABLE, b)))
                .isInstanceOf(DataIntegrityViolationException.class);
        objectRepo.saveAndFlush(TaskDbObject.of(c.getId(), "public", "t_new", DbObjectType.VIEW, b));   // 종류가 다르면 별개

        assertThat(objectRepo.findByConnectionIdAndTaskId(c.getId(), a)).extracting(TaskDbObject::getObjectName).containsExactly("t_new");
        assertThat(objectRepo.findByConnectionIdAndSchemaNameAndObjectNameAndObjectType(c.getId(), "public", "t_new", DbObjectType.TABLE))
                .get().extracting(TaskDbObject::getTaskId).isEqualTo(a);
        assertThat(objectRepo.findByConnectionIdAndSchemaNameAndObjectNameAndObjectType(c.getId(), "public", "t_new", DbObjectType.SEQUENCE)).isEmpty();
        assertThat(objectRepo.deleteByConnectionId(c.getId())).isEqualTo(2);
        assertThat(objectRepo.findAll()).isEmpty();
    }

    @Test
    void 쓰기기록의_분류는_다섯_값만_받는다() {
        DbConnection c = repoConn(true);
        Long taskId = task().getId();
        for (WriteClassification cls : WriteClassification.values()) log(taskId, c, cls);
        assertThat(logRepo.findByTaskIdOrderByCreatedAtAscIdAsc(taskId)).extracting(TaskDbWriteLog::getClassification)
                .containsExactlyInAnyOrder(WriteClassification.values());
        assertThatThrownBy(() -> jdbc.update("UPDATE com.task_db_write_log SET classification = 'DONE' WHERE task_id = ?", taskId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 쓰기기록은_작업별_시간_id_순서로_읽고_분류_존재를_본다() {
        DbConnection c = repoConn(true);
        Long a = task().getId();
        Long b = task().getId();
        TaskDbWriteLog first = log(a, c, WriteClassification.EXECUTED);
        TaskDbWriteLog second = log(a, c, WriteClassification.DEFERRED);
        log(b, c, WriteClassification.REJECTED);

        assertThat(logRepo.findByTaskIdOrderByCreatedAtAscIdAsc(a)).extracting(TaskDbWriteLog::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(logRepo.existsByTaskIdAndClassificationIn(a, Set.of(WriteClassification.DEFERRED))).isTrue();
        assertThat(logRepo.existsByTaskIdAndClassificationIn(b, Set.of(WriteClassification.EXECUTED, WriteClassification.DEFERRED))).isFalse();
    }

    @Test
    void 승인대기_전이는_한_번만_성공하고_다른_작업_id와_이미_처리된_행은_0행() {
        DbConnection c = repoConn(true);
        Long taskId = task().getId();
        Long otherTaskId = task().getId();
        TaskDbWriteLog row = log(taskId, c, WriteClassification.DEFERRED);

        assertThat(logRepo.transition(row.getId(), taskId, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "a")).isEqualTo(1);
        assertThat(logRepo.transition(row.getId(), taskId, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "a"))
                .as("같은 호출을 다시 누르면 0행(두 관리자 동시 실행 방지)").isZero();
        assertThat(logRepo.transition(row.getId(), otherTaskId, WriteClassification.DEFERRED, WriteClassification.EXECUTED, "a"))
                .as("다른 작업 id로는 0행(IDOR)").isZero();
        assertThat(logRepo.transition(row.getId(), taskId, WriteClassification.DEFERRED, WriteClassification.DISMISSED, "a"))
                .as("이미 실행된 행을 실행 안 함으로 바꿀 수 없다").isZero();

        TaskDbWriteLog after = logRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getClassification()).isEqualTo(WriteClassification.EXECUTED);
        assertThat(after.getExecutedBy()).isEqualTo("a");
        assertThat(after.getExecutedAt()).isNotNull();
    }
}
