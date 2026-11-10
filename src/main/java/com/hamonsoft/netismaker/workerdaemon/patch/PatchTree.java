package com.hamonsoft.netismaker.workerdaemon.patch;

import java.util.SortedMap;

/**
 * 산출물의 항목별 해시 목록.
 *
 * @param shape  산출물 구성(예: "single:war", "bundle:[a.war, b.war]"). 전·후가 같아야 비교할 수 있다.
 * @param hashes 패치 안 배포 경로(patch/ 접두 없음) → 해시
 */
public record PatchTree(String shape, SortedMap<String, String> hashes) {
}
