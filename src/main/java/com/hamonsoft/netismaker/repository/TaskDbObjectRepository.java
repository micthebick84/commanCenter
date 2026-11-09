package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.TaskDbObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 그 작업이 새로 만든 DB 객체 기록 (스펙 2026-11-03 §6.3). */
public interface TaskDbObjectRepository extends JpaRepository<TaskDbObject, Long> {

    /** 이 연결에서 이 작업이 만든 객체 전부 — 소유 판정에 쓴다. */
    List<TaskDbObject> findByConnectionIdAndTaskId(Long connectionId, Long taskId);

    /** 이 객체를 만든 작업(없으면 남의 객체). 이름은 방언 규칙으로 정규화한 값으로 찾는다. */
    Optional<TaskDbObject> findByConnectionIdAndSchemaNameAndObjectNameAndObjectType(
            Long connectionId, String schemaName, String objectName, DbObjectType objectType);

    /**
     * 연결의 소유 기록 전부 삭제 — 접속 대상이 바뀌면 옛 DB의 기록이 새 DB의 같은 이름 객체에 적용되지 않게 한다.
     * 반환 = 지운 행 수. 단일 DELETE 문이라 호출자의 트랜잭션이 없어도 돈다.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM TaskDbObject o WHERE o.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") Long connectionId);
}
