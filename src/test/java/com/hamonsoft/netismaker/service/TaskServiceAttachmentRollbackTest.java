package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * create(req, files, requesterId)의 실패-시-정리(cleanup) 경로 전용 단위 테스트.
 *
 * TaskServiceAttachmentTest(통합, Testcontainers)의 3개 실패 케이스는 전부 validate()
 * 단계에서 막혀 파일이 하나도 안 쓰인 채 실패하므로, "이미 write까지 성공한 뒤 실패"하는
 * 케이스(= written.forEach(deleteQuietly)가 실제로 도는 유일한 경로)는 아직 어떤 테스트도
 * 실행하지 않는다. 여기서는 AttachmentStorage를 mock해 두 번째 파일의 write에서만 결정적으로
 * 실패시켜, 첫 번째 파일까지 포함해 정확히 그 두 경로 모두가 정리되는지 확인한다
 * (written.add(rel)이 write(rel, f)보다 먼저 실행되므로 실패한 두 번째 경로도 write 목록에
 * 이미 들어가 있다 — 부분 파일이 남을 수 있어 반드시 정리 대상).
 */
class TaskServiceAttachmentRollbackTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskDesignRepository designRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private RepoCatalogService repoCatalogService;
    private InterviewService interviewService;
    private TaskAttachmentRepository attachmentRepo;
    private AttachmentStorage attachmentStorage;
    private TaskStageUsageRepository stageUsageRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        designRepo = mock(TaskDesignRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        repoCatalogService = mock(RepoCatalogService.class);
        interviewService = mock(InterviewService.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        attachmentStorage = mock(AttachmentStorage.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        service = new TaskService(taskRepo, analysisRepo, designRepo, historyRepo, mcpCatalogService,
                repoCatalogService, new ObjectMapper(), interviewService, attachmentRepo, attachmentStorage,
                stageUsageRepo);
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);

        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
        when(repoCatalogService.resolveForRegistration(1L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(1L, "alias",
                        "https://github.com/x/y.git", "github", "x/y", null));
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attachmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes());
    }

    @Test
    void 두번째_파일_쓰기가_실패하면_이미_쓴_첫번째_파일과_실패한_두번째_경로_모두_정리되고_예외가_전파된다() {
        MockMultipartFile f1 = file("a.txt", "A");
        MockMultipartFile f2 = file("b.txt", "B");
        when(attachmentStorage.relativePath(1L, 1, "a.txt")).thenReturn("task-1/1-a.txt");
        when(attachmentStorage.relativePath(1L, 2, "b.txt")).thenReturn("task-1/2-b.txt");
        doThrow(new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)"))
                .when(attachmentStorage).write(eq("task-1/2-b.txt"), eq(f2));

        TaskCreateRequest req = new TaskCreateRequest(1L, "main", "제목", "설명");

        assertThatThrownBy(() -> service.create(req, List.of(f1, f2), "user1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("디스크 오류");

        // 첫 파일은 정상적으로 write까지 갔다가, 두 번째 파일 write 실패로 인해 둘 다 정리 대상이 된다.
        verify(attachmentStorage).write(eq("task-1/1-a.txt"), eq(f1));
        verify(attachmentStorage).deleteQuietly("task-1/1-a.txt");
        verify(attachmentStorage).deleteQuietly("task-1/2-b.txt");
        verify(attachmentStorage, times(2)).deleteQuietly(any());
    }
}
