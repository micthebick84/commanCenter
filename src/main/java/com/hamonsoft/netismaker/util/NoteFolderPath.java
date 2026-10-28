package com.hamonsoft.netismaker.util;

import java.util.regex.Pattern;

/**
 * 종료 노트 폴더 값(볼트 상대 경로) 규칙 — 카탈로그 저장(API)과 워커 사용 때 같이 쓴다 (스펙 2026-10-28 close-note-folder §3-1).
 * {@code \}는 {@code /}로 바꾸고 앞뒤 공백·끝 구분자를 뺀다. 빈 단계·{@code .}·{@code ..}·드라이브 문자·맨 앞 {@code /}는 거부.
 */
public final class NoteFolderPath {

    public static final int MAX_LENGTH = 500;
    private static final Pattern FORBIDDEN = Pattern.compile("[:*?\"<>|\\p{Cntrl}]");

    private NoteFolderPath() {}

    /** 정규화한 값. null·공백이면 null, 규칙 위반이면 IllegalArgumentException(메시지는 그대로 사용자에게 보인다). */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().replace('\\', '/');
        boolean absolute = s.startsWith("/")
                || (s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0)));
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (absolute || s.isEmpty()) throw invalid();
        for (String part : s.split("/", -1)) {
            // Windows 워커에서 경로가 될 수 없는 이름(쓸 수 없는 문자·제어문자·앞뒤 공백·끝 점)은 저장 때 막는다 —
            // 통과시키면 노트 단계가 매 재시도마다 InvalidPathException으로 끝난다.
            if (part.isBlank() || part.equals(".") || part.equals("..") || !part.equals(part.strip())
                    || part.endsWith(".") || FORBIDDEN.matcher(part).find()) {
                throw invalid();
            }
        }
        if (s.length() > MAX_LENGTH) throw new IllegalArgumentException("노트 폴더가 너무 깁니다(500자)");
        return s;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("노트 폴더는 볼트 안 상대 경로여야 합니다");
    }
}
