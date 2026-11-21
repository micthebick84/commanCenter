package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.service.route.RouteDecision;

import java.util.List;

/** 질문 레포 추천 응답 (스펙 2026-11-20 §6.1). state는 RouteState 이름, routeId는 기록 저장에 실패하면 null. */
public record QuestionRouteResponse(Long routeId, String state, Long recommendedRepoCatalogId,
                                    List<RouteDecision.Candidate> candidates, List<Long> recentRepoCatalogIds,
                                    List<Long> eligibleRepoCatalogIds, Boolean dbNeeded) {}
