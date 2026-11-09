package com.hamonsoft.netismaker.workerdaemon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 기록의 cwd가 Symphony 작업 폴더 아래인지 가른다(순수 문자열 — Path를 쓰지 않아 OS와 무관하게 같은 답).
 * 정규화: trim → '\' → '/' → '//?/' 접두 제거 → '/c/…' → 'c:/…' → 겹 '/' 합치기 → 끝 '/' 제거 → 소문자.
 */
public final class UsagePathClassifier {

    public enum Category { SYMPHONY, DIRECT }

    public record Roots(List<String> accepted, List<String> ignored) {}

    private UsagePathClassifier() {}

    public static String normalize(String path) {
        if (path == null) return "";
        String s = path.trim().replace('\\', '/');
        if (s.startsWith("//?/")) s = s.substring(4);
        if (s.length() >= 2 && s.charAt(0) == '/' && Character.isLetter(s.charAt(1))
                && (s.length() == 2 || s.charAt(2) == '/')) {
            s = s.charAt(1) + ":" + s.substring(2);
        }
        s = s.replaceAll("/{2,}", "/");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    public static boolean isUnder(String normPath, String normRoot) {
        if (normPath == null || normRoot == null || normRoot.isEmpty()) return false;
        return normPath.equals(normRoot) || normPath.startsWith(normRoot + "/");
    }

    public static String expandHome(String raw, String home) {
        if (raw == null) return null;
        String s = raw.trim();
        if (home == null || home.isBlank()) return s;
        if (s.equals("~")) return home;
        if (s.startsWith("~/") || s.startsWith("~\\")) return home + "/" + s.substring(2);
        return s;
    }

    /** 후보: worktreeRoot, reposDir, {reposDir 부모}/close(종료 노트 claude의 cwd), {home}/netis-maker, 설정값. */
    public static Roots roots(String worktreeRoot, String reposDir, String home, List<String> configured) {
        String normHome = normalize(home);
        List<String> candidates = new ArrayList<>();
        if (worktreeRoot != null) candidates.add(worktreeRoot);
        if (reposDir != null) {
            candidates.add(reposDir);
            String normRepos = normalize(reposDir);
            int slash = normRepos.lastIndexOf('/');
            if (slash > 0) candidates.add(normRepos.substring(0, slash) + "/close");
        }
        if (home != null && !home.isBlank()) candidates.add(home + "/netis-maker");
        if (configured != null) {
            for (String c : configured) candidates.add(expandHome(c, home));
        }
        Set<String> accepted = new LinkedHashSet<>();
        List<String> ignored = new ArrayList<>();
        for (String c : candidates) {
            String norm = normalize(c);
            boolean driveRoot = norm.matches("[a-z]:");
            if (norm.isEmpty() || driveRoot || (!normHome.isEmpty() && norm.equals(normHome))) {
                ignored.add(c == null ? "" : c.trim());
            } else {
                accepted.add(norm);
            }
        }
        return new Roots(List.copyOf(accepted), List.copyOf(ignored));
    }

    public static Category classify(String cwd, List<String> acceptedRoots) {
        if (cwd == null || cwd.isBlank()) return Category.DIRECT;
        String norm = normalize(cwd);
        for (String root : acceptedRoots) {
            if (isUnder(norm, root)) return Category.SYMPHONY;
        }
        return Category.DIRECT;
    }
}
