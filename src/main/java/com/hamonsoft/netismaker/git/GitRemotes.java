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
}
