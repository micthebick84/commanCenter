package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.FixRoundSpec;
import com.hamonsoft.netismaker.entity.ImplementationMode;

import java.util.List;
import java.util.Locale;

/**
 * 수정 회차 구현 프롬프트 (스펙 2026-11-03 §7). 이 브랜치에는 이미 구현이 있으니 처음부터 다시 짜지 말고 이번 수정 요청만
 * 고치게 한다 — 원래 요건·계획은 무엇이 만들어졌는지 이해하는 참고로만 준다. 리뷰 방식이면 끝에 리뷰 절
 * (fix-round-review-section.md)을 붙이고, 그 리뷰 범위는 원래 계획이 아니라 이번 수정 요청 본문이다(§6.2). SDD는 회차에서 고를 수
 * 없어(원래 플랜 전체를 다시 구현한다) 워커가 리뷰로 돌린다. 끝에는 지금 구현 프롬프트처럼 배포 env 목록 지시·DB 절·참조 절을 붙인다.
 * 스크린샷은 넘기지 않는다 — 재검증의 근거는 요청 본문에 글로 들어온다.
 */
final class FixRoundPrompts {

    private static final String REVIEW_SECTION = ImplementationPrompts.resource("/prompts/fix-round-review-section.md");

    private FixRoundPrompts() {}

    /** "수정 {n}회차" — 커밋 메시지·실패 사유·로그 공용. */
    static String label(FixRoundSpec fix) {
        return "수정 " + fix.round() + "회차";
    }

    /**
     * 회차 브랜치 끝(tip)이 API가 기록한 head_sha와 다른가 — 사람이 배포 뒤 MR에 직접 push한 경우다. 기록이 짧은 sha여도 되게 앞부분으로
     * 비교한다(대소문자 무시). 기록이 없으면 비교할 수 없어 false.
     */
    static boolean remoteAhead(String tip, String recordedSha) {
        if (recordedSha == null || recordedSha.isBlank()) return false;
        String recorded = recordedSha.strip().toLowerCase(Locale.ROOT);
        return tip == null || !tip.strip().toLowerCase(Locale.ROOT).startsWith(recorded);
    }

    /** 원격 끝이 기록과 다를 때 프롬프트·구현 로그·실패 사유에 남기는 한 줄(조사 없이 — sha 뒤 받침을 모른다). */
    static String remoteAheadNote(String tip, String recordedSha) {
        return "원격 브랜치 끝이 " + sha7(tip) + "입니다(기록된 구현 커밋은 " + sha7(recordedSha)
                + ") — 사람이 브랜치에 직접 올린 커밋이 있을 수 있어 그 끝 위에서 고칩니다.";
    }

