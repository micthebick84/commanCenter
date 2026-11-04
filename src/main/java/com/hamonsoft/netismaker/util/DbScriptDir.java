package com.hamonsoft.netismaker.util;

import java.util.HashSet;
import java.util.List;
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

    /**
     * Windows 예약 장치 이름(소문자) — 단계의 첫 {@code .} 앞 이름이 이것이면 확장자가 붙어도({@code NUL.sql}) 파일이 아니라 장치를
     * 가리킨다. Microsoft 문서의 목록: CON·PRN·AUX·NUL, COM0–9·LPT0–9와 그 위첨자 숫자(¹²³) 변형.
     */
    private static final Set<String> RESERVED_DEVICE_NAMES = reservedDeviceNames();

    private DbScriptDir() {}

    private static Set<String> reservedDeviceNames() {
        // 0–9와 위첨자 숫자 ¹²³(U+00B9·U+00B2·U+00B3)
        String digits = "0123456789" + (char) 0x00B9 + (char) 0x00B2 + (char) 0x00B3;
        Set<String> names = new HashSet<>(Set.of("con", "prn", "aux", "nul"));
        for (String prefix : List.of("com", "lpt")) {
            for (char digit : digits.toCharArray()) names.add(prefix + digit);
        }
        return Set.copyOf(names);
    }

    /**
     * 정규화한 값. null·공백이면 null(기본 폴더를 쓴다). {@code \}는 {@code /}로 바꾸고 값 전체의 앞뒤 공백과 끝 {@code /}를 뗀다.
     * 다음은 IllegalArgumentException(메시지는 그대로 사용자에게 보인다): 절대 경로(맨 앞 {@code /}·UNC 포함)·드라이브 문자,
     * Windows 경로 금지 문자({@code <>:"|?*})·제어문자(NUL 포함), 빈 단계({@code a//b})·{@code .}·{@code ..}·{@code .git} 단계,
     * 공백(유니코드 공백 포함)으로 시작하거나 끝나는 단계({@code db /x}·{@code db/x /}), 끝이 {@code .}인 단계({@code db/x.}),
     * Windows 예약 장치 이름 단계({@code con}·{@code prn}·{@code aux}·{@code nul}·{@code com0}–{@code com9}·{@code lpt0}–{@code lpt9},
     * 대소문자 무시, {@code NUL.sql}처럼 확장자가 붙어도), 첫 단계가 {@code .netis}·{@code .design}(대소문자 무시),
     * 정규화 뒤 255자 초과. 단계 안쪽의 공백({@code my sql})은 막지 않는다.
     * 워커는 이 값으로 모든 구현에서 worktree 경로를 만든다 — 이름 끝 공백은 Windows Path가 InvalidPathException("Trailing char")으로
     * 거부하고, 끝 점은 Windows 파일 시스템이 조용히 떼어 git 경로와 디스크 경로가 어긋나며, 예약 장치 이름은 파일이 아니라 장치를
     * 가리킨다. 이런 값이 저장되면 그 레포의 모든 구현이 claude를 다 돈 뒤 커밋 단계에서 실패하거나 엉뚱한 곳을 가리킨다(최종 리뷰
     * Important 4). 이 검사가 Windows에서 막는 것은 위 항목뿐이다 — 경로 전체 길이(MAX_PATH)와 그 밖의 파일 시스템 별칭은 따로 보지 않는다.
     * {@code .git}은 worktree에서 파일이고 {@code .netis}·{@code .design}은 커밋 전에 지워지거나 커밋에서 빠지는 임시 폴더다.
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
            if (invalidStep(part)) throw invalid();
        }
        if (RESERVED_FIRST.contains(parts[0].toLowerCase(Locale.ROOT))) throw invalid();
        if (s.length() > MAX_LENGTH) throw invalid();
        return s;
    }

    /** 한 단계(두 {@code /} 사이 이름)를 저장할 수 없으면 true — 규칙은 {@link #normalize} 설명. */
    private static boolean invalidStep(String part) {
        // git은 어느 단계든 .git(대소문자 무시)인 경로를 index에 올리지 않는다 — 강제 추가도 실패한다
        if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) return true;
        // 값 전체의 앞뒤만 strip하므로 단계 이름 앞뒤의 공백은 여기서 거른다 — Windows Path는 이름 끝 공백을 거부한다
        if (isSpace(part.charAt(0)) || isSpace(part.charAt(part.length() - 1))) return true;
        // Windows는 이름 끝 점을 조용히 떼어 git 경로와 디스크 경로가 어긋난다
        if (part.charAt(part.length() - 1) == '.') return true;
        // 예약 장치 이름은 NUL.sql처럼 확장자가 붙어도 장치다 — 첫 점 앞 이름으로 비교한다(끝 공백은 Windows가 떼므로 같이 뗀다)
        int dot = part.indexOf('.');
        String base = (dot < 0 ? part : part.substring(0, dot)).stripTrailing();
        return RESERVED_DEVICE_NAMES.contains(base.toLowerCase(Locale.ROOT));
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
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
