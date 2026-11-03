package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
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
import org.springframework.context.ApplicationEventPublisher;
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
 * 등록·승인의 작업 타입 (스펙 2026-11-03 §4.2) — 등록 바디의 타입을 저장하고(없으면 null, 모르는 값은 저장 전 400),
 * 승인 바디의 타입이 다르면 세션을 만들기 전에 반영하고 같은 상태 이력 행을 남긴다. null·같은 값은 이력 없는 no-op이고
 * 레거시 분석완료 승인은 타입을 보지 않는다. Docker 없이 도는 단위 시험(하네스는 TaskServiceApproveRefsTest 모양).
 */
class TaskServiceTaskTypeTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
    private final TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final McpCatalogService mcpCatalog = mock(McpCatalogService.class);
    private final RepoCatalogService repoCatalog = mock(RepoCatalogService.class);
    private TaskService service;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepo, analysisRepo, mock(TaskDesignRepository.class),
                historyRepo, mcpCatalog, repoCatalog,
                new ObjectMapper(), interviewService, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class), db,
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(ApplicationEventPublisher.class), mock(QuestionAttachmentRepository.class), 200);
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);

        // 등록 쪽
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
        when(repoCatalog.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github",
                        "micthebick84/netis7.0", null));

        // 승인 쪽
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        task.setRepoCatalogId(9L);
        when(taskRepo.findActiveByIdForUpdate(5L)).thenReturn(Optional.of(task));
        when(mcpCatalog.resolveExtras(any())).thenReturn(List.of());
        when(db.validateSelection(any(), any(), any())).thenReturn(new ArrayList<>());
    }

    private static TaskCreateRequest register(String taskType) {
        return new TaskCreateRequest(7L, "main", "제목", "설명", null, null, taskType);
    }

    private static ApproveRequest approveWith(String taskType) {
        return new ApproveRequest(List.of(), null, null, null, null, taskType);
    }

    private List<TaskStatusHistory> historyRows() {
        ArgumentCaptor<TaskStatusHistory> rows = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeast(0)).save(rows.capture());
        return rows.getAllValues();
    }

    private List<String> reasons() {
        return historyRows().stream().map(TaskStatusHistory::getReason).toList();
    }

    // ── 등록 ──

    @Test
    void 등록_바디의_타입을_저장() {
        Task withType = service.create(register("BUG_FIX"), "user1", null, false);
        assertThat(withType.getTaskType()).isEqualTo(TaskType.BUG_FIX);

        // 키가 없는 옛 화면·다른 호출자는 타입 없음 — 막지 않는다
        Task without = service.create(register(null), "user1", null, false);
        assertThat(without.getTaskType()).isNull();
        // 4인자·6인자 호환 생성자도 타입 없음
        assertThat(service.create(new TaskCreateRequest(7L, "main", "제목", "설명"), "user1", null, false)
                .getTaskType()).isNull();
        assertThat(service.create(new TaskCreateRequest(7L, "main", "제목", "설명", null, null), "user1", null, false)
                .getTaskType()).isNull();
    }

    @Test
    void 등록은_앞뒤_공백을_자르고_공백뿐이면_타입_없음() {
        assertThat(service.create(register(" DB_WORK "), "user1", null, false).getTaskType())
                .isEqualTo(TaskType.DB_WORK);
        assertThat(service.create(register("  "), "user1", null, false).getTaskType()).isNull();
    }

    @Test
    void 모르는_타입은_저장_전에_400() {
        assertThatThrownBy(() -> service.create(register("bug_fix"), "user1", null, false))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("알 수 없는 작업 타입: bug_fix");
                });

        verify(taskRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }

    // ── 승인 ──

    @Test
    void 승인에서_바꾸면_이력() {
        task.setTaskType(TaskType.NEW_FEATURE);

        service.approve(5L, "admin", approveWith("DB_WORK"));

        assertThat(task.getTaskType()).isEqualTo(TaskType.DB_WORK);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작", "타입 변경: 신규 개발 → DB 작업");
        // 타입 변경 이력은 상태를 옮기지 않는 같은 상태 행이다
        TaskStatusHistory changed = historyRows().get(1);
        assertThat(changed.getFromStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(changed.getToStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(changed.getActorType()).isEqualTo("user");
        assertThat(changed.getActorId()).isEqualTo("admin");
    }

    @Test
    void 타입_없음에서_정하면_타입_없음이라고_적는다() {
        service.approve(5L, "admin", approveWith("BUG_FIX"));

        assertThat(task.getTaskType()).isEqualTo(TaskType.BUG_FIX);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작", "타입 변경: 타입 없음 → 버그 픽스");
    }

    @Test
    void 타입은_세션을_만들기_전에_반영된다() {
        AtomicReference<TaskType> typeAtSessionCreation = new AtomicReference<>();
        doAnswer(i -> {
            Task t = i.getArgument(0);
            typeAtSessionCreation.set(t.getTaskType());
            return null;
        }).when(interviewService).createForTask(any(), any(), any(), any());
        task.setTaskType(TaskType.NEW_FEATURE);

        service.approve(5L, "admin", approveWith("FEATURE_CHANGE"));

        assertThat(typeAtSessionCreation.get()).isEqualTo(TaskType.FEATURE_CHANGE);
        InOrder o = inOrder(interviewService, historyRepo);
        o.verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList());
        o.verify(historyRepo, atLeast(1)).save(any());
    }

    @Test
    void 같은_값이나_null이면_이력없는_no_op() {
        task.setTaskType(TaskType.BUG_FIX);

        // 같은 값 — 앞뒤 공백이 있어도 같은 값
        service.approve(5L, "admin", approveWith(" BUG_FIX "));
        assertThat(task.getTaskType()).isEqualTo(TaskType.BUG_FIX);
        assertThat(reasons()).containsExactly("관리자 승인 → 인터뷰 시작");

        // null·공백은 유지 — "타입 없음"으로 되돌리는 경로는 없다(ApproveRequest의 null = 유지 규약)
        for (ApproveRequest keep : List.of(approveWith(null), approveWith("  "),
                new ApproveRequest(List.of(), null, null))) {   // 3인자 호환 = 키 없음
            task.setStatus(TaskStatus.AWAITING_APPROVAL);   // 승인은 한 번에 한 번 — 인터뷰가 실패한 뒤 재승인을 흉내
            service.approve(5L, "admin", keep);
            assertThat(task.getTaskType()).isEqualTo(TaskType.BUG_FIX);
        }
        assertThat(reasons()).allMatch(r -> !r.startsWith("타입 변경"));

        // 타입 없음 작업에 null도 그대로 타입 없음
        Task untyped = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(untyped, "id", 6L);
        untyped.setStatus(TaskStatus.AWAITING_APPROVAL);
        untyped.setRepoCatalogId(9L);
        when(taskRepo.findActiveByIdForUpdate(6L)).thenReturn(Optional.of(untyped));
        service.approve(6L, "admin", approveWith(null));
        assertThat(untyped.getTaskType()).isNull();
        assertThat(reasons()).allMatch(r -> !r.startsWith("타입 변경"));
    }

    @Test
    void 인터뷰가_실패해_승인대기로_돌아오면_재승인에서_다시_바꿀_수_있다() {
        service.approve(5L, "admin", approveWith("FEATURE_CHANGE"));
        assertThat(task.getTaskType()).isEqualTo(TaskType.FEATURE_CHANGE);

        task.setStatus(TaskStatus.AWAITING_APPROVAL);   // InterviewService가 실패·만료·취소 때 되돌리는 상태
        service.approve(5L, "admin", approveWith("BUG_FIX"));

        assertThat(task.getTaskType()).isEqualTo(TaskType.BUG_FIX);
        assertThat(reasons()).contains("타입 변경: 타입 없음 → 기능 수정", "타입 변경: 기능 수정 → 버그 픽스");
    }

    @Test
    void 승인_타입이_틀리면_세션을_만들지_않는다() {
        task.setTaskType(TaskType.NEW_FEATURE);

        assertThatThrownBy(() -> service.approve(5L, "admin", approveWith("bug_fix")))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("알 수 없는 작업 타입: bug_fix");
                });

        verify(interviewService, never()).createForTask(any(), any(), any(), any());
        verify(historyRepo, never()).save(any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(task.getTaskType()).isEqualTo(TaskType.NEW_FEATURE);
    }

    @Test
    void 레거시_분석완료_승인은_타입_무시() {
        task.setStatus(TaskStatus.COMPLETED);
        task.setTaskType(TaskType.NEW_FEATURE);
        when(analysisRepo.findById(5L)).thenReturn(
                Optional.of(TaskAnalysis.create(5L, "# 분석", "[]", null, 1000L)));

        // 모르는 값이어도 이 경로는 바디를 보지 않는다(모델·MCP·DB·참조도 같다)
        service.approve(5L, "admin", approveWith("bug_fix"));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(task.getTaskType()).isEqualTo(TaskType.NEW_FEATURE);

        task.setStatus(TaskStatus.COMPLETED);
        service.approve(5L, "admin", approveWith("DB_WORK"));
        assertThat(task.getTaskType()).isEqualTo(TaskType.NEW_FEATURE);
        assertThat(reasons()).allMatch(r -> !r.startsWith("타입 변경"));
        verify(interviewService, never()).createForTask(any(), any(), any(), any());
    }
}
