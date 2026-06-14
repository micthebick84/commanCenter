/**
 * Phase-0 spike re-check. Subscription auth via the local claude CLI (no ANTHROPIC_API_KEY).
 * Run against a small temp repo (must already be a git checkout at the same path used for resume):
 *   SUPERPOWERS_PLUGIN_PATH=/Users/.../superpowers/5.1.0 \
 *   node --import tsx scripts/spike.ts /tmp/spike-repo
 * Records evidence for: (00b) subscription auth, (02) cwd-pinned resume, (04) skill auto-trigger.
 */
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { buildOptions } from '../src/sdk/sessionOptions.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
import { relay } from '../src/runner/messageRelay.js';

const workDir = process.argv[2] ?? process.cwd();
const superpowersPluginPath = process.env.SUPERPOWERS_PLUGIN_PATH ?? '';
const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);

async function* prompt(
  text: string,
): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield { type: 'user', message: { role: 'user', content: text } };
}

async function spike(): Promise<void> {
  // Spike 04: does the brainstorming skill auto-fire and reach the writing-plans handoff?
  const first = await relay(
    realQuery({
      prompt: prompt(
        'Use the brainstorming skill for a tiny feature: add a /health endpoint. Ask one question, then on my "go" produce the spec and transition to writing-plans.',
      ),
      options: buildOptions({ superpowersPluginPath, workDir, claudeCliPath, claudeSessionId: null }),
    }),
  );
  // eslint-disable-next-line no-console
  console.log('SPIKE init session_id:', first.sessionId, 'shadow cost:', first.costUsd);

  // Spike 02: resume the captured session_id from a SEPARATE query call with the IDENTICAL cwd.
  if (first.sessionId) {
    const second = await relay(
      realQuery({
        prompt: prompt('go'),
        options: buildOptions({
          superpowersPluginPath,
          workDir,
          claudeCliPath,
          claudeSessionId: first.sessionId,
        }),
      }),
    );
    // eslint-disable-next-line no-console
    console.log('SPIKE resume reached plan?:', /Implementation Plan/m.test(second.assistantText));
  }
}

void spike();
