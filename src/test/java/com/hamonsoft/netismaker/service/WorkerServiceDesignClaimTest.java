package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN_PENDING claim 큐 편입 — Task 4.
 *
 * 실 Postgres 필요(FOR UPDATE SKIP LOCKED). RUN_TESTCONTAINERS=true에서만 실행.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerServiceDesignClaimTest {

    @Autowired private WorkerService workerService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanUp() {
        // repoCatalogRepo는 삭제하지 않음 — V14 시드(alias 'Netis7.0')를 다른 테스트 클래스가
        // 같은 컨테이너에서 공유하므로 여기서 deleteAll하면 그쪽이 깨진다.
        // FK 위반 방지: task_design/interview_session(자식) 먼저 삭제, 그 다음 task(부모) 삭제
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** DESIGN_PENDING 상태의 task를 저장. repoCatalog에 designSystemProjectId="ds-1" 세팅. */
    private Task designPendingTask() {
        // 테스트 클래스 내 두 번째 @Test 실행 시 alias/git_url unique 충돌을 피하기 위해 고유값 사용.
        String suffix = "design-claim-" + System.nanoTime();
        RepoCatalogEntry cat = RepoCatalogEntry.create(suffix, "https://github.com/hamonsoft/" + suffix,
                "github", "hamonsoft/" + suffix, "main", "설명", "admin1");
        cat.setDesignSystemProjectId("ds-1");
        cat = repoCatalogRepo.save(cat);

        Task t = Task.create("hamonsoft/netis-backend", "main", "버튼 컴포넌트 디자인",
                "새 버튼 컴포넌트", "user1", 3, List.of(), "claude-opus-4-8", "high");
        t.setDesignRequested(true);
        t.setRepoCatalogId(cat.getId());
        t.setStatus(TaskStatus.DESIGN_PENDING);
        return taskRepo.save(t);
    }

    @Test
    void 디자인대기_작업은_kind_DESIGN으로_claim되고_디자인중이_된다() {
        Task t = designPendingTask();

        Optional<WorkerTaskResponse> claimed = workerService.claimNextTask("w1");

        assertThat(claimed).isPresent();
        WorkerTaskResponse r = claimed.get();
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
        assertThat(r.designSystemProjectId()).isEqualTo("ds-1");

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGNING);
        assertThat(reloaded.getWorkerId()).isEqualTo("w1");
    }

    @Test
    void 반려_이력이_있으면_이전_디자인과_피드백이_동봉된다() {
        Task t = designPendingTask();

        TaskDesign prev = TaskDesign.create(t.getId(), "# 이전 디자인 마크다운",
                "[{\"path\":\"a.html\",\"title\":\"A\",\"html\":\"<div/>\"}]",
                "design-out-1", "https://claude.ai/design/design-out-1",
                "claude log", 1000L);
        prev.setRejectCount(1);
        prev.setFeedbackHistoryJson("[{\"round\":1,\"feedback\":\"버튼 색이 이상함\"}]");
        designRepo.save(prev);

        Optional<WorkerTaskResponse> claimed = workerService.claimNextTask("w1");

        assertThat(claimed).isPresent();
        WorkerTaskResponse r = claimed.get();
        assertThat(r.designMarkdown()).isEqualTo("# 이전 디자인 마크다운");
        assertThat(r.feedbackHistoryJson()).isEqualTo("[{\"round\":1,\"feedback\":\"버튼 색이 이상함\"}]");
    }
}
