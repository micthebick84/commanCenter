package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인터뷰 claim 동시성 — FOR UPDATE SKIP LOCKED가 같은 세션을 두 워커에 안 준다.
 * 실 Postgres 필요(H2는 skip-locked 미지원). RUN_TESTCONTAINERS=true에서만 실행.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewClaimConcurrencyTest {

    @Autowired private InterviewService service;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void clean() {
        sessionRepo.deleteAll();
    }

    @Test
    void concurrent_claims_never_double_assign_a_session() throws Exception {
        int n = 8;
        // init-test-schema.sql seeds com."user"(user_id='user1'); reuse it as requester.
        // service.create()는 사용자당 동시 인터뷰 한도(3)에 걸리므로, claim 동시성이 관심사인
        // 이 테스트는 QUEUED 세션을 엔티티로 직접 저장한다 (InterviewRegisterContractTest 관용구).
        for (int i = 0; i < n; i++) {
            sessionRepo.save(InterviewSession.create("owner/repo", "main", "T" + i, "desc",
                    "user1", List.of(), null, null));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Optional<InterviewClaimResponse>>> futures = new java.util.ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            final String worker = "w" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return service.claim(worker);
            }));
        }
        start.countDown();

        Set<Long> claimedIds = ConcurrentHashMap.newKeySet();
        int claims = 0;
        for (Future<Optional<InterviewClaimResponse>> f : futures) {
            Optional<InterviewClaimResponse> r = f.get(10, TimeUnit.SECONDS);
            if (r.isPresent()) {
                claims++;
                // 같은 세션이 두 번 claim되지 않았는지 — add가 false면 중복
                assertThat(claimedIds.add(r.get().sessionId())).as("세션 중복 claim").isTrue();
            }
        }
        pool.shutdown();

        // n개 세션, n개 워커 → 정확히 n번 claim, 전부 RUNNING
        assertThat(claims).isEqualTo(n);
        assertThat(sessionRepo.findAll())
                .allMatch(s -> s.getStatus() == InterviewStatus.RUNNING);
    }
}
