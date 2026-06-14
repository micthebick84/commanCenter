package com.hamonsoft.netismaker.service;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 작업 등록 시 선택 가능한 모델 + effort의 단일 진실원천(허용 맵·기본값·검증). */
public final class ModelEffortPolicy {

    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    public static final String DEFAULT_EFFORT = "high";

    private static final List<String> FULL = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> LIMITED = List.of("low", "medium", "high");

    /** 모델 → 허용 effort. (claude-api 레퍼런스: Haiku만 low/medium/high) */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "claude-opus-4-8", FULL,
            "claude-opus-4-7", FULL,
            "claude-sonnet-4-6", FULL,
            "claude-haiku-4-5", LIMITED);

    private ModelEffortPolicy() {}

    public static String resolveModel(String model) {
        return (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }

    public static String resolveEffort(String effort) {
        return (effort == null || effort.isBlank()) ? DEFAULT_EFFORT : effort;
    }

    /** allowlist + effort×model 호환성. 위반 시 400. resolve로 보정한 값으로 호출할 것. */
    public static void validate(String model, String effort) {
        List<String> allowed = ALLOWED.get(model);
        if (allowed == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "지원하지 않는 모델입니다: " + model);
        }
        if (!allowed.contains(effort)) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    model + "은(는) effort '" + effort + "'를 지원하지 않습니다 (허용: " + allowed + ")");
        }
    }
}
