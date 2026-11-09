package com.hamonsoft.netismaker.service.applog;

/**
 * 화면으로 나가는 앱 로그 한 줄. SSE `lines` 이벤트의 JSON에 칸 이름이 그대로 실린다(`ts`·`stream`·`text`).
 *
 * @param ts     docker가 붙인 시각(`--timestamps`, RFC3339Nano) 그대로
 * @param stream `out`(stdout) 또는 `err`(stderr)
 * @param text   시각 접두를 뗀 본문 — 색 코드를 지우고, 비밀을 가리고, 상한에서 자른 뒤의 값
 */
public record AppLogLine(String ts, String stream, String text) {
}
