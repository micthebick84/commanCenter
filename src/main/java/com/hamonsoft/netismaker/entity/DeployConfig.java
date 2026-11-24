package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 레포 카탈로그의 배포 설정 (스펙 2026-11-24-deploy-db-connection-select).
 * DB 슬롯(어디에 접속정보를 쓰는지)과 그 밖의 변수 목록. null 목록은 빈 목록으로 읽는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeployConfig(List<DbSlot> databases, List<Variable> variables) {

    public DeployConfig {
        databases = databases == null ? List.of() : copy(databases);
        variables = variables == null ? List.of() : copy(variables);
    }

    /** List.copyOf는 null 요소에서 NPE — 검증기가 IllegalArgumentException으로 거절하도록 null을 허용해 복사한다. */
    private static <T> List<T> copy(List<T> in) {
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    @JsonIgnore
    public boolean isEmpty() {
        return databases.isEmpty() && variables.isEmpty();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DbSlot(String slot, String label, List<DbType> dbTypes, Target target) {
        public DbSlot {
            dbTypes = dbTypes == null ? List.of() : copy(dbTypes);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(TargetKind kind, String path, LinkedHashMap<String, String> entries) {
        public Target {
            entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
        }
    }

    public enum TargetKind { ENV, FILE }

    /** filePath·fileEntry가 null이면 같은 이름의 env. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Variable(String key, String description, boolean secret, boolean required,
                           String format, String filePath, String fileEntry) {}
}
