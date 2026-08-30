#!/usr/bin/env node
/**
 * Find response fields a handler emits that its output schema does not declare.
 *
 * Output is validated at runtime with `additionalProperties: false`, so an undeclared field is
 * not ignored — the whole call comes back as an output-validation ERROR. That fires only when
 * the branch emitting the field runs, which is how `repo_clone`'s `refreshed` reached a staged
 * build: the parameter had just been implemented and no test passed `refresh: true`.
 *
 * HEURISTIC. It slices each source file at `handler("name")` boundaries, collects
 * `.put("field"` / `arr(x, "field")` names, and compares them against every property the output
 * schema declares AT ANY DEPTH. Comparing against top-level properties only was tried first and
 * flagged 22 of 44 tools, essentially all of them nested fields — noise that buries the one real
 * finding. Depth-insensitive means a field emitted at the WRONG LEVEL still slips through; what
 * it reliably catches is a name the schema has never heard of, which is the case that bit.
 *
 *   node tools/check-outputs.mjs
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

/** Every property name a schema declares, at any depth. */
function propertyNames(node, into = new Set()) {
  if (!node || typeof node !== "object") return into;
  if (node.properties) Object.keys(node.properties).forEach((k) => into.add(k));
  for (const v of Object.values(node)) {
    if (Array.isArray(v)) v.forEach((x) => propertyNames(x, into));
    else if (v && typeof v === "object") propertyNames(v, into);
  }
  return into;
}

// Shared shapes ($defs/hunk, $defs/methodRef, ...) reach a schema through $ref, which this
// checker does not resolve. Treating every name _common declares as declared everywhere is
// blunt, but the alternative is flagging every tool that reuses a shape.
const common = propertyNames(
  JSON.parse(readFileSync(join(root, "schemas", "_common.json"), "utf8"))
);

// Known-benign, with reasons — the same discipline as tools/check-params.mjs.
const benign = {
  graph_query: "rows are free-form objects by design; their field names come from the query",
  export_run: "these names go into the exported FILE, not the response envelope",
};

function sources(dir, acc = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) {
      if (e !== "target" && e !== "test") sources(p, acc);
    } else if (e.endsWith(".scala")) acc.push(p);
  }
  return acc;
}

// tool name -> the source text of its handler block
const blocks = new Map();
for (const f of sources(join(root, "modules", "app"))) {
  const text = readFileSync(f, "utf8");
  const marks = [...text.matchAll(/handler\("([a-z0-9_]+)"\)/g)];
  marks.forEach((m, i) => {
    const end = i + 1 < marks.length ? marks[i + 1].index : text.length;
    blocks.set(m[1], text.slice(m.index, end));
  });
}

let flagged = 0;
for (const f of readdirSync(join(root, "schemas", "tools")).sort()) {
  const spec = JSON.parse(readFileSync(join(root, "schemas", "tools", f), "utf8"));
  const body = blocks.get(spec.name);
  if (!body) continue;

  const declared = propertyNames(spec.outputSchema, new Set(common));
  const emitted = new Set(
    [...body.matchAll(/(?:\.put|arr\([A-Za-z]+,\s*)\(?"([A-Za-z][A-Za-z0-9]*)"/g)].map((m) => m[1])
  );

  const undeclared = [...emitted].filter((k) => !declared.has(k));
  if (undeclared.length && benign[spec.name]) {
    console.log(`  (ok) ${spec.name.padEnd(23)} ${benign[spec.name]}`);
    continue;
  }
  if (undeclared.length) {
    flagged++;
    console.log(`  ${spec.name.padEnd(28)} ${undeclared.join(", ")}`);
  }
}
if (!flagged) console.log("no handler emits a field its output schema does not declare");
else {
  console.log(
    "\nA name the output schema does not use at any depth. If it is genuinely part of the\n" +
      "response, declare it — output is validated with additionalProperties:false, so an\n" +
      "undeclared field turns the whole call into an error rather than data."
  );
  process.exitCode = process.argv.includes("--check") ? 1 : 0;
}
