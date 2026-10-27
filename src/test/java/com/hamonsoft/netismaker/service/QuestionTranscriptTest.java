package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RefRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질문 대화 전문(.md) 렌더러 — "이 대화로 작업 등록" 때 작업 첨부로 남는다 (스펙 2026-10-24 §4.1).
 * 첫 질문은 세션 description에서 오고, 턴은 순서대로 역할 표시와 함께, 첨부는 이름만.
 */
class QuestionTranscriptTest {

    private InterviewSession q;

    private static InterviewSession question(long id, String title, String description) {
        InterviewSession s = InterviewSession.createQuestion("micthebick84/netis7.0", "main", title,
                description, "user1", List.of(), null, null);
        ReflectionTestUtils.setField(s, "id", id);
        s.setRepoAlias("Netis7.0");
        s.setStatus(InterviewStatus.AWAITING_INPUT);
        return s;
    }

    private static RefRepo ref(long catalogId, String alias, String branch, String sha, String error) {
        return new RefRepo(catalogId, alias, "acme/ref" + catalogId,
                "https://github.com/acme/ref" + catalogId + ".git", branch, sha,
                sha == null ? null : "2026-10-24T10:00:00+09:00", error);
    }

    private static QuestionAttachment attachment(Integer turnSeq, String name) {
        String message = turnSeq == null ? "create" : String.valueOf(turnSeq);
        return QuestionAttachment.create(9L, turnSeq, name, "question-9/" + message + "/1-" + name,
                "application/octet-stream", 10L, null, "user1");
    }

    @BeforeEach
    void setUp() {
        q = question(9L, "결재선 비교", "옛앱 결재선과 비교해 줘");
        q.setRefRepos(new ArrayList<>(List.of(
                ref(11L, "옛앱", "release/6.5", "a".repeat(40), null),
                ref(12L, "공통 UI", "main", null, "인증 실패"))));
    }

    @Test
    void header_has_title_target_and_refs_with_pinned_commit_or_not_received() {
        String md = QuestionTranscript.render(q, List.of(), List.of());

        assertThat(md).startsWith("# 질문 #9 대화 전문 — 결재선 비교\n")
                .contains("- 대상: Netis7.0 · main")
                .contains("- 참조 repo(질문 당시):\n  - 옛앱 · release/6.5@aaaaaaa\n  - 공통 UI · main — 받지 못함\n")
                .doesNotContain("a".repeat(8));   // 커밋은 앞 7자만
    }

    @Test
    void target_without_an_alias_falls_back_to_the_repo_path() {
        q.setRepoAlias(null);

        assertThat(QuestionTranscript.render(q, List.of(), List.of()))
                .contains("- 대상: micthebick84/netis7.0 · main");
    }

    @Test
    void no_refs_reads_none() {
        q.setRefRepos(new ArrayList<>());

        assertThat(QuestionTranscript.render(q, List.of(), List.of()))
                .contains("- 참조 repo(질문 당시): 없음\n")
                .doesNotContain("받지 못함");
    }

    @Test
    void first_question_comes_from_the_session_description_not_a_turn() {
        String md = QuestionTranscript.render(q, List.of(), List.of());

        assertThat(md).contains("## 첫 질문\n\n옛앱 결재선과 비교해 줘");
        assertThat(md.indexOf("## 첫 질문")).isLessThan(md.indexOf("## 대화"));
    }

