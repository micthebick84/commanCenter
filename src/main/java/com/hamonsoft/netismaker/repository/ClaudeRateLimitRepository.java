package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaudeRateLimitRepository extends JpaRepository<ClaudeRateLimit, String> {
    /** 표시 순서는 프론트(LIMIT_ORDER)가 정한다 — 여기선 결정적 순서만 보장. */
    List<ClaudeRateLimit> findAllByOrderByLimitTypeAsc();
}
