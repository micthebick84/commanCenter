/**
 * 스파이크(스펙 2026-10-02 §11-2·5) — openSession을 실제 DB에 붙여 읽기 전용·행 상한을 확인한다. 일회성 도구.
 * 실행(Git Bash): SPIKE_DB_TYPE=POSTGRESQL SPIKE_DB_HOST=localhost SPIKE_DB_PORT=5432 SPIKE_DB_NAME=<DB> SPIKE_DB_USER=<계정> \
 *   bash -c 'read -s SPIKE_DB_PASSWORD; export SPIKE_DB_PASSWORD; node --import tsx scripts/spikeDbAdapter.ts'
 * 비밀번호는 환경변수로만 받고 출력하지 않는다(오류 메시지는 mask를 거친다).
 */
import { openSession } from '../src/sdk/db/adapters.js';
import type { DbType } from '../src/types.js';

const e = process.env;
const ref = {
  serverName: 'db-0', label: 'spike', dbType: (e.SPIKE_DB_TYPE ?? 'POSTGRESQL') as DbType, host: e.SPIKE_DB_HOST ?? 'localhost',
  port: Number(e.SPIKE_DB_PORT ?? 5432), database: e.SPIKE_DB_NAME ?? '', username: e.SPIKE_DB_USER ?? '', password: e.SPIKE_DB_PASSWORD ?? '',
};
const mask = (m: string) => (ref.password ? m.split(ref.password).join('****') : m);
const s = await openSession(ref);
const probes: Array<[string, string]> = ref.dbType === 'POSTGRESQL'
  ? [['SELECT 1 AS n', '1행'], ['SELECT generate_series(1, 500) AS n', '200행 + truncated'],
     ['SHOW default_transaction_read_only', 'on'], ['CREATE TEMP TABLE spike_x(a int)', 'read-only 오류'],
     ['SELECT 1; SELECT 2', 'cannot insert multiple commands(extended 프로토콜이 다중 문장 거부)']]
  : ref.dbType === 'ORACLE'
    ? [['SELECT 1 AS n FROM dual', '1행'], ['SELECT level AS n FROM dual CONNECT BY level <= 500', '200행 + truncated'],
       ['CREATE TABLE spike_x(a int)', '오류(1선이 막는 대상 — 2선만으로는 DDL이 실행될 수 있음: 테스트 계정에서만)']]
    : [['SELECT 1 AS n', '1행'], ['SELECT @@session.transaction_read_only AS ro', '1'],
       ['SELECT 1 FROM information_schema.COLUMNS a, information_schema.COLUMNS b LIMIT 500', '200행 + truncated → 재접속'],
       ['SELECT 2 AS n', '1행(재접속 확인)']];
for (const [sql, expect] of probes) {
  try {
    const r = await s.run(sql);
    console.log(`[ok] ${sql} → rows=${r.rows.length} truncated=${r.truncated} first=${JSON.stringify(r.rows[0])}  (기대: ${expect})`);
  } catch (err) {
    console.log(`[err] ${sql} → ${mask(err instanceof Error ? err.message : String(err))}  (기대: ${expect})`);
  }
}
await s.close();
