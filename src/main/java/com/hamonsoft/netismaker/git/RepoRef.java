package com.hamonsoft.netismaker.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장소 1개. host는 "github" | "gitlab".
 *  - path: GitHub는 owner/repo, GitLab은 프로젝트 전체 경로(다단계 그룹 포함).
 *  - gitUrl: 자격증명이 없는 정식 https URL.
 */
public record RepoRef(String host, String path, String gitUrl) {

    private static final Pattern ORIGIN = Pattern.compile("^(https?://)(?:[^@/\\s]+@)?([^/\\s]+)");

    /**
     * task/interview_session 스냅샷(github_repo, git_url)에서 복원.
     * git_url이 없으면(카탈로그 도입 전 행, 구버전 API) GitHub로 간주한다.
     * 호스트는 URL로 판정: github.com → github, 그 외 → gitlab
     * (지원하지 않는 호스트는 등록 단계에서 거절되므로 스냅샷에 남을 수 없다).
     */
    public static RepoRef fromSnapshot(String path, String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return new RepoRef("github", path, "https://github.com/" + path + ".git");
        }
        Matcher m = ORIGIN.matcher(gitUrl);
        String authority = m.find() ? m.group(2) : "";
        String hostName = authority.replaceAll(":\\d+$", "");
        return new RepoRef("github.com".equalsIgnoreCase(hostName) ? "github" : "gitlab", path, gitUrl);
    }

    public boolean isGitlab() {
        return "gitlab".equals(host);
    }

    /** REST API base — gitUrl의 scheme://host[:port]. */
    public String apiBase() {
        Matcher m = ORIGIN.matcher(gitUrl);
        if (!m.find()) throw new IllegalStateException("gitUrl에서 호스트를 찾을 수 없습니다: " + gitUrl);
        return m.group(1) + m.group(2);
    }
}
