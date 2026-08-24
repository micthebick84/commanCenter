package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  결과 보고 유실 추적기 겸 재전송 pending 저장소 (ResultReporter.LostReportListener 구현).
 *
 *  - 유실된 결과 페이로드를 로컬 dead-letter 파일(<dir>/<workerId>.jsonl)에 한 줄씩 append.
 *    DeadLetterReplayJob이 snapshotPending()으로 읽어 재전송하고, 성공 시 resolve()로 제거,
 *    4xx 거부 시 reject()로 attempts를 올리며 소진되면 <workerId>.dead.jsonl로 이동(포렌식 보존).
 *  - 엔트리 JSON: {ts, taskId, status, permanent, reason, attempts, result}.
 *    attempts 없는 레거시 라인(구형식 운영 파일)은 0으로 관용 파싱.
 *    파싱 불가 라인은 warn 후 dead 파일로 이동 — 본 파일을 막지 않되 유실도 금지.
 *  - currentCount()는 "미전송 pending 엔트리 수" (프로세스 누적이 아님): 시작 시 파일 스캔으로
 *    초기화, resolve/reject-이동 시 감소. 재전송 성공 시 admin 배지(워커 화면 '유실 보고')가
 *    내려가고, 워커 재시작에도 파일 기준이라 유지된다.
 *  - onLost는 절대 throw 안 함(기존 불변식): 파일 I/O 실패는 로그만 (카운트는 증가 — 배지로 이상 신호).
 */
@Component
@Profile("worker")
@Slf4j
public class SilentLossTracker implements ResultReporter.LostReportListener {

    /** .dead.jsonl(포렌식 보존) 상한 — 초과 시 .dead.jsonl.1로 밀어 무한 성장을 막는다 (최대 2세대). */
    static final long MAX_DEAD_BYTES = 5L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final Path deadLetterFile;
    private final Path deadFile;
    private final long maxDeadBytes;

    /** 재전송 대기 엔트리. raw는 파일 내 원본 라인 그대로 — resolve/reject의 매칭 키. */
    public record PendingEntry(Long taskId, WorkerResultRequest result, int attempts, String raw) { }

    // 생성자 여러 개(테스트용 포함) → Spring 주입 생성자 명시(없으면 no-arg 폴백→기동 실패).
    @Autowired
    public SilentLossTracker(WorkerProperties props) {
        this(props.deadLetterDir(), props.id(), MAX_DEAD_BYTES);
    }

    SilentLossTracker(String deadLetterDir, String workerId) {
        this(deadLetterDir, workerId, MAX_DEAD_BYTES);
    }

    SilentLossTracker(String deadLetterDir, String workerId, long maxDeadBytes) {
        this.deadLetterFile = Path.of(deadLetterDir, workerId + ".jsonl");
        this.deadFile = Path.of(deadLetterDir, workerId + ".dead.jsonl");
        this.maxDeadBytes = maxDeadBytes;
        // 시작 시 파일 스캔으로 pending 수 초기화 (재시작해도 배지 유지)
        this.pendingCount.set(loadPendingSafe().size());
    }

