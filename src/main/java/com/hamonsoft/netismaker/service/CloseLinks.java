package com.hamonsoft.netismaker.service;

/** 메일·노트에 쓰는 Symphony 링크 (스펙 2026-10-14 §4·§7). */
public final class CloseLinks {

    private CloseLinks() {}

    /** {base}/tasks/{id}. base(APP_PUBLIC_BASE_URL)가 비면 null — 호출부는 링크 없이 안내 문구만 쓴다. */
    public static String taskUrl(String base, Long taskId) {
        if (base == null || base.isBlank() || taskId == null) return null;
        return base.strip().replaceAll("/+$", "") + "/tasks/" + taskId;
    }
}
