package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
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
import java.util.List;

@Service
@Profile("api")
public class RepoCatalogService {

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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