    @Override
    public synchronized void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason) {
        pendingCount.incrementAndGet();
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", OffsetDateTime.now().toString());
            node.put("taskId", taskId);
            node.put("status", req.status().dbValue());
            node.put("permanent", permanent);
            node.put("reason", reason);
            node.put("attempts", 0);
            node.set("result", mapper.valueToTree(req));

            Files.createDirectories(deadLetterFile.getParent());
            Files.writeString(deadLetterFile, mapper.writeValueAsString(node) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("dead-letter 기록 실패 task={} (카운트는 증가됨)", taskId, e);
        }
    }

    /**
     * 현재 파일의 pending 엔트리 스냅샷. 파싱 불가 라인은 dead 파일로 이동시키고 나머지만 반환.
     * 파일 접근 자체가 실패하면 빈 리스트 (다음 사이클에 재시도). 절대 throw 안 함.
     */
    public synchronized List<PendingEntry> snapshotPending() {
        List<PendingEntry> pending = loadPendingSafe();
        pendingCount.set(pending.size());
        return pending;
    }

    /** 재전송 성공: 해당 엔트리(raw 일치 첫 1개)를 본 파일에서 제거. 절대 throw 안 함. */
    public synchronized void resolve(PendingEntry e) {
        try {
            List<String> lines = readLines(deadLetterFile);
            if (!lines.remove(e.raw())) {
                log.warn("dead-letter resolve 대상 라인 없음 task={} (이미 처리됨?)", e.taskId());
                return;
            }
            rewrite(lines);
            pendingCount.set(lines.size());
        } catch (Exception ex) {
            log.error("dead-letter resolve 실패 task={} (무시 — 다음 사이클 재시도)", e.taskId(), ex);
        }
    }

    /**
     * 재전송 거부(4xx): attempts+1로 재기록. attempts+1 >= maxAttempts면 본 파일에서 제거하고
     * <workerId>.dead.jsonl로 이동(포렌식 보존, 재전송 종료). 절대 throw 안 함.
     */
    public synchronized void reject(PendingEntry e, int maxAttempts) {
        try {
            List<String> lines = readLines(deadLetterFile);
            int idx = lines.indexOf(e.raw());
            if (idx < 0) {
                log.warn("dead-letter reject 대상 라인 없음 task={} (이미 처리됨?)", e.taskId());
                return;
            }
            int next = e.attempts() + 1;
            ObjectNode node = (ObjectNode) mapper.readTree(e.raw());
            node.put("attempts", next);
            String updated = mapper.writeValueAsString(node);

            if (next >= maxAttempts) {
                lines.remove(idx);
                appendDead(List.of(updated));
                log.warn("dead-letter 재전송 소진({}/{}회) task={} → {} 이동(포렌식 보존)",
                        next, maxAttempts, e.taskId(), deadFile.getFileName());
            } else {
                lines.set(idx, updated);
            }
            rewrite(lines);
            pendingCount.set(lines.size());
        } catch (Exception ex) {
            log.error("dead-letter reject 실패 task={} (무시 — 다음 사이클 재시도)", e.taskId(), ex);
        }
    }

    /** 미전송 pending 엔트리 수 (파일 기준). heartbeat가 실어 admin 워커 화면 '유실 보고' 배지로 노출. */
    public int currentCount() {
        return pendingCount.get();
    }

    // ─────────────────────────── 내부 파일 연산 ───────────────────────────

    /** 파일 전체 파싱. 파싱 불가 라인은 dead 파일로 이동(본 파일 재기록). 실패 시 빈 리스트, throw 안 함. */
    private List<PendingEntry> loadPendingSafe() {
        try {
            List<String> lines = readLines(deadLetterFile);
            List<PendingEntry> pending = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (String line : lines) {
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode taskIdNode = node.get("taskId");
                    JsonNode resultNode = node.get("result");
                    if (taskIdNode == null || resultNode == null || resultNode.isNull()) {
                        throw new IOException("taskId/result 필드 누락");
                    }
                    // 레거시 라인(attempts 없음)은 0으로 관용 파싱 — 운영 파일에 구형식 존재 가능
                    int attempts = node.has("attempts") ? node.get("attempts").asInt(0) : 0;
                    WorkerResultRequest req = mapper.treeToValue(resultNode, WorkerResultRequest.class);
                    pending.add(new PendingEntry(taskIdNode.asLong(), req, attempts, line));
                } catch (Exception parse) {
                    log.warn("dead-letter 파싱 불가 라인 → {} 이동: {}", deadFile.getFileName(), parse.getMessage());
                    bad.add(line);
                }
            }
            if (!bad.isEmpty()) {
                appendDead(bad);
                rewrite(pending.stream().map(PendingEntry::raw).toList());
            }
            return pending;
        } catch (Exception e) {
            log.error("dead-letter 파일 읽기 실패 (빈 pending으로 처리): {}", deadLetterFile, e);
            return List.of();
        }
    }

    private List<String> readLines(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.removeIf(String::isBlank);
        return lines;
    }

    /** 본 파일 재기록: temp 쓰기 → ATOMIC_MOVE (부분 쓰기로 파일이 깨지는 것 방지). */
    private void rewrite(List<String> lines) throws IOException {
        Files.createDirectories(deadLetterFile.getParent());
        Path tmp = Files.createTempFile(deadLetterFile.getParent(), deadLetterFile.getFileName().toString(), ".tmp");
        Files.writeString(tmp, lines.isEmpty() ? "" : String.join(System.lineSeparator(), lines) + System.lineSeparator());
        Files.move(tmp, deadLetterFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void appendDead(List<String> lines) throws IOException {
        Files.createDirectories(deadFile.getParent());
        rotateDeadIfNeeded();
        Files.writeString(deadFile, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * dead 파일이 상한을 넘으면 <이름>.1로 밀어낸다(기존 .1은 대체). append-only 포렌식
     * 파일이 무한 성장하지 않게 하는 로테이션 — 최대 2세대(약 2×상한)만 보존한다.
     */
    private void rotateDeadIfNeeded() throws IOException {
        if (!Files.exists(deadFile) || Files.size(deadFile) < maxDeadBytes) return;
        Path rolled = deadFile.resolveSibling(deadFile.getFileName() + ".1");
        Files.move(deadFile, rolled, StandardCopyOption.REPLACE_EXISTING);
        log.warn("dead 파일 로테이션: {} ({}B 초과) → {} (이전 세대 대체)",
                deadFile.getFileName(), maxDeadBytes, rolled.getFileName());
    }
}
