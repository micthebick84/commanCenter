package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

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
        List<Turn> turns
) {
    public record Turn(int seq, String role, String kind, String content, Integer replyToSeq) {}

    public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns) {
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
        List<Turn> mapped = turns.stream()
                .map(t -> new Turn(t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq()))
                .toList();
        return new InterviewClaimResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(),
                s.getTitle(), s.getDescription(),
                s.getClaudeSessionId(), s.getCurrentPhase(), s.getWorkDir(),
                lastAnswer, replyToSeq,
                s.getMcpsExtra() == null ? List.of() : List.copyOf(s.getMcpsExtra()),
                mapped
        );
    }
}
