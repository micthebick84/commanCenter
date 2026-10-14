package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoCatalogRepository extends JpaRepository<RepoCatalogEntry, Long> {
    List<RepoCatalogEntry> findByEnabledTrueOrderByAlias();
    List<RepoCatalogEntry> findAllByOrderByAlias();
    Optional<RepoCatalogEntry> findByAlias(String alias);
    Optional<RepoCatalogEntry> findByGitUrl(String gitUrl);

    /** 작업의 카탈로그 항목 — repo_catalog_id 우선, 없으면 git_url. */
    static Optional<RepoCatalogEntry> findForTask(RepoCatalogRepository repo, Long catalogId, String gitUrl) {
        if (catalogId != null) return repo.findById(catalogId);
        if (gitUrl != null) return repo.findByGitUrl(gitUrl);
        return Optional.empty();
    }
}
