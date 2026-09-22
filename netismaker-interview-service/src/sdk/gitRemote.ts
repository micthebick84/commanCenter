/** 호스트별 토큰. Java GitRemotes와 같은 규칙(스펙 2026-09-21 §8). */
export interface GitTokens {
  githubPat?: string;
  gitlabToken?: string;
}

export interface RepoLocator {
  githubRepo: string;
  /** 정식 Git URL. 구버전 API claim에는 없다 → GitHub로 간주. */
  gitUrl?: string | null;
  repoHost?: string | null;
}

function hostOf(repo: RepoLocator): 'github' | 'gitlab' {
  if (repo.repoHost === 'gitlab' || repo.repoHost === 'github') return repo.repoHost;
  if (!repo.gitUrl) return 'github';
  const m = /^https?:\/\/(?:[^@/\s]+@)?([^/:\s]+)/.exec(repo.gitUrl);
  return m?.[1] && m[1].toLowerCase() !== 'github.com' ? 'gitlab' : 'github';
}

/**
 * clone/fetch용 URL. 토큰이 있으면 https://oauth2:<token>@host/path.git, 없으면 평문 URL
 * ('oauth2:@host' 같은 빈 비밀번호 URL은 GitHub가 거부한다).
 */
export function buildCloneUrl(repo: RepoLocator, tokens: GitTokens): string {
  const plain = repo.gitUrl || `https://github.com/${repo.githubRepo}.git`;
  const token = hostOf(repo) === 'gitlab' ? tokens.gitlabToken : tokens.githubPat;
  if (!token) return plain;
  return plain.replace(/^(https?:\/\/)/, (_all, scheme: string) => `${scheme}oauth2:${token}@`);
}

/** "://user:secret@" → "://***@". git 오류가 세션 실패 사유·로그로 나가기 전에 적용. */
export function maskSecrets(text: string): string {
  return text.replace(/(?<=:\/\/)[^/@\s:]+:[^/@\s]+@/g, '***@');
}
