package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskCloseServiceTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskCloseService service;

    private Task task(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setPrNumber(7);
        t.setPrUrl("https://github.com/owner/repo/pull/7");
        t.setHeadBranch("netismaker/task-42");
        t.setHeadSha("abcdef1234567890abcdef1234567890abcdef12");
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new TaskCloseService(taskRepo, historyRepo);
    }

    @Test
    void 등록자는_배포완료에서_종료를_요청한다() {
        task(TaskStatus.DEPLOYED);
        assertThat(service.requestClose(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW);
        verify(historyRepo).save(any());
    }

    @Test
    void 배포중단됨에서도_요청할_수_있다() {
        task(TaskStatus.DEPLOY_LOST);
        assertThat(service.requestClose(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW);
    }

    @Test
    void 다른_사용자는_요청할_수_없고_관리자는_대신_요청한다() {
        task(TaskStatus.DEPLOYED);
        assertThatThrownBy(() -> service.requestClose(42L, "someone", false)).isInstanceOf(TaskException.class);
        assertThat(service.requestClose(42L, "admin", true).getStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW);
    }

    @Test
    void PR생성_상태에서는_요청할_수_없다() {
        task(TaskStatus.PR_CREATED);
        assertThatThrownBy(() -> service.requestClose(42L, "user1", false))
                .isInstanceOf(TaskException.class).hasMessageContaining("배포완료");
    }

    @Test
    void PR_정보가_없으면_요청할_수_없다() {
        Task t = task(TaskStatus.DEPLOYED);
        t.setHeadSha(null);
        assertThatThrownBy(() -> service.requestClose(42L, "user1", false))
                .isInstanceOf(TaskException.class).hasMessageContaining("PR");
    }

    @Test
    void 관리자_승인은_종료대기로_보낸다() {
        Task t = task(TaskStatus.CLOSE_REVIEW);
        t.setWorkerId("w");
        Task r = service.approve(42L, "admin");
        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(r.getWorkerId()).isNull();
    }

    @Test
    void 반려는_직전_상태로_돌아간다_배포중단됨() {
        task(TaskStatus.CLOSE_REVIEW);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.DEPLOY_LOST, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청"),
                TaskStatusHistory.log(42L, TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, "system", "w", "소실")));
        assertThat(service.reject(42L, "admin", "결과가 다름").getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
    }

    @Test
    void 철회는_직전_상태로_돌아간다_배포완료() {
        task(TaskStatus.CLOSE_REVIEW);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.DEPLOYED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOYED);
    }

    @Test
    void 이력을_못_찾으면_보수적으로_배포중단됨() {
        task(TaskStatus.CLOSE_REVIEW);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of());
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
    }

    @Test
    void 반려_사유는_필수() {
        task(TaskStatus.CLOSE_REVIEW);
        assertThatThrownBy(() -> service.reject(42L, "admin", " ")).isInstanceOf(TaskException.class);
    }

    @Test
    void 재시도는_종료실패에서만_진행을_유지한채_종료대기로() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setFailureReason("CI 대기 중");
        t.setCloseProgress("MERGED");
        Task r = service.retry(42L, "admin");
        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(r.getFailureReason()).isNull();
        assertThat(r.getCloseProgress()).isEqualTo("MERGED");
    }

    @Test
    void 재시도는_다른_상태에서_409() {
        task(TaskStatus.DEPLOYED);
        assertThatThrownBy(() -> service.retry(42L, "admin")).isInstanceOf(TaskException.class);
    }
}
