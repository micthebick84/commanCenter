package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 구현 단계 개발 DB 쓰기가 열리는지 판정한다 (스펙 2026-11-03 §6.1). 조건을 아래 순서로 보고, 처음 걸린 조건의 사유를 돌려준다.
 * <ol>
 *   <li>전역 스위치 app.db-write.enabled</li>
 *   <li>작업이 구현중(IMPLEMENTING) — 토큰 단계는 발급 당시 작업 상태와 같아야 인증되므로 작업 상태로 본다</li>
 *   <li>그 작업이 승인 때 고른 연결(task.db_connection_ids)</li>
 *   <li>연결이 쓰기 허용 — allow_write · REPO · enabled를 넘겨받은 행으로 본다</li>
 *   <li>대상 레포가 자동 마이그레이션 레포가 아님(§6.8)</li>
 *   <li>claim한 워커가 쓰기를 지원(supports=dbWrite) — 큐 필터가 아니라 grant 판정이다</li>
 * </ol>
 * 조건 1·4는 {@link #connectionCheck}로 따로 쓸 수 있다 — 구현 단계 밖에서 도는 관리자 실행(승인 대기 SQL)도 같은 규칙을 다시 본다.
 */
@Component
@Profile("api")
public class DbWriteGate {

    public static final String SWITCH_OFF = "개발 DB 쓰기 스위치가 꺼져 있습니다";
    public static final String IMPLEMENTING_ONLY = "구현 단계에서만 쓸 수 있습니다";
    public static final String NOT_SELECTED = "이 작업이 고른 연결이 아닙니다";
    public static final String NOT_WRITABLE_CONNECTION = "쓰기 허용 연결이 아닙니다";
    public static final String MIGRATION_MANAGED = "자동 마이그레이션 레포입니다 — 마이그레이션 파일로 만드세요";
    public static final String WORKER_UNSUPPORTED = "이 워커는 DB 쓰기를 지원하지 않습니다";

    /** 판정 결과. 허용이면 reason은 null. */
    public record Check(boolean allowed, String reason) {
        static final Check ALLOWED = new Check(true, null);

        static Check denied(String reason) {
            return new Check(false, reason);
        }
    }

    private final DbWriteProperties props;
    private final DbConnectionRepository connections;
    private final RepoCatalogRepository catalogs;

    public DbWriteGate(DbWriteProperties props, DbConnectionRepository connections, RepoCatalogRepository catalogs) {
        this.props = props;
        this.connections = connections;
        this.catalogs = catalogs;
    }

    /**
     * 조건 1~6. 조건 4는 넘겨받은 행만 본다 — 판정과 접속이 같은 행을 보게(다시 읽으면 그 사이 바뀐 접속 대상에 쓸 수 있다).
     * snapshot이 null이면(연결이 없음) 조건 4로 거부한다. 이때는 id를 모르므로 조건 3은 건너뛴다(어느 쪽이든 거부).
     */
    public Check check(Task t, boolean workerSupportsWrite, DbConnection snapshot) {
        return evaluate(t, workerSupportsWrite, snapshot == null ? null : snapshot.getId(), () -> snapshot);
    }

    /** 연결을 읽어 판정한다(grant·인터뷰용). 조건 1~3에서 걸리면 연결을 읽지 않는다. */
    public Check check(Task t, boolean workerSupportsWrite, long connectionId) {
        return evaluate(t, workerSupportsWrite, connectionId, () -> connections.findById(connectionId).orElse(null));
    }

    /** 조건 1·4만 — 같은 문구. 관리자 실행처럼 작업 단계·워커와 무관하게 연결이 지금 쓰기를 받는지 볼 때. */
    public Check connectionCheck(DbConnection snapshot) {
        if (!props.enabled()) return Check.denied(SWITCH_OFF);
        if (snapshot == null || !snapshot.isEnabled() || snapshot.getScope() != DbConnectionScope.REPO
                || !snapshot.isAllowWrite()) {
            return Check.denied(NOT_WRITABLE_CONNECTION);
        }
        return Check.ALLOWED;
    }

    /**
     * 인터뷰 claim용 — 구현 단계에서 쓰기가 열릴 연결의 표시 이름(claimLabel). 조건 1·3·4·5만 보고 단계(2)와 워커(6)는 보지 않는다
     * (인터뷰는 아직 구현 전이고 구현 워커도 정해지지 않았다). ids 순서를 따르고 중복·null은 뺀다.
     */
    public List<String> writableLabels(Task t, List<? extends Number> ids) {
        if (ids == null || ids.isEmpty() || !props.enabled() || migrationManaged(t)) return List.of();
        List<Long> wanted = ids.stream().filter(Objects::nonNull).map(Number::longValue).distinct()
                .filter(id -> selected(t, id)).toList();
        if (wanted.isEmpty()) return List.of();
        Map<Long, DbConnection> rows = new HashMap<>();
        for (DbConnection c : connections.findAllById(wanted)) rows.put(c.getId(), c);
        return wanted.stream().map(rows::get)
                .filter(c -> connectionCheck(c).allowed())
                .map(DbConnectionService::claimLabel)
                .toList();
    }

    /** 작업의 레포 카탈로그가 자동 마이그레이션 표시를 켰는지(§6.8). 카탈로그 항목이 없으면 false. */
    public boolean migrationManaged(Task t) {
        return RepoCatalogRepository.findForTask(catalogs, t.getRepoCatalogId(), t.getGitUrl())
                .map(RepoCatalogEntry::isDbMigrationManaged)
                .orElse(false);
    }

    private Check evaluate(Task t, boolean workerSupportsWrite, Long connectionId, Supplier<DbConnection> row) {
        if (!props.enabled()) return Check.denied(SWITCH_OFF);
        if (t.getStatus() != TaskStatus.IMPLEMENTING) return Check.denied(IMPLEMENTING_ONLY);
        if (connectionId != null && !selected(t, connectionId)) return Check.denied(NOT_SELECTED);
        Check connection = connectionCheck(row.get());
        if (!connection.allowed()) return connection;
        if (migrationManaged(t)) return Check.denied(MIGRATION_MANAGED);
        if (!workerSupportsWrite) return Check.denied(WORKER_UNSUPPORTED);
        return Check.ALLOWED;
    }

    /** JSONB에서 읽은 원소가 Long이 아닐 수도 있어(resolveForClaim과 같은 이유) Number로 비교한다. */
    private static boolean selected(Task t, long connectionId) {
        List<?> ids = t.getDbConnectionIds();
        if (ids == null) return false;
        for (Object id : ids) {
            if (id instanceof Number n && n.longValue() == connectionId) return true;
        }
        return false;
    }
}
