package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.util.DbScriptDir;

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

    private static final String BUG_FIX_SECTION = "## 작업 타입: 버그 픽스\n"
            + "먼저 계획의 재현 방법으로 오류를 재현하고 원인을 확인한 뒤 고치세요. "
            + "고친 뒤 같은 오류가 다시 나지 않는지 확인하세요(가능하면 회귀 테스트 추가).\n";

    /**
     * {task_type_section} — 타입별 추가 지시(스펙 2026-11-03 §5.2). 버그 픽스와 DB 작업만 있고, 타입 없음·신규 개발·기능 수정은
     * 빈 문자열이라 구현 프롬프트가 타입이 생기기 전과 바이트까지 같다. 절은 제목 줄로 시작해 줄바꿈으로 끝난다
     * (템플릿은 {@code {plan_section}{task_type_section}}처럼 계획 절 바로 뒤에 둔다).
     *
     * <p>DB 작업의 세 갈래 — 쓰기 도구가 없으면 SQL을 scriptPath 파일에 적게 하고(§6.6 "쓰기를 못 쓸 때"), 있으면 {@code execute}로
     * 한 문장씩 적용하게 하고(§5.2), 자동 마이그레이션 레포(§6.8)면 레포의 마이그레이션 관례대로 파일을 만들게 한다(가데이터도 관례가 허용하면 그 방식으로,
     * 개발 DB에는 시험 컨테이너가 뜰 때 적용된다는 것, 다른 사람 화면을 깨뜨리지 않게 옛 컬럼은 두고 새 컬럼을 추가하라는 기준까지 —
     * 서버가 강제할 수 없는 기준이라 이 지시가 유일한 전달 경로다). 쓰기 도구와
     * 자동 마이그레이션은 함께 켜질 수 없지만(§6.1 조건 5) 겹쳐 들어오면 DB에 직접 쓰지 않는 쪽(마이그레이션)을 따른다.
     *
     * @param writeTools        이 구현에 개발 DB 쓰기 도구({@code execute})가 있는가
     * @param migrationManaged  대상 레포가 자동 마이그레이션 레포인가
     * @param scriptPath        쓰기 도구가 없을 때 세션이 SQL을 적을 파일(worktree 상대 경로)
     */
    static String taskTypeSection(TaskType type, boolean writeTools, boolean migrationManaged, String scriptPath) {
        if (type == TaskType.BUG_FIX) return BUG_FIX_SECTION;
        if (type != TaskType.DB_WORK) return "";
        if (migrationManaged) {
            return "## 작업 타입: DB 작업\n"
                    + "이 레포는 앱이 뜰 때 마이그레이션 파일을 DB에 자동 적용합니다. "
                    + "계획의 SQL을 DB에 직접 실행하지 말고 레포의 마이그레이션 관례대로 파일로 만드세요. "
                    + "가데이터도 레포의 마이그레이션 관례가 허용하면 그 방식으로 넣으세요. "
                    + "개발 DB에는 시험 컨테이너가 뜰 때 적용됩니다. "
                    + "같은 DB를 다른 사람도 씁니다. 기존 컬럼을 지우거나 이름·타입을 바꿔야 하면 "
                    + "다른 사람 화면을 깨뜨리지 않게 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요. "
                    + "코드 변경은 필요할 때만 하세요.\n";
        }
        if (writeTools) {
            return "## 작업 타입: DB 작업\n"
                    + "계획의 SQL을 `execute` 도구로 한 문장씩 적용하고, 적용할 때마다 `query`·`describe_table`로 결과를 확인하세요. "
                    + "코드 변경은 필요할 때만 하세요.\n";
        }
        return "## 작업 타입: DB 작업\n"
                + "이 구현에는 개발 DB 쓰기 도구가 없습니다. 계획의 SQL을 실행하지 말고 `" + scriptPath
                + "` 파일에 실행 순서대로 적으세요(문장마다 끝에 `;`). 코드 변경은 필요할 때만 하세요.\n";
    }

    /**
     * 작업의 기본 SQL 스크립트 경로 — {@code db/symphony/task-{id}.sql}. 세션이 쓴 파일의 강제 추가도 이 경로를 본다. 기본 폴더 규칙은
     * API의 카탈로그 저장과 워커가 같이 쓰는 {@link DbScriptDir} 한 곳이다 — 두 상수로 갈라지면 기본 폴더를 바꿀 때 워커 파일과 세션
     * 파일의 경로가 어긋난다.
     */
    static String defaultScriptPath(long taskId) {
        return DbScriptDir.resolve(null, taskId);
    }

    /**
     * 쓰기 도구 없이 이전 쓰기 기록이 있을 때 세션이 SQL을 적는 파일 — 끝의 {@code .sql}을 {@code -manual.sql}로 바꾼다
     * ({@code db/symphony/task-42.sql} → {@code db/symphony/task-42-manual.sql}). 같은 경로를 주면 세션이 적은 새 SQL을 워커가
     * 누적 기록으로 덮어써 PR에서 사라진다(계획 "설계와 다름 ②"). {@code .sql}로 끝나지 않으면 뒤에 붙인다 — 어떤 경우에도 원래 경로와 다르다.
     */
    static String manualScriptPath(String scriptPath) {
        return scriptPath.endsWith(".sql")
                ? scriptPath.substring(0, scriptPath.length() - ".sql".length()) + "-manual.sql"
                : scriptPath + "-manual.sql";
    }

    /**
     * 이 구현에서 세션이 SQL을 직접 적는 파일 — {@link #taskTypeSection}의 {@code scriptPath}로 쓴다. 쓰기 도구가 없고 이전 시도·회차가 남긴
     * 쓰기 기록이 있으면 {@link #manualScriptPath}, 아니면 워커가 만드는 파일과 같은 {@code db.scriptPath()}.
     */
    static String sessionScriptPath(DbWriteSpec db, boolean writeTools) {
        return !writeTools && db.priorWrites() ? manualScriptPath(db.scriptPath()) : db.scriptPath();
    }

    /** {commit_rule} — SDD만 태스크별 커밋, 나머지는 기존 문구(라이브 yml과 같은 글자). */
    static String commitRule(ImplementationMode effective) {
        return effective == ImplementationMode.SDD ? SDD_COMMIT_RULE : DIRECT_COMMIT_RULE;
    }

    /**
     * SDD 계획 절의 참조 줄 (스펙 2026-10-24 §6.5) — SDD 지시서(task-brief)는 `Task N` 사이 줄만 뽑아 계획 파일 머리의
     * 참조 절을 놓치므로, 서브에이전트에게 그 절을 넘기라고 한다. 참조가 있을 때만.
     */
    static final String SDD_REF_LINE =
            "- 서브에이전트(구현·리뷰)에게 일을 맡길 때 계획 파일 머리의 \"참조 repo\" 절을 그대로 함께 넘긴다.\n";

    /** {plan_section} — 참조 없는 구현. */
    static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha,
                              boolean designWritten) {
        return planSection(task, effective, baseSha, designWritten, false);
    }

    /** {plan_section} — 계획 파일 머리에 인터뷰 목업 절이 없는 구현(mockupsInPlanFile=false). */
    static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha,
                              boolean designWritten, boolean refsInPlanFile) {
        return planSection(task, effective, baseSha, designWritten, refsInPlanFile, false);
    }

    /**
     * {plan_section}. designWritten = SDD에 설계 파일을 알릴지(실제로 썼을 때만), refsInPlanFile = 계획 파일 머리에 참조 절을
     * 넣었는지(SDD만 참조 줄을 더한다), mockupsInPlanFile = 계획 파일 머리에 넘긴 인터뷰 목업 절을 넣었는지(SDD만 목업 줄을 더한다 —
     * 제외 번호만 있으면 머리가 그 줄뿐이라 거짓으로 넘긴다). 리뷰 방식은 claim의 목업이 있을 때 리뷰어 요구 한 줄을 더한다
     * (설계 2026-11-03 §7, 계획 "설계와 다름 ②" — SDD 리뷰어에는 계획 파일 머리와 SDD 줄로 전한다).
     */
    static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha,
                              boolean designWritten, boolean refsInPlanFile, boolean mockupsInPlanFile) {
        return switch (effective) {
            case DIRECT -> WorkerMainLoop.renderPlanSection(task);
            case REVIEW -> reviewPlanSection(task, baseSha);
            case SDD -> sddPlanSection(task, designWritten, refsInPlanFile, mockupsInPlanFile);
        };
    }

    private static String reviewPlanSection(WorkerTaskResponse task, String baseSha) {
        // 머리·소개·커밋/검증 지시는 직접 방식과 같은 틀(WorkerMainLoop.renderPlanSection)이고 마지막 줄만 다르다. 계획이 없으면 빈 문자열
        String plan = WorkerMainLoop.renderPlanSection(task, REVIEW_LAST_BULLET);
        boolean hasPlan = !plan.isEmpty();
        String planBlock = hasPlan ? plan : NO_PLAN_NOTE;
        // 목업 요구는 계획 파일 머리의 "인터뷰 목업" 절을 가리키므로 계획 파일이 있을 때만 붙인다. 목업이 없으면 ""
        String requirements = hasPlan
                ? "The approved plan is in `" + ImplementationScratch.planPath(task.id()) + "`. Read it in full."
                        + InterviewMockupPrompts.reviewerRequirement(task.interviewMockups(), hasApprovedDesign(task))
                : "There is no plan file. The task requirements are quoted under [DESCRIPTION] above; review against them.";
        return planBlock + REVIEW_SECTION.replace("__REVIEWER__", reviewer(requirements, baseSha));
    }

    /** 승인된 디자인이 있는가 — 구현 프롬프트의 디자인 절(WorkerMainLoop.renderDesignSection)과 같은 판정. */
    static boolean hasApprovedDesign(WorkerTaskResponse task) {
        return task.designMarkdown() != null && !task.designMarkdown().isBlank();
    }

    /**
     * 리뷰어 지시(code-reviewer.md 사본)에 리뷰 기준(requirements)과 기준 커밋을 채운 것 — 구현 리뷰와 수정 회차 리뷰
     * (FixRoundPrompts)가 같이 쓴다. 리뷰어는 baseSha 대비 커밋되지 않은 변경을 본다.
     */
    static String reviewer(String requirements, String baseSha) {
        return REVIEWER.replace("__REQUIREMENTS__", requirements).replace("__BASE_SHA__", baseSha);
    }

    private static String sddPlanSection(WorkerTaskResponse task, boolean designWritten, boolean refsInPlanFile,
                                         boolean mockupsInPlanFile) {
        String design = designWritten
                ? " 설계 문서(스펙)는 `" + ImplementationScratch.designPath(task.id()) + "`입니다."
                : "";
        return """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 서브에이전트 방식으로 구현)
                요청자와의 인터뷰로 확정된 구현 계획이 `%s` 파일에 있습니다(git 추적 제외 경로).%s
                - superpowers:subagent-driven-development 스킬로 이 계획 파일을 처음부터 끝까지 실행하세요. brainstorming·writing-plans는 이미 끝났으니 다시 하지 마세요.
                - 계획이 작아도 SDD의 리뷰를 빼지 마세요. 구현 서브에이전트가 끝날 때마다 태스크 리뷰 서브에이전트를, 작업이 모두 끝나면 전체 변경의 최종 리뷰 서브에이전트를 스킬이 정한 대로 각각 따로 띄웁니다. 리뷰를 건너뛰거나 구현·다른 리뷰와 합치지 마세요.
                - 현재 디렉터리가 이미 이 작업 전용 git worktree이고, 체크아웃된 브랜치가 작업 브랜치입니다. 새 worktree나 브랜치를 만들지 말고 여기서 진행하세요.
                - 태스크별 커밋은 계획대로 해도 됩니다. 단 git push, PR/MR 생성, 브랜치 병합·삭제·리베이스는 하지 마세요 — 워커가 마지막에 처리합니다.
                - 마지막 superpowers:finishing-a-development-branch 단계는 실행하지 마세요(사람에게 선택을 묻는 단계입니다). 최종 리뷰까지 끝나면 결과를 요약해 출력하고 종료하세요.
                - 이 세션은 비대화식이라 질문에 답할 사람이 없습니다. 사람에게 묻지 말고, 판단이 필요하면 SDD 규칙대로 원장에 Ruling으로 남기고 계속하세요.
                - 리뷰 지적은 Critical·Important 가운데 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 고치세요. 계획에 없는 동작 규칙 변경(입력 검증 강화, 정규화 추가 등)은 하지 마세요.
                - 끝내기 전에 원장의 Ruling·보류(parked) 항목과 고치지 않은 지적을 이유와 함께 `.netis/review-notes.md`에 한국어로 정리하세요. 이 파일은 워커가 PR/MR 본문에 붙인 뒤 지우며 커밋되지 않습니다.
                """.formatted(ImplementationScratch.planPath(task.id()), design)
                + (refsInPlanFile ? SDD_REF_LINE : "")
                + (mockupsInPlanFile ? InterviewMockupPrompts.SDD_MOCKUP_LINE : "");
    }

    /** PR/MR 본문 방식 줄 (스펙 §6.7) — 2회째부터 회차, 낮춰서 진행했으면 요청 방식과 이유. 마크다운 목록 항목이다. */
    static String modeLine(ImplementationMode requested, ImplementationMode effective, int attempt,
                           String fallbackReason) {
        return "- **구현 방식**: " + modeSummary(requested, effective, attempt, fallbackReason) + "\n";
    }

    /**
     * modeLine과 같은 내용의 평문 — 실패 사유 끝에 붙인다. 실패 사유는 화면에 마크다운이 아니라 평문으로 보여서
     * `- **…**` 기호가 그대로 드러나기 때문이다(PR 본문은 마크다운으로 그려지니 modeLine을 쓴다).
     */
    static String modeNote(ImplementationMode requested, ImplementationMode effective, int attempt,
                           String fallbackReason) {
        return "구현 방식: " + modeSummary(requested, effective, attempt, fallbackReason);
    }

    private static String modeSummary(ImplementationMode requested, ImplementationMode effective, int attempt,
                                      String fallbackReason) {
        StringBuilder text = new StringBuilder(effective.label());
        if (attempt >= 2) text.append(" (").append(attempt).append("회째)");
        if (fallbackReason != null && requested != null && requested != effective) {
            text.append(" (").append(requested.label()).append(" 요청 — ")
                    .append(withRo(fallbackReason)).append(' ')
                    .append(withRo(effective.label())).append(" 진행)");
        }
        return text.toString();
    }

    /** 조사 '(으)로' — 받침 없음·ㄹ받침·한글 아님이면 '로', 그 밖의 받침이면 '으로'. */
    static String withRo(String word) {
        if (word == null || word.isEmpty()) return "";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return word + "로";
        int finalConsonant = (last - 0xAC00) % 28;
        return word + (finalConsonant == 0 || finalConsonant == 8 ? "로" : "으로");
    }

    /** 클래스패스 프롬프트 리소스(UTF-8, 줄바꿈 LF) — FixRoundPrompts도 쓴다. 없으면 IllegalStateException. */
    static String resource(String path) {
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
