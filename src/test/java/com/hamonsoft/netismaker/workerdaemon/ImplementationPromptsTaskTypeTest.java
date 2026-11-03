package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 프롬프트의 {task_type_section} (스펙 2026-11-03 §5.2·§6.6·§6.8). */
class ImplementationPromptsTaskTypeTest {

    private static final String SCRIPT = "db/symphony/task-42.sql";

    /** 자동 마이그레이션 갈래가 설계 §6.8대로 싣는 세 안내 — 서버가 강제할 수 없어 이 지시가 유일한 전달 경로다. */
    private static final String MIGRATION_SEED_DATA = "가데이터도 레포의 마이그레이션 관례가 허용하면 그 방식으로 넣으세요";
    private static final String MIGRATION_APPLIED_ON_BOOT = "개발 DB에는 시험 컨테이너가 뜰 때 적용됩니다";
    private static final String MIGRATION_KEEP_OLD_COLUMN = "다른 사람 화면을 깨뜨리지 않게 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요";

    @Test
    void 타입절_문구() {
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.BUG_FIX, false, false, SCRIPT)).isEqualTo(
                "## 작업 타입: 버그 픽스\n먼저 계획의 재현 방법으로 오류를 재현하고 원인을 확인한 뒤 고치세요. 고친 뒤 같은 오류가 다시 나지 않는지 확인하세요(가능하면 회귀 테스트 추가).\n");
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, false, false, SCRIPT)).isEqualTo(
                "## 작업 타입: DB 작업\n이 구현에는 개발 DB 쓰기 도구가 없습니다. 계획의 SQL을 실행하지 말고 `db/symphony/task-42.sql` 파일에 실행 순서대로 적으세요(문장마다 끝에 `;`). 코드 변경은 필요할 때만 하세요.\n");
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.FEATURE_CHANGE, false, false, "x")).isEmpty();
    }

    @Test
    void 추가_지시가_없는_타입은_빈_문자열() {
        assertThat(ImplementationPrompts.taskTypeSection(null, false, false, SCRIPT)).isEmpty();
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.NEW_FEATURE, false, false, SCRIPT)).isEmpty();
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.FEATURE_CHANGE, true, true, SCRIPT)).isEmpty();
    }

    @Test
    void 버그픽스_절은_DB_플래그와_무관() {
        String plain = ImplementationPrompts.taskTypeSection(TaskType.BUG_FIX, false, false, SCRIPT);

        assertThat(ImplementationPrompts.taskTypeSection(TaskType.BUG_FIX, true, false, SCRIPT)).isEqualTo(plain);
        assertThat(ImplementationPrompts.taskTypeSection(TaskType.BUG_FIX, false, true, SCRIPT)).isEqualTo(plain);
    }

    /** F9 — 세 갈래(파일에 적기 / execute / 마이그레이션 관례)는 서명을 만든 이 Task가 모두 구현한다. */
    @Test
    void DB작업은_쓰기도구_유무와_자동마이그레이션에_따라_세_갈래() {
        String noTool = ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, false, false, SCRIPT);
        String withTool = ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, true, false, SCRIPT);
        String migration = ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, false, true, SCRIPT);

        assertThat(noTool).startsWith("## 작업 타입: DB 작업\n").contains("`" + SCRIPT + "` 파일에 실행 순서대로");
        assertThat(withTool).startsWith("## 작업 타입: DB 작업\n").contains("`execute` 도구로 한 문장씩")
                .doesNotContain(SCRIPT).doesNotContain("쓰기 도구가 없습니다");
        assertThat(migration).startsWith("## 작업 타입: DB 작업\n").contains("마이그레이션 관례대로")
                .doesNotContain(SCRIPT).doesNotContain("`execute`");
        assertThat(migration).contains(MIGRATION_SEED_DATA).contains(MIGRATION_APPLIED_ON_BOOT).contains(MIGRATION_KEEP_OLD_COLUMN);
        assertThat(withTool).isNotEqualTo(noTool);
        assertThat(migration).isNotEqualTo(noTool).isNotEqualTo(withTool);
        assertThat(noTool).endsWith("\n");
        assertThat(withTool).endsWith("\n");
        assertThat(migration).endsWith("\n");
    }

    /** 쓰기 도구와 자동 마이그레이션은 함께 켜질 수 없다(조건 5) — 그래도 겹치면 마이그레이션이 이긴다(DB에 직접 쓰지 않는 쪽). */
    @Test
    void 자동마이그레이션이_쓰기도구보다_우선() {
        String both = ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, true, true, SCRIPT);

        assertThat(both).isEqualTo(ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, false, true, SCRIPT));
        assertThat(both).doesNotContain("`execute`")
                .contains(MIGRATION_SEED_DATA).contains(MIGRATION_APPLIED_ON_BOOT).contains(MIGRATION_KEEP_OLD_COLUMN);
    }

    @Test
    void 기본_스크립트_경로() {
        assertThat(ImplementationPrompts.defaultScriptPath(42L)).isEqualTo("db/symphony/task-42.sql");
        assertThat(ImplementationPrompts.defaultScriptPath(7)).isEqualTo("db/symphony/task-7.sql");
    }
}
