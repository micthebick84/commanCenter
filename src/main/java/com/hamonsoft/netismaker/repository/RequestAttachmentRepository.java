package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RequestAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 요청 게시판 개발 요청 첨부 메타. */
public interface RequestAttachmentRepository extends JpaRepository<RequestAttachment, Long> {

    List<RequestAttachment> findByRequestIdOrderByOrdinalAsc(Long requestId);

    long countByRequestId(Long requestId);

    Optional<RequestAttachment> findByIdAndRequestId(Long id, Long requestId);
}
