package com.hamonsoft.netismaker.service.applog;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** {@link AppLogSink}의 운영 구현 — Spring {@link SseEmitter}로 보낸다. */
public final class SseEmitterSink implements AppLogSink {

    private final SseEmitter emitter;

    public SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void event(String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    @Override
    public void comment(String text) throws IOException {
        emitter.send(SseEmitter.event().comment(text));
    }

    /** 이미 끝난 emitter(타임아웃·오류 콜백 뒤)의 complete 예외는 삼킨다. */
    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 이미 닫힘
        }
    }
}
