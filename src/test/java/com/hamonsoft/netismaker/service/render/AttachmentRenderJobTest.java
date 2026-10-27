package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.service.AttachmentStorage;
import com.hamonsoft.netismaker.service.render.RenderJobRef.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentRenderJobTest {

    @TempDir Path tmp;

    private final AttachmentRenderQueue queue = mock(AttachmentRenderQueue.class);
    private final SlideRenderer renderer = mock(SlideRenderer.class);
    private final RenderTargetPolicy policy = mock(RenderTargetPolicy.class);
    private final RenderProperties props = new RenderProperties(true, "", List.of("pptx", "ppt"), 120, 30, 1600, 20, 180);
    private AttachmentStorage storage;
    private AttachmentRenderJob job;

    @BeforeEach
    void setUp() {
        storage = new AttachmentStorage(tmp.toString(), 10, 20, 50);
        job = new AttachmentRenderJob(queue, renderer, storage, policy, props);
        when(policy.active()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        job.shutdown();
    }

    private RenderJobRef source(Kind kind, long id, String rel, String name) throws Exception {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "pptx");
        return new RenderJobRef(kind, id, rel, name);
    }

    @Test
    void 실패_뒤_다음_건_처리() throws Exception {
        RenderJobRef first = source(Kind.TASK, 1, "task-1/1-a.pptx", "a.PPTX");
        RenderJobRef second = source(Kind.QUESTION, 2, "question-2/create/1-b.ppt", "b.ppt");
        when(queue.claimNext()).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        RenderResult ok = new RenderResult(3, 3, 1);
        when(renderer.render(tmp.resolve("task-1/1-a.pptx"), "pptx", tmp.resolve("task-1/1-a.pptx.render")))
                .thenThrow(new RenderException("PDF 변환 실패"));
        when(renderer.render(tmp.resolve("question-2/create/1-b.ppt"), "ppt",
                tmp.resolve("question-2/create/1-b.ppt.render"))).thenReturn(ok);

        job.drain();

        verify(queue).fail(first, "PDF 변환 실패");
        verify(queue).complete(second, "question-2/create/1-b.ppt.render", ok);
        verify(queue, never()).complete(eq(first), anyString(), any());
    }

    @Test
    void 종료_인터럽트로_끊긴_렌더는_실패로_기록하지_않고_다음_건도_잡지_않는다() throws Exception {
        RenderJobRef first = source(Kind.TASK, 1, "task-1/1-a.pptx", "a.pptx");
        RenderJobRef second = source(Kind.TASK, 2, "task-1/2-b.pptx", "b.pptx");
        when(queue.claimNext()).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        // SlideRenderer처럼: InterruptedException을 받으면 플래그를 다시 세우고 RenderException("렌더링 중단")
        when(renderer.render(any(), any(), any())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new RenderException("렌더링 중단", new InterruptedException());
        });
        try {
            job.drain();
        } finally {
            Thread.interrupted();   // 다른 테스트에 플래그가 새지 않게
        }

        verify(queue, times(1)).claimNext();
        verify(queue, never()).fail(any(), any());
        verify(queue, never()).complete(any(), any(), any());
    }

    @Test
    void 원인_사슬이_인터럽트면_플래그가_없어도_실패로_기록하지_않는다() throws Exception {
        RenderJobRef ref = source(Kind.TASK, 1, "task-1/1-a.pptx", "a.pptx");
        when(queue.claimNext()).thenReturn(Optional.of(ref));
        when(renderer.render(any(), any(), any()))
                .thenThrow(new RenderException("렌더링 중단", new InterruptedException()));

        assertThat(job.drainOnce()).isFalse();

        verify(queue, never()).fail(any(), any());
    }

    @Test
    void executor가_닫혔으면_drain은_claim하지_않는다() {
        job.shutdown();

        job.drain();

        assertThat(job.drainOnce()).isFalse();
        verify(queue, never()).claimNext();
    }

    @Test
    void 스레드가_인터럽트됐으면_drain은_claim하지_않는다() {
        Thread.currentThread().interrupt();
        try {
            job.drain();
        } finally {
            Thread.interrupted();
        }
        verify(queue, never()).claimNext();
    }

    @Test
    void 꺼짐이면_drain_안함() throws Exception {
        when(policy.active()).thenReturn(false);

        job.drain();

        assertThat(job.drainOnce()).isFalse();
        verify(queue, never()).claimNext();
        verify(renderer, never()).render(any(), any(), any());
    }

    @Test
    void 대기_건이_없으면_drainOnce는_false() {
        when(queue.claimNext()).thenReturn(Optional.empty());
        assertThat(job.drainOnce()).isFalse();
    }

    @Test
    void 원본_파일이_없으면_원본_파일_없음으로_실패() throws Exception {
        RenderJobRef ref = new RenderJobRef(Kind.TASK, 5, "task-5/1-gone.pptx", "gone.pptx");
        when(queue.claimNext()).thenReturn(Optional.of(ref));

        assertThat(job.drainOnce()).isTrue();

        verify(queue).fail(ref, "원본 파일 없음");
        verify(renderer, never()).render(any(), any(), any());
    }

    @Test
    void 렌더러의_예상_밖_오류도_렌더링_실패로_기록하고_다음_건을_처리한다() throws Exception {
        RenderJobRef first = source(Kind.TASK, 1, "task-1/1-a.pptx", "a.pptx");
        RenderJobRef second = source(Kind.TASK, 2, "task-1/2-b.pptx", "b.pptx");
        when(queue.claimNext()).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        RenderResult ok = new RenderResult(1, 1, 0);
        when(renderer.render(eq(tmp.resolve("task-1/1-a.pptx")), any(), any()))
                .thenThrow(new OutOfMemoryError("Java heap space"));
        when(renderer.render(eq(tmp.resolve("task-1/2-b.pptx")), any(), any())).thenReturn(ok);

        job.drain();

        verify(queue).fail(first, "렌더링 실패");
        verify(queue).complete(second, "task-1/2-b.pptx.render", ok);
    }

    @Test
    void 큐_오류는_drain을_멈추지만_예외를_밖으로_던지지_않는다() {
        when(queue.claimNext()).thenThrow(new IllegalStateException("DB 끊김"));
        job.drain();   // 예외 없이 끝나야 한다 — executor 스레드가 죽지 않게
        verify(queue).claimNext();
    }

    @Test
    void 확장자가_없으면_빈_확장자로_렌더러에_넘긴다() throws Exception {
        RenderJobRef ref = source(Kind.TASK, 1, "task-1/1-noext", "noext");
        when(queue.claimNext()).thenReturn(Optional.of(ref));
        when(renderer.render(any(), eq(""), any())).thenThrow(new RenderException("지원하지 않는 형식"));

        job.drainOnce();

        verify(queue).fail(ref, "지원하지 않는 형식");
    }

    @Test
    void 기동하면_작업_폴더를_비우고_상한_2배보다_오래된_RUNNING을_회수한다() throws Exception {
        Path leftover = Files.createDirectories(storage.renderWorkRoot().resolve("old-uuid"));
        Files.writeString(leftover.resolve("input.pptx"), "x");
        when(queue.claimNext()).thenReturn(Optional.empty());
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(240);

        job.start();

        var cutoff = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(queue, timeout(5000)).recoverStale(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), OffsetDateTime.now().minusSeconds(239));
        verify(queue, timeout(5000)).claimNext();
        assertThat(Files.exists(leftover)).isFalse();
    }

    @Test
    void 꺼짐이면_기동해도_아무것도_하지_않는다() throws Exception {
        when(policy.active()).thenReturn(false);
        Path leftover = Files.createDirectories(storage.renderWorkRoot().resolve("old-uuid"));

        job.start();
        job.onRequested(new AttachmentRenderRequested());
        job.shutdown();

        verify(queue, never()).recoverStale(any());
        verify(queue, never()).claimNext();
        assertThat(Files.exists(leftover)).isTrue();
    }

    @Test
    void 업로드_이벤트를_받으면_같은_스레드에서_drain한다() {
        Thread[] seen = new Thread[1];
        when(queue.claimNext()).thenAnswer(inv -> {
            seen[0] = Thread.currentThread();
            return Optional.empty();
        });

        job.onRequested(new AttachmentRenderRequested());

        verify(queue, timeout(5000)).claimNext();
        assertThat(seen[0].getName()).isEqualTo("attachment-render");
    }
}
