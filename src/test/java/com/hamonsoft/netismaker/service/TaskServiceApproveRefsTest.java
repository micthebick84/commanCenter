package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인 때 참조 repo 조정 (스펙 2026-10-24 §4.2) — null = 등록 값 유지, 같은 집합 = 검증 없는 no-op, 바뀌면 새로 들어온 것만
 * 카탈로그 검증. 세션을 만들기 전에 task에 반영하고(createForTask가 복사), 바뀌었으면 승인 이력 다음에 별도 이력 행.
 */
class TaskServiceApproveRefsTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final McpCatalogService mcpCatalog = mock(McpCatalogService.class);
    private final RepoCatalogService repoCatalog = mock(RepoCatalogService.class);
    private TaskService service;
    private Task task;

    private static final RefRepo OLD_APP = new RefRepo(11L, "옛앱", "grp/old-app",
            "https://gitlab.example/grp/old-app.git", "main", null, null, null);
    private static final RefRepo BATCH = new RefRepo(12L, "배치 서버", "grp/batch",
            "https://gitlab.example/grp/batch.git", "master", null, null, null);

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mcpCatalog, repoCatalog,
                new ObjectMapper(), interviewService, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class), db,
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class), mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        task.setRepoCatalogId(9L);
        task.setRefRepos(new ArrayList<>(List.of(OLD_APP)));
        when(taskRepo.findActiveByIdForUpdate(5L)).thenReturn(Optional.of(task));
        when(mcpCatalog.resolveExtras(any())).thenReturn(List.of());
        when(db.validateSelection(any(), any(), any())).thenReturn(new ArrayList<>());
    }

    /** 저장된 이력 행의 사유, 저장 순서대로. */
    private List<String> reasons() {
        return historyRows().stream().map(TaskStatusHistory::getReason).toList();
    }

    private List<TaskStatusHistory> historyRows() {
        ArgumentCaptor<TaskStatusHistory> rows = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeast(0)).save(rows.capture());
        return rows.getAllValues();
    }

    private static ApproveRequest approveWith(List<RefRepoInput> refs) {
        return new ApproveRequest(List.of(), null, null, null, refs);
    }

    @Test
    void null_refs_keep_the_registered_refs_and_add_no_history_row() {
        service.approve(5L, "admin", approveWith(null));

        assertThat(task.getRefRepos()).containsExactly(OLD_APP);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작");
        verify(repoCatalog, never()).resolveReferences(any(), any());
    }

    @Test
    void the_same_set_is_a_no_op_without_resolving() {
        service.approve(5L, "admin", approveWith(List.of(new RefRepoInput(11L, " main "))));

        assertThat(task.getRefRepos()).containsExactly(OLD_APP);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작");
        verify(repoCatalog, never()).resolveReferences(any(), any());
    }

    @Test
    void changed_refs_are_set_before_the_session_and_logged_after_the_approval_row() {
        // task refs [11 main], 요청 refRepos [11 main, 12 master]
        when(repoCatalog.resolveReferences(9L, List.of(new RefRepoInput(12L, "master")))).thenReturn(List.of(BATCH));
        AtomicReference<List<RefRepo>> refsAtSessionCreation = new AtomicReference<>();
        doAnswer(i -> {
            Task t = i.getArgument(0);
            refsAtSessionCreation.set(List.copyOf(t.getRefRepos()));
            return null;
        }).when(interviewService).createForTask(any(), anyString(), anyString(), anyList());

        service.approve(5L, "admin", approveWith(List.of(
                new RefRepoInput(11L, "main"), new RefRepoInput(12L, "master"))));

        InOrder o = inOrder(repoCatalog, interviewService);
        o.verify(repoCatalog).resolveReferences(9L, List.of(new RefRepoInput(12L, "master")));
        o.verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList());
        assertThat(refsAtSessionCreation.get()).containsExactly(OLD_APP, BATCH);   // 세션 복사 시점에 이미 반영됨
        assertThat(task.getRefRepos()).containsExactly(OLD_APP, BATCH);
        assertThat(task.getRefRepos().get(0)).isSameAs(OLD_APP);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작", "참조 repo 조정: ＋ 배치 서버(master)");
        TaskStatusHistory refRow = historyRows().get(1);
        assertThat(refRow.getTaskId()).isEqualTo(5L);
        assertThat(refRow.getFromStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(refRow.getToStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(refRow.getActorType()).isEqualTo("user");
        assertThat(refRow.getActorId()).isEqualTo("admin");
    }

    @Test
    void an_empty_list_clears_every_ref_and_logs_the_removals() {
        service.approve(5L, "admin", approveWith(List.of()));

        assertThat(task.getRefRepos()).isEmpty();
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작", "참조 repo 조정: − 옛앱(main)");
        verify(repoCatalog).resolveReferences(9L, List.of());   // 새로 들어온 것이 없다 — 기존 참조는 다시 검증하지 않는다
    }

    @Test
    void a_rejected_ref_is_400_and_no_session() {
        when(repoCatalog.resolveReferences(eq(9L), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: 배치 서버"));

        assertThatThrownBy(() -> service.approve(5L, "admin", approveWith(List.of(
                new RefRepoInput(11L, "main"), new RefRepoInput(12L, "master")))))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(interviewService, never()).createForTask(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(task.getRefRepos()).containsExactly(OLD_APP);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void legacy_four_arg_request_keeps_refs() {
        service.approve(5L, "admin", new ApproveRequest(List.of(), null, null, null));

        assertThat(task.getRefRepos()).containsExactly(OLD_APP);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작");
        verify(repoCatalog, never()).resolveReferences(any(), any());
    }

    @Test
    void a_missing_body_keeps_refs() {
        service.approve(5L, "admin", null);

        assertThat(task.getRefRepos()).containsExactly(OLD_APP);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작");
    }
}
