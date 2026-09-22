package com.hamonsoft.netismaker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레포 입력(전체 Git URL / scp-style ssh / owner/repo)을 정식 형태로 파싱.
 *
 *  GitHub면 host="github" + ownerRepo="owner/repo" + canonicalUrl="https://github.com/owner/repo.git".
 *  사내 GitLab(gitlabBaseUrl의 호스트)이면 host="gitlab" + ownerRepo=프로젝트 전체 경로 + canonicalUrl="{base}/{path}.git".
 *  비-GitHub면 host="other" + ownerRepo=null + canonicalUrl=입력 URL(공백/끝슬래시 정리).
 *  파싱 불가(빈 값/형식 불명)면 IllegalArgumentException.
 */
public final class RepoUrlParser {

    private RepoUrlParser() {}

    public record Parsed(String canonicalUrl, String host, String ownerRepo) {}

    private static final Pattern OWNER_REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    // github.com/<owner>/<repo> 를 https/http/ssh/scp 어떤 형태에서든 추출
    private static final Pattern GITHUB =
            Pattern.compile("github\\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");
    // 비-GitHub URL 모양: https?/git/ssh://<anything>
    private static final Pattern URL_SHAPE = Pattern.compile("^(https?|git|ssh)://.+");
    // SCP 스타일: <anything>@<anything>:<anything>
    private static final Pattern SCP_SHAPE = Pattern.compile("^[^\\s]+@[^\\s]+:.+");

    // http(s)/ssh URL: [user@]host[:port]/path
    private static final Pattern HOSTED_URL =
            Pattern.compile("^(?:https?|ssh)://(?:[^@/\\s]+@)?([^/:\\s]+)(?::\\d+)?/(.+)$");
    // scp 스타일: user@host:path
    private static final Pattern SCP_URL = Pattern.compile("^[^@\\s]+@([^:/\\s]+):(.+)$");
    private static final Pattern GITLAB_SEGMENT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]*$");
    private static final Pattern BASE_URL_HOST = Pattern.compile("^https?://(?:[^@/\\s]+@)?([^/:\\s]+)");

    public static Parsed parse(String input) {
        return parse(input, null);
    }

    /** gitlabBaseUrl(예: https://gitlab.hamon.vip)이 비면 GitLab 판별을 하지 않는다. */
    public static Parsed parse(String input, String gitlabBaseUrl) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("레포 입력이 비어 있습니다");
        }
        String s = input.strip();

        // 1) bare owner/repo — 항상 GitHub (GitLab 레포는 전체 URL로 입력)
        if (OWNER_REPO.matcher(s).matches()) {
            return new Parsed("https://github.com/" + s + ".git", "github", s);
        }

        // 2) github URL (https/http/ssh/scp)
        Matcher gh = GITHUB.matcher(s);
        if (gh.find()) {
            String ownerRepo = gh.group(1) + "/" + gh.group(2);
            return new Parsed("https://github.com/" + ownerRepo + ".git", "github", ownerRepo);
        }

        // 3) 사내 GitLab
        String base = normalizeBase(gitlabBaseUrl);
        if (base != null) {
            String path = pathIfHost(s, hostOf(base));
            if (path != null) {
                validateGitlabPath(path, input);
                return new Parsed(base + "/" + path + ".git", "gitlab", path);
            }
        }

        // 4) 그 밖의 URL 모양이면 other 로 보존
        if (URL_SHAPE.matcher(s).matches() || SCP_SHAPE.matcher(s).matches()) {
            String canonical = s.replaceAll("/+$", "");
            return new Parsed(canonical, "other", null);
        }

        throw new IllegalArgumentException("레포 URL 형식을 인식할 수 없습니다: " + input);
    }

    private static String normalizeBase(String gitlabBaseUrl) {
        if (gitlabBaseUrl == null || gitlabBaseUrl.isBlank()) return null;
        return gitlabBaseUrl.strip().replaceAll("/+$", "");
    }

    private static String hostOf(String baseUrl) {
        Matcher m = BASE_URL_HOST.matcher(baseUrl);
        return m.find() ? m.group(1) : null;
    }

    /** s의 호스트가 host와 같으면 정리된 경로(.git·앞뒤 슬래시 제거), 아니면 null. */
    private static String pathIfHost(String s, String host) {
        if (host == null) return null;
        Matcher m = HOSTED_URL.matcher(s);
        if (!m.matches()) {
            m = SCP_URL.matcher(s);
            if (!m.matches()) return null;
        }
        if (!m.group(1).equalsIgnoreCase(host)) return null;
        return m.group(2).replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("\\.git$", "");
    }

    private static void validateGitlabPath(String path, String input) {
        String[] segments = path.split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException("GitLab 프로젝트 경로는 그룹/프로젝트 형태여야 합니다: " + input);
        }
        for (String seg : segments) {
            if (seg.equals("..") || !GITLAB_SEGMENT.matcher(seg).matches()) {
                throw new IllegalArgumentException("GitLab 프로젝트 경로가 올바르지 않습니다: " + input);
            }
        }
    }
}
