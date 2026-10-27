package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 질문 세션 첨부 메타 (스펙 2026-09-13 §4). 다운로드 단건 조회는 findById + 세션 일치 검사(QuestionService). */
public interface QuestionAttachmentRepository extends JpaRepository<QuestionAttachment, Long> {

    /** 세션의 전체 첨부 — 업로드 순서(= id 순). 상세 뷰 조립용. */
    List<QuestionAttachment> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 등록 시(킥오프) 첨부 — turn_seq null. claim의 세션 레벨 attachments. */
    List<QuestionAttachment> findBySessionIdAndTurnSeqIsNullOrderByIdAsc(Long sessionId);

    /** 추가 질문 첨부 — turn_seq non-null. claim의 turns[].attachments(seq로 그룹). */
    List<QuestionAttachment> findBySessionIdAndTurnSeqIsNotNullOrderByIdAsc(Long sessionId);

    /** 렌더링 대기 1건을 잠그고 id만 — 호출자 트랜잭션이 끝날 때까지 잠금이 유지된다 (스펙 2026-10-27 §6). */
    @Query(value = "SELECT id FROM com.question_attachment WHERE render_status = 'PENDING'"
            + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Long> findFirstPendingIdForUpdateSkipLocked();

    /** 멈춘 렌더링 회수용 — 해당 상태이고 시작 시각이 cutoff보다 이른 행. */
    List<QuestionAttachment> findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus status, OffsetDateTime cutoff);
}
