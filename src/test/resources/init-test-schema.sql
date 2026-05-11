-- Testcontainers 초기 스키마. com."user" 테이블은 netis-backend 소유라
-- 통합 테스트에선 최소 컬럼만 만들어두고 시드.
CREATE SCHEMA IF NOT EXISTS com;

CREATE TABLE IF NOT EXISTS com."user" (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(100) NOT NULL UNIQUE,
    email       VARCHAR(255)
);

-- 표준 테스트 사용자 시드
INSERT INTO com."user" (id, username, email) VALUES
    (1, 'user1',  'user1@hamonsoft.co.kr'),
    (2, 'user2',  'user2@hamonsoft.co.kr'),
    (9, 'admin1', 'admin1@hamonsoft.co.kr')
ON CONFLICT (id) DO NOTHING;

-- 시퀀스 재정렬 (자동 ID 충돌 방지)
SELECT setval(pg_get_serial_sequence('com."user"', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM com."user"));
