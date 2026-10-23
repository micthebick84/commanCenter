package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** claim 응답의 DB 접속정보 보강 (스펙 2026-10-02 §5.4) — QUESTION만, 보강 실패가 claim을 깨지 않는다. */
class InterviewWorkerControllerClaimDbTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, mock(InterviewStreamService.class), db);

    private static InterviewClaimResponse claimOf(boolean question, List<Long> dbIds) {
        InterviewSession s = question
                ? InterviewSession.createQuestion("a/b", "main", "t", "q", "user1", new ArrayList<>(), "claude-sonnet-5", "medium")
                : InterviewSession.create("a/b", "main", "t", "q", "user1", new ArrayList<>(), "claude-sonnet-5", "medium");
        ReflectionTestUtils.setField(s, "id", 7L); // of()가 sessionId(long)로 unbox — 미영속 세션은 id set 필요
        s.setDbConnectionIds(new ArrayList<>(dbIds));
        return InterviewClaimResponse.of(s, List.of(), List.of());
    }

    @Test
    void question_claim_with_db_ids_is_enriched() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L))));
        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(7L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        assertThat(controller.claim("w1").getBody().dbConnections()).containsExactly(ref);
    }

    @Test
    void no_db_ids_skips_lookup() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of())));
        controller.claim("w1");
        verify(db, never()).resolveForClaim(any());
    }

    @Test
    void claim_survives_db_resolution_failure() {
        // Review Focus 3: claim 트랜잭션은 이미 커밋됨 — 여기서 500이면 세션이 RUNNING에 묶인다
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L))));
        when(db.resolveForClaim(any())).thenThrow(new RuntimeException("db down"));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).isEmpty();
        assertThat(body.dbNotices()).containsExactly("DB 접속정보를 불러오지 못해 DB 도구 없이 답합니다");
    }

    @Test
    void interview_claim_with_db_ids_is_enriched() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(false, List.of(7L))));
        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(7L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnectionIds()).containsExactly(7L);
        assertThat(body.dbConnections()).containsExactly(ref);
    }

    @Test
    void interview_claim_survives_db_resolution_failure() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(false, List.of(7L))));
        when(db.resolveForClaim(any())).thenThrow(new RuntimeException("db down"));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).isEmpty();
        assertThat(body.dbNotices()).containsExactly("DB 접속정보를 불러오지 못해 DB 도구 없이 진행합니다");
    }

    // ── 참조 repo는 DB 보강을 거쳐도 그대로 실린다 (스펙 2026-10-22 §4.4) ───────────────────────

    private static final InterviewClaimResponse.ClaimRefRepo REF = new InterviewClaimResponse.ClaimRefRepo(
            11L, "참조", "acme/ref", "https://github.com/acme/ref.git", "github", "main", "/root/_refs/7/11", null);

    @Test
    void question_claim_keeps_reference_repos_when_db_is_enriched() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L)).withRefRepos(List.of(REF))));
        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(7L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).containsExactly(ref);
        assertThat(body.refRepos()).containsExactly(REF);
    }

    @Test
    void question_claim_keeps_reference_repos_when_db_resolution_fails() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L)).withRefRepos(List.of(REF))));
        when(db.resolveForClaim(any())).thenThrow(new RuntimeException("db down"));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).isEmpty();
        assertThat(body.refRepos()).containsExactly(REF);
    }
}
