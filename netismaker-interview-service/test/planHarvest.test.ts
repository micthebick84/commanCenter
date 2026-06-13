import { describe, expect, it } from 'vitest';
import { harvestPlan, HarvestError } from '../src/runner/planHarvest.js';

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
