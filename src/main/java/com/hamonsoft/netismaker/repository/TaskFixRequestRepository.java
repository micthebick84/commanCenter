package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.FixRequestKind;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskFixRequestRepository extends JpaRepository<TaskFixRequest, Long> {

    /** 작업 상세의 수정 요청 목록 — 최신순. */
    List<TaskFixRequest> findByTaskIdOrderByIdDesc(Long taskId);

    /** 종류까지 가른 승인 회차 수 — 수정 요청 상한은 FIX만 센다(기준 브랜치 반영은 상한 밖). */
    long countByTaskIdAndStatusAndKind(Long taskId, FixRequestStatus status, FixRequestKind kind);

    /** 종류별 전체 회차 수(상태 무관) — 회차 번호 매길 때. */
    long countByTaskIdAndKind(Long taskId, FixRequestKind kind);

    /** 상태가 같은 요청 중 가장 최근 것. */
    Optional<TaskFixRequest> findFirstByTaskIdAndStatusOrderByIdDesc(Long taskId, FixRequestStatus status);

    /** 이전 회차 본문 — 종류까지 가른다(수정 회차 프롬프트는 FIX 본문만 싣는다). */
    List<TaskFixRequest> findByTaskIdAndStatusAndKindAndRoundLessThanOrderByRoundAsc(Long taskId, FixRequestStatus status, FixRequestKind kind, Integer round);
}
