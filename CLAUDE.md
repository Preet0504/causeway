# repo-bugs-extraction

Agentic workflow that takes a public GitHub repo URL and mines **symptom-to-fault paths** for
important, buildable, well-covered bugs in its history.

**Read [reference/design.md](reference/design.md) before making design decisions.** Decisions
are numbered D1–D21 there — cite the number rather than re-deriving the reasoning. This file
holds only the invariants.

## What this task is not

It is **not fault localization**. Both endpoints are known by design: the symptom, and the
lines the fix changed. The deliverable is **the route between them**. Do not hide the fix from
agents. Do not score with hit@k. Do not reintroduce held-out ground truth — it was tried and
rejected (see §2).

## Hard rules

1. **Every factual claim traces to a tool return.** Agents may not assert anything they did not
   retrieve. `findings.record` rejects claims citing evidence ids the ledger did not issue.
2. **Deterministic work is a service, never agent judgment.** Cloning, diffing, building,
   coverage, call graphs, metrics, graph writes. If it has one right answer, it is a tool.
3. **Reasoning is an agent, and agents adapt.** Deciding whether a PR is a bug fix, classifying
   a symptom, constructing a reproducer, explaining a path. Agents choose their next call from
   what the last one actually returned — never a fixed script.
4. **Agent boundaries are distinct questions, not tool categories.** A call-graph builder is a
   service. See the `add-agent` skill before creating one.
5. **A schema exists before anything invokes a tool.** `modules/mcpserver` refuses to register
   a tool with no entry in `schemas/tools/`. This is enforced at startup and by a test.
6. **A missing signal is neutral, never negative.** Use `Signal[A]`, never `Option[A]`.
   `Unknown(reason)` propagates and stays labelled. There is no `getOrElse`; a build failure
   must never become `0.0` coverage.
7. **Prefer an existing tool.** Check before building: PySZZ, Botsing, EvoSuite, JaCoCo,
   scoverage, OPAL, SootUp, JGit. Wrap them; do not reimplement them.

## Layout

| Path | Owns | Rule |
|---|---|---|
| `modules/core` | domain types, `Signal`, `EvidenceId`, `Finding` | no I/O, no dependencies |
| `modules/vcs` | JGit, diffs, blame, candidate nets, PySZZ | full clone; rename detection ON |
| `modules/forge` | GitHub GraphQL v4 | batched; rate limit → `Unknown`, never throw |
| `modules/jvm` | build probe, OPAL/SootUp, coverage, harness runner | self-contained ecosystem unit |
| `modules/llvm` | the peer for LLVM repos | **zero dependency on `modules/jvm`, both ways** |
| `modules/metrics` | churn, blast radius, ranking | pure total functions over `Signal` |
| `modules/graphstore` | Neo4j | all writes idempotent `MERGE` on natural keys |
| `modules/mcpserver` | transport, registry, ledger, ACL, pooling | the only place enforcement lives |
| `schemas/tools/` | tool contracts | **source of truth** for the catalog |
| `.claude/agents/` | the six reasoning agents | one distinct question each |
| `.claude/hooks/` | telemetry capture | must never block a run; all failure paths exit 0 |
| `runs/` | per-session telemetry, usage, reports | tracked in git — this is research output |
| `notes/` | per-repo agent knowledge, across runs | **non-evidentiary**, see below |
| `reference/design.md` | the full design | update the decisions log when a decision changes |

## Notes vs evidence

> **Notes influence what an agent DOES. Evidence determines what an agent may SAY.**

Notes carry `note_*` ids; `findings.record` requires `^ev_`. A note therefore *cannot* back a
claim — not by convention, by construction. The `noteKind` enum (`ENVIRONMENT`, `CONVENTION`,
`DEAD_END`, `FIXTURE`) has no member for assertions about bugs, on purpose. Anything read from
a note is a hint to be verified through tools, never a fact to be repeated.

Telemetry is likewise **not** an evidence source: it records that a call happened and the shape
of its arguments, never what it returned.

## Conventions

- **Tool names**: dotted in prose (`history.diff`), underscored on the wire
  (`mcp__causeway__history_diff`). The wire form is authoritative.
- **Line ranges**: `OldLines` (parent coordinates) and `NewLines` (fix coordinates) are
  distinct types. The fault lives in old-side lines. Mixing them is a compile error, and that
  is intentional — see the worked example in design §10.
