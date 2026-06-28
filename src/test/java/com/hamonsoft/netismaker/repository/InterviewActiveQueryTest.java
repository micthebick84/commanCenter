package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findActiveByRequester — 본인의 비종료 세션만, lastActivityAt DESC. 실 Postgres 필요.
 * RUN_TESTCONTAINERS=true에서만 실행 (H2 미사용 — 기존 통합 테스트와 동일 게이트).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewActiveQueryTest {

    @Autowired private InterviewService service;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void clean() { sessionRepo.deleteAll(); }

    private InterviewSession create(String requester, String repo) {
        // repoCatalogId=1 → V14 seed (Netis7.0). repo arg unused after Task 5 migration.
        return service.create(new CreateInterviewRequest(1L, "main", "T", "d", List.of(), null, null), requester);
    }

    @Test
    void returns_only_active_sessions_of_the_requester() {
        InterviewSession s1 = create("user1", "owner/a"); // QUEUED (active)
        InterviewSession s2 = create("user1", "owner/b"); // QUEUED (active)
        create("user2", "owner/c");                       // 다른 사용자 — 제외
        // s2를 취소(terminal)로 — active에서 빠져야
        service.cancel(s2.getId(), "user1", false);

        List<InterviewSession> active = sessionRepo.findActiveByRequester("user1");

        assertThat(active).extracting(InterviewSession::getId).containsExactly(s1.getId());
    }

    @Test
    void orders_by_last_activity_desc() {
        InterviewSession a = create("user1", "owner/a");
        InterviewSession b = create("user1", "owner/b");
        // 명확한 순서 검증을 위해 lastActivityAt에 간격: a가 더 오래됨, b가 더 최근.
        OffsetDateTime now = OffsetDateTime.now();
        a.setLastActivityAt(now.minusSeconds(60));
        b.setLastActivityAt(now);
        sessionRepo.save(a);
        sessionRepo.save(b);

        List<InterviewSession> active = sessionRepo.findActiveByRequester("user1");

        // DESC: 최근 활동(b)이 먼저
        assertThat(active).extracting(InterviewSession::getId)
                .containsExactly(b.getId(), a.getId());
    }
}
