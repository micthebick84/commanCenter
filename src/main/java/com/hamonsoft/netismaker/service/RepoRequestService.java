package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REPO 추가 요청 처리: 사내 GitLab 후보 추측·검색, 클릭 한 번으로 카탈로그 등록(스펙 2026-10-19).
 *
 * addRepo는 일부러 트랜잭션을 걸지 않는다 — GitLab HTTP 호출이 DB 트랜잭션(행 락) 안에서 일어나면 안 되고,
 * 실제 완료 처리는 {@link RequestBoardService#completeRepoRequest}가 자기 트랜잭션에서 행을 잠그고 다시 검사한다.
 */
@Service
@Profile("api")
public class RepoRequestService {

    private static final int CANDIDATE_LIMIT = 5;
    private static final int SEARCH_LIMIT = 10;
    private static final int MAX_TERMS = 3;
    private static final String NOT_FOUND_MESSAGE = "요청을 찾을 수 없습니다";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,}");
    private static final Set<String> STOPWORDS = Set.of(
            "repo", "repository", "git", "gitlab", "github", "http", "https", "www", "com", "the", "and", "for",
            "please");

    private static final Pattern HOSTED_URL =
            Pattern.compile("^https?://(?:[^@/\\s]+@)?([^/:\\s]+)(?::\\d+)?(?:/(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE_HOST = Pattern.compile("^https?://(?:[^@/\\s]+@)?([^/:\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_PATH = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+$");

    private final GitLabProjectClient client;
    private final RequestBoardService requestBoardService;
    private final RequestPostRepository posts;
    private final RepoCatalogRepository repoCatalogRepository;
    private final String gitlabBaseUrl;

    public RepoRequestService(GitLabProjectClient client,
                              RequestBoardService requestBoardService,
                              RequestPostRepository posts,
                              RepoCatalogRepository repoCatalogRepository,
                              @Value("${app.gitlab.base-url:}") String gitlabBaseUrl) {
        this.client = client;
        this.requestBoardService = requestBoardService;
        this.posts = posts;
        this.repoCatalogRepository = repoCatalogRepository;
        this.gitlabBaseUrl = gitlabBaseUrl == null ? "" : gitlabBaseUrl.strip();
    }

    /** 요청의 힌트·제목·내용에서 GitLab 프로젝트 후보를 추측한다(최대 5개). */
    public List<RequestBoardDto.RepoCandidate> candidates(Long requestId) {
        RequestPost post = posts.findById(requestId)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        if (post.getKind() != RequestKind.REPO) {
            throw TaskException.conflict("REPO 추가 요청이 아닙니다");
        }
        if (!client.configured()) {
            throw new TaskException(HttpStatus.SERVICE_UNAVAILABLE, "사내 GitLab이 설정되지 않았습니다");
        }

        Optional<String> hintPath = hintPath(post.getRepoHint(), gitlabBaseUrl);
        List<String> terms = searchTerms(post.getRepoHint(), post.getTitle(), post.getContent());

        Map<Long, GitLabProjectClient.GitLabProject> found = new LinkedHashMap<>();
        if (hintPath.isPresent()) {
            client.findByPath(hintPath.get()).ifPresent(p -> found.putIfAbsent(p.id(), p));
        }
        for (String term : terms) {
            for (GitLabProjectClient.GitLabProject p : client.search(term, CANDIDATE_LIMIT)) {
                found.putIfAbsent(p.id(), p);
            }
        }
        return found.values().stream().limit(CANDIDATE_LIMIT).map(this::toCandidate).toList();
    }

    /** 관리자가 직접 입력한 검색어로 GitLab 프로젝트를 찾는다(최대 10개). */
    public List<RequestBoardDto.RepoCandidate> search(String q) {
        String term = q == null ? "" : q.strip();
        if (term.length() < 2) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "검색어를 2자 이상 입력하세요");
        }
        return client.search(term, SEARCH_LIMIT).stream().map(this::toCandidate).toList();
    }

    /**
     * 후보 하나를 카탈로그에 등록하고 요청을 처리완료로 닫는다. 트랜잭션 없음(클래스 설명 참고).
     * 등록할 Git URL은 GitLab 응답이 아니라 설정된 base URL과 프로젝트 경로로 직접 만든다.
     */
    public RequestBoardDto.Detail addRepo(Long requestId, long gitlabProjectId, String adminId) {
        requestBoardService.assertOpenRepoRequest(requestId);
        GitLabProjectClient.GitLabProject project = client.findById(gitlabProjectId)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "GitLab 프로젝트를 찾을 수 없습니다"));
        String gitUrl = gitUrlFor(project.pathWithNamespace());
        return requestBoardService.completeRepoRequest(requestId, project, gitUrl, adminId);
    }

    String gitUrlFor(String pathWithNamespace) {
        return stripTrailingSlash(gitlabBaseUrl) + "/" + pathWithNamespace + ".git";
    }

    /** 힌트 → 제목 → 내용 순으로 ASCII 토큰을 뽑아 소문자 기준 중복·불용어를 제거하고 최대 3개를 돌려준다. */
    static List<String> searchTerms(String hint, String title, String content) {
        Set<String> terms = new LinkedHashSet<>();
        for (String text : new String[]{hint, title, content}) {
            if (text == null) continue;
            Matcher m = TOKEN.matcher(text);
            while (m.find() && terms.size() < MAX_TERMS) {
                String t = m.group().toLowerCase(Locale.ROOT);
                if (!STOPWORDS.contains(t)) terms.add(t);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * 힌트가 사내 GitLab URL이거나 그룹/프로젝트 경로면 프로젝트 전체 경로를 돌려준다.
     * `.git`과 `/-/…` 뒤쪽은 잘라 낸다. 그룹/프로젝트 두 단계 미만이면 empty(빈 경로를 GitLab에 보내지 않기 위해).
     */
    static Optional<String> hintPath(String hint, String gitlabBaseUrl) {
        if (hint == null || hint.isBlank()) return Optional.empty();
        String s = hint.strip();
        String path;
        if (s.contains("://")) {
            Matcher base = BASE_HOST.matcher(gitlabBaseUrl == null ? "" : gitlabBaseUrl.strip());
            Matcher url = HOSTED_URL.matcher(s);
            if (!base.find() || !url.matches() || !url.group(1).equalsIgnoreCase(base.group(1))) {
                return Optional.empty();
            }
            path = url.group(2) == null ? "" : url.group(2);
            path = path.replaceAll("[?#].*$", "");
            int tail = path.indexOf("/-/");
            if (tail >= 0) path = path.substring(0, tail);
            if (path.endsWith("/-")) path = path.substring(0, path.length() - 2);
        } else {
            path = s;
        }
        path = path.replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("\\.git$", "");
        if (!BARE_PATH.matcher(path).matches()) return Optional.empty();
        for (String seg : path.split("/")) {
            if (seg.equals(".") || seg.equals("..")) return Optional.empty();
        }
        return Optional.of(path);
    }

    private RequestBoardDto.RepoCandidate toCandidate(GitLabProjectClient.GitLabProject p) {
        return new RequestBoardDto.RepoCandidate(p.id(), p.name(), p.pathWithNamespace(), p.defaultBranch(),
                p.description(), isRegistered(p.pathWithNamespace()));
    }

    private boolean isRegistered(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            String canonical = RepoUrlParser.parse(gitUrlFor(path), gitlabBaseUrl).canonicalUrl();
            return repoCatalogRepository.findByGitUrl(canonical).isPresent();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String stripTrailingSlash(String s) {
        String b = s;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
