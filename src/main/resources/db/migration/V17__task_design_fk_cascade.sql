-- V17: task_design.task_id FK에 ON DELETE CASCADE 부여 — task_analysis(V1)와 대칭화.
-- 운영은 soft-delete(deleted_at)라 실害는 없었으나, 하드 삭제 시 자식 row가
-- 부모 task 삭제를 막는 비대칭(최종리뷰 트리아지 항목) 제거.
ALTER TABLE com.task_design
    DROP CONSTRAINT IF EXISTS task_design_task_id_fkey;
ALTER TABLE com.task_design
    ADD CONSTRAINT task_design_task_id_fkey
        FOREIGN KEY (task_id) REFERENCES com.task(id) ON DELETE CASCADE;
