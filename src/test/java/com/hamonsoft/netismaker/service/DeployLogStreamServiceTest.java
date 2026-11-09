package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeployLogStreamServiceTest {

    private TaskDeployLogChunkRepository repo;
    private DeployLogStreamService svc;

    @BeforeEach
    void setUp() {
        repo = mock(TaskDeployLogChunkRepository.class);
        svc = new DeployLogStreamService(repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void ingest_persists_chunk() {
        svc.ingestChunk(7L, 0, "building...");
        verify(repo).save(any(TaskDeployLogChunk.class));
    }

    @Test
    void consolidate_joins_chunks_in_order() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of(
                TaskDeployLogChunk.of(7L, 0, "a\n"),
                TaskDeployLogChunk.of(7L, 1, "b\n")));
        assertThat(svc.consolidate(7L)).contains("a\nb\n");
    }

    @Test
    void consolidate_empty_when_no_chunks() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of());
        assertThat(svc.consolidate(7L)).isEmpty();
    }

    @Test
    void finish_deletes_chunks() {
        svc.finish(7L);
        verify(repo).deleteByTaskId(7L);
    }

    // ---- SSE 핑 (스펙 2026-11-03 §4 "빌드 로그 스트림 보강") ----

    /** 이벤트 빌더가 실제로 쓰게 될 SSE 텍스트(주석·event:·data: 줄)를 이어 붙인다. */
    private static String wire(SseEventBuilder builder) {
        return builder.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining());
    }

    @Test
    void 접속하면_먼저_핑_주석() throws IOException {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of(TaskDeployLogChunk.of(7L, 0, "building...")));
        SseEmitter e = mock(SseEmitter.class);

        svc.subscribe(7L, e);

        ArgumentCaptor<SseEventBuilder> sent = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(e, times(2)).send(sent.capture());
        assertThat(wire(sent.getAllValues().get(0))).contains(":ping");        // 먼저 핑 — 조용한 빌드여도 헤더가 나간다
        assertThat(wire(sent.getAllValues().get(1))).contains("building...");  // 그다음 기존 청크 replay
    }

    @Test
    void 핑은_모든_구독에_보내고_실패한_구독은_뺀다() throws IOException {
        SseEmitter e1 = mock(SseEmitter.class);
        SseEmitter e2 = mock(SseEmitter.class);
        // e2는 접속 핑은 받고(등록됨) 그다음 send부터 끊긴 연결처럼 IOException — ping()의 제거 경로를 거치게 한다
        doNothing().doThrow(new IOException("끊김")).when(e2).send(any(SseEventBuilder.class));
        svc.subscribe(7L, e1);
        svc.subscribe(7L, e2);

        svc.ping();                       // e1·e2 모두에게 감 — e2는 여기서 실패해 빠진다
        svc.ingestChunk(7L, 1, "x");      // e1만 받는다

        verify(e2, times(2)).send(any(SseEventBuilder.class));   // 접속 핑 + ping() 시도, 그 뒤로는 없음
        ArgumentCaptor<SseEventBuilder> sent = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(e1, times(3)).send(sent.capture());               // 접속 핑·ping()·log
        assertThat(wire(sent.getAllValues().get(1))).contains(":ping");
        assertThat(wire(sent.getAllValues().get(2))).contains("event:log").contains("data:x");
    }

    @Test
    void 접속_핑이_실패하면_구독을_등록하지_않는다() throws IOException {
        SseEmitter e = mock(SseEmitter.class);
        doThrow(new IOException("끊김")).when(e).send(any(SseEventBuilder.class));

        svc.subscribe(7L, e);
        svc.ingestChunk(7L, 0, "x");

        verify(e, times(1)).send(any(SseEventBuilder.class));    // 접속 핑 한 번뿐
        verify(e).completeWithError(any(IOException.class));
    }

    @Test
    void keepAlive는_설정_키로_주기를_정하고_핑을_보낸다() throws Exception {
        SseEmitter e = mock(SseEmitter.class);
        svc.subscribe(7L, e);

        svc.keepAlive();

        verify(e, times(2)).send(any(SseEventBuilder.class));    // 접속 핑 + keepAlive 핑
        Scheduled scheduled = DeployLogStreamService.class.getMethod("keepAlive").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedRateString()).isEqualTo("${app.deploy-log.sse-ping-interval-ms:20000}");
    }
}
