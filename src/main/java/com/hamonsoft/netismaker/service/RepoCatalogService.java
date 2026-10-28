package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Profile("api")
public class RepoCatalogService {

    /** 질문 세션에 붙일 수 있는 참조 repo 상한 (스펙 2026-10-22 §2). 프론트 composables/refRepos.ts의 상수와 같은 값이다. */
    public static final int MAX_REF_REPOS = 3;

    private final RepoCatalogRepository repo;
    private final GitRefService gitRefService;
    private final String gitlabBaseUrl;

    public RepoCatalogService(RepoCatalogRepository repo, GitRefService gitRefService,
                              @Value("${app.gitlab.base-url:}") String gitlabBaseUrl) {
        this.repo = repo;
        this.gitRefService = gitRefService;
        this.gitlabBaseUrl = gitlabBaseUrl;
    }

    /** 등록 해석 결과 — 작업/인터뷰 스냅샷에 박제할 필드들. */
    public record ResolvedRepo(Long catalogId, String alias, String gitUrl,
                               String host, String ownerRepo, String defaultBranch) {}

    @Transactional(readOnly = true)
    public List<RepoCatalogEntry> listEnabled() {
        return repo.findByEnabledTrueOrderByAlias();
    }

    @Transactional(readOnly = true)
    public List<RepoCatalogEntry> listAll() {
        return repo.findAllByOrderByAlias();
    }

    @Transactional
    public RepoCatalogEntry create(RepoCatalogDto.UpsertRequest req, String adminId) {
        repo.findByAlias(req.alias()).ifPresent(existing -> {
            throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 별칭: " + req.alias());
        });
        RepoUrlParser.Parsed p = parseOrThrow(req.gitUrl());
        repo.findByGitUrl(p.canonicalUrl()).ifPresent(existing -> {
            throw new TaskException(HttpStatus.CONFLICT, "이미 등록된 Git URL: " + p.canonicalUrl());
        });
        RepoCatalogEntry e = RepoCatalogEntry.create(req.alias(), p.canonicalUrl(), p.host(),
                p.ownerRepo(), blankToNull(req.defaultBranch()), req.description(), adminId);
        if (req.enabled() != null) e.setEnabled(req.enabled());
        applyArtifact(e, req);
        applyPrebuild(e, req);
        return repo.save(e);
    }

