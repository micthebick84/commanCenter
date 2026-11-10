package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 산출물·부분 패치 내려받기 (스펙 2026-10-14 §6, 2026-11-10) — 권한 확인 뒤 1회용 주소, 그 주소로 파일. */
@Slf4j
@Service
@Profile("api")
public class ArtifactDownloadService {

    public record Link(String url, long expiresInSec) {}

    public record Download(Path file, String name, long size) {}

    private static final String URL_PREFIX = "/api/artifact-downloads/";

    private final TaskRepository taskRepo;
    private final ArtifactStore store;
    private final PatchStore patchStore;
    private final ArtifactDownloadTokens tokens;

    public ArtifactDownloadService(TaskRepository taskRepo, ArtifactStore store, PatchStore patchStore,
                                   ArtifactDownloadTokens tokens) {
        this.taskRepo = taskRepo;
        this.store = store;
        this.patchStore = patchStore;
        this.tokens = tokens;
    }

    /** 판정 순서(스펙 §6): 작업 없음 404 → 권한(등록자 본인 또는 관리자, 아니면 403) → 산출물 기록 없음 404 → 파일 없음 410. */
    @Transactional(readOnly = true)
    public Link link(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) throw TaskException.forbidden();
        if (t.getArtifactName() == null) throw new TaskException(HttpStatus.NOT_FOUND, "보관한 산출물이 없습니다");
        if (!store.exists(taskId, t.getArtifactName())) {
            throw new TaskException(HttpStatus.GONE, "보관 파일이 정리되었습니다");
        }
        return new Link(URL_PREFIX + tokens.issue(taskId, ArtifactDownloadTokens.Kind.ARTIFACT),
                ArtifactDownloadTokens.TTL.toSeconds());
    }

    /** 부분 패치 — 판정 순서는 {@link #link}와 같다: 작업 없음 404 → 403 → 패치 기록 없음 404 → 파일 없음 410. */
    @Transactional(readOnly = true)
    public Link patchLink(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) throw TaskException.forbidden();
        if (t.getPatchName() == null) throw new TaskException(HttpStatus.NOT_FOUND, "보관한 패치가 없습니다");
        if (!patchStore.exists(taskId)) {
            throw new TaskException(HttpStatus.GONE, "보관 파일이 정리되었습니다");
        }
        return new Link(URL_PREFIX + tokens.issue(taskId, ArtifactDownloadTokens.Kind.PATCH),
                ArtifactDownloadTokens.TTL.toSeconds());
    }

    @Transactional(readOnly = true)
    public Download redeem(String token) {
        ArtifactDownloadTokens.Ticket ticket = tokens.redeem(token).orElseThrow(TaskException::notFound);
        long taskId = ticket.taskId();
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        String name;
        Path f;
        if (ticket.kind() == ArtifactDownloadTokens.Kind.PATCH) {
            if (t.getPatchName() == null || !patchStore.exists(taskId)) throw TaskException.notFound();
            name = t.getPatchName();
            f = patchStore.file(taskId);
        } else {
            if (t.getArtifactName() == null || !store.exists(taskId, t.getArtifactName())) throw TaskException.notFound();
            name = t.getArtifactName();
            f = store.file(taskId, name);
        }
        try {
            return new Download(f, name, Files.size(f));
        } catch (IOException e) {
            log.warn("내려받기 파일 크기 조회 실패 — 404로 응답: taskId={}, kind={}", taskId, ticket.kind(), e);
            throw TaskException.notFound();
        }
    }
}
