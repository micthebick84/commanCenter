package com.hamonsoft.netismaker.util;

import java.util.Locale;
import java.util.Set;

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

    /** Windows 경로 금지 문자 — {@code :}는 드라이브 표기 밖 단계 안에서도 막는다(NTFS 대체 데이터 스트림). */
    private static final String FORBIDDEN_CHARS = "<>:\"|?*";

    /** 첫 단계로 쓸 수 없는 이름(소문자로 비교) — 구현 임시 폴더. {@code .git}은 어느 단계든 막는다. */
    private static final Set<String> RESERVED_FIRST = Set.of(".netis", ".design");

    private DbScriptDir() {}

    /**
     * 정규화한 값. null·공백이면 null(기본 폴더를 쓴다). {@code \}는 {@code /}로 바꾸고 앞뒤 공백과 끝 {@code /}를 뗀다.
     * 다음은 IllegalArgumentException(메시지는 그대로 사용자에게 보인다): 절대 경로(맨 앞 {@code /}·UNC 포함)·드라이브 문자,
     * Windows 경로 금지 문자({@code <>:"|?*})·제어문자(NUL 포함), 빈 단계({@code a//b})·{@code .}·{@code ..}·{@code .git} 단계,
     * 첫 단계가 {@code .netis}·{@code .design}(대소문자 무시), 정규화 뒤 255자 초과.
     * 워커는 이 값으로 모든 구현에서 worktree 경로를 만든다 — Windows 워커에서 InvalidPathException이 나면 그 레포의 모든 구현이
     * claude를 다 돈 뒤 커밋 단계에서 실패한다(최종 리뷰 Important 4). {@code .git}은 worktree에서 파일이고 {@code .netis}·{@code .design}은
     * 커밋 전에 지워지거나 커밋에서 빠지는 임시 폴더다.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().replace('\\', '/');
        boolean absolute = s.startsWith("/")
                || (s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0)));
        if (absolute) throw invalid();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (FORBIDDEN_CHARS.indexOf(c) >= 0 || Character.isISOControl(c)) throw invalid();
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("/", -1);
        for (String part : parts) {
            // git은 어느 단계든 .git(대소문자 무시)인 경로를 index에 올리지 않는다 — 강제 추가도 실패한다
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) throw invalid();
        }
        if (RESERVED_FIRST.contains(parts[0].toLowerCase(Locale.ROOT))) throw invalid();
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
