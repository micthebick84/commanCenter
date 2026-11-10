package com.hamonsoft.netismaker.workerdaemon.patch;

/** 부분 패치 만들기 실패. 메시지가 그대로 실패 사유다. */
public class PatchException extends Exception {

    public PatchException(String message) {
        super(message);
    }
}
