package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.render.AttachmentRenderRequested;
import com.hamonsoft.netismaker.service.render.RenderTargetPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 작업 첨부 업로드의 Tika sidecar·렌더링 대상 표시·깨우기 이벤트 (스펙 2026-10-27 §4·§5.1).
 * 통합 테스트(TaskServiceAttachmentTest)는 Docker가 필요해 같은 동작을 목으로 따로 지킨다.
 */
class TaskServiceRenderTest {

    private TaskAttachmentRepository attachmentRepo;
    private AttachmentStorage attachmentStorage;
    private TikaExtractionService tika;
    private RenderTargetPolicy policy;
    private ApplicationEventPublisher events;
    private TaskService service;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        RepoCatalogService repoCatalogService = mock(RepoCatalogService.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        attachmentStorage = mock(AttachmentStorage.class);
        tika = mock(TikaExtractionService.class);
        policy = mock(RenderTargetPolicy.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                mock(InterviewService.class), attachmentRepo, attachmentStorage,
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class), tika, policy, events,
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
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
        when(attachmentStorage.relativePath(anyLong(), anyInt(), any())).thenAnswer(i ->
                "task-" + i.getArgument(0) + "/" + i.getArgument(1) + "-" + i.getArgument(2));
        when(attachmentStorage.extractedTextRelativePath(any())).thenAnswer(i -> i.getArgument(0) + ".txt");
        when(attachmentStorage.resolve(any())).thenAnswer(i -> Path.of("/abs", (String) i.getArgument(0)));
        when(tika.extractText(any(), any())).thenReturn(Optional.empty());
        when(policy.initial(any())).thenReturn(AttachmentRendition.of(RenderStatus.NONE, null));
    }

    private static TaskCreateRequest req() {
        return new TaskCreateRequest(7L, "main", "제목", "설명");
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pptx_첨부는_sidecar와_PENDING이고_이벤트는_한_번() {
        when(policy.initial("덱.pptx")).thenReturn(AttachmentRendition.of(RenderStatus.PENDING, null));
        when(policy.initial("덱2.pptx")).thenReturn(AttachmentRendition.of(RenderStatus.PENDING, null));
        when(tika.extractText(Path.of("/abs/task-1/1-덱.pptx"), "덱.pptx")).thenReturn(Optional.of("슬라이드 본문"));

        service.create(req(), List.of(file("덱.pptx"), file("덱2.pptx"), file("a.txt")), "user1");

        verify(attachmentStorage).writeText("task-1/1-덱.pptx.txt", "슬라이드 본문");
        ArgumentCaptor<TaskAttachment> cap = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepo, times(3)).save(cap.capture());
        TaskAttachment first = cap.getAllValues().get(0);
        assertThat(first.getExtractedTextPath()).isEqualTo("task-1/1-덱.pptx.txt");
        assertThat(first.getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(cap.getAllValues().get(1).getExtractedTextPath()).isNull();   // Tika가 못 뽑으면 sidecar 없이 원본만
        assertThat(cap.getAllValues().get(1).getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(cap.getAllValues().get(2).getRendition().getStatus()).isEqualTo(RenderStatus.NONE);
        verify(events, times(1)).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void PENDING이_없으면_이벤트를_내지_않는다() {
        service.create(req(), List.of(file("a.txt")), "user1");
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void 쓰기가_실패하면_sidecar까지_정리하고_이벤트는_내지_않는다() {
        when(policy.initial("덱.pptx")).thenReturn(AttachmentRendition.of(RenderStatus.PENDING, null));
        when(tika.extractText(any(), any())).thenReturn(Optional.of("본문"));
        doThrow(new TaskException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "boom"))
                .when(attachmentStorage).writeText(any(), any());

        assertThatThrownBy(() -> service.create(req(), List.of(file("덱.pptx")), "user1"))
                .isInstanceOf(TaskException.class);

        verify(attachmentStorage).deleteQuietly("task-1/1-덱.pptx");
        verify(attachmentStorage).deleteQuietly("task-1/1-덱.pptx.txt");
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }
}
