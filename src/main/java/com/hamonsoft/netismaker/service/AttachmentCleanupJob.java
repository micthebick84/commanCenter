package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Decision;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Owner;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.OwnerDir;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Owners;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Policy;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.SessionRow;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.TaskRow;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 첨부 디렉터리 보존 기간 정리 (api 프로파일). 매일 cron(기본 03:30)에 app.attachment.dir 바로 아래의
 * task-{id}/ · question-{sid}/ 를 스캔 → 소유 행 상태를 DB에서 일괄 조회 → AttachmentCleanupPlanner로 판정 → 삭제.
 *
 *  - 판정을 전부 끝낸 뒤에 지운다: DB 조회가 실패하면 아무것도 지우지 않고 이번 주기를 건너뛴다(fail-closed).
 *  - DB 첨부 행(task_attachment/question_attachment)은 감사용으로 남긴다 — 파일만 지운다.
 *    질문 다운로드는 파일이 없으면 410(QuestionController), 삭제된 작업은 다운로드 전에 404.
 *  - 디렉터리 단위 실패는 로그만 남기고 다음 디렉터리로 진행 — 남은 것은 다음 주기에 재시도된다.
 *  - 인터뷰 work_dir(~/netis-maker/interviews)는 범위 밖 — 별도 정책.
 */
@Component
@Profile("api")
@Slf4j
public class AttachmentCleanupJob {

    /** IN 절 1회당 id 수 상한 — 디렉터리가 수천 개여도 바인드 파라미터 한도·쿼리 크기를 넘지 않게. */
    static final int LOOKUP_CHUNK = 500;

    private final AttachmentStorage storage;
    private final TaskRepository taskRepo;
    private final InterviewSessionRepository sessionRepo;
    private final boolean enabled;
    private final Policy policy;

    public AttachmentCleanupJob(AttachmentStorage storage,
                                TaskRepository taskRepo,
                                InterviewSessionRepository sessionRepo,
                                @Value("${app.attachment.cleanup.enabled:true}") boolean enabled,
                                @Value("${app.attachment.cleanup.task-deleted-retention-days:30}") int taskDeletedRetentionDays,
                                @Value("${app.attachment.cleanup.question-closed-retention-days:90}") int questionClosedRetentionDays,
                                @Value("${app.attachment.cleanup.orphan-grace-hours:24}") int orphanGraceHours) {
        if (taskDeletedRetentionDays < 0 || questionClosedRetentionDays < 0) {
            throw new IllegalArgumentException("app.attachment.cleanup.*-retention-days는 0 이상이어야 합니다");
        }
        // 유예 0이면 등록 트랜잭션이 파일을 쓰는 중(행 미커밋)인 디렉터리를 고아로 볼 수 있다.
        if (orphanGraceHours < 1) {
            throw new IllegalArgumentException("app.attachment.cleanup.orphan-grace-hours는 1 이상이어야 합니다");
        }
        this.storage = storage;
        this.taskRepo = taskRepo;
        this.sessionRepo = sessionRepo;
        this.enabled = enabled;
        this.policy = new Policy(Duration.ofDays(taskDeletedRetentionDays),
                Duration.ofDays(questionClosedRetentionDays), Duration.ofHours(orphanGraceHours));
    }

    /** 한 주기 결과 — 요약 로그와 테스트 검증용. */
    public record Summary(int deletedTask, int deletedQuestion, int deletedOrphan,
                          long freedBytes, int kept, int skipped, int failed) {
        public int deleted() { return deletedTask + deletedQuestion + deletedOrphan; }
    }

