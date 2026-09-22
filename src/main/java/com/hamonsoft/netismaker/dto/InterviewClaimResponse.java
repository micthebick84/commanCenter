package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.git.RepoRef;

import java.util.List;
import java.util.Map;

/**
 * 워커가 인터뷰를 claim했을 때 받는 페이로드 (canonical — plan 02/03가 그대로 재사용).
 * claudeSessionId null → brainstorming 신규 시작, 있으면 resume.
 * workDir = resume 시 반드시 동일하게 써야 하는 cwd(레포 체크아웃 경로, 스파이크 02).
 * lastAnswer = resume 시 주입할 마지막 사용자 답변, replyToSeq = 그 답변이 응답한 question seq.
 * turns = 누적 컨텍스트.
 */
public record InterviewClaimResponse(
        long sessionId,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        String claudeSessionId,
        String currentPhase,
        String workDir,
        String lastAnswer,
        Integer replyToSeq,
        List<TaskMcpSpec> mcpsExtra,
        List<Turn> turns,
        String model,
        String effort,
        /** 세션 누적 SHADOW 비용. 워커가 CostGuard를 이 값으로 시드해 세션 전체 누적 가드로 쓴다(단일 턴 아님). */
        double totalCostUsd,
        /**
         * 등록 시 업로드된 첨부(절대경로 — work_dir과 동일한 단일 호스트 전제). 항상 non-null.
         * INTERVIEW = 소유 task의 첨부, QUESTION = turn_seq null(킥오프) 첨부 (스펙 2026-09-13 §5.1).
         */
        List<AttachmentRef> attachments,
        /** 세션 종류 'INTERVIEW' | 'QUESTION' (InterviewKind.name()). 인터뷰 서비스가 Q&A 모드 판정에 사용. */
        String kind,
        /**
         * 질문 세션 첨부 루트 절대경로({dir}/question-{sid}) — 러너의 Read 게이트 두 번째 허용 루트.
         * INTERVIEW면 null (스펙 2026-09-13 §5.1).
         */
        String attachmentRoot,
        /** 정식 Git URL 스냅샷. null이면 GitHub로 간주(구버전 세션). */
        String gitUrl,
        /** "github" | "gitlab". */
        String repoHost
) {
    /** 어느 경로로 생성돼도 non-null 계약 유지 (스펙 §5.4). */
    public InterviewClaimResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public RepoRef repoRef() {
        return RepoRef.fromSnapshot(githubRepo, gitUrl);
    }

    /** attachments = 이 턴(user answer)에 첨부된 파일. 항상 non-null (QUESTION 외엔 []). */
    public record Turn(int seq, String role, String kind, String content, Integer replyToSeq,
                       List<AttachmentRef> attachments) {
        public Turn {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /**
     * contentType만 null 가능 — TS 타입도 string | null (스펙 §5.4).
     * extractedTextPath = Tika sidecar(.txt) 절대경로, 오피스 문서 + 추출 성공 시만 (그 외 null).
     */
    public record AttachmentRef(long id, String fileName, String absolutePath,
                                String contentType, long sizeBytes, String extractedTextPath) {
        /** 추출본 없는 첨부(작업 첨부·비오피스) — 기존 호출처 호환. */
        public AttachmentRef(long id, String fileName, String absolutePath, String contentType, long sizeBytes) {
            this(id, fileName, absolutePath, contentType, sizeBytes, null);
        }
    }

    /** 레거시 3-arg — 턴별 첨부 없음, attachmentRoot null (INTERVIEW 경로와 동일 형태). */
    public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns,
                                            List<AttachmentRef> attachments) {
        return of(s, turns, attachments, Map.of(), null);
    }

    public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns,
                                            List<AttachmentRef> attachments,
                                            Map<Integer, List<AttachmentRef>> turnAttachments,
                                            String attachmentRoot) {
        String lastAnswer = null;
        Integer replyToSeq = null;
        for (int i = turns.size() - 1; i >= 0; i--) {
            InterviewTurn t = turns.get(i);
            if ("user".equals(t.getRole()) && "answer".equals(t.getKind())) {
                lastAnswer = t.getContent();
                replyToSeq = t.getReplyToSeq();
                break;
            }
        }
        Map<Integer, List<AttachmentRef>> byTurn = turnAttachments == null ? Map.of() : turnAttachments;
        List<Turn> mapped = turns.stream()
                .map(t -> new Turn(t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq(),
                        byTurn.get(t.getSeq())))
                .toList();
        double totalCostUsd = s.getTotalCostUsd() == null ? 0.0 : s.getTotalCostUsd().doubleValue();
        return new InterviewClaimResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                s.getClaudeSessionId(), s.getCurrentPhase(), s.getWorkDir(),
                lastAnswer, replyToSeq,
                s.getMcpsExtra() == null ? List.of() : List.copyOf(s.getMcpsExtra()),
                mapped, s.getModel(), s.getEffort(), totalCostUsd, attachments, s.getKind().name(),
                attachmentRoot,
                s.getGitUrl(), RepoRef.fromSnapshot(s.getGithubRepo(), s.getGitUrl()).host());
    }
}
