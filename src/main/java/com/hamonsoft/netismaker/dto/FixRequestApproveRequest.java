package com.hamonsoft.netismaker.dto;

/** 수정 요청 승인 바디 (스펙 2026-11-03 §6.2). implementationMode는 DIRECT|REVIEW, 비면 REVIEW. 바디 자체를 생략해도 된다. */
public record FixRequestApproveRequest(String implementationMode) {}
