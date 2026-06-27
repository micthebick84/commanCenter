package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  결과 보고 유실 추적기 (ResultReporter.LostReportListener 구현).
 *
 *  - 워커 프로세스 시작 이후 유실 건수 누적(in-memory). heartbeat가 currentCount()를 실어 보냄.
 *  - 유실된 결과 페이로드를 로컬 dead-letter 파일(<dir>/<workerId>.jsonl)에 한 줄씩 append(durable 기록, 수동 재투입용).
 *  - 절대 throw 안 함: 파일 I/O 실패는 로그만.
 */
@Component
@Profile("worker")
@Slf4j
public class SilentLossTracker implements ResultReporter.LostReportListener {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger lostCount = new AtomicInteger();
    private final Path deadLetterFile;

    public SilentLossTracker(WorkerProperties props) {
        this(props.deadLetterDir(), props.id());
    }

    SilentLossTracker(String deadLetterDir, String workerId) {
        this.deadLetterFile = Path.of(deadLetterDir, workerId + ".jsonl");
    }

    @Override
    public synchronized void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason) {
        lostCount.incrementAndGet();
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", OffsetDateTime.now().toString());
            node.put("taskId", taskId);
            node.put("status", req.status().dbValue());
            node.put("permanent", permanent);
            node.put("reason", reason);
            node.set("result", mapper.valueToTree(req));

            Files.createDirectories(deadLetterFile.getParent());
            Files.writeString(deadLetterFile, mapper.writeValueAsString(node) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("dead-letter 기록 실패 task={} (카운트는 증가됨)", taskId, e);
        }
    }

    /** 워커 시작 이후 누적 유실 건수. */
    public int currentCount() {
        return lostCount.get();
    }
}
