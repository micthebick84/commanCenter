import { describe, expect, it } from 'vitest';
import { childEnv, STACK_SECRET_ENV } from '../src/sdk/childEnv.js';

describe('childEnv', () => {
  it('스택 비밀만 대소문자 없이 빼고 나머지는 그대로 둔다', () => {
    const src = {
      GITLAB_TOKEN: 'glpat-x',
      worker_api_key: 'k',
      Spring_Datasource_Password: 'pw',
      NETISMAKER_DB_SECRET_SALT: 'salt',
      PATH: '/bin',
      CLAUDE_CODE_OAUTH_TOKEN: 'keep', // claude 로그인 — 빼면 CLI가 인증을 못 한다
      CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS: '1',
      NPM_TOKEN: 'keep', // 빌드 도구 자체의 토큰은 스택 비밀이 아니다
    };
    expect(childEnv(src)).toEqual({
      PATH: '/bin',
      CLAUDE_CODE_OAUTH_TOKEN: 'keep',
      CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS: '1',
      NPM_TOKEN: 'keep',
    });
    expect(src.GITLAB_TOKEN).toBe('glpat-x'); // 원본은 건드리지 않는다
  });

  it('목록은 워커(ClaudeExecAdapter.STACK_SECRET_ENV)와 같은 이름들이다', () => {
    expect([...STACK_SECRET_ENV].sort()).toEqual(
      [
        'CLOUDFLARE_API_TOKEN',
        'GH_TOKEN',
        'GITHUB_PAT',
        'GITHUB_TOKEN',
        'GITLAB_TOKEN',
        'MAIL_PASSWORD',
        'NETISMAKER_DB_SECRET_KEY',
        'NETISMAKER_DB_SECRET_SALT',
        'PGPASSWORD',
        'SPRING_DATASOURCE_PASSWORD',
        'WORKER_API_KEY',
      ].sort(),
    );
  });
});
