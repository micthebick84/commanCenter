package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.RequestAttachment;
import com.hamonsoft.netismaker.entity.RequestComment;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestPriority;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestAttachmentRepository;
import com.hamonsoft.netismaker.repository.RequestCommentRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 요청 게시판 개발 요청 → 작업 등록 (스펙 2026-11-19 §5.4). 실제 RequestBoardService(저장소만 목)와 임시 폴더의 실제
 * AttachmentStorage로 권한·중복 등록 거절·원 요청자·한도 건너뜀·첨부 복사·롤백 정리를 본다.
 * 트랜잭션 롤백(연결·작업 행 취소) 자체는 RequestToTaskFlowIntegrationTest가 CI에서 본다.
 */
class TaskServiceFromRequestTest {

    private static final long RID = 30L;
    private static final String REQUESTER = "req1";
    private static final String ADMIN = "admin1";

    @TempDir Path root;

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskAttachmentRepository attachmentRepo;
    private RenderTargetPolicy policy;
    private ApplicationEventPublisher events;
    private TikaExtractionService tika;
    private AttachmentStorage storage;
    private RequestPostRepository posts;
    private RequestCommentRepository comments;
    private RequestAttachmentRepository requestAttachments;
    private final List<RequestAttachment> requestAtts = new ArrayList<>();
    private RequestPost post;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        policy = mock(RenderTargetPolicy.class);
        events = mock(ApplicationEventPublisher.class);
        tika = mock(TikaExtractionService.class);
        storage = spy(new AttachmentStorage(root.toString(), 10, 20, 50));
        posts = mock(RequestPostRepository.class);
        comments = mock(RequestCommentRepository.class);
        requestAttachments = mock(RequestAttachmentRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attachmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(comments.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tika.extractText(any(), any())).thenReturn(Optional.empty());
        when(policy.initial(any())).thenAnswer(i -> ((String) i.getArgument(0)).endsWith(".pptx")
                ? AttachmentRendition.of(RenderStatus.PENDING, null)
                : AttachmentRendition.of(RenderStatus.NONE, null));
        when(requestAttachments.findByRequestIdOrderByOrdinalAsc(RID)).thenReturn(requestAtts);
        post = devPost(RequestStatus.RECEIVED);
    }

    private RequestPost devPost(RequestStatus status) {
        RequestPost p = RequestPost.createDev("결재선 개선", "내용", RequestIssueType.BUG, RequestPriority.NORMAL,
                null, null, REQUESTER);
        p.setStatus(status);
        return stub(p);
    }

    private RequestPost stub(RequestPost p) {
        ReflectionTestUtils.setField(p, "id", RID);
        when(posts.findByIdForUpdate(RID)).thenReturn(Optional.of(p));
        when(posts.findById(RID)).thenReturn(Optional.of(p));
        return p;
    }

    private TaskService service(int inheritMaxTotalMb) {
        RepoCatalogService repoCatalogService = mock(RepoCatalogService.class);
        TaskService s = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                mock(InterviewService.class), attachmentRepo, storage, mock(TaskStageUsageRepository.class),
                mock(DbConnectionService.class), tika, policy, events, mock(QuestionAttachmentRepository.class),
                inheritMaxTotalMb);
        ReflectionTestUtils.setField(s, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(s, "maxRetry", 3);
        RequestBoardService board = new RequestBoardService(posts, comments, requestAttachments,
                mock(RepoCatalogRepository.class), repoCatalogService, taskRepo, storage, 20, "");
        s.setRequestBoardService(board);
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(repoCatalogService.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github", "micthebick84/netis7.0", null));
        return s;
    }

    private TaskService service() {
        return service(200);
    }

    private static TaskCreateRequest req(Long sourceQuestionId, Long sourceRequestId) {
        return new TaskCreateRequest(7L, "main", "결재선 개선", "설명", null, sourceQuestionId, null, sourceRequestId);
    }

    private static TaskCreateRequest req() {
        return req(null, RID);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", "사용자".getBytes(StandardCharsets.UTF_8));
    }

    /** 요청 첨부 하나 — writeOriginal이면 원본을 디스크에 쓰고, 행을 목 저장소 목록에 넣는다. */
    private RequestAttachment requestAtt(int ordinal, String name, long sizeBytes, boolean writeOriginal)
            throws Exception {
        String rel = "request-" + RID + "/" + ordinal + "-" + name;
        if (writeOriginal) {
            Files.createDirectories(root.resolve(rel).getParent());
            Files.writeString(root.resolve(rel), "원본:" + name);
        }
        RequestAttachment a = RequestAttachment.create(RID, ordinal, name, rel, "application/octet-stream", sizeBytes);
        ReflectionTestUtils.setField(a, "id", 200L + ordinal);
        requestAtts.add(a);
        return a;
    }

    private Task savedTask() {
        ArgumentCaptor<Task> cap = ArgumentCaptor.forClass(Task.class);
        verify(taskRepo).save(cap.capture());
        return cap.getValue();
    }

    private List<TaskAttachment> savedAttachments(int expected) {
        ArgumentCaptor<TaskAttachment> cap = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepo, times(expected)).save(cap.capture());
        return cap.getAllValues();
    }

