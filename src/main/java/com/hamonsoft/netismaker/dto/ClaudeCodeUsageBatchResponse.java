package com.hamonsoft.netismaker.dto;

/** received는 건너뛴 것을 포함한 원래 이벤트 수. */
public record ClaudeCodeUsageBatchResponse(int received, int inserted, int updated, int skipped) {}
