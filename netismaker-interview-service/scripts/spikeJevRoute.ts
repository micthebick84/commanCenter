/**
 * 스파이크 — TypeSafe Jev(System One)가 한국어 질문을 카탈로그 레포로 얼마나 정확히 가르는지 잰다
 * (스펙 docs/superpowers/specs/2026-11-20-question-repo-route-design.md §6.2·§9).
 *
 * 실행:
 *   cd netismaker-interview-service
 *   npx tsx scripts/spikeJevRoute.ts --dry-run   # 입력 검증 + 요청 바디 두 개 출력, 네트워크·키 없음
 *   npx tsx scripts/spikeJevRoute.ts             # 실제 호출(키 필요), 두 변형(별칭만 / 키워드 포함)을 차례로 잰다
 * 입력: %USERPROFILE%\netis-maker\jev-route-spike.json  (repos[{id,alias,hint}], cases[{q,kind,expect}]) — 고객사 이름이 있어 커밋 금지
 * 키:   %USERPROFILE%\netis-maker\typesafe.env 의 TYPESAFE_API_KEY=… 한 줄. 출력하지 않는다.
 * 판정: 임계값은 스펙 §6.2 시작값(mentions·listed-yes ≥ 0.7, listed-no ≤ 0.3, 1위 ≥ 0.5, needs_db ≥ 0.6).
 */
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const ENDPOINT = 'https://api.typesafe.ai/v1/systemone';
const MODEL = 'jev-1.14.0';
const REQUEST_TIMEOUT_MS = 30_000;

const T = { mentions: 0.7, listedYes: 0.7, listedNo: 0.3, top: 0.5, needsDb: 0.6 };

const INSTR = {
  repo: "Which repository is the user's `question` about? Pick the repository whose site, customer, product or version the question refers to.",
  mentions:
    'Does `question` refer by name to a specific site, customer, product, or product version (for example a customer name or a version number)?',
  listed:
    'Is the site, customer, product, or version that `question` refers to one of the entries in `repositories`? Answer no if the question names something that is not in `repositories`, or names nothing specific.',
  needsDb:
    'Would answering `question` require looking at an actual database (table structure, columns, stored rows, or query results) rather than reading source code only?',
};

type Kind = 'site' | 'version' | 'none' | 'unlisted';
type State = 'CONFIDENT' | 'AMBIGUOUS' | 'NO_MATCH';
interface Repo { id: number; alias: string; hint: string }
interface Case {
  q: string;
  kind: Kind;
  expect: { state: State; repoId?: number; alt?: number[]; db: boolean };
}
interface Input { repos: Repo[]; cases: Case[] }

const MIN_KIND: Record<Kind, number> = { site: 8, version: 6, none: 6, unlisted: 4 };
const MIN_DB = 8;

const inputPath = join(homedir(), 'netis-maker', 'jev-route-spike.json');
const keyPath = join(homedir(), 'netis-maker', 'typesafe.env');
const dryRun = process.argv.includes('--dry-run');

function loadInput(): Input {
  const raw = JSON.parse(readFileSync(inputPath, 'utf8')) as Input;
  const errs: string[] = [];
  if (!Array.isArray(raw.repos) || !Array.isArray(raw.cases)) throw new Error('repos/cases 배열이 없습니다');
  const ids = new Set<number>();
  for (const r of raw.repos) {
    if (typeof r.id !== 'number' || !r.alias || typeof r.hint !== 'string') errs.push(`repo 필드 누락: ${JSON.stringify(r.id)}`);
    ids.add(r.id);
  }
  raw.cases.forEach((c, i) => {
    if (!c.q || !c.kind || !c.expect || !c.expect.state || typeof c.expect.db !== 'boolean') errs.push(`case ${i}: 필드 누락`);
    if (!(c.kind in MIN_KIND)) errs.push(`case ${i}: 알 수 없는 kind`);
    if (c.expect.repoId !== undefined && !ids.has(c.expect.repoId)) errs.push(`case ${i}: repoId가 repos에 없음`);
    for (const a of c.expect.alt ?? []) if (!ids.has(a)) errs.push(`case ${i}: alt가 repos에 없음`);
    if ((c.kind === 'site' || c.kind === 'version') && (c.expect.state !== 'CONFIDENT' || c.expect.repoId === undefined))
      errs.push(`case ${i}: ${c.kind}는 CONFIDENT + repoId여야 함`);
    if (c.kind === 'none' && c.expect.state !== 'AMBIGUOUS') errs.push(`case ${i}: none은 AMBIGUOUS여야 함`);
    if (c.kind === 'unlisted' && c.expect.state !== 'NO_MATCH') errs.push(`case ${i}: unlisted는 NO_MATCH여야 함`);
  });
  const counts = countKinds(raw.cases);
  for (const k of Object.keys(MIN_KIND) as Kind[]) if (counts[k] < MIN_KIND[k]) errs.push(`kind ${k} ${counts[k]}개 < 최소 ${MIN_KIND[k]}`);
  const db = raw.cases.filter((c) => c.expect.db).length;
  if (db < MIN_DB) errs.push(`DB 필요 질문 ${db}개 < 최소 ${MIN_DB}`);
  if (errs.length > 0) throw new Error(`입력 검증 실패:\n - ${errs.join('\n - ')}`);
  return raw;
}

