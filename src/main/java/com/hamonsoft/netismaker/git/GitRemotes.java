package com.hamonsoft.netismaker.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 호스트별 원격 처리 — 인증 URL 조립 / 로컬 폴더 키 / 자격증명 마스킹.
 * github.com 문자열 조립과 owner/repo 2단계 가정은 여기 밖에 두지 않는다.
 */
public final class GitRemotes {

    private static final Pattern CREDENTIALS = Pattern.compile("(?<=://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern SCHEME = Pattern.compile("^(https?://)");

    private final String githubPat;
    private final String gitlabToken;

    public GitRemotes(String githubPat, String gitlabToken) {
        this.githubPat = githubPat;
        this.gitlabToken = gitlabToken;
    }

    /** 해당 호스트의 토큰. 없으면 null. */
    public String tokenFor(RepoRef ref) {
        String t = ref.isGitlab() ? gitlabToken : githubPat;
        return (t == null || t.isBlank()) ? null : t;
    }

    /**
     * clone/fetch/push용 URL. 토큰이 있으면 https://oauth2:<token>@host/path.git.
     * 토큰이 없으면 평문 URL — 'oauth2:@host' 같은 빈 비밀번호 URL은 GitHub가 거부한다.
     */
    public String authenticatedUrl(RepoRef ref) {
        String token = tokenFor(ref);
        if (token == null) return ref.gitUrl();
        return SCHEME.matcher(ref.gitUrl())
                .replaceFirst("$1" + Matcher.quoteReplacement("oauth2:" + token + "@"));
    }

    /**
     * 로컬 폴더 키(캐시·worktree·잠금 파일·인터뷰 workDir). 항상 2단계 상대경로.
     *  github → owner/repo (기존 폴더 그대로 유효)
     *  gitlab → _gitlab/<경로의 '/'를 '+'로> — GitHub 사용자명은 '_'로 시작할 수 없고
     *           GitLab 경로에는 '+'가 올 수 없어 충돌이 없다.
     */
    public static String localKey(RepoRef ref) {
        return ref.isGitlab() ? "_gitlab/" + ref.path().replace('/', '+') : ref.path();
    }

    /** "://user:secret@" → "://***@". 로그·예외 메시지로 나가는 모든 git/gh 문자열에 적용. */
    public static String mask(String text) {
        return text == null ? null : CREDENTIALS.matcher(text).replaceAll("***@");
    }

    /**
     * 자격증명을 가리고({@link #mask}) 나서 max자(UTF-16 단위)로 자른다 — 저장·이력·보고로 나가는 자유 텍스트의 상한.
     * 가린 뒤에 자르는 이유: 먼저 자르면 토큰이 반쪽으로 남고 mask(끝의 `@`까지 있어야 가린다)가 알아보지 못한다.
     * 서로게이트 쌍의 앞 절반에서 잘렸으면 그 반쪽은 버린다 — 깨진 문자가 JSONB로 나가면 직렬화가 실패한다. null은 null.
     */
    public static String maskAndCut(String text, int max) {
        String masked = mask(text);
        if (masked == null || masked.length() <= max) return masked;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(masked.charAt(end - 1))) end--;
        return masked.substring(0, end);
    }
}
