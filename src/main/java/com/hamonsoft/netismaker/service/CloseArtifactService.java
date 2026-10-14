package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 워커의 종료 산출물 업로드 수신 (스펙 2026-10-14 §5).
 * 254MB 본문을 받는 동안 DB 커넥션·행 잠금을 잡지 않도록 ① 검사 tx(잠금 없음) → ② 파일 쓰기(tx 밖) →
 * ③ 기록 tx(FOR UPDATE 후 재검사)로 나눈다. Hikari leak-detection-threshold(30초)에도 걸리지 않는다.
 */
@Service
@Profile("api")
@Slf4j
public class CloseArtifactService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final TaskRepository taskRepo;
    private final ArtifactStore store;
    private final CloseArtifactProperties props;
    private final TransactionTemplate tx;

    public CloseArtifactService(TaskRepository taskRepo, ArtifactStore store, CloseArtifactProperties props,
                                PlatformTransactionManager txManager) {
        this.taskRepo = taskRepo;
        this.store = store;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public ArtifactInfo receive(Long taskId, String workerId, String name, String source, String sha256,
                                InputStream body) {
        if (!ArtifactStore.validName(name)) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "산출물 이름은 [A-Za-z0-9._-]이고 .war 또는 .jar로 끝나야 합니다: " + name);
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "X-Artifact-Sha256 헤더는 64자 16진수여야 합니다");
        }
        if (source != null && source.length() > 500) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "source가 너무 깁니다");
        }
        // ① 검사 — 잠그지 않는다
        Task snapshot = tx.execute(s -> guard(taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound),
                workerId));
        if (name.equals(snapshot.getArtifactName()) && sha256.equalsIgnoreCase(snapshot.getArtifactSha256())
                && store.exists(taskId, name)) {
            drain(body);   // 재시도 멱등 — 같은 파일이 이미 있다
            return info(snapshot);
        }
        // ② 파일 쓰기 — 트랜잭션 밖
        ArtifactInfo saved;
        try {
            saved = store.write(taskId, name, body, sha256, props.maxBytes());
        } catch (ArtifactStore.TooLarge e) {
            throw new TaskException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (ArtifactStore.HashMismatch e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.warn("산출물 저장 실패: taskId={}", taskId, e);   // 응답 사유만으로는 서버 쪽 원인(디스크·권한)을 추적할 수 없다
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "산출물 저장 실패: " + e.getMessage());
        }
        // ③ 기록 — 잠그고 다시 검사(그사이 종료실패·다른 워커로 바뀌었으면 거절, 파일은 다음 시도가 덮어쓴다)
        return tx.execute(s -> {
            Task t = guard(taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound), workerId);
            OffsetDateTime now = OffsetDateTime.now();
            t.setArtifactName(saved.name());
            t.setArtifactSize(saved.sizeBytes());
            t.setArtifactSha256(saved.sha256());
            t.setArtifactSource(source);
            t.setArtifactUploadedAt(now);
            t.setUpdatedAt(now);
            return saved;
        });
    }

    /** 워커 재시도용 — 기록과 파일이 모두 있을 때만. */
    @Transactional(readOnly = true)
    public Optional<ArtifactInfo> current(Long taskId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getArtifactName() == null || !store.exists(taskId, t.getArtifactName())) return Optional.empty();
        return Optional.of(info(t));
    }

    private static Task guard(Task t, String workerId) {
        if (t.getStatus() != TaskStatus.CLOSING) {
            throw TaskException.conflict("종료중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (!Objects.equals(workerId, t.getWorkerId())) {
            throw TaskException.conflict("다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())) {
            throw TaskException.conflict("산출물 보관 대상이 아닙니다");
        }
        return t;
    }

    private static ArtifactInfo info(Task t) {
        return new ArtifactInfo(t.getArtifactName(), t.getArtifactSize() == null ? 0 : t.getArtifactSize(),
                t.getArtifactSha256());
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignore) {
            // 버릴 본문 — 끊겨도 응답은 같다
        }
    }
}