function countKinds(cases: Case[]): Record<Kind, number> {
  const c: Record<Kind, number> = { site: 0, version: 0, none: 0, unlisted: 0 };
  for (const x of cases) c[x.kind] += 1;
  return c;
}

function shuffle<T2>(a: T2[]): T2[] {
  const out = [...a];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

function normalizeQuestion(q: string): string {
  return q.replace(/\s+/g, ' ').trim().slice(0, 500);
}

function buildBody(repos: Repo[], question: string, withHints: boolean): Record<string, unknown> {
  const criteria: Record<string, string> = {};
  for (const r of shuffle(repos)) criteria[`r${r.id}`] = withHints && r.hint ? `${r.alias} — ${r.hint}` : r.alias;
  return {
    model: MODEL,
    state: {
      question: normalizeQuestion(question),
      repositories: repos.map((r) => (withHints && r.hint ? { key: `r${r.id}`, name: r.alias, hint: r.hint } : { key: `r${r.id}`, name: r.alias })),
    },
    questions: {
      repo: { type: 'choice', instructions: INSTR.repo, criteria },
      mentions_target: { type: 'noul', instructions: INSTR.mentions },
      target_listed: { type: 'noul', instructions: INSTR.listed },
      needs_db: { type: 'noul', instructions: INSTR.needsDb },
    },
  };
}

function loadKey(): string | undefined {
  if (!existsSync(keyPath)) return undefined;
  for (const line of readFileSync(keyPath, 'utf8').split(/\r?\n/)) {
    const m = /^\s*TYPESAFE_API_KEY\s*=\s*(.*?)\s*$/.exec(line);
    if (m) return m[1].replace(/^["']|["']$/g, '') || undefined;
  }
  return undefined;
}

interface Answer {
  top: number | undefined;
  topP: number;
  mentions: number;
  listed: number;
  needsDb: number;
  ms: number;
  model?: string;
}

async function ask(key: string, body: unknown): Promise<Answer> {
  const started = Date.now();
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if ((res.status === 429 || res.status === 529) && attempt === 0) {
      await new Promise((r) => setTimeout(r, 1000));
      continue;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const j = (await res.json()) as {
      model?: string;
      answers: {
        repo: { probabilities: Record<string, number> };
        mentions_target: { noul: number };
        target_listed: { noul: number };
        needs_db: { noul: number };
      };
    };
    let top: number | undefined;
    let topP = -1;
    for (const [k, p] of Object.entries(j.answers.repo.probabilities)) {
      if (p > topP) { topP = p; top = Number(k.slice(1)); }
    }
    return {
      top,
      topP,
      mentions: j.answers.mentions_target.noul,
      listed: j.answers.target_listed.noul,
      needsDb: j.answers.needs_db.noul,
      ms: Date.now() - started,
      model: j.model,
    };
  }
}

function decide(a: Answer): State {
  if (a.mentions >= T.mentions && a.listed >= T.listedYes && a.topP >= T.top) return 'CONFIDENT';
  if (a.mentions >= T.mentions && a.listed <= T.listedNo) return 'NO_MATCH';
  return 'AMBIGUOUS';
}

const pct = (n: number, d: number): string => (d === 0 ? '-' : `${((100 * n) / d).toFixed(1)}% (${n}/${d})`);

async function runVariant(key: string, input: Input, withHints: boolean): Promise<void> {
  const label = withHints ? '키워드 포함' : '별칭만';
  console.log(`\n===== 변형: ${label} (model ${MODEL}) =====`);
  const alias = new Map(input.repos.map((r) => [r.id, r.alias]));
  const kinds: Kind[] = ['site', 'version', 'none', 'unlisted'];
  const stateOk: Record<Kind, [number, number]> = { site: [0, 0], version: [0, 0], none: [0, 0], unlisted: [0, 0] };
  let topHit = 0, topTotal = 0, dbHit = 0, failures = 0, msSum = 0, answered = 0;
  const noneTop: number[] = [];
  const wrong: string[] = [];
  let seenModel: string | undefined;

  for (const c of input.cases) {
    let a: Answer;
    try {
      a = await ask(key, buildBody(input.repos, c.q, withHints));
    } catch (e) {
      failures += 1;
      wrong.push(`[호출 실패] ${c.q} — ${(e as Error).message}`);
      continue;
    }
    seenModel = a.model ?? seenModel;
    answered += 1;
    msSum += a.ms;
    const st = decide(a);
    stateOk[c.kind][1] += 1;
    if (st === c.expect.state) stateOk[c.kind][0] += 1;
    else wrong.push(`[상태] ${c.q} — 기대 ${c.expect.state}, 실제 ${st} (mentions ${a.mentions.toFixed(2)}, listed ${a.listed.toFixed(2)}, top ${a.topP.toFixed(2)}→${alias.get(a.top ?? -1) ?? '?'})`);
    if (c.kind === 'site' || c.kind === 'version') {
      topTotal += 1;
      const accept = [c.expect.repoId!, ...(c.expect.alt ?? [])];
      if (a.top !== undefined && accept.includes(a.top)) topHit += 1;
      else wrong.push(`[1위] ${c.q} — 기대 ${accept.map((i) => alias.get(i)).join(' | ')}, 실제 ${alias.get(a.top ?? -1) ?? '?'} (${a.topP.toFixed(2)})`);
    }
    if (c.kind === 'none') noneTop.push(a.topP);
    const gotDb = a.needsDb >= T.needsDb;
    if (gotDb === c.expect.db) dbHit += 1;
    else wrong.push(`[DB] ${c.q} — 기대 ${c.expect.db}, 실제 ${gotDb} (${a.needsDb.toFixed(2)})`);
  }

  console.log(`응답 모델: ${seenModel ?? '?'}, 성공 ${answered}/${input.cases.length}, 실패 ${failures}`);
  for (const k of kinds) console.log(`상태 적중 ${k}: ${pct(stateOk[k][0], stateOk[k][1])}`);
  console.log(`1위 적중(site+version): ${pct(topHit, topTotal)}`);
  console.log(`needs_db 적중: ${pct(dbHit, answered)}`);
  if (noneTop.length > 0) {
    const s = [...noneTop].sort((x, y) => x - y);
    const med = s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    console.log(`none 질문 repo 1위 확률: 최소 ${s[0].toFixed(3)}, 중앙 ${med.toFixed(3)}, 최대 ${s[s.length - 1].toFixed(3)}`);
  }
  console.log(`평균 응답 시간: ${answered === 0 ? '-' : Math.round(msSum / answered)}ms`);
  console.log(`틀린 사례 ${wrong.length}건`);
  for (const w of wrong) console.log(`  - ${w}`);
}

async function main(): Promise<void> {
  const input = loadInput();
  const counts = countKinds(input.cases);
  const dbCount = input.cases.filter((c) => c.expect.db).length;

  if (dryRun) {
    console.log(`입력 검증 통과: repos ${input.repos.length}개, cases ${input.cases.length}개`);
    console.log(`kind별: site ${counts.site}, version ${counts.version}, none ${counts.none}, unlisted ${counts.unlisted}; DB 필요 ${dbCount}`);
    const first = input.cases[0];
    console.log('--- 요청 바디(별칭만, 첫 케이스) ---');
    console.log(JSON.stringify(buildBody(input.repos, first.q, false), null, 2));
    console.log('--- 요청 바디(키워드 포함, 첫 케이스) ---');
    console.log(JSON.stringify(buildBody(input.repos, first.q, true), null, 2));
    return;
  }

  const key = loadKey();
  if (!key) {
    console.error(`키 파일이 없거나 TYPESAFE_API_KEY가 비어 있습니다: ${keyPath}`);
    process.exit(2);
  }
  await runVariant(key, input, false);
  await runVariant(key, input, true);
}

main().catch((e) => {
  console.error(`실패: ${(e as Error).message}`);
  process.exit(1);
});
