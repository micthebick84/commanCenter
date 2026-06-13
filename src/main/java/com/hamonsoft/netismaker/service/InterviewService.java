package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 인터뷰 세션 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 (TaskService 패턴).
 *
 *   create        ─► QUEUED
 *   claim         ─► QUEUED → RUNNING (워커, SKIP LOCKED)
 *   recordQuestion─► RUNNING → AWAITING_INPUT (워커가 질문 emit, 반납)
 *   submitAnswer  ─► AWAITING_INPUT → QUEUED (사용자 답변, 재큐; idempotent)
 *   recordPlan    ─► RUNNING → PLAN_READY (writing-plans 완료)
 *   register      ─► PLAN_READY → REGISTERED (+ Task(COMPLETED)+TaskAnalysis 프리필)
 *   cancel        ─► QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY → CANCELLED
 *   expire        ─► AWAITING_INPUT → EXPIRED (idle TTL)
 *   fail          ─► QUEUED/RUNNING/AWAITING_INPUT → FAILED
 *
 * Task 상태머신(TaskStatus)은 변경하지 않는다.
 */
@Service
@Profile("api")
public class InterviewService {

    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final InterviewPlanRepository planRepo;
    private final McpCatalogService mcpCatalogService;
    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;

    @Value("${app.interview.user-concurrent-limit:3}")
    private int userConcurrentLimit;

    @Value("${app.task.max-retry:3}")
    private int maxRetry;

    public InterviewService(InterviewSessionRepository sessionRepo,
                            InterviewTurnRepository turnRepo,
                            InterviewPlanRepository planRepo,
                            McpCatalogService mcpCatalogService,
                            TaskRepository taskRepo,
                            TaskAnalysisRepository analysisRepo,
                            TaskStatusHistoryRepository historyRepo) {
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.planRepo = planRepo;
        this.mcpCatalogService = mcpCatalogService;
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public InterviewSession create(CreateInterviewRequest req, String requesterId) {
        long active = sessionRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 진행할 수 있는 인터뷰 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        InterviewSession s = InterviewSession.create(req.githubRepo(), req.githubBranch(),
                req.title(), req.description(), requesterId, extras);
        return sessionRepo.save(s);
    }

    /** 카탈로그 id 리스트 → snapshot 스펙. 비활성/누락 id는 거절. TaskService와 동일 규칙. */
    private List<TaskMcpSpec> resolveMcpExtras(List<Long> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) return new ArrayList<>();
        List<McpCatalogEntry> entries = mcpCatalogService.resolveByIds(catalogIds);
        if (entries.size() != catalogIds.size()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 MCP 카탈로그 id 포함. 요청=" + catalogIds.size()
                            + " 매칭=" + entries.size());
        }
        for (McpCatalogEntry e : entries) {
            if (!e.isEnabled()) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "비활성화된 MCP 카탈로그 항목: " + e.getName());
            }
        }
        List<TaskMcpSpec> out = new ArrayList<>(entries.size());
        for (McpCatalogEntry e : entries) {
            out.add(new TaskMcpSpec(e.getName(), e.getUrl(), e.getTransport()));
        }
        return out;
    }

    /** session_id의 다음 seq. 턴이 없으면 0. */
    private int nextSeq(Long sessionId) {
        Integer max = turnRepo.findMaxSeq(sessionId);
        return max == null ? 0 : max + 1;
    }

    /** assistant/system 턴 (reply_to_seq 없음). */
    private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content) {
        return appendTurn(sessionId, role, kind, content, null);
    }

    /** user answer 턴은 replyToSeq를 함께 기록 (idempotency 키 + UI 스레딩). */
    private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content,
                                     Integer replyToSeq) {
        return turnRepo.save(
                InterviewTurn.of(sessionId, nextSeq(sessionId), role, kind, content, replyToSeq));
    }
}
