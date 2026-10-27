package com.hamonsoft.netismaker.service.render;

/** 렌더링 실패 — getMessage()는 화면·render_error에 그대로 보여도 되는 짧은 사유다(경로·스택 없음). */
public class RenderException extends Exception {

    public RenderException(String userMessage) {
        super(userMessage);
    }

    public RenderException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
