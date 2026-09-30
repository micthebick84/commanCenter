package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskStatusHistoryRepository extends JpaRepository<TaskStatusHistory, Long> {

    /**
     * 최신순 이력 — limit은 DB LIMIT로 적용된다(전체를 가져와 자르지 않음).
     * at이 같은 행(같은 시각에 몰린 전이)은 id 내림차순으로 2차 정렬해 나중에 기록된 행이 먼저 온다 —
     * at 하나로만 정렬하면 동률의 상대 순서가 쿼리마다 달라질 수 있다.
     */
    List<TaskStatusHistory> findByTaskIdOrderByAtDescIdDesc(Long taskId, Limit limit);
}
