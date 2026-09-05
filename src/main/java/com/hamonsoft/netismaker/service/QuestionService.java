package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 질문 세션(Q&A) — 스펙 docs/superpowers/specs/2026-08-30-question-sessions-design.md.
 * 상태 전이는 전부 InterviewService에 위임하고, 여기서는 (1) kind=QUESTION 교차 가드(404),
 * (2) 등록 시 남용 가드(429)와 모델/MCP 스냅샷, (3) 문답 턴 상한(400)만 담당한다.
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
    private final int maxActivePerUser;
    private final int maxQaTurns;

    public QuestionService(InterviewService interviewService,
                           InterviewSessionRepository sessionRepo,
                           InterviewTurnRepository turnRepo,
                           RepoCatalogService repoCatalogService,
                           McpCatalogService mcpCatalogService,
                           @Value("${app.question.max-active-per-user:3}") int maxActivePerUser,
                           @Value("${app.question.max-qa-turns:10}") int maxQaTurns) {
        this.interviewService = interviewService;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.repoCatalogService = repoCatalogService;
        this.mcpCatalogService = mcpCatalogService;
        this.maxActivePerUser = maxActivePerUser;
        this.maxQaTurns = maxQaTurns;
    }

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
        return sessionRepo.save(s);
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

    /** SSE 구독 전 ACL 검증 (kind 404 → 소유자/관리자 403). */
    @Transactional(readOnly = true)
    public void requireViewable(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        interviewService.getForView(id, viewerId, isAdmin);
    }

    /**
     * 추가 질문 = submitAnswer 재사용(재큐). 문답 상한(assistant 답변 수)은 여기서 400으로 우아하게 막는다 —
     * 러너의 maxTurns 가드(FAILED)는 방어선으로만 남긴다 (불변식: maxQaTurns < INTERVIEW_MAX_TURNS).
     */
    @Transactional
    public InterviewSession ask(Long id, String actorId, boolean isAdmin, AnswerRequest req) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        long answered = turnRepo.countBySessionIdAndRole(id, "assistant");
        if (answered >= maxQaTurns) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "최대 문답 수(" + maxQaTurns + ")에 도달했습니다 — 새 질문 세션을 열어주세요");
        }
        return interviewService.submitAnswer(id, actorId, isAdmin, req);
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
