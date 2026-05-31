package com.hamonsoft.netismaker.entity;

import jakarta.validation.constraints.Size;

/**
 * 배포 task별 환경변수 (snapshot). task.env_vars (jsonb) 배열의 한 요소.
 *
 * secret=true는 UI 마스킹 표시 용도이며 저장/주입 동작에는 영향이 없다(평문 저장).
 *
 * 길이 제약은 요청 바인딩 시 {@code @Valid} 경로에서만 검증된다 (JPA 영속/직렬화에는 무영향).
 */
public record EnvVar(
        @Size(max = 200, message = "환경변수 key는 200자 이하여야 합니다") String key,
        @Size(max = 4000, message = "환경변수 value는 4000자 이하여야 합니다") String value,
        boolean secret) {
    public EnvVar {
        if (key == null) key = "";
        if (value == null) value = "";
    }
}
