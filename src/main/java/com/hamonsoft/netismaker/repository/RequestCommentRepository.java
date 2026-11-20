package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RequestCommentRepository extends JpaRepository<RequestComment, Long> {
    List<RequestComment> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);

    /** 취소 가드: 관리자 답글(COMMENT)이 하나라도 있으면 접수 상태여도 취소할 수 없다. */
    boolean existsByRequestIdAndKindAndAuthorAdminTrue(Long requestId, RequestCommentKind kind);

    /** 개발 요청 취소 가드: 작성자가 아닌 사람의 답글이 하나라도 있으면 취소할 수 없다. */
    boolean existsByRequestIdAndKindAndAuthorIdNot(Long requestId, RequestCommentKind kind, String authorId);

    long countByRequestIdAndKind(Long requestId, RequestCommentKind kind);

    /** 목록용 묶음 개수 — 해당 종류의 답글이 없는 요청은 결과에 없다. */
    @Query("select c.requestId as requestId, count(c) as total from RequestComment c"
            + " where c.requestId in :ids and c.kind = :kind group by c.requestId")
    List<RequestCountView> countByRequestIdsAndKind(@Param("ids") Collection<Long> ids,
                                                    @Param("kind") RequestCommentKind kind);
}
