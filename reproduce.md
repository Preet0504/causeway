# Reproduce this project

Everything needed to stand Causeway up on a fresh machine and run a mining pass: install,
credentials, the MCP server, the `.claude` layer, the tool catalog, the verification gates, the
first run, and resume.

This is the operational document. For *why* the system is shaped this way, read
[README.md](README.md); for the numbered decision log, [reference/design.md](reference/design.md).

> **There is no `npm install causeway`.** Causeway is not a published package. The MCP server is a
> Scala 3 binary built from this repository and launched by Claude Code over stdio, as declared in
> [.mcp.json](.mcp.json). Steps 4–6 below are the equivalent of "install the server and start it".

## Table of contents

1. [Prerequisites](#1-prerequisites)
2. [Get the code](#2-get-the-code)
3. [Install and authenticate Claude Code](#3-install-and-authenticate-claude-code)
4. [Start Neo4j](#4-start-neo4j)
5. [Credentials and environment](#5-credentials-and-environment)
6. [Build and package the causeway MCP server](#6-build-and-package-the-causeway-mcp-server)
7. [Register and approve the MCP server](#7-register-and-approve-the-mcp-server)
8. [Verify the server is actually serving](#8-verify-the-server-is-actually-serving)
9. [The `.claude` layer](#9-the-claude-layer)
10. [The six agents and their system prompts](#10-the-six-agents-and-their-system-prompts)
11. [The skills](#11-the-skills)
12. [The orchestrating command](#12-the-orchestrating-command)
13. [The causeway tool list](#13-the-causeway-tool-list)
14. [Run the verification gates](#14-run-the-verification-gates)
15. [First run](#15-first-run)
16. [Watch, resume, and collect output](#16-watch-resume-and-collect-output)
17. [Adding a tool or an agent later](#17-adding-a-tool-or-an-agent-later)
18. [Troubleshooting](#18-troubleshooting)
19. [Checklist](#19-checklist)

---

## 1. Prerequisites

| Requirement | Version | Needed for |
|---|---|---|
| JDK | 17 | The MCP server and the analyzers it wraps |
| sbt | 2.x | Building and packaging the server |
| Node | 18 or later | Catalog checkers, telemetry hooks, usage accounting |
| Docker | any recent | Neo4j, containerised builds, coverage, harness runs |
| Claude Code | current | Orchestrator, subagents, MCP client |
| GitHub PAT | classic or fine-grained, public repo read | Forge queries |
| Disk | ~20 GB free | Clones, per-revision worktrees, dependency caches |

Confirm all of it in one line before starting:

```bash
java -version && sbt --version && node --version && docker info >/dev/null && echo "docker ok"
```

## 2. Get the code

```bash
git clone <this-repository> causeway
cd causeway
```

Everything below is run from the repository root. Claude Code must also be started from this
directory, because `.mcp.json`, `.claude/` and `schemas/` are all resolved relative to it.

## 3. Install and authenticate Claude Code

```bash
npm install -g @anthropic-ai/claude-code
claude --version
```

Then authenticate, choosing **one** of:

```bash
# a) Interactive: subscription or Console account
claude
# then: /login

# b) Long-lived token for headless or scripted use
claude setup-token

# c) API key from the environment
export ANTHROPIC_API_KEY=sk-ant-...
```

Verify:

```bash
claude -p "reply with the single word ready"
```

A mining run dispatches six subagents across fourteen steps and is long. Confirm authentication
works before starting one, not after.

## 4. Start Neo4j

Neo4j is the evidence ledger and the run's durable state. Use **non-default ports** so a Neo4j
already on the machine is not disturbed:

```bash
docker run -d --name causeway-neo4j \
  -p 7475:7474 -p 7690:7687 \
  -e NEO4J_AUTH=neo4j/causeway-dev \
  -e NEO4J_server_memory_heap_max__size=2G \
  -e NEO4J_server_memory_pagecache_size=512m \
  neo4j:5-community
```

Browser at http://localhost:7475, bolt at `bolt://localhost:7690`.

The server retries the connection at startup rather than testing once, because Claude Code launches
it the instant a session opens — which, after a host reboot, is before the container is ready.
Losing that race silently costs the whole run's evidence, so the fallback to an in-memory ledger is
announced on stderr.

## 5. Credentials and environment

Create `.env` in the repository root. It is gitignored, and the server reads it itself:

```bash
GITHUB_TOKEN=ghp_...
NEO4J_URI=bolt://localhost:7690
NEO4J_USER=neo4j
NEO4J_PASSWORD=causeway-dev
CAUSEWAY_WORKSPACE=./workspace
```

No secrets go in `.mcp.json`; that file is committed.

Optional tuning, all read from the environment or `.env`:

| Variable | Default | Effect |
|---|---|---|
| `CAUSEWAY_SCHEMAS` | `schemas` | Where the tool catalog is read from |
| `CAUSEWAY_WORKSPACE` | `./workspace` | Clones, worktrees, caches, handle journal |
| `CAUSEWAY_NOTES` | `notes` | Per-repository non-evidentiary notes |
| `CAUSEWAY_RUN_ID` | minted at startup | Pin the server to a known run identifier |
| `CAUSEWAY_CALLGRAPH_CACHE` | `2` | How many SootUp views to hold in memory at once |
| `CAUSEWAY_BUILD_SLOTS` | `2` | Concurrent containerised builds |
| `CAUSEWAY_HARNESS_TIMEOUT_SEC` | `120` | Wall clock for one agent-authored harness run |
| `CAUSEWAY_HARNESS_MEMORY_MB` | `2048` | Memory cap for one harness run |

The GitHub token is **verified with a real request** at startup, not merely checked for presence.
A token that is absent degrades the forge tools into recorded gaps; a token that is rejected says
so on the banner.

## 6. Build and package the causeway MCP server

```bash
export PATH="$HOME/scoop/shims:$PATH"     # if sbt/node came from scoop on Windows
set -a && . ./.env && set +a

for m in core mcpserver vcs forge jvm metrics graphstore app; do
  sbt -batch "$m/testOnly *"
done

sbt -batch "app/dist"                     # packages into target/dist/lib
```

Three rules that are not optional:

- **One command per `sbt -batch` invocation.** Passing several runs the *first*, reports
  `[success]`, and prints one summary that reads like a full pass. Check per-module counts, not the
  exit code.
- `sbt test` alone maps to `testQuick` in sbt 2 and skips unchanged suites. Use `testOnly *`.
- sbt 2 runs a **background server that outlives the invocation**, holding both its start-up
  environment and the jars it has opened. If an env var seems invisible, a new build key reports
  `Not a valid key`, or `compile` fails with `AccessDeniedException`, kill it first:

```bash
sbt -batch shutdown
```

To rebuild while a Claude Code session is holding `target/dist`, stage elsewhere and swap:

```bash
sbt -batch "app/distTo target/dist-next"
tools/promote-dist.sh                     # once the session is closed
```

## 7. Register and approve the MCP server

`.mcp.json` is already in the repository and is committed as-is:

```json
{
  "mcpServers": {
    "causeway": {
      "command": "java",
      "args": [
        "-Xmx6g",
        "-XX:+ExitOnOutOfMemoryError",
        "-cp",
        "target/dist/lib/*",
        "causeway.app.Main"
      ],
      "env": {
        "CAUSEWAY_SCHEMAS": "${CAUSEWAY_SCHEMAS:-schemas}",
        "CAUSEWAY_WORKSPACE": "${CAUSEWAY_WORKSPACE:-./workspace}",
        "CAUSEWAY_BUILD_SLOTS": "2",
        "CAUSEWAY_HARNESS_TIMEOUT_SEC": "120",
        "CAUSEWAY_HARNESS_MEMORY_MB": "2048"
      }
    }
  }
}
```

A project-scoped server needs a one-time approval. Either accept the prompt when the session opens,
or pre-approve it in `.claude/settings.local.json` (gitignored):

```json
{ "enabledMcpjsonServers": ["causeway"] }
```

Use **project scope, not user scope**. The paths here are relative to the repository, so a
user-scoped registration would try to launch the server in every project you open.

Confirm registration:

```bash
claude mcp list
```

## 8. Verify the server is actually serving

Start Claude Code from the repository root and check the server's stderr banner, or run `/mcp` in
session. Four separate facts, each verified rather than assumed:

```
causeway run run_1a2b3c4d5e6f0000
evidence ledger: Neo4j (persistent)
github: authenticated as <your-login>
causeway 0.1.0 serving 45 of 48 catalogued tools
```

| Line | What to require |
|---|---|
| `causeway run ...` | Present. The orchestrator adopts its own runId on the first call. |
| `evidence ledger` | Must say **Neo4j (persistent)**. `in-memory` means the run's findings vanish at exit. |
| `github` | `authenticated as <login>`. `TOKEN REJECTED` or `no token found` means forge steps become gaps. |
| `serving N of M` | **45 of 48.** The three unserved are deliberate; see the catalog table below. |

Stdio is the protocol channel, so all diagnostics go to stderr. Anything written to stdout that is
not a JSON-RPC frame corrupts the stream.

## 9. The `.claude` layer

This is the part that makes the repository an agentic workflow rather than a library. All of it is
committed except `settings.local.json`.

```text
.claude/
  agents/
    mining-strategist.md         opus    where is there minable signal, and is the harvest enough?
    build-doctor.md              sonnet  how does this repository build, test and get entered?
    bugfix-adjudicator.md        sonnet  is this candidate commit a genuine bug fix?
    symptom-characterizer.md     sonnet  what did an observer see, and where did it surface?
    reproducer-synthesist.md     opus    what input makes this bug manifest?
    path-tracer.md               opus    what route connects the symptom to the fault?
  commands/
    mine-repo.md                 the orchestrating slash command, steps S0..S13
  skills/
    add-tool/SKILL.md            how to add a deterministic tool
    add-agent/SKILL.md           whether a proposal is an agent at all, and how to add one
  hooks/
    telemetry.mjs                call-shape telemetry; every failure path exits 0
  settings.json                  permissions (GENERATED) + hooks (hand written)
  settings.local.json            machine-local MCP approvals, gitignored
```

### settings.json

The `permissions` block is **generated from the schemas** — never hand-edit it. The `hooks` block is
hand-maintained and preserved by the generator.

```bash
node tools/sync-catalog.mjs --write     # regenerate permissions from schemas/tools/
```

It currently allows 47 `mcp__causeway__*` tools and wires the telemetry hook onto five events:

```json
{
  "permissions": {
    "allow": ["mcp__causeway__evidence_attest", "mcp__causeway__export_run", "..."],
    "ask": []
  },
  "hooks": {
    "PostToolUse":        [{ "matcher": "*", "hooks": [{ "type": "command", "command": "node .claude/hooks/telemetry.mjs" }] }],
    "PostToolUseFailure": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "node .claude/hooks/telemetry.mjs" }] }],
    "SubagentStart":      [{ "hooks": [{ "type": "command", "command": "node .claude/hooks/telemetry.mjs" }] }],
    "SubagentStop":       [{ "hooks": [{ "type": "command", "command": "node .claude/hooks/telemetry.mjs" }] }],
    "SessionEnd":         [{ "hooks": [{ "type": "command", "command": "node .claude/hooks/telemetry.mjs" }] }]
  }
}
```

The hook records that a call happened and the **shape** of its arguments — never values, never
responses. Telemetry is not an evidence source, and a hook that fails must never take a run down,
so every failure path exits zero.

## 10. The six agents and their system prompts

Each agent is one markdown file: YAML frontmatter is the contract (name, description, granted
tools, model), the body is the system prompt. Tool grants must match `x-causeway.agents` in the
schemas in **both** directions, which `sync-catalog` enforces.

| Agent | Model | Tools | Objective, as written in the file |
|---|---|---|---|
| [`mining-strategist`](.claude/agents/mining-strategist.md) | opus | 9 | "decide WHERE in this repository's history there is minable signal, and WHETHER what has been harvested so far is sufficient. You do not analyse individual bugs." |
| [`build-doctor`](.claude/agents/build-doctor.md) | sonnet | 8 | "produce a `BuildRecipe` that actually works — and if none does, say so with the evidence." |
| [`bugfix-adjudicator`](.claude/agents/bugfix-adjudicator.md) | sonnet | 13 | "for each candidate in your batch, decide `BUG_FIX`, `NOT_BUG_FIX`, or `UNDECIDED`, with evidence." |
| [`symptom-characterizer`](.claude/agents/symptom-characterizer.md) | sonnet | 10 | "reconstruct the OBSERVABLE MANIFESTATION of the fault, and identify the **entry point** where it surfaced. You do not explain the cause. The path-tracer owns that." |
| [`reproducer-synthesist`](.claude/agents/reproducer-synthesist.md) | opus | 17 | "write a harness whose execution **differs** between the buggy revision and the fixed one. That difference is proof the input triggers the bug." |
| [`path-tracer`](.claude/agents/path-tracer.md) | opus | 19 | "produce the ordered path from where the symptom surfaced to the code responsible for it, with every hop backed by a retrieved relation." |

A complete agent file, for shape:

```markdown
---
name: bugfix-adjudicator
description: Decides whether each candidate commit is a genuine bug fix, as opposed to a
  feature, refactor, test change, revert, dependency bump, or cosmetic edit. Processes a
  batch of candidates at once. Use after candidate generation, before any expensive analysis.
tools: mcp__causeway__forge_bundle_candidates, mcp__causeway__history_commit_meta,
  mcp__causeway__history_diff, mcp__causeway__history_file_at, mcp__causeway__forge_issue,
  mcp__causeway__forge_issue_comments, mcp__causeway__forge_pr_for_commit,
  mcp__causeway__forge_ci_status, mcp__causeway__jvm_tests_for_change,
  mcp__causeway__findings_record, mcp__causeway__notes_read, mcp__causeway__notes_write,
  mcp__causeway__notes_list_subjects
model: sonnet
---

Your objective: for each candidate in your batch, decide `BUG_FIX`, `NOT_BUG_FIX`, or
`UNDECIDED`, with evidence.

A genuine bug fix corrects behaviour that was already wrong.

**Not bug fixes:** new features, pure refactors, dependency bumps, formatting, build config.

## Recording

Record every verdict with `findings_record`, citing the evidence ids you actually retrieved.
You may not assert anything you did not retrieve. If you find yourself wanting to state
something you did not fetch, fetch it.

`UNDECIDED` is a real answer. Use it when the evidence genuinely does not settle the question.
```

Five properties every agent file has, and any new one must:

1. **The objective is the first line**, so the agent can tell in one sentence whether a task is
   its own.
2. **Minimum tool grants.** An agent that does not need the forge does not get the forge, so a
   reasoning mistake cannot become an unexpected API call.
3. **It states what it does not own.** The symptom characterizer's file names the path-tracer
   explicitly. Without such a line agents drift into each other's territory.
4. **The uncertain answer is legitimate and named.** If `UNDECIDED` is not clearly allowed, the
   agent produces a confident answer instead.
5. **The model is chosen per agent.** Bounded classification on sonnet, open-ended construction on
   opus.

Every agent is granted `findings_record` plus the three notes tools. Nothing else is universal.

## 11. The skills

Two skills encode the two procedures that are easy to get wrong. Invoke with `/add-tool` and
`/add-agent`, or let them trigger from their descriptions.

| Skill | Use it when | What it enforces |
|---|---|---|
| [`add-tool`](.claude/skills/add-tool/SKILL.md) | a new deterministic capability is needed — a git query, a build operation, a coverage lookup, a graph write, a metric | Schema before implementation, and the four places the catalog is written down stay in sync |
| [`add-agent`](.claude/skills/add-agent/SKILL.md) | before creating any new `.claude/agents/*.md` | The distinct-question test; most proposals that reach it should not become agents |

## 12. The orchestrating command

[`.claude/commands/mine-repo.md`](.claude/commands/mine-repo.md) is the only place that knows the
step order, the gates and the fan-out ceilings.

```markdown
---
description: Mine a public GitHub repository for symptom-to-fault paths on important, well-covered bugs
argument-hint: <github-url> [--window 12m] [--cap 60] [--deep 5] [--resume <runId>]
---

Mine bug paths from: $ARGUMENTS

Run the stages in order. Each stage's output is the next stage's input, and every stage
checkpoints, so an interrupted run resumes rather than restarts.
```

| Argument | Default | Meaning |
|---|---|---|
| `<github-url>` | required | Public repository to mine |
| `--window` | `12m` | How far back in history to look |
| `--cap` | `60` | Maximum candidates to adjudicate |
| `--deep` | `5` | How many top-ranked bugs get symptom, reproducer and path work |
| `--resume` | — | Continue an existing `runId` instead of starting fresh |

Fan-out ceilings, set in the command and nowhere else:

```
adjudication:              waves of at most 5
symptom characterization:  waves of at most 5
path tracing:              waves of at most 4
```

## 13. The causeway tool list

48 catalogued, 45 served. Class **P** = pipeline service (orchestrator only), **E** = evidence query
(agents), **X** = experiment (executes agent-authored code), **R** = recording, **N** = notes.
"Agents" is how many of the six are granted the tool; `0` means pipeline-only.

### Class P — pipeline services (18)

| Tool | Module | Purpose |
|---|---|---|
| `repo_clone` | vcs | Clone a public repository in full |
| `repo_checkout` | vcs | Materialise one commit into an isolated worktree |
| `repo_detect_ecosystem` | vcs | Determine which analysis pipeline this repository routes to |
| `szz_baseline` | vcs | Fault-inducing commit identification via PySZZ |
| `jvm_build_probe` | jvm | Compile one revision in a container (also granted to `build-doctor`) |
| `jvm_coverage_run` | jvm | Run the test suite under coverage instrumentation in a container |
| `jvm_callgraph_build` | jvm | Construct a call graph from compiled bytecode |
| `jvm_native_test_run` | jvm | Run the project's own test at both revisions and compare |
| `repro_botsing` | jvm | Crash reproduction from a stack trace — *catalogued, not wired* |
| `repro_evosuite_sweep` | jvm | Differentially filtered EvoSuite sweep — *catalogued, not wired* |
| `metrics_churn` | metrics | Size and spread of a change |
| `metrics_complexity_delta` | metrics | Cyclomatic complexity of changed methods, before and after |
| `metrics_coverage_impact` | metrics | Were the faulty lines covered before the fix, and the coverage delta |
| `metrics_bug_lifetime` | metrics | Days from fault introduction to report and to fix |
| `rank_importance` | metrics | Rank by coverage of changed lines, blast radius, symptom distance |
| `graph_checkpoint` | graphstore | Mark one `(stage, key)` complete so an interrupted run resumes |
| `graph_upsert_bug` | graphstore | Persist one bug and everything attached to it |
| `export_run` | graphstore | Export a run to JSON so results survive a wiped database |

### Class E — evidence queries (24)

| Tool | Module | Agents | Purpose |
|---|---|---|---|
| `history_list_commits` | vcs | 1 | Walk commit history within a window |
| `history_candidates` | vcs | 1 | Run the recall nets over a window, returning the nets that matched |
| `history_commit_meta` | vcs | 4 | Full metadata for one commit, with parsed trailers and issue references |
| `history_diff` | vcs | 3 | Diff a commit against its parent, rename detection ON |
| `history_blame` | vcs | 1 | Blame a line range, ignoring whitespace-only changes |
| `history_file_at` | vcs | 5 | Read a file, or a slice, as it existed at a given commit |
| `forge_bundle_candidates` | forge | 1 | Batched bundle for many candidates in one GraphQL round trip |
| `forge_issue` | forge | 4 | Fetch one issue |
| `forge_issue_comments` | forge | 4 | Fetch an issue discussion thread |
| `forge_pr_for_commit` | forge | 2 | Resolve the PR that introduced a commit, with the issues it closes |
| `forge_ci_status` | forge | 2 | Check runs for a commit |
| `jvm_build_toolchain` | jvm | 1 | Declared toolchain at a revision: build tool, source level, plugin config |
| `jvm_coverage_lines` | jvm | 1 | Per-line coverage for a range |
| `jvm_callgraph_paths` | jvm | 1 | Find call paths between two methods |
| `jvm_callgraph_callers` | jvm | 2 | Transitive callers of a method, to a bounded depth |
| `jvm_callgraph_chains_to` | jvm | 2 | Chains from public entry points to a target, with parameter types per hop |
| `jvm_class_hierarchy` | jvm | 2 | Supertypes, subtypes and overrides for a type |
| `jvm_method_at_line` | jvm | 2 | Resolve file and line to the enclosing method, via the debug line table |
| `jvm_method_body` | jvm | 2 | Source of a method plus a bytecode summary |
| `jvm_tests_for_change` | jvm | 3 | Test files and methods added or modified by a commit |
| `jvm_trace_executed` | jvm | 2 | The set of methods actually executed by a reproducing run |
| `jvm_trace_path_in` | jvm | 1 | Path between two methods **confined to what an execution actually ran** |
| `graph_query` | graphstore | 1 | Run a named, parameterised query |
| `graph_resume_state` | graphstore | 1 | What a prior run on this repository already completed |

### Class X — experiment (1)

| Tool | Module | Agents | Purpose |
|---|---|---|---|
| `jvm_harness_run` | jvm | 1 | Compile an agent-authored harness against **both** revisions, run both in a network-isolated container, and return `differs` |

This is the only tool that executes code an agent wrote. The agent does not judge whether the bug
triggered; the two executions decide it.

### Class R — recording (2)

| Tool | Module | Agents | Purpose |
|---|---|---|---|
| `findings_record` | mcpserver | 6 | The only way an agent may persist a claim. Rejects any evidence id the ledger did not issue |
| `evidence_attest` | mcpserver | 0 | Register an externally obtained payload as `provenance=Attested`. **Granted to no agent by design, and correctly unserved** |

### Class N — notes (3)

| Tool | Module | Agents | Purpose |
|---|---|---|---|
| `notes_read` | graphstore | 6 | Read notes for this repository, filtered by kind or subject |
| `notes_write` | graphstore | 6 | Record procedural knowledge that is expensive to re-derive |
| `notes_list_subjects` | graphstore | 6 | Cheap index of what notes exist — kinds and subjects only |

Notes carry `note_` identifiers; `findings_record` requires `^ev_`. A note therefore cannot back a
claim — not by convention, by construction.

### The three unserved tools

| Tool | Why |
|---|---|
| `evidence_attest` | Granted to no agent by design; every identifier in the graph stays server-issued |
| `repro_botsing` | External tool needing its own harnessing; ladder rung 2 |
| `repro_evosuite_sweep` | External tool needing its own harnessing; ladder rung 4 |

Names are dotted in prose (`history.diff`) and underscored on the wire
(`mcp__causeway__history_diff`). **The wire form is authoritative.**

## 14. Run the verification gates

Four checks, each catching a distinct class of drift. Run all four after any change to the catalog,
the handlers or the agents.

```bash
node tools/sync-catalog.mjs --check     # schemas and client permissions agree, both directions
node tools/check-agents.mjs             # every granted tool is actually served
node tools/check-params.mjs --check     # every served tool reads every parameter it declares
node tools/check-outputs.mjs --check    # no handler emits a field its output schema forbids
```

| Gate | What it catches |
|---|---|
| `sync-catalog` | An agent listing a tool no schema grants it, or a schema granting access an agent does not declare. Also regenerates the `permissions` block with `--write` |
| `check-agents` | A tool that is catalogued and granted but has no handler — invisible until an agent calls it mid-run |
| `check-params` | A parameter a schema declares and a handler never reads. It is accepted, dropped, and a plausible answer to a *different question* comes back |
| `check-outputs` | A response field no schema declares. Output is validated with `additionalProperties: false`, so the whole call fails as an error rather than as data — and only on the branch that emits it |

The last two exist because the catalog is written **before** the handlers, so nothing challenges a
schema until an implementation disagrees with it.

`check-params.mjs` keeps an explicit list of the deliberately unimplemented tools, each with a
reason, so the gate passes today and still catches tomorrow's regression:

```javascript
const unimplemented = {
  evidence_attest:      "granted to no agent by D22; correctly unserved",
  repro_botsing:        "external tool, needs its own harnessing",
  repro_evosuite_sweep: "external tool, needs its own harnessing",
};
```

## 15. First run

Start Claude Code from the repository root, confirm the banner from step 8, then:

```
/mine-repo https://github.com/jhy/jsoup --window 6m --cap 25 --deep 2
```

**Choosing a first repository.** Pick one that is small, actively maintained, single module, and
needs no external services to test. Recent history resolves its dependencies cleanly, which keeps
the build gate from dominating the first run. jsoup fits; so do most self-contained parsing or
utility libraries.

Start with a small window, a small cap and a small deep count. The first run is diagnostic: you are
finding out whether the agent layer's judgement holds up, and two deeply analysed bugs demonstrate
that as clearly as twenty.

What the run does, in order — the detail is in [README.md](README.md):

```
S0  ingest            repo_clone, repo_detect_ecosystem, graph_resume_state
S1  environment       build-doctor            -> BuildRecipe, proved by a probe   [hard gate]
S2  candidates        mining-strategist       -> K, tagged by recall net
S3  bundling          forge_bundle_candidates -> issues, PRs, CI, batched
S4  adjudication      bugfix-adjudicator x5   -> F
S5  structure         history_diff, jvm_method_at_line, szz_baseline
S6  build gate        jvm_build_probe at parent and fix                           [hard gate]
S7  coverage + CG     jvm_coverage_run, jvm_callgraph_build, at the PARENT
S8  ranking           rank_importance         -> importance + scoredOn
S9  symptom           symptom-characterizer x5 -> symptomClass, entryPoint
S10 reproducer        ladder, rung 1 first; rung 3 dispatches an agent
S11 path              path-tracer x4          -> path, pathFidelity   <-- deliverable
S12 persistence       metrics, graph_upsert_bug, export_run
S13 report            admissions, tiers, fidelity, and the Unknown breakdown in full
```

A full pass over jsoup produced 19 admitted bugs, 18 native reproducers, 18 traced paths and 94.2%
of path steps observed rather than static. See [example.md](example.md).

## 16. Watch, resume, and collect output

### Watch without disturbing the run

```bash
docker exec causeway-neo4j cypher-shell -u neo4j -p causeway-dev --format plain \
  "MATCH (c:Checkpoint {runId:'run_...'})
   RETURN c.stage,
          sum(CASE WHEN c.status='DONE' THEN 1 ELSE 0 END) AS done,
          sum(CASE WHEN c.status<>'DONE' THEN 1 ELSE 0 END) AS pending
   ORDER BY c.stage;"
```

This is the same aggregation the resume logic uses, so it tells you exactly where a resume would
pick up.

### Resume

```
/mine-repo https://github.com/jhy/jsoup --resume run_f7997c1a240cc238
```

Handles held only in server memory do not survive a restart, so the server journals what each was
derived from at `workspace/handles.jsonl` and rehydrates on a lookup miss. A coverage report is
re-parsed from an exec file still on disk in seconds, rather than re-running a test suite for an
hour.

A retried step must **reuse the original checkpoint key**, or explicitly name what it supersedes. A
retry under a new key leaves the failed row outstanding forever, and that step can then never read
as complete.

### Where output lands

| Path | Contents |
|---|---|
| `runs/<session-id>/manifest.json` | repo, window, caps, config snapshot, linked runIds |
| `runs/<session-id>/telemetry/tool-calls.jsonl` | every tool call, attributed to the agent that made it |
| `runs/<session-id>/telemetry/agent-turns.jsonl` | subagent start/stop |
| `runs/<session-id>/usage.json` | tokens and cost — `node tools/collect-usage.mjs <session>` |
| `runs/<session-id>/report.md` | human-readable run summary |
| Neo4j | `Run`, `Checkpoint`, `Evidence`, `Finding`, `Bug`, `Commit`, `Repository` |
| `export_run` output | the whole run as JSON or JSONL, including bugs that failed admission |
| `notes/<owner>__<repo>/` | non-evidentiary agent knowledge, carried across runs |
| `workspace/` | clones, per-revision worktrees, dependency caches, handle journal — gitignored |

`runs/` is tracked in git. It is research output, not scratch.

Fill in `runs/rates.json` with per-MTok prices before relying on cost accounting.

## 17. Adding a tool or an agent later

**A tool**, in this order, every time — or run `/add-tool`:

1. Write the schema in `schemas/tools/`, including `x-causeway.agents` and `unknownReasons`.
2. `node tools/sync-catalog.mjs --write`.
3. Implement the service in the owning module, with unit tests that do not need the server.
4. Implement the handler, reading every declared parameter and emitting only declared fields.
5. Run all four gates.
6. `sbt -batch "app/dist"`.
7. **Restart the Claude Code session.** A server cannot attach mid-session.

Skipping step 1 costs the most: a handler written before its schema encodes assumptions the schema
then has to accommodate, and the schema stops being a contract.

**An agent** — run `/add-agent`, which applies the tests first. It is a *tool* if the answer is
deterministic. It is *not a new agent* if an existing one already owns that question; extend that
one. If it does warrant an agent:

1. Write `.claude/agents/<name>.md` with the objective on the first line.
2. Grant the minimum tool set in the frontmatter.
3. Add the agent name to `x-causeway.agents` in each schema it needs.
4. `node tools/sync-catalog.mjs --write`, then `node tools/check-agents.mjs`.
5. Add the dispatch to `mine-repo.md`, with a fan-out ceiling.
6. State in the agent file which adjacent questions it does **not** own.

## 18. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `evidence ledger: in-memory` | Neo4j unreachable at startup | Start the container, then restart the session. Do not run: findings are lost at exit |
| `github: TOKEN REJECTED` | `${GITHUB_TOKEN}` survived unexpanded, or the PAT expired | The server ignores `${...}` placeholders; put the real value in `.env` |
| `serving 0 of N` | `target/dist/lib` missing or stale | `sbt -batch "app/dist"` |
| `causeway failed to start: handlers with no schema` | A handler was added before its schema | Write the schema; the registry refuses to start otherwise, by design |
| MCP server not listed | Project server never approved | Accept the prompt, or add `enabledMcpjsonServers` to `.claude/settings.local.json` |
| Build fails as `MissingToolchain` for every repo | Container invoked with a login shell | Use `sh -c`, never `sh -lc`: `-l` re-initialises `PATH` and discards the image's own JDK path |
| Every build re-downloads the world | Dependency cache not bind-mounted | Check `workspace/caches` and the per-tool env redirects |
| Coverage is empty though the suite ran green | JaCoCo attached via Surefire `argLine`, which a project-level `<argLine>` overrides | The agent attaches through `JAVA_TOOL_OPTIONS`, honoured unconditionally and additively |
| `AccessDeniedException` moving a module jar | The sbt 2 background server holds open jars | `sbt -batch shutdown`, then rebuild |
| `Not a valid key` for a key that exists | Same background server, stale build definition | `sbt -batch shutdown` |
| A new env var is invisible to the build | The sbt server kept the environment it started with | `sbt -batch shutdown`; note this is why `distTo` takes an argument |
| A call-graph handle returns "rebuild" | LRU eviction, bound is 2 | Rebuild it, or raise `CAUSEWAY_CALLGRAPH_CACHE` |
| A Python helper broke a multi-line source fixture | Text-mode write converted LF to CRLF on Windows | Sources are LF; any script rewriting a source file must pass `newline=''` |

## 19. Checklist

Ordered so each item is verifiable before the next.

**Environment**

- [ ] `java -version` is 17, `sbt --version` is 2.x, `node --version` is 18+, `docker info` succeeds
- [ ] `claude --version` prints, and `claude -p "reply with the single word ready"` answers
- [ ] Neo4j container is up on 7475/7690

**Configuration**

- [ ] `.env` holds `GITHUB_TOKEN` and the three `NEO4J_*` values, and is gitignored
- [ ] `.mcp.json` is unmodified and contains no secrets
- [ ] `.claude/settings.local.json` enables the `causeway` server

**Build**

- [ ] All eight modules' tests run, one `sbt -batch` invocation each, per-module counts checked
- [ ] `sbt -batch "app/dist"` populated `target/dist/lib`

**Gates**

- [ ] `sync-catalog --check` passes
- [ ] `check-agents` passes
- [ ] `check-params --check` passes
- [ ] `check-outputs --check` passes

**Server**

- [ ] Banner says `evidence ledger: Neo4j (persistent)`
- [ ] Banner says `github: authenticated as <login>`
- [ ] Banner says `serving 45 of 48 catalogued tools`

**Agent layer**

- [ ] Six agent files present, each with objective on the first line and a model set
- [ ] `mine-repo.md` present, with the fan-out ceilings
- [ ] Both skills present
- [ ] Telemetry hook wired on all five events

**First run**

- [ ] Small window, small cap, small deep count
- [ ] Progress observable by querying checkpoints
- [ ] Resume verified by interrupting deliberately and resuming
- [ ] `runs/<session-id>/` contains telemetry, usage and a report
