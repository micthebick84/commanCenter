package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 배포 다이얼로그 미리 채움 규칙 (스펙 2026-10-07 §6.3). 순수 함수 — 조회(직전 배포 찾기)는 TaskService가 한다.
 *
 *   저장된 env가 있으면: 저장값 그대로 + 템플릿에만 있는 KEY를 빈 값으로. 직전 배포는 보지 않는다.
 *   없으면(첫 배포):    템플릿 KEY에 직전 배포 값을 채우고, 직전 배포에만 있던 KEY를 덧붙인다.
 * 비밀 여부는 출처끼리 OR. 빈 KEY는 모든 입력에서 무시하고 KEY는 trim한다.
 */
public final class DeployEnvSuggester {

    private DeployEnvSuggester() {}

    public static DeployEnvSuggestionResponse suggest(List<EnvVar> saved, List<EnvTemplateItem> template,
                                                     List<EnvVar> previous, Long previousTaskId) {
        Map<String, EnvTemplateItem> tpl = new LinkedHashMap<>();
        if (template != null) {
            for (EnvTemplateItem i : template) {
                if (i != null && !i.key().isBlank()) tpl.putIfAbsent(i.key().trim(), i);
            }
        }
        Map<String, EnvVar> savedByKey = byKey(saved);
        List<Row> rows = new ArrayList<>();

        if (!savedByKey.isEmpty()) {
            for (EnvVar v : savedByKey.values()) {
                EnvTemplateItem t = tpl.get(v.key());
                rows.add(new Row(v.key(), v.value(), v.secret() || (t != null && t.secret()),
                        t == null ? "" : t.description(), t != null && t.required(), Source.SAVED, false));
            }
            for (Map.Entry<String, EnvTemplateItem> e : tpl.entrySet()) {
                if (savedByKey.containsKey(e.getKey())) continue;
                EnvTemplateItem t = e.getValue();
                rows.add(new Row(e.getKey(), "", t.secret(), t.description(), t.required(), Source.TEMPLATE, false));
            }
            return new DeployEnvSuggestionResponse(rows, tpl.size(), null);
        }

        Map<String, EnvVar> prev = byKey(previous);
        boolean usedPrevious = false;
        for (Map.Entry<String, EnvTemplateItem> e : tpl.entrySet()) {
            EnvTemplateItem t = e.getValue();
            EnvVar p = prev.get(e.getKey());
            boolean fromPrev = p != null && !p.value().isBlank();
            usedPrevious |= fromPrev;
            rows.add(new Row(e.getKey(), fromPrev ? p.value() : "", t.secret() || (p != null && p.secret()),
                    t.description(), t.required(), Source.TEMPLATE, fromPrev));
        }
        for (EnvVar p : prev.values()) {
            if (tpl.containsKey(p.key())) continue;
            usedPrevious = true;   // 직전 배포에서 KEY를 가져왔다
            boolean hasValue = !p.value().isBlank();
            rows.add(new Row(p.key(), hasValue ? p.value() : "", p.secret(), "", false, Source.PREVIOUS, hasValue));
        }
        return new DeployEnvSuggestionResponse(rows, tpl.size(), usedPrevious ? previousTaskId : null);
    }

    /** 빈 KEY를 버리고 KEY를 trim, 같은 KEY는 처음 것만 — 입력 순서 유지. */
    private static Map<String, EnvVar> byKey(List<EnvVar> vars) {
        Map<String, EnvVar> out = new LinkedHashMap<>();
        if (vars == null) return out;
        for (EnvVar v : vars) {
            if (v == null || v.key().isBlank()) continue;
            String key = v.key().trim();
            out.putIfAbsent(key, new EnvVar(key, v.value(), v.secret()));
        }
        return out;
    }
}
