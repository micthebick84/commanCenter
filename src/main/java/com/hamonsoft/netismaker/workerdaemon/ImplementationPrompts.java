package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 방식별 구현 프롬프트 조각 (스펙 2026-10-15 §6.2~§6.4·§6.7). 직접 방식은 WorkerMainLoop.renderPlanSection 그대로다.
 * 리뷰 방식의 리뷰어 지시는 superpowers code-reviewer.md(MIT) 사본 리소스 — 플러그인 없이도 돈다.
 */
final class ImplementationPrompts {

    static final String DIRECT_COMMIT_RULE = "의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)";
    static final String SDD_COMMIT_RULE =
            "태스크별 커밋은 SDD 흐름대로 하세요 — push는 하지 마세요. 끝난 뒤 남은 변경과 push는 워커가 처리합니다";

    /** 리뷰 방식 플랜 절의 마지막 줄 — 구현은 이 세션에서 직접, 리뷰만 서브에이전트 하나. 나머지 틀은 직접 방식과 공유한다. */
    private static final String REVIEW_LAST_BULLET =
            "구현은 계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) "
                    + "없이 이 세션에서 직접 하세요. 리뷰만 아래 \"마지막 리뷰\"대로 서브에이전트 하나에 맡깁니다.";
    /** 계획 본문이 없는 작업의 리뷰 — 리뷰어가 요구사항 원문을 기준으로 본다. */
    private static final String NO_PLAN_NOTE =
            "\n계획 본문이 없는 작업입니다 — 리뷰어 지시의 [DESCRIPTION]에 구현 요약과 함께 위 \"요구사항\" 원문을 그대로 넣으세요.\n";

    private static final String REVIEW_SECTION = resource("/prompts/implementation-review-section.md");
    private static final String REVIEWER = stripLeadingComment(resource("/prompts/implementation-reviewer.md"));

    private ImplementationPrompts() {}

    /** {commit_rule} — SDD만 태스크별 커밋, 나머지는 기존 문구(라이브 yml과 같은 글자). */
    static String commitRule(ImplementationMode effective) {
        return effective == ImplementationMode.SDD ? SDD_COMMIT_RULE : DIRECT_COMMIT_RULE;
    }

    /** {plan_section}. designWritten = SDD에 설계 파일을 알릴지(실제로 썼을 때만). */
    static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha,
                              boolean designWritten) {
        return switch (effective) {
            case DIRECT -> WorkerMainLoop.renderPlanSection(task);
            case REVIEW -> reviewPlanSection(task, baseSha);
            case SDD -> sddPlanSection(task, designWritten);
        };
    }

    private static String reviewPlanSection(WorkerTaskResponse task, String baseSha) {
        // 머리·소개·커밋/검증 지시는 직접 방식과 같은 틀(WorkerMainLoop.renderPlanSection)이고 마지막 줄만 다르다. 계획이 없으면 빈 문자열
        String plan = WorkerMainLoop.renderPlanSection(task, REVIEW_LAST_BULLET);
        boolean hasPlan = !plan.isEmpty();
        String planBlock = hasPlan ? plan : NO_PLAN_NOTE;
        String requirements = hasPlan
                ? "The approved plan is in `" + ImplementationScratch.planPath(task.id()) + "`. Read it in full."
                : "There is no plan file. The task requirements are quoted under [DESCRIPTION] above; review against them.";
        String reviewer = REVIEWER.replace("__REQUIREMENTS__", requirements).replace("__BASE_SHA__", baseSha);
        return planBlock + REVIEW_SECTION.replace("__REVIEWER__", reviewer);
    }

    private static String sddPlanSection(WorkerTaskResponse task, boolean designWritten) {
        String design = designWritten
                ? " 설계 문서(스펙)는 `" + ImplementationScratch.designPath(task.id()) + "`입니다."
                : "";
        return """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 서브에이전트 방식으로 구현)
                요청자와의 인터뷰로 확정된 구현 계획이 `%s` 파일에 있습니다(git 추적 제외 경로).%s
                - superpowers:subagent-driven-development 스킬로 이 계획 파일을 처음부터 끝까지 실행하세요. brainstorming·writing-plans는 이미 끝났으니 다시 하지 마세요.
                - 현재 디렉터리가 이미 이 작업 전용 git worktree이고, 체크아웃된 브랜치가 작업 브랜치입니다. 새 worktree나 브랜치를 만들지 말고 여기서 진행하세요.
                - 태스크별 커밋은 계획대로 해도 됩니다. 단 git push, PR/MR 생성, 브랜치 병합·삭제·리베이스는 하지 마세요 — 워커가 마지막에 처리합니다.
                - 마지막 superpowers:finishing-a-development-branch 단계는 실행하지 마세요(사람에게 선택을 묻는 단계입니다). 최종 리뷰까지 끝나면 결과를 요약해 출력하고 종료하세요.
                - 이 세션은 비대화식이라 질문에 답할 사람이 없습니다. 사람에게 묻지 말고, 판단이 필요하면 SDD 규칙대로 원장에 Ruling으로 남기고 계속하세요.
                - 리뷰 지적은 Critical·Important 가운데 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 고치세요. 계획에 없는 동작 규칙 변경(입력 검증 강화, 정규화 추가 등)은 하지 마세요.
                - 끝내기 전에 원장의 Ruling·보류(parked) 항목과 고치지 않은 지적을 이유와 함께 `.netis/review-notes.md`에 한국어로 정리하세요. 이 파일은 워커가 PR/MR 본문에 붙인 뒤 지우며 커밋되지 않습니다.
                """.formatted(ImplementationScratch.planPath(task.id()), design);
    }

    /** PR/MR 본문 방식 줄 (스펙 §6.7) — 2회째부터 회차, 낮춰서 진행했으면 요청 방식과 이유. */
    static String modeLine(ImplementationMode requested, ImplementationMode effective, int attempt,
                           String fallbackReason) {
        StringBuilder line = new StringBuilder("- **구현 방식**: ").append(effective.label());
        if (attempt >= 2) line.append(" (").append(attempt).append("회째)");
        if (fallbackReason != null && requested != null && requested != effective) {
            line.append(" (").append(requested.label()).append(" 요청 — ")
                    .append(withRo(fallbackReason)).append(' ')
                    .append(withRo(effective.label())).append(" 진행)");
        }
        return line.append('\n').toString();
    }

    /** 조사 '(으)로' — 받침 없음·ㄹ받침·한글 아님이면 '로', 그 밖의 받침이면 '으로'. */
    static String withRo(String word) {
        if (word == null || word.isEmpty()) return "";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return word + "로";
        int finalConsonant = (last - 0xAC00) % 28;
        return word + (finalConsonant == 0 || finalConsonant == 8 ? "로" : "으로");
    }

    private static String resource(String path) {
        try (InputStream in = ImplementationPrompts.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("프롬프트 리소스 없음: " + path);
            // Windows 작업본(core.autocrlf)에서 빌드해도 프롬프트 줄바꿈은 LF로
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 리소스 머리의 `<!-- … -->`(출처·라이선스)를 뺀다 — 프롬프트에는 리뷰어 지시만. */
    static String stripLeadingComment(String text) {
        if (!text.startsWith("<!--")) return text;
        int end = text.indexOf("-->");
        if (end < 0) return text;
        String rest = text.substring(end + 3);
        return rest.startsWith("\n") ? rest.substring(1) : rest;
    }
}
