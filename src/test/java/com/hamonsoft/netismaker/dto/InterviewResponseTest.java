package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.RefRepo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewResponseTest {

    @Test
    void plan_ready_event_carries_all_three_fields() {
        var ev = new PlanReadyEvent("# 설계", "# 플랜", "[{\"title\":\"A\"}]");
        assertThat(ev.designMarkdown()).isEqualTo("# 설계");
        assertThat(ev.planMarkdown()).isEqualTo("# 플랜");
        assertThat(ev.planJson()).isEqualTo("[{\"title\":\"A\"}]");
    }

    @Test
    void turn_view_maps_seq_role_kind_content() {
        // Phase 1 팩토리 시그니처: InterviewTurn.of(sessionId, seq, role, kind, content, replyToSeq)
        InterviewTurn t = InterviewTurn.of(1L, 2, "assistant", "question", "어떤 인증?", null);
        var tv = new InterviewResponse.TurnView(
                t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq(), t.getCreatedAt());
        assertThat(tv.seq()).isEqualTo(2);
        assertThat(tv.role()).isEqualTo("assistant");
        assertThat(tv.kind()).isEqualTo("question");
    }

    @Test
    void of_exposes_both_korean_status_and_english_statusName() {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "제목", "설명", "u1", List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", 7L);
        s.setStatus(InterviewStatus.AWAITING_INPUT);

        InterviewResponse r = InterviewResponse.of(s, List.of(), null);

        assertThat(r.status()).isEqualTo("입력대기");          // 한글 dbValue (표시용) — 유지
        assertThat(r.statusName()).isEqualTo("AWAITING_INPUT"); // 영문 enum name (로직용) — 신규
    }

    @Test
    void of_carries_kind_and_total_cost() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setTotalCostUsd(new java.math.BigDecimal("1.25"));
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.kind()).isEqualTo("QUESTION");
        assertThat(r.totalCostUsd()).isEqualByComparingTo("1.25");
    }

    @Test
    void of_carries_context_snapshot() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setContextTokens(76004L);
        q.setContextWindow(200000L);
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isEqualTo(76004L);
        assertThat(r.contextWindow()).isEqualTo(200000L);
    }

    // ── 질문 첨부·MCP id (스펙 2026-09-13 §5.1) ────────────────────────────────

    @Test
    void of_five_arg_carries_mcp_ids_kickoff_attachments_and_per_turn_attachments() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setMcpCatalogIds(new java.util.ArrayList<>(List.of(3L, 4L)));
        InterviewTurn t0 = InterviewTurn.of(1L, 0, "assistant", "question", "답", null);
        InterviewTurn t1 = InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0);
        var kick = new InterviewResponse.AttachmentView(11L, "설계.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100L);
        var turnAtt = new InterviewResponse.AttachmentView(12L, "로그.txt", null, 5L);

        InterviewResponse r = InterviewResponse.of(q, List.of(t0, t1), null,
                List.of(kick), java.util.Map.of(1, List.of(turnAtt)));

        assertThat(r.mcpCatalogIds()).containsExactly(3L, 4L);
        assertThat(r.attachments()).containsExactly(kick);
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(1).attachments()).containsExactly(turnAtt);
    }

    @Test
    void three_arg_of_and_six_arg_turn_view_keep_legacy_shape_with_empty_non_null_lists() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewTurn t = InterviewTurn.of(1L, 2, "assistant", "question", "어떤 인증?", null);
        InterviewResponse r = InterviewResponse.of(q, List.of(t), null);
        assertThat(r.mcpCatalogIds()).isNotNull().isEmpty();
        assertThat(r.attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        var tv = new InterviewResponse.TurnView(1, "user", "answer", "x", 0, t.getCreatedAt(), null);
        assertThat(tv.attachments()).isNotNull().isEmpty();
    }

    @Test
    void of_leaves_context_null_when_never_reported() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isNull();
        assertThat(r.contextWindow()).isNull();
    }

    @Test
    void carries_repo_catalog_id_and_db_ids_and_chips_are_filled_by_with() {
        InterviewSession s = InterviewSession.createQuestion("a/b", "main", "t", "q", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        s.setRepoCatalogId(1L);
        s.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        InterviewResponse r = InterviewResponse.of(s, java.util.List.of(), null);
        assertThat(r.repoCatalogId()).isEqualTo(1L);
        assertThat(r.dbConnectionIds()).containsExactly(7L);
        assertThat(r.dbConnections()).isEmpty();
        InterviewResponse filled = r.withDbConnections(java.util.List.of(new DbConnectionDto.Chip(7, "운영", "MYSQL")));
        assertThat(filled.dbConnections()).extracting(DbConnectionDto.Chip::name).containsExactly("운영");
        assertThat(filled.id()).isEqualTo(r.id());
    }

    // ── 참조 repo (스펙 2026-10-22 §4.3) ───────────────────────────────────────

    private static InterviewSession questionWithRefs(RefRepo... refs) {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setRefRepos(new ArrayList<>(List.of(refs)));
        return q;
    }

    private static final RefRepo FRESH = new RefRepo(11L, "옛앱", "grp/old", "https://gitlab.example/grp/old.git",
            "release/6.5", null, null, null);
    private static final RefRepo PINNED = new RefRepo(12L, "배치 서버", "grp/batch", "https://gitlab.example/grp/batch.git",
            "main", "a".repeat(40), "2026-10-22T10:00:00+09:00", null);
    private static final RefRepo FAILED = new RefRepo(13L, "공통 UI", "owner/ui", "https://github.com/owner/ui.git",
            "dev", null, null, "권한 없음");

    @Test
    void of_maps_session_ref_repos_to_views_in_order_without_internal_fields() {
        InterviewResponse r = InterviewResponse.of(questionWithRefs(FRESH, PINNED, FAILED), List.of(), null);

        assertThat(r.refRepos()).containsExactly(
                new InterviewResponse.RefRepoView(11L, "옛앱", "release/6.5", null, null, null),
                new InterviewResponse.RefRepoView(12L, "배치 서버", "main", "a".repeat(40), "2026-10-22T10:00:00+09:00", null),
                new InterviewResponse.RefRepoView(13L, "공통 UI", "dev", null, null, "권한 없음"));
    }

    @Test
    void of_with_attachments_overload_also_carries_ref_repos() {
        InterviewResponse r = InterviewResponse.of(questionWithRefs(PINNED), List.of(), null, List.of(), Map.of());
        assertThat(r.refRepos()).extracting(InterviewResponse.RefRepoView::catalogId).containsExactly(12L);
    }

    @Test
    void ref_repos_is_never_null_when_the_session_has_none() {
        InterviewSession none = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        assertThat(InterviewResponse.of(none, List.of(), null).refRepos()).isNotNull().isEmpty();
        assertThat(InterviewResponse.of(none, List.of(), null, List.of(), Map.of()).refRepos()).isNotNull().isEmpty();

        none.setRefRepos(null);   // 방어: 세션 쪽 값이 null이어도 응답은 []
        assertThat(InterviewResponse.of(none, List.of(), null).refRepos()).isNotNull().isEmpty();

        InterviewSession interview = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of(), "claude-opus-5", "high");
        assertThat(InterviewResponse.of(interview, List.of(), null).refRepos()).isNotNull().isEmpty();   // INTERVIEW 세션
    }

    @Test
    void direct_construction_normalizes_null_ref_repos_to_an_empty_list() {
        // 컴팩트 생성자가 "항상 non-null"을 지킨다 — of()를 거치지 않는 호출처도 null을 못 내보낸다
        InterviewResponse r = new InterviewResponse(1L, "o/r", "main", "t", "d", "큐", "QUEUED", null, null, null,
                "m", "e", List.of(), null, null, null, "QUESTION", null, null, null, null, null, null, null, null, null);
        assertThat(r.refRepos()).isNotNull().isEmpty();
        assertThat(r.mcpCatalogIds()).isEmpty();
    }

    @Test
    void with_db_connections_keeps_ref_repos() {
        InterviewResponse r = InterviewResponse.of(questionWithRefs(FRESH, PINNED), List.of(), null);

        InterviewResponse filled = r.withDbConnections(List.of(new DbConnectionDto.Chip(7, "운영", "MYSQL")));

        assertThat(filled.refRepos()).isEqualTo(r.refRepos()).hasSize(2);
        assertThat(filled.dbConnections()).hasSize(1);
    }

    @Test
    void ref_repos_serialize_as_an_array_with_exactly_the_fields_the_screen_needs() throws Exception {
        InterviewResponse r = InterviewResponse.of(questionWithRefs(FRESH, PINNED), List.of(), null);

        JsonNode root = new ObjectMapper()
                .registerModule(new JavaTimeModule()).valueToTree(r);

        JsonNode refs = root.get("refRepos");
        assertThat(refs.isArray()).isTrue();
        assertThat(refs).hasSize(2);
        List<String> names = new ArrayList<>();
        refs.get(0).fieldNames().forEachRemaining(names::add);
        // path·gitUrl은 화면이 안 쓰므로 내보내지 않는다. 값이 없는 필드도 null로 실어 TS 쪽 모양이 늘 같다
        assertThat(names).containsExactlyInAnyOrder("catalogId", "alias", "branch", "sha", "fetchedAt", "error");
        assertThat(refs.get(0).get("sha").isNull()).isTrue();
        assertThat(refs.get(1).get("sha").asText()).isEqualTo("a".repeat(40));
        assertThat(refs.get(1).get("branch").asText()).isEqualTo("main");
    }
}
