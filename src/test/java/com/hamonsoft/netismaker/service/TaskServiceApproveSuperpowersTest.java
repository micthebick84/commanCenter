package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인 바디의 superpowers 켬/끔 (설계 2026-11-03-interview-superpowers-toggle §4) — 끔은 `Boolean.FALSE`일 때만이다.
 * 바디가 없거나 키가 없거나(null) true면 켬이고 이력도 없다. 끔이면 세션에 false를 넘기고, 승인·참조·타입 이력 뒤
 * 마지막에 `인터뷰 방식: 간단(superpowers 끔)` 이력 한 줄을 남긴다. 레거시 분석완료 승인은 바디를 보지 않는다.
 * Docker 없이 도는 단위 시험(하네스는 TaskServiceApproveRefsTest 모양).
 */
class TaskServiceApproveSuperpowersTest {

    private static final String START = "관리자 승인 → 인터뷰 시작";
    private static final String SIMPLE = "인터뷰 방식: 간단(superpowers 끔)";

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
    private final TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
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
        service = new TaskService(taskRepo, analysisRepo, mock(TaskDesignRepository.class),
                historyRepo, mcpCatalog, repoCatalog,
                new ObjectMapper(), interviewService, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class), db,
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        task.setRepoCatalogId(9L);
        task.setRefRepos(new ArrayList<>(List.of(OLD_APP)));
        task.setTaskType(TaskType.NEW_FEATURE);
        when(taskRepo.findActiveByIdForUpdate(5L)).thenReturn(Optional.of(task));
        when(mcpCatalog.resolveExtras(any())).thenReturn(List.of());
        when(db.validateSelection(any(), any(), any())).thenReturn(new ArrayList<>());
    }

    /** 저장된 이력 행의 사유, 저장 순서대로. */
    private List<String> reasons() {
        ArgumentCaptor<TaskStatusHistory> rows = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeast(0)).save(rows.capture());
        return rows.getAllValues().stream().map(TaskStatusHistory::getReason).toList();
    }

    private static ApproveRequest approveWith(Boolean superpowersEnabled) {
        return new ApproveRequest(List.of(), null, null, null, null, null, superpowersEnabled);
    }

    /** 켬으로 읽히는 입력 셋 — 바디 없음, 키 없음(null), 명시 true. */
    static Stream<ApproveRequest> onRequests() {
        return Stream.of(null, approveWith(null), approveWith(Boolean.TRUE),
                new ApproveRequest(List.of(), null, null, null, null, null));   // 6인자 호환 = 키 없음
    }

    @Test
    void 레거시_2인자_승인은_켬() {
        service.approve(5L, "admin");   // 레거시 2인자 호출 = 바디 없음

        verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList(), eq(true));
        assertThat(reasons()).containsExactly(START);
    }

    @ParameterizedTest
    @MethodSource("onRequests")
    void 바디없음_null_true는_켬_이력없음(ApproveRequest req) {
        service.approve(5L, "admin", req);

        verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList(), eq(true));
        verify(interviewService, never()).createForTask(any(), any(), any(), any(), eq(false));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(reasons()).containsExactly(START);
    }

    @Test
    void 끔이면_세션에_false와_마지막_이력() {
        // 참조 [11 main] → [11 main, 12 master], 타입 신규 개발 → DB 작업, 끔
        when(repoCatalog.resolveReferences(9L, List.of(new RefRepoInput(12L, "master")))).thenReturn(List.of(BATCH));
        ApproveRequest req = new ApproveRequest(List.of(), null, null, null,
                List.of(new RefRepoInput(11L, "main"), new RefRepoInput(12L, "master")), "DB_WORK", Boolean.FALSE);

        service.approve(5L, "admin", req);

        verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList(), eq(false));
        verify(interviewService, never()).createForTask(any(), any(), any(), any(), eq(true));
        List<String> reasons = reasons();
        assertThat(reasons).hasSize(4);
        assertThat(reasons.get(0)).isEqualTo(START);
        assertThat(reasons.get(reasons.size() - 1)).isEqualTo(SIMPLE);   // 승인·참조·타입 이력 뒤 마지막
        assertThat(reasons.get(1)).isEqualTo("참조 repo 조정: ＋ 배치 서버(master)");
        assertThat(reasons).contains("타입 변경: 신규 개발 → DB 작업");
    }

    @Test
    void 끔_이력은_같은_상태_행으로_user_관리자_이름을_남긴다() {
        service.approve(5L, "admin", approveWith(Boolean.FALSE));

        ArgumentCaptor<TaskStatusHistory> rows = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeast(1)).save(rows.capture());
        List<TaskStatusHistory> all = rows.getAllValues();
        assertThat(all).hasSize(2);
        TaskStatusHistory simple = all.get(1);
        assertThat(simple.getReason()).isEqualTo(SIMPLE);
        assertThat(simple.getTaskId()).isEqualTo(5L);
        assertThat(simple.getFromStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(simple.getToStatus()).isEqualTo(TaskStatus.INTERVIEWING.dbValue());
        assertThat(simple.getActorType()).isEqualTo("user");
        assertThat(simple.getActorId()).isEqualTo("admin");
    }

    @Test
    void 검증이_실패하면_끔이어도_세션도_이력도_없다() {
        ApproveRequest badType = new ApproveRequest(List.of(), null, null, null, null, "bug_fix", Boolean.FALSE);

        assertThatThrownBy(() -> service.approve(5L, "admin", badType))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(interviewService, never()).createForTask(any(), any(), any(), any(), anyBoolean());
        verify(historyRepo, never()).save(any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void 레거시_분석완료_승인은_끔_바디를_무시한다() {
        task.setStatus(TaskStatus.COMPLETED);
        when(analysisRepo.findById(5L)).thenReturn(
                Optional.of(TaskAnalysis.create(5L, "# 분석", "[]", null, 1000L)));

        service.approve(5L, "admin", approveWith(Boolean.FALSE));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        verify(interviewService, never()).createForTask(any(), any(), any(), any(), anyBoolean());
        assertThat(reasons()).allMatch(r -> !r.equals(SIMPLE));
    }
}
