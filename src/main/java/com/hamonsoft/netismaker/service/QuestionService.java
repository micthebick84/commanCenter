package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 질문 세션(Q&A) — 스펙 docs/superpowers/specs/2026-08-30-question-sessions-design.md.
 * 상태 전이는 전부 InterviewService에 위임하고, 여기서는 (1) kind=QUESTION 교차 가드(404),
 * (2) 등록 시 남용 가드(429)와 모델/MCP 스냅샷, (3) 문답 턴 상한(400), (4) 대화 중 모델·effort 변경,
 * (5) 대화 중 MCP 변경 + 메시지 단위 첨부(스펙 2026-09-13)만 담당한다.
 * 등록 즉시 QUEUED — 승인 게이트 없음. taskId는 항상 null(만료/취소의 Task 부수효과는 mirrorTask가 no-op).
 */
@Service
@Profile("api")
public class QuestionService {

    /** 자동 생성 제목 최대 길이(초과 시 절단 + '…'). 스펙 2026-09-05 §2. */
    static final int DERIVED_TITLE_MAX = 60;

    private final InterviewService interviewService;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final RepoCatalogService repoCatalogService;
    private final McpCatalogService mcpCatalogService;
    private final AttachmentStorage attachmentStorage;
    private final TikaExtractionService tika;
    private final QuestionAttachmentRepository attachmentRepo;
    private final int maxActivePerUser;
    private final int maxQaTurns;

    public QuestionService(InterviewService interviewService,
                           InterviewSessionRepository sessionRepo,
                           InterviewTurnRepository turnRepo,
                           RepoCatalogService repoCatalogService,
                           McpCatalogService mcpCatalogService,
                           AttachmentStorage attachmentStorage,
                           TikaExtractionService tika,
                           QuestionAttachmentRepository attachmentRepo,
                           @Value("${app.question.max-active-per-user:3}") int maxActivePerUser,
                           @Value("${app.question.max-qa-turns:10}") int maxQaTurns) {
        this.interviewService = interviewService;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.repoCatalogService = repoCatalogService;
        this.mcpCatalogService = mcpCatalogService;
        this.attachmentStorage = attachmentStorage;
        this.tika = tika;
        this.attachmentRepo = attachmentRepo;
        this.maxActivePerUser = maxActivePerUser;
        this.maxQaTurns = maxQaTurns;
    }

    /** ask 결과 (스펙 2026-09-13 §5.2). mcpNote = MCP가 실제로 바뀌었을 때의 system note 턴, 아니면 null. */
    public record AskResult(InterviewSession session, InterviewTurn mcpNote) {}

    @Transactional
    public InterviewSession create(QuestionCreateRequest req, String requesterId) {
        long active = sessionRepo.countActiveQuestionsByRequester(requesterId);
        if (active >= maxActivePerUser) {
            throw TaskException.tooManyRequests(
                    "동시에 진행할 수 있는 질문 세션 한도(" + maxActivePerUser + ")를 초과했습니다");
        }
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        List<TaskMcpSpec> extras = mcpCatalogService.resolveExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);   // 검증이 먼저 — 실패 시 세션이 생기면 안 된다

