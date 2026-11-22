import { describe, expect, it } from 'vitest';
import { createSuggestMcp, suggestSection } from '../src/sdk/suggestMcp.js';
import type { SuggestCandidates } from '../src/types.js';

type Tools = NonNullable<ReturnType<typeof createSuggestMcp>>['tools'];
const call = async (tools: Tools, name: string, args: Record<string, unknown>) => {
  const t = tools.find((x) => x.name === name);
  if (!t) throw new Error(`도구 없음: ${name}`);
  return (await t.handler(args as never, {})) as { content: Array<{ text: string }>; isError?: boolean };
};
const text = (r: { content: Array<{ text: string }> }) => r.content[0]!.text;

const OK = '제안을 남겼습니다 — 사용자가 연결하면 다음 답변부터 쓸 수 있습니다. 지금은 코드로 확인한 범위에서 답을 마무리하세요.';
const dbs = [
  { id: 4, name: '시험 DB', dbType: 'POSTGRESQL' },
  { id: 5, name: '두번째 DB', dbType: 'MYSQL' },
];
const refs = [
  { catalogId: 11, alias: 'Netis7.0', hint: '공유 모듈' },
  { catalogId: 12, alias: 'acme/x' },
  { catalogId: 13, alias: 'acme/y' },
];
const both: SuggestCandidates = { dbCandidates: dbs, refCandidates: refs };
const make = (c: SuggestCandidates = both) => createSuggestMcp(c)!;

describe('createSuggestMcp', () => {
  it('후보가 없으면 null이다', () => {
    for (const c of [null, undefined, {}, { dbCandidates: [], refCandidates: [] }]) {
      expect(createSuggestMcp(c)).toBeNull();
    }
  });

  it('DB 후보만 있으면 suggest_db만 만든다', () => {
    const m = make({ dbCandidates: dbs });
    expect(m.tools.map((t) => t.name)).toEqual(['suggest_db']);
    expect(Object.keys(m.servers)).toEqual(['suggest']);
    expect(m.tools[0]!._meta?.['anthropic/alwaysLoad']).toBe(true);
  });

  it('레포 후보만 있으면 suggest_ref_repo만 만든다', () => {
    const m = make({ refCandidates: refs });
    expect(m.tools.map((t) => t.name)).toEqual(['suggest_ref_repo']);
    expect(m.tools[0]!._meta?.['anthropic/alwaysLoad']).toBe(true);
  });

  it('DB 제안을 수집한다', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_db', { reason: ' 설정 값 확인 ', targets: [' tb_config ', ''], connectionId: 4 });
    expect(r.isError).toBeUndefined();
    expect(text(r)).toBe(OK);
    expect(m.collected()).toEqual([{ kind: 'DB', reason: '설정 값 확인', targets: ['tb_config'], dbConnectionId: 4 }]);
  });

  it('연결을 고르지 않은 DB 제안은 dbConnectionId 키가 없다', async () => {
    const m = make();
    await call(m.tools, 'suggest_db', { reason: '확인' });
    const c = m.collected()[0]!;
    expect(c).toEqual({ kind: 'DB', reason: '확인', targets: [] });
    expect('dbConnectionId' in c).toBe(false);
  });

  it('후보에 없는 연결은 isError이고 수집하지 않는다', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_db', { reason: '확인', connectionId: 99 });
    expect(r.isError).toBe(true);
    expect(text(r)).toBe('후보에 없는 연결입니다: 99');
    expect(m.collected()).toEqual([]);
  });

  it('이유가 비면 isError이고 수집하지 않는다 (suggest_db)', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_db', { reason: '   ' });
    expect(r.isError).toBe(true);
    expect(text(r)).toBe('이유를 적어 주세요');
    expect(m.collected()).toEqual([]);
  });

  it('DB 제안은 한 답변에 한 번이다', async () => {
    const m = make();
    await call(m.tools, 'suggest_db', { reason: '첫째' });
    const r = await call(m.tools, 'suggest_db', { reason: '둘째' });
    expect(r.isError).toBe(true);
    expect(text(r)).toBe('이번 답변에서는 이미 DB 연결을 제안했습니다');
    expect(m.collected()).toHaveLength(1);
  });

  it('이유는 200자, 대상은 5개·80자로 자른다', async () => {
    const m = make();
    const emoji = '😀';
    await call(m.tools, 'suggest_db', {
      reason: emoji.repeat(250),
      targets: Array.from({ length: 7 }, (_, i) => `${emoji.repeat(100)}${i}`),
    });
    const c = m.collected()[0] as { reason: string; targets: string[] };
    expect(Array.from(c.reason)).toHaveLength(200);
    expect(c.reason).toBe(emoji.repeat(200));
    expect(c.targets).toHaveLength(5);
    for (const t of c.targets) expect(t).toBe(emoji.repeat(80));
  });

  it('레포 제안을 수집한다', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 11, reason: ' 호출 대상 ' });
    expect(r.isError).toBeUndefined();
    expect(text(r)).toBe(OK);
    expect(m.collected()).toEqual([{ kind: 'REF_REPO', reason: '호출 대상', repoCatalogId: 11 }]);
  });

  it('후보에 없는 레포는 isError다', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 99, reason: '근거' });
    expect(r.isError).toBe(true);
    expect(text(r)).toBe('후보에 없는 레포입니다: 99');
    expect(m.collected()).toEqual([]);
  });

  it('이유가 비면 isError이고 수집하지 않는다 (suggest_ref_repo)', async () => {
    const m = make();
    const r = await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 11, reason: ' ' });
    expect(r.isError).toBe(true);
    expect(text(r)).toBe('이유를 적어 주세요');
    expect(m.collected()).toEqual([]);
  });

  it('같은 레포는 한 번만, 전체는 2개까지다', async () => {
    const m = make();
    await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 11, reason: 'a' });
    const dup = await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 11, reason: 'b' });
    expect(dup.isError).toBe(true);
    expect(text(dup)).toBe('이미 제안한 레포입니다');
    await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 12, reason: 'c' });
    const third = await call(m.tools, 'suggest_ref_repo', { repoCatalogId: 13, reason: 'd' });
    expect(third.isError).toBe(true);
    expect(text(third)).toBe('이번 답변에서 제안할 수 있는 레포는 2개까지입니다');
    expect(m.collected().map((x) => (x as { repoCatalogId: number }).repoCatalogId)).toEqual([11, 12]);
  });

  it('collected()는 사본을 돌려준다', async () => {
    const m = make();
    await call(m.tools, 'suggest_db', { reason: '확인', targets: ['t'] });
    const first = m.collected();
    first.length = 0;
    (m.collected()[0] as { targets: string[] }).targets.push('x');
    expect(m.collected()).toHaveLength(1);
    expect((m.collected()[0] as { targets: string[] }).targets).toEqual(['t']);
  });
});

