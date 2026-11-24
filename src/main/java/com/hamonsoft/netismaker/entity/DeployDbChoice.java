package com.hamonsoft.netismaker.entity;

/** 배포 때 DB 슬롯마다 고른 연결. connectionId가 null이면 "고르지 않음". */
public record DeployDbChoice(String slot, Long connectionId) {}
