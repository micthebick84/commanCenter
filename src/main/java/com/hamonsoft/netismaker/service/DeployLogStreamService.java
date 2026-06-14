package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
 *  - subscribe: 브라우저 접속 시 기존 청크 replay 후 live 구독.
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
        SseEmitter emitter = new SseEmitter(0L); // 무제한 타임아웃 (종료는 finish가 close)
        // 1) 지금까지의 청크 replay
        try {
            for (TaskDeployLogChunk c : chunkRepo.findByTaskIdOrderBySeqAsc(taskId)) {
                emitter.send(SseEmitter.event().name("log").data(c.getContent()));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        // 2) live 구독 등록
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ex -> remove(taskId, emitter));
        return emitter;
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
