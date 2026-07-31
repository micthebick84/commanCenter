package com.hamonsoft.netismaker.service;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 작업 등록 시 선택 가능한 모델 + effort의 단일 진실원천(허용 맵·기본값·검증). */
public final class ModelEffortPolicy {

    public static final String DEFAULT_MODEL = "claude-opus-5";
    public static final String DEFAULT_EFFORT = "high";

    /**
     * ultracode는 일부러 뺐다 — 실수로 누락된 게 아니다.
     *
     * claude CLI(2.1.220)는 `--effort ultracode`를 정식 별칭으로 인식하고(내부 맵
     * {ultracode: "xhigh"}), 잘못된 값과 달리 "Unknown --effort value" 경고도 안 낸다.
     * 하지만 헤드리스(`claude -p`)에서 xhigh와의 관측 가능한 차이가 없다(2026-07-29 실측):
     *   · init 이벤트의 툴 목록 30개가 xhigh와 완전 동일 (Workflow/Task는 xhigh에도 이미 있음)
     *   · 서브에이전트 팬아웃 0건, workflows/·wf_* 디스크 흔적 0건
     *   · 프롬프트 키워드 트리거는 isHumanTypedPrompt 게이트라 -p에서 원천 무효
     * 즉 목록에 두면 관리자가 "더 센 모드"로 믿고 고르지만 실행은 xhigh와 구분되지 않는다.
     *
     * 실제로 켜려면 게이트가 `settings.ultracode && 워크플로우 활성 && effort=="xhigh"`이므로
     * `--effort xhigh --settings '{"ultracode":true}'` 조합을 워커에 넣어야 한다(미검증).
     * 그 전에 워커 잔존 silent-loss부터 닫을 것 — 팬아웃은 보고 유실 표면을 N배로 키운다.
     */
    private static final List<String> FULL = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> LIMITED = List.of("low", "medium", "high");

    /**
     * 모델 → 허용 effort. Claude 5 제품군 + Haiku 4.5 (claude-api 레퍼런스 기준).
     * Haiku만 low/medium/high — 나머지는 xhigh/max까지.
     *
     * 여기서 뺀 구세대 모델(opus-4-8/4-7, sonnet-4-6)도 CLI에서는 여전히 유효하다.
     * 즉 이 맵은 "새로 고를 수 있는 목록"이고, 과거 작업에 박제된 모델 값은
     * 검증을 타지 않으므로 그대로 실행된다(배포 Dockerfile 생성 등).
     */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "claude-fable-5", FULL,
            "claude-opus-5", FULL,
            "claude-sonnet-5", FULL,
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
