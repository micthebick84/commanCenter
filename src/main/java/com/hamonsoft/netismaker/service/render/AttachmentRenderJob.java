package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.service.AttachmentStorage;
import com.hamonsoft.netismaker.util.FileTrees;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 슬라이드 렌더링 잡 (스펙 2026-10-27 §6). 전용 단일 스레드({@value #THREAD_NAME})에서 한 번에 1건 — API의 공유 스케줄러
 * 스레드(풀 1)를 붙잡지 않는다. 기동 때 작업 폴더를 비우고 멈춘 RUNNING을 회수한 뒤 바로 한 번 비우고, 이후
 * {@value #POLL_SECONDS}초 고정 지연 폴링(매 회차 회수 포함) + 업로드 커밋 뒤 이벤트로 깨운다. 렌더러 호출은 트랜잭션 밖이다.
 * 렌더링이 꺼져 있으면(설정 off·soffice 없음) 아무것도 하지 않는다 — 작업 폴더도 건드리지 않는다.
 */
@Slf4j
@Component
@Profile("api")
public class AttachmentRenderJob {

    static final String THREAD_NAME = "attachment-render";
    static final long POLL_SECONDS = 5;
    static final String MISSING_SOURCE = "원본 파일 없음";
    static final String UNEXPECTED_FAILURE = "렌더링 실패";

    private final AttachmentRenderQueue queue;
    private final SlideRenderer renderer;
    private final AttachmentStorage storage;
    private final RenderTargetPolicy policy;
    private final RenderProperties props;
    private final ScheduledExecutorService executor;

    public AttachmentRenderJob(AttachmentRenderQueue queue, SlideRenderer renderer, AttachmentStorage storage,
                               RenderTargetPolicy policy, RenderProperties props) {
        this.queue = queue;
        this.renderer = renderer;
        this.storage = storage;
        this.policy = policy;
        this.props = props;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        start();
    }

    /** 기동 정리 + 첫 drain을 executor에 넣고 고정 지연 폴링을 건다. 꺼짐이면 아무것도 하지 않는다. */
    void start() {
        if (!policy.active()) return;
        submit(() -> {
            clearWorkRoot();
            recoverStale();
            drain();
        });
        executor.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** 업로드 커밋 뒤 깨우기 — 같은 단일 스레드에 drain을 넣는다(직렬 보장). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(AttachmentRenderRequested event) {
        if (!policy.active()) return;
        submit(this::drain);
    }

    /** 대기 건이 없을 때까지 처리한다. 큐(DB) 오류는 로그만 남기고 이번 회차를 끝낸다 — 예외를 executor로 내보내지 않는다. */
    void drain() {
        try {
            while (drainOnce()) {
                // 한 건씩 계속
            }
        } catch (Throwable t) {
            log.warn("슬라이드 렌더링: 큐 처리 중단 — 다음 회차에 재시도 ({})", t.toString());
        }
    }

    /**
     * 한 건 처리 — 없으면(또는 꺼짐이면) false. 렌더러가 던진 것은 무엇이든(Error 포함) 그 건의 실패로 기록하고 true를
     * 돌려 다음 건으로 넘어간다. 큐 호출(claim·complete·fail)의 예외만 밖으로 나간다(행은 RUNNING으로 남아 회수 대상).
     */
    boolean drainOnce() {
        if (!policy.active()) return false;
        Optional<RenderJobRef> claimed = queue.claimNext();
        if (claimed.isEmpty()) return false;
        RenderJobRef ref = claimed.get();

        String renderDirRel;
        RenderResult result;
        try {
            Path source = storage.resolve(ref.storedPath());
            if (!Files.isRegularFile(source)) {
                log.warn("슬라이드 렌더링: 원본 파일 없음 — {} #{}", ref.kind(), ref.attachmentId());
                queue.fail(ref, MISSING_SOURCE);
                return true;
            }
            renderDirRel = storage.renderDirRelativePath(ref.storedPath());
            result = renderer.render(source, extensionOf(ref.originalFilename()), storage.resolve(renderDirRel));
        } catch (RenderException e) {
            log.info("슬라이드 렌더링 실패: {} #{} — {}", ref.kind(), ref.attachmentId(), e.getMessage());
            queue.fail(ref, e.getMessage());
            return true;
        } catch (Throwable t) {
            log.error("슬라이드 렌더링: 예상 밖 오류 — {} #{}", ref.kind(), ref.attachmentId(), t);
            queue.fail(ref, UNEXPECTED_FAILURE);
            return true;
        }
        queue.complete(ref, renderDirRel, result);
        log.info("슬라이드 렌더링 완료: {} #{} — {}/{}장, 원본 그림 {}개", ref.kind(), ref.attachmentId(),
                result.pageCount(), result.totalPages(), result.mediaCount());
        return true;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 폴링 회차 — 회수 + drain. 예외가 새면 scheduleWithFixedDelay가 이후 회차를 끊으므로 모두 여기서 막는다. */
    private void tick() {
        try {
            recoverStale();
        } catch (Throwable t) {
            log.warn("슬라이드 렌더링: 멈춘 RUNNING 회수 실패 ({})", t.toString());
        }
        drain();
    }

    /** 상한의 2배보다 오래된 RUNNING — 단일 스레드라 회수 시점에 이 프로세스가 처리 중인 건은 없다. */
    private void recoverStale() {
        queue.recoverStale(OffsetDateTime.now().minusSeconds(2L * props.timeoutSeconds()));
    }

    /** 이전 프로세스가 남긴 .render-work/* 를 지운다(스펙 §6). 실패는 경고만 — 렌더러는 실행마다 새 uuid 폴더를 쓴다. */
    private void clearWorkRoot() {
        Path root = storage.renderWorkRoot();
        if (!Files.exists(root)) return;
        FileTrees.DeleteResult r = FileTrees.deleteTree(root);
        if (r.failures() > 0) log.warn("슬라이드 렌더링: 작업 폴더 일부 삭제 실패 {}개 — {}", r.failures(), root);
    }

    private void submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("슬라이드 렌더링: 작업 실패 ({})", t.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("슬라이드 렌더링: 종료 중이라 작업을 받지 않음");
        }
    }

    /** 원본 이름의 소문자 확장자 — 없으면 빈 문자열(렌더러가 "지원하지 않는 형식"으로 거른다). */
    static String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 ? "" : originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
