package com.hamonsoft.netismaker.entity;

/**
 * 배포 task별 환경변수 (snapshot). task.env_vars (jsonb) 배열의 한 요소.
 *
 * secret=true는 UI 마스킹 표시 용도이며 저장/주입 동작에는 영향이 없다(평문 저장).
 */
public record EnvVar(String key, String value, boolean secret) {
    public EnvVar {
        if (key == null) key = "";
        if (value == null) value = "";
    }
}
