package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskFixRequestRepository extends JpaRepository<TaskFixRequest, Long> {

    /** 작업 상세의 수정 요청 목록 — 최신순. */
    List<TaskFixRequest> findByTaskIdOrderByIdDesc(Long taskId);

    /** 상태별 개수 — 대기 중인 요청이 이미 있는지, 승인된 회차가 몇 번째인지 셀 때. */
    long countByTaskIdAndStatus(Long taskId, FixRequestStatus status);

    /** 상태가 같은 요청 중 가장 최근 것. */
    Optional<TaskFixRequest> findFirstByTaskIdAndStatusOrderByIdDesc(Long taskId, FixRequestStatus status);

    /** 이전 회차(round 미만)의 요청 본문 — 다음 회차 구현 프롬프트에 앞선 수정 내용을 싣는다. 오래된 회차부터. */
    List<TaskFixRequest> findByTaskIdAndStatusAndRoundLessThanOrderByRoundAsc(Long taskId, FixRequestStatus status, Integer round);
}
