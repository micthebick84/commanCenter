package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageTokens;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 집계 SQL 한 행: 서울 시각 버킷(시간·날짜 시작) × 구분 × 모델 × speed × 구간 기준값별 초과 여부의 토큰 합.
 * over.get(i)는 그 행 이벤트들의 promptTokens() > thresholds.get(i)다(같은 행의 이벤트는 모두 같은 값).
 */
public record UsageAggRow(LocalDateTime bucket, String category, String model, String speed, List<Boolean> over,
                          UsageTokens tokens) {}
