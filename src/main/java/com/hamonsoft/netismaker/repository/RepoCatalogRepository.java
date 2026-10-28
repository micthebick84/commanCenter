package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoCatalogRepository extends JpaRepository<RepoCatalogEntry, Long> {
    List<RepoCatalogEntry> findByEnabledTrueOrderByAlias();
    List<RepoCatalogEntry> findAllByOrderByAlias();
    Optional<RepoCatalogEntry> findByAlias(String alias);
    Optional<RepoCatalogEntry> findByGitUrl(String gitUrl);

    /**
     * 종료 노트 폴더를 비어 있을 때만 기록 (스펙 2026-10-28 close-note-folder §3-1). 같은 레포 작업 둘이 동시에 정해도
     * 먼저 커밋한 값이 남는다(작업 행 잠금은 카탈로그 행을 보호하지 않는다). 반환 = 바뀐 행 수.
     */
    @Modifying
    @Query("update RepoCatalogEntry e set e.noteFolder = :folder where e.id = :id and e.noteFolder is null")
    int recordNoteFolderIfEmpty(@Param("id") Long id, @Param("folder") String folder);

    /** 작업의 카탈로그 항목 — repo_catalog_id 우선, 없으면 git_url. */
    static Optional<RepoCatalogEntry> findForTask(RepoCatalogRepository repo, Long catalogId, String gitUrl) {
        if (catalogId != null) return repo.findById(catalogId);
        if (gitUrl != null) return repo.findByGitUrl(gitUrl);
        return Optional.empty();
    }
}
