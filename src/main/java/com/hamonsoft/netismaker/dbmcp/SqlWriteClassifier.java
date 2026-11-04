package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;

import java.util.List;
import java.util.function.Function;

/**
 * 쓰기 문장 분류 — 바로 실행 · 승인 대기 · 거부 (스펙 2026-11-03 §6.2).
 * "그 작업의 테이블"은 소유 기록에 있는 객체뿐이고 그 밖은 모두 남의 테이블이다. 의존 작업이 있으면 소유가 없는 것으로 본다(§6.3).
 * 설계와 다름: 트리거는 자기 테이블에 걸어도 승인 대기(본문이 남의 테이블을 바꿀 수 있다), 소유하지 않은 OR REPLACE VIEW는
 * 새 뷰여도 승인 대기, 남의 테이블에서 제약을 지우거나 기본값을 바꾸면 승인 대기.
 */
public final class SqlWriteClassifier {

    static final String DROP_COLUMN = "남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요";
    static final String RENAME_COLUMN = "남의 테이블 컬럼 이름 변경은 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요";
    static final String ALTER_COLUMN = "남의 테이블 컬럼 변경(타입·NULL 여부·기본값)은 승인 대기로 기록됨 — 바꾸지 말고 새 컬럼을 추가해 쓰세요";
    static final String NOT_NULL_NO_DEFAULT = "남의 테이블에 DEFAULT 없는 NOT NULL 컬럼 추가는 승인 대기로 기록됨 — NULL을 허용하거나 DEFAULT를 주면 바로 실행됩니다";
    static final String ADD_CONSTRAINT = "남의 테이블 제약 추가는 승인 대기로 기록됨 — 다른 사람 코드의 INSERT가 깨질 수 있습니다";
    static final String DROP_CONSTRAINT = "남의 테이블 제약·인덱스 삭제는 승인 대기로 기록됨";
    static final String UNIQUE_INDEX = "남의 테이블 유니크 인덱스는 승인 대기로 기록됨 — 다른 사람 코드의 INSERT가 깨질 수 있습니다";
    static final String REPLACE_VIEW = "남의 뷰 교체(CREATE OR REPLACE VIEW)는 승인 대기로 기록됨 — 새 뷰면 CREATE VIEW를 쓰세요";
    static final String TRIGGER = "트리거는 승인 대기로 기록됨 — 본문이 다른 테이블을 바꿀 수 있어 관리자가 확인한 뒤 실행합니다";
    static final String ROUTINE = "프로시저·함수·패키지는 승인 대기로 기록됨 — 관리자가 확인한 뒤 실행합니다";
    static final String DROP_TABLE = "남의 테이블은 지울 수 없습니다(DROP) — 그 작업이 만든 테이블만 지울 수 있습니다";
    static final String DROP_VIEW = "남의 뷰는 지울 수 없습니다(DROP) — 그 작업이 만든 뷰만 지울 수 있습니다";
    static final String DROP_SEQUENCE = "남의 시퀀스는 지울 수 없습니다(DROP) — 그 작업이 만든 시퀀스만 지울 수 있습니다";
    static final String TRUNCATE = "남의 테이블은 비울 수 없습니다(TRUNCATE) — 그 작업이 만든 테이블만 비울 수 있습니다";
    static final String RENAME_TABLE = "남의 테이블은 이름을 바꿀 수 없습니다 — 그 작업이 만든 테이블만 바꿀 수 있습니다";
    static final String REPLACE_TABLE = "남의 테이블은 교체할 수 없습니다(CREATE OR REPLACE) — 새 테이블이면 CREATE TABLE을 쓰세요";
    static final String REPLACE_SEQUENCE = "남의 시퀀스는 교체할 수 없습니다(CREATE OR REPLACE) — 새 시퀀스면 CREATE SEQUENCE를 쓰세요";

    private SqlWriteClassifier() {}

    /** 분석과 분류를 한 번에 — 분석기가 거부한 문장은 그 사유로 REJECT. */
    public static WriteDecision decide(String sql, SqlDialect d, Function<DbObjectRef, DbObjectName> names,
                                       Ownership ownership, int maxUpdateDeleteRows, int maxInsertRows) {
        try {
            return classify(SqlWriteParser.parse(sql, d), names, ownership, maxUpdateDeleteRows, maxInsertRows);
        } catch (SqlWriteRejected e) {
            return WriteDecision.reject(e.getMessage());
        }
    }

