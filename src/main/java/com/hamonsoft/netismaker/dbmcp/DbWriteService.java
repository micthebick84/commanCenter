package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbObject;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.TaskDbObjectRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 구현 단계 개발 DB 쓰기 — MCP execute 한 번 (스펙 2026-11-03 §6.2~§6.5).
 * 트랜잭션 없이 이 순서로 돈다(외부 DB는 앱 DB 트랜잭션 밖): 길이 → 연결 행 한 번 읽기·판정 → 분석 → 같은 행으로 복호화 → 접속
 * → 기본 스키마 확인 → 소유 → 분류 → (바로 실행이면) 소유 대상 존재 확인·새 객체 확인 → 기록 사전 저장 → 실행·상한 → 커밋
 * → 소유 반영 → 기록 완료 → 닫기(커밋하지 않았으면 롤백).
 * 설계와 다름: 기록 행을 실행 <b>전에</b> EXECUTED·executed_at NULL로 저장하고, 그 저장이 실패하면 실행하지 않는다
 * (반목표 "실행된 SQL이 기록에서 빠짐"). 비밀번호·접속정보는 기록·응답·로그에 싣지 않고(DbMcpTools.safeMessage),
 * SQL은 로그에 싣지 않는다(컨트롤러 감사 로그가 앞 200자만).
 * <p>
 * 승인 대기(DEFERRED)로 남은 문장은 관리자가 나중에 {@link #executeDeferred 실행}하거나 {@link #dismiss 실행 안 함}으로 정리한다
 * (§6.5) — 같은 서비스가 {@link #list 목록}도 낸다. 관리자 실행도 외부 DB 때문에 앱 DB 트랜잭션 밖이다.
 */
@Service
@Profile("api")
public class DbWriteService {

    private static final Logger log = LoggerFactory.getLogger(DbWriteService.class);

    static final String INVALID_SQL = "sql은 1~100000자 문자열이어야 합니다";
    static final String CANNOT_DECRYPT = "연결 정보를 풀 수 없습니다";
    static final String LOG_SAVE_FAILED = "기록 저장 실패 — 실행하지 않았습니다";
    static final String UNKNOWN_DEFAULT_SCHEMA = "기본 스키마를 알 수 없습니다 — 연결의 기본 스키마를 정하세요";
    static final String UNSAFE_DEFAULT_SCHEMA = "기본 스키마 이름에 서버와 대소문자 접기가 다를 수 있는 문자가 있습니다"
            + " — ASCII 이름의 데이터베이스를 연결의 기본으로 정하세요";
    static final String ONLY_DEFERRED_EXECUTE = "승인 대기 항목만 실행할 수 있습니다";
    static final String ONLY_DEFERRED_DISMISS = "승인 대기 항목만 실행 안 함으로 처리할 수 있습니다";
    static final String ALREADY_HANDLED = "이미 처리된 항목입니다";
    static final String ADMIN_REJECTED = "거부 분류는 관리자도 실행할 수 없습니다";

    /** 소유 덕에 바로 실행될 때 대상이 실제로 있는지 확인하는 문장 — CREATE_*·COMMENT_ON은 빼고 유니크 인덱스는 넣는다. */
    private static final Set<WriteKind> OWNED_TARGET_KINDS = EnumSet.of(
            WriteKind.DROP_TABLE, WriteKind.DROP_VIEW, WriteKind.DROP_SEQUENCE, WriteKind.TRUNCATE,
            WriteKind.ALTER_TABLE, WriteKind.RENAME_TABLE, WriteKind.CREATE_UNIQUE_INDEX,
            WriteKind.INSERT, WriteKind.UPDATE, WriteKind.DELETE);
    private static final Set<WriteKind> DML = EnumSet.of(WriteKind.INSERT, WriteKind.UPDATE, WriteKind.DELETE);
    private static final Set<WriteKind> CREATES = EnumSet.of(
            WriteKind.CREATE_TABLE, WriteKind.CREATE_VIEW, WriteKind.CREATE_SEQUENCE);
    private static final String PG_CATALOG = "pg_catalog";

    /**
     * execute 결과. 바로 실행이면 affectedRows(DML만, DDL은 null), 승인 대기·거부면 reason, 실패면 error.
     * logId는 기록 행(입력 길이 오류·기록 저장 실패면 null).
     */
    public record ExecuteResult(WriteClassification classification, String reason, Long affectedRows, Long logId,
                                String error) {}

    private final DbWriteGate gate;
    private final DbConnectionService connections;
    private final DbWriteExecutor executor;
    private final TaskDbObjectRepository objects;
    private final TaskDbWriteLogRepository logs;
    private final TaskRepository tasks;
    private final DbWriteProperties props;
    private final TaskStatusHistoryRepository history;

    public DbWriteService(DbWriteGate gate, DbConnectionService connections, DbWriteExecutor executor,
                          TaskDbObjectRepository objects, TaskDbWriteLogRepository logs, TaskRepository tasks,
                          DbWriteProperties props, TaskStatusHistoryRepository history) {
        this.gate = gate;
        this.connections = connections;
        this.executor = executor;
        this.objects = objects;
        this.logs = logs;
        this.tasks = tasks;
        this.props = props;
        this.history = history;
    }

    /**
     * @param tokenWriteGranted 토큰 발급 때의 쓰기 허가(§6.1 조건 6 — 쓰기를 아는 워커의 구현 claim)
     */
    public ExecuteResult execute(Task task, boolean tokenWriteGranted, long connectionId, String sql) {
        if (sql == null || sql.isEmpty() || sql.length() > SqlWriteParser.MAX_SQL_CHARS) {
            return new ExecuteResult(WriteClassification.REJECTED, INVALID_SQL, null, null, null);
        }
        // 판정과 접속은 이 행 하나로 — 따로 읽으면 그 사이 바뀐 접속 대상에 이미 통과한 판정으로 쓴다
        DbConnection snapshot = connections.findForWrite(connectionId).orElse(null);
        Entry entry = new Entry(task, connectionId, snapshot, sql);
        DbWriteGate.Check check = gate.check(task, tokenWriteGranted, snapshot);
        if (!check.allowed()) return entry.reject(check.reason());

        SqlDialect d = SqlDialect.of(snapshot.getDbType());
        WriteStatement st;
        try {
            st = SqlWriteParser.parse(sql, d);
        } catch (SqlWriteRejected e) {
            return entry.reject(e.getMessage());
        }
        Optional<DbConnectionRef> ref = connections.writeRefOf(snapshot);
        if (ref.isEmpty()) return entry.fail(CANNOT_DECRYPT);
        String password = ref.get().password();
        try (DbWriteSession session = executor.open(ref.get())) {
            return run(task, connectionId, d, st, session, entry, password);
        } catch (SQLException | RuntimeException e) {
            // 커밋 전 실패만 여기 온다(run은 커밋 뒤 예외를 밖으로 내지 않는다) — 세션은 이미 롤백하고 닫혔다
            return entry.fail(DbMcpTools.safeMessage(e, password));
        }
    }

    private ExecuteResult run(Task task, long connectionId, SqlDialect d, WriteStatement st, DbWriteSession session,
                              Entry entry, String password) throws SQLException {
        String defaultSchema = session.defaultSchema();
        boolean lowerCaseNames = session.lowerCaseNames();
        String schemaProblem = defaultSchemaProblem(d, defaultSchema, lowerCaseNames);
        if (schemaProblem != null) return entry.reject(schemaProblem);
        // 분류·소유 기록·존재 확인은 모두 이 정규화 하나를 쓴다
        Function<DbObjectRef, DbObjectName> names = r -> DbObjectName.normalize(d, r, defaultSchema, lowerCaseNames);

        Ownership ownership = ownershipFor(task, connectionId);
        WriteDecision decision = SqlWriteClassifier.classify(st, names, ownership,
                props.maxUpdateDeleteRows(), props.maxInsertRows());
        if (decision.verdict() == WriteDecision.Verdict.REJECT) return entry.reject(decision.reason());
        if (decision.verdict() == WriteDecision.Verdict.DEFER) return entry.defer(decision.reason());

        String missing = missingOwnedObject(connectionId, task.getId(), st, decision, ownership, names, session);
        if (missing != null) return entry.reject(missing);
        String taken = takenName(d, st, names, session, password);
        if (taken != null) return entry.reject(taken);

        try {
            entry.begin();
        } catch (RuntimeException e) {
            log.warn("DB 쓰기 기록 사전 저장 실패 — 실행하지 않음 task={} conn={} ({})", task.getId(), connectionId,
                    e.getClass().getSimpleName());
            return new ExecuteResult(WriteClassification.FAILED, null, null, null, LOG_SAVE_FAILED);
        }
        long affected = session.executeUpdate(st.executableSql());
        Integer cap = decision.rowCap();
        if (cap != null && affected > cap) {
            return entry.reject("영향 행 상한 초과: " + affected + " > " + cap);   // 커밋하지 않는다 — close가 롤백
        }
        session.commit();
        reflectOwnership(task.getId(), connectionId, d, st, names);
        return entry.executed(DML.contains(st.kind()) ? affected : null);
    }

    /** 작업의 DB 변경 기록 — 시간순(같은 시각이면 id순). 화면과 워커가 같은 순서를 쓴다. 조회 권한은 호출자가 본다. */
    public List<DbChangeView> list(Long taskId) {
        return logs.findByTaskIdOrderByCreatedAtAscIdAsc(taskId).stream().map(DbChangeView::of).toList();
    }

    /**
     * 승인 대기(DEFERRED) SQL을 관리자가 실행한다 (스펙 §6.5). 같은 연결로 하고, 성공하면 기록 행이 EXECUTED(실행자·시각)로 바뀌고
     * 작업 이력에 한 줄이 남는다. 응답은 갱신된 행이다. 막히면 409(없는 기록·남의 작업 기록은 404).
     * <p>
     * 순서(선점 전 단계는 상태를 바꾸지 않으므로 거기서 막히면 되돌릴 것이 없다):
     * ① 이 작업의 DEFERRED 행인지 ② 연결 행을 한 번 읽어 스위치·enabled·REPO·allowWrite를 그 행으로 확인(조건 1·4 — 구현 단계 밖에서
     * 도는 경로라 작업 단계·워커 조건은 보지 않는다) ③ 같은 행으로 복호화해 접속 ④ 다시 분류해 거부면 거부(거부 분류는 관리자도
     * 못 한다) — 기본 스키마를 모르거나 소유 덕의 EXECUTE인데 소유 대상이 DB에 없어도 막는다(Task 7 리뷰 반영, {@link #execute}와 같은
     * 메서드) ⑤ transition(DEFERRED→EXECUTED)으로 선점, 0이면 이미 처리됨 ⑥ 영향 행 상한 없이 실행하고 커밋
     * ⑦ 실행·커밋이 실패하면 DEFERRED로 되돌리고 오류를 남긴 뒤 409.
     * 병합 여부·작업 상태로는 막지 않는다. 세션은 항상 닫는다.
     * <p>
     * 설계와 다름: 설계는 관리자 실행 때 쓰기 허용을 다시 확인하라고 정하지 않았다. 반목표 "허용 안 된 연결에 쓰기"를 막으려고
     * 다시 확인한다. 상태를 바꾸는 저장은 모두 칸 단위 UPDATE다 — 미리 읽은 행을 save하면 그 사이 바뀐 칸이 옛 값으로 덮인다.
     */
    public DbChangeView executeDeferred(Long taskId, Long logId, String adminId) {
        Task task = tasks.findActiveById(taskId).orElseThrow(TaskException::notFound);
        TaskDbWriteLog row = deferredRow(taskId, logId, ONLY_DEFERRED_EXECUTE);

        Long rowConnectionId = row.getConnectionId();   // 연결을 지웠으면 null — 판정이 거부한다
        DbConnection snapshot = rowConnectionId == null ? null : connections.findForWrite(rowConnectionId).orElse(null);
        DbWriteGate.Check check = gate.connectionCheck(snapshot);
        if (!check.allowed()) throw TaskException.conflict(check.reason());
        Optional<DbConnectionRef> ref = connections.writeRefOf(snapshot);
        if (ref.isEmpty()) throw TaskException.conflict(CANNOT_DECRYPT);

        long connectionId = snapshot.getId();
        SqlDialect d = SqlDialect.of(snapshot.getDbType());
        WriteStatement st;
        try {
            st = SqlWriteParser.parse(row.getSqlText(), d);
        } catch (SqlWriteRejected e) {
            throw TaskException.conflict(ADMIN_REJECTED + " — " + e.getMessage());
        }

        String password = ref.get().password();
        boolean claimed = false;
        boolean committed = false;
        long affected = 0;
        try (DbWriteSession session = executor.open(ref.get())) {
            String defaultSchema = session.defaultSchema();
            boolean lowerCaseNames = session.lowerCaseNames();
            String schemaProblem = defaultSchemaProblem(d, defaultSchema, lowerCaseNames);
            if (schemaProblem != null) throw TaskException.conflict(schemaProblem);
            Function<DbObjectRef, DbObjectName> names = r -> DbObjectName.normalize(d, r, defaultSchema, lowerCaseNames);

            Ownership ownership = ownershipFor(task, connectionId);
            WriteDecision decision = SqlWriteClassifier.classify(st, names, ownership,
                    props.maxUpdateDeleteRows(), props.maxInsertRows());
            if (decision.verdict() == WriteDecision.Verdict.REJECT) {
                throw TaskException.conflict(ADMIN_REJECTED + " — " + decision.reason());
            }
            String missing = missingOwnedObject(connectionId, taskId, st, decision, ownership, names, session);
            if (missing != null) throw TaskException.conflict(missing);

            // 선점 — 두 번 누르거나 두 관리자가 동시에 눌러도 조건부 UPDATE 한 번만 1을 돌려준다(Review Focus 2)
            if (logs.transition(logId, taskId, WriteClassification.DEFERRED, WriteClassification.EXECUTED, adminId) == 0) {
                throw TaskException.conflict(ALREADY_HANDLED);
            }
            claimed = true;
            affected = session.executeUpdate(st.executableSql());   // 영향 행 상한은 걸지 않는다 — 관리자가 정한 실행이다
            session.commit();
            committed = true;
        } catch (TaskException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            String safe = DbMcpTools.safeMessage(e, password);
            if (!claimed) throw TaskException.conflict("실행 준비에 실패했습니다: " + safe);
            if (!committed) {
                revertToDeferred(taskId, logId, safe);
                throw TaskException.conflict("실행에 실패했습니다: " + safe + " — 승인 대기로 되돌렸습니다");
            }
            // 커밋은 끝났다 — 닫을 때의 예외로 결과를 뒤집지 않는다
            log.warn("DB 쓰기(관리자 실행) 세션 닫기 실패 — 커밋은 끝남 task={} log={} ({})", taskId, logId,
                    e.getClass().getSimpleName());
        }

        try {
            logs.completeExecution(logId, taskId, DML.contains(st.kind()) ? affected : null);
        } catch (RuntimeException e) {
            log.warn("DB 쓰기(관리자 실행) 영향 행 저장 실패 — 실행은 끝남 task={} log={} ({})", taskId, logId,
                    e.getClass().getSimpleName());
        }
        recordHistory(task, adminId, "DB 변경 실행: #" + logId + " (" + row.getConnectionName() + ")");
        log.info("DB 쓰기(관리자 실행) task={} conn={} log={} by={}", taskId, connectionId, logId, adminId);
        return refreshed(logId);
    }

    /**
     * 승인 대기(DEFERRED) SQL을 실행하지 않기로 한다 (스펙 §6.5) — DISMISSED가 되고 작업 이력에 남으며 스크립트 파일의
     * "병합 뒤 실행" 절에서 빠진다. DB에는 닿지 않으므로 스위치·연결 상태와 무관하게 정리할 수 있다.
     * 두 번 누르거나 두 관리자가 동시에 눌러도 한 번만 바뀌고 나머지는 409다. 응답은 갱신된 행이다.
     */
    public DbChangeView dismiss(Long taskId, Long logId, String adminId) {
        Task task = tasks.findActiveById(taskId).orElseThrow(TaskException::notFound);
        deferredRow(taskId, logId, ONLY_DEFERRED_DISMISS);
        if (logs.transition(logId, taskId, WriteClassification.DEFERRED, WriteClassification.DISMISSED, adminId) == 0) {
            throw TaskException.conflict(ALREADY_HANDLED);
        }
        recordHistory(task, adminId, "DB 변경 실행 안 함: #" + logId);
        return refreshed(logId);
    }

    /** 이 작업의 기록 행 — 없거나 다른 작업의 것이면 404(기록 id로 남의 작업을 건드리지 못하게), 승인 대기가 아니면 409. */
    private TaskDbWriteLog deferredRow(Long taskId, Long logId, String onlyDeferredMessage) {
        TaskDbWriteLog row = logs.findById(logId).filter(l -> taskId.equals(l.getTaskId()))
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "DB 변경 기록을 찾을 수 없습니다"));
        if (row.getClassification() != WriteClassification.DEFERRED) {
            throw TaskException.conflict(onlyDeferredMessage);
        }
        return row;
    }

    /** 칸 단위 UPDATE 뒤에는 행을 다시 읽어 응답한다(미리 읽은 행은 옛 분류다). */
    private DbChangeView refreshed(Long logId) {
        return DbChangeView.of(logs.findById(logId).orElseThrow(TaskException::notFound));
    }

    /**
     * 선점한 행(EXECUTED)을 승인 대기로 되돌리고 오류를 남긴다. 되돌리기가 실패해도 호출자의 409는 그대로 나간다 — 그때 행은
     * EXECUTED로 남지만 처리자·시각이 있어 관리자가 알아볼 수 있고, 로그에 남긴다(Task 11 "설계와 다름 ②"와 같은 쪽의 한계).
     */
    private void revertToDeferred(Long taskId, Long logId, String error) {
        try {
            if (logs.revertToDeferred(logId, taskId, error) == 0) {
                log.warn("DB 쓰기(관리자 실행) 승인 대기로 되돌릴 행이 없음 task={} log={}", taskId, logId);
            }
        } catch (RuntimeException e) {
            log.warn("DB 쓰기(관리자 실행) 승인 대기로 되돌리기 실패 — 행이 EXECUTED로 남음 task={} log={} ({})", taskId, logId,
                    e.getClass().getSimpleName());
        }
    }

    /** 같은 상태 행 — 처리한 관리자가 사람으로 남는다. 저장이 실패해도 이미 한 처리는 그대로다(로그만). */
    private void recordHistory(Task task, String adminId, String reason) {
        try {
            history.save(TaskStatusHistory.log(task.getId(), task.getStatus(), task.getStatus(), "user", adminId, reason));
        } catch (RuntimeException e) {
            log.warn("DB 변경 이력 저장 실패 — 처리는 끝남 task={} ({})", task.getId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 기본 스키마를 모르면 비한정 이름을 정규화할 수 없다 — 쓰기를 거부할 사유, 괜찮으면 null (Task 7 리뷰 반영 ③).
     * 예: PG current_schema() NULL(search_path의 스키마가 하나도 없음), MySQL DATABASE() NULL.
     * MySQL 소문자 서버는 기본 스키마도 이 JVM이 접으므로 서버와 접기가 다를 수 있는 문자가 있으면 거부한다(분석기의 이름 규칙과 같다).
     */
    static String defaultSchemaProblem(SqlDialect d, String defaultSchema, boolean lowerCaseNames) {
        if (defaultSchema == null || defaultSchema.isBlank()) return UNKNOWN_DEFAULT_SCHEMA;
        if (d == SqlDialect.MYSQL && lowerCaseNames && DbObjectName.caseFoldMayDiffer(defaultSchema)) {
            return UNSAFE_DEFAULT_SCHEMA;
        }
        return null;
    }

    /**
     * 소유 덕에 바로 실행되는 문장이면 그 소유 대상이 그 스키마에 그 종류로 실제로 있는지 확인한다 (Task 7 리뷰 반영 ①).
     * 비한정 이름은 DB가 다르게 풀 수 있다(PG search_path 뒤쪽 스키마의 같은 이름, Oracle 공개 동의어) — 소유 기록만 남고 객체가
     * 사라졌으면 그 이름이 남의 객체에 닿는다. 없거나 확인이 예외면 그 낡은 소유 기록을 지우고 거부 사유를 돌려준다. 괜찮으면 null.
     * 소유 OR REPLACE VIEW·CREATE_*·COMMENT_ON은 보지 않는다. 의존 작업이 있으면 소유 예외가 꺼져 있으므로 보지 않는다.
     * 관리자 실행(승인 대기 SQL)도 다시 분류한 뒤 이것을 쓴다.
     */
    String missingOwnedObject(long connectionId, long taskId, WriteStatement st, WriteDecision decision,
                              Ownership ownership, Function<DbObjectRef, DbObjectName> names, DbWriteSession session) {
        if (decision.verdict() != WriteDecision.Verdict.EXECUTE || ownership.dependentNote() != null
                || !OWNED_TARGET_KINDS.contains(st.kind())) {
            return null;
        }
        DbObjectType type = objectTypeOf(st.kind());
        for (DbObjectRef r : st.targets()) {
            DbObjectName n = names.apply(r);
            if (!ownership.owned().contains(new Ownership.OwnedObject(type, n))) continue;
            boolean present;
            try {
                present = session.exists(n, type);
            } catch (SQLException | RuntimeException e) {
                present = false;   // 확인이 애매하면 없는 것으로 — 실행하지 않는다
            }
            if (!present) {
                forget(connectionId, type, n, taskId);
                return "소유 기록의 객체가 DB에 없습니다: " + qualified(n) + " — 기록을 정리했습니다";
            }
        }
        return null;
    }

    /**
     * 새로 만드는 CREATE(OR REPLACE 아님)의 대상이 이미 있으면 거부 사유 — IF NOT EXISTS로 남의 객체 소유를 얻지 못하게(§6.3).
     * PostgreSQL은 search_path보다 pg_catalog를 먼저 찾으므로 시스템 카탈로그와 같은 이름도 이미 있는 것으로 본다 — 그 이름을 소유하면
     * 비한정 DELETE·DROP이 소유 확인(그 스키마에 있음)을 통과하고 pg_catalog의 객체에 닿는다(스키마를 적어 만들어도 같다).
     */
    private static String takenName(SqlDialect d, WriteStatement st, Function<DbObjectRef, DbObjectName> names,
                                    DbWriteSession session, String password) {
        if (st.orReplace() || !CREATES.contains(st.kind())) return null;
        for (DbObjectRef r : st.targets()) {
            DbObjectName n = names.apply(r);
            List<DbObjectName> taken = d == SqlDialect.POSTGRES && !PG_CATALOG.equals(n.schema())
                    ? List.of(n, new DbObjectName(PG_CATALOG, n.name())) : List.of(n);
            for (DbObjectName candidate : taken) {
                try {
                    if (session.exists(candidate)) {
                        return "이미 있음: " + qualified(candidate) + " — 실행하지 않았습니다. 새 객체면 다른 이름을 쓰세요";
                    }
                } catch (SQLException | RuntimeException e) {
                    return "존재 확인 실패: " + qualified(candidate) + " — " + DbMcpTools.safeMessage(e, password);
                }
            }
        }
        return null;
    }

    /**
     * 그 작업이 그 연결에 만든 객체(소유 기록)와 의존 작업 (§6.3). 소유 기록이 없으면 의존 작업을 찾지 않는다(소유 예외가 없다).
     */
    public Ownership ownershipFor(Task t, long connectionId) {
        Set<Ownership.OwnedObject> owned = objects.findByConnectionIdAndTaskId(connectionId, t.getId()).stream()
                .map(o -> new Ownership.OwnedObject(o.getObjectType(), new DbObjectName(o.getSchemaName(), o.getObjectName())))
                .collect(Collectors.toSet());
        if (owned.isEmpty()) return Ownership.none();
        return new Ownership(owned, dependentNote(t));
    }

    /**
     * 같은 레포·같은 호스트의 열린 작업 중 이 작업 브랜치를 기준으로 쓰는 것 — 그 작업 코드가 이 작업의 테이블을 쓴다(작업 25 ← 19).
     * 기준 브랜치가 이 작업의 head 브랜치와 같거나, 마지막 경로 조각이 task-{id}-로 시작하면(재시도 -rN은 head_branch를 비워
     * 옛 브랜치 netismaker/task-42-…를 기준으로 쓰는 작업을 head 브랜치로는 못 찾는다, Review Focus 5) 의존 작업이다.
     * 설계와 다름: findActiveByBaseBranch는 취소된 작업을 포함하고 head_branch가 빈 재시도에서 옛 브랜치를 못 찾아 쓰지 않는다.
     */
    private String dependentNote(Task t) {
        String host = RepoRef.fromSnapshot(t.getGithubRepo(), t.getGitUrl()).host();
        String prefix = "task-" + t.getId() + "-";
        List<Long> ids = tasks.findOpenByRepo(t.getGithubRepo(), t.getId()).stream()
                .filter(d -> d.getStatus() != TaskStatus.CLOSED && d.getStatus() != TaskStatus.CANCELLED)
                .filter(d -> host.equals(RepoRef.fromSnapshot(d.getGithubRepo(), d.getGitUrl()).host()))
                .filter(d -> basedOn(d.getGithubBranch(), t.getHeadBranch(), prefix))
                .map(Task::getId)
                .toList();
        if (ids.isEmpty()) return null;
        return "작업 #" + ids.stream().map(String::valueOf).collect(Collectors.joining(", #"));
    }

    private static boolean basedOn(String baseBranch, String headBranch, String prefix) {
        if (baseBranch == null) return false;
        if (headBranch != null && !headBranch.isBlank() && baseBranch.equals(headBranch)) return true;
        return baseBranch.substring(baseBranch.lastIndexOf('/') + 1).startsWith(prefix);
    }

    /** 커밋 뒤 소유 기록 반영 — 실패해도 실행 결과는 그대로다(소유가 빠지면 남의 객체로 보일 뿐이라 안전한 쪽). */
    private void reflectOwnership(long taskId, long connectionId, SqlDialect d, WriteStatement st,
                                  Function<DbObjectRef, DbObjectName> names) {
        try {
            DbObjectType type = objectTypeOf(st.kind());
            switch (st.kind()) {
                case CREATE_TABLE, CREATE_VIEW, CREATE_SEQUENCE -> {
                    if (!st.orReplace()) {
                        for (DbObjectRef r : st.targets()) claim(connectionId, type, names.apply(r), taskId);
                    }
                }
                case DROP_TABLE, DROP_VIEW, DROP_SEQUENCE -> {
                    for (DbObjectRef r : st.targets()) forget(connectionId, type, names.apply(r), taskId);
                }
                case RENAME_TABLE, ALTER_TABLE -> {
                    if (st.renameTo() != null && !st.targets().isEmpty()) {
                        DbObjectName from = names.apply(st.targets().get(0));
                        rename(connectionId, from, renamedTo(d, st.renameTo(), from, names), taskId);
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("DB 쓰기 소유 기록 반영 실패(실행·커밋은 끝남) task={} conn={} kind={} ({})", taskId, connectionId,
                    st.kind(), e.getClass().getSimpleName());
        }
    }

    /**
     * 새로 만든 객체를 이 작업 것으로 기록한다. 실행 전에 없음을 확인한 이름에 남은 기록(다른 작업 것 포함)은 낡은 것이다 —
     * UNIQUE가 새 생성자의 기록을 막지 않게 그 행을 이 작업으로 바꿔 쓴다(Task 7 리뷰 반영 ④).
     */
    private void claim(long connectionId, DbObjectType type, DbObjectName n, long taskId) {
        Optional<TaskDbObject> stale = find(connectionId, type, n);
        if (stale.isEmpty()) {
            objects.save(TaskDbObject.of(connectionId, n.schema(), n.name(), type, taskId));
        } else if (!Objects.equals(stale.get().getTaskId(), taskId)) {
            stale.get().setTaskId(taskId);
            objects.save(stale.get());
        }
    }

    private void forget(long connectionId, DbObjectType type, DbObjectName n, long taskId) {
        find(connectionId, type, n).filter(o -> Objects.equals(o.getTaskId(), taskId)).ifPresent(objects::delete);
    }

    /** 소유 테이블 이름 변경 — 성공했으니 새 이름 자리에 남은 기록은 낡은 것이다. */
    private void rename(long connectionId, DbObjectName from, DbObjectName to, long taskId) {
        if (from.equals(to)) return;
        Optional<TaskDbObject> row = find(connectionId, DbObjectType.TABLE, from)
                .filter(o -> Objects.equals(o.getTaskId(), taskId));
        if (row.isEmpty()) return;
        find(connectionId, DbObjectType.TABLE, to).ifPresent(objects::delete);
        row.get().setSchemaName(to.schema());
        row.get().setObjectName(to.name());
        objects.save(row.get());
    }

    /**
     * 이름 변경의 새 이름. 스키마를 적지 않았으면 PostgreSQL(ALTER TABLE … RENAME TO)·Oracle(RENAME·ALTER TABLE … RENAME TO)은
     * 원래 스키마에 남고, MySQL·MariaDB(RENAME TABLE·ALTER TABLE … RENAME)는 기본 스키마(DATABASE())로 옮긴다.
     */
    private static DbObjectName renamedTo(SqlDialect d, DbObjectRef to, DbObjectName from,
                                          Function<DbObjectRef, DbObjectName> names) {
        DbObjectName n = names.apply(to);
        return to.schema() == null && d != SqlDialect.MYSQL ? new DbObjectName(from.schema(), n.name()) : n;
    }

    private Optional<TaskDbObject> find(long connectionId, DbObjectType type, DbObjectName n) {
        return objects.findByConnectionIdAndSchemaNameAndObjectNameAndObjectType(connectionId, n.schema(), n.name(), type);
    }

    /** 문장 종류가 다루는 객체 종류 — 분류기가 소유를 볼 때와 같은 대응. 객체를 다루지 않는 종류는 null. */
    static DbObjectType objectTypeOf(WriteKind k) {
        return switch (k) {
            case CREATE_VIEW, DROP_VIEW -> DbObjectType.VIEW;
            case CREATE_SEQUENCE, DROP_SEQUENCE -> DbObjectType.SEQUENCE;
            case CREATE_TABLE, DROP_TABLE, TRUNCATE, ALTER_TABLE, RENAME_TABLE, CREATE_UNIQUE_INDEX,
                 INSERT, UPDATE, DELETE -> DbObjectType.TABLE;
            case CREATE_INDEX, COMMENT_ON, CREATE_TRIGGER, CREATE_ROUTINE -> null;
        };
    }

    private static String qualified(DbObjectName n) {
        return n.schema() + "." + n.name();
    }

    /**
     * 기록 행 하나 — 바로 실행이면 실행 전에 저장(begin)하고 끝에 같은 행을 고친다. 그 밖은 끝에 한 번 저장한다.
     * connection_id는 db_connection FK라 연결 행이 없으면 비우고, 이름은 기록 시점의 claimLabel(없으면 "#id")이다.
     * attempt·fix_request_id는 그때 작업의 구현 시도 번호·수정 회차.
     */
    private final class Entry {
        private final Task task;
        private final Long connectionId;
        private final String connectionName;
        private final String sql;
        private TaskDbWriteLog row;

        Entry(Task task, long connectionId, DbConnection snapshot, String sql) {
            this.task = task;
            this.connectionId = snapshot == null ? null : connectionId;
            this.connectionName = snapshot == null ? "#" + connectionId : DbConnectionService.claimLabel(snapshot);
            this.sql = sql;
        }

        /** 실행 전 저장 — EXECUTED·executed_at NULL. 실패하면 예외를 던진다(호출자가 실행하지 않는다). */
        void begin() {
            row = logs.save(newRow(WriteClassification.EXECUTED));
        }

        ExecuteResult executed(Long affectedRows) {
            return end(WriteClassification.EXECUTED, null, affectedRows, null, true);
        }

        ExecuteResult defer(String reason) {
            return end(WriteClassification.DEFERRED, reason, null, null, false);
        }

        ExecuteResult reject(String reason) {
            return end(WriteClassification.REJECTED, reason, null, null, false);
        }

        ExecuteResult fail(String error) {
            return end(WriteClassification.FAILED, null, null, error, false);
        }

        private ExecuteResult end(WriteClassification c, String reason, Long affectedRows, String error, boolean executed) {
            TaskDbWriteLog r = row != null ? row : newRow(c);
            r.setClassification(c);
            r.setReason(reason);
            r.setAffectedRows(affectedRows);
            r.setError(error);
            if (executed) r.setExecutedAt(OffsetDateTime.now());
            try {
                row = logs.save(r);
            } catch (RuntimeException e) {
                // 사전 저장한 행이 있으면 그 행(EXECUTED·executed_at NULL)은 남는다
                log.warn("DB 쓰기 기록 저장 실패 task={} conn={} class={} ({})", task.getId(), connectionId, c,
                        e.getClass().getSimpleName());
            }
            Long logId = row == null ? null : row.getId();
            log.info("DB 쓰기 task={} conn={} → {} log={}", task.getId(), connectionId, c, logId);
            return new ExecuteResult(c, reason, affectedRows, logId, error);
        }

        private TaskDbWriteLog newRow(WriteClassification c) {
            return TaskDbWriteLog.create(task.getId(), connectionId, connectionName, task.getImplementationAttempt(),
                    task.getActiveFixRequestId(), sql, c);
        }
    }
}
