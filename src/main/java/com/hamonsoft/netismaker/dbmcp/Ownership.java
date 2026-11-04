package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;

import java.util.Set;

/**
 * 이 작업이 그 연결에 만든 객체(소유 기록, 스펙 2026-11-03 §6.3).
 *
 * @param dependentNote 다른 열린 작업이 이 작업 브랜치를 기준으로 쓰면 그 설명(예 "작업 #25") — 있으면 소유가 없는 것으로 보고,
 *                      승인 대기·거부 사유에 덧붙인다
 */
public record Ownership(Set<OwnedObject> owned, String dependentNote) {

    public record OwnedObject(DbObjectType type, DbObjectName name) {
    }

    public Ownership {
        owned = owned == null ? Set.of() : Set.copyOf(owned);
        dependentNote = dependentNote == null || dependentNote.isBlank() ? null : dependentNote;
    }

    public static Ownership none() {
        return new Ownership(Set.of(), null);
    }
}
