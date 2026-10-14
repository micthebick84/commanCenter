package com.hamonsoft.netismaker.entity;

import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * 작업 종료(kind=CLOSE) 단계. 선언 순서 = 실행 순서 = task.close_progress 기록 순서
 * (스펙 2026-10-13 §4, 2026-10-14 §3). 산출물 단계는 close_artifact_mode가 REQUIRED인 작업에만 필요하다.
 */
public enum CloseStep {
    MERGED, UNDEPLOYED, ARTIFACT_UPLOADED, NOTE_WRITTEN, BRANCH_DELETED;

    /** task.close_artifact_mode 값. null = 해당 없음(기능 꺼짐·레포 꺼짐·이 기능 전 승인). */
    public static final String ARTIFACT_REQUIRED = "REQUIRED";
    public static final String ARTIFACT_SKIPPED = "SKIPPED";

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

    /** 이 작업이 작업종료가 되려면 끝나야 하는 단계. 산출물 단계는 모드가 REQUIRED일 때만. */
    public static EnumSet<CloseStep> required(String artifactMode) {
        EnumSet<CloseStep> s = EnumSet.allOf(CloseStep.class);
        if (!ARTIFACT_REQUIRED.equals(artifactMode)) s.remove(ARTIFACT_UPLOADED);
        return s;
    }

    public static boolean allDone(String csv, String artifactMode) {
        return parse(csv).containsAll(required(artifactMode));
    }
}
