package com.hamonsoft.netismaker.dbmcp;

import java.util.List;

/**
 * 분석한 쓰기 문장 한 개.
 *
 * @param targets       바뀌는 객체 — DROP·TRUNCATE는 나열한 전부, CREATE INDEX·COMMENT ON은 그 테이블, 트리거·루틴은 빈 목록
 * @param actions       ALTER_TABLE만 채운다(적힌 순서)
 * @param hasWhere      UPDATE·DELETE의 최상위 WHERE 여부
 * @param renameTo      테이블 이름 변경(RENAME_TABLE 문장이나 ALTER TABLE … RENAME TO)의 새 이름, 없으면 null
 * @param executableSql 실행할 문장 — 끝 ';' 하나(와 그 뒤 공백·주석)를 뗀 문장. 트리거·루틴은 원문 그대로(PL/SQL은 END;가 필요하다)
 */
public record WriteStatement(WriteKind kind, List<DbObjectRef> targets, List<AlterAction> actions,
                             boolean ifNotExists, boolean orReplace, boolean hasWhere,
                             DbObjectRef renameTo, String executableSql) {

    public WriteStatement {
        targets = List.copyOf(targets);
        actions = List.copyOf(actions);
    }
}
