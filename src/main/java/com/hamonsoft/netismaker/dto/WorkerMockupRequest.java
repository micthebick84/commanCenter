package com.hamonsoft.netismaker.dto;

/**
 * 워커(인터뷰 서비스)가 올리는 목업 한 건 (스펙 2026-11-03 §4·§5) — {@code POST /worker/interviews/{id}/mockups?workerId=…}의 바디.
 *
 * <p>Bean Validation을 달지 않는다: {@code @Valid} 실패는 {@code GlobalExceptionHandler}가 "검증 오류" 고정 문구로 돌려줘
 * 사유가 에이전트에게 가지 않는다. 형식·길이 검사와 사유 문구는 {@code InterviewMockupService.upload}가 맡는다.
 *
 * <p>html은 최대 200KB라 {@link #toString()}에 싣지 않는다(예외 메시지·로그에 본문이 새지 않게) — 길이만.
 *
 * @param name        에이전트가 정한 짧은 이름 — 같은 이름으로 다시 올리면 새 버전
 * @param title       카드·목록·새 탭에 보이는 제목(줄바꿈·제어문자는 서비스가 공백으로 바꾼다)
 * @param description 카드 제목 아래 설명 한 줄(선택)
 * @param html        완전한 HTML 문서
 */
public record WorkerMockupRequest(String name, String title, String description, String html) {

    @Override
    public String toString() {
        return "WorkerMockupRequest{name=" + name + ", title=" + title + ", description=" + description
                + ", htmlLength=" + (html == null ? 0 : html.length()) + "}";
    }
}
