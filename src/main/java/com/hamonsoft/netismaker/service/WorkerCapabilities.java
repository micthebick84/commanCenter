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
 * @param mockups   인터뷰 목업 파일(interviewMockups)과 '전달되지 않은 목업'(excludedMockupNumbers)을 아는 워커 (스펙 2026-11-03 §7).
 *                  모르면 목업을 고른(또는 제외 번호가 있는) 구현대기·디자인대기를 받지 못한다 — 구버전 jar는 모르는 필드를 버리고
 *                  목업 없이 구현해 버리기 때문이다
 * @param baseSync  기준 브랜치 반영 회차(kind=BASE_SYNC)가 열린 구현대기를 받을 수 있다 (스펙 2026-11-13) — 구버전 워커는
 *                  회차의 kind를 버리고 빈 수정 요청으로 Claude를 돌려 버린다
 * @param closeKeepBranch 병합하지 않고 종료(브랜치·PR 유지)하는 종료대기를 받을 수 있다 (스펙 2026-11-23 §4.4) — 구버전 워커는
 *                  모드를 몰라 그냥 병합해 버린다
 * @param deployOverrides 배포 설정이 있는 레포·고른 DB 연결이 있는 배포대기를 받을 수 있다 (스펙 2026-11-24) — 구버전 워커는
 *                  덮어쓰기 필드를 버리고 저장된 env로만 배포한다
 */
public record WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups,
                                 boolean baseSync, boolean closeKeepBranch, boolean deployOverrides) {

    /** 모든 기능을 아는 워커 — 거르는 조건이 없는 전체 큐. */
    public static final WorkerCapabilities ALL = new WorkerCapabilities(true, true, true, true, true, true, true);

    /** deployOverrides를 모르는 워커 — deployOverrides가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups,
                              boolean baseSync, boolean closeKeepBranch) {
        this(refRepos, fixRounds, dbWrite, mockups, baseSync, closeKeepBranch, false);
    }

    /** closeKeepBranch를 모르는 워커 — closeKeepBranch가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups, boolean baseSync) {
        this(refRepos, fixRounds, dbWrite, mockups, baseSync, false, false);
    }

    /** baseSync를 모르는 워커 — baseSync가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups) {
        this(refRepos, fixRounds, dbWrite, mockups, false, false, false);
    }

    /** mockups·baseSync를 모르는 워커 — mockups가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite) {
        this(refRepos, fixRounds, dbWrite, false, false, false, false);
    }

    /** dbWrite·mockups·baseSync를 모르는 워커 — dbWrite가 생기기 전 호출 호환. */
    public WorkerCapabilities(boolean refRepos, boolean fixRounds) {
        this(refRepos, fixRounds, false, false, false, false, false);
    }

    /** 거르는 조건이 하나라도 걸리는지. false면 전체 큐(JPQL)를 그대로 쓴다. dbWrite는 보지 않는다. */
    boolean needsFilteredQueue() {
        return !(refRepos && fixRounds && mockups && baseSync && closeKeepBranch && deployOverrides);
    }
}
