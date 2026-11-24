package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 카탈로그 배포 설정 검증·정규화 (스펙 2026-11-24-deploy-db-connection-select §9).
 * 위반(입력이 깨진 경우 포함)은 모두 IllegalArgumentException — 호출부가 400으로 바꾼다.
 */
public final class DeployConfigValidator {

    static final int MAX_SLOTS = 3;
    static final int MAX_VARIABLES = 50;
    private static final Pattern SLOT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,199}$");
    private static final Set<String> DB_PLACEHOLDERS = Set.of(
            "host", "port", "database", "username", "password", "username:url", "password:url");
    private static final String VALUE_TOKEN = "{값}";
    private static final String PATH_PREFIX = "WEB-INF/classes/";

    private DeployConfigValidator() {
    }

    public static DeployConfig normalize(DeployConfig in) {
        if (in == null) {
            return null;
        }
        List<DbSlot> slotsIn = in.databases();
        List<Variable> varsIn = in.variables();
        if (slotsIn.size() > MAX_SLOTS) {
            throw bad("DB 자리는 최대 " + MAX_SLOTS + "개입니다");
        }
        if (varsIn.size() > MAX_VARIABLES) {
            throw bad("변수는 최대 " + MAX_VARIABLES + "개입니다");
        }
        Set<String> seenEnv = new HashSet<>();
        Set<String> seenFile = new HashSet<>();
        Set<String> seenSlots = new HashSet<>();
        List<DbSlot> slots = new ArrayList<>();
        for (DbSlot s : slotsIn) {
            slots.add(normalizeSlot(s, seenSlots, seenEnv, seenFile));
        }
        Set<String> seenKeys = new HashSet<>();
        List<Variable> vars = new ArrayList<>();
        for (Variable v : varsIn) {
            vars.add(normalizeVariable(v, seenKeys, seenEnv, seenFile));
        }
        if (slots.isEmpty() && vars.isEmpty()) {
            return null;
        }
        return new DeployConfig(slots, vars);
    }

    /** 변수(파일 대상 아님) 이름 + DB ENV 대상 이름. 대소문자 그대로. */
    public static Set<String> envNames(DeployConfig c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null) {
            return out;
        }
        for (Variable v : c.variables()) {
            if (v.filePath() == null && v.key() != null) {
                out.add(v.key());
            }
        }
        out.addAll(dbEnvNames(c));
        return out;
    }

    /** DB ENV 대상 이름만. */
    public static Set<String> dbEnvNames(DeployConfig c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null) {
            return out;
        }
        for (DbSlot s : c.databases()) {
            Target t = s.target();
            if (t != null && t.kind() == TargetKind.ENV) {
                out.addAll(t.entries().keySet());
            }
        }
        return out;
    }

    private static DbSlot normalizeSlot(DbSlot s, Set<String> seenSlots, Set<String> seenEnv, Set<String> seenFile) {
        if (s == null) {
            throw bad("DB 자리가 비어 있습니다");
        }
        String slot = trim(s.slot());
        if (slot == null || !SLOT.matcher(slot).matches()) {
            throw bad("DB 자리 이름은 영문 소문자로 시작하는 소문자·숫자·- 32자 이하여야 합니다: " + slot);
        }
        if (!seenSlots.add(slot)) {
            throw bad("DB 자리 이름이 겹칩니다: " + slot);
        }
        String label = trim(s.label());
        if (label == null || label.codePointCount(0, label.length()) > 40) {
            throw bad("DB 자리 '" + slot + "'의 표시 이름은 1~40자여야 합니다");
        }
        if (s.dbTypes().isEmpty()) {
            throw bad("DB 자리 '" + slot + "'에 DB 종류가 하나 이상 필요합니다");
        }
        Set<DbType> types = new LinkedHashSet<>();
        for (DbType t : s.dbTypes()) {
            if (t == null) {
                throw bad("DB 자리 '" + slot + "'의 DB 종류에 빈 값이 있습니다");
            }
            types.add(t);
        }
        Target t = s.target();
        if (t == null || t.kind() == null) {
            throw bad("DB 자리 '" + slot + "'에 적용 대상(종류)이 필요합니다");
        }
        if (t.entries().isEmpty()) {
            throw bad("DB 자리 '" + slot + "'에 적용 항목이 하나 이상 필요합니다");
        }
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        String path = null;
        if (t.kind() == TargetKind.FILE) {
            path = checkPath(trim(t.path()));
        } else if (trim(t.path()) != null) {
            throw bad("DB 자리 '" + slot + "'의 환경변수 대상에는 경로를 쓸 수 없습니다");
        }
        for (Map.Entry<String, String> en : t.entries().entrySet()) {
            String key = trim(en.getKey());
            String val = en.getValue();
            if (val == null || val.isBlank()) {
                throw bad("DB 자리 '" + slot + "'의 항목 '" + key + "' 값이 비어 있습니다");
            }
            if (t.kind() == TargetKind.ENV) {
                if (key == null || !ENV_NAME.matcher(key).matches()) {
                    throw bad("환경변수 이름 규칙에 맞지 않습니다: " + key);
                }
                if (!seenEnv.add(key)) {
                    throw bad("같은 환경변수 이름이 두 번 정의됐습니다: " + key);
                }
            } else {
                if (key == null || key.length() > 200) {
                    throw bad("파일 항목 이름은 1~200자여야 합니다");
                }
                if (!seenFile.add(path + "\u0000" + key)) {
                    throw bad("같은 파일 항목이 두 번 정의됐습니다: " + path + " " + key);
                }
            }
            for (String p : DeployValueTemplates.placeholders(val)) {
                if (!DB_PLACEHOLDERS.contains(p)) {
                    throw bad("쓸 수 없는 자리표시자입니다: {" + p + "}");
                }
            }
            if (entries.put(key, val) != null) {
                throw bad("DB 자리 '" + slot + "'에 같은 항목 이름이 두 번 있습니다: " + key);
            }
        }
        return new DbSlot(slot, label, new ArrayList<>(types), new Target(t.kind(), path, entries));
    }

    private static Variable normalizeVariable(Variable v, Set<String> seenKeys, Set<String> seenEnv, Set<String> seenFile) {
        if (v == null) {
            throw bad("변수가 비어 있습니다");
        }
        String key = trim(v.key());
        if (key == null || !ENV_NAME.matcher(key).matches()) {
            throw bad("변수 이름 규칙에 맞지 않습니다: " + key);
        }
        if (!seenKeys.add(key)) {
            throw bad("변수 이름이 겹칩니다: " + key);
        }
        String format = trim(v.format());
        if (format != null) {
            int first = format.indexOf(VALUE_TOKEN);
            if (first < 0 || format.indexOf(VALUE_TOKEN, first + 1) >= 0) {
                throw bad("변수 '" + key + "'의 형식에는 {값}이 정확히 한 번 있어야 합니다");
            }
            String rest = format.replace(VALUE_TOKEN, "");
            if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) {
                throw bad("변수 '" + key + "'의 형식에는 {값} 외의 {…}를 쓸 수 없습니다");
            }
        }
        String filePath = trim(v.filePath());
        String fileEntry = trim(v.fileEntry());
        if ((filePath == null) != (fileEntry == null)) {
            throw bad("변수 '" + key + "'의 파일 경로와 항목은 함께 있거나 함께 없어야 합니다");
        }
        if (filePath == null) {
            if (!seenEnv.add(key)) {
                throw bad("같은 환경변수 이름이 두 번 정의됐습니다: " + key);
            }
        } else {
            checkPath(filePath);
            if (fileEntry.length() > 200) {
                throw bad("파일 항목 이름은 1~200자여야 합니다");
            }
            if (!seenFile.add(filePath + "\u0000" + fileEntry)) {
                throw bad("같은 파일 항목이 두 번 정의됐습니다: " + filePath + " " + fileEntry);
            }
        }
        return new Variable(key, trim(v.description()), v.secret(), v.required(), format, filePath, fileEntry);
    }

    private static String checkPath(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(".properties")
                || path.startsWith("/") || path.contains("\\") || path.length() > 300) {
            throw bad("파일 경로는 WEB-INF/classes/ 아래의 .properties 상대 경로여야 합니다: " + path);
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                throw bad("파일 경로에 빈 단계나 .. 를 쓸 수 없습니다: " + path);
            }
        }
        return path;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(msg);
    }
}
