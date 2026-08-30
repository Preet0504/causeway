#!/usr/bin/env node
/**
 * telemetry — captures agent behaviour and tool usage from Claude Code hook events.
 *
 * Wired to PostToolUse, PostToolUseFailure, SubagentStart, SubagentStop, and SessionEnd
 * in .claude/settings.json. Reads the hook payload on stdin and appends JSONL under
 * runs/<session_id>/telemetry/.
 *
 * Deliberately NOT captured here: token counts and cost. No hook payload carries them.
 * They come from tools/collect-usage.mjs, which parses the session transcript afterwards.
 *
 * This hook must never block a run: every failure path exits 0.
 */
import { appendFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const readStdin = async () => {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
};

/** Arguments can contain a whole Java harness. Record shape and size, never payload. */
const summarizeArgs = (input) => {
  if (!input || typeof input !== 'object') return {};
  const out = {};
  for (const [k, v] of Object.entries(input)) {
    if (v == null) continue;
    const t = typeof v;
    if (t === 'string') out[k] = v.length <= 80 ? v : { chars: v.length };
    else if (t === 'number' || t === 'boolean') out[k] = v;
    else if (Array.isArray(v)) out[k] = { items: v.length };
    else out[k] = { keys: Object.keys(v).length };
  }
  return out;
};

const main = async () => {
  const raw = await readStdin();
  if (!raw.trim()) return;
  const e = JSON.parse(raw);

  const session = e.session_id || 'unknown-session';
  const dir = join(ROOT, 'runs', session, 'telemetry');
  mkdirSync(dir, { recursive: true });

  const append = (file, rec) =>
    appendFileSync(join(dir, file), JSON.stringify({ ts: new Date().toISOString(), ...rec }) + '\n');

  const who = {
    sessionId: session,
    agentId: e.agent_id ?? null,
    // Absent outside subagent context — that means the orchestrating command made the call.
    agent: e.agent_type ?? 'orchestrator',
  };

  switch (e.hook_event_name) {
    case 'PostToolUse':
    case 'PostToolUseFailure':
      append('tool-calls.jsonl', {
        ...who,
        tool: e.tool_name,
        toolUseId: e.tool_use_id ?? null,
        ok: e.hook_event_name === 'PostToolUse',
        args: summarizeArgs(e.tool_input),
        promptId: e.prompt_id ?? null,
      });
      break;

    case 'SubagentStart':
      append('agent-turns.jsonl', { ...who, event: 'start', effort: e.effort?.level ?? null });
      break;

    case 'SubagentStop':
      append('agent-turns.jsonl', {
        ...who,
        event: 'stop',
        // Length only. The content is in the transcript; duplicating it here would double
        // storage and tempt someone into treating telemetry as an evidence source.
        finalMessageChars: (e.last_assistant_message ?? '').length,
      });
      break;

    case 'SessionEnd':
      append('agent-turns.jsonl', {
        ...who,
        event: 'session-end',
        transcriptPath: e.transcript_path ?? null,
      });
      break;
  }
};

main().catch(() => {}).finally(() => process.exit(0));
