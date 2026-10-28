package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 작업 종료(kind=CLOSE) 설정 (스펙 2026-10-13 §4). WorkerProperties와 분리 — 기존 생성자 호출부를 건드리지 않는다.
 *  obsidian-vault-dir  볼트 루트. 비면 노트 단계가 '미설정'으로 실패한다(종료실패 → 설정 후 재시도).
 *  note-folder         진행상황 노트 폴더(기본 HamonSoft/netisMaker). 레포 별칭이 없을 때의 노트 폴더이고, 첫 단계(HamonSoft)는
 *                      노트 폴더 후보 루트다 — 노트는 레포 기준 폴더에 쓴다(스펙 2026-10-28 close-note-folder)
 *  merge-method        merge | squash | rebase (GitLab은 rebase를 merge로 취급)
 */
@ConfigurationProperties(prefix = "netis-maker.worker.close")
public record CloseProperties(
        String obsidianVaultDir,
        String noteFolder,
        String mergeMethod,
        String noteModel,
        Duration noteTimeout,
        String notePromptTemplate
) {
    private static final Set<String> METHODS = Set.of("merge", "squash", "rebase");

    public CloseProperties {
        obsidianVaultDir = (obsidianVaultDir == null || obsidianVaultDir.isBlank()) ? null : obsidianVaultDir.trim();
        noteFolder = (noteFolder == null || noteFolder.isBlank()) ? "HamonSoft/netisMaker" : noteFolder.trim();
        mergeMethod = (mergeMethod == null || mergeMethod.isBlank()) ? "merge" : mergeMethod.trim().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(mergeMethod)) {
            throw new IllegalArgumentException("CLOSE_MERGE_METHOD는 merge|squash|rebase 중 하나여야 한다: '" + mergeMethod + "'");
        }
        noteModel = (noteModel == null || noteModel.isBlank()) ? "sonnet" : noteModel.trim();
        if (noteTimeout == null) noteTimeout = Duration.ofMinutes(10);
    }

    public Path vaultDir() {
        return obsidianVaultDir == null ? null : Path.of(obsidianVaultDir);
    }
}
