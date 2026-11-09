package com.hamonsoft.netismaker.util;

import com.hamonsoft.netismaker.git.GitRemotes;

import java.util.regex.Pattern;

/**
 * 자유 텍스트에서 자격증명을 가리는 공용 규칙 — 종료 노트(CloseNoteWriter)와 앱 로그 줄(service.applog)이 함께 쓴다.
 * URL 안의 `user:secret@`는 {@link GitRemotes#mask}가, URL 밖에 맨몸으로 나온 토큰은 {@link #BARE_TOKENS}가 맡는다.
 */
public final class SecretMasks {

    /** URL 밖에 맨몸으로 나온 토큰(GitHub classic/fine-grained PAT, GitLab PAT, Anthropic 키). */
    public static final Pattern BARE_TOKENS = Pattern.compile(
            "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{16,}");

    private SecretMasks() {}

    /** 맨 토큰 패턴에 맞는 부분을 `***`로 바꾼다. null은 null. */
    public static String maskBareTokens(String text) {
        return text == null ? null : BARE_TOKENS.matcher(text).replaceAll("***");
    }

    /** URL 안 자격증명(`://user:secret@` → `://***@`)을 가린 뒤 맨 토큰도 가린다. null은 null. */
    public static String maskUrlAndTokens(String text) {
        return maskBareTokens(GitRemotes.mask(text));
    }
}
