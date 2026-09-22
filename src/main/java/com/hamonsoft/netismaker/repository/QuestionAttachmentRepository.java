package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.QuestionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 질문 세션 첨부 메타 (스펙 2026-09-13 §4). 다운로드 단건 조회는 findById + 세션 일치 검사(QuestionService). */
public interface QuestionAttachmentRepository extends JpaRepository<QuestionAttachment, Long> {

    /** 세션의 전체 첨부 — 업로드 순서(= id 순). 상세 뷰 조립용. */
    List<QuestionAttachment> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 등록 시(킥오프) 첨부 — turn_seq null. claim의 세션 레벨 attachments. */
    List<QuestionAttachment> findBySessionIdAndTurnSeqIsNullOrderByIdAsc(Long sessionId);

    /** 추가 질문 첨부 — turn_seq non-null. claim의 turns[].attachments(seq로 그룹). */
    List<QuestionAttachment> findBySessionIdAndTurnSeqIsNotNullOrderByIdAsc(Long sessionId);
}
