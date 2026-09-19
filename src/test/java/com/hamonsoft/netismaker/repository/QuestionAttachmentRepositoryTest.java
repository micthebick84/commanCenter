package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V23 com.question_attachment — turn_seq null(등록분)/non-null(턴별) 분리 조회 + 세션 삭제 CASCADE. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionAttachmentRepositoryTest {

    @Autowired private QuestionAttachmentRepository attachmentRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach void clean() {
        attachmentRepo.deleteAll();
        sessionRepo.deleteAll();
    }

    private InterviewSession savedQuestion() {
        return sessionRepo.save(InterviewSession.createQuestion("acme/widgets", "main", "제목", "질문?",
                "user1", List.of(), "claude-opus-5", "high"));
    }

    @Test
    void 등록분과_턴별_첨부를_분리해_id순으로_조회한다() {
        InterviewSession s = savedQuestion();
        long sid = s.getId();
        attachmentRepo.save(QuestionAttachment.create(sid, null, "설계.docx", "question-" + sid + "/create/1-설계.docx",
                "application/octet-stream", 1234L, "question-" + sid + "/create/1-설계.docx.txt", "user1"));
        attachmentRepo.save(QuestionAttachment.create(sid, 3, "로그.txt", "question-" + sid + "/3/1-로그.txt",
                "text/plain", 99L, null, "user1"));
        attachmentRepo.save(QuestionAttachment.create(sid, 3, "화면.png", "question-" + sid + "/3/2-화면.png",
                null, 7L, null, "user1"));

        assertThat(attachmentRepo.findBySessionIdOrderByIdAsc(sid)).hasSize(3)
                .extracting(QuestionAttachment::getOriginalFilename)
                .containsExactly("설계.docx", "로그.txt", "화면.png");
        List<QuestionAttachment> kickoff = attachmentRepo.findBySessionIdAndTurnSeqIsNullOrderByIdAsc(sid);
        assertThat(kickoff).hasSize(1);
        assertThat(kickoff.get(0).getExtractedTextPath()).endsWith("1-설계.docx.txt");
        assertThat(kickoff.get(0).getCreatedAt()).isNotNull();
        List<QuestionAttachment> perTurn = attachmentRepo.findBySessionIdAndTurnSeqIsNotNullOrderByIdAsc(sid);
        assertThat(perTurn).hasSize(2).allSatisfy(a -> assertThat(a.getTurnSeq()).isEqualTo(3));
        assertThat(perTurn.get(1).getContentType()).isNull();
    }

    @Test
    void 세션이_지워지면_첨부_행도_CASCADE로_사라진다() {
        InterviewSession s = savedQuestion();
        attachmentRepo.save(QuestionAttachment.create(s.getId(), null, "a.txt",
                "question-" + s.getId() + "/create/1-a.txt", "text/plain", 1L, null, "user1"));
        assertThat(attachmentRepo.count()).isEqualTo(1);
        sessionRepo.delete(s);
        sessionRepo.flush();
        assertThat(attachmentRepo.count()).isZero();
    }

    @Test
    void 세션의_mcp_catalog_ids는_jsonb로_왕복된다() {
        InterviewSession s = savedQuestion();
        s.setMcpCatalogIds(new java.util.ArrayList<>(List.of(5L, 9L)));
        sessionRepo.saveAndFlush(s);
        InterviewSession reloaded = sessionRepo.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getMcpCatalogIds()).containsExactly(5L, 9L);
        // 기본값 — 컬럼 DEFAULT '[]'와 엔티티 초기자 모두 빈 리스트
        InterviewSession fresh = sessionRepo.findById(savedQuestion().getId()).orElseThrow();
        assertThat(fresh.getMcpCatalogIds()).isNotNull().isEmpty();
    }
}