    private List<String> historyReasons() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream().map(TaskStatusHistory::getReason).toList();
    }

    private static HttpStatus statusOf(Throwable t) {
        return ((TaskException) t).getStatus();
    }

    private void assertNothingRegistered() {
        verify(taskRepo, never()).save(any());
        verify(comments, never()).save(any());
        assertThat(post.getTaskId()).isNull();
    }

    @Test
    void 비관리자는_403() {
        assertThatThrownBy(() -> service().create(req(), "user1", "u@x", false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessage("요청을 작업으로 등록할 권한이 없습니다");
        assertNothingRegistered();
        assertThat(post.getStatus()).isEqualTo(RequestStatus.RECEIVED);
    }

    @Test
    void 없는_요청은_404() {
        when(posts.findByIdForUpdate(RID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(req(), ADMIN, null, true))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(taskRepo, never()).save(any());
    }

    @Test
    void 등록자는_원_요청자() {
        Task t = service().create(req(), ADMIN, "admin@x", true);

        Task saved = savedTask();
        assertThat(t).isSameAs(saved);
        assertThat(saved.getRequesterId()).isEqualTo(REQUESTER);
        assertThat(saved.getRequesterEmail()).isNull();   // 종료 메일은 com."user".email로 대체된다
        assertThat(saved.getSourceRequestId()).isEqualTo(RID);
        assertThat(saved.getSourceQuestionId()).isNull();
        verify(posts, atLeastOnce()).findByIdForUpdate(RID);   // 행 락
        // 요청은 연결·처리완료, 시스템 답글
        assertThat(post.getTaskId()).isEqualTo(1L);
        assertThat(post.getStatus()).isEqualTo(RequestStatus.DONE);
        ArgumentCaptor<RequestComment> cap = ArgumentCaptor.forClass(RequestComment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo(RequestCommentKind.SYSTEM);
        assertThat(cap.getValue().getBody()).isEqualTo("관리자(admin1)가 작업 #1으로 등록 — 처리완료");
        assertThat(historyReasons()).containsExactly("요청 #30에서 등록(관리자 admin1)");
    }

    @Test
    void 요청자가_한도_5건을_넘겨도_등록된다() {
        when(taskRepo.countActiveByRequester(anyString())).thenReturn(9L);

        service().create(req(), ADMIN, null, true);

        assertThat(savedTask().getRequesterId()).isEqualTo(REQUESTER);
        verify(taskRepo, never()).countActiveByRequester(anyString());
    }

    @Test
    void 출처_요청_없으면_한도_검사는_그대로() {
        when(taskRepo.countActiveByRequester("user1")).thenReturn(5L);

        assertThatThrownBy(() -> service().create(req(null, null), "user1", null, false))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void 이미_연결된_요청은_409() {
        post.setStatus(RequestStatus.DONE);
        post.setTaskId(5L);

        assertThatThrownBy(() -> service().create(req(), ADMIN, null, true))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("이미 작업 #5으로 등록된 요청입니다");
        verify(taskRepo, never()).save(any());
        verify(comments, never()).save(any());
        assertThat(post.getTaskId()).isEqualTo(5L);
    }

    @Test
    void REPO_요청은_409() {
        post = stub(RequestPost.create(RequestKind.REPO, "레포", "내용", "힌트", REQUESTER));

        assertThatThrownBy(() -> service().create(req(), ADMIN, null, true))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("개발 요청이 아닙니다");
        assertNothingRegistered();
    }

    @Test
    void 처리완료_요청은_409() {
        post.setStatus(RequestStatus.DONE);

        assertThatThrownBy(() -> service().create(req(), ADMIN, null, true))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("이미 처리된 요청입니다");
        assertNothingRegistered();
    }

    @Test
    void 반려_요청도_409() {
        post.setStatus(RequestStatus.REJECTED);

        assertThatThrownBy(() -> service().create(req(), ADMIN, null, true))
                .hasMessage("이미 처리된 요청입니다");
        assertNothingRegistered();
    }

    @Test
    void sourceQuestionId와_함께면_400() {
        assertThatThrownBy(() -> service().create(req(9L, RID), ADMIN, null, true))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("출처는 하나만 지정할 수 있습니다");
        assertNothingRegistered();
        verify(posts, never()).findByIdForUpdate(any());
    }

    @Test
    void 요청_첨부가_작업_첨부로_복사된다() throws Exception {
        requestAtt(1, "화면.png", 10, true);
        requestAtt(2, "덱.pptx", 20, true);
        when(tika.extractText(any(), eq("덱.pptx"))).thenReturn(Optional.of("슬라이드 글"));

        service().create(req(), List.of(file("a.txt")), ADMIN, null, true);

        List<TaskAttachment> atts = savedAttachments(3);
        assertThat(atts).extracting(TaskAttachment::getStoredPath).containsExactly(
                "task-1/1-a.txt", "task-1/2-화면.png", "task-1/3-덱.pptx");
        assertThat(atts.get(0).getUploadedBy()).isEqualTo(ADMIN);   // 관리자가 직접 올린 파일
        TaskAttachment png = atts.get(1);
        assertThat(png.getOriginalFilename()).isEqualTo("화면.png");
        assertThat(png.getContentType()).isEqualTo("application/octet-stream");
        assertThat(png.getSizeBytes()).isEqualTo(10);
        assertThat(png.getUploadedBy()).isEqualTo(REQUESTER);
        assertThat(png.getExtractedTextPath()).isNull();
        assertThat(png.getRendition().getStatus()).isEqualTo(RenderStatus.NONE);
        assertThat(png.getSourceQuestionAttachmentId()).isNull();
        TaskAttachment deck = atts.get(2);
        assertThat(deck.getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(deck.getExtractedTextPath()).isEqualTo("task-1/3-덱.pptx.txt");
        assertThat(Files.readString(root.resolve("task-1/2-화면.png"))).isEqualTo("원본:화면.png");
        assertThat(Files.readString(root.resolve("task-1/3-덱.pptx"))).isEqualTo("원본:덱.pptx");
        assertThat(Files.readString(root.resolve("task-1/3-덱.pptx.txt"))).isEqualTo("슬라이드 글");
        // 요청 쪽 원본은 그대로 남는다(요청 상세에서 계속 받는다)
        assertThat(root.resolve("request-30/1-화면.png")).exists();
        assertThat(historyReasons()).containsExactly("요청 #30에서 등록(관리자 admin1)", "요청 첨부 승계: 2개");
        verify(events, times(1)).publishEvent(any(AttachmentRenderRequested.class));   // 등록당 한 번
    }

    @Test
    void JSON_등록이면_요청_첨부가_순번_1부터() throws Exception {
        requestAtt(1, "화면.png", 10, true);

        service().create(req(), ADMIN, null, true);

        assertThat(savedAttachments(1).get(0).getStoredPath()).isEqualTo("task-1/1-화면.png");
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void 디스크에_없는_요청_첨부는_건너뛴다() throws Exception {
        requestAtt(1, "사라짐.png", 10, false);
        requestAtt(2, "있음.png", 10, true);

        service().create(req(), ADMIN, null, true);

        assertThat(savedAttachments(1).get(0).getStoredPath()).isEqualTo("task-1/1-있음.png");
        assertThat(historyReasons()).containsExactly("요청 #30에서 등록(관리자 admin1)",
                "요청 첨부 승계: 1개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 용량_상한_초과분_제외() throws Exception {
        long mb08 = 800L * 1024;
        requestAtt(1, "a.png", mb08, true);
        requestAtt(2, "b.png", mb08, true);

        service(1).create(req(), ADMIN, null, true);

        assertThat(savedAttachments(1).get(0).getOriginalFilename()).isEqualTo("a.png");
        assertThat(root.resolve("task-1/2-b.png")).doesNotExist();
        assertThat(historyReasons()).containsExactly("요청 #30에서 등록(관리자 admin1)",
                "요청 첨부 승계: 1개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 요청_첨부가_없으면_승계_이력도_없다() {
        service().create(req(), ADMIN, null, true);

        verify(attachmentRepo, never()).save(any());
        assertThat(historyReasons()).containsExactly("요청 #30에서 등록(관리자 admin1)");
    }

    @Test
    void 복사_실패면_파일을_지우고_예외() throws Exception {
        requestAtt(1, "a.png", 10, true);
        requestAtt(2, "b.png", 10, true);
        TaskException boom = new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)");
        doThrow(boom).when(storage).copyTree("request-30/2-b.png", "task-1/3-b.png");

        assertThatThrownBy(() -> service().create(req(), List.of(file("u.txt")), ADMIN, null, true)).isSameAs(boom);

        // 사용자 파일·먼저 옮긴 첨부가 지워지고, 실패한 대상도 정리 목록에 먼저 들어갔다
        assertThat(root.resolve("task-1/1-u.txt")).doesNotExist();
        assertThat(root.resolve("task-1/2-a.png")).doesNotExist();
        verify(storage).deleteQuietly("task-1/3-b.png");
        // 요청 쪽 원본은 그대로
        assertThat(root.resolve("request-30/1-a.png")).exists();
        assertThat(root.resolve("request-30/2-b.png")).exists();
        // linkTask는 불렸지만(연결은 같은 tx) 롤백으로 취소된다 — 통합 시험이 DB에서 확인한다
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }
}
