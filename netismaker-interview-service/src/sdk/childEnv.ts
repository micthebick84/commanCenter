/**
 * claude CLI 자식 프로세스(그리고 그 밑에서 도는 도구·빌드 스크립트·stdio MCP 서버)에 넘기지 않는 스택 비밀.
 * 인터뷰 서비스 자신은 쓰지만(GITLAB_TOKEN으로 clone, WORKER_API_KEY로 API 호출) 세션 안의 에이전트나
 * 대상 레포의 코드가 볼 이유는 없다. claude 로그인(CLAUDE_*)과 빌드 도구 자체의 토큰은 건드리지 않도록 패턴이
 * 아니라 이 스택이 정의한 이름만 뺀다.
 * 워커 쪽 같은 목록: ClaudeExecAdapter.STACK_SECRET_ENV — 바꿀 때 같이 바꿀 것.
 */
export const STACK_SECRET_ENV: readonly string[] = [
  'WORKER_API_KEY',
  'SPRING_DATASOURCE_PASSWORD',
  'GITLAB_TOKEN',
  'GITHUB_PAT',
  'GH_TOKEN',
  'GITHUB_TOKEN',
  'CLOUDFLARE_API_TOKEN',
  'NETISMAKER_DB_SECRET_KEY',
  'NETISMAKER_DB_SECRET_SALT',
  'MAIL_PASSWORD',
  'PGPASSWORD',
];

const SECRET_NAMES = new Set(STACK_SECRET_ENV.map((k) => k.toUpperCase()));

/**
 * SDK options.env로 넘길 env — 받은 env를 복사해 스택 비밀만 뺀다(원본은 그대로). SDK는 options.env를
 * process.env와 합치지 않고 그대로 쓰므로 복사본 전체를 넘긴다. Windows env는 대소문자를 가리지 않아 이름도
 * 대소문자 없이 비교한다.
 */
export function childEnv(env: NodeJS.ProcessEnv = process.env): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {};
  for (const [k, v] of Object.entries(env)) {
    if (!SECRET_NAMES.has(k.toUpperCase())) out[k] = v;
  }
  return out;
}
