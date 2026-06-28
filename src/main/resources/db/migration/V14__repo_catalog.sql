-- 관리자 큐레이션 레포 카탈로그. 작업/인터뷰 등록 시 자유 입력 대신 별칭 선택.
-- 정식 식별자 = 전체 Git URL(멀티호스트 대비). owner_repo/host는 업서트 시 파싱해 박제.
CREATE TABLE IF NOT EXISTS com.repo_catalog (
    id              BIGSERIAL PRIMARY KEY,
    alias           VARCHAR(100) NOT NULL UNIQUE,   -- 한글 표시명. 예: 'Netis7.0'
    git_url         TEXT         NOT NULL UNIQUE,    -- 정식 식별자(전체 Git URL)
    host            VARCHAR(30)  NOT NULL DEFAULT 'github',  -- 'github' | 'other'
    owner_repo      VARCHAR(255),                   -- GitHub 파생 owner/repo. 비-GitHub면 NULL
    default_branch  VARCHAR(255),                   -- (선택) 비우면 등록 폼이 ls-remote 기본 사용
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_by      VARCHAR(20)  REFERENCES com."user"(user_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_repo_catalog_enabled ON com.repo_catalog(enabled) WHERE enabled = true;

-- 작업/인터뷰별 선택 레포 스냅샷 (카탈로그 변경/삭제 후에도 이력 보존).
-- githubRepo(owner/repo)는 기존 컬럼 유지 — 파이프라인이 계속 사용. git_url/repo_alias는 표시·감사용.
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS git_url         TEXT,
    ADD COLUMN IF NOT EXISTS repo_alias      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS repo_catalog_id BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL;

ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS git_url         TEXT,
    ADD COLUMN IF NOT EXISTS repo_alias      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS repo_catalog_id BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL;

-- (선택) 첫 실행 시 폼이 비지 않도록 시드 1건. 불필요하면 이 블록 삭제 가능.
INSERT INTO com.repo_catalog (alias, git_url, host, owner_repo, description, enabled)
VALUES ('Netis7.0', 'https://github.com/micthebick84/netis7.0.git', 'github',
        'micthebick84/netis7.0', '교통 분석 데모 앱', true)
ON CONFLICT (alias) DO NOTHING;
