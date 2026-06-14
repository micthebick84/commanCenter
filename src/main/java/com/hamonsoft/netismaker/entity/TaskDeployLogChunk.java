package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "task_deploy_log_chunk", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDeployLogChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static TaskDeployLogChunk of(Long taskId, int seq, String content) {
        TaskDeployLogChunk c = new TaskDeployLogChunk();
        c.taskId = taskId;
        c.seq = seq;
        c.content = content == null ? "" : content;
        c.createdAt = OffsetDateTime.now();
        return c;
    }
}
