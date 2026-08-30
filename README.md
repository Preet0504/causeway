# Causeway
[![Scala 3](https://img.shields.io/badge/Scala-3.3-red.svg)](https://www.scala-lang.org/)
[![MCP tools](https://img.shields.io/badge/MCP-48%20catalogued%20%2F%2045%20served-blue.svg)](schemas/tools/)
[![Agents](https://img.shields.io/badge/reasoning%20agents-6-green.svg)](.claude/agents/)
[![Observed paths](https://img.shields.io/badge/observed%20path%20steps-94.2%25-brightgreen.svg)](example.md)

**Mining symptom-to-fault paths from public repository history.**

---

Causeway takes a public GitHub repository URL and returns a structured dataset of historical bugs.
For each bug it records not only where the fault was and what the user observed, but **the route
that connects them**: the ordered sequence of code locations through which a defect propagated
from the line that was wrong to the behaviour someone complained about. Where the bug can be made
to manifest, that route is not argued for — it is executed, and every hop in it is a method that
actually ran.

Causeway is an agentic workflow rather than a program with a language model bolted on.
Deterministic work is performed by a purpose-built [MCP](https://modelcontextprotocol.io) server
exposing 48 catalogued tools over git, the GitHub GraphQL API, containerised JVM builds, SootUp
call graphs, JaCoCo coverage and Neo4j. Reasoning is performed by six subagents, each answering
exactly one distinct question. An orchestrating slash command sequences fourteen stages, dispatches
agents in bounded waves, and checkpoints after each stage so an interrupted run resumes rather than
restarts.

The system's governing constraint is that **the model may not invent facts about the repository**.
It does not read a diff and conclude that a value flows somewhere; it calls a tool that returns the
flow. Every payload the server returns is hashed into an evidence identifier and recorded in a
ledger, and the only write path for agent claims validates every cited identifier against that
ledger. An agent cannot assert something it did not retrieve — not because it was instructed not
to, but because the write path rejects the attempt.

Causeway targets JVM repositories (Java and Scala) today, with an LLVM peer module specified and
deferred. The two ecosystem modules are strictly independent in both directions, so adding a third
requires touching neither.

## Table of contents

- [Project vision](#project-vision)
- [Architectural principle](#architectural-principle)
- [What this task is, and what it is not](#what-this-task-is-and-what-it-is-not)
- [Target repositories](#target-repositories)
- [Model assignment](#model-assignment)
- [Repository layout](#repository-layout)
- [The central mechanism: observed paths](#the-central-mechanism-observed-paths)
- [Design principles](#design-principles)
- [Architecture](#architecture)
- [The data model](#the-data-model)
- [The tool catalog](#the-tool-catalog)
- [The agent layer](#the-agent-layer)
- [Step 0. Ingest the repository](#step-0-ingest-the-repository)
- [Step 1. Establish the build environment](#step-1-establish-the-build-environment)
- [Step 2. Generate candidates with recall nets](#step-2-generate-candidates-with-recall-nets)
- [Step 3. Bundle forge metadata](#step-3-bundle-forge-metadata)
- [Step 4. Adjudicate bug fixes](#step-4-adjudicate-bug-fixes)
- [Step 5. Extract fault coordinates and lineage](#step-5-extract-fault-coordinates-and-lineage)
- [Step 6. Apply the build gate](#step-6-apply-the-build-gate)
- [Step 7. Measure coverage and build the call graph](#step-7-measure-coverage-and-build-the-call-graph)
- [Step 8. Rank bugs by importance](#step-8-rank-bugs-by-importance)
- [Step 9. Characterize the symptom](#step-9-characterize-the-symptom)
- [Step 10. Acquire a reproducer](#step-10-acquire-a-reproducer)
- [Step 11. Trace the symptom-to-fault path](#step-11-trace-the-symptom-to-fault-path)
- [Step 12. Compute metrics and persist](#step-12-compute-metrics-and-persist)
- [Step 13. Report what the run could and could not support](#step-13-report-what-the-run-could-and-could-not-support)
- [Variable reference](#variable-reference)
- [Execution and isolation model](#execution-and-isolation-model)
- [Persistence, checkpointing and resume](#persistence-checkpointing-and-resume)
- [Notes versus evidence](#notes-versus-evidence)
- [Evaluation metrics](#evaluation-metrics)
- [Implementation status](#implementation-status)
- [Framework choices](#framework-choices)
- [References](#references)
- [End to end data flow](#end-to-end-data-flow)
- [Build and operate](#build-and-operate)

## Project vision

Causeway is built around one practical goal. A researcher should be able to name a public
repository and receive a dataset in which each entry says: this is what a user observed, this is
the code that was wrong, and **this is how the second became the first** — with each step in that
route backed by a retrieved fact rather than by an argument.

Software defect datasets are the raw material for fault localization, automated program repair,
test generation and defect prediction. The datasets in wide use, such as Defects4J, are hand
curated. Curation gives them high quality and also fixes their size and their scope. Extending one
to a new project, a new language, or a new decade of history is a manual undertaking.

Repository history, by contrast, is enormous and free. Every merged bug fix in a public project is
a labelled example: the commit tells you what the correct behaviour is, its parent tells you what
the incorrect behaviour was, and the linked issue often tells you what a user actually observed.
The obstacle is not availability. It is that raw history is noisy, most of it is unusable, and
separating signal from noise requires judgement that does not reduce to a regular expression.

Consider the question *is this commit a bug fix?* A commit message containing the word "fix" is
weak evidence at best: projects use it for refactors, for documentation, for build changes, and
for fixing a previous fix. A commit that touches a test file may be adding coverage rather than
correcting behaviour. A commit that reverts another may be a bug fix or a rollback of an unrelated
experiment. Deciding correctly requires reading the diff, reading the linked issue, noticing
whether the change alters a condition or merely renames a symbol, and weighing those against each
other.

That is reasoning, and it is exactly what a language model is good at. It is also exactly what a
language model should not be trusted to do unsupervised, because a model asked "is this a bug
fix?" will produce a confident answer with a plausible justification whether or not it looked at
anything. Causeway's design is the resolution of that tension.

## Architectural principle

The implementation follows a strict division of labour.

Deterministic analyzers build the map: git supplies history, diffs and blame; the forge supplies
issues, pull requests and CI conclusions; SootUp supplies call graphs; JaCoCo supplies coverage;
containers supply execution. The evidence ledger stores the memory of every payload those
analyzers returned. The MCP boundary refuses any call that is not catalogued, permitted and
schema-valid in both directions. The language model performs grounded abduction over what came
back, and nothing else.

This division is necessary because a language model should not be trusted to reconstruct
whole-program control flow or data flow by reading code alone. It can summarize, compare, explain
and hypothesize, but the authoritative facts must come from tools. Causeway therefore exposes
history queries, forge queries, build probes, coverage measurement, call-graph construction,
execution tracing and differential experiment execution as callable tools.

Two properties pull against each other and both are required:

1. **Adaptive reasoning.** An agent must decide its next action from what the previous call
   actually returned, rather than following a fixed script that cannot cope with the variety of
   real repositories.
2. **Verifiable grounding.** Every factual claim must be traceable to a specific retrieved
   payload, so a reader of the dataset can distinguish what was measured from what was inferred
   and from what was invented.

Causeway separates them mechanically rather than by convention. Deterministic operations are
services with one right answer, exposed as tools. Reasoning operations are agents that call those
tools. Between them sits an evidence ledger, and a recording tool that **refuses** any claim citing
an identifier the ledger never issued.

## What this task is, and what it is not

This is **not fault localization**.

In fault localization the fault site is unknown and the task is to predict it, typically scored
with a metric such as hit@k. Causeway knows both endpoints by construction:

- **The fault** is known because the fix commit's diff names exactly which lines changed. The
  faulty lines are those lines in the parent revision.
- **The symptom** is known because the linked issue, the commit message, or an added regression
  test describes what was observed.

The deliverable is the **route between two known points**:

```
KNOWN:      S(f)      the symptom       (what was observed)
KNOWN:      H(f)      the fault site    (what the fix changed, in parent coordinates)
UNKNOWN:    Pi(f)     the path          <-- the deliverable
```

Concretely, that means several choices that would be wrong for a localization system:

| Localization would | Causeway does |
|---|---|
| Hide the fix from the model to avoid leakage | Give agents the fix; it defines one endpoint |
| Score with hit@k against held-out ground truth | Score by the fraction of path steps observed rather than assumed |
| Treat a missing signal as evidence against a candidate | Treat a missing signal as neutral and record why it is missing |
| Prefer recall of candidate fault sites | Prefer bugs that build and are well covered, because those admit observation |

Knowing the destination makes traversal **pathfinding rather than search**. An earlier iteration of
the design held out the fix and scored predictions. It was rejected: it answered a different
question, and it discarded the information that makes path extraction tractable.

## Target repositories

Causeway targets JVM and LLVM based repositories. The goal is not to force both into one analysis
mechanism; it is to normalize their evidence into the same domain model, so that call graphs,
coverage reports, executed method sets and paths mean the same thing regardless of which analyzer
produced them.

| Ecosystem | Source of structure | Source of execution | Status |
|---|---|---|---|
| JVM (Java, Scala) | SootUp call graphs, class hierarchy, method bodies at a revision | Maven, Gradle and sbt suites in containers, JaCoCo instrumentation, agent-authored harnesses | Reference implementation |
| LLVM (C, C++) | LLVM IR, debug symbol mappings, IR-level call graphs | native test harnesses in containers | Specified, deferred |

A repository is admissible when its ecosystem is detected, both revisions of a candidate bug
compile, and the faulty lines are exercised by the project's own test suite. Those are not budget
concessions; they are admission and ranking criteria, because a bug whose code will not build or is
never exercised produces a path that cannot be trusted.

## Model assignment

The orchestrator and all six agents run inside Claude Code, which is also the MCP client. Model
selection is per agent, declared in each agent file's frontmatter, because the questions differ in
kind:

| Work | Model | Agents |
|---|---|---|
| Open-ended construction and explanation | opus | `mining-strategist`, `reproducer-synthesist`, `path-tracer` |
| Bounded classification against retrieved evidence | sonnet | `build-doctor`, `bugfix-adjudicator`, `symptom-characterizer` |

Nothing in the tool layer depends on which model called it. The MCP server validates, authorizes
and mints evidence identically for every caller, so swapping a model changes the quality of the
judgement and nothing about the guarantees around it.

## Repository layout

The implementation is a multi-module Scala 3 project managed by sbt, with the agent layer as
markdown under `.claude/`.

```text
causeway/
  build.sbt
  .mcp.json                     MCP server registration, committed, no secrets
  CLAUDE.md                     invariants, loaded into every session
  modules/
    core/                       domain types: Signal, EvidenceId, Finding. No I/O
    vcs/                        JGit: clone, diff, blame, candidate nets, SZZ
    forge/                      GitHub GraphQL v4, batched
    jvm/                        build probe, SootUp, JaCoCo, harness runner
    llvm/                       the peer for LLVM repositories (deferred)
    metrics/                    churn, blast radius, ranking
    graphstore/                 Neo4j, note store
    mcpserver/                  transport, registry, ledger, ACL
    app/                        handler assembly and server entry point
  schemas/
    _common.json                shared definitions
    tools/                      one file per tool, SOURCE OF TRUTH for the catalog
  tools/                        catalog checkers and usage accounting, Node
  .claude/
    agents/                     the six reasoning agents
    commands/mine-repo.md       the orchestrating slash command
    skills/                     add-tool, add-agent
    hooks/telemetry.mjs         call-shape telemetry, never blocking
    settings.json               permissions (generated) and hooks (hand written)
  reference/design.md           full design with the numbered decision log D1-D22
  notes/                        per-repo agent knowledge, non-evidentiary
  runs/                         per-session telemetry, usage and reports
  workspace/                    gitignored: clones, checkouts, caches
```

| Path | Owns | Rule |
|---|---|---|
| `modules/core` | domain types, `Signal`, `EvidenceId`, `Finding` | no I/O, no dependencies |
| `modules/vcs` | JGit, diffs, blame, candidate nets, SZZ | full clone, rename detection on |
| `modules/forge` | GitHub GraphQL v4 | batched, rate limit becomes `Unknown` |
| `modules/jvm` | build probe, SootUp, coverage, harness runner | self-contained ecosystem unit |
| `modules/llvm` | the peer for LLVM repositories | zero dependency on `modules/jvm`, both ways |
| `modules/metrics` | churn, blast radius, ranking | pure total functions over `Signal` |
| `modules/graphstore` | Neo4j and the note store | all writes idempotent `MERGE` |
| `modules/mcpserver` | transport, registry, ledger, ACL | the only place enforcement lives |
| `modules/app` | handler assembly and server entry point | wires services into the registry |
| `schemas/tools/` | tool contracts | source of truth for the catalog |
| `.claude/agents/` | the six reasoning agents | one distinct question each |
| `.claude/commands/` | the orchestrating command | stage sequence and fan-out ceilings |
| `.claude/hooks/` | telemetry capture | must never block a run |
| `runs/` | per-session telemetry, usage, reports | tracked; this is research output |
| `notes/` | per-repo agent knowledge, across runs | non-evidentiary |
| `reference/design.md` | the full design and decision log | cite decision numbers, do not re-derive |

This structure keeps history analysis, forge retrieval, ecosystem analysis, ranking, persistence
and enforcement separate, because git libraries, forge APIs, bytecode frameworks, graph databases
and model providers will all evolve independently.

## The central mechanism: observed paths

### Static analysis over-approximates

A call graph built by static analysis tells you which calls are *possible*. It includes edges that
no execution ever takes: branches never entered, interface implementations never selected,
reflective calls resolved conservatively, dependency-injected wiring the analysis cannot narrow. A
path through a static call graph is therefore a hypothesis, not an observation.

### The intersection that matters

When a revision builds and the faulty code is covered by tests, something better is available.
Obtain an input that makes the bug manifest, execute it, and record which methods actually ran. The
path lives in the intersection:

```
static call graph  G(p(f))        every call that is possible
        INTERSECT
executed methods   Ex(f)          every method that actually ran when the symptom occurred
        =
executed subgraph                 the path lives here, and nowhere else
```

`G(p(f))` supplies structure — which method calls which. `Ex(f)` supplies reality — which of those
calls happened. Neither alone is sufficient: the call graph without execution gives a plausible
route that may never be taken, and execution without the call graph gives an unordered set of
method names with no edges between them.

### Reachability is not triggering

Three separate conditions must hold, and the distinction is load bearing:

| Condition | Meaning | Sufficient? |
|---|---|---|
| **R**eachability | the input causes the faulty code to execute | necessary, not sufficient |
| **I**nfection | that execution produces incorrect internal state | not implied by reachability |
| **P**ropagation | the incorrect state reaches an observable output | this is the symptom |

An input may reach a faulty null check a thousand times and trigger nothing, because the value was
never null on those paths. Reaching the fault is the easy part. The system confirms all three, and
it confirms the third by observation rather than by argument.

### The differential oracle

Deciding "did this input trigger the bug?" would ordinarily require a human to state what correct
behaviour is. Causeway does not need one, because both revisions exist and can both be run:

```
run(input, parent(f))   versus   run(input, f)

  outputs differ  =>  the input triggers the bug, since the fix changed what it does
  outputs same    =>  reached but not infected, so iterate on the input
```

Behavioural difference across the fix boundary **is** the oracle. Observable difference is defined
narrowly and deliberately:

- **thrown exception**: its type and its throw site
- **return value or computed output**

Deliberately excluded are file, log and stdout side effects — noisy because of timestamps, ordering
and absolute paths — and timing or resource behaviour, too flaky to serve as an oracle. A bug that
manifests only through those channels falls to a lower reproducer tier and is recorded as such
rather than being forced into a false positive.

### The reproducer ladder

Obtaining an input that triggers the bug is attempted cheapest first. Each rung produces a **tier**
that is recorded with the bug and that determines whether its path is observed or hypothesised.

| Rung | Method | Tier | Cost |
|---|---|---|---|
| 1 | The fix added a regression test; run it at the parent, where it should fail | **T1 NATIVE** | Free |
| 2 | The symptom carries a stack trace; feed it to a crash reproducer | **T2** | Cheap, deterministic |
| 3 | An agent writes a harness, with a budget of 3 iterations | **T2 or T3** | One agent, several container runs |
| 4 | Search-based test generation with a differential filter | **T3** | Expensive |
| 5 | Nothing reproduces | **T4 STATIC** | Path is hypothesised, and labelled so |

Only rung 3 costs an agent. Bugs whose reproducer arrives free at rung 1 never dispatch one.

### Path tiers

Every extracted path carries a tier stating how much of it was observed:

- **OBSERVED** — every hop appears in the executed subgraph.
- **HYPOTHESIZED** — hops come from the static call graph, with no execution to confirm them.
- **DISCONNECTED** — no path could be constructed between the endpoints, recorded with the reason.

The headline quality number for a run is the fraction of path steps that are observed rather than
assumed.

## Design principles

These are the invariants the whole system is built around. They are enforced mechanically wherever
a mechanism exists.

**1. Every factual claim traces to a tool return.** Agents may not assert anything they did not
retrieve. The recording tool rejects claims citing evidence identifiers the ledger did not issue.

**2. Deterministic work is a service, never agent judgement.** Cloning, diffing, building,
coverage, call graphs, metrics, graph writes. If an operation has one right answer, it is a tool.

**3. Reasoning is an agent, and agents adapt.** Deciding whether a commit is a bug fix, classifying
a symptom, constructing a reproducer, explaining a path. Agents choose their next call from what
the last one actually returned, never from a fixed script.

**4. Agent boundaries are distinct questions, not tool categories.** A call-graph builder is a
service, not an agent. An agent exists when there is a question requiring judgement, and each agent
owns exactly one such question.

**5. A schema exists before anything invokes a tool.** The MCP server refuses at startup to register
a tool with no entry in `schemas/tools/`. This is checked by a test and by a build gate.

**6. A missing signal is neutral, never negative.** The domain uses `Signal[A]` rather than
`Option[A]`. An `Unknown` carries a reason and propagates. There is no `getOrElse`, so a build
failure can never quietly become a coverage of `0.0`.

**7. Prefer an existing tool.** Established analyzers are wrapped rather than reimplemented: JGit,
SootUp, JaCoCo, Neo4j, Botsing, EvoSuite, PySZZ.

## Architecture

### Three layers

```
+---------------------------------------------------------------+
|  ORCHESTRATION                                                 |
|  .claude/commands/mine-repo.md                                 |
|  Sequences steps 0..13, dispatches agents in bounded waves,    |
|  checkpoints after every step.                                 |
+---------------------------------------------------------------+
                  |  dispatch (Task)          |  direct tool calls
                  v                           v
+-------------------------------+  +----------------------------+
|  REASONING AGENTS (6)         |  |                            |
|  .claude/agents/*.md          |  |                            |
|  mining-strategist            |  |                            |
|  build-doctor                 |  |                            |
|  bugfix-adjudicator           |  |                            |
|  symptom-characterizer        |  |                            |
|  reproducer-synthesist        |  |                            |
|  path-tracer                  |  |                            |
+-------------------------------+  |                            |
                  |                |                            |
                  v                v                            |
+---------------------------------------------------------------+
|  MCP BOUNDARY  (modules/mcpserver)                             |
|  schema validation in, ACL check, handler, schema validation   |
|  out, evidence minted and recorded                             |
+---------------------------------------------------------------+
                  |
                  v
+---------------------------------------------------------------+
|  DETERMINISTIC SERVICES                                        |
|  vcs (JGit)   forge (GraphQL)   jvm (SootUp/JaCoCo/Docker)     |
|  metrics      graphstore (Neo4j)                               |
+---------------------------------------------------------------+
```

### Why the MCP boundary is the enforcement point

Every agent action and every orchestrator action crosses one interface. Putting validation, access
control, evidence minting and output checking at that single point means the guarantees hold for
all callers, present and future, without any caller needing to cooperate.

Concretely, one invocation performs the following in order:

```scala
def invoke(tool: String, args: JsonNode, agent: String): Either[InvocationError, ToolResponse] =
  for
    s   <- specs.get(tool).toRight(InvocationError.NoSuchTool(tool))
    _   <- Either.cond(permits(tool, agent), (),
             InvocationError.NotPermittedForAgent(tool, agent, s.agents))
    _   <- validate(compiled(tool)._1, args).left.map(InvocationError.InvalidInput(tool, _))
    _    = adoptRunId(args)
    res <- runHandler(tool, args)
    out <- envelope(s, tool, args, res)
  yield out
```

1. The tool must exist in the catalog.
2. The calling agent must be granted it.
3. The arguments must validate against the declared input schema.
4. The handler runs.
5. The result must validate against the declared output schema.
6. An evidence identifier is minted from the tool name, the arguments and the payload, and
   recorded in the ledger.

### The universal response envelope

Every tool returns the same shape:

```json
{ "ok": true, "evidenceId": "ev_928fe6851c6d5512f201da6e62e48569", "data": { } }
```

or

```json
{ "ok": true, "evidenceId": "ev_...", "unknown": { "reason": "BuildFailed", "detail": "..." } }
```

An `unknown` result is a **success**, not an error. It means the tool was called, it could not
determine the value, and the reason is stated. It carries an evidence identifier and is citable,
which is what makes a recorded gap different from a silent omission. Errors are reserved for
protocol-level problems: no such tool, permission denied, malformed input.

### Handles, not payloads

Call graphs, build trees and clones are far too large to travel through an agent's context window.
Tools that produce them return an opaque **handle**, and the agent queries through it.

```
repo_clone          -> repoHandle    "repo_843ede82fa0baac9"
jvm_build_probe     -> buildHandle   "build_08abc41a411ce424"
jvm_callgraph_build -> callGraphId   "cg_000000001d26b901"
jvm_coverage_run    -> reportId      "cov_000000006d4b2797"
```

Handles are derived from content rather than allocated from a counter, so the same input resolves
to the same handle across a restart and a resumed run does not strand its references.

## The data model

Six files in `modules/core` define the domain. The module has no I/O and no dependencies.

### `Signal[A]`: a value, or a stated reason there is none

<a id="type-signal"></a>

```scala
enum UnknownReason:
  case BuildFailed, TestsFailed, Timeout, NoLinkedIssue, NoSymptom, NoDebugInfo,
       NoReproducer, RateLimited, NotApplicable, Truncated, ToolUnavailable

enum Signal[+A]:
  case Known[+A](value: A, evidence: Vector[EvidenceId]) extends Signal[A]
  case Unknown(reason: UnknownReason, detail: String, evidence: Vector[EvidenceId])
      extends Signal[Nothing]
```

This replaces `Option` throughout the system. The reason is that `Option` invites
`getOrElse(0.0)`, which converts "the build failed so there is no coverage number" into "coverage
was zero". Those two facts must remain distinguishable, because one is a measurement and the other
is the absence of one. A dataset that conflates them ranks a broken build alongside a genuinely
untested line.

`UnknownReason` is a closed enumeration rather than free text, because these reasons are aggregated
across a run into a report of what the dataset can and cannot support. Free text would make that
aggregation unreadable.

An `Unknown` carries evidence identifiers of its own. Calling a tool and receiving "cannot
determine" is itself a retrieved fact, and it is citable.

### `OldLines` and `NewLines`: line numbers that know their revision

<a id="type-linerange"></a>

```scala
sealed trait LineRange:
  def start: Int
  def end: Int

final case class OldLines(start: Int, end: Int) extends LineRange   // parent coordinates
final case class NewLines(start: Int, end: Int) extends LineRange   // fix coordinates
```

This split prevents a bug class that is invisible in review. Suppose a fix replaces line 42 with
lines 42 to 45:

- "was the faulty line covered before the fix?" queries the **parent** with `OldLines(42, 42)`
- "did the new code get covered?" queries the **fix** with `NewLines(42, 45)`

Query the parent about line 45 and you receive the closing brace of a different method. The answer
looks entirely plausible and is entirely wrong, and nothing at runtime signals the mistake. Making
the two sides different types converts that into a compile error. The fault always lives in
old-side lines.

### `EvidenceId` and the ledger

<a id="type-evidence"></a>

```scala
enum Provenance:
  case ServerIssued   // minted by the server from a payload it retrieved itself
  case Attested       // transcribed by an agent from outside the ledger, and marked as such

final case class Evidence(
    id: EvidenceId,
    tool: String,
    argsHash: String,
    payloadHash: String,
    at: Instant,
    runId: RunId,
    provenance: Provenance
)

trait EvidenceLedger:
  def issue(e: Evidence): Unit
  def contains(id: EvidenceId): Boolean
  def get(id: EvidenceId): Option[Evidence]
  def size: Int
```

An `EvidenceId` is a content hash over the tool name, the canonicalised arguments and the returned
payload. Because the hash inputs are recorded, any claim can be traced back to the exact call that
produced it, and the payload hash can be recomputed to verify nothing was altered.

`Provenance` distinguishes payloads the server retrieved itself from anything an agent transcribed.
No agent is currently granted the ability to produce an attested identifier, so in practice every
identifier in the graph is server issued and verifiable.

### `Finding`: a claim that cannot exist without evidence

<a id="type-finding"></a>

```scala
enum ClaimType:
  case Verdict, Symptom, BuildRecipe, Reproducer, Path, Strategy

final case class Finding[+A] private (
    claim: A,
    claimType: ClaimType,
    agent: String,
    subject: String,
    confidence: Double,
    evidence: Vector[EvidenceId],
    gaps: Vector[(UnknownReason, String)]
)
```

The constructor is **private**. The only way to build a `Finding` is `Finding.make`, which takes the
ledger and checks every cited identifier against it. A claim with no evidence, or with evidence the
ledger never issued, is not representable. That is the mechanical form of principle 1: the rule is a
property of the type, not a convention someone must remember.

Rejections are explicit and instructive rather than generic:

```scala
enum FindingRejection:
  case NoEvidence
  case UnknownEvidence(ids: Vector[EvidenceId])
  case ConfidenceOutOfRange(value: Double)
```

The message for `UnknownEvidence` ends with "Retrieve the fact rather than rephrasing the claim",
because the correct response to a rejection is another tool call, not a reworded assertion.

`gaps` records what could not be retrieved and is as important as the evidence. A low-confidence
symptom that states its gaps is useful data. A low-confidence symptom that looks well evidenced is
corrosive.

## The tool catalog

### Schema first, always

`schemas/tools/` holds one JSON file per tool and is the **source of truth** for the catalog. Each
file declares the input schema, the output schema (describing the `data` payload only, since the
envelope is universal), and a block of Causeway-specific metadata:

```json
{
  "name": "jvm_coverage_run",
  "title": "jvm.coverage_run",
  "description": "Run the project test suite under coverage instrumentation in a container...",
  "x-causeway": {
    "class": "P",
    "module": "jvm",
    "permission": "allow",
    "agents": [],
    "unknownReasons": ["BuildFailed", "TestsFailed", "Timeout", "ToolUnavailable", "NotApplicable"]
  },
  "inputSchema":  { "type": "object", "properties": { }, "additionalProperties": false },
  "outputSchema": { "type": "object", "properties": { }, "additionalProperties": false }
}
```

`x-causeway.agents` lists which agents may call the tool. An empty list means pipeline only: the
orchestrating command may call it and no agent may. That list is what generates the permission block
in `.claude/settings.json`, and a checker verifies the two agree in both directions.

Note `additionalProperties: false` on the output schema. A handler that emits an undeclared field
does not have that field ignored; the entire call fails validation. This is deliberate, and it is
why a build gate exists to catch undeclared response fields before they reach a run.

### Tool classes

| Class | Meaning | Invoked by |
|---|---|---|
| **P** | Pipeline service. Expensive, stateful, deterministic. | The orchestrating command |
| **E** | Evidence query. Read-only, small, fast. | Agents |
| **X** | Experiment. Executes agent-authored code. | `reproducer-synthesist` |
| **R** | Recording. `findings_record` and `evidence_attest`. | All agents |
| **N** | Notes. Procedural knowledge, non-evidentiary. | All agents |

Counts across the 48 catalogued tools: 18 P, 24 E, 1 X, 2 R, 3 N.

### The catalog by module

| Module | Count | Tools |
|---|---|---|
| **vcs** | 10 | `repo_clone`, `repo_checkout`, `repo_detect_ecosystem`, `history_list_commits`, `history_commit_meta`, `history_diff`, `history_blame`, `history_file_at`, `history_candidates`, `szz_baseline` |
| **forge** | 5 | `forge_bundle_candidates`, `forge_issue`, `forge_issue_comments`, `forge_pr_for_commit`, `forge_ci_status` |
| **jvm** | 18 | `jvm_build_toolchain`, `jvm_build_probe`, `jvm_coverage_run`, `jvm_coverage_lines`, `jvm_callgraph_build`, `jvm_callgraph_paths`, `jvm_callgraph_callers`, `jvm_callgraph_chains_to`, `jvm_class_hierarchy`, `jvm_method_at_line`, `jvm_method_body`, `jvm_tests_for_change`, `jvm_trace_executed`, `jvm_trace_path_in`, `jvm_harness_run`, `jvm_native_test_run`, `repro_botsing`, `repro_evosuite_sweep` |
| **metrics** | 5 | `metrics_churn`, `metrics_complexity_delta`, `metrics_coverage_impact`, `metrics_bug_lifetime`, `rank_importance` |
| **graphstore** | 8 | `graph_checkpoint`, `graph_resume_state`, `graph_query`, `graph_upsert_bug`, `export_run`, `notes_read`, `notes_write`, `notes_list_subjects` |
| **mcpserver** | 2 | `findings_record`, `evidence_attest` |

### The one experiment tool

`jvm_harness_run` is the only tool that executes code an agent wrote. It compiles a harness against
**both** revisions, runs both under instrumentation in a network-denied container, and returns the
comparison:

```
in:  { fixSha, harnessSource, entryPoint }
out: { compiled, reachedFault, executedMethods, resultAtParent, resultAtFix,
       differs, differsOn: [EXCEPTION | RETURN], traceId }
```

`differs` is the differential oracle in a single boolean. The agent does not judge whether the bug
triggered; the two executions decide it.

## The agent layer

### One agent, one question

Agent boundaries follow reasoning objectives, never tool categories. There is no "callgraph-agent"
because building a call graph is deterministic. There is a `path-tracer` because deciding which of
many possible routes explains the symptom is not.

| Agent | Model | Question it owns | Tools |
|---|---|---|---|
| [`mining-strategist`](.claude/agents/mining-strategist.md) | opus | Where in this history is there minable signal, and is the harvest sufficient? | 9 |
| [`build-doctor`](.claude/agents/build-doctor.md) | sonnet | How does this repository build, test and get entered at a given revision? | 8 |
| [`bugfix-adjudicator`](.claude/agents/bugfix-adjudicator.md) | sonnet | Is this candidate commit a genuine bug fix? | 13 |
| [`symptom-characterizer`](.claude/agents/symptom-characterizer.md) | sonnet | What did an observer actually see, and where did it surface? | 10 |
| [`reproducer-synthesist`](.claude/agents/reproducer-synthesist.md) | opus | What input makes this bug manifest? | 17 |
| [`path-tracer`](.claude/agents/path-tracer.md) | opus | What route connects the symptom to the fault? | 19 |

### How an agent is defined

Each agent is a markdown file with YAML frontmatter listing its granted tools:

```markdown
---
name: bugfix-adjudicator
description: Decides whether each candidate commit is a genuine bug fix, as opposed to a
  feature, refactor, test change, revert, dependency bump, or cosmetic edit.
tools: mcp__causeway__forge_bundle_candidates, mcp__causeway__history_commit_meta,
  mcp__causeway__history_diff, ... mcp__causeway__findings_record
model: sonnet
---

Your objective: for each candidate in your batch, decide `BUG_FIX`, `NOT_BUG_FIX`, or
`UNDECIDED`, with evidence.

A genuine bug fix corrects behaviour that was already wrong.
```

The grant list is checked against `x-causeway.agents` in every schema, in both directions. An agent
listing a tool no schema grants it fails the check, and a schema granting access an agent does not
declare fails equally.

### What agents share

Every agent has `findings_record` and the three notes tools. Nothing else is universal. An agent
that does not need the forge does not get the forge, so a mistake in its reasoning cannot turn into
an unexpected API call.

### Bounded fan-out

Agent dispatch happens in waves with explicit ceilings, set in the orchestrating command:

- adjudication: at most 5 concurrent
- symptom characterization: at most 5 concurrent
- path tracing: at most 4 concurrent

Unbounded fan-out is an anti-pattern. The ceilings exist so a run stays inside ordinary
subscription limits and so container-backed tools are not asked to serve more simultaneous work
than the host can support.

### Tool naming

Tool names are dotted in prose (`history.diff`) and underscored on the wire
(`mcp__causeway__history_diff`). The wire form is authoritative.

---

The fourteen steps below are the pipeline. The orchestrating command is
[`.claude/commands/mine-repo.md`](.claude/commands/mine-repo.md), invoked as:

```
/mine-repo <github-url> [--window 12m] [--cap 60] [--deep 5] [--resume <runId>]
```

Each step's output is the next step's input. Every step checkpoints, so an interrupted run resumes
rather than restarts. Steps are labelled `S0`..`S13` in the code, the command and the checkpoint
table.

## Step 0. Ingest the repository

**Purpose.** Get the repository onto disk and establish whether it can be processed at all.

**Runs.** `repo_clone`, then `repo_detect_ecosystem`, then `graph_resume_state` to discover whether
an incomplete run already exists for this repository.

**Produces.** [`repoHandle`](#var-repohandle), [`runId`](#var-runid), the ecosystem classification,
and the default branch and head SHA.

**Gate.** If the ecosystem is unsupported, the run stops here and reports the detection evidence.
Only JVM and LLVM ecosystems are in scope.

The clone is **full**, never shallow. Blame and SZZ need complete history, and a shallow clone
would silently truncate lineage rather than fail visibly. This step is required because every later
step addresses the repository through the handle it returns, and because resuming is only possible
if the run's identity is established before any work happens.

## Step 1. Establish the build environment

**Purpose.** Determine how this repository builds, so every later step has a working recipe.

**Runs.** Dispatches [`build-doctor`](.claude/agents/build-doctor.md).

**Consumes.** [`repoHandle`](#var-repohandle).

**Produces.** [`buildRecipe`](#var-buildrecipe), cached for the whole run.

```
BuildRecipe = {
  baseImage:       container image, for example maven:3.9.9-eclipse-temurin-17
  buildTool:       maven | gradle | sbt
  buildCommand:    compile only, never tests
  testCommand:     the project's own suite
  entryPoints:     public methods for call-graph construction
}
```

The agent reads the CI workflow, the build file and the declared source level, chooses an image,
and then **proves the recipe works** by calling `jvm_build_probe`. That feedback loop is why
`build-doctor` is one of only two consumers of a class P tool.

**Gate.** If no recipe works, the run stops. Every downstream number would be `Unknown`.

Entry points matter more than they appear to. Wrong entry points produce a call graph that is valid
and useless: it is a correct over-approximation of a program nobody runs.

## Step 2. Generate candidates with recall nets

**Purpose.** Decide where in history to look, and produce a candidate set with high recall.

**Runs.** Dispatches [`mining-strategist`](.claude/agents/mining-strategist.md), which calls
`history_list_commits` and `history_candidates`.

**Consumes.** [`repoHandle`](#var-repohandle), the window and cap arguments.

**Produces.** [`K`](#var-k), the candidate set, with per-net tags.

Three **recall nets** run over the window:

| Net | Signal |
|---|---|
| LEXICAL | commit message patterns suggesting a correction |
| STRUCTURAL | diff shape, for example a changed condition plus a changed test |
| RANDOM_CONTROL | a random sample of commits in the window |

The nets are recall devices, not classifiers; all precision comes from step 4. The control net is
mandatory. It is the only way to estimate what the other two nets **missed**, because it is
unbiased by construction. Precision of the prefilter can be measured from the adjudicated results;
recall cannot, without a sample that no filter selected.

## Step 3. Bundle forge metadata

**Purpose.** Fetch forge metadata for the whole candidate set in as few round trips as possible.

**Runs.** `forge_bundle_candidates` over `K`, batched. Fully deterministic, no agent.

**Consumes.** [`K`](#var-k).

**Produces.** Per-candidate commit metadata, linked issues and pull requests, CI conclusions.

Batching matters because GraphQL complexity limits bite well before the useful batch size does.
Requests are chunked and aliased, and rate limiting maps to `Unknown(RateLimited)` rather than an
exception, so a throttled run degrades instead of failing. This step exists as a separate stage,
rather than being folded into adjudication, so that the same metadata is fetched once and cited by
every agent that needs it.

## Step 4. Adjudicate bug fixes

**Purpose.** Separate genuine bug fixes from everything else.

**Runs.** Partitions `K` into batches of roughly 15 and dispatches
[`bugfix-adjudicator`](.claude/agents/bugfix-adjudicator.md) in waves of at most 5 concurrent.

**Consumes.** [`K`](#var-k) plus the bundled metadata from step 3.

**Produces.** [`F`](#var-f), the candidates verdicted `BUG_FIX`.

Each verdict is one of `BUG_FIX`, `NOT_BUG_FIX`, or `UNDECIDED`, recorded through `findings_record`
with cited evidence. `UNDECIDED` is a real option and is used, because forcing a binary decision on
genuinely ambiguous commits would pollute the dataset in a way that is invisible downstream.

The control-net candidates are interleaved into the batches rather than adjudicated separately, so
the adjudicator cannot treat them differently.

## Step 5. Extract fault coordinates and lineage

**Purpose.** Extract exact fault coordinates and lineage for every confirmed bug fix.

**Runs.** For each `f` in `F`: `history_diff`, `jvm_method_at_line` on the old-side ranges,
`szz_baseline`. Deterministic, no agent.

**Consumes.** [`F`](#var-f).

**Produces.** [`oldRanges`](#var-oldranges) (the faulty lines in parent coordinates),
[`faultMethods`](#var-faultmethods), and [`inducingCommit`](#var-inducingcommit).

SZZ runs for **every** `f`, before any build gate. Blame requires no build, so bugs that later fail
an expensive gate still carry real lineage data rather than being dropped empty. The implemented
variants are B-SZZ and R-SZZ; any other variant returns `Unknown` rather than being silently
substituted.

Rename detection is on for all diffs. Without it a renamed file reads as a wholesale delete plus a
wholesale add, and every line in it appears to be a fault.

## Step 6. Apply the build gate

**Purpose.** Admit only bugs whose parent and fix revisions both compile, because everything
downstream requires execution.

**Runs.** For each `f` in `F`: `jvm_build_probe` at `parent(f)` and at `f`, in containers.

**Consumes.** [`F`](#var-f), [`buildRecipe`](#var-buildrecipe).

**Produces.** [`F_b`](#var-fb), the build-admitted set, and one [`buildHandle`](#var-buildhandle)
per revision.

Each revision is checked out into its **own worktree** under `workspace/checkouts/`, so two probes
can run concurrently without contending for a checkout and the shared clone stays on its default
branch for history queries.

Builds are compile only, never tests. A compile failure surfaces in seconds where a full test run
takes minutes, and this gate runs twice per bug.

Failures are **recorded, not deleted**, with `admitted: false` and a classification:

| Classification | Meaning |
|---|---|
| `DependencyResolution` | artifacts no longer resolvable, common on old commits |
| `JdkMismatch` | recoverable by choosing another base image |
| `MissingToolchain` | the image lacks the build tool |
| `CompileError` | the revision genuinely does not compile |
| `Timeout` | exceeded the wall clock |

Keeping rejected bugs is what makes the dataset's selection bias measurable rather than invisible.

## Step 7. Measure coverage and build the call graph

**Purpose.** Measure how well the faulty lines are tested, and build the structure a path can be
traced through.

**Runs.** For `f` in `F_b`: `jvm_coverage_run` at `parent(f)`, `jvm_callgraph_build` at `parent(f)`.

**Consumes.** [`F_b`](#var-fb), [`buildHandle`](#var-buildhandle), [`oldRanges`](#var-oldranges).

**Produces.** [`reportId`](#var-reportid) and [`callGraphId`](#var-callgraphid) per bug.

Coverage of the changed lines is queried with the **old-side** ranges against the **parent**
revision. This is the single most important coordinate discipline in the system, and it is the
reason `OldLines` and `NewLines` are separate types.

Coverage is measured with the project's own test suite, because the point is to reflect the
project's actual testing discipline rather than a synthetic one. It is aggregate over the run: a
line marked covered was reached by some test, not by an identified one.

## Step 8. Rank bugs by importance

**Purpose.** Choose which bugs deserve expensive deep analysis.

**Runs.** `rank_importance`. Deterministic.

**Consumes.** coverage of changed lines, blast radius, symptom distance, churn.

**Produces.** [`importance`](#var-importance) and [`scoredOn`](#var-scoredon) per bug, and the top
`--deep` selection.

Each dimension is scored independently, and the composite is taken **only over dimensions that are
Known**. `scoredOn` records which those were, and travels with the score forever. A composite over
three dimensions and one over seven are not comparable, and without `scoredOn` the number looks
like it is.

## Step 9. Characterize the symptom

**Purpose.** Reconstruct what an observer actually saw, and where it surfaced.

**Runs.** Dispatches [`symptom-characterizer`](.claude/agents/symptom-characterizer.md) over the
selected bugs, in waves of at most 5.

**Consumes.** the selection from step 8, linked issues, commit messages, added tests.

**Produces.** [`symptomClass`](#var-symptomclass) and [`entryPoint`](#var-entrypoint).

The `entryPoint` is what step 10 aims at and what path length is measured from. It is the public
method through which the symptom became visible, not the method that was wrong.

The agent does not explain the cause. That is the path-tracer's question. This agent answers only
what went wrong as seen from outside, and where it was seen — and its file says so explicitly, so
the two agents do not drift into each other's territory.

## Step 10. Acquire a reproducer

**Purpose.** Obtain an input that makes the bug manifest, so execution can be observed.

**Runs.** Walks the [reproducer ladder](#the-reproducer-ladder), cheapest rung first. Only rung 3
dispatches an agent, [`reproducer-synthesist`](.claude/agents/reproducer-synthesist.md), with a
budget of 3 iterations.

**Consumes.** [`entryPoint`](#var-entrypoint), the fix diff, the symptom, added tests.

**Produces.** [`reproducerTier`](#var-reproducertier) and, when execution succeeded,
[`traceId`](#var-traceid) and the executed method set.

The synthesist's advantage is that it can see the fix. It knows exactly which condition changed, so
it can construct an input that drives execution into that condition rather than searching blindly.

Each iteration calls `jvm_harness_run`, which compiles the harness against both revisions and
reports `differs`. The agent iterates on `differs == false`, meaning the input reached the fault but
did not infect state.

## Step 11. Trace the symptom-to-fault path

**Purpose.** Produce the ordered route from symptom to fault. This is the deliverable.

**Runs.** Dispatches [`path-tracer`](.claude/agents/path-tracer.md), one per admitted bug, in waves
of at most 4.

**Consumes.** [`entryPoint`](#var-entrypoint), [`faultMethods`](#var-faultmethods),
[`callGraphId`](#var-callgraphid), [`traceId`](#var-traceid), [`reportId`](#var-reportid).

**Produces.** [`path`](#var-path) and [`pathFidelity`](#var-pathfidelity).

For a bug with a working reproducer, the tracer intersects the static call graph with the executed
method set and constrains every hop to what actually ran. For a bug at tier T4, the path is drawn
from the call graph alone and must be tiered as hypothesised.

Every hop must be backed by a retrieved relation, obtained through `jvm_callgraph_paths`,
`jvm_callgraph_callers`, `jvm_callgraph_chains_to` or `jvm_trace_path_in`. A hop the tracer cannot
back with a tool return is not written.

## Step 12. Compute metrics and persist

**Purpose.** Compute derived measures and write everything durably.

**Runs.** `metrics_churn`, `metrics_complexity_delta`, `metrics_coverage_impact`,
`metrics_bug_lifetime`, then `graph_upsert_bug` and `export_run`.

All graph writes are idempotent `MERGE` operations on natural keys, so a resumed run overwrites
rather than duplicating.

`export_run` writes the run to a file so results survive a wiped database, in `json` or `jsonl`. The
export includes bugs that failed admission, for the same reason the graph keeps them.

## Step 13. Report what the run could and could not support

**Purpose.** State what the run produced and, equally, what it could not.

Reports:

- candidates examined per net, adjudication precision, estimated recall from the control net
- bugs admitted versus rejected, with the `rejectedFor` breakdown
- reproducer tier distribution and path fidelity distribution
- **the fraction of path steps observed rather than static**, which is the headline number
- the `Unknown` breakdown by reason, in full

The last item is the dataset's honesty report. Every figure must come from a tool response or a
graph query, and a step that produced nothing says so plainly rather than being omitted.

## Variable reference

This section is the map of what flows where. Each entry names the variable, the step that produces
it, the steps that consume it, and its shape.

<a id="var-runid"></a>
### `runId`
- **Produced by** S0
- **Consumed by** every step, all checkpoints, all findings
- **Shape** `run_` followed by 16 hex characters
- Identifies one mining run. Findings, checkpoints and evidence are all filed under it.

<a id="var-repohandle"></a>
### `repoHandle`
- **Produced by** S0 (`repo_clone`)
- **Consumed by** S1, S2, S3, S5, S6, and every history and forge tool
- **Shape** `repo_` followed by 16 hex characters, derived from the clone URL
- Opaque reference to the clone on disk. Because it is content derived, the same URL always
  produces the same handle, across restarts.

<a id="var-buildrecipe"></a>
### `buildRecipe`
- **Produced by** S1 (`build-doctor`)
- **Consumed by** S6, S7
- **Shape** base image, build tool, build command, test command, entry points
- Cached for the whole run. Proved by an actual probe before being returned.

<a id="var-k"></a>
### `K`
- **Produced by** S2 (`mining-strategist`)
- **Consumed by** S3, S4
- **Shape** vector of commit SHAs with per-net tags
- The candidate set. Tags record which recall nets matched each candidate, which is what makes
  per-net precision measurable later.

<a id="var-f"></a>
### `F`
- **Produced by** S4 (`bugfix-adjudicator`)
- **Consumed by** S5, S6
- **Shape** subset of `K` verdicted `BUG_FIX`
- Confirmed bug fixes. `UNDECIDED` candidates are excluded from `F` and retained in the graph.

<a id="var-oldranges"></a>
### `oldRanges`
- **Produced by** S5 (`history_diff`)
- **Consumed by** S5 (`jvm_method_at_line`), S7 (`jvm_coverage_lines`), S11
- **Type** `OldLines`
- The faulty lines, in **parent** coordinates. Never mix with `NewLines`.

<a id="var-faultmethods"></a>
### `faultMethods`
- **Produced by** S5 (`jvm_method_at_line` over `oldRanges`)
- **Consumed by** S10, S11
- **Shape** vector of `MethodRef` (class, name, descriptor)
- The methods containing the faulty lines. One endpoint of the path.

<a id="var-inducingcommit"></a>
### `inducingCommit`
- **Produced by** S5 (`szz_baseline`)
- **Consumed by** S12 (`metrics_bug_lifetime`)
- **Shape** SHA plus confidence plus the filters applied
- The commit that introduced the fault, by blame. Used for bug lifetime, not for path tracing.

<a id="var-fb"></a>
### `F_b`
- **Produced by** S6
- **Consumed by** S7, S8
- **Shape** subset of `F` where both revisions built
- The build-admitted set. This is the hard gate.

<a id="var-buildhandle"></a>
### `buildHandle`
- **Produced by** S6 (`jvm_build_probe`)
- **Consumed by** S7 (`jvm_coverage_run`, `jvm_callgraph_build`), S10
- **Shape** `build_` followed by 16 hex characters, derived from worktree path and image
- Names a compiled tree. Deterministic, so the same revision and image reproduce it.

<a id="var-reportid"></a>
### `reportId`
- **Produced by** S7 (`jvm_coverage_run`)
- **Consumed by** S7 (`jvm_coverage_lines`), S8, S11
- **Shape** `cov_` followed by 16 hex characters, a content hash of the exec data and the classes
- Names a coverage measurement. Content derived, so two different measurements never collide.

<a id="var-callgraphid"></a>
### `callGraphId`
- **Produced by** S7 (`jvm_callgraph_build`)
- **Consumed by** S10, S11
- **Shape** `cg_` followed by 16 hex characters
- Names a static call graph. The graph itself never enters agent context; agents query through the
  handle.

<a id="var-importance"></a>
### `importance` and <a id="var-scoredon"></a>`scoredOn`
- **Produced by** S8 (`rank_importance`)
- **Consumed by** S8 selection, S12
- **Shape** a composite in [0,1] plus the list of dimensions it was computed over
- `scoredOn` must travel with `importance` everywhere. Without it the number is not interpretable.

<a id="var-symptomclass"></a>
### `symptomClass`
- **Produced by** S9 (`symptom-characterizer`)
- **Consumed by** S10, S12
- **Shape** a class such as `DATA_CORRUPTION_OR_LOSS`, `INCORRECT_OUTPUT`, `CRASH`, plus secondary
  classes and a description
- What the observer saw.

<a id="var-entrypoint"></a>
### `entryPoint`
- **Produced by** S9 (`symptom-characterizer`)
- **Consumed by** S10, S11
- **Shape** `MethodRef`
- Where the symptom surfaced. The other endpoint of the path, and the origin from which path length
  is measured.

<a id="var-reproducertier"></a>
### `reproducerTier`
- **Produced by** S10
- **Consumed by** S11, S12, S13
- **Shape** `T1` | `T2` | `T3` | `T4`
- Determines whether the path can be observed or must be hypothesised.

<a id="var-traceid"></a>
### `traceId`
- **Produced by** S10 (`jvm_harness_run`, or a native test run)
- **Consumed by** S11 (`jvm_trace_executed`, `jvm_trace_path_in`)
- **Shape** opaque handle
- Names one recorded execution. The executed method set is queried through it.

<a id="var-path"></a>
### `path` and <a id="var-pathfidelity"></a>`pathFidelity`
- **Produced by** S11 (`path-tracer`)
- **Consumed by** S12, S13
- **Shape** ordered hops from `entryPoint` to `faultMethods`, each with backing evidence, plus a
  fidelity of `FULL` | `PARTIAL` | `UNIT`
- The deliverable.

## Execution and isolation model

Everything from the build gate onward runs in a container. Repository code and agent-authored
harnesses are both treated as untrusted, because building any cloned repository already executes
arbitrary third-party code: Maven and Gradle plugins run, and an sbt build file is a program.

```scala
final case class ContainerPolicy(
    image: String,
    network: Boolean,
    memoryMb: Int = 2048,
    cpus: Double = 2.0,
    timeoutSec: Int = 600,
    mounts: Vector[Mount] = Vector.empty,
    env: Map[String, String] = Map.empty
)
```

### Network policy

Network access is **not** uniform, and the split is deliberate:

| Workload | Network | Reason |
|---|---|---|
| Builds and test runs | allowed | Maven and Gradle cannot resolve dependencies without it |
| Harness runs | **denied** | agent-authored code, and nothing it legitimately needs is outside the workspace |

A build with no network would fail as `DependencyResolution` for a reason that has nothing to do
with the commit under test, which is why the two cases are separated rather than being uniformly
locked down.

### Dependency cache

Container filesystems start empty, so without a shared cache every build re-downloads the entire
dependency tree. The pipeline builds two revisions per bug, so the waste compounds.
`DependencyCache.forTool` maps each build tool onto a cache location under `workspace/caches` and
redirects it by environment variable:

| Tool | Mechanism |
|---|---|
| Maven | `MAVEN_OPTS=-Dmaven.repo.local=/cache/m2` |
| Gradle | `GRADLE_USER_HOME=/cache/gradle` |
| sbt | `COURSIER_CACHE=/cache/coursier`, `SBT_OPTS=-Dsbt.ivy.home=/cache/ivy2` |

Environment variables rather than guessing `$HOME`, because the official images do not agree on
which user they run as. Bind mounts rather than named volumes, because a fresh named volume is
owned by root and not every official image runs as root.

Builds get the cache. Harness runs do not.

### Build provenance

A worktree is reused across probes, and a build tool skips recompiling a class whose output is
newer than its source. `BuildProbe` therefore records the image it built with in
`.causeway/build-image` and cleans when the next recipe names a different one. Output present with
no marker counts as stale, since unknown provenance is not the same as known-clean.

### Coverage instrumentation

The JaCoCo agent is attached through `JAVA_TOOL_OPTIONS` rather than through Surefire's `argLine`
property. A project-level `<argLine>` in the POM overrides the property form, and many projects set
one, so the property attaches nothing while the suite still runs green. `JAVA_TOOL_OPTIONS` is
honoured unconditionally and additively, so the project keeps its own JVM flags.

Because that variable reaches every JVM in the build, the agent runs with `append=true`, and the
exec file is deleted before the run rather than relying on `append=false`.

### Memory bounds

Each SootUp `JavaView` pins the whole JDK runtime plus the project's classes. Call graph handles are
therefore held in an access-ordered LRU cache bounded at 2, configurable with
`CAUSEWAY_CALLGRAPH_CACHE`. The server runs with an explicit heap cap so that exhaustion is a fast,
legible failure. An evicted handle returns a message telling the caller to rebuild, which is a
different instruction from "no such handle".

## Persistence, checkpointing and resume

### The graph

Neo4j holds the run's durable state. Node labels:

| Label | Key | Holds |
|---|---|---|
| `Run` | `id` | the run, and the repository it is mining |
| `Checkpoint` | `(runId, stage, key)` | stage progress and a human-readable detail |
| `Evidence` | `id` | tool, argument hash, payload hash, timestamp, provenance |
| `Finding` | `id` | an agent claim, with `SUPPORTED_BY` edges to evidence |
| `Bug` | `(repo, fixSha)` | admission, importance, `scoredOn`, tier, fidelity |
| `Commit` | `(repo, sha)` | commits referenced by bugs |
| `Repository` | `slug` | the repository |

Every write is an idempotent `MERGE` on a natural key. A resumed run overwrites rather than
duplicating, which matters because runs are expected to be interrupted and continued.

### Checkpoints and resume

```scala
def checkpoint(runId: RunId, stage: String, key: String, status: String, detail: String): Unit
def resumeState(runId: RunId): Vector[CheckpointRow]   // per stage: done, pending
```

`resumeState` aggregates per stage into a count of `DONE` rows and a count of everything else. The
orchestrator resumes at the first stage with outstanding work.

Retrying a stage must **reuse the original key**, or explicitly name what it supersedes. A retry
under a new key leaves the failed row behind permanently, and that stage can then never read as
complete.

### Run identity

A server process mints its own run identifier at startup, because nothing has yet told it which
mining run it serves and a run outlives several server processes. The first tool call carrying an
explicit `runId` wins, and evidence issued before that point is re-attributed rather than stranded.
Only the first adoption counts.

### Handle journal

Handles live in server memory, so a restart would ordinarily forfeit them. A journal at
`workspace/handles.jsonl` records what each handle was derived from, so that on a lookup miss the
handle can be rehydrated from disk rather than recomputed. Coverage reports in particular can be
re-parsed from the exec file that is still on disk, which turns an hour of re-running test suites
into seconds of parsing.

## Notes versus evidence

> **Notes influence what an agent DOES. Evidence determines what an agent may SAY.**

Agents accumulate procedural knowledge across runs: which JDK a project needs, which build flags
break it, which approaches led nowhere. That knowledge is valuable and must not be allowed to become
a source of claims.

The separation is structural, not conventional:

- Notes carry `note_` identifiers. Evidence carries `ev_` identifiers.
- `findings_record` requires `^ev_` in its schema, so a note identifier is rejected **before the
  handler runs**.
- The `noteKind` enumeration is `ENVIRONMENT`, `CONVENTION`, `DEAD_END`, `FIXTURE`. There is
  deliberately no member for assertions about bugs.
- Notes live on the filesystem under `notes/<owner>__<repo>/`, never in the evidence graph.

Anything read from a note is a hint to be verified through tools, never a fact to be repeated.

Telemetry is likewise not an evidence source. It records that a call happened and the shape of its
arguments, never what the call returned.

## Evaluation metrics

The system evaluates itself as well as the repositories it mines.

### Dataset quality

| Metric | Definition |
|---|---|
| **Observed path fraction** | share of path steps confirmed by execution rather than assumed. The headline number. |
| Reproducer tier distribution | how many bugs reached T1, T2, T3, T4 |
| Path fidelity distribution | FULL, PARTIAL, UNIT |
| Admission rate | admitted bugs over adjudicated bug fixes |
| Rejection breakdown | why bugs were not admitted, by classification |

### Prefilter quality

| Metric | Definition |
|---|---|
| Per-net precision | fraction of each net's candidates that adjudicated as `BUG_FIX` |
| Estimated recall | derived from the random control net, which no filter selected |
| Adjudicator agreement | rate of `UNDECIDED`, as a measure of genuine ambiguity |

### Honesty

The `Unknown` breakdown by reason, reported in full. This is what tells a consumer of the dataset
what it can and cannot support. A run with a high `BuildFailed` count produced fewer observed paths,
and the report says so rather than presenting a smaller dataset as a cleaner one.

## Implementation status

| Step | Status |
|---|---|
| S0 Ingest | Implemented, exercised |
| S1 Environment | Implemented, exercised |
| S2 Candidates | Implemented, exercised |
| S3 Bundling | Implemented, exercised |
| S4 Adjudication | Implemented, exercised |
| S5 Structure | Implemented, exercised |
| S6 Build gate | Implemented, exercised |
| S7 Coverage and call graph | Implemented, exercised |
| S8 Ranking | Implemented, exercised |
| S9 Symptom | Implemented, exercised |
| S10 Reproducer | Rung 1 implemented and exercised across all admitted bugs, rung 3 implemented, rungs 2 and 4 not wired |
| S11 Path | Implemented, exercised |
| S12 Metrics and persistence | Implemented, exercised |
| S13 Report | Implemented, exercised |

A full pass over jhy/jsoup has completed end to end: 19 admitted bugs, **18 native reproducers**, 18
traced paths, and **94.2% of path steps observed rather than static** (65 of 69, with zero
unobserved and 2 unmeasurable). See [example.md](example.md) for the walkthrough, including the two
paths that execution corrected and the instrument blind spot a controlled comparison exposed.

The single remaining T4 is a commit that ships no regression test, which is the ladder's floor
rather than a failure. Rung 2 stays inapplicable to repositories whose users do not post stack
traces, and rung 4 is unwired.

| Component | Status |
|---|---|
| Tool catalog | 48 schemas, 45 served |
| `evidence_attest` | Catalogued, granted to no agent, correctly unserved |
| `repro_botsing`, `repro_evosuite_sweep` | Catalogued, not wired; both are external tools needing their own harnessing |
| `modules/llvm` | Deferred; the JVM module is the reference implementation |
| Test suite | 315 tests |

## Framework choices

| Framework | Role | Justification |
|---|---|---|
| Scala 3 | Core implementation language | Strong type system; `Signal` and the old/new line split are enforced by the compiler |
| sbt 2 | Build tool | Standard Scala build and test workflow |
| MCP | Tool protocol | One boundary at which validation, access control and evidence minting can be enforced for all callers |
| Claude Code | Orchestrator and MCP client | Subagents with per-agent tool grants and models, slash commands, hooks |
| JGit | Repository access | In-process git: clone, diff with rename detection, blame, no shelling out |
| GitHub GraphQL v4 | Forge retrieval | Batched and aliased; one round trip per candidate chunk rather than per candidate |
| SootUp | Call graphs and class hierarchy | JVM call graph construction with a modern, maintained API |
| JaCoCo | Coverage | Line-level coverage attachable to any JVM via `JAVA_TOOL_OPTIONS` |
| Docker | Isolation | Repository code and agent-authored harnesses are both untrusted |
| Neo4j | Graph store and evidence ledger | Natural fit for evidence-to-finding edges; idempotent `MERGE` makes resume safe |
| PySZZ (B-SZZ, R-SZZ) | Fix-inducing commit | Wrapped rather than reimplemented; an evaluated implementation beats a fresh one |
| Botsing | Crash reproduction from a stack trace | Rung 2 of the reproducer ladder; catalogued, not yet wired |
| EvoSuite | Search-based test generation | Rung 4 of the ladder, with a differential filter; catalogued, not yet wired |
| Node 18+ | Catalog checkers, telemetry hooks, usage accounting | No build step; the checkers must run before the Scala build does |

The stack sits behind stable interfaces wherever possible. Analyzers, graph databases,
instrumentation libraries and model providers all change. The domain model in `modules/core` should
survive those changes.

## References

External tools wrapped or planned, and the literature the design rests on.

- Botsing (crash reproduction): https://stamp-project.github.io/botsing/
- EvoSuite: https://www.evosuite.org/ · regression mode: https://www.evosuite.org/evosuiter/
- PySZZ: https://github.com/grosa1/pyszz
- SootUp: https://soot-oss.github.io/SootUp/
- JaCoCo: https://www.jacoco.org/jacoco/
- JGit: https://www.eclipse.org/jgit/
- Model Context Protocol: https://modelcontextprotocol.io
- Defects4J: https://homes.cs.washington.edu/~rjust/publ/defects4j_issta_2014.pdf
- Rosa et al., *Evaluating SZZ Implementations Through a Developer-informed Oracle* (ICSE 2021):
  https://arxiv.org/abs/2102.03300
- Comprehensive evaluation of SZZ variants:
  https://www.sciencedirect.com/science/article/pii/S0164121223001243
- AgenticSZZ (2026): https://arxiv.org/abs/2602.02934
- MAS-SZZ (2026): https://arxiv.org/pdf/2604.24398
- Call-Chain-Aware LLM-Based Test Generation (2026): https://arxiv.org/pdf/2604.22046

## End to end data flow

A repository URL feeds ingestion, which produces the clone handle every later step addresses the
repository through. The build recipe feeds the build gate, coverage and every container invocation.
Recall nets feed the candidate set; forge bundling attaches issues, pull requests and CI conclusions
to it; adjudication reduces it to confirmed bug fixes. Diffing produces the faulty line ranges in
parent coordinates, and those ranges — never their new-side counterparts — feed both method
resolution and the coverage query.

The build gate admits only the bugs whose two revisions compile, and hands each a compiled tree.
Coverage measures whether the faulty lines are exercised; the call graph supplies the structure a
path could run through. Ranking composes the Known dimensions into an importance score that carries
the list of dimensions it was computed over. Symptom characterization supplies the entry point; the
reproducer ladder supplies, where it can, an execution that made the bug manifest, and with it the
set of methods that actually ran.

Path tracing intersects the call graph with that executed set and emits the ordered route, tiered by
how much of it was observed. Metrics, graph persistence and export make the result durable, and the
report states the gap breakdown in full alongside the headline observed-path fraction.

Causeway is therefore a grounded mining system rather than a model asked to summarize a repository.
Static structure, containerised execution, differential oracles, forge metadata, an evidence ledger
and bounded agent reasoning are cooperating parts of one pipeline, and the parts that reason cannot
speak past the parts that retrieve.

## Build and operate

Setup, credentials, MCP registration, the verification gates and the first run are documented step
by step in **[reproduce.md](reproduce.md)**. The short version:

```bash
sbt -batch "app/dist"                     # package the server into target/dist/lib
node tools/sync-catalog.mjs --check       # catalog integrity, after ANY tool change
node tools/check-agents.mjs               # are the agents' granted tools actually served?
node tools/check-params.mjs --check       # does every served tool read every declared parameter?
node tools/check-outputs.mjs --check      # does any handler emit a field its schema forbids?
```

Then, in Claude Code, from the project directory:

```
/mine-repo https://github.com/jhy/jsoup --window 6m --cap 25 --deep 2
/mine-repo https://github.com/jhy/jsoup --resume run_f7997c1a240cc238
```

At startup the server prints which ledger it obtained. `evidence ledger: Neo4j (persistent)` means
results will survive. Anything else means they will not.

## Further reading

- **[reproduce.md](reproduce.md)** — the operational playbook: install, credentials, MCP
  registration, agents and skills, the tool list, first run, resume.
- **[example.md](example.md)** — one repository walked through all fourteen steps with real data.
- **[reference/design.md](reference/design.md)** — the full design with the numbered decision log
  D1–D22. Cite decision numbers rather than re-deriving the reasoning.
- **[CLAUDE.md](CLAUDE.md)** — the invariants, loaded into every session.
