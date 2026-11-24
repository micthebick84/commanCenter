package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 배포/재배포 요청 body. 전체 또는 envVars가 null/빈 배열이면 기존 env 유지.
 * dbSelection: null이면 저장된 선택 유지, 아니면 검증 뒤 그대로 저장(connectionId null = 고르지 않음).
 */
public record DeployRequest(List<@Valid EnvVar> envVars, List<@Valid DeployDbChoice> dbSelection) {

    public DeployRequest(List<EnvVar> envVars) {
        this(envVars, null);
    }
}
