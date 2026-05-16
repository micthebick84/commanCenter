package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpCatalogRepository extends JpaRepository<McpCatalogEntry, Long> {
    List<McpCatalogEntry> findByEnabledTrueOrderByDisplayName();
    List<McpCatalogEntry> findAllByOrderByDisplayName();
    Optional<McpCatalogEntry> findByName(String name);
}
