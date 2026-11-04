package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.util.SecretMasks;
import com.hamonsoft.netismaker.util.StackSecretEnv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 앱 로그 본문에서 비밀 값을 가린다 (스펙 2026-11-03 §4 비밀 가리기). 스트림(접속) 하나에 하나씩 만들어 줄마다 쓴다.
 * 가리는 순서: 작업 env의 비밀 값 → API 프로세스 env의 스택 비밀 → URL 안 자격증명 → 맨 토큰
 * (뒤 둘은 공용 유틸 {@link SecretMasks#maskUrlAndTokens}).
 * 값 바꾸기는 정규식이 아니라 {@link String#replace(CharSequence, CharSequence)}(문자 그대로)로 한다 —
 * 비밀번호에 `$`·`\`·`(`가 들어 있어도 안전하고, 후보를 긴 값부터 바꿔 한 값이 다른 값의 일부여도 긴 쪽이 통째로 가려진다.
 * 한계: env로 주지 않은 비밀(레포 설정 파일의 비밀번호 등)은 가려지지 않는다(스펙 §8).
 */
public final class SecretMasker {

    /** 가린 자리에 쓰는 표시. */
    static final String MASK = "••••";

    /** 작업 env 비밀 값의 하한 — 더 짧은 값은 흔한 문자열과 겹쳐 오탐이 많다. */
    private static final int MIN_ENV_SECRET_LENGTH = 4;

    /** 여러 줄 값을 줄로 나누는 기준 — `\r`은 어느 쪽 줄바꿈이든 후보에 남기지 않는다. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

    /** 중복을 뺀, 길이 내림차순 후보. 바뀌지 않는다. */
    private final List<String> candidates;

    private SecretMasker(List<String> candidates) {
        this.candidates = candidates;
    }

    /**
     * 후보를 모아 마스커를 만든다. null 인자는 빈 것으로 본다.
     * <ul>
     *   <li>작업 env 중 `secret`이고 4자 이상인 값. 값이 여러 줄이면 줄마다(4자 이상인 것) 따로도 후보로 넣는다 —
     *       로그는 줄 단위로 오므로 여러 줄 값 전체는 한 줄에 나오지 않는다.</li>
     *   <li>API 프로세스 env 중 이름이 {@link StackSecretEnv#NAMES}와 같고(대소문자 무시) 값이 비어 있지 않은 것.
     *       배포 앱이 스택과 같은 비밀번호를 비밀 표시 없이 받았을 때를 막는다.</li>
     * </ul>
     * 두 갈래 모두 공백뿐인 값은 후보가 아니다 — 후보가 되면 로그의 모든 공백(들여쓰기)이 가려진다.
     */
    public static SecretMasker of(List<EnvVar> envVars, Map<String, String> processEnv) {
        Set<String> found = new LinkedHashSet<>();
        if (envVars != null) {
            for (EnvVar v : envVars) {
                if (v == null || !v.secret()) continue;
                addCandidate(found, v.value(), MIN_ENV_SECRET_LENGTH);
                for (String line : LINE_BREAKS.split(v.value())) {
                    addCandidate(found, line, MIN_ENV_SECRET_LENGTH);
                }
            }
        }
        if (processEnv != null) {
            for (Map.Entry<String, String> e : processEnv.entrySet()) {
                String name = e.getKey();
                if (name != null && StackSecretEnv.NAMES.stream().anyMatch(name::equalsIgnoreCase)) {
                    addCandidate(found, e.getValue(), 1);
                }
            }
        }
        List<String> sorted = new ArrayList<>(found);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length())); // List.sort는 안정 정렬 — 같은 길이는 들어온 순서
        return new SecretMasker(List.copyOf(sorted));
    }

    /** 비어 있지 않고(공백뿐인 값 제외) minLength자 이상인 값만 후보로 넣는다. */
    private static void addCandidate(Set<String> found, String value, int minLength) {
        if (value != null && !value.isBlank() && value.length() >= minLength) found.add(value);
    }

    /** 후보를 문자 그대로 {@value #MASK}로 바꾼 뒤 URL 자격증명·맨 토큰을 가린다. null은 null. */
    public String mask(String text) {
        if (text == null) return null;
        String masked = text;
        for (String candidate : candidates) {
            masked = masked.replace(candidate, MASK);
        }
        return SecretMasks.maskUrlAndTokens(masked);
    }
}
