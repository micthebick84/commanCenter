package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.PatchInfo;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.util.PatchFileNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 워커의 종료 부분 패치 zip 업로드 수신 (스펙 2026-11-10). {@link CloseArtifactService}와 같은 3단계 —
 * ① 검사 tx(잠금 없음) → ② 파일 쓰기(tx 밖) → ③ 기록 tx(FOR UPDATE 후 재검사). 내려받기 파일 이름은 기록 시점의
 * 서울 날짜와 작업 제목으로 정한다({@link PatchFileNames}).
 */
@Service
@Profile("api")
@Slf4j
public class ClosePatchService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final TaskRepository taskRepo;
    private final PatchStore store;
    private final ClosePatchProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public ClosePatchService(TaskRepository taskRepo, PatchStore store, ClosePatchProperties props,
                             PlatformTransactionManager txManager) {
        this(taskRepo, store, props, txManager, Clock.systemUTC());
    }

    ClosePatchService(TaskRepository taskRepo, PatchStore store, ClosePatchProperties props,
                      PlatformTransactionManager txManager, Clock clock) {
        this.taskRepo = taskRepo;
        this.store = store;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public PatchInfo receive(Long taskId, String workerId, String sha256, int fileCount, InputStream body) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "X-Artifact-Sha256 헤더는 64자 16진수여야 합니다");
        }
        if (fileCount < 0) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "fileCount가 올바르지 않습니다");
        }
        // ① 검사 — 잠그지 않는다
        Task snapshot = tx.execute(s -> guard(taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound),
                workerId));
        if (sha256.equalsIgnoreCase(snapshot.getPatchSha256()) && snapshot.getPatchName() != null
                && store.exists(taskId)) {
            drain(body);   // 재시도 멱등 — 같은 파일이 이미 있다
            return info(snapshot);
        }
        // ② 파일 쓰기 — 트랜잭션 밖
        long size;
        try {
            size = store.write(taskId, body, sha256, props.maxBytes());
        } catch (ArtifactStore.TooLarge e) {
            throw new TaskException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (ArtifactStore.HashMismatch e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.warn("패치 저장 실패: taskId={}", taskId, e);
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "패치 저장 실패: " + e.getMessage());
        }
        // ③ 기록 — 잠그고 다시 검사(그사이 종료실패·다른 워커로 바뀌었으면 거절, 파일은 다음 시도가 덮어쓴다)
        String sha = sha256.toLowerCase();
        return tx.execute(s -> {
            Task t = guard(taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound), workerId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            t.setPatchName(PatchFileNames.zipName(LocalDate.now(clock.withZone(SEOUL)), t.getTitle(), taskId));
            t.setPatchSize(size);
            t.setPatchSha256(sha);
            t.setPatchFileCount(fileCount);
            t.setPatchUploadedAt(now);
            t.setUpdatedAt(now);
            return info(t);
        });
    }

    /** 워커 재시도용 — 기록과 파일이 모두 있을 때만. */
    @Transactional(readOnly = true)
    public Optional<PatchInfo> current(Long taskId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getPatchName() == null || !store.exists(taskId)) return Optional.empty();
        return Optional.of(info(t));
    }

    private static Task guard(Task t, String workerId) {
        if (t.getStatus() != TaskStatus.CLOSING) {
            throw TaskException.conflict("종료중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (!Objects.equals(workerId, t.getWorkerId())) {
            throw TaskException.conflict("다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        if (!CloseStep.PATCH_REQUIRED.equals(t.getClosePatchMode())) {
            throw TaskException.conflict("부분 패치 대상이 아닙니다");
        }
        return t;
    }

    private static PatchInfo info(Task t) {
        return new PatchInfo(t.getPatchName(), t.getPatchSize() == null ? 0 : t.getPatchSize(), t.getPatchSha256(),
                t.getPatchFileCount() == null ? 0 : t.getPatchFileCount());
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignore) {
            // 버릴 본문 — 끊겨도 응답은 같다
        }
    }
}
