package com.hamonsoft.netismaker.workerdaemon.patch;

import java.util.List;

/** 전·후 비교 결과. 세 목록은 모두 오름차순이다. */
public record PatchDelta(List<String> added, List<String> changed, List<String> deleted) {

    public boolean isEmpty() {
        return added.isEmpty() && changed.isEmpty() && deleted.isEmpty();
    }

    /** 패치에 실리는 파일 수(추가 + 변경). 삭제는 파일을 싣지 않는다. */
    public int fileCount() {
        return added.size() + changed.size();
    }
}
