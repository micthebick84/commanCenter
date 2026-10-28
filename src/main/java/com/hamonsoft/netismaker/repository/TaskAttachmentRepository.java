package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /** 업로드 순서(= id 순) 그대로. */
    List<TaskAttachment> findByTaskIdOrderByIdAsc(Long taskId);

    /** 렌더링 대기 1건을 잠그고 id만 — 호출자 트랜잭션이 끝날 때까지 잠금이 유지된다 (스펙 2026-10-27 §6). */
    @Query(value = "SELECT id FROM com.task_attachment WHERE render_status = 'PENDING'"
            + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Long> findFirstPendingIdForUpdateSkipLocked();

    /** 멈춘 렌더링 회수용 — 해당 상태이고 시작 시각이 cutoff보다 이른 행. */
    List<TaskAttachment> findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus status, OffsetDateTime cutoff);
}