    /** 참조 repo 없는 회차 프롬프트. */
    static String render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, boolean remoteAhead,
                         ImplementationMode mode, String commitRule) {
        return render(task, fix, tipSha, remoteAhead, mode, commitRule, List.of());
    }

    /** DB 추가 절 없는 회차 프롬프트 — 기존 호출부·테스트용(dbExtra=""). */
    static String render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, boolean remoteAhead,
                         ImplementationMode mode, String commitRule, List<WorktreeService.RefWorktree> refs) {
        return render(task, fix, tipSha, remoteAhead, mode, commitRule, refs, "");
    }

    /**
     * 회차 프롬프트 본문 + 배포 env 목록 지시 + DB 절 + dbExtra + 참조 절. mode는 실제로 돌 방식(직접·리뷰) — 리뷰만 리뷰 절을 붙이고, 리뷰어는
     * tipSha 대비 커밋되지 않은 변경을 본다. DB 절은 쓰기 연결이 있으면 "개발 DB 쓰기"(스펙 2026-11-03 §6.7)이고, dbExtra에는 호출부가
     * 만든 자동 마이그레이션 절과 이미 적용된 SQL 절만 순서대로 담는다({@link DbMcpWorkerSupport}) — 타입 절은 회차에 넣지 않는다(§5.2).
     */
    static String render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, boolean remoteAhead,
                         ImplementationMode mode, String commitRule, List<WorktreeService.RefWorktree> refs,
                         String dbExtra) {
        String label = label(fix);
        List<String> previous = fix.previousBodies() == null ? List.of() : fix.previousBodies();
        StringBuilder p = new StringBuilder();
        p.append("""
                당신은 코드 구현 전문가입니다. 현재 디렉토리는 이 작업 전용 git worktree이고, 작업 브랜치 '%s'의 끝 커밋(%s)이
                detached HEAD로 체크아웃되어 있습니다. (베이스 브랜치: %s)

                ## %s — 이미 구현된 브랜치에서 이어 고치기
                이 브랜치에는 이 작업의 구현이 이미 있고 배포까지 되었습니다. 배포된 결과를 확인한 사람이 아래 "이번 수정 요청"을 냈습니다.
                처음부터 다시 짜지 말고, 이번 수정 요청에 적힌 문제만 고치세요.
                - 아래 "원래 작업"(요건·계획·분석)은 무엇이 만들어졌는지 이해하는 참고용입니다. 계획을 다시 실행하거나 이미 된 부분을 새로 구현하지 마세요.
                - 요청과 상관없는 리팩터링·이름 바꾸기·포맷 정리는 하지 마세요.
                """.formatted(fix.headBranch(), sha7(tipSha), task.githubBranch(), label));
        if (!previous.isEmpty()) {
            p.append("- 앞선 수정 회차에서 고친 것(아래 \"앞선 수정 요청\")을 되돌리지 마세요.\n");
        }
        if (remoteAhead) {
            p.append("- ").append(remoteAheadNote(tipSha, fix.headSha())).append(" 그 커밋의 변경을 되돌리지 마세요.\n");
        }
        p.append("""
                - 새 브랜치를 만들거나 브랜치를 바꾸지 말고(checkout·switch), push·rebase·reset·merge도 하지 마세요 — 워커가 마지막에 이 브랜치에 이어서 push합니다.
                - 현재 디렉토리에서 직접 파일을 편집·생성하세요 (cd 금지)
                - %s
                - 빌드/테스트는 가능하면 수행하되 실패 시 명확한 사유를 남기세요
                - 고칠 곳을 찾지 못했거나 이미 고쳐져 있으면 파일을 바꾸지 말고 그 이유를 출력에 남기세요
                - 종료 직전에 `git status`로 변경 요약을 출력하세요

                ## 이번 수정 요청 (%s)
                """.formatted(commitRule, label));
        p.append(fix.body() == null ? "" : fix.body().strip()).append("\n");

        if (!previous.isEmpty()) {
            p.append("\n## 앞선 수정 요청 (참고 — 앞선 회차)\n");
            int firstRound = fix.round() - previous.size();
            for (int i = 0; i < previous.size(); i++) {
                p.append("\n### 수정 ").append(firstRound + i).append("회차\n")
                        .append(previous.get(i) == null ? "" : previous.get(i).strip()).append("\n");
            }
        }

        p.append("\n## 원래 작업 (참고용)\n\n### 제목\n").append(task.title()).append("\n\n### 요구사항\n")
                .append(task.description() == null ? "" : task.description()).append("\n");
        String design = designSection(task);
        if (!design.isEmpty()) p.append(design).append("\n");
        if (task.planMarkdown() != null && !task.planMarkdown().isBlank()) {
            p.append("\n### 확정된 구현 계획 (참고용 — 다시 실행하지 마세요)\n").append(task.planMarkdown()).append("\n");
        }
        if (task.analysisMarkdown() != null && !task.analysisMarkdown().isBlank()) {
            p.append("\n### 사전 분석 결과\n").append(task.analysisMarkdown()).append("\n");
        }

        if (mode == ImplementationMode.REVIEW) {
            String requirements = "This is fix round " + fix.round() + " on a branch that already holds "
                    + "this task's implementation. The fix request for this round is quoted under [DESCRIPTION] above: "
                    + "review only whether the uncommitted changes resolve it without breaking existing behavior. "
                    + "Do not review the earlier implementation against the original plan.";
            p.append(REVIEW_SECTION.replace("__REVIEWER__", ImplementationPrompts.reviewer(requirements, tipSha)));
        }
        return p
                + WorkerMainLoop.DEPLOY_ENV_INSTRUCTION
                + DbMcpWorkerSupport.promptSection(task.dbMcp(), true)
                + (dbExtra == null ? "" : dbExtra)
                + RefRepoPrompts.promptSection(refs);
    }

    /**
     * "원래 작업" 아래 H3로 넣는 승인된 디자인 — 참고용. 보통 구현 프롬프트의 H2 절(WorkerMainLoop.renderDesignSection, "반드시 이 디자인
     * 기준으로 구현")을 그대로 넣으면 회차 에이전트가 이미 된 화면을 다시 만들 수 있어 따로 쓴다. 승인된 디자인이 없으면 빈 문자열.
     */
    static String designSection(WorkerTaskResponse task) {
        if (task.designMarkdown() == null || task.designMarkdown().isBlank()) return "";
        return """

                ### 확정된 디자인 (참고용 — 이미 구현됨, 이번 수정 범위만 고칠 것)
                아래 디자인 문서와 `.design/` 디렉토리의 화면 목업 HTML은 이 작업에서 승인되어 이미 구현된 디자인입니다.
                이번 수정 요청이 화면을 고치게 할 때만 그 부분을 목업에 맞추는 기준으로 쓰고, 요청과 상관없는 화면을 디자인대로 다시 만들지 마세요.
                `.design/`은 참조용이며 커밋하지 마세요.

                """ + task.designMarkdown();
    }

    private static String sha7(String sha) {
        if (sha == null) return "";
        String s = sha.strip();
        return s.substring(0, Math.min(7, s.length()));
    }
}
