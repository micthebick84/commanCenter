package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * recordResult 디자인 분기 (워커 결과 보고 수신) — Task 5.
 *
 * 실 Postgres 필요. RUN_TESTCONTAINERS=true에서만 실행.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerServiceDesignResultTest {

    @Autowired private WorkerService workerService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanUp() {
        // FK 위반 방지: task_design/interview_session(자식) 먼저 삭제, 그 다음 task(부모) 삭제
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** DESIGNING 상태의 task를 저장 (workerId="w1"). repoCatalog 연결은 옵션. */
    private Task designingTask(Long repoCatalogId) {
        Task t = Task.create("hamonsoft/netis-backend", "main", "버튼 컴포넌트 디자인",
                "새 버튼 컴포넌트", "user1", 3, List.of(), "claude-opus-4-8", "high");
        t.setDesignRequested(true);
        t.setRepoCatalogId(repoCatalogId);
        t.setStatus(TaskStatus.DESIGNING);
        t.setWorkerId("w1");
        return taskRepo.save(t);
    }

    @Test
    void 디자인_성공_보고는_task_design을_저장하고_디자인승인대기로_전이한다() {
        Task t = designingTask(null);

        workerService.recordResult(t.getId(), WorkerResultRequest.designReview(
                "w1", "# D", "[{\"path\":\"a.html\",\"title\":\"A\",\"html\":\"<div/>\"}]",
                "p", "u", "log", 5L, null));

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGN_REVIEW);

        TaskDesign d = designRepo.findById(t.getId()).orElseThrow();
        assertThat(d.getDesignMarkdown()).isEqualTo("# D");
        assertThat(d.getDesignUrl()).isEqualTo("u");
    }

    @Test
    void 반려_재실행_보고는_reject_count와_피드백_이력을_보존한다() throws Exception {
        Task t = designingTask(null);

        TaskDesign prev = TaskDesign.create(t.getId(), "# 이전 디자인", "[{\"path\":\"a.html\",\"title\":\"A\",\"html\":\"<div/>\"}]",
                "p-old", "u-old", "old log", 1000L);
        prev.setRejectCount(1);
        prev.setFeedbackHistoryJson("[{\"feedback\":\"f1\"}]");
        designRepo.save(prev);

        workerService.recordResult(t.getId(), WorkerResultRequest.designReview(
                "w1", "# 새 디자인", "[{\"path\":\"b.html\",\"title\":\"B\",\"html\":\"<div/>\"}]",
                null, null, "new log", 10L, null));

        TaskDesign reloaded = designRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getRejectCount()).isEqualTo(1);
        // jsonb 컬럼은 Postgres가 재직렬화(공백 삽입)하므로 문자열이 아닌 JSON 의미로 비교
        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(reloaded.getFeedbackHistoryJson()))
                .isEqualTo(om.readTree("[{\"feedback\":\"f1\"}]"));
        assertThat(reloaded.getDesignMarkdown()).isEqualTo("# 새 디자인");
        // designUrl은 무조건 덮어쓴다 (업로드 실패 시 null → 이전 URL이 남아 구버전 목업으로 오도하지 않도록)
        assertThat(reloaded.getDesignUrl()).isNull();
        // designProjectId는 null이면 이전 값 보존 (프로젝트 재사용 목적)
        assertThat(reloaded.getDesignProjectId()).isEqualTo("p-old");

        Task reloadedTask = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloadedTask.getStatus()).isEqualTo(TaskStatus.DESIGN_REVIEW);
    }

    @Test
    void 디자인_실패_보고는_디자인실패와_사유를_기록한다() {
        Task t = designingTask(null);

        workerService.recordResult(t.getId(), WorkerResultRequest.designFailed(
                "w1", "DesignSync 인증 실패", "log", null));

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGN_FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("DesignSync 인증 실패");
    }

    @Test
    void 성공_보고에_designMarkdown_없으면_400() {
        Task t = designingTask(null);

        WorkerResultRequest req = new WorkerResultRequest("w1", TaskStatus.DESIGN_REVIEW,
                null, null, "log", 5L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, "[{\"path\":\"a.html\"}]", "p", "u", null);

        assertThatThrownBy(() -> workerService.recordResult(t.getId(), req))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    @Test
    void 성공_보고시_카탈로그에_출력_프로젝트가_없으면_박제한다() {
        RepoCatalogEntry cat = RepoCatalogEntry.create("design-result-" + System.nanoTime(),
                "https://github.com/hamonsoft/design-result-" + System.nanoTime(),
                "github", "hamonsoft/design-result", "main", "설명", "admin1");
        cat = repoCatalogRepo.save(cat);

        Task t = designingTask(cat.getId());

        workerService.recordResult(t.getId(), WorkerResultRequest.designReview(
                "w1", "# D", "[{\"path\":\"a.html\",\"title\":\"A\",\"html\":\"<div/>\"}]",
                "new-proj", "u", "log", 5L, null));

        RepoCatalogEntry reloaded = repoCatalogRepo.findById(cat.getId()).orElseThrow();
        assertThat(reloaded.getDesignOutputProjectId()).isEqualTo("new-proj");
    }
}
