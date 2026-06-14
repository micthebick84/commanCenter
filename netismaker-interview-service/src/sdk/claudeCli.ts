import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

type ExistsFn = (path: string) => boolean;

/**
 * Resolves the claude CLI binary for subscription auth (Phase-0 spike 00b),
 * mirroring netisMaker's ClaudeExecAdapter discovery order:
 *   explicit override -> ~/.local/bin -> ~/.claude/local -> /opt/homebrew/bin
 *   -> /usr/local/bin -> /usr/bin -> bare "claude" (PATH).
 */
export function resolveClaudeCli(
  override: string | undefined,
  home: string = homedir(),
  exists: ExistsFn = existsSync,
): string {
  if (override) return override;
  const candidates = [
    join(home, '.local/bin/claude'),
    join(home, '.claude/local/claude'),
    '/opt/homebrew/bin/claude',
    '/usr/local/bin/claude',
    '/usr/bin/claude',
  ];
  for (const c of candidates) {
    if (exists(c)) return c;
  }
  return 'claude'; // last resort: rely on PATH
}
