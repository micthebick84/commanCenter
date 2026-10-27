package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
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
import static org.mockito.Mockito.*;

/**
 * 질문 → 작업 첨부 승계 (스펙 2026-10-27 §7.5) — 실제 AttachmentStorage를 임시 폴더에 두고 복사·건너뜀·상한·
 * 렌더링 상태 승계·롤백 정리를 본다. DB는 목(트랜잭션 롤백 자체는 TaskServiceInheritAttachmentsTest가 CI에서 본다).
 */
class TaskServiceInheritAttachmentsUnitTest {

    private static final String TRANSCRIPT = "# 전문";
    private static final long QID = 9L;

    @TempDir Path root;

    private TaskStatusHistoryRepository historyRepo;
    private TaskAttachmentRepository attachmentRepo;
    private QuestionAttachmentRepository questionAttachmentRepo;
    private InterviewService interviewService;
    private RenderTargetPolicy policy;
    private ApplicationEventPublisher events;
    private AttachmentStorage storage;
    private final List<QuestionAttachment> questionAtts = new ArrayList<>();
    private long nextQaId = 100;

    @BeforeEach
    void setUp() {
        historyRepo = mock(TaskStatusHistoryRepository.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        questionAttachmentRepo = mock(QuestionAttachmentRepository.class);
        interviewService = mock(InterviewService.class);
        policy = mock(RenderTargetPolicy.class);
        events = mock(ApplicationEventPublisher.class);
        storage = spy(new AttachmentStorage(root.toString(), 10, 20, 50));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attachmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(interviewService.questionTranscript(QID, "user1", false)).thenReturn(TRANSCRIPT);
        when(questionAttachmentRepo.findBySessionIdOrderByIdAsc(QID)).thenReturn(questionAtts);
        when(policy.initial(any())).thenAnswer(i -> ((String) i.getArgument(0)).endsWith(".pptx")
                ? AttachmentRendition.of(RenderStatus.PENDING, null)
                : AttachmentRendition.of(RenderStatus.NONE, null));
    }

    private TaskService service(int inheritMaxTotalMb) {
        TaskRepository taskRepo = mock(TaskRepository.class);
        RepoCatalogService repoCatalogService = mock(RepoCatalogService.class);
        TikaExtractionService tika = mock(TikaExtractionService.class);
        when(tika.extractText(any(), any())).thenReturn(Optional.empty());
        TaskService s = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                interviewService, attachmentRepo, storage, mock(TaskStageUsageRepository.class),
                mock(DbConnectionService.class), tika, policy, events, questionAttachmentRepo, inheritMaxTotalMb);
        ReflectionTestUtils.setField(s, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(s, "maxRetry", 3);
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

    private static TaskCreateRequest req(Long sourceQuestionId) {
        return new TaskCreateRequest(7L, "main", "제목", "설명", null, sourceQuestionId);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", "사용자".getBytes(StandardCharsets.UTF_8));
    }

    /** 질문 첨부 하나 — 원본을 디스크에 쓰고(writeOriginal), 행을 목 저장소 목록에 넣는다. */
    private QuestionAttachment questionAtt(Integer turnSeq, String name, long sizeBytes, boolean writeOriginal,
                                           boolean withSidecar, AttachmentRendition rendition) throws Exception {
        String rel = "question-" + QID + "/" + (turnSeq == null ? "create" : turnSeq) + "/1-" + name;
        if (writeOriginal) {
            Files.createDirectories(root.resolve(rel).getParent());
            Files.writeString(root.resolve(rel), "원본:" + name);
        }
        String sidecar = null;
        if (withSidecar) {
            sidecar = rel + ".txt";
            Files.createDirectories(root.resolve(sidecar).getParent());
            Files.writeString(root.resolve(sidecar), "텍스트:" + name);
        }
        QuestionAttachment a = QuestionAttachment.create(QID, turnSeq, name, rel,
                "application/octet-stream", sizeBytes, sidecar, "asker", rendition);
        ReflectionTestUtils.setField(a, "id", nextQaId++);
        questionAtts.add(a);
        return a;
    }

    private static AttachmentRendition done(String dir) {
        AttachmentRendition r = AttachmentRendition.of(RenderStatus.PENDING, null);
        r.markDone(dir, 2, 3, 1);
        return r;
    }

    private void writeRender(String renderRel) throws Exception {
        Path dir = Files.createDirectories(root.resolve(renderRel).resolve("media"));
        Files.write(root.resolve(renderRel).resolve("slide-001.png"), new byte[]{1});
        Files.write(dir.resolve("img.png"), new byte[]{2});
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

    @Test
    void 등록_턴_첨부_순번_N플러스2부터() throws Exception {
        questionAtt(null, "덱.pptx", 10, true, true, AttachmentRendition.of(RenderStatus.NONE, null));
        questionAtt(2, "표.csv", 10, true, false, AttachmentRendition.of(RenderStatus.NONE, null));

        service(200).create(req(QID), List.of(file("a.txt")), "user1", null, false);

        List<TaskAttachment> atts = savedAttachments(4);
        assertThat(atts).extracting(TaskAttachment::getStoredPath).containsExactly(
                "task-1/1-a.txt", "task-1/2-question-9-대화.md", "task-1/3-덱.pptx", "task-1/4-표.csv");
        assertThat(Files.readString(root.resolve("task-1/3-덱.pptx"))).isEqualTo("원본:덱.pptx");
        assertThat(Files.readString(root.resolve("task-1/3-덱.pptx.txt"))).isEqualTo("텍스트:덱.pptx");
        assertThat(Files.readString(root.resolve("task-1/4-표.csv"))).isEqualTo("원본:표.csv");
        TaskAttachment deck = atts.get(2);
        assertThat(deck.getExtractedTextPath()).isEqualTo("task-1/3-덱.pptx.txt");
        assertThat(deck.getOriginalFilename()).isEqualTo("덱.pptx");
        assertThat(deck.getContentType()).isEqualTo("application/octet-stream");
        assertThat(deck.getSizeBytes()).isEqualTo(10);
        assertThat(deck.getUploadedBy()).isEqualTo("asker");
        assertThat(deck.getSourceQuestionAttachmentId()).isEqualTo(100L);
        assertThat(atts.get(3).getExtractedTextPath()).isNull();
        assertThat(atts.get(3).getSourceQuestionAttachmentId()).isEqualTo(101L);
        assertThat(historyReasons()).containsExactly("작업 등록", "질문 #9 첨부 승계: 2개");
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void DONE_렌더링_승계() throws Exception {
        String src = "question-9/create/1-덱.pptx.render";
        writeRender(src);
        questionAtt(null, "덱.pptx", 10, true, false, done(src));

        service(200).create(req(QID), "user1", null, false);

        TaskAttachment deck = savedAttachments(2).get(1);
        assertThat(deck.getStoredPath()).isEqualTo("task-1/2-덱.pptx");
        assertThat(deck.getRendition().getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(deck.getRendition().getDir()).isEqualTo("task-1/2-덱.pptx.render");
        assertThat(deck.getRendition().getPageCount()).isEqualTo(2);
        assertThat(deck.getRendition().getTotalPages()).isEqualTo(3);
        assertThat(deck.getRendition().getMediaCount()).isEqualTo(1);
        assertThat(deck.getRendition().getAttempts()).isZero();
        assertThat(root.resolve("task-1/2-덱.pptx.render/slide-001.png")).exists();
        assertThat(root.resolve("task-1/2-덱.pptx.render/media/img.png")).exists();
        verify(policy, never()).initial(any());
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void DONE인데_폴더가_없으면_다시_PENDING() throws Exception {
        questionAtt(null, "덱.pptx", 10, true, false, done("question-9/create/1-덱.pptx.render"));

        service(200).create(req(QID), "user1", null, false);

        TaskAttachment deck = savedAttachments(2).get(1);
        assertThat(deck.getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(deck.getRendition().getDir()).isNull();
        assertThat(root.resolve("task-1/2-덱.pptx.render")).doesNotExist();
        verify(events, times(1)).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void 질문쪽_PENDING이면_작업쪽_PENDING() throws Exception {
        questionAtt(null, "덱.pptx", 10, true, false, AttachmentRendition.of(RenderStatus.PENDING, null));
        AttachmentRendition running = AttachmentRendition.of(RenderStatus.PENDING, null);
        running.markRunning(java.time.OffsetDateTime.now());
        questionAtt(2, "덱2.pptx", 10, true, false, running);

        service(200).create(req(QID), List.of(file("a.txt")), "user1", null, false);

        List<TaskAttachment> atts = savedAttachments(4);
        assertThat(atts.get(2).getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(atts.get(3).getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(atts.get(3).getRendition().getAttempts()).isZero();
        assertThat(atts.get(3).getRendition().getStartedAt()).isNull();
        verify(events, times(1)).publishEvent(any(AttachmentRenderRequested.class));   // 등록당 한 번
    }

    @Test
    void 렌더링이_꺼져_있으면_PENDING_대신_정책값() throws Exception {
        doReturn(AttachmentRendition.of(RenderStatus.SKIPPED, "렌더링 꺼짐")).when(policy).initial(any());
        questionAtt(null, "덱.pptx", 10, true, false, AttachmentRendition.of(RenderStatus.PENDING, null));

        service(200).create(req(QID), "user1", null, false);

        assertThat(savedAttachments(2).get(1).getRendition().getStatus()).isEqualTo(RenderStatus.SKIPPED);
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void FAILED_SKIPPED_NONE은_그대로_옮긴다() throws Exception {
        AttachmentRendition failed = AttachmentRendition.of(RenderStatus.PENDING, null);
        failed.markFailed("soffice 시간 초과");
        questionAtt(null, "덱.pptx", 10, true, false, failed);
        questionAtt(null, "덱2.pptx", 10, true, false, AttachmentRendition.of(RenderStatus.SKIPPED, "렌더링 꺼짐"));
        questionAtt(null, "a.txt", 10, true, false, AttachmentRendition.of(RenderStatus.NONE, null));

        service(200).create(req(QID), "user1", null, false);

        List<TaskAttachment> atts = savedAttachments(4);
        assertThat(atts.get(1).getRendition().getStatus()).isEqualTo(RenderStatus.FAILED);
        assertThat(atts.get(1).getRendition().getError()).isEqualTo("soffice 시간 초과");
        assertThat(atts.get(2).getRendition().getStatus()).isEqualTo(RenderStatus.SKIPPED);
        assertThat(atts.get(2).getRendition().getError()).isEqualTo("렌더링 꺼짐");
        assertThat(atts.get(3).getRendition().getStatus()).isEqualTo(RenderStatus.NONE);
        verify(policy, never()).initial(any());
    }

    @Test
    void 정리된_질문_첨부는_건너뜀() throws Exception {
        questionAtt(null, "덱.pptx", 10, false, false, AttachmentRendition.of(RenderStatus.NONE, null));

        Task t = service(200).create(req(QID), "user1", null, false);

        assertThat(t.getId()).isEqualTo(1L);
        assertThat(savedAttachments(1).get(0).getOriginalFilename()).isEqualTo("question-9-대화.md");
        assertThat(historyReasons()).containsExactly("작업 등록", "질문 #9 첨부 승계: 0개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 용량_상한_초과분_제외() throws Exception {
        long mb08 = 800L * 1024;   // 0.8MB(KiB 기준) — 두 개면 1MB 상한을 넘는다
        questionAtt(null, "a.pptx", mb08, true, false, AttachmentRendition.of(RenderStatus.NONE, null));
        questionAtt(null, "b.pptx", mb08, true, false, AttachmentRendition.of(RenderStatus.NONE, null));

        service(1).create(req(QID), "user1", null, false);

        List<TaskAttachment> atts = savedAttachments(2);
        assertThat(atts.get(1).getOriginalFilename()).isEqualTo("a.pptx");
        assertThat(root.resolve("task-1/3-b.pptx")).doesNotExist();
        assertThat(historyReasons()).containsExactly("작업 등록", "질문 #9 첨부 승계: 1개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 복사_실패시_등록_롤백() throws Exception {
        String src = "question-9/create/1-a.pptx.render";
        writeRender(src);
        questionAtt(null, "a.pptx", 10, true, true, done(src));
        questionAtt(2, "b.pptx", 10, true, false, AttachmentRendition.of(RenderStatus.NONE, null));
        TaskException boom = new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)");
        doThrow(boom).when(storage).copyTree("question-9/2/1-b.pptx", "task-1/3-b.pptx");

        assertThatThrownBy(() -> service(200).create(req(QID), "user1", null, false)).isSameAs(boom);

        // 먼저 옮긴 첫 첨부(원본·sidecar·.render 폴더)와 대화 전문까지 지워진다 — 반쯤 승계된 상태를 남기지 않는다
        assertThat(root.resolve("task-1/1-question-9-대화.md")).doesNotExist();
        assertThat(root.resolve("task-1/2-a.pptx")).doesNotExist();
        assertThat(root.resolve("task-1/2-a.pptx.txt")).doesNotExist();
        assertThat(root.resolve("task-1/2-a.pptx.render")).doesNotExist();
        verify(storage).deleteQuietly("task-1/3-b.pptx");   // 실패한 복사 대상도 정리 목록에 먼저 들어갔다
        // 질문 쪽 원본은 그대로
        assertThat(root.resolve("question-9/create/1-a.pptx")).exists();
        assertThat(root.resolve(src).resolve("slide-001.png")).exists();
        verify(events, never()).publishEvent(any(AttachmentRenderRequested.class));
    }

    @Test
    void 출처_질문_없으면_그대로() {
        service(200).create(req(null), List.of(file("a.txt")), "user1", null, false);

        verify(questionAttachmentRepo, never()).findBySessionIdOrderByIdAsc(any());
        assertThat(savedAttachments(1).get(0).getStoredPath()).isEqualTo("task-1/1-a.txt");
        assertThat(historyReasons()).containsExactly("작업 등록");
    }
}
