package com.hamonsoft.netismaker.workerdaemon;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * worktree-root/{localKey}/{kind}-{taskId} 의 kind. 폴더 이름 규칙의 단일 출처 —
 * WorktreeService가 만들 때와 WorktreeCleanupJob이 해석할 때 같은 규칙을 쓴다.
 */
public enum WorktreeKind {
    TASK("task-"),
    DEPLOY("deploy-"),
    DESIGN("design-");

    /** 양의 10진 정수, 앞자리 0 없음, long 범위 안(18자리까지). */
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

    private final String prefix;

    WorktreeKind(String prefix) {
        this.prefix = prefix;
    }

    public String dirName(long taskId) {
        return prefix + taskId;
    }

    /** 정확히 {prefix}{id} 형태일 때만. 대소문자·앞자리 0·부호·접미사가 다르면 empty — 모르는 폴더는 건드리지 않는다. */
    public static Optional<Parsed> parse(String dirName) {
        if (dirName == null) return Optional.empty();
        for (WorktreeKind k : values()) {
            if (dirName.startsWith(k.prefix)) {
                String rest = dirName.substring(k.prefix.length());
                if (ID.matcher(rest).matches()) return Optional.of(new Parsed(k, Long.parseLong(rest)));
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public record Parsed(WorktreeKind kind, long taskId) {}
}
