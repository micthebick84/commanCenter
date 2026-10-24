package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * "이 대화로 작업 등록" (스펙 2026-10-24 §4.1) — sourceQuestionId가 있으면 등록자의 질문 조회 권한·답변 완료를
 * 저장 전에 확인하고, 출처를 기록하고, 대화 전문 .md를 사용자 첨부 다음 순번의 작업 첨부로 남긴다.
 */
class TaskServiceSourceQuestionTest {

    private static final String TRANSCRIPT = "# 전문 한글";

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private RepoCatalogService repoCatalogService;
    private InterviewService interviewService;
    private TaskAttachmentRepository attachmentRepo;
    private AttachmentStorage attachmentStorage;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        repoCatalogService = mock(RepoCatalogService.class);
        interviewService = mock(InterviewService.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        attachmentStorage = mock(AttachmentStorage.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                interviewService, attachmentRepo, attachmentStorage,
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class));
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attachmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
        when(repoCatalogService.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github",
                        "micthebick84/netis7.0", null));
    }

    private static TaskCreateRequest req(Long sourceQuestionId) {
        return new TaskCreateRequest(7L, "main", "제목", "설명", null, sourceQuestionId);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void json_registration_records_the_source_and_attaches_the_transcript() {
        when(interviewService.questionTranscript(9L, "user1", false)).thenReturn(TRANSCRIPT);
        when(attachmentStorage.relativePath(1L, 1, "question-9-대화.md")).thenReturn("task-1/1-question-9-대화.md");

        Task t = service.create(req(9L), "user1", null, false);

        assertThat(t.getSourceQuestionId()).isEqualTo(9L);
        verify(attachmentStorage).writeText("task-1/1-question-9-대화.md", TRANSCRIPT);
        ArgumentCaptor<TaskAttachment> att = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepo).save(att.capture());
        assertThat(att.getValue().getTaskId()).isEqualTo(1L);
        assertThat(att.getValue().getOriginalFilename()).isEqualTo("question-9-대화.md");
        assertThat(att.getValue().getStoredPath()).isEqualTo("task-1/1-question-9-대화.md");
        assertThat(att.getValue().getContentType()).isEqualTo("text/markdown");
        assertThat(att.getValue().getSizeBytes()).isEqualTo(TRANSCRIPT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(att.getValue().getUploadedBy()).isEqualTo("user1");
        // 등록 이력 문구는 그대로 — 출처 기록은 별도 행을 만들지 않는다
        verify(historyRepo, times(1)).save(any());
    }

    @Test
    void multipart_registration_puts_the_transcript_after_the_user_files() {
        MockMultipartFile f1 = file("a.txt");
        MockMultipartFile f2 = file("b.txt");
        when(interviewService.questionTranscript(9L, "user1", false)).thenReturn(TRANSCRIPT);
        when(attachmentStorage.relativePath(1L, 1, "a.txt")).thenReturn("task-1/1-a.txt");
        when(attachmentStorage.relativePath(1L, 2, "b.txt")).thenReturn("task-1/2-b.txt");
        when(attachmentStorage.relativePath(1L, 3, "question-9-대화.md")).thenReturn("task-1/3-question-9-대화.md");

        Task t = service.create(req(9L), List.of(f1, f2), "user1", null, false);

        assertThat(t.getSourceQuestionId()).isEqualTo(9L);
        // 사용자 10개 한도 검증은 사용자 파일만 — 대화 전문은 그 밖의 1개
        verify(attachmentStorage).validate(List.of(f1, f2));
        InOrder order = inOrder(attachmentStorage);
        order.verify(attachmentStorage).write("task-1/1-a.txt", f1);
        order.verify(attachmentStorage).write("task-1/2-b.txt", f2);
        order.verify(attachmentStorage).writeText("task-1/3-question-9-대화.md", TRANSCRIPT);
        ArgumentCaptor<TaskAttachment> atts = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepo, times(3)).save(atts.capture());
        assertThat(atts.getAllValues()).extracting(TaskAttachment::getOriginalFilename)
                .containsExactly("a.txt", "b.txt", "question-9-대화.md");
    }

    @Test
    void the_requesters_admin_flag_reaches_the_question_check() {
        when(interviewService.questionTranscript(9L, "admin1", true)).thenReturn(TRANSCRIPT);
        when(attachmentStorage.relativePath(1L, 1, "question-9-대화.md")).thenReturn("task-1/1-question-9-대화.md");

        service.create(req(9L), List.of(), "admin1", null, true);

        verify(interviewService).questionTranscript(9L, "admin1", true);
        verify(attachmentStorage).writeText("task-1/1-question-9-대화.md", TRANSCRIPT);
    }

    @Test
    void the_old_overloads_check_the_question_as_a_non_admin() {
        when(interviewService.questionTranscript(9L, "user1", false)).thenReturn(TRANSCRIPT);
        when(attachmentStorage.relativePath(anyLong(), anyInt(), anyString())).thenReturn("task-1/1-question-9-대화.md");

        service.create(req(9L), "user1");
        service.create(req(9L), List.of(), "user1", "user1@hamonsoft.co.kr");

        verify(interviewService, times(2)).questionTranscript(9L, "user1", false);
    }

    @Test
    void a_forbidden_or_busy_question_saves_no_task() {
        when(interviewService.questionTranscript(9L, "user2", false)).thenThrow(TaskException.forbidden());
        when(interviewService.questionTranscript(10L, "user2", false))
                .thenThrow(TaskException.conflict("답변이 끝난 뒤에 작업으로 등록할 수 있습니다"));

        assertThatThrownBy(() -> service.create(req(9L), "user2", null, false))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.create(req(10L), List.of(file("a.txt")), "user2", null, false))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("답변이 끝난 뒤에 작업으로 등록할 수 있습니다");
                });

        verify(taskRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
        verify(attachmentRepo, never()).save(any());
        verify(attachmentStorage, never()).write(any(), any());
        verify(attachmentStorage, never()).writeText(any(), any());
    }

    @Test
    void a_failed_transcript_write_cleans_up_and_rethrows() {
        MockMultipartFile f1 = file("a.txt");
        TaskException boom = new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)");
        when(interviewService.questionTranscript(9L, "user1", false)).thenReturn(TRANSCRIPT);
        when(attachmentStorage.relativePath(1L, 1, "a.txt")).thenReturn("task-1/1-a.txt");
        when(attachmentStorage.relativePath(1L, 2, "question-9-대화.md")).thenReturn("task-1/2-question-9-대화.md");
        when(attachmentStorage.relativePath(1L, 1, "question-9-대화.md")).thenReturn("task-1/1-question-9-대화.md");
        doThrow(boom).when(attachmentStorage).writeText(anyString(), eq(TRANSCRIPT));

        // JSON 등록
        assertThatThrownBy(() -> service.create(req(9L), "user1", null, false)).isSameAs(boom);
        verify(attachmentStorage).deleteQuietly("task-1/1-question-9-대화.md");

        // multipart 등록 — 먼저 쓴 사용자 파일까지 정리한다
        assertThatThrownBy(() -> service.create(req(9L), List.of(f1), "user1", null, false)).isSameAs(boom);
        verify(attachmentStorage).deleteQuietly("task-1/1-a.txt");
        verify(attachmentStorage).deleteQuietly("task-1/2-question-9-대화.md");
        verify(attachmentStorage, times(3)).deleteQuietly(any());
    }

    @Test
    void registration_without_a_source_reads_no_question() {
        Task t = service.create(req(null), "user1", null, false);
        service.create(req(null), List.of(), "user1", null, true);

        assertThat(t.getSourceQuestionId()).isNull();
        verify(interviewService, never()).questionTranscript(any(), any(), anyBoolean());
        verify(attachmentRepo, never()).save(any());
        verify(attachmentStorage, never()).writeText(any(), any());
    }
}
