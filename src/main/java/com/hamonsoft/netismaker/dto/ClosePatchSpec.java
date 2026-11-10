package com.hamonsoft.netismaker.dto;

/**
 * CLOSE claim의 부분 패치 지시 (스펙 2026-11-10). command·toolchain·artifact는 레포 카탈로그의 미리 빌드 설정,
 * baseSha는 병합 직전 기준 브랜치 끝(MERGED 보고 때 기록). 패치 모드가 REQUIRED이고 PATCH_BUILT 전일 때만 싣는다.
 */
public record ClosePatchSpec(String command, String toolchain, String artifact, String baseSha) {}
