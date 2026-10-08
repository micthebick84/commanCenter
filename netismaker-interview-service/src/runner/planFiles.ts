import { execFile } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { tryHarvest, type HarvestResult } from './planHarvest.js';

const execFileAsync = promisify(execFile);

const PLANS_DIR = 'docs/superpowers/plans/';
const SPECS_DIR = 'docs/superpowers/specs/';

/**
 * workDir에서 이번 세션에 생기거나 바뀐 파일 경로(저장소 기준, '/' 구분). 커밋된 채 그대로인 파일은 빠진다 —
 * 레포에 원래 있던 옛 plan을 이번 인터뷰의 산출물로 오인하지 않기 위해서다. 매 턴의 `git reset --hard`는
 * untracked 파일을 지우지 않으므로 앞 턴에 쓴 plan도 여기서 잡힌다.
 */
async function changedFiles(workDir: string): Promise<string[]> {
  const { stdout } = await execFileAsync(
    'git',
    ['status', '--porcelain=v1', '-z', '--untracked-files=all', '--', PLANS_DIR, SPECS_DIR],
    { cwd: workDir },
  );
  const entries = stdout.split('\0');
  const paths: string[] = [];
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i]!;
    if (e.length < 4) continue;
    const xy = e.slice(0, 2);
    // rename/copy는 -z에서 원래 경로가 다음 항목으로 따라온다 — 새 경로만 쓴다.
    if (xy.includes('R') || xy.includes('C')) i++;
    if (xy.includes('D')) continue;
    paths.push(e.slice(3));
  }
  return paths;
}

function newest(workDir: string, paths: string[], dir: string): string | undefined {
  return paths
    .filter((p) => p.startsWith(dir) && p.endsWith('.md'))
    .map((p) => ({ p, mtime: statSync(join(workDir, p)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime)[0]?.p;
}

/**
 * 채팅 본문 추출이 실패했을 때의 대체 완료 판정: writing-plans가 plan을 파일로만 저장하고 채팅엔 요약만
 * 남기는 경우(2026-10-08 세션 21)를 잡는다. 이번 세션의 최신 plan 파일을 같은 규칙(tryHarvest)으로 추출하고,
 * 이번 세션의 최신 spec 파일이 있으면 그것을 설계로 싣는다. git/파일 오류는 전부 ok:false — 대체 경로가
 * 기존 질문 흐름을 깨면 안 된다.
 */
export async function harvestSessionPlanFiles(workDir: string): Promise<HarvestResult> {
  try {
    const paths = await changedFiles(workDir);
    const plan = newest(workDir, paths, PLANS_DIR);
    if (!plan) return { ok: false };
    const harvested = tryHarvest(readFileSync(join(workDir, plan), 'utf8'));
    if (!harvested.ok) return harvested;
    const spec = newest(workDir, paths, SPECS_DIR);
    if (spec) harvested.harvest.designMarkdown = readFileSync(join(workDir, spec), 'utf8').trim();
    return harvested;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn(`[runner] plan 파일 확인 실패: ${(err as Error).message}`);
    return { ok: false };
  }
}
