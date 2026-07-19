package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDesign;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskDesign 저장/조회 — 기본값(reject_count=0, approved=false, feedback_history='[]')이
 * DB 라운드트립 후에도 유지되는지 검증. 실 Postgres 필요 (jsonb 컬럼 + task_design.task_id FK).
 * RUN_TESTCONTAINERS=true에서만 실행 (H2 미사용 — InterviewActiveQueryTest와 동일 게이트).
 *
 * task_design.task_id는 com.task(id) FK다. IDENTITY 시퀀스는 공유 Testcontainer에서
 * 다른 테스트 클래스의 삽입으로 계속 증가하므로(브리핑 예시의 하드코딩 taskId=1L은
 * 실행 순서에 따라 FK 위반 가능) 실제로 저장한 Task의 생성된 id를 사용한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskDesignRepositoryTest {

    @Autowired TaskRepository taskRepo;
    @Autowired TaskDesignRepository designRepo;

    @Test
    void 저장_후_기본값과_필드가_유지된다() {
        Task task = taskRepo.saveAndFlush(Task.create("owner/repo", "main", "T", "d",
                "user1", 3, List.of(), null, null));

        TaskDesign d = TaskDesign.create(task.getId(), "# 디자인",
                "[{\"path\":\"screens/main.html\",\"title\":\"메인\",\"html\":\"<html></html>\"}]",
                "proj-1", "https://claude.ai/design/proj-1", "log", 1000L);
        designRepo.saveAndFlush(d);
        TaskDesign found = designRepo.findById(task.getId()).orElseThrow();
        assertThat(found.getRejectCount()).isZero();
        assertThat(found.isApproved()).isFalse();
        assertThat(found.getFeedbackHistoryJson()).isEqualTo("[]");
        assertThat(found.getDesignMarkdown()).isEqualTo("# 디자인");
    }
}
