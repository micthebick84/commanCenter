package com.hamonsoft.netismaker.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 구현 시 추출한 배포 환경변수 템플릿 항목. task.env_template (jsonb) 배열의 한 요소.
 *
 * 값은 담지 않는다 — 이름·설명·비밀 여부·필수 여부만 (스펙 2026-10-07 §3.1). 값은 배포 다이얼로그에서
 * 관리자가 넣고 task.env_vars(EnvVar)로 저장된다.
 */
public record EnvTemplateItem(String key, String description, boolean secret, boolean required) {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_DESCRIPTION = 300;
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public EnvTemplateItem {
        if (key == null) key = "";
        if (description == null) description = "";
    }

    /**
     * 워커 파싱 결과와 API 수신값에 공통으로 거는 정규화 — 잘못된 KEY·중복(처음 것만) 제거,
     * 설명 300자, 최대 50개. 항상 새 가변 목록을 돌려준다(JPA 컬렉션으로 그대로 set).
     */
    public static List<EnvTemplateItem> sanitize(List<EnvTemplateItem> raw) {
        List<EnvTemplateItem> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        for (EnvTemplateItem item : raw) {
            if (out.size() >= MAX_ITEMS) break;
            if (item == null) continue;
            String key = item.key().trim();
            if (!KEY.matcher(key).matches() || !seen.add(key)) continue;
            out.add(new EnvTemplateItem(key, clip(item.description().trim()), item.secret(), item.required()));
        }
        return out;
    }

    private static String clip(String s) {
        if (s.length() <= MAX_DESCRIPTION) return s;
        int end = Character.isHighSurrogate(s.charAt(MAX_DESCRIPTION - 1)) ? MAX_DESCRIPTION - 1 : MAX_DESCRIPTION;
        return s.substring(0, end);
    }
}
