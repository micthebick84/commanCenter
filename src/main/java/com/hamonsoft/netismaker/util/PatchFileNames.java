package com.hamonsoft.netismaker.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 부분 패치 zip 내려받기 파일 이름 (스펙 2026-11-10): {yyyyMMdd}_{정리한 제목}.zip */
public final class PatchFileNames {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_CODE_POINTS = 60;
    /** 파일 이름에 못 쓰는 문자: \ / : * ? " < > | */
    private static final String FORBIDDEN = "\\/:*?\"<>|";

    private PatchFileNames() {}

    public static String zipName(LocalDate date, String title, long taskId) {
        String cleaned = clean(title);
        if (cleaned.isEmpty()) cleaned = "task-" + taskId;
        return DATE.format(date) + "_" + cleaned + ".zip";
    }

    private static String clean(String title) {
        if (title == null) return "";
        StringBuilder sb = new StringBuilder();
        title.codePoints()
                .filter(cp -> FORBIDDEN.indexOf(cp) < 0 && !Character.isISOControl(cp))
                .forEach(sb::appendCodePoint);
        String s = stripEdges(sb.toString().trim().replaceAll("\\s+", "_"));
        if (s.codePointCount(0, s.length()) > MAX_CODE_POINTS) {
            s = stripEdges(s.substring(0, s.offsetByCodePoints(0, MAX_CODE_POINTS)));
        }
        return s;
    }

    private static String stripEdges(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '_' || s.charAt(a) == '.')) a++;
        while (b > a && (s.charAt(b - 1) == '_' || s.charAt(b - 1) == '.')) b--;
        return s.substring(a, b);
    }
}