    @Test
    void turns_follow_in_order_with_role_labels() {
        List<InterviewTurn> turns = List.of(
                InterviewTurn.of(9L, 0, "assistant", "question", "답", null),
                InterviewTurn.of(9L, 1, "user", "answer", "추가?", 0),
                InterviewTurn.of(9L, 2, "system", "note", "참조 repo 변경: ＋ X(main)", null),
                InterviewTurn.of(9L, 3, "assistant", "question", "답2", null));

        String md = QuestionTranscript.render(q, turns, List.of());

        assertThat(md).contains("### 답변 (턴 0)\n\n답\n")
                .contains("### 추가 질문 (턴 1)\n\n추가?\n")
                .contains("> 시스템 메모: 참조 repo 변경: ＋ X(main)")
                .contains("### 답변 (턴 3)\n\n답2\n");
        assertThat(md.indexOf("## 대화")).isLessThan(md.indexOf("### 답변 (턴 0)"));
        assertThat(md.indexOf("### 답변 (턴 0)")).isLessThan(md.indexOf("### 추가 질문 (턴 1)"));
        assertThat(md.indexOf("### 추가 질문 (턴 1)")).isLessThan(md.indexOf("> 시스템 메모:"));
        assertThat(md.indexOf("> 시스템 메모:")).isLessThan(md.indexOf("### 답변 (턴 3)"));
    }

    @Test
    void a_multi_line_system_note_stays_inside_its_quote() {
        List<InterviewTurn> turns = List.of(
                InterviewTurn.of(9L, 0, "system", "note", "답변 실패: 첫 줄\n둘째 줄", null));

        assertThat(QuestionTranscript.render(q, turns, List.of()))
                .contains("> 시스템 메모: 답변 실패: 첫 줄\n> 둘째 줄\n");
    }

    @Test
    void attachments_are_listed_by_name_only() {
        List<InterviewTurn> turns = List.of(
                InterviewTurn.of(9L, 0, "assistant", "question", "답", null),
                InterviewTurn.of(9L, 1, "user", "answer", "추가?", 0));
        List<QuestionAttachment> atts = List.of(
                attachment(null, "spec.pdf"), attachment(null, "표.xlsx"), attachment(1, "shot.png"));

        String md = QuestionTranscript.render(q, turns, atts);

        assertThat(md).contains("## 첫 질문\n\n옛앱 결재선과 비교해 줘\n\n첨부: spec.pdf, 표.xlsx\n")
                .contains("### 추가 질문 (턴 1)\n\n추가?\n\n첨부: shot.png\n")
                .doesNotContain("question-9/");
        // 답변 턴에는 첨부 줄이 없다
        assertThat(md.substring(md.indexOf("### 답변 (턴 0)"), md.indexOf("### 추가 질문 (턴 1)")))
                .doesNotContain("첨부:");
    }

    @Test
    void file_name_is_question_id_dialog_md() {
        assertThat(QuestionTranscript.fileName(9L)).isEqualTo("question-9-대화.md");
        assertThat(QuestionTranscript.CONTENT_TYPE).isEqualTo("text/markdown");
    }

    /** Review Focus 4 — 한 줄짜리 값의 줄바꿈이 머리·턴 구조를 깨지 못한다. */
    @Test
    void title_and_attachment_names_stay_on_one_line() {
        InterviewSession tricky = question(9L, "비교\n# 가짜 머리", "본문");
        tricky.setRefRepos(new ArrayList<>(List.of(ref(11L, "옛\r\n## 앱", "rel\nease", null, null))));
        List<InterviewTurn> turns = List.of(InterviewTurn.of(9L, 1, "user", "answer", "추가?", 0));
        List<QuestionAttachment> atts = List.of(attachment(null, "a\nb.pdf"), attachment(1, "c\r### d.png"));

        String md = QuestionTranscript.render(tricky, turns, atts);

        List<String> lines = md.lines().toList();
        assertThat(lines.get(0)).isEqualTo("# 질문 #9 대화 전문 — 비교 # 가짜 머리");
        assertThat(lines).filteredOn(l -> l.startsWith("# ")).containsExactly(lines.get(0));
        assertThat(lines).filteredOn(l -> l.startsWith("## "))
                .containsExactly("## 첫 질문", "## 대화");
        assertThat(lines).filteredOn(l -> l.startsWith("### "))
                .containsExactly("### 추가 질문 (턴 1)");
        assertThat(md).contains("첨부: a b.pdf\n")
                .contains("첨부: c ### d.png\n")
                .contains("  - 옛 ## 앱 · rel ease — 받지 못함\n");
    }
}
