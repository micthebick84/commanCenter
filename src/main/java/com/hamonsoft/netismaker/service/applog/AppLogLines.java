package com.hamonsoft.netismaker.service.applog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * docker logs 원시 한 줄을 화면 줄로 바꾼다 (스펙 2026-11-03 §4 줄 처리).
 * 순서: 끝 `\r` 제거 → ANSI 색 코드 제거 → 시각 접두 분리 → 비밀 가리기 → 상한에서 자르기.
 * 가린 뒤에 자른다 — 먼저 자르면 경계에 걸친 비밀이 반쪽으로 남아 가려지지 않는다.
 * 색 코드를 지우는 이유: NestJS 같은 앱은 TTY가 아니어도 색을 찍어 화면에 `[31m` 잔해가 남고,
 * `\e[31mERROR`에서는 화면의 `\bERROR\b` 강조도 빗나간다(m과 E 사이에 단어 경계가 없다).
 */
public final class AppLogLines {

    /** 자른 줄 끝에 붙이는 표시. */
    public static final String CUT_MARK = "…(잘림)";

    /**
     * docker `--timestamps` 접두(RFC3339Nano, `Z` 또는 `±hh:mm`). 접두 뒤는 공백이거나 줄 끝이어야 한다.
     * 시각 접두만 `lookingAt()`으로 맞춘다 — 본문을 `.`로 잡으면 `\r`·U+0085·U+2028·U+2029에서 멈춰 한 줄이 잘린다.
     */
    static final Pattern TS_PREFIX = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})(?= |\\z)");

    /** ANSI CSI 색·서식 코드(`ESC [ … 문자`). */
    static final Pattern ANSI = Pattern.compile("\\u001b\\[[0-9;]*[A-Za-z]");

    private AppLogLines() {
    }

    /**
     * 시각 접두를 뗀 줄. 시각 접두가 없으면(docker 자신의 오류 문장 등) null — 호출자가 {@link #toInfo}로 따로 다룬다.
     * 본문은 접두 뒤 공백 하나만 뺀 나머지이고 비어 있을 수 있다.
     */
    public static AppLogLine toLine(String raw, String stream, SecretMasker masker, int maxChars) {
        String cleaned = clean(raw);
        Matcher m = TS_PREFIX.matcher(cleaned);
        if (!m.lookingAt()) return null;
        int end = m.end(); // 뒤는 공백이거나 줄 끝(lookahead)
        String body = end < cleaned.length() ? cleaned.substring(end + 1) : "";
        return new AppLogLine(m.group(), stream, cut(masker.mask(body), maxChars));
    }

    /** 시각 접두가 없는 줄을 안내 문장으로 쓸 때: 끝 `\r`·ANSI를 지우고 가린 뒤 자른다. */
    public static String toInfo(String raw, SecretMasker masker, int maxChars) {
        return cut(masker.mask(clean(raw)), maxChars);
    }

    /**
     * text가 max자(UTF-16 단위)를 넘으면 max자까지만 남기고 {@link #CUT_MARK}를 붙인다.
     * 끝이 서로게이트 쌍의 앞 절반이면 그 반쪽은 버린다 — 깨진 문자가 JSON으로 나가면 직렬화가 어긋난다
     * (같은 처리: {@code GitRemotes.maskAndCut}). 음수 max는 0으로 본다.
     */
    public static String cut(String text, int max) {
        int limit = Math.max(0, max);
        if (text.length() <= limit) return text;
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + CUT_MARK;
    }

    /** 끝의 `\r`(CRLF 줄 끝의 잔해)과 ANSI 색 코드를 지운다. */
    private static String clean(String raw) {
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == '\r') end--;
        String trimmed = end == raw.length() ? raw : raw.substring(0, end);
        return ANSI.matcher(trimmed).replaceAll("");
    }
}
