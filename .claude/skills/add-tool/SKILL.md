---
name: add-tool
description: Add a new deterministic tool to the causeway MCP server. Use whenever a new capability is needed — a git query, a build operation, a coverage lookup, a graph write, a metric. Enforces schema-before-implementation and keeps the four places the catalog is written down in sync.
---

# Adding a tool to causeway

Tools are deterministic. If the thing you are adding requires judgment, you want an agent —
see the `add-agent` skill instead.

**The schema comes first.** `modules/mcpserver` refuses at startup to register a tool with no
entry in `schemas/tools/`, so implementing before writing the schema produces a server that
will not boot.

## 1. Classify it

| Class | Who calls it | Examples |
|---|---|---|
| **P** Pipeline | the `/mine-repo` command, in fixed stages | `repo.clone`, `jvm.coverage.run`, `graph.upsert_bug` |
| **E** Evidence | agents, to retrieve facts they reason over | `history.diff`, `jvm.callgraph.paths` |
| **X** Experiment | `reproducer-synthesist` only | `jvm.harness.run` |
| **R** Recording | every agent | `findings.record` |

Class decides who gets it in their tool list, and whether it goes in `allow` or `ask` in
settings. Expensive and stateful → P, and keep it away from agents. Cheap, read-only, small
response → E.

## 2. Write the schema

`schemas/tools/<namespace>.<name>.json`. Both directions.

The output schema must include the `unknown` branch — every tool can fail to determine
something, and that failure is a *success* carrying a reason:

```json
{
  "oneOf": [
    { "required": ["ok", "evidenceId", "data"] },
    { "required": ["ok", "evidenceId", "unknown"] }
  ]
}
```

`unknown.reason` must be one of the `UnknownReason` values in `modules/core`. If your tool has
a failure mode none of them describes, add the case to the enum in `core` first — do not
improvise a string.

Rules for the schema:

- **Handles, not payloads.** If the result could exceed a few hundred rows, return an id and
  add a companion query tool. Call graphs, coverage reports, and traces are never returned
  whole.
- **Commit arguments are validated 40-hex.** Reuse the shared `$ref` rather than restating it.
- **Line ranges carry a side.** `oldRange` and `newRange` are different fields with different
  meanings; never a bare `range`.

## 3. Implement it in the owning module

Not in `mcpserver` — that layer only routes. `history.*` lives in `vcs`, `jvm.*` in `jvm`,
`metrics.*` in `metrics`. If it does not fit an existing module, stop and reconsider: a tool
that spans modules is usually two tools.

- Return `Signal`-shaped results. Never throw for a determinable-but-absent value.
- Never compute a default. A missing number is `Unknown(reason)`, not zero.
- If it executes anything — build, test, harness — it runs in a container and takes a slot
  from the pool. Do not spawn processes directly.
- Evidence ids are minted by the server on the way out. Do not construct them yourself.

## 4. Register it

`modules/mcpserver` tool registry. Registration reads the schema from disk; if the file is
missing the server fails loudly at boot, which is the intended behaviour.

## 5. Propagate — mostly automatic

The `x-causeway` block you wrote in step 2 drives everything else:

```bash
node tools/sync-catalog.mjs --write    # regenerates .claude/settings.json
node tools/sync-catalog.mjs --check    # verifies agent frontmatter both directions
```

**Never hand-edit `.claude/settings.json`** — it is generated, and manual edits are reported
as drift on the next check.

What the checker will make you fix:

- Every agent named in `x-causeway.agents` must list `mcp__causeway__<name>` in its own
  frontmatter. An agent cannot call a tool absent from its list, whatever settings say.
- Conversely, an agent listing a tool whose schema does not name it also fails. Access is
  granted in one direction only, from the schema.
- Class `P` may name at most one agent; class `X` must be permission `ask`.
- Every `unknownReason` must exist in `schemas/_common.json`.

Still manual: `reference/design.md` §9's matrix, which is documentation. If it disagrees with
the schemas, the schemas are right — fix the doc.

## 6. Test

- **Schema conformance**: the registry test asserts every registered tool has a schema and
  every schema has a registered tool. Adding a tool should not require touching this test.
- **Behaviour**: a fixture-based unit test in the owning module. Use `fixtures/` — pinned tiny
  repos and recorded responses — not a live network call.
- **The unknown path**: assert that the failure mode produces `unknown` with the right reason,
  not an exception and not a zero. This is the test most often skipped and it is the one
  protecting rule 6.
