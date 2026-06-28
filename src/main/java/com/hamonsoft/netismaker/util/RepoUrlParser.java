package com.hamonsoft.netismaker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레포 입력(전체 Git URL / scp-style ssh / owner/repo)을 정식 형태로 파싱.
 *
 *  GitHub면 host="github" + ownerRepo="owner/repo" + canonicalUrl="https://github.com/owner/repo.git".
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

    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("레포 입력이 비어 있습니다");
        }
        String s = input.strip();

        // 1) bare owner/repo
        if (OWNER_REPO.matcher(s).matches()) {
            return new Parsed("https://github.com/" + s + ".git", "github", s);
        }

        // 2) github URL (https/http/ssh/scp)
        Matcher gh = GITHUB.matcher(s);
        if (gh.find()) {
            String ownerRepo = gh.group(1) + "/" + gh.group(2);
            return new Parsed("https://github.com/" + ownerRepo + ".git", "github", ownerRepo);
        }

        // 3) 비-GitHub but URL 모양이면 other 로 보존
        if (URL_SHAPE.matcher(s).matches() || SCP_SHAPE.matcher(s).matches()) {
            String canonical = s.replaceAll("/+$", "");
            return new Parsed(canonical, "other", null);
        }

        throw new IllegalArgumentException("레포 URL 형식을 인식할 수 없습니다: " + input);
    }
}
