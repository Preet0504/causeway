#!/usr/bin/env node
/**
 * sync-catalog — schemas/tools/ is the single source of truth for the tool catalog.
 *
 * The catalog would otherwise be written down in four places (schemas, the MCP registry,
 * .claude/settings.json, and each agent's frontmatter). This collapses two of them and
 * checks the third.
 *
 *   node tools/sync-catalog.mjs --check    verify, exit 1 on drift   (use in CI)
 *   node tools/sync-catalog.mjs --write    regenerate settings.json
 */
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SCHEMA_DIR = join(ROOT, 'schemas', 'tools');
const AGENT_DIR = join(ROOT, '.claude', 'agents');
const SETTINGS = join(ROOT, '.claude', 'settings.json');
const SERVER = 'causeway';

const mode = process.argv.includes('--write') ? 'write' : 'check';
const problems = [];
const fail = (m) => problems.push(m);

/* ── load schemas ─────────────────────────────────────────────────────── */

const schemas = readdirSync(SCHEMA_DIR)
  .filter((f) => f.endsWith('.json'))
  .map((f) => {
    const s = JSON.parse(readFileSync(join(SCHEMA_DIR, f), 'utf8'));
    if (`${s.name}.json` !== f) fail(`${f}: "name" is "${s.name}" — filename and name must match`);
    const x = s['x-causeway'];
    if (!x) fail(`${f}: missing x-causeway metadata`);
    else {
      if (!['P', 'E', 'X', 'R', 'N'].includes(x.class)) fail(`${f}: bad class "${x.class}"`);
      if (!['allow', 'ask'].includes(x.permission)) fail(`${f}: bad permission "${x.permission}"`);
      if (!Array.isArray(x.agents)) fail(`${f}: x-causeway.agents must be an array`);
      if (!Array.isArray(x.unknownReasons)) fail(`${f}: x-causeway.unknownReasons must be an array`);
      if (x.class === 'P' && x.agents.length > 1)
        fail(`${f}: class P is pipeline-only but lists ${x.agents.length} agents`);
      if (x.class === 'X' && x.permission !== 'ask')
        fail(`${f}: class X executes agent-authored code and must be permission "ask"`);
    }
    if (!s.inputSchema) fail(`${f}: missing inputSchema`);
    if (!s.outputSchema) fail(`${f}: missing outputSchema`);
    return s;
  });

/* ── validate unknownReasons against the closed set in _common ────────── */

const common = JSON.parse(readFileSync(join(ROOT, 'schemas', '_common.json'), 'utf8'));
const REASONS = new Set(common.$defs.unknownReason.enum);
for (const s of schemas)
  for (const r of s['x-causeway']?.unknownReasons ?? [])
    if (!REASONS.has(r))
      fail(`${s.name}: unknownReason "${r}" is not in _common.json — add the case to modules/core first`);

/* ── settings.json ────────────────────────────────────────────────────── */

const qualify = (n) => `mcp__${SERVER}__${n}`;
const allow = schemas.filter((s) => s['x-causeway']?.permission === 'allow').map((s) => qualify(s.name)).sort();
const ask = schemas.filter((s) => s['x-causeway']?.permission === 'ask').map((s) => qualify(s.name)).sort();

// Only `permissions` is generated. Everything else in settings.json — hooks above all — is
// hand-maintained and must survive a --write.
const actual = readFileSync(SETTINGS, 'utf8');
const existing = JSON.parse(actual);
const desired = JSON.stringify({ ...existing, permissions: { allow, ask } }, null, 2) + '\n';

if (mode === 'write') {
  writeFileSync(SETTINGS, desired);
  console.log(`settings.json: wrote ${allow.length} allow, ${ask.length} ask (other keys preserved)`);
} else if (actual !== desired) {
  fail('settings.json permissions are out of sync — run: node tools/sync-catalog.mjs --write');
}

/* ── agent frontmatter must match x-causeway.agents, both directions ─── */

const agentFiles = readdirSync(AGENT_DIR).filter((f) => f.endsWith('.md'));
const declared = new Map();   // agent -> Set(toolName)

for (const f of agentFiles) {
  const text = readFileSync(join(AGENT_DIR, f), 'utf8');
  const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!fm) { fail(`${f}: no frontmatter`); continue; }
  const name = fm[1].match(/^name:\s*(.+)$/m)?.[1].trim();
  if (name !== f.replace(/\.md$/, '')) fail(`${f}: frontmatter name "${name}" does not match filename`);
  const toolsLine = fm[1].match(/^tools:\s*([\s\S]*?)(?=^\w+:|$)/m)?.[1] ?? '';
  const tools = toolsLine.split(',').map((t) => t.trim()).filter(Boolean);
  declared.set(name, new Set(
    tools.filter((t) => t.startsWith(`mcp__${SERVER}__`)).map((t) => t.replace(`mcp__${SERVER}__`, ''))
  ));
  for (const t of tools)
    if (t.startsWith('mcp__') && !t.startsWith(`mcp__${SERVER}__`))
      fail(`${name}: references a tool on another MCP server: ${t}`);
}

const expected = new Map([...declared.keys()].map((a) => [a, new Set()]));
for (const s of schemas)
  for (const a of s['x-causeway']?.agents ?? []) {
    if (!expected.has(a)) { fail(`${s.name}: names unknown agent "${a}"`); continue; }
    expected.get(a).add(s.name);
  }

for (const [agent, want] of expected) {
  const have = declared.get(agent) ?? new Set();
  for (const t of want) if (!have.has(t))
    fail(`${agent}.md: schema ${t} grants access but frontmatter omits mcp__${SERVER}__${t}`);
  for (const t of have) if (!want.has(t))
    fail(`${agent}.md: frontmatter claims ${t} but its schema does not list this agent`);
}

/* ── report ───────────────────────────────────────────────────────────── */

const cls = (c) => schemas.filter((s) => s['x-causeway']?.class === c).length;
console.log(`${schemas.length} tools — P:${cls('P')} E:${cls('E')} X:${cls('X')} R:${cls('R')} N:${cls('N')}`);
console.log(`${agentFiles.length} agents — ${[...expected].map(([a, s]) => `${a}:${s.size}`).join('  ')}`);

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(mode === 'check' ? '\ncatalog is in sync' : '\ncatalog written');
