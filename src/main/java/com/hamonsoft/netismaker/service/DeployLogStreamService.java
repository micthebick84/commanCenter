package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 배포 로그 스트리밍 (api 프로파일).
 *
 *  - ingestChunk: 워커가 올린 청크를 영속화 + 구독 SSE emitter에 push.
 *  - subscribe: 브라우저 접속 시 핑 주석 → 기존 청크 replay 후 live 구독.
 *  - ping/keepAlive: 20초마다 모든 구독에 주석 핑 (프록시 유휴 끊김 방지, 실패한 구독은 제거).
 *  - finish: 종료 시 done 이벤트 + emitter complete + 청크 DELETE.
 *  - consolidate: 청크를 합쳐 tail 반환 (워커 사망 시 부분 로그 보존용).
 *
 *  api 단일 인스턴스 전제 — emitter 레지스트리는 in-memory.
 */
@Service
@Profile("api")
@Slf4j
public class DeployLogStreamService {

    private static final int MAX_TAIL = 8000;
    private static final String PING = "ping";

    private final TaskDeployLogChunkRepository chunkRepo;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public DeployLogStreamService(TaskDeployLogChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
    }

    @Transactional
    public void ingestChunk(Long taskId, int seq, String content) {
        chunkRepo.save(TaskDeployLogChunk.of(taskId, seq, content));
        List<SseEmitter> subs = emitters.get(taskId);
        if (subs != null) {
            for (SseEmitter e : subs) {
                try {
                    e.send(SseEmitter.event().name("log").data(content == null ? "" : content));
                } catch (Exception ex) {
                    remove(taskId, e);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(Long taskId) {
        return subscribe(taskId, new SseEmitter(0L)); // 무제한 타임아웃 (종료는 finish가 close)
    }

    /** 시험에서 emitter를 바꿔 끼우려고 나눴다. 트랜잭션은 위 공개 메서드가 연다. */
    SseEmitter subscribe(Long taskId, SseEmitter emitter) {
        // 1) 접속 핑 먼저 — 조용한 빌드 구간에도 응답 헤더와 첫 바이트가 바로 나가 프록시(Cloudflare)가 유휴로 끊지 않는다
        // 2) 지금까지의 청크 replay
        try {
            emitter.send(SseEmitter.event().comment(PING));
            for (TaskDeployLogChunk c : chunkRepo.findByTaskIdOrderBySeqAsc(taskId)) {
                emitter.send(SseEmitter.event().name("log").data(c.getContent()));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        // 3) live 구독 등록
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ex -> remove(taskId, emitter));
        return emitter;
    }

    /** 모든 구독에 주석 핑을 보내고, 보내지 못한 구독은 뺀다 (끊긴 브라우저가 목록에 남지 않게). */
    public void ping() {
        for (Map.Entry<Long, List<SseEmitter>> entry : emitters.entrySet()) {
            Long taskId = entry.getKey();
            for (SseEmitter e : entry.getValue()) {
                try {
                    e.send(SseEmitter.event().comment(PING));
                } catch (Exception ex) {
                    remove(taskId, e);
                }
            }
        }
    }

    /** 20초마다 핑 — 조용한 빌드 구간에 Cloudflare가 연결을 끊지 않게 한다 ({@code InterviewStreamService.keepAlive} 선례). */
    @Scheduled(fixedRateString = "${app.deploy-log.sse-ping-interval-ms:20000}")
    public void keepAlive() {
        ping();
    }

    @Transactional
    public void finish(Long taskId) {
        List<SseEmitter> subs = emitters.remove(taskId);
        if (subs != null) {
            for (SseEmitter e : subs) {
                try {
                    e.send(SseEmitter.event().name("done").data("end"));
                    e.complete();
                } catch (Exception ignore) { /* 이미 닫힘 */ }
            }
        }
        chunkRepo.deleteByTaskId(taskId);
    }

    /** 청크를 seq순으로 합쳐 tail 반환. 없으면 empty. (워커 사망 회수 시 부분 로그 보존용) */
    @Transactional(readOnly = true)
    public Optional<String> consolidate(Long taskId) {
        List<TaskDeployLogChunk> chunks = chunkRepo.findByTaskIdOrderBySeqAsc(taskId);
        if (chunks.isEmpty()) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (TaskDeployLogChunk c : chunks) sb.append(c.getContent());
        String s = sb.toString();
        return Optional.of(s.length() <= MAX_TAIL ? s : "…" + s.substring(s.length() - MAX_TAIL));
    }

    private void remove(Long taskId, SseEmitter e) {
        List<SseEmitter> subs = emitters.get(taskId);
        if (subs != null) subs.remove(e);
    }
}
