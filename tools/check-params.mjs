#!/usr/bin/env node
/**
 * Find input parameters that no handler ever reads.
 *
 * Schemas are written before handlers, which is the right order — but it means a parameter can
 * sit in the catalog for months without an implementation ever disagreeing with it. `perTest`
 * and `testFilter` both did, and `until` on history_list_commits was worse: the caller asked for
 * commits up to a date, the bound was dropped, and all of history came back looking like an
 * answer.
 *
 * Heuristic by nature — it looks for the parameter NAME as a string literal. It slices each
 * source file at `handler("name")` boundaries first, which matters more than it sounds: the
 * original version grepped the whole tree at once, so a common name like "commit" counted as
 * read by EVERY tool because SOME handler read it. `jvm_build_probe` ignored its `commit` and
 * built whatever the shared clone had checked out — always HEAD — and this checker passed it.
 * A real run found it instead, five stages in.
 *
 * Within a handler the check still over-reports rather than under-reports, which is the safe
 * direction: a false hit costs one manual check, a miss costs a silent wrong answer.
 *
 *   node tools/check-params.mjs          list unread parameters
 *   node tools/check-params.mjs --check  exit 1 if a SERVED tool has one
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// fileURLToPath, not URL.pathname: this project lives under a directory with a SPACE in its
// name, and pathname hands back the percent-encoded form.
const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function scalaSources(dir, acc = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) {
      if (e !== "target" && e !== "test") scalaSources(p, acc);
    } else if (e.endsWith(".scala")) acc.push(p);
  }
  return acc;
}

// tool name -> just that handler's source text
const blocks = new Map();
for (const f of scalaSources(join(root, "modules"))) {
  const text = readFileSync(f, "utf8");
  const marks = [...text.matchAll(/handler\("([a-z0-9_]+)"\)/g)];
  marks.forEach((m, i) => {
    const end = i + 1 < marks.length ? marks[i + 1].index : text.length;
    blocks.set(m[1], text.slice(m.index, end));
  });
}

// Shared helpers read parameters on a handler's behalf, from outside its slice: `withGit`
// reads repoHandle, `withCallGraph` reads callGraphId. A handler that calls one is reading
// what it reads — without this the check flags ten tools for a parameter they all use
// correctly, and a checker that cries wolf gets ignored.
const helpers = new Map();
for (const f of scalaSources(join(root, "modules"))) {
  const text = readFileSync(f, "utf8");
  for (const m of text.matchAll(/private def (with[A-Za-z]+)([\s\S]{0,900}?)(?=\n  (?:private )?def )/g)) {
    const names = [...m[2].matchAll(/"([A-Za-z][A-Za-z0-9]*)"/g)].map((x) => x[1]);
    helpers.set(m[1], new Set([...(helpers.get(m[1]) ?? []), ...names]));
  }
}

// Fallback for tools whose handler this cannot locate — better to miss than to cry wolf.
const allSources = scalaSources(join(root, "modules"))
  .map((p) => readFileSync(p, "utf8"))
  .join("\n");

// Parameters every tool carries, resolved by the framework rather than any one handler.
const universal = new Set(["agent", "runId"]);

// Tools with no handler at all. Their parameters cannot be silently ignored, because nothing is
// served. Each needs a REASON, so this stays a record of deliberate omissions rather than a
// place to park failures.
const unimplemented = {
  evidence_attest: "granted to no agent by D22; correctly unserved",
  repro_botsing: "external tool, needs its own harnessing",
  repro_evosuite_sweep: "external tool, needs its own harnessing",
};

const broken = [];
const deferred = [];
for (const f of readdirSync(join(root, "schemas", "tools")).sort()) {
  const spec = JSON.parse(readFileSync(join(root, "schemas", "tools", f), "utf8"));
  const props = Object.keys(spec.inputSchema?.properties ?? {});
  const body = blocks.get(spec.name) ?? allSources;

  const viaHelper = new Set();
  for (const [name, params] of helpers) {
    if (body.includes(`${name}(`)) params.forEach((x) => viaHelper.add(x));
  }

  const unread = props.filter(
    (p) => !universal.has(p) && !viaHelper.has(p) && !body.includes(`"${p}"`)
  );
  if (!unread.length) continue;
  (unimplemented[spec.name] ? deferred : broken).push([spec.name, unread]);
}

if (deferred.length) {
  console.log("unserved tools (no handler, so nothing is being ignored):\n");
  for (const [name] of deferred) console.log(`  ${name.padEnd(24)} ${unimplemented[name]}`);
  console.log("");
}

if (!broken.length) {
  console.log("every parameter on every SERVED tool is read by its handler");
  process.exit(0);
}

console.log("input parameters a served handler never reads:\n");
for (const [name, unread] of broken) console.log(`  ${name.padEnd(28)} ${unread.join(", ")}`);
console.log(
  "\nEach is a promise the catalog makes and the code does not keep: the call is accepted, the\n" +
    "parameter is dropped, and a plausible answer to a different question comes back. Implement\n" +
    "it, remove it, or add it to `unimplemented` with a reason."
);
process.exit(process.argv.includes("--check") ? 1 : 0);
