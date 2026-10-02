package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DbConnectionRepository extends JpaRepository<DbConnection, Long> {
    /** 레포의 전체 접속정보(REPO 먼저, 이름순). 보이는 범위 필터는 DbConnectionService.canView. */
    List<DbConnection> findByRepoCatalogIdOrderByScopeAscNameAsc(Long repoCatalogId);
}