    @Scheduled(cron = "${app.attachment.cleanup.cron:0 30 3 * * *}")
    public void cleanup() {
        if (!enabled) return;
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            // 스캔/조회 단계 실패 — 삭제는 판정이 끝난 뒤에만 하므로 이 경로에선 지운 것이 없다.
            log.error("첨부 정리 실패 — 이번 주기 건너뜀(다음 주기에 재시도)", e);
        }
    }

    Summary runOnce(Instant now) throws IOException {
        AttachmentStorage.OwnerDirScan scan = storage.scanOwnerDirectories();
        if (scan.dirs().isEmpty()) {
            log.debug("첨부 정리: 대상 디렉터리 없음 (건너뜀 {}개)", scan.skipped());
            return new Summary(0, 0, 0, 0, 0, scan.skipped(), 0);
        }
        List<Decision> decisions = AttachmentCleanupPlanner.plan(scan.dirs(), lookupOwners(scan.dirs()), now, policy);

        int deletedTask = 0, deletedQuestion = 0, deletedOrphan = 0, kept = 0, failed = 0;
        long freed = 0;
        // 유지는 디렉터리별로 찍지 않고 사유별 개수만 요약에 싣는다 — 패키지 로그 레벨이 DEBUG라 작업 수만큼 줄이 쌓인다.
        Map<Verdict, Integer> keptBy = new EnumMap<>(Verdict.class);
        for (Decision d : decisions) {
            if (!d.verdict().delete()) {
                kept++;
                keptBy.merge(d.verdict(), 1, Integer::sum);
                continue;
            }
            try {
                long bytes = storage.deleteOwnerDirectory(d.dir());
                freed += bytes;
                switch (d.verdict()) {
                    case DELETE_TASK_DELETED -> deletedTask++;
                    case DELETE_QUESTION_CLOSED -> deletedQuestion++;
                    case DELETE_ORPHAN -> deletedOrphan++;
                    default -> { }
                }
                log.info("첨부 정리: 삭제 {} — {} ({})", d.dir().name(), d.verdict().label(), humanBytes(bytes));
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("첨부 정리: 삭제 실패 {} — 다음 주기에 재시도", d.dir().name(), e);
            }
        }
        Summary s = new Summary(deletedTask, deletedQuestion, deletedOrphan, freed, kept, scan.skipped(), failed);
        log.info("첨부 정리 완료: 삭제 {}개(삭제된 작업 {}, 종료된 질문 {}, 고아 {}), 해제 {}, 유지 {}개{}, 건너뜀 {}개, 실패 {}개",
                s.deleted(), deletedTask, deletedQuestion, deletedOrphan, humanBytes(freed), kept, breakdown(keptBy),
                s.skipped(), failed);
        return s;
    }

    /** 스캔된 id만 IN 절로 일괄 조회 (LOOKUP_CHUNK씩). 최대 id는 고아 판정 가드용. */
    private Owners lookupOwners(List<OwnerDir> dirs) {
        List<Long> taskIds = idsOf(dirs, Owner.TASK);
        List<Long> sessionIds = idsOf(dirs, Owner.QUESTION);

        Map<Long, TaskRow> tasks = new HashMap<>();
        for (List<Long> chunk : chunks(taskIds)) {
            for (TaskRepository.AttachmentOwnerState r : taskRepo.findAttachmentOwnerStates(chunk)) {
                tasks.put(r.getId(), new TaskRow(r.getDeletedAt()));
            }
        }
        Map<Long, SessionRow> sessions = new HashMap<>();
        for (List<Long> chunk : chunks(sessionIds)) {
            for (InterviewSessionRepository.AttachmentOwnerState r : sessionRepo.findAttachmentOwnerStates(chunk)) {
                sessions.put(r.getId(), new SessionRow(r.getKind(), r.getStatus(), r.getLastActivityAt()));
            }
        }
        Long maxTaskId = taskIds.isEmpty() ? null : taskRepo.findMaxId();
        Long maxSessionId = sessionIds.isEmpty() ? null : sessionRepo.findMaxId();
        return new Owners(tasks, maxTaskId, sessions, maxSessionId);
    }

    private static List<Long> idsOf(List<OwnerDir> dirs, Owner owner) {
        return dirs.stream().filter(d -> d.owner() == owner).map(OwnerDir::id).toList();
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += LOOKUP_CHUNK) {
            out.add(ids.subList(i, Math.min(i + LOOKUP_CHUNK, ids.size())));
        }
        return out;
    }

    /** 유지 사유별 개수 — "(삭제되지 않은 작업 3, 진행 중/답변 완료 질문 2)". 없으면 빈 문자열. */
    private static String breakdown(Map<Verdict, Integer> keptBy) {
        if (keptBy.isEmpty()) return "";
        return keptBy.entrySet().stream()
                .map(e -> e.getKey().label() + " " + e.getValue())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }
}
