package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * 질문 세션의 읽기 전용 참조 repo 1개 (스펙 2026-10-22 §3.2). interview_session.ref_repos (jsonb) 배열의 한 요소.
 *
 * 카탈로그에서 고른 시점의 별칭·경로·URL·브랜치를 박제한다(TaskMcpSpec과 같은 방식) — 카탈로그를 고치거나
 * 지워도 세션의 참조는 그대로 남는다. sha·fetchedAt·error는 인터뷰 서비스가 답변 보고로 알려 주는 런타임 상태다.
 * fetchedAt은 ISO-8601 문자열(OffsetDateTime.toString()) — JSONB 매핑에 날짜 타입을 쓰지 않기 위해서다.
 * 값은 불변이라 바꾸려면 새 인스턴스를 돌려받는다. error에는 자격증명을 가린 문구만 담는다(GitRemotes.mask).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefRepo(
        Long catalogId,
        String alias,
        String path,
        String gitUrl,
        String branch,
        String sha,
        String fetchedAt,
        String error
) {

    /** 같은 참조인지 — 카탈로그 id가 같고 브랜치도 같다. branch가 blank(null 포함)면 카탈로그 id만 비교한다. */
    public boolean matches(Long catalogId, String branch) {
        if (!Objects.equals(this.catalogId, catalogId)) return false;
        return branch == null || branch.isBlank() || branch.equals(this.branch);
    }

    /**
     * 처음 받은 커밋으로 고정한 사본 (스펙 §3.2·§4.5). sha는 한 번 정해지면 바뀌지 않는다 — 이미 있으면 새 값은 무시한다.
     * 성공 보고이므로 error는 비운다(이미 고정된 참조의 낡은 오류도 지운다).
     * 이미 고정됐고 오류도 없으면 바꿀 것이 없어 this를 돌려준다.
     */
    public RefRepo pinned(String sha, String fetchedAt) {
        boolean alreadyPinned = this.sha != null && !this.sha.isBlank();
        if (alreadyPinned && error == null) return this;
        return alreadyPinned
                ? new RefRepo(catalogId, alias, path, gitUrl, branch, this.sha, this.fetchedAt, null)
                : new RefRepo(catalogId, alias, path, gitUrl, branch, sha, fetchedAt, null);
    }

    /** 준비 실패 사유를 기록한 사본. 이미 고정된 커밋·시각은 건드리지 않는다. */
    public RefRepo withError(String error) {
        return new RefRepo(catalogId, alias, path, gitUrl, branch, sha, fetchedAt, error);
    }

    /** 시스템 메모·로그·프롬프트에 쓰는 이름 — 별칭(브랜치). */
    public String label() {
        return alias + "(" + branch + ")";
    }
}
