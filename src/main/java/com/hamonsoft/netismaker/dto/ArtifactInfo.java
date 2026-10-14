package com.hamonsoft.netismaker.dto;

/** 보관한 종료 산출물 — 워커 업로드 응답·조회 (스펙 2026-10-14 §5). API·워커가 함께 쓴다. */
public record ArtifactInfo(String name, long sizeBytes, String sha256) {}
