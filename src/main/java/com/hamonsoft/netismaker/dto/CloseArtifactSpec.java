package com.hamonsoft.netismaker.dto;

/**
 * CLOSE claim의 산출물 지시 (스펙 2026-10-14 §3). 모드가 REQUIRED이고 산출물 단계가 남았을 때만 실린다.
 * path = 카탈로그의 이미지 안 경로, null이면 워커가 후보 폴더에서 하나일 때만 자동 선택.
 */
public record CloseArtifactSpec(String path) {}
