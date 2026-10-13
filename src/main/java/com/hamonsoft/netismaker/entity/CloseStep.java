package com.hamonsoft.netismaker.entity;

import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * 작업 종료(kind=CLOSE)의 4단계. 끝난 단계는 task.close_progress에 콤마 목록으로 쌓이고,
 * 재claim한 워커는 이 목록에 있는 단계를 건너뛴다(스펙 2026-10-13 §4).
 */
public enum CloseStep {
    MERGED, UNDEPLOYED, NOTE_WRITTEN, BRANCH_DELETED;

    /** null·빈 값은 빈 집합, 모르는 이름은 무시(구버전·수동 편집 방어). */
    public static EnumSet<CloseStep> parse(String csv) {
        EnumSet<CloseStep> out = EnumSet.noneOf(CloseStep.class);
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String name = part.trim();
            for (CloseStep s : values()) {
                if (s.name().equals(name)) out.add(s);
            }
        }
        return out;
    }

    /** 중복 없이 enum 선언 순서로 다시 쓴다. */
    public static String append(String csv, CloseStep step) {
        EnumSet<CloseStep> set = parse(csv);
        set.add(step);
        return set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    public static boolean allDone(String csv) {
        return parse(csv).containsAll(EnumSet.allOf(CloseStep.class));
    }
}
