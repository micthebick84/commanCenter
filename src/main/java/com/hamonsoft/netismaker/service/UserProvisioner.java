package com.hamonsoft.netismaker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * netis-auth에서 승인된 사용자의 com."user" 행을 첫 API 요청 때 만든다.
 *
 * 작업·질문·첨부 테이블이 com."user"(user_id)를 FK로 참조해, 행이 없으면 등록이 500으로 실패한다.
 * com."user"는 netis-backend 공용 테이블 — 기존 행은 건드리지 않고(ON CONFLICT DO NOTHING),
 * password는 NOT NULL이라 로그인에 쓰이지 않는 표식을 넣는다. 확인한 username은 프로세스 안에 기억해
 * 요청마다 DB를 치지 않는다. 실패해도 요청은 계속하고(실제 등록에서 FK 오류로 드러난다),
 * 같은 사용자는 5분간 다시 시도하지 않아 요청마다 INSERT·경고가 반복되지 않게 한다.
 */
@Component
@Profile("api")
public class UserProvisioner {

    static final int MAX_USER_ID_LENGTH = 20;
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    static final String NO_LOGIN_PASSWORD = "!netis-auth";
    static final String INSERT_SQL = "INSERT INTO com.\"user\" (user_id, user_name, password, email) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING";

    private static final Logger log = LoggerFactory.getLogger(UserProvisioner.class);

    private final JdbcTemplate jdbc;
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public UserProvisioner(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    UserProvisioner(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void ensureUser(String username, String email) {
        if (username == null || username.isBlank() || known.contains(username)) return;
        if (username.length() > MAX_USER_ID_LENGTH) {
            log.warn("com.\"user\" 자동 생성 건너뜀 — user_id {}자 초과: {}", MAX_USER_ID_LENGTH, username);
            known.add(username);
            return;
        }
        Instant now = clock.instant();
        Instant retryAt = failedUntil.get(username);
        if (retryAt != null && now.isBefore(retryAt)) return;
        try {
            jdbc.update(INSERT_SQL, username, username, NO_LOGIN_PASSWORD, email);
            known.add(username);
            failedUntil.remove(username);
        } catch (RuntimeException e) {
            failedUntil.put(username, now.plus(RETRY_AFTER));
            log.warn("com.\"user\" 자동 생성 실패 user={}: {}", username, e.getMessage());
        }
    }
}
