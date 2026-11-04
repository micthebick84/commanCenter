package com.hamonsoft.netismaker.service;

/**
 * 워커가 next-task의 supports로 알린 기능. 모르는 기능이 필요한 구현대기는 그 워커에게 내주지 않는다 —
 * 구버전 jar는 모르는 필드를 버리고 엉뚱하게 구현해 버리기 때문이다. 예외는 dbWrite: 큐를 거르지 않고 쓰기 도구만 정한다.
 *
 * @param refRepos  참조 repo가 있는 구현대기를 받을 수 있다 (스펙 2026-10-24 §4.7)
 * @param fixRounds 수정 회차가 열린 구현대기를 받을 수 있다 — 구버전은 회차 필드를 버리고 base에서 -rN 브랜치를
 *                  새로 딴다 (스펙 2026-11-03 §7)
 * @param dbWrite   구현 단계 DB 쓰기 도구를 받을 수 있다 (스펙 2026-11-03 §6.1 조건 6) — 실행한 SQL을 PR 스크립트로 남기는 워커만.
 *                  큐 필터가 아니다: 모르는 워커도 작업은 받고, claim의 DB 연결이 모두 읽기 전용이 될 뿐이다
 */
public record WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite) {

    /** 모든 기능을 아는 워커 — 거르는 조건이 없는 전체 큐. */
    public static final WorkerCapabilities ALL = new WorkerCapabilities(true, true, true);

    /** dbWrite를 모르는 워커 — dbWrite가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds) {
        this(refRepos, fixRounds, false);
    }

    /** 거르는 조건이 하나라도 걸리는지. false면 전체 큐(JPQL)를 그대로 쓴다. dbWrite는 보지 않는다. */
    boolean needsFilteredQueue() {
        return !(refRepos && fixRounds);
    }
}
