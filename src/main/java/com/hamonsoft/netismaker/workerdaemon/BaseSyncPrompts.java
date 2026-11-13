package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.FixRoundSpec;

import java.util.List;

/**
 * 기준 브랜치 반영 회차(kind=BASE_SYNC)의 이름·병합 커밋 메시지·충돌 해결 프롬프트. 작업 브랜치에 기준 브랜치를 병합하다가 텍스트
 * 충돌로 멈춘 worktree에서 Claude가 충돌 표식만 푼다. 리뷰 절·배포 env 지시·DB 절·참조 절·디자인·목업은 붙이지 않는다.
 */
final class BaseSyncPrompts {

    /** claim의 회차 종류 — FixRoundSpec.kind. */
    static final String KIND = "BASE_SYNC";

    private BaseSyncPrompts() {}

    static boolean isBaseSync(FixRoundSpec fix) {
        return fix != null && KIND.equals(fix.kind());
    }

    /** "기준 브랜치 반영 {n}회차" — 실패 사유·로그 공용. */
    static String label(FixRoundSpec fix) {
        return "기준 브랜치 반영 " + fix.round() + "회차";
    }

    /** 병합 커밋 메시지 — 기준 브랜치는 작업의 githubBranch. */
    static String commitMessage(WorkerTaskResponse task, String gitUserEmail) {
        return "merge: 기준 브랜치 " + task.githubBranch() + " 반영 — " + task.title()
                + "\n\n" + "task #" + task.id()
                + "\n\nCo-Authored-By: netisMaker <" + gitUserEmail + ">";
    }

    /** 충돌 해결 프롬프트. conflicted는 병합기가 돌려준 경로 그대로(다시 나누거나 따옴표를 씌우지 않는다). */
    static String render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, List<String> conflicted) {
        StringBuilder files = new StringBuilder();
        for (String f : conflicted) {
            if (files.length() > 0) files.append('\n');
            files.append("- ").append(f);
        }
        StringBuilder p = new StringBuilder("""
                당신은 git 병합 충돌을 푸는 전문가입니다. 현재 디렉토리는 이 작업 전용 git worktree이고, 작업 브랜치 '%s'(끝 커밋 %s)에
                기준 브랜치 '%s'를 병합하다가 충돌로 멈춘 상태입니다. 충돌하지 않은 변경은 이미 병합되어 스테이징돼 있습니다.

                ## 할 일 — 아래 충돌 파일의 충돌만 푸세요
                %s
                - 충돌 표식(<<<<<<<, =======, >>>>>>>)이 있는 곳만 고치고, 표식은 모두 지우세요.
                - 양쪽 변경의 의도를 모두 살리세요: 이 작업의 변경(아래 "이 작업")과 기준 브랜치에 먼저 들어온 다른 작업의 변경 둘 다 남아야 합니다.
                - 작업 기록 문서(PLAN.md처럼 작업마다 통째로 다시 쓰는 파일)는 한쪽을 버리지 마세요. 기준 브랜치 쪽 내용을 그대로 두고 그 아래에 이 작업의 내용을 덧붙이세요.
                - 위 목록에 없는 파일은 건드리지 마세요. 리팩터링·이름 바꾸기·포맷 정리도 하지 마세요.
                - git add는 해도 되지만 commit·push·rebase·reset·checkout·switch·`merge --abort`는 하지 마세요 — 워커가 마지막에 병합 커밋을 만들고 push합니다.
                - 현재 디렉토리에서 직접 파일을 편집하세요 (cd 금지).
                - 어느 쪽을 살려야 할지 판단할 수 없는 충돌은 고치지 말고 그대로 둔 채 그 이유를 출력에 남기세요.
                - 종료 직전에 `git status`로 상태를 출력하세요.

                ## 이 작업 (참고용 — 다시 구현하지 마세요)
                """.formatted(fix.headBranch(), sha7(tipSha), task.githubBranch(), files));
        p.append("\n### 제목\n").append(task.title()).append("\n\n### 요구사항\n")
                .append(task.description() == null ? "" : task.description()).append("\n");
        if (task.planMarkdown() != null && !task.planMarkdown().isBlank()) {
            p.append("\n### 확정된 구현 계획\n").append(task.planMarkdown()).append("\n");
        }
        return p.toString();
    }

    private static String sha7(String sha) {
        if (sha == null) return "";
        String s = sha.strip();
        return s.substring(0, Math.min(7, s.length()));
    }
}
