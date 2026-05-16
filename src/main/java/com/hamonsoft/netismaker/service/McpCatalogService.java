package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.McpCatalogDto;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.repository.McpCatalogRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@Profile("api")
public class McpCatalogService {

    private final McpCatalogRepository repo;

    public McpCatalogService(McpCatalogRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<McpCatalogEntry> listEnabled() {
        return repo.findByEnabledTrueOrderByDisplayName();
    }

    @Transactional(readOnly = true)
    public List<McpCatalogEntry> listAll() {
        return repo.findAllByOrderByDisplayName();
    }

    @Transactional(readOnly = true)
    public List<McpCatalogEntry> resolveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return repo.findAllById(ids);
    }

    @Transactional
    public McpCatalogEntry create(McpCatalogDto.UpsertRequest req, String adminId) {
        repo.findByName(req.name()).ifPresent(existing -> {
            throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 name: " + req.name());
        });
        McpCatalogEntry e = McpCatalogEntry.create(req.name(), req.displayName(), req.url(),
                req.transport(), req.description(), adminId);
        if (req.enabled() != null) e.setEnabled(req.enabled());
        return repo.save(e);
    }

    @Transactional
    public McpCatalogEntry update(Long id, McpCatalogDto.UpsertRequest req) {
        McpCatalogEntry e = repo.findById(id)
                .orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "카탈로그 항목 없음: " + id));
        // name 변경 시 충돌 검증
        if (!e.getName().equals(req.name())) {
            repo.findByName(req.name()).ifPresent(other -> {
                throw new TaskException(HttpStatus.CONFLICT, "이미 존재하는 name: " + req.name());
            });
        }
        e.setName(req.name());
        e.setDisplayName(req.displayName());
        e.setUrl(req.url());
        e.setTransport(req.transport());
        e.setDescription(req.description());
        if (req.enabled() != null) e.setEnabled(req.enabled());
        e.setUpdatedAt(OffsetDateTime.now());
        return e;
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new TaskException(HttpStatus.NOT_FOUND, "카탈로그 항목 없음: " + id);
        }
        repo.deleteById(id);
    }
}
