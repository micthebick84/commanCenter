package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기능을 다 알리지 않은 워커(참조·수정 회차를 모르는 구버전)나 종류를 고른 워커의 claim 큐(네이티브)는
 * TaskRepository.CLAIMABLE_STATUSES를 쓰고, 두 기능을 다 아는 워커의 전체 큐(JPQL)는 상태를 쿼리 문자열에 적는다 —
 * 두 목록이 어긋나면 구버전 워커만 어떤 대기 상태를 영영 못 가져가거나 더 가져간다.
 * JPQL 문자열을 읽어 두 목록이 같은지 본다 (스펙 2026-10-24 §4.7). 네이티브 쿼리가 JPQL과 같은 순서·잠금이고
 * 두 거름 조건(참조·수정 회차)을 모두 갖고 있는지도 본다 (스펙 2026-11-03 §7) — 한 경로에서만 조건이 빠지면 그 경로의
 * 구버전 워커가 회차를 가져가 base에서 새 브랜치를 딴다.
 */
class TaskRepositoryClaimableStatusesTest {

    private static final Pattern STATUS = Pattern.compile("TaskStatus\\.([A-Z_]+)");

    @Test
    void the_native_claim_queue_names_exactly_the_jpql_claimable_statuses() throws Exception {
        String jpql = TaskRepository.class.getMethod("findClaimableForUpdateSkipLocked", Pageable.class)
                .getAnnotation(Query.class).value();
        List<TaskStatus> named = new ArrayList<>();
        Matcher m = STATUS.matcher(jpql);
        while (m.find()) named.add(TaskStatus.valueOf(m.group(1)));

        assertThat(named).isNotEmpty().doesNotHaveDuplicates();
        assertThat(TaskRepository.CLAIMABLE_STATUSES).containsExactlyInAnyOrderElementsOf(named);
    }

    @Test
    void the_native_claim_queue_keeps_the_jpql_order_and_lock_and_both_filters() throws Exception {
        String sql = TaskRepository.class
                .getMethod("findClaimableAmong", java.util.Collection.class, String.class, boolean.class, boolean.class)
                .getAnnotation(Query.class).value();

        assertThat(sql)
                .contains("t.status IN (:claimable)")
                .contains("t.deleted_at IS NULL")
                .contains("ORDER BY t.created_at ASC")
                .contains("LIMIT 1")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("t.ref_repos <> CAST('[]' AS jsonb)")
                .contains("t.active_fix_request_id IS NOT NULL")
                .contains(":includeRefImplementations")
                .contains(":includeFixRounds");
        // 거름은 구현대기(:approved)에만 — 회차 안의 자동 재배포(배포대기 등)는 막지 않는다
        assertThat(sql.split(Pattern.quote("t.status = :approved"), -1)).hasSize(3);
    }
}
