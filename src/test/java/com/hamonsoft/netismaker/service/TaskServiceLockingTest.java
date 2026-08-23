package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cancel/retry/softDelete 비관적 락 동시성 테스트 (PR #26 approve 락의 확장).
 * findActiveByIdForUpdate(FOR UPDATE, SKIP LOCKED 없음) 없이 돌리면 두 스레드가
 * 같은 스냅샷을 읽어 둘 다 성공한다 — 실제 Postgres 트랜잭션으로 재현되는 결정적 테스트.
 * 템플릿: TaskServiceApproveInterviewTest.concurrent_double_approve_creates_only_one_session.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceLockingTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        analysisRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task register() {
        return taskService.create(new TaskCreateRequest(1L, "main", "RBAC 추가", "역할 기반 권한"), "user1");
    }

    /** 두 작업을 latch로 동시 실행 — 성공은 null, TaskException은 HTTP status 반환. */
    private List<HttpStatus> raceBoth(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> futures = new ArrayList<>();
        for (Callable<?> action : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    action.call();
                    return null;
                } catch (TaskException e) {
                    return e.getStatus();
                }
            }));
        }
        start.countDown();
        List<HttpStatus> results = new ArrayList<>();
        for (Future<HttpStatus> f : futures) {
            results.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private long count(List<HttpStatus> results, HttpStatus status) {
        return results.stream().filter(r -> r == status).count();
    }

    @Test
    void concurrent_double_cancel_succeeds_exactly_once() throws Exception {
        Task t = register(); // AWAITING_APPROVAL

        Callable<?> cancel = () -> taskService.cancel(t.getId(), "user1", false);
        List<HttpStatus> results = raceBoth(cancel, cancel);

        assertThat(count(results, null)).as("정확히 한 번만 취소가 성공해야 함").isEqualTo(1);
        assertThat(count(results, HttpStatus.CONFLICT)).as("나머지 한 번은 409").isEqualTo(1);
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
        long cancelledHistoryRows = historyRepo.findByTaskIdOrderByAtDesc(t.getId()).stream()
                .filter(h -> TaskStatus.CANCELLED.dbValue().equals(h.getToStatus()))
                .count();
        assertThat(cancelledHistoryRows).as("CANCELLED 히스토리 행은 정확히 1개").isEqualTo(1);
    }

    @Test
    void concurrent_double_retry_increments_retry_count_exactly_once() throws Exception {
        Task t = register();
        t.setStatus(TaskStatus.FAILED); // 분석실패 시드 (retryCount=0, maxRetry=3)
        taskRepo.save(t);

        Callable<?> retry = () -> taskService.retry(t.getId(), "user1", false);
        List<HttpStatus> results = raceBoth(retry, retry);

        assertThat(count(results, null)).as("정확히 한 번만 재시도가 성공해야 함").isEqualTo(1);
        assertThat(count(results, HttpStatus.CONFLICT)).isEqualTo(1);
        Task after = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getRetryCount()).as("retryCount는 정확히 1이어야 함 (증분 유실/중복 금지)").isEqualTo(1);
    }

    @Test
    void concurrent_double_soft_delete_succeeds_exactly_once() throws Exception {
        Task t = register();

        Callable<?> del = () -> {
            taskService.softDelete(t.getId(), "user1", false);
            return null;
        };
        List<HttpStatus> results = raceBoth(del, del);

        // 두 번째는 deletedAt IS NULL 조건에서 걸러져 404
        assertThat(count(results, null)).as("정확히 한 번만 삭제가 성공해야 함").isEqualTo(1);
        assertThat(count(results, HttpStatus.NOT_FOUND)).isEqualTo(1);
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    /**
     * softDelete vs approve 경합 — 어느 쪽이 이기든 불변식:
     * 삭제된 task(deletedAt != null)에는 열린 인터뷰 세션이 남아 있으면 안 된다.
     * (락이 없으면 approve가 세션을 만드는 사이 softDelete가 옛 세션 목록으로 정리를 끝내
     *  '삭제된 task + 살아있는 QUEUED 세션' 고아가 생길 수 있다.)
     */
    @Test
    void soft_delete_vs_approve_never_leaves_an_open_session_on_a_deleted_task() throws Exception {
        Task t = register(); // AWAITING_APPROVAL

        raceBoth(
                () -> {
                    taskService.approve(t.getId(), "admin", null);
                    return null;
                },
                () -> {
                    taskService.softDelete(t.getId(), "user1", false);
                    return null;
                });

        Task after = taskRepo.findById(t.getId()).orElseThrow();
        if (after.getDeletedAt() != null) {
            assertThat(sessionRepo.findOpenByTaskId(t.getId()))
                    .as("삭제된 task에 열린 세션(QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY)이 없어야 함")
                    .isEmpty();
        } else {
            // 삭제가 안 됐다면 approve만 이긴 것 — 세션은 살아 있고 task는 인터뷰중이어야 정상
            assertThat(after.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        }
        // 어느 순서든 세션 상태는 QUEUED(승인만 성공) 또는 CANCELLED(승인 후 삭제)만 허용
        sessionRepo.findAll().forEach(s ->
                assertThat(s.getStatus()).isIn(InterviewStatus.QUEUED, InterviewStatus.CANCELLED));
    }
}
