package com.hamonsoft.netismaker.dto;

/** 보관한 부분 패치 zip — 워커 업로드 응답·조회 (스펙 2026-11-10). */
public record PatchInfo(String name, long sizeBytes, String sha256, int fileCount) {}
