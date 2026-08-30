package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.McpCatalogDto;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.McpCatalogRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    /** 카탈로그 id 리스트 → 스냅샷 스펙. 비활성/누락 id는 거절(400). 작업 승인과 질문 등록이 공유한다. */
    @Transactional(readOnly = true)
    public List<TaskMcpSpec> resolveExtras(List<Long> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) return new ArrayList<>();
        List<McpCatalogEntry> entries = resolveByIds(catalogIds);
        if (entries.size() != catalogIds.size()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 MCP 카탈로그 id 포함. 요청=" + catalogIds.size() + " 매칭=" + entries.size());
        }
        List<TaskMcpSpec> out = new ArrayList<>(entries.size());
        for (McpCatalogEntry e : entries) {
            if (!e.isEnabled()) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 MCP 카탈로그 항목: " + e.getName());
            }
            out.add(new TaskMcpSpec(e.getName(), e.getUrl(), e.getTransport()));
        }
        return out;
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
