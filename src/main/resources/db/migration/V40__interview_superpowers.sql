-- 작업 인터뷰의 superpowers 켜기/끄기 (설계 2026-11-03-interview-superpowers-toggle §4).
-- 승인 때 관리자가 고른 값을 세션에 저장한다 — 승인마다 세션이 새로 만들어지므로 인터뷰 실패 뒤 다시 승인하면 다시 고를 수 있다.
-- false = 간단 인터뷰(superpowers 플러그인 없이 질문을 최대 한 번 모아서 묻고 바로 계획). 기존 세션과 질문 세션은 true다
-- (질문 세션은 인터뷰 서비스가 이 값을 보지 않는다).
ALTER TABLE com.interview_session ADD COLUMN superpowers_enabled BOOLEAN NOT NULL DEFAULT true;
