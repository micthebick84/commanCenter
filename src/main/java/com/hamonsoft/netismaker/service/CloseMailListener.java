package com.hamonsoft.netismaker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 작업종료 커밋 뒤 메일을 1회 보낸다 (스펙 2026-10-14 §7). SMTP(최대 10초)가 워커 결과 보고 응답을 붙잡지 않도록
 * 전용 단일 스레드에서 돈다. @EnableAsync + 실행기 빈은 Boot 기본 applicationTaskExecutor를 꺼서 쓰지 않는다.
 */
@Component
@Profile("api")
@Slf4j
public class CloseMailListener implements DisposableBean {

    private final CloseMailService mail;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "close-mail");
        t.setDaemon(true);
        return t;
    });

    public CloseMailListener(CloseMailService mail) {
        this.mail = mail;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(CloseCompletedEvent e) {
        executor.submit(() -> {
            try {
                mail.send(e.taskId());
            } catch (RuntimeException ex) {
                log.warn("종료 메일 처리 실패: task={} {}", e.taskId(), ex.getMessage());
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