    /**
     * @param names               문장의 이름을 그 연결의 기본 스키마·대소문자 규칙으로 정규화한다({@link DbObjectName#normalize})
     * @param maxUpdateDeleteRows 남의 테이블 UPDATE·DELETE 한 문장의 영향 행 상한
     * @param maxInsertRows       남의 테이블 INSERT(INSERT…SELECT 포함) 한 문장의 영향 행 상한
     */
    public static WriteDecision classify(WriteStatement st, Function<DbObjectRef, DbObjectName> names,
                                         Ownership ownership, int maxUpdateDeleteRows, int maxInsertRows) {
        Owner owner = new Owner(names, ownership);
        List<DbObjectRef> targets = st.targets();
        WriteDecision decision = switch (st.kind()) {
            case CREATE_TABLE -> st.orReplace() && !owner.ownsAll(DbObjectType.TABLE, targets)
                    ? WriteDecision.reject(REPLACE_TABLE) : WriteDecision.execute(null);
            case CREATE_SEQUENCE -> st.orReplace() && !owner.ownsAll(DbObjectType.SEQUENCE, targets)
                    ? WriteDecision.reject(REPLACE_SEQUENCE) : WriteDecision.execute(null);
            case CREATE_VIEW -> st.orReplace() && !owner.ownsAll(DbObjectType.VIEW, targets)
                    ? WriteDecision.defer(REPLACE_VIEW) : WriteDecision.execute(null);
            case CREATE_INDEX, COMMENT_ON -> WriteDecision.execute(null);
            case CREATE_UNIQUE_INDEX -> owner.ownsAll(DbObjectType.TABLE, targets)
                    ? WriteDecision.execute(null) : WriteDecision.defer(UNIQUE_INDEX);
            case ALTER_TABLE -> owner.ownsAll(DbObjectType.TABLE, targets)
                    ? WriteDecision.execute(null) : strictest(st.actions());
            case RENAME_TABLE -> ownedOrReject(owner, DbObjectType.TABLE, targets, RENAME_TABLE);
            case DROP_TABLE -> ownedOrReject(owner, DbObjectType.TABLE, targets, DROP_TABLE);
            case DROP_VIEW -> ownedOrReject(owner, DbObjectType.VIEW, targets, DROP_VIEW);
            case DROP_SEQUENCE -> ownedOrReject(owner, DbObjectType.SEQUENCE, targets, DROP_SEQUENCE);
            case TRUNCATE -> ownedOrReject(owner, DbObjectType.TABLE, targets, TRUNCATE);
            case INSERT -> WriteDecision.execute(owner.ownsAll(DbObjectType.TABLE, targets) ? null : maxInsertRows);
            case UPDATE, DELETE -> {
                if (owner.ownsAll(DbObjectType.TABLE, targets)) yield WriteDecision.execute(null);
                yield st.hasWhere() ? WriteDecision.execute(maxUpdateDeleteRows)
                        : WriteDecision.reject("남의 테이블은 조건 없이 " + (st.kind() == WriteKind.UPDATE ? "고칠" : "지울")
                        + " 수 없습니다(" + st.kind() + ") — WHERE를 붙이세요");
            }
            case CREATE_TRIGGER -> WriteDecision.defer(TRIGGER);
            case CREATE_ROUTINE -> WriteDecision.defer(ROUTINE);
        };
        return owner.explainDependent(decision, targets);
    }

    private static WriteDecision ownedOrReject(Owner owner, DbObjectType type, List<DbObjectRef> targets, String reason) {
        return owner.ownsAll(type, targets) ? WriteDecision.execute(null) : WriteDecision.reject(reason);
    }

    /** 남의 테이블 ALTER — 동작마다 판정해 가장 엄한 것(같으면 앞의 것). */
    private static WriteDecision strictest(List<AlterAction> actions) {
        WriteDecision worst = null;
        for (AlterAction a : actions) {
            WriteDecision d = forOthersTable(a);
            if (worst == null || d.verdict().compareTo(worst.verdict()) > 0) worst = d;
        }
        return worst == null ? WriteDecision.reject("ALTER TABLE 동작이 없습니다") : worst;
    }

    private static WriteDecision forOthersTable(AlterAction a) {
        return switch (a) {
            case ADD_COLUMN -> WriteDecision.execute(null);
            case ADD_COLUMN_NOT_NULL_NO_DEFAULT -> WriteDecision.defer(NOT_NULL_NO_DEFAULT);
            case ADD_COLUMN_WITH_CONSTRAINT, ADD_CONSTRAINT -> WriteDecision.defer(ADD_CONSTRAINT);
            case DROP_COLUMN -> WriteDecision.defer(DROP_COLUMN);
            case RENAME_COLUMN -> WriteDecision.defer(RENAME_COLUMN);
            case ALTER_COLUMN -> WriteDecision.defer(ALTER_COLUMN);
            case DROP_CONSTRAINT -> WriteDecision.defer(DROP_CONSTRAINT);
            case RENAME_TABLE -> WriteDecision.reject(RENAME_TABLE);
        };
    }

    /** 소유 판단 — 정규화한 이름과 종류가 소유 기록과 정확히 같을 때만, 의존 작업이 있으면 늘 아니다. */
    private record Owner(Function<DbObjectRef, DbObjectName> names, Ownership ownership) {

        boolean ownsAll(DbObjectType type, List<DbObjectRef> refs) {
            if (ownership.dependentNote() != null || refs.isEmpty()) return false;
            return refs.stream().allMatch(r -> ownership.owned().contains(new Ownership.OwnedObject(type, names.apply(r))));
        }

        /** 의존 작업 때문에 소유 예외를 끈 대상이 있으면 승인 대기·거부 사유에 그 이유를 붙인다. */
        WriteDecision explainDependent(WriteDecision decision, List<DbObjectRef> refs) {
            String note = ownership.dependentNote();
            if (note == null || decision.verdict() == WriteDecision.Verdict.EXECUTE) return decision;
            boolean suppressed = refs.stream().map(names).anyMatch(n -> ownership.owned().stream().anyMatch(o -> o.name().equals(n)));
            if (!suppressed) return decision;
            return new WriteDecision(decision.verdict(),
                    decision.reason() + " (의존 작업이 이 작업 브랜치를 기준으로 써서 소유 예외를 껐습니다: " + note + ")",
                    decision.rowCap());
        }
    }
}