- **Tool responses**: always `{ok, evidenceId, data | unknown}`. `unknown` is a *success*.
  Never throw across the MCP boundary for a determinable-but-absent result.
- **Big payloads**: return a handle (`callGraphId`, `buildHandle`, `reportId`), never the
  object. Agents query handles; graphs must never enter agent context.
- **Execution**: everything past the build gate runs in a container — `--network none`, memory
  and CPU caps, wall-clock timeout, workspace bind-mount only. Repository code and
  agent-authored harnesses both count as untrusted.

## Anti-patterns

- An agent named after an analyzer (`callgraph-agent`, `coverage-agent`) — that's a service.
- `Option[Double]` for a metric, then `getOrElse(0.0)` — destroys rule 6 silently.
- Querying coverage at the parent commit with new-side line numbers — plausible and wrong.
- Adding a tool to `settings.json` before its schema exists.
- Unbounded subagent fan-out. Waves: adjudication ≤5, path tracing ≤4.
- Deleting bugs that fail admission. Record them with `admitted: false` and the reason.

## Build

```bash
node tools/sync-catalog.mjs --check       # verify catalog integrity — run after ANY tool change
node tools/sync-catalog.mjs --write       # regenerate settings.json permissions from the schemas
node tools/collect-usage.mjs <session>    # tokens and cost for a session, from its transcript
node tools/check-agents.mjs               # do the agents' granted tools actually get SERVED?
node tools/check-params.mjs --check       # does every SERVED tool read every parameter it declares?
node tools/check-outputs.mjs --check      # does any handler emit a field its output schema forbids?
```

Both checkers exist because the catalog is written BEFORE the handlers, so nothing challenges a
schema until an implementation disagrees with it. An unread input parameter is accepted and
dropped, and a plausible answer to a different question comes back. An undeclared output field
is worse: output is validated with `additionalProperties: false`, so the whole call fails as an
error rather than data — and only on the branch that emits it.

`schemas/tools/` is the source of truth for the tool catalog (D22). The checker regenerates
`settings.json` and verifies every agent's frontmatter against `x-causeway.agents` in both
directions — an agent listing a tool no schema grants it, or a schema granting access an agent
does not declare, both fail. **Never hand-edit `.claude/settings.json`'s `permissions` block**;
other keys (`hooks`) are hand-maintained and are preserved by `--write`.

```bash
export PATH="$HOME/scoop/shims:$PATH"
set -a && . ./.env && set +a                 # GITHUB_TOKEN, NEO4J_* for live tests
sbt -batch "core/testOnly *; mcpserver/testOnly *; vcs/testOnly *; metrics/testOnly *;             forge/testOnly *; graphstore/testOnly *; jvm/testOnly *; app/testOnly *"
sbt -batch "app/dist"                        # package into target/dist/lib
sbt -batch "app/distTo target/dist-next"     # stage elsewhere while a server holds target/dist
tools/promote-dist.sh                        # swap the staged build in once the server is down
sbt -batch shutdown                          # release Windows file locks
```

`sbt test` alone maps to `testQuick` in sbt 2 and skips unchanged suites — use `testOnly *`.

**One command per `sbt -batch` invocation.** `sbt -batch "core/testOnly *" "app/testOnly *"`
runs the FIRST and reports `[success]`, printing one summary. It reads as a full-suite pass. Run
the modules in a shell loop instead, and check the per-module counts rather than the exit code —
`core` and `mcpserver` both report 34, so a single ambiguous number is not confirmation.

**sbt 2 runs a background server that outlives the invocation**, and it holds both the
environment it started with and the jars it has opened. Three symptoms, one cause: an env var
set on the client is invisible to the build (hence `distTo` taking an argument), a new key in
`build.sbt` reports `Not a valid key`, and `compile` fails with `AccessDeniedException` moving a
module jar. Kill the server before hunting any of them.

**A task whose value is a side effect must be `Def.uncached`.** sbt 2 caches task results, and a
`Unit` result caches trivially — `dist` reported `[success]` on a second run having copied
nothing.

**Sources are LF.** Python's text-mode write converts to CRLF on Windows, which silently breaks
multi-line string fixtures. Any script that rewrites a source file must pass `newline=''`.
