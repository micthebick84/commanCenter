import { describe, expect, it } from 'vitest';
import { harvestPlan, tryHarvest, HarvestError } from '../src/runner/planHarvest.js';

const transcript =
  '# Design\n\nExport visible columns as CSV.\n\n' +
  '# CSV Export Implementation Plan\n\n## File Structure\n\n- a.ts\n\n' +
  '### Task 1: Build exporter\n- [ ] **Step 1: write test**\n' +
  '### Task 2: Wire button\n- [ ] **Step 1: add button**';

describe('harvestPlan', () => {
  it('splits design vs plan at the "Implementation Plan" header', () => {
    const r = harvestPlan(transcript);
    expect(r.designMarkdown).toContain('Export visible columns');
    expect(r.designMarkdown).not.toContain('Implementation Plan');
    expect(r.planMarkdown).toContain('## File Structure');
  });

  it('extracts task titles into planJson', () => {
    const r = harvestPlan(transcript);
    expect(r.planJson).toEqual([
      { task: 1, title: 'Build exporter' },
      { task: 2, title: 'Wire button' },
    ]);
  });

  it('throws HarvestError when no Implementation Plan header is present', () => {
    expect(() => harvestPlan('# Design\n\nonly design, no plan')).toThrow(HarvestError);
  });

  it('throws HarvestError when plan has zero tasks', () => {
    expect(() => harvestPlan('# Design\nx\n# Foo Implementation Plan\nno tasks here')).toThrow(
      HarvestError,
    );
  });
});

describe('harvestPlan bilingual', () => {
  it('harvests a Korean plan: "# … 구현 계획" header + "### 작업 N:" tasks', () => {
    const t = '# 설계\n\n개요.\n\n# 서버관리 구현 계획\n\n### 작업 1: DTO 정의\n- [ ] a\n\n### 작업 2: API\n- [ ] b';
    const h = harvestPlan(t);
    expect(h.designMarkdown).toContain('# 설계');
    expect(h.planMarkdown).toContain('# 서버관리 구현 계획');
    expect(h.planJson).toEqual([{ task: 1, title: 'DTO 정의' }, { task: 2, title: 'API' }]);
  });

  it('accepts an H2 header and full-width colon', () => {
    const t = '## 구현 계획\n\n### 작업 1： 첫 작업';
    const h = harvestPlan(t);
    expect(h.planJson).toEqual([{ task: 1, title: '첫 작업' }]);
  });

  it('accepts "### 태스크 N:" and "### Task N:" task lines', () => {
    const t = '# 구현 계획\n\n### 태스크 1: 가\n\n### Task 2: na';
    expect(harvestPlan(t).planJson).toEqual([{ task: 1, title: '가' }, { task: 2, title: 'na' }]);
  });

  // 2026-11-11 작업 37(세션 51): 제목 끝의 이슈 번호 꼬리 때문에 계획을 못 알아보고 매 턴 질문으로 보고했다
  it('accepts a trailing bracketed tag after the plan keyword', () => {
    const tasks = '\n\n### 작업 1: 가';
    for (const title of [
      '# OID지표설정 Index 숫자 크기 순 정렬 구현 계획 (LINALIFE2026-10)',
      '# 서버관리 구현 계획 [HIVE-12]',
      '## 서버관리 구현 계획（v2）',
      '# CSV Export Implementation Plan (ABC-1)',
    ]) {
      expect(harvestPlan(`# 설계\n\nx\n\n${title}${tasks}`).planMarkdown.startsWith(title)).toBe(true);
    }
  });

  it('does not treat a heading that merely continues after the keyword as the plan header', () => {
    expect(tryHarvest('## 구현 계획 검토\n\n### 작업 1: 가').ok).toBe(false);
    expect(tryHarvest('# 구현 계획 (초안) 이후 메모\n\n### 작업 1: 가').ok).toBe(false);
  });

  it('still harvests the English structure (regression)', () => {
    const t = '# Design\n\nx\n\n# CSV Export Implementation Plan\n\n### Task 1: do';
    expect(harvestPlan(t).planJson).toEqual([{ task: 1, title: 'do' }]);
  });

  it('throws HarvestError when no plan header present', () => {
    expect(() => harvestPlan('## 설계안 (1/4)\n\n질문입니다')).toThrow(HarvestError);
  });

  it('throws HarvestError when the plan has zero task lines', () => {
    expect(() => harvestPlan('# 구현 계획\n\n작업 항목이 없음')).toThrow(HarvestError);
  });
});

describe('tryHarvest', () => {
  it('returns ok:true with harvest on success', () => {
    const r = tryHarvest('# 구현 계획\n\n### 작업 1: 가');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.harvest.planJson).toEqual([{ task: 1, title: '가' }]);
  });
  it('returns ok:false on HarvestError (no throw)', () => {
    expect(tryHarvest('## 설계안\n\n질문')).toEqual({ ok: false });
  });
});
