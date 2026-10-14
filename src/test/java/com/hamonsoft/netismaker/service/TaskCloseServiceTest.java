package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
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
    private RepoCatalogRepository catalogRepo;
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
        catalogRepo = mock(RepoCatalogRepository.class);
        service = new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(false, null));
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

    @Test
    void 병합_전_종료실패는_취소해_직전_배포_상태로_되돌린다() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setFailureReason("head 불일치");
        t.setCloseProgress("");
        t.setWorkerId("w");
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.CLOSING, TaskStatus.CLOSE_FAILED, "system", "w", "head 불일치"),
                TaskStatusHistory.log(42L, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING, "system", "w", "claim"),
                TaskStatusHistory.log(42L, TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, "user", "admin", "승인"),
                TaskStatusHistory.log(42L, TaskStatus.DEPLOYED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));

        Task r = service.cancel(42L, "admin");

        assertThat(r.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(r.getFailureReason()).isNull();
        assertThat(r.getCloseProgress()).isNull();
        assertThat(r.getWorkerId()).isNull();
        org.mockito.ArgumentCaptor<TaskStatusHistory> h = org.mockito.ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(h.capture());
        assertThat(h.getValue().getReason()).isEqualTo("종료 취소(병합 전)");
        assertThat(h.getValue().getToStatus()).isEqualTo(TaskStatus.DEPLOYED.dbValue());
    }

    @Test
    void 이미_병합됐으면_종료를_취소할_수_없다() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseProgress("MERGED,UNDEPLOYED");
        assertThatThrownBy(() -> service.cancel(42L, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("이미 병합되어 종료를 취소할 수 없습니다 — 재시도로 마무리하세요")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSE_FAILED);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 종료실패가_아니면_취소는_409() {
        task(TaskStatus.CLOSING);
        assertThatThrownBy(() -> service.cancel(42L, "admin"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(historyRepo, never()).save(any());
    }

    private TaskCloseService enabledService() {
        return new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(true, null));
    }

    private RepoCatalogEntry catalog(boolean artifactEnabled) {
        RepoCatalogEntry e = RepoCatalogEntry.create("W", "https://github.com/owner/repo.git", "github",
                "owner/repo", null, null, "admin");
        e.setArtifactEnabled(artifactEnabled);
        return e;
    }

    @Test
    void 승인은_전역_스위치와_카탈로그가_모두_켜졌을때만_산출물_모드를_REQUIRED로_둔다() {
        Task t = task(TaskStatus.CLOSE_REVIEW);
        t.setRepoCatalogId(5L);
        RepoCatalogEntry e = catalog(true);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(e));

        assertThat(service.approve(42L, "admin").getCloseArtifactMode()).isNull();   // 전역 스위치 꺼짐

        t.setStatus(TaskStatus.CLOSE_REVIEW);
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");

        t.setStatus(TaskStatus.CLOSE_REVIEW);
        e.setArtifactEnabled(false);
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isNull();
    }

    @Test
    void 카탈로그_id가_없으면_git_url로_찾고_그래도_없으면_해당_없음() {
        Task t = task(TaskStatus.CLOSE_REVIEW);
        t.setGitUrl("https://github.com/owner/repo.git");
        when(catalogRepo.findByGitUrl("https://github.com/owner/repo.git")).thenReturn(Optional.of(catalog(true)));
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");

        task(TaskStatus.CLOSE_REVIEW);   // gitUrl·catalogId 없는 새 작업
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isNull();
    }

    @Test
    void 산출물_단계에서_멈춘_종료실패는_산출물_없이_마무리한다() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setFailureReason("산출물: 이미지 없음(netis-task-42:abcdef1)");

        Task r = service.skipArtifact(42L, "admin", "이미지를 수동으로 지웠음");

        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(r.getCloseArtifactMode()).isEqualTo("SKIPPED");
        assertThat(r.getFailureReason()).isNull();
        assertThat(r.getCloseProgress()).isEqualTo("MERGED,UNDEPLOYED");
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo("산출물 없이 마무리: 이미지를 수동으로 지웠음");
    }

    @Test
    void 산출물_없이_마무리는_조건이_안_맞으면_409() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED");                              // 배포 중지 전
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseProgress("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED"); // 이미 올림
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setCloseArtifactMode(null);                              // 산출물 대상 아님
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseArtifactMode("REQUIRED");
        t.setStatus(TaskStatus.CLOSING);                           // 실패 상태 아님
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null))
                .isInstanceOf(TaskException.class).hasMessageContaining("종료실패");
    }
}
