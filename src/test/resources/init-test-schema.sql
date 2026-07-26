-- Testcontainers 초기 스키마. com."user" 테이블은 netis-backend 소유라
-- 통합 테스트에선 최소 컬럼만 만들어두고 시드.
CREATE SCHEMA IF NOT EXISTS com;

-- com."user" 실제 PK는 user_id VARCHAR(20). 테스트엔 password NOT NULL 컬럼만 만족.
CREATE TABLE IF NOT EXISTS com."user" (
    user_id     VARCHAR(20) PRIMARY KEY,
    user_name   VARCHAR(30) NOT NULL,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100)
);

INSERT INTO com."user" (user_id, user_name, password, email) VALUES
    ('user1',  'User 1', 'x', 'user1@hamonsoft.co.kr'),
    ('user2',  'User 2', 'x', 'user2@hamonsoft.co.kr'),
    ('admin1', 'Admin',  'x', 'admin1@hamonsoft.co.kr'),
    ('admin',  'Admin',  'x', 'admin@hamonsoft.co.kr')
ON CONFLICT (user_id) DO NOTHING;
