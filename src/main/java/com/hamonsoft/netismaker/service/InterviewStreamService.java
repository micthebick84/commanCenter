package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.PlanReadyEvent;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 인터뷰 SSE 스트리밍 (api 프로파일).
 *
 *  이벤트: question | design | plan_ready | status | done
 *  - subscribe: 접속 시 keepalive ping + 기존 turn replay 후 live 구독.
 *  - pushQuestion/pushDesign/pushPlanReady/pushStatus: 라이브 fan-out.
 *  - finish: done 이벤트 + emitter complete (세션 terminal 시).
 *
 *  LOCKED CONTRACT v2:
 *    status 페이로드 = InterviewStatus.name() (영문 enum, 예 "AWAITING_INPUT") — 한글 dbValue 아님.
 *    plan_ready 페이로드 = {designMarkdown, planMarkdown, planJson} JSON 객체 — bare string 아님.
 *
 *  api 단일 인스턴스 전제 — emitter 레지스트리 in-memory.
 */
@Service
@Profile("api")
@Slf4j
public class InterviewStreamService {

    private final InterviewTurnRepository turnRepo;
    private final ObjectMapper json;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public InterviewStreamService(InterviewTurnRepository turnRepo, ObjectMapper json) {
        this.turnRepo = turnRepo;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(Long sessionId) {
        SseEmitter emitter = new SseEmitter(0L); // 무제한 타임아웃; 종료는 finish가 close
        try {
            // 0) keepalive ping — 프록시/브라우저가 연결을 살아있게 유지
            emitter.send(SseEmitter.event().comment("ping"));
            // 1) 지금까지의 assistant turn replay (재연결 시 누락 복구)
            for (InterviewTurn t : turnRepo.findBySessionIdOrderBySeqAsc(sessionId)) {
                if ("user".equals(t.getRole())) continue; // 사용자 답변은 클라가 이미 가짐
                String event = "design".equals(t.getKind()) ? "design" : "question";
                emitter.send(SseEmitter.event().name(event)
                        .data(t.getContent() == null ? "" : t.getContent()));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        // 2) live 구독 등록
        emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(ex -> remove(sessionId, emitter));
        return emitter;
    }

    public void pushQuestion(Long sessionId, String content) { send(sessionId, "question", content); }
    public void pushDesign(Long sessionId, String content)   { send(sessionId, "design", content); }

    /** status 페이로드 = 영문 enum name (예 "AWAITING_INPUT"). 한글 dbValue 절대 아님. */
    public void pushStatus(Long sessionId, InterviewStatus status) {
        send(sessionId, "status", status.name());
    }

    /** plan_ready 페이로드 = {designMarkdown, planMarkdown, planJson} JSON 객체. */
    public void pushPlanReady(Long sessionId, String designMarkdown, String planMarkdown, String planJson) {
        try {
            String payload = json.writeValueAsString(
                    new PlanReadyEvent(designMarkdown, planMarkdown, planJson));
            send(sessionId, "plan_ready", payload);
        } catch (JsonProcessingException e) {
            log.error("plan_ready 직렬화 실패 session={}", sessionId, e);
        }
    }

    /** 키프얼라이브 핑 — 스케줄러가 주기적으로 호출 (idle 연결 유지). */
    public void ping(Long sessionId) {
        List<SseEmitter> subs = emitters.get(sessionId);
        if (subs == null) return;
        for (SseEmitter e : subs) {
            try { e.send(SseEmitter.event().comment("ping")); }
            catch (Exception ex) { remove(sessionId, e); }
        }
    }

    public void finish(Long sessionId) {
        List<SseEmitter> subs = emitters.remove(sessionId);
        if (subs != null) {
            for (SseEmitter e : subs) {
                try {
                    e.send(SseEmitter.event().name("done").data("end"));
                    e.complete();
                } catch (Exception ignore) { /* 이미 닫힘 */ }
            }
        }
    }

    public int subscriberCount(Long sessionId) {
        List<SseEmitter> subs = emitters.get(sessionId);
        return subs == null ? 0 : subs.size();
    }

    /** 30초마다 모든 활성 세션에 핑 — proxy idle 타임아웃/EventSource 끊김 방지. */
    @Scheduled(fixedRateString = "${app.interview.sse-ping-interval-ms:30000}")
    public void keepAlive() {
        for (Long sessionId : emitters.keySet()) ping(sessionId);
    }

    private void send(Long sessionId, String event, String data) {
        List<SseEmitter> subs = emitters.get(sessionId);
        if (subs == null) return;
        for (SseEmitter e : subs) {
            try { e.send(SseEmitter.event().name(event).data(data == null ? "" : data)); }
            catch (Exception ex) { remove(sessionId, e); }
        }
    }

    private void remove(Long sessionId, SseEmitter e) {
        List<SseEmitter> subs = emitters.get(sessionId);
        if (subs != null) subs.remove(e);
    }
}
