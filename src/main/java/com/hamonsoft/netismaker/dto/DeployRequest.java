package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;

import java.util.List;

/** 배포/재배포 요청 body. 전체 또는 envVars가 null/빈 배열이면 기존 env 유지. */
public record DeployRequest(List<EnvVar> envVars) {}
