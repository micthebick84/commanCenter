package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /** 업로드 순서(= id 순) 그대로. */
    List<TaskAttachment> findByTaskIdOrderByIdAsc(Long taskId);
}
