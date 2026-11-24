package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.DbSlotView;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.service.deployconfig.DeployConfigValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 배포 다이얼로그 미리 채움 규칙 (스펙 2026-10-07 §6.3). 순수 함수 — 조회(직전 배포 찾기)는 TaskService가 한다.
 *
 *   저장된 env가 있으면: 저장값 그대로 + 템플릿에만 있는 KEY를 빈 값으로. 직전 배포는 보지 않는다.
 *   없으면(첫 배포):    템플릿 KEY에 직전 배포 값을 채우고, 직전 배포에만 있던 KEY를 덧붙인다.
 * 비밀 여부는 출처끼리 OR. 빈 KEY는 모든 입력에서 무시하고 KEY는 trim한다.
 */
public final class DeployEnvSuggester {

    private DeployEnvSuggester() {}

    /** 배포 설정 없는 레포용 — 6인자 오버로드의 cfg=null과 같다. */
    public static DeployEnvSuggestionResponse suggest(List<EnvVar> saved, List<EnvTemplateItem> template,
                                                     List<EnvVar> previous, Long previousTaskId) {
        return legacy(saved, template, previous, previousTaskId, List.of());
    }

    /**
     * 카탈로그 배포 설정이 있으면(cfg != null) 행 = 카탈로그 변수(CATALOG) → 저장·템플릿 행 중 envNames 밖 (스펙 §7.2).
     * 직전 배포는 카탈로그 변수의 값에만 쓰고(저장 env가 하나도 없을 때), 직전 배포의 자유 행은 가져오지 않는다.
     * previousTaskId는 직전 배포 값을 하나라도 썼거나 DB 선택이 PREVIOUS일 때만 싣는다.
     */
    public static DeployEnvSuggestionResponse suggest(List<EnvVar> saved, List<EnvTemplateItem> template,
                                                     List<EnvVar> previous, Long previousTaskId,
                                                     DeployConfig cfg, List<DbSlotView> databases) {
        List<DbSlotView> dbs = databases == null ? List.of() : databases;
        if (cfg == null) return legacy(saved, template, previous, previousTaskId, dbs);

        Map<String, EnvTemplateItem> tpl = new LinkedHashMap<>();
        if (template != null) {
            for (EnvTemplateItem i : template) {
                if (i != null && !i.key().isBlank()) tpl.putIfAbsent(i.key().trim(), i);
            }
        }
        Map<String, EnvVar> savedByKey = byKey(saved);
        Map<String, EnvVar> prev = savedByKey.isEmpty() ? byKey(previous) : Map.of();
        Set<String> hidden = DeployConfigValidator.envNames(cfg);

        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean usedPrevious = dbs.stream()
                .anyMatch(d -> d.selected() != null && d.selected().from() == Source.PREVIOUS);
        for (Variable v : cfg.variables()) {
            if (v == null || v.key() == null || v.key().isBlank() || !seen.add(v.key())) continue;
            EnvVar s = savedByKey.get(v.key());
            EnvVar p = prev.get(v.key());
            boolean fromPrev = s == null && p != null && !p.value().isBlank();
            usedPrevious |= fromPrev;
            String value = s != null ? s.value() : fromPrev ? p.value() : "";
            boolean secret = v.secret() || (s != null && s.secret()) || (fromPrev && p.secret());
            rows.add(new Row(v.key(), value, secret, v.description() == null ? "" : v.description(), v.required(),
                    Source.CATALOG, fromPrev, v.format()));
        }
        for (EnvVar sv : savedByKey.values()) {
            if (hidden.contains(sv.key()) || seen.contains(sv.key())) continue;
            EnvTemplateItem t = tpl.get(sv.key());
            rows.add(new Row(sv.key(), sv.value(), sv.secret() || (t != null && t.secret()),
                    t == null ? "" : t.description(), t != null && t.required(), Source.SAVED, false));
        }
        for (Map.Entry<String, EnvTemplateItem> e : tpl.entrySet()) {
            if (hidden.contains(e.getKey()) || seen.contains(e.getKey()) || savedByKey.containsKey(e.getKey())) continue;
            EnvTemplateItem t = e.getValue();
            rows.add(new Row(e.getKey(), "", t.secret(), t.description(), t.required(), Source.TEMPLATE, false));
        }
        return new DeployEnvSuggestionResponse(rows, tpl.size(), usedPrevious ? previousTaskId : null, dbs);
    }

    private static DeployEnvSuggestionResponse legacy(List<EnvVar> saved, List<EnvTemplateItem> template,
                                                     List<EnvVar> previous, Long previousTaskId,
                                                     List<DbSlotView> databases) {
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
            return new DeployEnvSuggestionResponse(rows, tpl.size(), null, databases);
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
        return new DeployEnvSuggestionResponse(rows, tpl.size(), usedPrevious ? previousTaskId : null, databases);
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
