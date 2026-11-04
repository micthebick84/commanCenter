package com.hamonsoft.netismaker.util;

/**
 * 구현 단계 DB 쓰기 SQL 스크립트 파일의 레포 안 폴더 규칙 — 카탈로그 저장(API)과 워커의 파일 경로 계산이 같이 쓴다
 * (스펙 2026-11-03 task-type-db-write §6.6). 파일은 {@code {폴더}/task-{작업 id}.sql}이고 폴더가 비면 {@link #DEFAULT}다.
 */
public final class DbScriptDir {

    /** 카탈로그 값이 비었을 때의 기본 폴더. */
    public static final String DEFAULT = "db/symphony";

    /** {@code repo_catalog.db_script_dir} 열 길이. */
    public static final int MAX_LENGTH = 255;

    private static final String INVALID_MESSAGE = "DB 스크립트 폴더는 레포 안 상대 경로여야 합니다";

    private DbScriptDir() {}

    /**
     * 정규화한 값. null·공백이면 null(기본 폴더를 쓴다). {@code \}는 {@code /}로 바꾸고 앞뒤 공백과 끝 {@code /}를 뗀다.
     * 절대 경로(맨 앞 {@code /}·UNC 포함)·드라이브 문자·{@code ..} 단계·정규화 뒤 255자 초과는
     * IllegalArgumentException(메시지는 그대로 사용자에게 보인다).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().replace('\\', '/');
        boolean absolute = s.startsWith("/")
                || (s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0)));
        if (absolute) throw invalid();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String part : s.split("/", -1)) {
            if (part.equals("..")) throw invalid();
        }
        if (s.length() > MAX_LENGTH) throw invalid();
        return s;
    }

    /** 작업의 스크립트 파일 경로 — {@code {dir 또는 DEFAULT}/task-{id}.sql}. {@code dir}은 이미 정규화된 저장값이다. */
    public static String resolve(String dir, long taskId) {
        String folder = (dir == null || dir.isBlank()) ? DEFAULT : dir;
        return folder + "/task-" + taskId + ".sql";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }
}
