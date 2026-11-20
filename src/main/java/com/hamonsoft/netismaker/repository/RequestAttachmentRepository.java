package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RequestAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 요청 게시판 개발 요청 첨부 메타. */
public interface RequestAttachmentRepository extends JpaRepository<RequestAttachment, Long> {

    List<RequestAttachment> findByRequestIdOrderByOrdinalAsc(Long requestId);

    long countByRequestId(Long requestId);

    /** 목록용 묶음 개수 — 첨부가 없는 요청은 결과에 없다. */
    @Query("select a.requestId as requestId, count(a) as total from RequestAttachment a"
            + " where a.requestId in :ids group by a.requestId")
    List<RequestCountView> countByRequestIds(@Param("ids") Collection<Long> ids);

    Optional<RequestAttachment> findByIdAndRequestId(Long id, Long requestId);
}
