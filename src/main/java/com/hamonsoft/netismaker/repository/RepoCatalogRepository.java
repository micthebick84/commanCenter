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
}
