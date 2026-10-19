package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestCommentRepository extends JpaRepository<RequestComment, Long> {
    List<RequestComment> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);

    /** 취소 가드: 관리자 답글(COMMENT)이 하나라도 있으면 접수 상태여도 취소할 수 없다. */
    boolean existsByRequestIdAndKindAndAuthorAdminTrue(Long requestId, RequestCommentKind kind);
}
