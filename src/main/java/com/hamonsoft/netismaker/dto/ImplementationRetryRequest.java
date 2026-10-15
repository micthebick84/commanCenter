package com.hamonsoft.netismaker.dto;

/** 구현 재시도 바디 (스펙 2026-10-15 §5.5). implementationMode가 null·공백이면 지금 방식 유지. */
public record ImplementationRetryRequest(String implementationMode) {}
