package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.dto.DeployOverrides;
import com.hamonsoft.netismaker.dto.DeployOverrides.FileOverride;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.service.deployconfig.DeployValueTemplates.DbValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 저장된 환경변수 + 배포 설정 + 고른 DB 연결로 최종 env·파일 덮어쓰기를 만드는 순수 함수 (설계 §7.3).
 * 오류는 예외가 아니라 {@link DeployOverrides#failed(String)}로 돌려준다.
 */
public final class DeployOverrideAssembler {

    private DeployOverrideAssembler() {
    }

    /** 복호화된 DB 연결. toString에는 비밀번호가 나가지 않는다(DbValues가 가림). */
    public record ResolvedDb(long id, String name, DbType dbType, boolean enabled, DbValues values) {
        @Override
        public String toString() {
            return "ResolvedDb[id=" + id + ", name=" + name + ", dbType=" + dbType + ", enabled=" + enabled + "]";
        }
    }

    public static DeployOverrides assemble(DeployConfig cfg, List<EnvVar> saved, List<DeployDbChoice> selection,
                                           Function<Long, Optional<ResolvedDb>> resolver) {
        Map<String, Long> chosen = new LinkedHashMap<>();
        if (selection != null) {
            for (DeployDbChoice c : selection) {
                if (c == null || c.slot() == null) {
                    continue;
                }
                boolean known = cfg.databases().stream().anyMatch(s -> c.slot().equals(s.slot()));
                if (!known && c.connectionId() != null) {
                    return DeployOverrides.failed("배포 DB 자리 '" + c.slot()
                            + "'이 카탈로그 배포 설정에 없습니다 — 배포 창에서 다시 고르세요");
                }
                chosen.put(c.slot(), c.connectionId());
            }
        }

        Map<String, EnvVar> env = new LinkedHashMap<>();
        Map<String, String> savedValues = new LinkedHashMap<>();
        if (saved != null) {
            for (EnvVar e : saved) {
                if (e == null || e.key().isBlank() || e.value().isBlank()) {
                    continue;
                }
                env.put(e.key(), e);
                savedValues.put(e.key(), e.value());
            }
        }
        Map<String, LinkedHashMap<String, String>> files = new LinkedHashMap<>();

        for (Variable v : cfg.variables()) {
            String input = savedValues.get(v.key());
            if (input == null) {
                continue;
            }
            String value = DeployValueTemplates.wrapVariable(v.format(), input);
            boolean toFile = v.filePath() != null && v.fileEntry() != null;
            if (toFile) {
                env.remove(v.key());
                files.computeIfAbsent(v.filePath(), k -> new LinkedHashMap<>()).put(v.fileEntry(), value);
            } else {
                putEnv(env, v.key(), value, v.secret());
            }
        }

        List<String> summaries = new ArrayList<>();
        for (DbSlot slot : cfg.databases()) {
            Long id = chosen.get(slot.slot());
            if (id == null) {
                continue;
            }
            Optional<ResolvedDb> found = resolver.apply(id);
            String prefix = "배포 DB '" + slot.label() + "': ";
            if (found.isEmpty()) {
                return DeployOverrides.failed(prefix + "연결 #" + id + "를 찾을 수 없습니다(삭제·복호화 불가)");
            }
            ResolvedDb db = found.get();
            if (!db.enabled()) {
                return DeployOverrides.failed(prefix + "연결 '" + db.name() + "'이 비활성입니다");
            }
            if (!slot.dbTypes().contains(db.dbType())) {
                return DeployOverrides.failed(prefix + "연결 '" + db.name() + "'(" + db.dbType()
                        + ")은 이 자리의 DB 종류가 아닙니다");
            }
            DbValues values = db.values();
            boolean fileTarget = slot.target().kind() == TargetKind.FILE;
            for (Map.Entry<String, String> t : slot.target().entries().entrySet()) {
                String filled = DeployValueTemplates.fillDb(t.getValue(), values);
                if (fileTarget) {
                    files.computeIfAbsent(slot.target().path(), k -> new LinkedHashMap<>()).put(t.getKey(), filled);
                } else {
                    boolean secret = DeployValueTemplates.placeholders(t.getValue()).stream()
                            .anyMatch(p -> p.startsWith("password"));
                    putEnv(env, t.getKey(), filled, secret);
                }
            }
            summaries.add(slot.label() + " = " + db.name() + " (" + db.dbType() + " " + values.host() + ":"
                    + values.port() + "/" + values.database() + ") → "
                    + (fileTarget ? slot.target().path() : "env") + " " + slot.target().entries().size() + "항목");
        }

        List<FileOverride> fileList = new ArrayList<>();
        files.forEach((path, entries) -> fileList.add(new FileOverride(path, entries)));
        return new DeployOverrides(new ArrayList<>(env.values()), fileList,
                summaries.isEmpty() ? null : String.join("; ", summaries), null);
    }

    private static void putEnv(Map<String, EnvVar> env, String key, String value, boolean secret) {
        EnvVar old = env.get(key);
        env.put(key, new EnvVar(key, value, secret || (old != null && old.secret())));
    }
}
