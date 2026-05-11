package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskStatusHistoryRepository extends JpaRepository<TaskStatusHistory, Long> {
    List<TaskStatusHistory> findByTaskIdOrderByAtDesc(Long taskId);
}
