package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GC 결정 로직 (순수 함수, docker I/O 없음 → 단위 테스트 용이).
 *
 *  보존: 실행 중 컨테이너 + 그 이미지, task repo당 최신 keepImagesPerTask개 태그.
 *  제거: grace 지난 비실행 owned 컨테이너, 보존 외 owned 이미지 태그.
 *  소유 식별: 컨테이너 이름/이미지 repo 접두사 "netis-task-".
 */
public final class DockerGcPlanner {

    public static final String OWN_PREFIX = "netis-task-";

    private DockerGcPlanner() {}

    /** @param createdAt 비실행 컨테이너의 생성 시각(grace 판정용). 실행 중이면 무시. */
    public record ContainerInfo(String name, String state, OffsetDateTime createdAt, String imageRef) {}

    /** images는 docker 기본 정렬(최신순)으로 전달한다. */
    public record ImageInfo(String repoTag, String id) {}

    public record GcPlan(List<String> containersToRemove, List<String> imagesToRemove) {}

    public static GcPlan plan(List<ContainerInfo> containers, List<ImageInfo> images,
                              OffsetDateTime now, int orphanGraceMinutes, int keepImagesPerTask) {
        OffsetDateTime graceBefore = now.minusMinutes(orphanGraceMinutes);
        Set<String> inUseImages = new HashSet<>();
        List<String> containersToRemove = new ArrayList<>();

        for (ContainerInfo c : containers) {
            if (c.name() == null || !c.name().startsWith(OWN_PREFIX)) continue; // 소유 아님
            boolean running = "running".equalsIgnoreCase(c.state());
            if (running) {
                if (c.imageRef() != null) inUseImages.add(c.imageRef());
            } else if (c.createdAt() != null && c.createdAt().isBefore(graceBefore)) {
                containersToRemove.add(c.name());
            }
        }

        // repo별 최신순 태그를 keep 개수만큼 보존. 사용중 이미지는 무조건 보존하되 keep 카운트에도 포함.
        Map<String, Integer> keptPerRepo = new LinkedHashMap<>();
        List<String> imagesToRemove = new ArrayList<>();
        for (ImageInfo img : images) {
            String repoTag = img.repoTag();
            if (repoTag == null || !repoTag.startsWith(OWN_PREFIX)) continue; // 소유 아님
            String repo = repoTag.contains(":") ? repoTag.substring(0, repoTag.lastIndexOf(':')) : repoTag;
            if (inUseImages.contains(repoTag)) {
                // 실행 중 컨테이너 이미지 → 보존하되 keep 카운트에 포함
                keptPerRepo.merge(repo, 1, Integer::sum);
                continue;
            }
            int kept = keptPerRepo.getOrDefault(repo, 0);
            if (kept < keepImagesPerTask) {
                keptPerRepo.put(repo, kept + 1); // 최신순이므로 앞에서부터 보존
            } else {
                imagesToRemove.add(repoTag);
            }
        }
        return new GcPlan(containersToRemove, imagesToRemove);
    }
}
