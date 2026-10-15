package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.ImplementationMode;

/**
 * 요청한 구현 방식을 실제로 쓸 수 있는지 판정 — 준비가 안 되면 한 단계 낮춘다(SDD → 리뷰 → 직접, 스펙 2026-10-15 §6.5).
 * 실패로 끝내지 않는 이유: 구현실패에는 재시도 외에 다른 길이 없고, 낮은 방식도 같은 계획을 구현한다.
 */
final class ImplementationModeResolver {

    static final String NO_PLUGIN = "플러그인 경로 없음";
    static final String NO_EXCLUDE = "info/exclude 기록 실패";
    static final String NO_PLAN = "계획 본문 없음";
    static final String PLAN_WRITE_FAILED = "계획 파일 쓰기 실패";
    static final String NO_TASK_HEADINGS = "계획에서 Task N 제목을 찾지 못함";

    /** 워커가 모은 준비 결과. taskHeadings는 SDD용으로 변환한 계획의 `Task N` 제목 수. */
    record Inputs(boolean pluginReady, boolean excludesReady, boolean hasPlan, boolean planFileWritten,
                  int taskHeadings) {}

    /** effective = 실제로 쓸 방식, fallbackReason = 낮췄을 때 이유(아니면 null). */
    record Resolution(ImplementationMode effective, String fallbackReason) {
        boolean fellBack() {
            return fallbackReason != null;
        }
    }

    private ImplementationModeResolver() {}

    static Resolution resolve(ImplementationMode requested, Inputs in) {
        ImplementationMode mode = requested == null ? ImplementationMode.DIRECT : requested;
        // 리뷰·SDD 모두 계획 파일을 읽는다 — 본문이 있는데 파일을 못 썼으면 직접까지 내려간다
        boolean planFileBroken = in.hasPlan() && !in.planFileWritten();
        if (mode == ImplementationMode.SDD) {
            String reason = !in.pluginReady() ? NO_PLUGIN
                    : !in.excludesReady() ? NO_EXCLUDE
                    : !in.hasPlan() ? NO_PLAN
                    : planFileBroken ? PLAN_WRITE_FAILED
                    : in.taskHeadings() == 0 ? NO_TASK_HEADINGS
                    : null;
            if (reason == null) return new Resolution(ImplementationMode.SDD, null);
            if (planFileBroken) return new Resolution(ImplementationMode.DIRECT, PLAN_WRITE_FAILED);
            return new Resolution(ImplementationMode.REVIEW, reason);
        }
        if (mode == ImplementationMode.REVIEW && planFileBroken) {
            return new Resolution(ImplementationMode.DIRECT, PLAN_WRITE_FAILED);
        }
        return new Resolution(mode, null);
    }
}
