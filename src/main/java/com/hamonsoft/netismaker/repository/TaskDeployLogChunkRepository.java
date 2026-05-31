package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskDeployLogChunkRepository extends JpaRepository<TaskDeployLogChunk, Long> {
    List<TaskDeployLogChunk> findByTaskIdOrderBySeqAsc(Long taskId);
    void deleteByTaskId(Long taskId);
}
