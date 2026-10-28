package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.InterviewSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewClaimResponseTest {

    private InterviewSession session() {
        InterviewSession s = InterviewSession.create(
                "owner/repo", "main", "제목", "설명", "u1", List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", 7L);
        return s;
    }

    @Test
    void of_carries_accumulated_session_totalCostUsd_so_the_worker_guard_is_cumulative() {
        InterviewSession s = session();
        ReflectionTestUtils.setField(s, "totalCostUsd", new BigDecimal("4.95"));

        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(4.95);
    }

    @Test
    void of_defaults_totalCostUsd_to_zero_for_a_fresh_session() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(0.0);
    }

    @Test
    void attachments가_null이어도_빈_리스트로_정규화된다_계약은_non_null() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), null);
        assertThat(r.attachments()).isNotNull().isEmpty();
    }

    @Test
    void attachments는_id_파일명_절대경로_타입_크기를_나른다() {
        var ref = new InterviewClaimResponse.AttachmentRef(
                3L, "요구사항.pdf", "/abs/task-7/1-요구사항.pdf", "application/pdf", 1234L);
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of(ref));
        assertThat(r.attachments()).containsExactly(ref);
        assertThat(r.attachments().get(0).absolutePath()).startsWith("/abs/");
    }

    // ── 질문 첨부·attachmentRoot (스펙 2026-09-13 §5.1) ────────────────────────

    @Test
    void of_five_arg_carries_attachment_root_and_per_turn_attachments_non_null() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(q, "id", 1L);
        var t0 = com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "답", null);
        var t1 = com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0);
        var ref = new InterviewClaimResponse.AttachmentRef(12L, "로그.txt", "/abs/question-1/1/1-로그.txt",
                "text/plain", 5L, null);

        InterviewClaimResponse r = InterviewClaimResponse.of(q, List.of(t0, t1), List.of(),
                java.util.Map.of(1, List.of(ref)), "/abs/question-1");

        assertThat(r.attachmentRoot()).isEqualTo("/abs/question-1");
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(1).attachments()).containsExactly(ref);
    }

    @Test
    void three_arg_of_keeps_legacy_shape_null_root_and_empty_turn_attachments() {
        var t0 = com.hamonsoft.netismaker.entity.InterviewTurn.of(7L, 0, "assistant", "question", "q", null);
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(t0), List.of());
        assertThat(r.attachmentRoot()).isNull();
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
    }

    @Test
    void turn_normalizes_null_attachments_to_empty_and_attachment_ref_defaults_extracted_path_to_null() {
        var turn = new InterviewClaimResponse.Turn(1, "user", "answer", "x", 0, null);
        assertThat(turn.attachments()).isNotNull().isEmpty();
        var legacy = new InterviewClaimResponse.AttachmentRef(3L, "a.pdf", "/abs/a.pdf", "application/pdf", 1L);
        assertThat(legacy.extractedTextPath()).isNull();
        var office = new InterviewClaimResponse.AttachmentRef(4L, "a.docx", "/abs/a.docx", null, 1L, "/abs/a.docx.txt");
        assertThat(office.extractedTextPath()).isEqualTo("/abs/a.docx.txt");
    }

    @Test
    void of_carries_session_kind_as_enum_name() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(q, "id", 1L); // of()가 sessionId(long)로 unbox — 미영속 세션은 id set 필요
        InterviewClaimResponse r = InterviewClaimResponse.of(q, List.of(), List.of());
        assertThat(r.kind()).isEqualTo("QUESTION");

        InterviewSession i = InterviewSession.create("o/r", "main", "t", "d", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(i, "id", 2L);
        assertThat(InterviewClaimResponse.of(i, List.of(), List.of()).kind()).isEqualTo("INTERVIEW");
    }

    @Test
    void of_carries_git_url_and_host() {
        InterviewSession s = InterviewSession.createQuestion("g/sub/p", "main", "t", "q", "admin",
                List.of(), "claude-opus-5", "high");
        s.setGitUrl("https://gitlab.hamon.vip/g/sub/p.git");
        ReflectionTestUtils.setField(s, "id", 5L);
        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());
        assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/g/sub/p.git");
        assertThat(r.repoHost()).isEqualTo("gitlab");
        assertThat(r.repoRef().path()).isEqualTo("g/sub/p");
    }

    @Test
    void db_ids_only_for_question_and_with_db_fills_refs() throws Exception {
        InterviewSession q = InterviewSession.createQuestion("a/b", "main", "t", "q", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        ReflectionTestUtils.setField(q, "id", 7L); // of()가 sessionId(long)로 unbox — 미영속 세션은 id set 필요
        q.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        InterviewClaimResponse c = InterviewClaimResponse.of(q, java.util.List.of(), java.util.List.of());
        assertThat(c.dbConnectionIds()).containsExactly(7L);
        assertThat(c.dbConnections()).isEmpty();
        assertThat(c.dbNotices()).isEmpty();

        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "Pw-9");
        InterviewClaimResponse filled = c.withDb(java.util.List.of(ref), java.util.List.of("참고"));
        assertThat(filled.dbConnections()).containsExactly(ref);
        assertThat(filled.dbNotices()).containsExactly("참고");
        assertThat(filled.sessionId()).isEqualTo(c.sessionId());
        assertThat(filled.toString()).doesNotContain("Pw-9");
        // 워커 계약: JSON에는 평문 password가 실려야 한다(내부 API 전용)
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(filled);
        assertThat(json).contains("\"password\":\"Pw-9\"").contains("\"serverName\":\"db-7\"");
    }

    @Test
    void interview_kind_carries_session_db_ids() {
        InterviewSession i = InterviewSession.create("a/b", "main", "t", "d", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        ReflectionTestUtils.setField(i, "id", 8L);
        i.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        assertThat(InterviewClaimResponse.of(i, java.util.List.of(), java.util.List.of()).dbConnectionIds()).containsExactly(7L);
    }

    // ── 질문 세션 참조 repo (스펙 2026-10-22 §4.4) ──────────────────────────────

    private static final String SHA = "c".repeat(40);

    private static InterviewClaimResponse.ClaimRefRepo claimRef(long catalogId, String sha) {
        return new InterviewClaimResponse.ClaimRefRepo(catalogId, "참조" + catalogId, "acme/ref" + catalogId,
                "https://github.com/acme/ref" + catalogId + ".git", "github", "main",
                "/root/_refs/7/" + catalogId, sha);
    }

    @Test
    void of_has_no_ref_repos_and_null_normalizes_to_empty() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of());

        assertThat(r.refRepos()).isNotNull().isEmpty();
        assertThat(r.withRefRepos(null).refRepos()).isNotNull().isEmpty();
    }

    @Test
    void withRefRepos_fills_only_the_refs() {
        InterviewClaimResponse base = InterviewClaimResponse.of(session(), List.of(), List.of());

        InterviewClaimResponse filled = base.withRefRepos(List.of(claimRef(11L, null), claimRef(12L, SHA)));

        assertThat(filled.refRepos()).containsExactly(claimRef(11L, null), claimRef(12L, SHA));
        assertThat(filled).usingRecursiveComparison().ignoringFields("refRepos").isEqualTo(base);
    }

    @Test
    void withDb_keeps_the_ref_repos_and_withRefRepos_keeps_the_db_parts() {
        var dbRef = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "Pw-9");
        InterviewClaimResponse base = InterviewClaimResponse.of(session(), List.of(), List.of());

        InterviewClaimResponse refsThenDb = base.withRefRepos(List.of(claimRef(11L, SHA)))
                .withDb(List.of(dbRef), List.of("참고"));
        InterviewClaimResponse dbThenRefs = base.withDb(List.of(dbRef), List.of("참고"))
                .withRefRepos(List.of(claimRef(11L, SHA)));

        for (InterviewClaimResponse r : List.of(refsThenDb, dbThenRefs)) {
            assertThat(r.refRepos()).containsExactly(claimRef(11L, SHA));
            assertThat(r.dbConnections()).containsExactly(dbRef);
            assertThat(r.dbNotices()).containsExactly("참고");
        }
    }

    @Test
    void refRepos_serialize_as_an_array_with_the_worker_contract_field_names() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of())
                .withRefRepos(List.of(claimRef(11L, null), claimRef(12L, SHA)));

        JsonNode refs = new ObjectMapper().findAndRegisterModules().valueToTree(r).get("refRepos");

        assertThat(refs.isArray()).isTrue();
        assertThat(refs.size()).isEqualTo(2);
        java.util.Set<String> names = new java.util.TreeSet<>();
        refs.get(0).fieldNames().forEachRemaining(names::add);
        // 인터뷰 서비스(TS)가 이 이름 그대로 읽는다 — 아직 안 받은 sha도 null로 필드는 있다
        assertThat(names).containsExactlyInAnyOrder(
                "catalogId", "alias", "githubRepo", "gitUrl", "repoHost", "branch", "dir", "sha");
        assertThat(refs.get(0).get("sha").isNull()).isTrue();
        assertThat(refs.get(1).get("catalogId").asLong()).isEqualTo(12L);
        assertThat(refs.get(1).get("githubRepo").asText()).isEqualTo("acme/ref12");
        assertThat(refs.get(1).get("repoHost").asText()).isEqualTo("github");
        assertThat(refs.get(1).get("dir").asText()).isEqualTo("/root/_refs/7/12");
        assertThat(refs.get(1).get("sha").asText()).isEqualTo(SHA);
    }

    // ── 작업 인터뷰에서 참조를 모두 뺐는지 (스펙 2026-10-24 §4.4·§5.2) ──────────────────────

    @Test
    void refsCleared_is_false_by_default_and_withRefsCleared_changes_only_it() {
        InterviewClaimResponse base = InterviewClaimResponse.of(session(), List.of(), List.of());

        InterviewClaimResponse cleared = base.withRefsCleared(true);

        assertThat(base.refsCleared()).isFalse();
        assertThat(cleared.refsCleared()).isTrue();
        assertThat(cleared).usingRecursiveComparison().ignoringFields("refsCleared").isEqualTo(base);
        assertThat(cleared.withRefsCleared(false)).isEqualTo(base);
    }

    @Test
    void withDb_and_withRefRepos_keep_refsCleared() {
        var dbRef = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "Pw-9");
        InterviewClaimResponse cleared = InterviewClaimResponse.of(session(), List.of(), List.of()).withRefsCleared(true);

        assertThat(cleared.withDb(List.of(dbRef), List.of("참고")).refsCleared()).isTrue();
        assertThat(cleared.withRefRepos(List.of()).refsCleared()).isTrue();
        assertThat(cleared.withRefRepos(List.of(claimRef(11L, SHA))).withDb(List.of(), List.of()).refsCleared()).isTrue();
    }

    @Test
    void refsCleared_is_always_serialized_as_a_boolean() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InterviewClaimResponse base = InterviewClaimResponse.of(session(), List.of(), List.of());

        assertThat(mapper.writeValueAsString(base.withRefsCleared(true))).contains("\"refsCleared\":true");
        JsonNode plain = mapper.valueToTree(base);
        assertThat(plain.get("refsCleared").isBoolean()).isTrue();
        assertThat(plain.get("refsCleared").asBoolean()).isFalse();
    }

    @Test
    void a_claim_without_refs_serializes_an_empty_refRepos_array() {
        JsonNode json = new ObjectMapper().findAndRegisterModules()
                .valueToTree(InterviewClaimResponse.of(session(), List.of(), List.of()));

        assertThat(json.has("refRepos")).isTrue();
        assertThat(json.get("refRepos").isArray()).isTrue();
        assertThat(json.get("refRepos").size()).isZero();
    }

    // ── 첨부 슬라이드 그림 경로 (스펙 2026-10-27 §7) ──────────────────────

    @Test
    void attachment_ref의_슬라이드_필드는_JSON에_계약_이름으로_실린다() {
        var ref = new InterviewClaimResponse.AttachmentRef(5L, "발표.pptx", "/abs/발표.pptx", null, 10L,
                "/abs/발표.pptx.txt", List.of("/abs/발표.pptx.render/slide-001.png"), 12,
                List.of("/abs/발표.pptx.render/media/image1.png"), null, 77L);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(ref);

        assertThat(json.get("slideImagePaths").get(0).asText()).isEqualTo("/abs/발표.pptx.render/slide-001.png");
        assertThat(json.get("slideTotalPages").asInt()).isEqualTo(12);
        assertThat(json.get("mediaImagePaths").get(0).asText()).isEqualTo("/abs/발표.pptx.render/media/image1.png");
        assertThat(json.has("renderNote")).isTrue();
        assertThat(json.get("renderNote").isNull()).isTrue();
        assertThat(json.get("inheritedFromQuestionId").asLong()).isEqualTo(77L);
    }

    @Test
    void 구_생성자와_null_목록은_빈_목록으로_정규화된다() {
        var five = new InterviewClaimResponse.AttachmentRef(1L, "a.txt", "/abs/a.txt", "text/plain", 1L);
        var six = new InterviewClaimResponse.AttachmentRef(2L, "a.docx", "/abs/a.docx", null, 1L, "/abs/a.docx.txt");
        var nulls = new InterviewClaimResponse.AttachmentRef(3L, "b.pptx", "/abs/b.pptx", null, 1L, null,
                null, null, null, "슬라이드 그림 없음: 변환 실패", null);

        for (var r : List.of(five, six, nulls)) {
            assertThat(r.slideImagePaths()).isNotNull().isEmpty();
            assertThat(r.mediaImagePaths()).isNotNull().isEmpty();
            assertThat(r.slideTotalPages()).isNull();
            assertThat(r.inheritedFromQuestionId()).isNull();
        }
        assertThat(five.renderNote()).isNull();
        assertThat(six.extractedTextPath()).isEqualTo("/abs/a.docx.txt");
        assertThat(nulls.renderNote()).isEqualTo("슬라이드 그림 없음: 변환 실패");
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(five);
        assertThat(json.get("slideImagePaths").isArray()).isTrue();
        assertThat(json.get("mediaImagePaths").size()).isZero();
    }
}
