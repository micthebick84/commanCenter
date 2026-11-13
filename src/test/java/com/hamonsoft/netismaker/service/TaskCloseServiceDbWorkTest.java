package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DB 작업의 배포 없는 종료 (스펙 2026-11-03 §6.9) — PR생성에서 종료 요청, 철회·반려·종료 취소의 복귀 상태, 산출물 모드.
 * 하네스는 TaskCloseServiceTest와 같다.
 */
class TaskCloseServiceDbWorkTest {

    private static final String GATE_MESSAGE =
            "배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (DB 작업은 PR생성에서도 가능)";

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private RepoCatalogRepository catalogRepo;
    private TaskCloseService service;

    private Task task(TaskStatus status, TaskType type) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setTaskType(type);
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
        service = new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(false, null), new ClosePatchProperties(false, null), mock(TaskFixRequestRepository.class), 3);
    }

    private TaskCloseService artifactEnabledService() {
        return new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(true, null), new ClosePatchProperties(false, null), mock(TaskFixRequestRepository.class), 3);
    }

    /** 카탈로그 항목(id 5)의 산출물 칸을 켜 두고 작업에 연결한다. */
    private void catalogArtifactOn(Task t) {
        t.setRepoCatalogId(5L);
        RepoCatalogEntry e = RepoCatalogEntry.create("W", "https://github.com/owner/repo.git", "github",
                "owner/repo", null, null, "admin");
        e.setArtifactEnabled(true);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(e));
    }

    private ArgumentCaptor<TaskStatusHistory> savedHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        return cap;
    }

    @Test
    void DB작업은_PR생성에서_종료요청() {
        Task t = task(TaskStatus.PR_CREATED, TaskType.DB_WORK);

        Task r = service.requestClose(42L, "user1", false);

        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW);
        assertThat(r.getWorkerId()).isNull();
        TaskStatusHistory h = savedHistory().getValue();
        assertThat(h.getFromStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(h.getToStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW.dbValue());
        assertThat(h.getReason()).isEqualTo("결과 확인 완료 → 종료 요청");
        assertThat(t.getDeployUrl()).isNull();   // 배포 칸은 건드리지 않는다
    }

    @Test
    void 관리자도_DB작업의_PR생성_종료를_대신_요청하고_다른_사용자는_403() {
        task(TaskStatus.PR_CREATED, TaskType.DB_WORK);
        assertThatThrownBy(() -> service.requestClose(42L, "someone", false))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.requestClose(42L, "admin", true).getStatus()).isEqualTo(TaskStatus.CLOSE_REVIEW);
    }

    @Test
    void DB작업이어도_PR_정보가_없으면_PR생성에서_요청할_수_없다() {
        Task t = task(TaskStatus.PR_CREATED, TaskType.DB_WORK);
        t.setPrNumber(null);
        assertThatThrownBy(() -> service.requestClose(42L, "user1", false))
                .isInstanceOf(TaskException.class).hasMessageContaining("PR 정보");
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 다른타입_PR생성은_409() {
        for (TaskType type : new TaskType[]{TaskType.BUG_FIX, TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE, null}) {
            Task t = task(TaskStatus.PR_CREATED, type);
            assertThatThrownBy(() -> service.requestClose(42L, "user1", false))
                    .as("타입 %s", type)
                    .isInstanceOf(TaskException.class)
                    .hasMessageContaining("배포완료")
                    .hasMessageContaining(GATE_MESSAGE)
                    .hasMessageContaining("현재: PR생성")
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        }
        verify(historyRepo, never()).save(any());
    }

    @Test
    void DB작업이어도_PR생성_배포완료_배포중단됨_밖의_상태는_409() {
        for (TaskStatus s : new TaskStatus[]{TaskStatus.IMPLEMENTING, TaskStatus.DEPLOY_FAILED,
                TaskStatus.DEPLOYING, TaskStatus.APPROVED, TaskStatus.FIX_REVIEW}) {
            task(s, TaskType.DB_WORK);
            assertThatThrownBy(() -> service.requestClose(42L, "user1", false))
                    .as("상태 %s", s)
                    .isInstanceOf(TaskException.class).hasMessageContaining(GATE_MESSAGE);
        }
    }

    @Test
    void 철회_반려_종료취소는_PR생성으로() {
        // 철회
        task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.PR_CREATED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.PR_CREATED);

        // 반려
        task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        assertThat(service.reject(42L, "admin", "결과가 다름").getStatus()).isEqualTo(TaskStatus.PR_CREATED);

        // 종료 취소(병합 전)
        Task failed = task(TaskStatus.CLOSE_FAILED, TaskType.DB_WORK);
        failed.setCloseProgress("");
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.CLOSING, TaskStatus.CLOSE_FAILED, "system", "w", "head 불일치"),
                TaskStatusHistory.log(42L, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING, "system", "w", "claim"),
                TaskStatusHistory.log(42L, TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, "user", "admin", "승인"),
                TaskStatusHistory.log(42L, TaskStatus.PR_CREATED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.cancel(42L, "admin").getStatus()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 이력의_직전_상태가_배포완료_배포중단됨이면_타입과_상관없이_그대로() {
        task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.DEPLOYED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOYED);

        task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.DEPLOY_LOST, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
    }

    @Test
    void 이력을_못찾으면_배포흔적없는_DB작업은_PR생성_있으면_배포중단됨() {
        // 이력이 비었을 때 — 배포한 적 없는 DB 작업은 PR생성(그대로 두면 배포중단됨에 갇힌다: 컨테이너가 없어 되살아나지 않음)
        Task neverDeployed = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of());
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(neverDeployed.getDeployUrl()).isNull();

        // 이력의 직전 상태가 후보(배포완료·배포중단됨·PR생성) 밖이어도 같은 대체값
        task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, TaskStatus.APPROVED, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
        assertThat(service.reject(42L, "admin", "다시").getStatus()).isEqualTo(TaskStatus.PR_CREATED);

        // 배포 흔적이 있으면(deployUrl 또는 deployedAt) 배포중단됨
        Task withUrl = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        withUrl.setDeployUrl("https://task-42.example.dev");
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of());
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);

        Task withTime = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        withTime.setDeployedAt(OffsetDateTime.now());
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);

        // DB 작업이 아니면 배포 흔적이 없어도 지금처럼 배포중단됨
        task(TaskStatus.CLOSE_REVIEW, TaskType.BUG_FIX);
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
        task(TaskStatus.CLOSE_REVIEW, null);
        assertThat(service.withdraw(42L, "user1", false).getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
    }

    @Test
    void DB작업_종료승인은_산출물모드_null() {
        // 배포 흔적 없음: 스위치·카탈로그가 켜져 있어도 null, 이력에 "(산출물 보관)" 없음
        Task t = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        catalogArtifactOn(t);

        Task r = artifactEnabledService().approve(42L, "admin");

        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(r.getCloseArtifactMode()).isNull();
        assertThat(savedHistory().getValue().getReason()).isEqualTo("관리자 종료 승인 → 종료 큐 진입");
    }

    @Test
    void 배포한_DB작업은_산출물모드_그대로() {
        // deployUrl·deployedAt 있음 → 다른 작업과 같다
        Task t = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        catalogArtifactOn(t);
        t.setDeployUrl("https://task-42.example.dev");
        t.setDeployedAt(OffsetDateTime.now());

        Task r = artifactEnabledService().approve(42L, "admin");

        assertThat(r.getCloseArtifactMode()).isEqualTo("REQUIRED");
        assertThat(savedHistory().getValue().getReason()).isEqualTo("관리자 종료 승인 → 종료 큐 진입 (산출물 보관)");
    }

    @Test
    void 배포흔적이_한쪽만_있어도_배포한_DB작업으로_본다() {
        Task t = task(TaskStatus.CLOSE_REVIEW, TaskType.DB_WORK);
        catalogArtifactOn(t);
        t.setDeployUrl("https://task-42.example.dev");   // deployedAt 없음
        assertThat(artifactEnabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");

        t.setStatus(TaskStatus.CLOSE_REVIEW);
        t.setDeployUrl(null);
        t.setDeployedAt(OffsetDateTime.now());           // deployUrl 없음
        assertThat(artifactEnabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");
    }

    @Test
    void 다른타입의_배포_흔적없는_종료승인은_지금처럼_스위치와_카탈로그를_따른다() {
        // 배포완료·배포중단됨에서 온 작업은 배포 흔적이 항상 있지만, 시험 데이터에서 흔적이 없어도 DB 작업이 아니면 가르지 않는다
        for (TaskType type : new TaskType[]{TaskType.BUG_FIX, TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE, null}) {
            Task t = task(TaskStatus.CLOSE_REVIEW, type);
            catalogArtifactOn(t);
            assertThat(artifactEnabledService().approve(42L, "admin").getCloseArtifactMode())
                    .as("타입 %s", type).isEqualTo("REQUIRED");
        }
    }

    @Test
    void 수정요청_가능상태는_그대로() {
        // TaskFixService.REQUESTABLE은 이 변경으로 바뀌지 않는다 — PR생성은 들어가지 않는다 (§6.9)
        assertThat(TaskFixService.REQUESTABLE).isEqualTo(
                EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.DEPLOY_FAILED));
        assertThat(TaskFixService.REQUESTABLE).doesNotContain(TaskStatus.PR_CREATED);
    }

    @Test
    void 종료요청_후보_판정은_상태와_타입으로_가른다() {
        Set<TaskStatus> always = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST);
        for (TaskStatus s : TaskStatus.values()) {
            for (TaskType type : new TaskType[]{TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE, TaskType.BUG_FIX,
                    TaskType.DB_WORK, null}) {
                Task t = task(s, type);
                boolean expected = always.contains(s) || (s == TaskStatus.PR_CREATED && type == TaskType.DB_WORK);
                assertThat(TaskCloseService.canRequestClose(t)).as("%s / %s", s, type).isEqualTo(expected);
            }
        }
    }

    @Test
    void 배포흔적_판정은_deployUrl과_deployedAt이_둘_다_비었을_때만() {
        Task t = task(TaskStatus.PR_CREATED, TaskType.DB_WORK);
        assertThat(TaskCloseService.neverDeployed(t)).isTrue();
        t.setDeployUrl("https://task-42.example.dev");
        assertThat(TaskCloseService.neverDeployed(t)).isFalse();
        t.setDeployUrl(null);
        t.setDeployedAt(OffsetDateTime.now());
        assertThat(TaskCloseService.neverDeployed(t)).isFalse();
        t.setDeployUrl("https://task-42.example.dev");
        assertThat(TaskCloseService.neverDeployed(t)).isFalse();
    }
}
