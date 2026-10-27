package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;

import java.util.List;

/**
 * 구현의 읽기 전용 참조 repo 문구 (스펙 2026-10-24 §6.1·§6.4·§6.5·§6.6) — 프롬프트 절·계획 파일 머리·PR 본문 절·실패 문구.
 * 참조가 없으면 전부 빈 문자열이라 참조 없는 작업의 프롬프트·계획 파일·PR 본문은 바이트 그대로다.
 */
final class RefRepoPrompts {

    private static final String TITLE = "## 참조 repo (읽기 전용)\n";
    /** 준비 실패 사유의 git 메시지 부분 상한(자). */
    private static final int PREP_REASON_MAX_CHARS = 300;

    private RefRepoPrompts() {}

    /** 구현 프롬프트 끝(DB 절 뒤)에 붙는 참조 절 — 앞에 빈 줄 둘. 참조가 없으면 "". */
    static String promptSection(List<WorktreeService.RefWorktree> refs) {
        return refs.isEmpty() ? "" : "\n\n" + block(refs);
    }

    /**
     * 계획 scratch 파일 머리 — 프롬프트 절과 같은 블록에 빈 줄 하나. SDD 지시서·리뷰어가 파일만 읽어도 참조를 안다.
     * `### Task N`·`### 작업 N` 모양의 줄이 없어 태스크 수가 바뀌지 않는다. 참조가 없으면 "".
     */
    static String planFileHeader(List<WorktreeService.RefWorktree> refs) {
        return refs.isEmpty() ? "" : block(refs) + "\n";
    }

    private static String block(List<WorktreeService.RefWorktree> refs) {
        StringBuilder sb = new StringBuilder(TITLE)
                .append("아래 폴더는 비교·이식할 때 읽기만 하는 참조다. 구현은 현재 디렉토리(대상)에서만 한다.\n");
        for (WorktreeService.RefWorktree r : refs) {
            sb.append("- ").append(r.alias()).append(" · ").append(r.branch()).append('@').append(sha7(r.sha()))
                    .append(" : ").append(r.dir().getAbsolutePath()).append('\n');
        }
        return sb.append("규칙:\n")
                .append("- 참조 폴더의 파일을 만들거나 고치거나 지우지 않는다. 빌드·설치·테스트 같은 명령도 참조 폴더에서 돌리지 않는다.\n")
                .append("- 계획·설계에 적힌 `별칭:경로:줄`은 위 별칭의 폴더에서 찾는다.\n")
                .append("- 참조 폴더는 커밋되지 않는다. 필요한 코드는 대상에 옮겨 적는다.\n")
                .toString();
    }

    /** PR/MR 본문의 고정 절(머리 다음) — 고정 커밋, 경고가 있으면(null은 없음) 경고 줄, 끝에 빈 줄. 참조가 없으면 "". */
    static String prSection(List<WorkerTaskResponse.TaskRefRepo> refs, List<String> warnings) {
        if (refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(TITLE);
        for (WorkerTaskResponse.TaskRefRepo r : refs) {
            sb.append("- ").append(r.alias()).append(" · ").append(r.branch()).append('@').append(sha7(r.sha()))
                    .append('\n');
        }
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("> ⚠ 참조 repo 변경 감지(이 PR에는 포함 안 됨): ").append(String.join(", ", warnings)).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** 구현 실패 사유 끝에 붙는 변경 경고(평문). 경고가 없으면(null 포함) "". */
    static String failureSuffix(List<String> warnings) {
        return warnings == null || warnings.isEmpty() ? "" : " — 참조 repo 변경 감지: " + String.join(", ", warnings);
    }

    /**
     * 참조 준비 실패 사유 — {@code 참조 repo 준비 실패: {별칭}({브랜치}) — {사유}}. 사유는 가린 메시지의 마지막
     * {@code fatal:}·{@code error:} 줄(앞뒤 공백 제거), 없으면 비지 않은 첫 줄, {@value #PREP_REASON_MAX_CHARS}자까지.
     * 메시지가 없으면 예외 이름.
     */
    static String prepFailureReason(WorkerTaskResponse.TaskRefRepo ref, Throwable e) {
        return "참조 repo 준비 실패: " + ref.alias() + "(" + ref.branch() + ") — " + reasonLine(e);
    }

    private static String reasonLine(Throwable e) {
        String message = GitRemotes.mask(e.getMessage());
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        List<String> lines = message.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        String picked = lines.stream()
                .filter(line -> line.startsWith("fatal:") || line.startsWith("error:"))
                .reduce((first, second) -> second)
                .orElse(lines.get(0));
        return GitRemotes.maskAndCut(picked, PREP_REASON_MAX_CHARS);
    }

    static String sha7(String sha) {
        if (sha == null) return "";
        return sha.substring(0, Math.min(7, sha.length()));
    }
}
