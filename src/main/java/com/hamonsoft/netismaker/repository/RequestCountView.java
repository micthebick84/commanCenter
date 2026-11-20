package com.hamonsoft.netismaker.repository;

/** 요청 id별 개수 묶음 조회 결과(목록의 첨부·답글 수). */
public interface RequestCountView {
    Long getRequestId();

    long getTotal();
}
