package com.hamonsoft.netismaker.workerdaemon.patch;

import java.util.List;

/** 부분 패치의 "전" 커밋 결정 — 병합 커밋의 부모와 병합 직전에 기록한 기준 브랜치 끝을 맞춰 본다. */
public final class PatchBase {

    private static final String PREFIX = "패치 기준 커밋을 확인할 수 없습니다: ";

    private PatchBase() {}

    /**
     * @param mergeSha      병합 커밋(기록 없으면 실패)
     * @param recordedBase  병합 직전에 읽어 둔 기준 브랜치 끝(없을 수 있음)
     * @param parentsOfAfter 병합 커밋의 부모들(첫째가 기준 브랜치 쪽)
     * @return "전" sha
     */
    public static String resolve(String mergeSha, String recordedBase, List<String> parentsOfAfter)
            throws PatchException {
        if (mergeSha == null || mergeSha.isBlank()) throw new PatchException(PREFIX + "병합 커밋이 기록되지 않았습니다");
        if (parentsOfAfter == null || parentsOfAfter.isEmpty()) {
            throw new PatchException(PREFIX + "병합 커밋의 부모를 읽지 못했습니다");
        }
        String first = parentsOfAfter.get(0);
        if (recordedBase != null && !recordedBase.isBlank()) {
            if (!recordedBase.equals(first)) {
                throw new PatchException(PREFIX + "병합 직전 기준 브랜치 끝(" + abbrev(recordedBase)
                        + ")과 병합 커밋의 부모(" + abbrev(first) + ")가 다릅니다");
            }
            return first;
        }
        if (parentsOfAfter.size() != 2) {
            throw new PatchException(PREFIX + "병합 직전 기준 브랜치를 기록하지 못했고 병합 커밋이 아닙니다");
        }
        return first;
    }

    private static String abbrev(String sha) {
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }
}
