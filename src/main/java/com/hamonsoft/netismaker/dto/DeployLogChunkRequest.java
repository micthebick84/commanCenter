package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotNull;

/** 워커가 배포 중 올리는 증분 로그 청크. 상태를 바꾸지 않는다. */
public record DeployLogChunkRequest(@NotNull Integer seq, String content) {}
