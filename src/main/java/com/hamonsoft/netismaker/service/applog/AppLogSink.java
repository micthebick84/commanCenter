package com.hamonsoft.netismaker.service.applog;

import java.io.IOException;

/**
 * 앱 로그 스트림이 이벤트를 내보내는 곳. 운영은 {@link SseEmitterSink}이고, 단위 시험은 기록용 가짜를 넣는다.
 * 한 스트림에서 이 sink를 부르는 스레드는 언제나 하나다 — open 중에는 요청 스레드, 그 뒤에는 그 스트림의 전송 스레드.
 */
public interface AppLogSink {

    /** 이름 있는 이벤트(`lines`·`info`·`end`)를 JSON 데이터로 보낸다. 받는 쪽이 끊겼으면 IOException. */
    void event(String name, Object data) throws IOException;

    /** SSE 주석(`:text`)을 보낸다 — 핑에 쓴다. */
    void comment(String text) throws IOException;

    /** 응답을 끝낸다. 이미 끝났어도 예외를 내지 않는다. */
    void complete();
}
