import { readFileSync } from 'node:fs';
import { hostname as osHostname } from 'node:os';
import type { JavaApiClient } from './api/javaClient.js';
import { EXPIRING_SOON_MS, UNKNOWN_AUTH, type ClaudeAuthStatus } from './claudeAuth.js';

export interface WorkerHeartbeatOptions {
  workerId: string;
  version: string;
  /** 하트비트 간격 — 관리 화면 alive 기준(60초)보다 짧아야 한다. */
  intervalMs: number;
  /** claude 로그인 판정 간격 — 그 사이 하트비트는 마지막 판정값을 싣는다. */
  authIntervalMs: number;
  checkAuth: () => Promise<ClaudeAuthStatus>;
  now?: () => number;
  hostname?: string;
}

/**
 * 인터뷰 서비스도 Java 워커처럼 POST /worker/heartbeat로 관리 화면 "워커 헬스"에 한 줄을 남긴다 (스펙 2026-11-02 §5) —
 * claude 로그인 판정과 서비스가 살아 있는지를 함께 보인다. 세션 생사 판정은 지금처럼 세션 heartbeat(HeartbeatTicker)다.
 * mcps는 비운다 — 작업 등록 창의 MCP 합집합(/api/workers/mcps/available)에 인터뷰 쪽 MCP가 섞이지 않게.
 * 어떤 경로로도 throw하지 않는다(보고 실패가 턴을 죽이면 안 된다). 연속 실패는 첫 번째만 경고한다.
 */
export class WorkerHeartbeatReporter {
  private timer: ReturnType<typeof setInterval> | null = null;
  private auth: ClaudeAuthStatus = UNKNOWN_AUTH;
  private authCheckedAt = Number.NEGATIVE_INFINITY;
  private loggedAuth = '';
  private failing = false;
  private inFlight: Promise<void> | null = null;

  constructor(
    private readonly client: Pick<JavaApiClient, 'workerHeartbeat'>,
    private readonly opts: WorkerHeartbeatOptions,
  ) {}

  start(): void {
    if (this.timer) return;
    void this.tick();
    this.timer = setInterval(() => void this.tick(), this.opts.intervalMs);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** 한 번 보고한다. 앞선 보고(판정 포함)가 아직 진행 중이면 새로 보내지 않고 그 보고를 돌려준다 — 겹쳐 보내지 않게. */
  tick(): Promise<void> {
    if (!this.inFlight) {
      this.inFlight = this.send().finally(() => {
        this.inFlight = null;
      });
    }
    return this.inFlight;
  }

  private now(): number {
    return (this.opts.now ?? Date.now)();
  }

  private async send(): Promise<void> {
    const now = this.now();
    if (now - this.authCheckedAt >= this.opts.authIntervalMs) {
      this.authCheckedAt = now;
      try {
        this.auth = await this.opts.checkAuth();
      } catch {
        this.auth = UNKNOWN_AUTH;
      }
      this.logAuthChange(now);
    }
    try {
      await this.client.workerHeartbeat({
        workerId: this.opts.workerId,
        hostname: this.opts.hostname ?? osHostname(),
        version: this.opts.version,
        claudeSessionOk: this.auth.ok,
        vpnStatus: null,
        mcps: [],
        lostReportCount: 0,
        claudeAuthMethod: this.auth.method,
        claudeAuthExpiresAt: this.auth.expiresAt,
      });
      if (this.failing) {
        this.failing = false;
        // eslint-disable-next-line no-console
        console.log('[worker-heartbeat] 보고 다시 성공');
      }
    } catch (err) {
      if (!this.failing) {
        this.failing = true;
        // eslint-disable-next-line no-console
        console.warn(`[worker-heartbeat] 보고 실패(다시 성공할 때까지 알리지 않음): ${(err as Error).message}`);
      }
    }
  }

  /** 판정이 바뀔 때만 남긴다 — 끊김·임박은 경고. */
  private logAuthChange(now: number): void {
    const a = this.auth;
    const exp = a.expiresAt === null ? null : Date.parse(a.expiresAt);
    const soon = a.ok === true && exp !== null && exp - now <= EXPIRING_SOON_MS;
    const key = `${a.ok}|${a.method}|${a.expiresAt}|${soon}`;
    if (key === this.loggedAuth) return;
    this.loggedAuth = key;
    const until = a.expiresAt ? `, 만료 ${new Date(a.expiresAt).toLocaleString('sv-SE').slice(0, 16)}` : '';
    const fix = a.method === 'ENV_TOKEN' ? '토큰 교체 필요' : '이 PC에서 claude 다시 로그인 또는 setup-token 필요';
    /* eslint-disable no-console */
    if (a.ok === false) console.warn(`[claude-auth] 로그인 끊김(${a.method}${until}) — ${fix}`);
    else if (soon) console.warn(`[claude-auth] 로그인 만료 임박(${a.method}${until}) — ${fix}`);
    else if (a.ok === true) console.log(`[claude-auth] 로그인 확인(${a.method}${until})`);
    else console.log('[claude-auth] 로그인 상태를 판정하지 못함');
    /* eslint-enable no-console */
  }
}

/** package.json version — src/(tsx)와 dist/src/(빌드) 어느 쪽에서 돌든 찾는다. 못 찾으면 'unknown'. */
export function serviceVersion(read: (url: URL) => string = (u) => readFileSync(u, 'utf8')): string {
  for (const rel of ['../package.json', '../../package.json']) {
    try {
      const v = (JSON.parse(read(new URL(rel, import.meta.url))) as { name?: string; version?: unknown });
      if (v.name === 'netismaker-interview-service' && typeof v.version === 'string') return v.version;
    } catch {
      // 다음 후보
    }
  }
  return 'unknown';
}
