-- worker가 보고하는 활성 MCP 서버 이름 목록.
-- UI(워커 헬스 페이지, 작업 등록 다이얼로그)에서 어떤 도구가 분석 시 사용 가능한지 노출하기 위해 도입.
-- 헬스 페이로드라 nullable; 빈 배열은 "워커가 보고했지만 MCP 0개".
ALTER TABLE com.worker_heartbeat
    ADD COLUMN IF NOT EXISTS mcps jsonb NOT NULL DEFAULT '[]'::jsonb;
