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
 * 참조 지원을 알리지 않은 워커의 claim 큐(네이티브)는 TaskRepository.CLAIMABLE_STATUSES를 쓰고, 전체 큐(JPQL)는 상태를
 * 쿼리 문자열에 적는다 — 두 목록이 어긋나면 구버전 워커만 어떤 대기 상태를 영영 못 가져가거나 더 가져간다.
 * JPQL 문자열을 읽어 두 목록이 같은지 본다 (스펙 2026-10-24 §4.7).
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
}
