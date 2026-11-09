package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 구현 단계 DB 쓰기로 그 작업이 새로 만든 테이블·뷰·시퀀스 (스펙 2026-11-03 §6.3). "그 작업의 테이블"은 이 기록만 본다 —
 * 그 밖의 모든 객체(레거시, 다른 작업이 만든 것)는 남의 것이다. 연결·작업이 지워지면 DB가 같이 지운다.
 * 스키마·이름은 방언 규칙으로 정규화한 값이다(DbObjectName).
 */
@Entity
@Table(name = "task_db_object", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDbObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "schema_name", nullable = false, length = 128)
    private String schemaName;

    @Column(name = "object_name", nullable = false, length = 128)
    private String objectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 20)
    private DbObjectType objectType;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TaskDbObject of(Long connectionId, String schemaName, String objectName, DbObjectType objectType,
                                  Long taskId) {
        TaskDbObject o = new TaskDbObject();
        o.connectionId = connectionId;
        o.schemaName = schemaName;
        o.objectName = objectName;
        o.objectType = objectType;
        o.taskId = taskId;
        o.createdAt = OffsetDateTime.now();
        return o;
    }
}
