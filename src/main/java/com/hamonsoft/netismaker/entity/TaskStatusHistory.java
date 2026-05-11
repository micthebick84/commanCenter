package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "task_status_history", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    private OffsetDateTime at;

    public static TaskStatusHistory log(Long taskId, TaskStatus from, TaskStatus to,
                                        String actorType, String actorId, String reason) {
        TaskStatusHistory h = new TaskStatusHistory();
        h.taskId = taskId;
        h.fromStatus = from == null ? null : from.dbValue();
        h.toStatus = to.dbValue();
        h.actorType = actorType;
        h.actorId = actorId;
        h.reason = reason;
        h.at = OffsetDateTime.now();
        return h;
    }
}
