package com.hamonsoft.netismaker.dto;

/** 종료 승인 바디 (선택). keepBranch=true면 병합하지 않고 브랜치·PR을 남긴 채 종료한다. 바디가 없거나 null이면 병합 종료. */
public record ApproveCloseRequest(Boolean keepBranch) {}
