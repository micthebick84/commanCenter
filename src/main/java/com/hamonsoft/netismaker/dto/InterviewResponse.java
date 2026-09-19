package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewPlan;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewTurn;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 인터뷰 세션 상세 뷰 (status + turns + plan). status는 한글 dbValue로 노출(프론트 표시용). */
public record InterviewResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        String status,        // 한글 dbValue (사용자 표시). SSE status 이벤트는 영문 enum name 사용.
        String statusName,     // 영문 enum name (InterviewStatus.name()). 프론트 로직/하이드레이션용.
        String currentPhase,
        String workDir,
        Long taskId,
        String model,
        String effort,
        List<TurnView> turns,
        PlanView plan,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** 'INTERVIEW' | 'QUESTION' — 프론트가 질문 세션 UI 분기에 사용. */
        String kind,
        /** 세션 누적 SHADOW 비용(상세 헤더 칩). */
        BigDecimal totalCostUsd,
        /** 마지막 턴 컨텍스트 토큰 / 창 크기 (스펙 2026-09-05 §4.2). null = 미보고 → 프론트는 칩 숨김. */
        Long contextTokens,
        Long contextWindow,
        /** 선택한 MCP 카탈로그 id — 대화 페이지 McpPicker 시딩 (스펙 2026-09-13 §5.1). 항상 non-null. */
        List<Long> mcpCatalogIds,
        /** 등록 시(킥오프) 첨부 — turn_seq null. 항상 non-null. 절대경로·추출경로는 내보내지 않는다. */
        List<AttachmentView> attachments
) {
    public InterviewResponse {
        mcpCatalogIds = mcpCatalogIds == null ? List.of() : List.copyOf(mcpCatalogIds);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** attachments = 이 턴(user answer)에 첨부된 파일. 항상 non-null. */
    public record TurnView(int seq, String role, String kind, String content,
                           Integer replyToSeq, OffsetDateTime createdAt, List<AttachmentView> attachments) {
        public TurnView {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** 첨부 없는 턴 — 기존 호출처 호환. */
        public TurnView(int seq, String role, String kind, String content,
                        Integer replyToSeq, OffsetDateTime createdAt) {
            this(seq, role, kind, content, replyToSeq, createdAt, List.of());
        }
    }

    public record PlanView(String designMarkdown, String planMarkdown, String planJson,
                           Long durationMs, OffsetDateTime completedAt) {}

    /** 브라우저용 첨부 메타 — 경로 없음(다운로드는 GET /api/questions/{id}/attachments/{attId}). */
    public record AttachmentView(long id, String fileName, String contentType, long sizeBytes) {}

    /** 레거시 3-arg — 첨부/MCP id 없음 (INTERVIEW 세션 형태). */
    public static InterviewResponse of(InterviewSession s, List<InterviewTurn> turns, InterviewPlan plan) {
        return of(s, turns, plan, List.of(), Map.of());
    }

    public static InterviewResponse of(InterviewSession s, List<InterviewTurn> turns, InterviewPlan plan,
                                       List<AttachmentView> attachments,
                                       Map<Integer, List<AttachmentView>> turnAttachments) {
        Map<Integer, List<AttachmentView>> byTurn = turnAttachments == null ? Map.of() : turnAttachments;
        List<TurnView> tvs = turns.stream()
                .map(t -> new TurnView(t.getSeq(), t.getRole(), t.getKind(), t.getContent(),
                        t.getReplyToSeq(), t.getCreatedAt(), byTurn.get(t.getSeq())))
                .toList();
        PlanView pv = plan == null ? null : new PlanView(
                plan.getDesignMarkdown(), plan.getPlanMarkdown(), plan.getPlanJson(),
                plan.getDurationMs(), plan.getCompletedAt());
        return new InterviewResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(), s.getWorkDir(), s.getTaskId(),
                s.getModel(), s.getEffort(), tvs, pv, s.getCreatedAt(), s.getUpdatedAt(),
                s.getKind().name(), s.getTotalCostUsd(), s.getContextTokens(), s.getContextWindow(),
                s.getMcpCatalogIds(), attachments);
    }
}