        InterviewSession s = InterviewSession.createQuestion(repo.ownerRepo(), req.githubBranch(),
                deriveTitle(req.title(), req.question()), req.question(), requesterId, extras, model, effort);
        s.setGitUrl(repo.gitUrl());
        s.setRepoAlias(repo.alias());
        s.setRepoCatalogId(repo.catalogId());
        // 선택 id 스냅샷 — 프론트 픽커 시딩 + applyMcpChange 집합 비교 (스펙 2026-09-13 §4)
        s.setMcpCatalogIds(req.mcpCatalogIds() == null ? new ArrayList<>() : new ArrayList<>(req.mcpCatalogIds()));
        return sessionRepo.save(s);
    }

    /**
     * 파일 첨부 등록 (스펙 2026-09-13 §5.2). 검증 → 기존 create(같은 tx) → 등록분 첨부(turn_seq null) 쓰기.
     * 파일 쓰기는 마지막 — 모델/MCP 검증 400이 고아 파일을 남기지 않는다. 실패 시 tx 롤백 + 쓴 파일 정리.
     */
    @Transactional
    public InterviewSession create(QuestionCreateRequest req, List<MultipartFile> files, String requesterId) {
        List<MultipartFile> attached = files == null ? List.of() : files;
        attachmentStorage.validate(attached);
        InterviewSession s = create(req, requesterId);   // 내부 호출 — 이미 @Transactional 안이라 같은 tx
        writeAttachments(s.getId(), null, attached, requesterId);
        return s;
    }

    /** 본인 목록. 관리자는 all=true일 때만 전체 (스펙 §5). */
    @Transactional(readOnly = true)
    public List<QuestionSummaryResponse> list(String viewerId, boolean isAdmin, boolean all) {
        List<InterviewSession> rows = (isAdmin && all)
                ? sessionRepo.findByKindOrderByCreatedAtDesc(InterviewKind.QUESTION)
                : sessionRepo.findByKindAndRequesterIdOrderByCreatedAtDesc(InterviewKind.QUESTION, viewerId);
        return rows.stream().map(QuestionSummaryResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public InterviewResponse get(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        return interviewService.getResponse(id, viewerId, isAdmin);
    }

    /** SSE 구독·첨부 다운로드 전 ACL 검증 (kind 404 → 소유자/관리자 403). */
    @Transactional(readOnly = true)
    public void requireViewable(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        interviewService.getForView(id, viewerId, isAdmin);
    }

    /** 첨부 없는 추가 질문 — 기존 호출처 호환(파일·MCP 변경 결과가 필요 없을 때). */
    @Transactional
    public InterviewSession ask(Long id, String actorId, boolean isAdmin, QuestionAskRequest req) {
        return ask(id, actorId, isAdmin, req, null).session();
    }

    /**
     * 추가 질문 = submitAnswer 재사용(재큐). 문답 상한(assistant 답변 수)은 여기서 400으로 우아하게 막는다 —
     * 러너의 maxTurns 가드(FAILED)는 방어선으로만 남긴다 (불변식: maxQaTurns < INTERVIEW_MAX_TURNS).
     *
     * model/effort가 오면(대화 중 변경, 스펙 2026-09-05 §2 개정) ACL·상태 가드를 통과한 세션에 검증 후 반영한다.
     * 같은 트랜잭션이므로 조합이 틀리면(400) 답변 턴·재큐까지 함께 롤백된다. 인터뷰 서비스는 claim마다
     * claim.model/effort로 SDK 옵션을 조립하므로(캐시 없음) 다음 턴부터 새 값으로 답한다 — CLI는 --resume 세션에서도
     * --model/--effort를 그대로 적용한다(2026-09-06 실측: sonnet 세션 resume + haiku → modelUsage=haiku).
     *
     * 순서(스펙 2026-09-13 §5.2): kind 가드 → 문답 상한 → validate(files) → submitAnswer → applyModelChange
     * → applyMcpChange → (새 턴이 생겼을 때만) writeAttachments. 파일 쓰기가 <b>마지막</b>이라 400 경로는
     * 고아 파일을 남기지 않고, 중복 제출(turnSeq null)이면 첨부는 텍스트와 마찬가지로 조용히 버려진다.
     */
    @Transactional
    public AskResult ask(Long id, String actorId, boolean isAdmin, QuestionAskRequest req,
                         List<MultipartFile> files) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        long answered = turnRepo.countBySessionIdAndRole(id, "assistant");
        if (answered >= maxQaTurns) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "최대 문답 수(" + maxQaTurns + ")에 도달했습니다 — 새 질문 세션을 열어주세요");
        }
        List<MultipartFile> attached = files == null ? List.of() : files;
        attachmentStorage.validate(attached);
        InterviewService.AnswerOutcome outcome =
                interviewService.submitAnswer(id, actorId, isAdmin, req.toAnswerRequest());
        InterviewSession s = outcome.session();
        applyModelChange(s, req.model(), req.effort());
        InterviewTurn note = applyMcpChange(id, s, req.mcpCatalogIds());
        if (outcome.turnSeq() != null) {
            writeAttachments(id, outcome.turnSeq(), attached, actorId);
        }
        return new AskResult(s, note);
    }

    /**
     * blank = 현재 값 유지(등록 시의 resolve*와 달리 기본값으로 되돌리지 않는다).
     * 한쪽만 바뀌어도 model×effort 조합은 항상 함께 검증한다 (예: effort=max 세션을 Haiku로만 바꾸면 400).
     * 단, 결과가 현재 값과 같으면 no-op(검증 생략) — 프론트가 픽커 값을 매 질문에 동봉하므로, 목록에서 빠진
     * 박제 모델(fable-5 등) 세션도 값을 바꾸지 않는 한 그대로 이어간다(ModelEffortPolicy 주석의 "박제 값은 검증을 타지 않는다").
     */
    private static void applyModelChange(InterviewSession s, String model, String effort) {
        boolean hasModel = model != null && !model.isBlank();
        boolean hasEffort = effort != null && !effort.isBlank();
        if (!hasModel && !hasEffort) return;
        String nextModel = hasModel ? model : s.getModel();
        String nextEffort = hasEffort ? effort : s.getEffort();
        if (nextModel.equals(s.getModel()) && nextEffort.equals(s.getEffort())) return;
        ModelEffortPolicy.validate(nextModel, nextEffort);
        s.setModel(nextModel);
        s.setEffort(nextEffort);
    }

    /**
     * 대화 중 MCP 변경 (스펙 2026-09-13 §5.2). ids == null → 유지. 현재 선택과 집합이 같으면(순서 무관)
     * <b>검증 없이</b> 무시 — 사후 비활성화된 카탈로그 항목이 무관한 다음 질문을 400으로 막지 않게
     * (applyModelChange의 박제 보호와 같은 취지). 다르면 resolveExtras(400) → mcps_extra + mcp_catalog_ids 갱신
     * → system note 턴. 다음 claim이 mcpsExtra를 새로 복사하므로 러너 변경 없이 반영된다.
     * @return 노트 턴(변경 시) 또는 null
     */
    private InterviewTurn applyMcpChange(Long sessionId, InterviewSession s, List<Long> ids) {
        if (ids == null) return null;
        if (idSet(ids).equals(idSet(s.getMcpCatalogIds()))) return null;
        List<TaskMcpSpec> extras = mcpCatalogService.resolveExtras(ids);
        s.setMcpsExtra(new ArrayList<>(extras));
        s.setMcpCatalogIds(new ArrayList<>(ids));
        String note = extras.isEmpty()
                ? "MCP 도구 변경: 없음(전부 해제)"
                : "MCP 도구 변경: " + extras.stream().map(TaskMcpSpec::name).collect(Collectors.joining(", "));
        return interviewService.appendSystemNote(sessionId, note);
    }

    /** jsonb 역직렬화 숫자 타입(Integer/Long)에 흔들리지 않는 집합 비교용. */
    private static Set<Long> idSet(List<? extends Number> ids) {
        Set<Long> out = new HashSet<>();
        if (ids == null) return out;
        for (Number n : ids) if (n != null) out.add(n.longValue());
        return out;
    }

    /**
     * 첨부 쓰기 (스펙 2026-09-13 §5.2): 순번 1..N → write → 오피스면 Tika 추출 → 성공 시 sidecar(.txt) → 메타 행.
     * RuntimeException 시 이미 쓴 파일(sidecar 포함) best-effort 삭제 후 rethrow(tx 롤백 → 메타 행 취소).
     * written.add가 write보다 먼저 — 실패한 파일의 부분 쓰기도 정리 대상(TaskService.create 패턴).
     */
    private void writeAttachments(Long sessionId, Integer turnSeq, List<MultipartFile> files, String uploadedBy) {
        if (files.isEmpty()) return;
        List<String> written = new ArrayList<>();
        try {
            int ordinal = 1;
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename();
                String rel = attachmentStorage.relativePathForQuestion(sessionId, turnSeq, ordinal, name);
                written.add(rel);
                attachmentStorage.write(rel, f);
                String extractedRel = null;
                Optional<String> text = tika.extractText(attachmentStorage.resolve(rel), name);
                if (text.isPresent()) {
                    extractedRel = attachmentStorage.extractedTextRelativePath(rel);
                    written.add(extractedRel);
                    attachmentStorage.writeText(extractedRel, text.get());
                }
                attachmentRepo.save(QuestionAttachment.create(sessionId, turnSeq,
                        AttachmentStorage.sanitize(name), rel, f.getContentType(), f.getSize(),
                        extractedRel, uploadedBy));
                ordinal++;
            }
        } catch (RuntimeException e) {
            written.forEach(attachmentStorage::deleteQuietly);
            throw e;   // tx 롤백 → 답변 턴/재큐/메타 행 전부 취소
        }
    }

    /** 첨부 단건 — 세션 소속이 아니면 존재를 숨긴다(404). ACL은 컨트롤러의 requireViewable이 담당. */
    @Transactional(readOnly = true)
    public QuestionAttachment getAttachment(Long sessionId, Long attachmentId) {
        QuestionAttachment a = attachmentRepo.findById(attachmentId).orElseThrow(TaskException::notFound);
        if (!a.getSessionId().equals(sessionId)) throw TaskException.notFound();
        return a;
    }

    public Path resolveAttachmentPath(QuestionAttachment att) {
        return attachmentStorage.resolve(att.getStoredPath());
    }

    /** 종료 = cancel 재사용 → CANCELLED (질문 문맥 라벨 "종료됨"). */
    @Transactional
    public InterviewSession close(Long id, String actorId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        return interviewService.cancel(id, actorId, isAdmin);
    }

    /**
     * 제목 미입력 시 질문 첫 줄에서 생성: strip → 첫 줄 → 연속 공백 1칸 → 60자 초과면 절단+'…'.
     * 명시 제목은 trim만 한다. question이 blank인 경우는 @NotBlank가 먼저 400으로 막는다.
     */
    static String deriveTitle(String title, String question) {
        if (title != null && !title.isBlank()) return title.trim();
        String firstLine = question.strip().lines().findFirst().orElse("")
                .replaceAll("\\s+", " ").trim();
        if (firstLine.length() <= DERIVED_TITLE_MAX) return firstLine;
        return firstLine.substring(0, DERIVED_TITLE_MAX) + "…";
    }
}
