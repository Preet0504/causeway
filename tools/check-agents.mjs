#!/usr/bin/env node
/**
 * check-agents — do the agents' granted tools actually exist at runtime?
 *
 * `sync-catalog` checks agent frontmatter against the SCHEMAS. That is a static check: a tool
 * can have a schema, be granted to an agent, and still have no handler — in which case the
 * agent is told it has a capability the server will never serve. It would discover that only
 * mid-run, having already spent budget getting there.
 *
 * This starts the real server, asks it what it serves, and compares.
 *
 *   node tools/check-agents.mjs
 */
import { spawn } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import readline from 'node:readline';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SERVER = 'causeway';

// ── what each agent is granted, from its frontmatter ────────────────────

const agentDir = join(ROOT, '.claude', 'agents');
const agents = readdirSync(agentDir)
  .filter((f) => f.endsWith('.md'))
  .map((f) => {
    const text = readFileSync(join(agentDir, f), 'utf8');
    const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---/);
    const name = fm?.[1].match(/^name:\s*(.+)$/m)?.[1].trim() ?? f;
    const toolsLine = fm?.[1].match(/^tools:\s*([\s\S]*?)(?=^\w+:|$)/m)?.[1] ?? '';
    const tools = toolsLine
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.startsWith(`mcp__${SERVER}__`))
      .map((t) => t.replace(`mcp__${SERVER}__`, ''));
    return { name, tools };
  });

// ── what the server actually serves ─────────────────────────────────────

// CAUSEWAY_DIST lets this run against a STAGED build (`app/distTo target/dist-next`) while a
// live server still holds target/dist open — otherwise a change cannot be verified until the
// thing it changes has been shut down.
const distDir = process.env.CAUSEWAY_DIST || 'target/dist';

const proc = spawn('java', ['-cp', `${distDir}/lib/*`, 'causeway.app.Main'], {
  cwd: ROOT,
  env: { ...process.env, CAUSEWAY_SCHEMAS: 'schemas' }
});
const rl = readline.createInterface({ input: proc.stdout });
const pending = new Map();
let id = 1;
rl.on('line', (l) => {
  const t = l.trim();
  if (!t.startsWith('{')) return;
  const m = JSON.parse(t);
  if (m.id !== undefined && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
});
const request = (method, params = {}) =>
  new Promise((res, rej) => {
    const myId = id++;
    const timer = setTimeout(() => rej(new Error(`timeout on ${method}`)), 90000);
    pending.set(myId, (m) => { clearTimeout(timer); res(m); });
    proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: myId, method, params }) + '\n');
  });

let failed = false;
try {
  await request('initialize', {
    protocolVersion: '2025-06-18', capabilities: {},
    clientInfo: { name: 'check-agents', version: '1.0' }
  });
  proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');

  const list = await request('tools/list');
  const served = new Set(list.result.tools.map((t) => t.name));
  console.log(`server serves ${served.size} tools\n`);

  for (const a of agents) {
    const missing = a.tools.filter((t) => !served.has(t));
    const ok = missing.length === 0;
    if (!ok) failed = true;
    const status = ok ? 'ok ' : 'GAP';
    console.log(`${status} ${a.name.padEnd(24)} granted ${String(a.tools.length).padStart(2)}, served ${String(a.tools.length - missing.length).padStart(2)}`);
    // An agent granted a tool the server will never serve discovers it mid-run, after
    // spending budget to get there. Better to know before dispatching anything.
    missing.forEach((m) => console.log(`      unserved: ${m}`));
  }

  console.log(failed
    ? '\nSome agents are granted tools the server does not serve.'
    : '\nEvery granted tool is served.');
} catch (e) {
  console.error('CHECK ERROR:', e.message);
  failed = true;
} finally {
  proc.kill();
  setTimeout(() => process.exit(failed ? 1 : 0), 200);
}
