package com.hamonsoft.netismaker.dbmcp;

/**
 * 쓰기 문장 판정 (스펙 2026-11-03 §6.2).
 *
 * @param reason 승인 대기·거부의 사유와 대안(한국어). 바로 실행은 null
 * @param rowCap 바로 실행할 DML의 영향 행 상한 — 남의 테이블에만, 그 밖은 null
 */
public record WriteDecision(Verdict verdict, String reason, Integer rowCap) {

    public enum Verdict { EXECUTE, DEFER, REJECT }

    static WriteDecision execute(Integer rowCap) {
        return new WriteDecision(Verdict.EXECUTE, null, rowCap);
    }

    static WriteDecision defer(String reason) {
        return new WriteDecision(Verdict.DEFER, reason, null);
    }

    static WriteDecision reject(String reason) {
        return new WriteDecision(Verdict.REJECT, reason, null);
    }
}
