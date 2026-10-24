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
        /** 세션 누적 SHADOW 비용. 인터뷰 서비스가 resume 턴의 SDK 누적 비용에서 이 값을 빼 이번 턴 비용을 구한다(2026-10-15). */
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
        String repoHost,
        /** 세션이 선택한 DB 접속정보 id (스펙 2026-10-02 §5.4, 2026-10-14 §5 — INTERVIEW도). */
        List<Long> dbConnectionIds,
        /** 복호화된 접속정보 — InterviewWorkerController가 withDb로 채운다. ⚠️ 평문 비밀번호 포함, 로그 금지. */
        List<DbConnectionRef> dbConnections,
        /** 사용할 수 없는 연결 등 프롬프트에 붙일 안내 (비밀번호 없음). */
        List<String> dbNotices,
        /**
         * 질문·인터뷰 세션의 읽기 전용 참조 repo (스펙 2026-10-22 §4.4, 2026-10-24 §4.4) — InterviewService.claim이
         * withRefRepos로 채운다. 항상 non-null, 참조가 없으면 [].
         */
        List<ClaimRefRepo> refRepos,
        /**
         * 작업 인터뷰에서 참조를 대화 중 모두 뺐는지 (스펙 2026-10-24 §4.4·§5.2) — 마지막 참조 변경 메모가
         * '없음(전부 해제)'이고 지금 참조가 없을 때만 true. 그 밖(참조를 고른 적 없음·질문 세션 포함)은 false라
         * 프롬프트가 그대로다. InterviewService.claim이 withRefsCleared로 채운다.
         */
        boolean refsCleared
) {
    /** 어느 경로로 생성돼도 non-null 계약 유지 (스펙 §5.4). */
    public InterviewClaimResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        dbConnectionIds = dbConnectionIds == null ? List.of() : ((List<?>) dbConnectionIds).stream()
                .map(o -> ((Number) o).longValue()).toList();
        dbConnections = dbConnections == null ? List.of() : List.copyOf(dbConnections);
        dbNotices = dbNotices == null ? List.of() : List.copyOf(dbNotices);
        refRepos = refRepos == null ? List.of() : List.copyOf(refRepos);
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

    /**
     * 복호화된 DB 접속정보 (스펙 2026-10-02 §5.4) — 질문·인터뷰 세션 claim에서만, 내부 워커 API로만 나간다.
     * ⚠️ password 평문 포함: 이 레코드(와 이를 담은 claim 응답)를 로그·예외 메시지에 넣지 말 것. toString은 가린다.
     */
    public record DbConnectionRef(String serverName, String label, String dbType, String host, int port,
                                  String database, String username, String password) {
        @Override
        public String toString() {
            return "DbConnectionRef[" + serverName + " " + dbType + " " + host + ":" + port + "/" + database
                    + " user=" + username + " password=****]";
        }
    }

    /**
     * 질문·인터뷰 세션 참조 repo 1개 (스펙 2026-10-22 §4.4, 2026-10-24 §4.4) — 인터뷰 서비스(TS)가 이 필드 이름 그대로 읽는다.
     * githubRepo = GitHub owner/repo 또는 GitLab 프로젝트 전체 경로(RefRepo.path), repoHost = "github" | "gitlab",
     * dir = 체크아웃 절대경로({루트}/_refs/{세션 id}/{카탈로그 id} — 저장하지 않고 claim마다 같은 규칙으로 계산),
     * sha = 처음 받아 고정한 커밋(아직 안 받았으면 null).
     */
    public record ClaimRefRepo(Long catalogId, String alias, String githubRepo, String gitUrl, String repoHost,
                               String branch, String dir, String sha) {}

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
                s.getGitUrl(), RepoRef.fromSnapshot(s.getGithubRepo(), s.getGitUrl()).host(),
                s.getDbConnectionIds() == null ? List.of() : List.copyOf(s.getDbConnectionIds()), List.of(), List.of(),
                List.of(), false);
    }

    /** DB 접속정보만 채운 사본 (스펙 §5.4). 참조 repo·refsCleared는 그대로 실어 나른다. */
    public InterviewClaimResponse withDb(List<DbConnectionRef> refs, List<String> notices) {
        return new InterviewClaimResponse(sessionId, githubRepo, githubBranch, title, description, claudeSessionId,
                currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns, model, effort, totalCostUsd,
                attachments, kind, attachmentRoot, gitUrl, repoHost, dbConnectionIds, refs, notices, refRepos,
                refsCleared);
    }

    /** 참조 repo만 채운 사본 (스펙 2026-10-22 §4.4). DB 접속정보·refsCleared는 그대로 실어 나른다. */
    public InterviewClaimResponse withRefRepos(List<ClaimRefRepo> refs) {
        return new InterviewClaimResponse(sessionId, githubRepo, githubBranch, title, description, claudeSessionId,
                currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns, model, effort, totalCostUsd,
                attachments, kind, attachmentRoot, gitUrl, repoHost, dbConnectionIds, dbConnections, dbNotices, refs,
                refsCleared);
    }

    /** refsCleared만 바꾼 사본 (스펙 2026-10-24 §4.4). 나머지는 그대로 실어 나른다. */
    public InterviewClaimResponse withRefsCleared(boolean cleared) {
        return new InterviewClaimResponse(sessionId, githubRepo, githubBranch, title, description, claudeSessionId,
                currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns, model, effort, totalCostUsd,
                attachments, kind, attachmentRoot, gitUrl, repoHost, dbConnectionIds, dbConnections, dbNotices,
                refRepos, cleared);
    }
}