    @Transactional
    public RepoCatalogEntry update(Long id, RepoCatalogDto.UpsertRequest req) {
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id));
        if (!e.getAlias().equals(req.alias())) {
            repo.findByAlias(req.alias()).ifPresent(other -> {
                throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 별칭: " + req.alias());
            });
        }
        RepoUrlParser.Parsed p = parseOrThrow(req.gitUrl());
        repo.findByGitUrl(p.canonicalUrl()).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new TaskException(HttpStatus.CONFLICT, "이미 등록된 Git URL: " + p.canonicalUrl());
            }
        });
        e.setAlias(req.alias());
        e.setGitUrl(p.canonicalUrl());
        e.setHost(p.host());
        e.setOwnerRepo(p.ownerRepo());
        e.setDefaultBranch(blankToNull(req.defaultBranch()));
        e.setDescription(req.description());
        if (req.enabled() != null) e.setEnabled(req.enabled());
        applyArtifact(e, req);
        applyPrebuild(e, req);
        e.setUpdatedAt(OffsetDateTime.now());
        return e;
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id);
        }
        repo.deleteById(id);
    }

    /** 작업/인터뷰 등록 시: id → 검증된 경로 + 스냅샷 필드. 비활성/누락/지원하지 않는 호스트 거절. */
    @Transactional(readOnly = true)
    public ResolvedRepo resolveForRegistration(Long id) {
        if (id == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "repoCatalogId가 필요합니다");
        }
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 레포 카탈로그 id: " + id));
        if (!e.isEnabled()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: " + e.getAlias());
        }
        RepoUrlParser.Parsed p = reparse(e);
        return new ResolvedRepo(e.getId(), e.getAlias(), p.canonicalUrl(),
                p.host(), p.ownerRepo(), e.getDefaultBranch());
    }

    /**
     * 참조 repo 입력의 모양 검사 — 카탈로그를 조회하지 않는다 (스펙 2026-10-22 §4.1의 1·3·4번). 위반이면 400.
     * 순서는 상한({@link #MAX_REF_REPOS}) → 대상과 같은 카탈로그 id → 참조끼리 같은 카탈로그 id다.
     * 널 원소는 세지도 검사하지도 않고, 카탈로그 id가 빠진 항목도 여기서는 걸러내지 않는다
     * ({@link #resolveForRegistration}이 "repoCatalogId가 필요합니다"로 거절한다).
     * 대화 중 변경은 결과 집합을 이 메서드로 먼저 검사한 뒤 새로 들어오는 항목만 {@link #resolveReferences}에 넘긴다.
     */
    public static void checkRefShape(Long targetCatalogId, List<RefRepoInput> inputs) {
        if (inputs == null) return;
        List<RefRepoInput> refs = inputs.stream().filter(Objects::nonNull).toList();
        if (refs.size() > MAX_REF_REPOS) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "참조 repo는 최대 " + MAX_REF_REPOS + "개까지 고를 수 있습니다");
        }
        for (RefRepoInput ref : refs) {
            if (ref.repoCatalogId() != null && ref.repoCatalogId().equals(targetCatalogId)) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "대상 레포는 참조로 고를 수 없습니다");
            }
        }
        Set<Long> seen = new HashSet<>();
        for (RefRepoInput ref : refs) {
            if (ref.repoCatalogId() != null && !seen.add(ref.repoCatalogId())) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "같은 레포를 참조로 두 번 고를 수 없습니다");
            }
        }
    }

    /**
     * 질문 등록 때 고른 참조 repo → 세션에 박제할 스냅샷 (스펙 2026-10-22 §4.1). 입력 순서를 지킨다.
     * null·빈 목록(널 원소뿐인 목록 포함)은 참조 없음이다. 하나라도 거절되면 전체가 400이다.
     * 순서: {@link #checkRefShape}(상한·대상·중복, 조회 없음) → 항목마다 {@link #resolveForRegistration}
     * (없음·비활성·지원하지 않는 호스트) → 브랜치 결정(비면 카탈로그 기본, 그것도 없으면 400).
     * 브랜치가 실제로 있는지는 확인하지 않는다 — 러너가 받지 못하면 칩에 ⚠로 보인다.
     * sha·fetchedAt·error는 러너가 보고로 채우므로 비워 둔다.
     */
    @Transactional(readOnly = true)
    public List<RefRepo> resolveReferences(Long targetCatalogId, List<RefRepoInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        checkRefShape(targetCatalogId, inputs);
        List<RefRepo> resolved = new ArrayList<>();
        for (RefRepoInput input : inputs) {
            if (input == null) continue;
            ResolvedRepo r = resolveForRegistration(input.repoCatalogId());
            resolved.add(new RefRepo(r.catalogId(), r.alias(), r.ownerRepo(), r.gitUrl(),
                    refBranch(input.branch(), r), null, null, null));
        }
        return List.copyOf(resolved);
    }

    /** 참조 브랜치 — 입력(앞뒤 공백 제거) → 카탈로그 기본 브랜치 → 둘 다 비면 400. */
    private static String refBranch(String requested, ResolvedRepo r) {
        String branch = blankToNull(requested);
        if (branch == null) branch = blankToNull(r.defaultBranch());
        if (branch == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "참조 브랜치를 지정하세요");
        }
        return branch;
    }

    /** 라이브 도달성 체크 — ls-remote 성공 여부. DB 영속 안 함. */
    @Transactional(readOnly = true)
    public RepoCatalogDto.CheckResult check(Long id) {
        RepoCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "레포 카탈로그 항목 없음: " + id));
        RepoUrlParser.Parsed p;
        try {
            p = reparse(e);
        } catch (TaskException ex) {
            return new RepoCatalogDto.CheckResult(false, null, 0, "GitHub/사내 GitLab 레포만 확인 가능");
        }
        try {
            var res = gitRefService.listBranches(RepoRef.fromSnapshot(p.ownerRepo(), p.canonicalUrl()));
            return new RepoCatalogDto.CheckResult(true, res.defaultBranch(), res.branches().size(), null);
        } catch (TaskException ex) {
            return new RepoCatalogDto.CheckResult(false, null, 0, ex.getMessage());
        }
    }

    private RepoUrlParser.Parsed parseOrThrow(String gitUrl) {
        try {
            return RepoUrlParser.parse(gitUrl, gitlabBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new TaskException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 저장된 host/owner_repo는 저장 시점 해석의 캐시다. GITLAB_BASE_URL 설정 전에 등록돼
     * 'other'로 남은 GitLab 행이 있을 수 있으므로 사용 시점에 git_url을 다시 해석한다.
     */
    private RepoUrlParser.Parsed reparse(RepoCatalogEntry e) {
        RepoUrlParser.Parsed p = parseOrThrow(e.getGitUrl());
        if ("other".equals(p.host()) || p.ownerRepo() == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 호스트입니다(GitHub/사내 GitLab만 가능 — GITLAB_BASE_URL 설정 확인): " + e.getAlias());
        }
        return p;
    }

    /** 브랜치 조회용: 활성 카탈로그 id → RepoRef. */
    @Transactional(readOnly = true)
    public RepoRef refOf(Long id) {
        ResolvedRepo r = resolveForRegistration(id);
        return RepoRef.fromSnapshot(r.ownerRepo(), r.gitUrl());
    }

    public RepoCatalogDto.View toView(RepoCatalogEntry e) {
        try {
            RepoUrlParser.Parsed p = RepoUrlParser.parse(e.getGitUrl(), gitlabBaseUrl);
            return RepoCatalogDto.View.of(e, p.host(), p.ownerRepo());
        } catch (IllegalArgumentException ex) {
            return RepoCatalogDto.View.of(e);
        }
    }

    /** artifactEnabled가 null이면(활성화 토글 등) 산출물 설정을 그대로 둔다. */
    private static void applyArtifact(RepoCatalogEntry e, RepoCatalogDto.UpsertRequest req) {
        if (req.artifactEnabled() == null) return;
        e.setArtifactEnabled(req.artifactEnabled());
        e.setArtifactPath(blankToNull(req.artifactPath()));
    }

    /**
     * prebuildEnabled가 null이면(활성화 토글 등) 미리 빌드 설정 4개를 그대로 둔다 (스펙 2026-10-28 §3).
     * 켜면 검증 후 앞뒤 공백을 뺀 값을, 끄면 검증 없이 값을 보존한다(빈 문자열은 null).
     */
    private static void applyPrebuild(RepoCatalogEntry e, RepoCatalogDto.UpsertRequest req) {
        if (req.prebuildEnabled() == null) return;
        if (req.prebuildEnabled()) {
            validatePrebuild(req.prebuildCommand(), req.prebuildArtifact(), req.prebuildToolchain());
        }
        e.setPrebuildEnabled(req.prebuildEnabled());
        e.setPrebuildCommand(blankToNull(req.prebuildCommand()));
        e.setPrebuildArtifact(blankToNull(req.prebuildArtifact()));
        e.setPrebuildToolchain(blankToNull(req.prebuildToolchain()));
    }

    private static final Pattern PREBUILD_TOOLCHAIN = Pattern.compile("^[A-Za-z0-9._-]{1,50}$");

    /**
     * 미리 빌드를 켤 때의 입력 검증. 위반이면 400. 순서: 명령 필수 → 한 줄 → 산출물 필수 → 상대 경로 → * 위치 → 도구 이름.
     * 경로는 {@code /}·{@code \} 둘 다 구분자로 본다(워커가 Windows일 수 있다).
     */
    static void validatePrebuild(String command, String artifact, String toolchain) {
        String cmd = blankToNull(command);
        if (cmd == null) throw badRequest("미리 빌드 명령이 필요합니다");
        if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) throw badRequest("미리 빌드 명령은 한 줄이어야 합니다");
        String art = blankToNull(artifact);
        if (art == null) throw badRequest("미리 빌드 산출물 경로가 필요합니다");

        String[] parts = art.split("[/\\\\]", -1);
        boolean drive = art.length() >= 2 && art.charAt(1) == ':' && Character.isLetter(art.charAt(0));
        boolean dotdot = false;
        for (String part : parts) {
            if (part.equals("..")) dotdot = true;
        }
        if (art.startsWith("/") || art.startsWith("\\") || drive || dotdot) {
            throw badRequest("미리 빌드 산출물 경로는 레포 안 상대 경로여야 합니다");
        }
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].indexOf('*') >= 0) {
                throw badRequest("미리 빌드 산출물 경로의 *는 마지막 이름에만 쓸 수 있습니다");
            }
        }

        String tool = blankToNull(toolchain);
        if (tool != null && !PREBUILD_TOOLCHAIN.matcher(tool).matches()) {
            throw badRequest("미리 빌드 도구 이름은 영문·숫자·. _ -로 50자 이하여야 합니다");
        }
    }

    private static TaskException badRequest(String message) {
        return new TaskException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