describe('suggestSection', () => {
  it('제안 절은 후보가 있는 종류의 줄만 담는다', () => {
    const db = suggestSection({ dbCandidates: dbs });
    expect(db).toContain('[제안 도구]');
    expect(db).toContain('suggest_db:');
    expect(db).not.toContain('suggest_ref_repo:');
    expect(db).not.toContain('함께 볼 수 있는 레포');
    expect(db).toContain('연결할 수 있는 DB: 4 · 시험 DB · POSTGRESQL');
    expect(db.endsWith('\n\n')).toBe(true);
    const ref = suggestSection({ refCandidates: refs });
    expect(ref).toContain('suggest_ref_repo:');
    expect(ref).not.toContain('suggest_db:');
    expect(ref).not.toContain('연결할 수 있는 DB');
  });

  it('제안 절에 hint가 없으면 별칭까지만 쓴다', () => {
    const s = suggestSection({ refCandidates: [{ catalogId: 11, alias: 'Netis7.0' }, { catalogId: 12, alias: 'acme/x', hint: '공유 모듈' }] });
    const lines = s.split('\n');
    expect(lines).toContain('함께 볼 수 있는 레포: 11 · Netis7.0');
    expect(lines).toContain('함께 볼 수 있는 레포: 12 · acme/x · 공유 모듈');
  });

  it('후보가 없으면 제안 절은 빈 문자열이다', () => {
    for (const c of [null, undefined, {}, { dbCandidates: [], refCandidates: [] }]) expect(suggestSection(c)).toBe('');
  });
});
