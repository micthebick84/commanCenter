package com.hamonsoft.netismaker.workerdaemon.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 병합 전·후 빌드 결과의 해시 목록을 비교한다. */
public final class PatchDiff {

    private PatchDiff() {
    }

    public static PatchDelta diff(PatchTree before, PatchTree after) throws PatchException {
        if (!before.shape().equals(after.shape())) {
            throw new PatchException("패치 전·후 빌드 결과의 구성이 다릅니다");
        }
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, String> e : after.hashes().entrySet()) {
            String old = before.hashes().get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String path : before.hashes().keySet()) {
            if (!after.hashes().containsKey(path)) {
                deleted.add(path);
            }
        }
        // SortedMap 순회라 이미 오름차순이다.
        return new PatchDelta(List.copyOf(added), List.copyOf(changed), List.copyOf(deleted));
    }
}
