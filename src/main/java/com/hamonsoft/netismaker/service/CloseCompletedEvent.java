package com.hamonsoft.netismaker.service;

/** 작업종료 전이가 기록됐다 — 커밋 뒤 등록자 메일의 신호 (스펙 2026-10-14 §7). */
public record CloseCompletedEvent(Long taskId) {}
