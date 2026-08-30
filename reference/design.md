# repo-bugs-extraction — Design Reference

**Status:** design phase, no implementation yet.
**Last updated:** 2026-08-21

---

## 1. Purpose

Given only a public GitHub repository URL, mine a curated set of **important, well-observed
bugs** from history. For each one, extract the **path connecting the observed symptom to the
responsible code**, backed wherever possible by an execution that actually manifested the bug.

Scope: JVM (Java/Scala) and LLVM-based repositories only. Standalone practice project in
agentic workflow design.

---

## 2. What the task is — and is not

**Not fault localization.** Fault localization hides the fix and scores a prediction of where
the fault is. That is a different problem.

**This task:** both endpoints are known — the symptom and the bug. The object of study is
**the route between them**.

```
KNOWN:      symptom S(f)          (what was observed)
KNOWN:      fault site  H(f)      (what the fix changed)
UNKNOWN:    Π(f)                  (the path connecting them)  <-- the deliverable
```

The dataset's value is the paths. Buildability and coverage are not budget concessions; they
are **admission and ranking criteria**, because a bug whose code will not build or is not
exercised by tests produces a path we cannot trust.

### Why the direction matters

Traversal runs symptom → fault. Knowing the destination makes this **pathfinding**, not
search. The agent cannot fabricate a route: every edge must be a retrieved call-graph edge,
and in the best case an observed execution.

---

## 3. The central mechanism: observed paths

A static call graph says a path is *possible*. It over-approximates — reflection, DI, dead
branches, virtual dispatch to implementations that never run.

If the revision **builds** and the fault site is **covered**, something far better is
available: obtain an input that makes the bug manifest, run it, and record what executed.

```
static call graph  G(p(f))        — everything possible
        ∩
executed methods   Ex(f)          — what actually ran when the symptom occurred
        =
executed subgraph                 — the path lives here, and nowhere else
```

### Reachability is not triggering

| | | |
|---|---|---|
| **R**eachability | the input causes the faulty code to execute | necessary, not sufficient |
| **I**nfection | that execution produces incorrect internal state | not guaranteed by R |
| **P**ropagation | the incorrect state reaches an observable output | **this is the symptom** |

An input can reach `ConfigLoader.get` a thousand times and trigger nothing if `s` is never
null. All three are required.

### The differential oracle (D14)

"Did this input trigger the bug?" normally needs a human to state correct behavior. We do not
need one, because both revisions exist:

```
run(input, p(f))   vs   run(input, f)

  outputs differ  ⇒  the input triggers the bug — the fix changed what it does
  outputs same    ⇒  reached but not infected — iterate
```

Differential execution across the fix boundary **is** the oracle. Observable difference is
defined (D15) as:

- **thrown exception**: type and throw site
- **return value / computed output**

Explicitly excluded: file/log/stdout side effects (noisy — timestamps, ordering, paths) and
timing/resource behavior (inherently flaky as an oracle). Bugs manifesting *only* through
those channels will fall to tier T3/T4. Recorded as a known limitation, see A17.

---

## 4. The reproducer ladder (D16)

Cheapest rung first; each falls through to the next.

| Rung | Condition | Method | Cost |
|---|---|---|---|
| 0 | Fix added a regression test | Run it at `p(f)` — it fails. Free reproducer. | ~0 |
| 1 | Symptom is a stack trace | **Botsing** — purpose-built search-based crash reproduction | minutes, no tokens |
| 2 | Fix diff is readable | **Agent-synthesized harness** + differential oracle | ≤3 agent turns |
| 3 | All else | **EvoSuite** sweep on the fault class, differentially filtered | minutes, no tokens |
| 4 | Nothing reproduces | Static path only, recorded as such | – |

Rungs 1 and 3 are existing tools doing deterministic work. Rung 2 is where the agent earns
its place — and its advantage over any search-based tool is that **it can read the fix diff**.
Seeing `if (s == null)` added, it knows immediately that the input must make
`sections.get(section)` return null. Targeted construction, not blind search.

No leakage concern: under this framing both endpoints are known by design. We are
constructing a reproducer for a known bug, not discovering an unknown one.

### Reproducer tiers

```
T1  NATIVE      — the project's own regression test reproduces it
T2  SYNTHESIZED — a generated input differentiates p(f) from f
T3  REACHED     — input reaches the fault site but triggers no observable difference
T4  STATIC      — no input reaches it; the path is a hypothesis from G alone
```

### Path fidelity (D17)

Synthesis targets the **symptom's own entry point** — the outermost stack frame, or the action
the reporter described — and degrades inward on failure. Each fallback shortens the path and
lowers fidelity:

```
Φ = FULL     input at the symptom's own entry point      e.g. HttpHandler.handle(req)  ~15 hops
Φ = PARTIAL  input at an intermediate public boundary    e.g. ConfigService.load(path)  ~6 hops
Φ = UNIT     input directly at the fault method          e.g. ConfigLoader.get(null,"k") 1 hop
```

`Φ = UNIT` reproduces the bug but explains nothing about how anything reached it. Recorded,
ranked low, never discarded.

---

## 5. Verified background (checked 2026-08-20/21)

### Reproduction and test generation

- **Botsing** — https://stamp-project.github.io/botsing/ — ASE 2020, built on EvoSuite,
  extends the EvoCrash approach. Takes a stack trace and frame, uses a guided genetic
  algorithm whose fitness function measures how close each candidate test comes to
  reproducing the trace. Evaluated on JCrashPack.
- **EvoSuite** — https://www.evosuite.org/ — search-based unit test generation. Its
  regression/differential mode (EvoSuiteR, https://www.evosuite.org/evosuiter/) generates
  tests revealing behavioral differences between two versions of a class, which is exactly
  our shape — **but recent research reports the feature is discontinued in newer releases.**
  Do not build on it; implement the differential oracle ourselves over standard generation.
- **Call-Chain-Aware LLM-Based Test Generation for Java Projects** (2026) —
  https://arxiv.org/pdf/2604.22046 — feeds call chains, object initialization info, and
  inter-module dependencies from static analysis into an LLM for deep-target test generation.
  Validates the rung-2 approach.

### SZZ status

- Original: Śliwerski, Zimmermann, Zeller 2005, "When Do Changes Induce Fixes?"
- **PySZZ** (Rosa et al., ICSE 2021) — https://github.com/grosa1/pyszz — implements all 9
  major SZZ variants, evaluated on a developer-informed oracle of 2,304 instances.
  **R-SZZ performed best.** Paper: https://arxiv.org/abs/2102.03300
- **Known ceiling**: a study of 2,102 validated bug-fixing commits found **28% of
  bug-inducing commits require traversing history beyond blame results** and **14% are
  blameless**. Pure git-blame SZZ structurally cannot find these.
- **AgenticSZZ** (2026) — https://arxiv.org/abs/2602.02934 — reformulates bug-inducing-commit
  identification as graph search over a Temporal Knowledge Graph navigated by an LLM agent.
  F1 0.47–0.79, up to 34% over prior SOTA. Concurrent: SZZ-Agent, AgentSZZ, MAS-SZZ
  (https://arxiv.org/pdf/2604.24398).
- **Role here**: SZZ is a *metadata enricher* (temporal origin, bug lifetime), not part of the
  symptom→fault path. R-SZZ is the deterministic baseline.

### Stack components (re-verify currency at implementation time)

JGit, OPAL, SootUp, JaCoCo, scoverage, Neo4j Java driver, Testcontainers Neo4j module,
MCP Java SDK (io.modelcontextprotocol), PySZZ, Botsing, EvoSuite.

---

## 6. Decisions log

| # | Decision | Rationale |
|---|---|---|
| D1 | Per-bug parent/fix pair builds, compile-probe gated | Coverage and differential execution both require running at p(f) and f |
| D2 | Bugs failing admission are recorded with `admitted:false` and a reason, not deleted | Lets us characterize our own selection bias; deletion is irreversible |
| D3 | Coverage bar = **the changed lines specifically**, measured at p(f) | Per-bug and directly relevant: the fault site must be exercised for the path to be observable. Repo-level coverage would select well-tested *projects*, not well-observed *bugs* |
| D4 | SZZ via PySZZ/R-SZZ as metadata enricher only | The path of interest is symptom→fault, not fix→origin. SZZ answers a different, still useful question |
| D5 | JVM and LLVM pipelines fully independent, no shared abstraction beyond `core`. JVM first | User: "should be treated separately and should not depend on each other" |
| D6 | GitHub data via GraphQL v4 inside `modules/forge`, NOT via the GitHub MCP server | Pure retrieval is a deterministic service. Makes all GitHub data ServerIssued evidence. **Deviates from the original stack constraint** |
| D7 | Agents never compute; every tool is deterministic; tools split by invoker into Pipeline (P) / Evidence (E) / Recording (R) classes | User rule plus access-control needs |
| D8 | Regex/link matching is a recall net, not a classifier. Agents supply all precision | Agents make these judgement calls well with proper reasoning |
| D9 | Third candidate net: random control sample of un-caught commits, adjudicated anyway | Measures prefilter recall instead of assuming it |
| D10 | Agent `build-doctor` | Determines how to build/test/enter the project; also resolves call-graph entry points |
| D11 | Buildable = **hard gate**. Coverage, blast radius, symptom distance = **ranking dimensions** | User: "we consider each and every bug, and we rank them" |
| D12 | `Signal[A] = Known \| Unknown(reason)` replaces `Option`, no `getOrElse` | Makes the neutrality rule unbreakable rather than merely requested |
| D13 | `LineRange` split into `OldLines` / `NewLines` phantom types | Prevents silently querying new-side line numbers against the parent revision |
| **D14** | **Differential execution across the fix boundary is the oracle** | Removes the need for a human oracle on synthesized inputs |
| **D15** | **Observable difference = thrown exception (type + site) and return value only** | Side effects and timing are too noisy to serve as a reliable oracle |
| **D16** | **Reproducer ladder, rungs 0–4, cheapest first** | Existing tools (Botsing, EvoSuite) do deterministic work; the agent is used only where its fix-reading advantage matters |
| **D17** | **Synthesis targets the symptom's own entry point, degrading inward; fidelity recorded as FULL/PARTIAL/UNIT** | Graded failure instead of blocking; preserves the interesting long paths where achievable |
| **D18** | **Rung-2 synthesis budget = 3 iterations per bug, then fall through** | Predictable per-bug cost; the fix-reading advantage means successes land early or not at all |
| **D19** | **Importance = per-dimension scores, composite over `Known` dimensions only, `scoredOn` recorded** | A weighted sum would sink a bug with no symptom to zero — a missing signal acting as evidence against |
| **D20** | **Everything from the build gate onward runs in a container** — build, test, coverage, harness. `--network none`, memory/CPU caps, wall-clock timeout, workspace bind-mount and nothing else | Building any cloned repo already executes arbitrary third-party code (Maven/Gradle plugins, sbt build files are programs); the agent-authored harness merely adds a layer. Java's `SecurityManager` is deprecated for removal and disabled by default in current JDKs, so no in-JVM sandbox exists. **Side benefit:** `JDK_MISMATCH` is solved by choosing a base image rather than provisioning JDKs on the host — `build-doctor` picks an image, not a toolchain |
| **D28** | **Line endings: all sources are LF.** Python's text-mode write converts `
` to `
` on Windows | A patched file silently became CRLF, and a multi-line string literal whose content is then searched with `"
"` stopped matching — breaking fixtures with no compile error and no obvious cause. Any tooling that rewrites a source file must preserve LF (`newline=''` in Python) |
| **D27** | **The executed set is coverage UNION the throw's stack frames**, not coverage alone | JaCoCo places probes at branch points and method exits, so a method whose call THROWS records zero covered instructions despite certainly having run. For an exception-manifesting bug — the commonest kind here — that omits exactly the chain leading to the fault. The stack trace names those frames precisely. Consequence: methods are matched across producers by **class and name only**, because JaCoCo, SootUp and a Java stack trace use three different descriptor dialects and a stack trace has none at all. Overloads collide; that widens the set slightly, whereas demanding descriptors would empty the intersection and silently demote every observed path to hypothesised |
| **D29** | **The container's dependency cache is shared and bind-mounted; the working tree's build output is provenance-tracked** | Two independent hazards, both found on real runs. Without a shared cache every container re-downloads the entire dependency tree, and this project builds TWO revisions per bug, so the waste doubles per bug — `DependencyCache.forTool` redirects Maven/Gradle/sbt at `workspace/caches` via environment variables rather than guessing `$HOME`, and bind mounts avoid the root-owned-volume permission failure that a named volume causes for the non-root gradle image. Separately, a working tree is REUSED across revisions and JDKs, and a build tool skips recompiling a class whose output is newer than its source: on jsoup, Java 11 test classes survived into a JDK 8 container, surefire died before the first test, and the build reported success with zero tests run. `BuildProbe` records the image it built with and cleans when the next recipe names a different one; output with no marker counts as stale, because unknown provenance is not the same as clean. **Builds get the cache; harness runs do not** — that is agent-authored code with no network |
| **D26** | **R-SZZ implemented over JGit, not PySZZ wrapped** | Checked first, per the prefer-existing-tools rule. PySZZ requires **srcML**, a native binary needing per-platform installation on `PATH`; depending on it cleanly means a purpose-built container image. That is disproportionate for what D4 demoted to a metadata enricher. Only B-SZZ and R-SZZ are implemented; other variants return Unknown rather than being silently substituted |
| **D25** | **SootUp, not OPAL, is the call-graph backend.** OPAL stays a possible cross-check only if the build ever moves to Scala 3.7+ | Not a preference — a hard constraint discovered on 2026-08-22. OPAL 7.0.0 publishes Scala 3 artifacts but is **built against Scala 3.7.3**, and a 3.3 compiler cannot read TASTy emitted by 3.7. Adopting OPAL would pin the entire project's Scala version to whatever OPAL was last built with, permanently. SootUp 3.0.1 is **pure Java** and couples to nothing. The original rationale for OPAL — "Scala-native, fits the stack" — does not survive that |
| **D24** | **Notes are a separate, non-evidentiary store.** `notes.write` / `notes.read` / `notes.list_subjects` return `note_*` ids; `findings.record` requires `^ev_`, so a note is structurally uncitable. The `noteKind` enum has no `FACT`/`FINDING` member | Agents need durable procedural knowledge (how this repo builds, which harness altitude failed) or they re-derive it every run. But if a note could back a claim, a claim would trace to an earlier agent's belief instead of a tool return — laundering exactly what the ledger prevents. **Notes influence what an agent DOES; evidence determines what an agent may SAY** |
| **D23** | **Telemetry via hooks; token/cost via a post-hoc transcript pass.** `.claude/hooks/telemetry.mjs` writes `runs/<session>/telemetry/*.jsonl` from PostToolUse and Subagent events; `tools/collect-usage.mjs` parses the transcript afterwards | Hook payloads carry `agent_type`, giving per-agent tool attribution free — no need to thread an agent id through 47 schemas. **No hook payload carries tokens or cost**, so usage must come from the transcript. Cost rates are left null in `runs/rates.json` rather than guessed: an unset rate reports `null`, never `0`, which would read as free |
| **D22** | **`schemas/tools/` is the single source of truth for the catalog.** `x-causeway` metadata on each tool carries class, module, permission, and permitted agents; `tools/sync-catalog.mjs` generates `.claude/settings.json` from it and verifies agent frontmatter both directions. **`evidence.attest` is granted to no agent** | The catalog was written down in four places and drifted on the first check. Generating settings and checking frontmatter collapses that to one authority. Ungranting attestation makes all evidence ServerIssued and verifiable |
| **D21** | **Server name: `causeway`.** Run-scale defaults deliberately small: window = 12 months or 300 commits (whichever smaller), candidate cap 60, deep analysis for the top 5 by ω, first smoke run 1–2 bugs | Start on a small single-module application; validate the whole pipeline end-to-end before scaling anything |

---

## 7. Architecture

Two halves:

- **`causeway-mcp`** — standalone Scala 3 MCP server. Every deterministic operation.
  Schema-first. (A causeway is a raised path across difficult ground; it also contains
  *cause*, which is what these paths connect to. Tools read as `mcp__causeway__*`.)
- **Claude Code subagents** in `.claude/agents/` — reasoning only, six of them.

### How the MCP server works

JSON-RPC over stdio. `.mcp.json` launches it; Claude Code calls `tools/list` at startup. Four
properties beyond plain wrapping:

1. **Handles, not payloads.** A call graph has millions of edges and never enters agent
   context. `jvm.callgraph.build` returns `{callGraphId, nodeCount}`; the graph lives
   server-side. Agents query it (callers of X at depth 2 → 12 rows). Same for `repoHandle`,
   `buildHandle`, `reportId`, `traceId`. Context stays small regardless of repo size.
2. **Evidence ledger.** Every response is hashed into an `evidenceId` and persisted before
   return. `findings.record` rejects claims citing ids the ledger did not issue.
3. **Resource pooling.** Six agents may call `jvm.build.probe`; the server queues them onto two
   build slots. Limits live in enforcing code, not in prompts.
4. **Scoped tool views.** Per-agent catalogs.

### Universal response envelope

```json
{ "ok": true, "evidenceId": "ev_9f3a...", "data": { } }
```
```json
{ "ok": true, "evidenceId": "ev_1c8b...",
  "unknown": { "reason": "BUILD_FAILED", "classified": "DEPENDENCY_RESOLUTION",
               "detail": "repo.maven.apache.org 404" } }
```

`unknown` is a SUCCESS with an evidence id. "We tried and could not determine this, for this
reason" is a real, citable finding.

---

## 8. Folder structure

```
repo-bugs-extraction/
├── .mcp.json
├── .claude/
│   ├── agents/
│   │   ├── mining-strategist.md
│   │   ├── bugfix-adjudicator.md
│   │   ├── build-doctor.md
│   │   ├── symptom-characterizer.md
│   │   ├── reproducer-synthesist.md
│   │   └── path-tracer.md
│   ├── commands/mine-repo.md
│   └── settings.json
├── build.sbt · project/
├── schemas/
│   ├── tools/          # one JSON Schema per MCP tool; server refuses unschema'd tools
│   └── findings/       # evidence-bound agent output envelopes
├── modules/
│   ├── core/           # domain, Signal, EvidenceId, Finding, LineRange — no I/O
│   ├── vcs/            # JGit: clone, walk, hunks, blame, candidate nets, PySZZ wrapper
│   ├── forge/          # GitHub GraphQL v4 client, batched
│   ├── jvm/            # buildprobe, OPAL/SootUp, JaCoCo/scoverage, harness runner,
│   │                   #   Botsing + EvoSuite drivers
│   ├── llvm/           # peer module, later, zero coupling to jvm/
│   ├── metrics/        # pure functions over Signal
│   ├── graphstore/     # Neo4j, idempotent MERGE, checkpoints, export
│   └── mcpserver/      # transport, registry, schema validation, ledger, pooling, ACL
├── it/                 # Testcontainers integration tests
├── fixtures/           # pinned tiny repos + recorded responses
├── ops/docker-compose.yml
├── reference/          # this file
└── workspace/          # gitignored: clones, builds, coverage, harnesses, cache
```

---

## 9. Tool classification

**Class P — Pipeline services.** Invoked by the orchestrating command in fixed deterministic
stages. Not exposed to reasoning agents. Expensive, stateful.

`repo.clone` · `repo.checkout` · `jvm.build.probe`* · `jvm.coverage.run` ·
`jvm.callgraph.build` · `repro.botsing` · `repro.evosuite_sweep` · `metrics.*` ·
`rank.importance` · `graph.upsert_bug` · `export.run` · `szz.baseline`

(*also exposed to `build-doctor`, which needs the feedback loop.)

**Class E — Evidence queries.** Invoked by agents to retrieve facts. Read-only, small.

`history.commit_meta` · `history.diff` · `history.blame` · `history.file_at` · `forge.issue` ·
`forge.issue_comments` · `forge.pr_for_commit` · `forge.ci_status` · `jvm.callgraph.paths` ·
`jvm.callgraph.callers` · `jvm.callgraph.chains_to` · `jvm.method.at_line` ·
`jvm.method.body` · `jvm.coverage.lines` · `jvm.class_hierarchy` · `jvm.tests.for_change` ·
`jvm.trace.executed` · `jvm.trace.path_in`

**Class X — Experiment.** The one tool that executes agent-authored code. Compiles a harness
against both revisions, runs both, returns the differential result. Sandboxed, timeout-bound,
network-denied, pooled.

`jvm.harness.run` — in `{fixSha, harnessSource, entryPoint}`, out
`{compiled: bool, reachedFault: bool, executedMethods: [MethodRef], resultAtParent, resultAtFix, differs: bool, differsOn: [EXCEPTION|RETURN]}`

**Class R — Recording.** `findings.record` only.

### Agent × tool access matrix

Legend: ✅ allowed · ⛔ not in the agent's tool list · – not applicable

| Tool | strategist | adjudicator | build-doctor | symptom | synthesist | tracer |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `history.list_commits` | ✅ | – | – | – | – | – |
| `history.candidates` | ✅ | – | – | – | – | – |
| `history.commit_meta` | – | ✅ | – | ✅ | ✅ | ✅ |
| `history.diff` | – | ✅ | – | – | ✅ | ✅ |
| `history.blame` | – | – | – | – | – | ✅ |
| `history.file_at` | – | ✅ | ✅ | ✅ | ✅ | ✅ |
| `forge.issue` / `.comments` | – | ✅ | – | ✅ | ✅ | ✅ |
| `forge.pr_for_commit` | – | ✅ | – | ✅ | – | – |
| `forge.ci_status` | – | ✅ | ✅ | – | – | – |
| `jvm.build.toolchain` | – | – | ✅ | – | – | – |
| `jvm.build.probe` | – | – | ✅ | – | – | – |
| `jvm.method.at_line` | – | – | – | – | ✅ | ✅ |
| `jvm.method.body` | – | – | – | – | ✅ | ✅ |
| `jvm.callgraph.chains_to` | – | – | – | – | ✅ | ✅ |
| `jvm.callgraph.paths` | – | – | – | – | – | ✅ |
| `jvm.callgraph.callers` | – | – | – | – | ✅ | ✅ |
| `jvm.class_hierarchy` | – | – | – | – | ✅ | ✅ |
| `jvm.coverage.lines` | – | – | – | – | – | ✅ |
| `jvm.trace.executed` | – | – | – | – | ✅ | ✅ |
| `jvm.trace.path_in` | – | – | – | – | – | ✅ |
| `jvm.tests.for_change` | – | ✅ | – | ✅ | ✅ | – |
| **`jvm.harness.run`** | – | – | – | – | **✅** | ⛔ |
| `graph.resume_state` / `.query` | ✅ | – | – | – | – | – |
| `findings.record` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `Task` (dispatch) | ✅ | – | – | – | – | – |

`jvm.harness.run` is the synthesist's alone. The tracer analyses executions; it does not
create them.

**`evidence.attest` is granted to no agent** (D22). With GitHub routed through `forge.*` and
every other retrieval passing through the ledger, nothing legitimately needs attestation — so
every piece of evidence in the graph is `ServerIssued` and verifiable. The schema exists to
define the capability and to record that we chose not to hand it out. Granting it weakens the
integrity guarantee from *verifiable* to *agent-transcribed*; do so only with a stated reason.

**This matrix is checked, not maintained by hand.** `schemas/tools/*.json` carries
`x-causeway.agents` per tool; `node tools/sync-catalog.mjs --check` verifies agent frontmatter
against it in both directions and regenerates `.claude/settings.json`. If this table and the
schemas disagree, the schemas are right.

---

## 10. Modules

### modules/core — domain, no I/O

Vocabulary: `CommitId` (validated 40-hex), `RepoRef`, `Ecosystem`, `Hunk`, `MethodRef`,
`RunId`, `RunManifest`, `ReproducerTier`, `PathFidelity`.

**LineRange, split by side:**

```scala
sealed trait LineRange { def start: Int; def end: Int }   // 1-based inclusive
case class OldLines(start: Int, end: Int) extends LineRange   // coords at p(f)
case class NewLines(start: Int, end: Int) extends LineRange   // coords at f
```

Worked example — NPE in a config parser:

```
commit a1b2c3d  "Fix NPE when config section is absent"
--- a/src/main/java/cfg/ConfigLoader.java
+++ b/src/main/java/cfg/ConfigLoader.java
@@ -40,7 +40,11 @@
 40  public String get(String section, String key) {
 41      Section s = sections.get(section);
-42      return s.lookup(key);
+42      if (s == null) {
+43          return defaults.getOrDefault(key, null);
+44      }
+45      return s.lookup(key);
 46  }
```

```scala
Hunk(
  file     = "src/main/java/cfg/ConfigLoader.java",
  oldRange = OldLines(42, 42),   // the FAULT, in parent coordinates
  newRange = NewLines(42, 45),   // the REPAIR, in fix coordinates
  deleted  = List("    return s.lookup(key);"),
  added    = List("    if (s == null) {", "…")
)
```

| Question | Commit to query | Range |
|---|---|---|
| Was the faulty line covered? (the admission bar, D3) | p(f) | oldRange = 42 |
| Which method contained the fault? | p(f) | oldRange = 42 |
| Did the fix's new code get covered? | f | newRange = 42–45 |

Querying coverage of line 45 at the parent returns the closing brace of a different method —
plausible-looking and silently wrong. The phantom types make that a compile error.

This example also shows the rung-2 advantage: reading the added `if (s == null)`, the
synthesist knows the input must make `sections.get(section)` return null. No search required.

**Signal — the neutrality type:**

```scala
enum Signal[+A]:
  case Known(value: A, evidence: EvidenceId)
  case Unknown(reason: UnknownReason, detail: String, evidence: EvidenceId)

enum UnknownReason:
  case BuildFailed, TestsFailed, Timeout, NoLinkedIssue, NoDebugInfo, NoSymptom,
       NoReproducer, RateLimited, NotApplicable, Truncated, ToolUnavailable
```

No `getOrElse`. Every metric is `Signal[A] => Signal[B]`; Unknown propagates carrying its
reason. Two cases that must never be confusable:

- build succeeded, line 42 `UNCOVERED` → the suite never touched the faulty line. A finding.
- build failed → we do not know. Not a finding.

With `Option#getOrElse(0.0)` both become `0.0` and the dataset lies.

**Evidence and Finding:**

```scala
opaque type EvidenceId = String        // sha256(canonicalJson(tool, args, payload))
case class Evidence(id, tool, args, payloadHash, at, runId, provenance)
enum Provenance: case ServerIssued, Attested

final case class Finding[A] private (claim: A, evidence: NonEmptyList[EvidenceId], …)
object Finding:
  def make[A](claim: A, ev: List[EvidenceId]): Either[NoEvidence, Finding[A]]
```

Private constructor plus a non-empty evidence list ⇒ an unevidenced claim is not
representable.

### modules/vcs — JGit

Owns cloning, traversal, diffing, blame, candidate generation, PySZZ wrapping.

- **Full clone required.** Blame and SZZ need complete history.
- **Rename detection ON** in `DiffFormatter`. Without it a rename reads as delete-all +
  add-all, destroying blame lineage.
- **Parallel checkouts**: JGit's `git worktree` support is weak. Plan: one canonical clone,
  then per-build local clones from it (hardlinked, cheap), each pinned to one commit.
  RISK — validate early. Note the differential oracle needs *two* checkouts per bug
  simultaneously.
- **Blame ignores whitespace.**

**Three candidate nets** (all mechanical; none emits a verdict):

1. **Lexical** — message regex (`fix|bug|resolve|crash|regression|NPE|leak`), issue refs
   (`#123`, `GH-123`, `KEY-456`).
2. **Structural, language-agnostic** — source modified AND test added in the same commit;
   commit referenced from a closed issue's timeline; small diff closing an issue. Catches
   "handle empty section gracefully", which has zero keywords.
3. **Random control sample** — ~50 commits drawn uniformly from those NO net caught,
   adjudicated anyway. Purpose: MEASURE prefilter recall rather than assume it.

Nets are a recall device. All precision comes from `bugfix-adjudicator`.

### modules/forge — GitHub GraphQL v4

One query pulls issue body, labels, timeline, linked PR, review threads, and check runs for a
BATCH of commits per round trip. Caching on etag. Rate limits return `Unknown(RateLimited)`,
never throw. All GitHub data is ServerIssued evidence.

### modules/jvm — ecosystem-complete

**buildprobe** — detects the build tool from markers; infers the JDK from the POM compiler
plugin, `.java-version`, `.sdkmanrc`, CI workflow; compiles only, with a timeout, in a sandbox.
Failure classification is load-bearing: `DEPENDENCY_RESOLUTION` | `COMPILE_ERROR` |
`JDK_MISMATCH` | `TIMEOUT` | `MISSING_TOOLCHAIN`. If most failures are `JDK_MISMATCH`,
provisioning more JDKs recovers large swaths of history. Compile-only gates the expensive
stage: fails in seconds, not eight minutes.

**static** — **SootUp primary** (pure Java, so no Scala version coupling — see D25). OPAL is
Scala-native but is built against Scala 3.7.3 while this project is on the 3.3 LTS, and TASTy
is not forward compatible; it remains available as a cross-check only if the build moves to
3.7+. Where both run, agreement raises confidence and disagreement is recorded, not hidden. Call graphs cached by
`(commit, classpathHash)`. `jvm.method.at_line` joins git coordinates (file + line) to code
coordinates (methods) via the line-number debug table; no debug info → `Unknown(NoDebugInfo)`.
`jvm.callgraph.chains_to(M)` enumerates call chains from public entry points down to the
fault method — the input the synthesist needs to know what to call and in what order.
**Entry points matter**: wrong entry points produce a valid, useless call graph. Supplied by
`build-doctor`.

**coverage** — JaCoCo (Java/Maven/Gradle), per-line hit counts, queried at p(f) using
`OldLines`. **Aggregate, not per-test**: the JaCoCo agent writes one exec dump at JVM exit, so
a covered line was reached by SOME test in the run, not by an identified one. Per-test
attribution would need a dump between tests (fork-per-test plus `jacoco:dump`, or a run
listener) and is not implemented — the schema no longer advertises a `perTest` flag, because a
parameter that is accepted and ignored is worse than one that is absent. The executed subgraph
does not depend on it: per D27 it is built from the FAILING execution’s stack-trace frames
unioned with coverage, and the frames are what supply per-test specificity. scoverage is
likewise not implemented here — it instruments at compile time and cannot be attached to an
already-built revision, so `engine: "scoverage"` is refused rather than answered with JaCoCo
under a scoverage label.

**harness runner** — backs `jvm.harness.run`. Compiles agent-authored harness source against
BOTH revisions, executes both under instrumentation, captures exception (type + site) and
return value, computes `differs`. Sandboxed: no network, wall-clock timeout, memory cap,
temp-dir isolation, output size cap. Pooled — this is the most resource-intensive tool in the
system.

**reproduction drivers** — `repro.botsing` (stack trace → reproducing test) and
`repro.evosuite_sweep` (generate on the fault class, then filter differentially). Both wrap
external processes; neither is reimplemented.

### modules/llvm — deferred peer

cmake/bazel/make detection; `clang -fprofile-instr-generate -fcoverage-mapping` plus
`llvm-cov export`; `clang -emit-llvm` plus `opt` call-graph passes. Namespace `llvm.*`. No
dependency on `modules/jvm` in either direction. Note: KLEE is the natural rung-1/3 analogue
for input synthesis on this side — evaluate when the LLVM pipeline is built.

### modules/metrics — pure

Total functions over `Signal`. Churn, complexity delta, bug lifetime, diffusion, blast radius,
symptom distance, path length, and the importance composite (D19). Purity means reproducible
from stored inputs, exhaustively unit-testable, never a source of nondeterminism.

### modules/graphstore — Neo4j

All writes `MERGE` on natural keys (`repoId+sha`, `repoId+issueNumber`) ⇒ idempotent re-runs,
which matters when a session hits its cap and resumes tomorrow. `RunState` checkpoints per
(stage, candidate). JSON export so a wiped DB does not cost a run.

Model sketch:

```
(Repository)-[:HAS_COMMIT]->(Commit)
(Bug {admitted, rejectedFor, importance, scoredOn})-[:FIXED_BY]->(Commit)
(Bug)-[:INTRODUCED_BY {szzVariant, confidence}]->(Commit)     // metadata only
(Bug)-[:MANIFESTED_AS]->(Symptom)-[:REPORTED_IN]->(Issue)
(Commit)-[:CHANGED]->(Hunk)-[:IN_FILE]->(File)
(Hunk)-[:RESOLVES_TO]->(Method)
(Method)-[:CALLS]->(Method)
(Bug)-[:REPRODUCED_BY {tier, fidelity}]->(Reproducer {source, entryPoint})
(Bug)-[:PATH]->(PathStep {ordinal, tier})-[:AT]->(Method)
(CoverageObservation {atCommit, status})-[:OF]->(Hunk)
(*)-[:SUPPORTED_BY]->(Evidence {tool, args, hash, provenance})
```

Every agent-asserted node carries `SUPPORTED_BY`. Any claim walks back to the tool call that
produced it.

### modules/mcpserver — gatekeeper

Loads `schemas/tools/*.json` at boot and REFUSES to register any tool lacking a schema
(startup assertion plus a test). Validates requests and responses. Mints and persists
EvidenceIds. Enforces `findings.record` validation, per-agent ACLs, the resource pool, and the
harness sandbox policy.

---

## 11. Agents

Six, each a distinct QUESTION rather than a distinct toolset:

| Agent | Question it answers | Model | Fan-out |
|---|---|---|---|
| `mining-strategist` | Where is minable signal, and are we done? | opus | 1 |
| `bugfix-adjudicator` | Is this a genuine bug fix? | sonnet | batched ~15/call, waves ≤5 |
| `build-doctor` | How do I build, test, and enter this project? | sonnet | 1 per repo (cached) |
| `symptom-characterizer` | What did this look like from outside, and where did it surface? | sonnet | batched, waves ≤5 |
| `reproducer-synthesist` | What input makes this bug manifest? | opus | rung-2 bugs only, ≤3 turns |
| `path-tracer` | What route connects the symptom to the fault? | opus | 1 per admitted bug, waves ≤4 |

A "call graph agent" would fail the test: call-graph construction is a CAPABILITY, not a
question.

**Why synthesist and tracer are separate:** synthesis is an iterative compile-run-adapt loop
with its own budget and failure mode; tracing is analysis of a resulting execution. Rungs 0, 1
and 3 can produce a reproducer with **no agent at all**, in which case the synthesist is never
invoked — but the tracer always runs. Merging them would spend opus turns on bugs whose
reproducer came free.

`build-doctor` output is a `BuildRecipe` (tool, JDK, flags, test command, entry points) cached
per repo, so its reasoning cost is paid once, not per commit.

---

## 12. Pipeline

### Notation

```
R       repository URL (the only input)      R*      local clone
E       ecosystem                            ρ       run id
W       candidate window                     K       candidate set, k ∈ K
B(k)    evidence bundle                      V(k)    adjudication verdict
F       confirmed fixes = {k : V(k)=BugFix}  f ∈ F   a fix commit
p(f)    parent of f                          H(f)    hunks (oldRange, newRange)
I(f)    SZZ inducing candidates (metadata)   F_b     buildable subset (hard gate)
G(c)    call graph at commit c               M(f)    fault-site methods
X(f)    coverage of H(f).oldRange at p(f)    ω(f)    importance score
S(f)    symptom + entry point                Rep(f)  reproducer, tier T1..T4
Ex(f)   executed methods from Rep(f)         Π(f)    the path
Φ(f)    path fidelity FULL|PARTIAL|UNIT      μ(f)    metrics
Γ       the Neo4j graph                      ε       evidence id
⊥ᵣ      Unknown with reason r
```

### Stages

**S0 Ingest** — in `R`, out `⟨ρ, R*, E⟩`. Deterministic.
`repo.clone` → `repo.detect_ecosystem`. If `E = unsupported`, halt with detection evidence.
Full clone. Routing decided once.

**S1 Environment** — in `R*`, out `BuildRecipe`. Agent: `build-doctor`.
Reads README/CONTRIBUTING/CI workflow/build files, proposes a recipe, calls `jvm.build.probe`,
reads the classified failure, adapts. Also resolves call-graph entry points.
*Significance:* without correct entry points the call graph is valid and useless; without a
working recipe every downstream stage is Unknown.

**S2 Candidates** — in `⟨R*, W, nets⟩`, out `K` (~200–800). Strategist chooses W and nets;
tools execute. Emits matched-net tags, no verdict.
*Rationale:* choosing a window is adaptive reasoning; matching regexes is mechanical.

**S3 Bundling** — in `K`, out `{B(k)}`. Deterministic, batched 20 per GraphQL query.
*Significance:* adjudicators arrive with evidence in hand; makes parallelism cheap.

**S4 Adjudication** — in `{B(k)}` batched ~15, out `V(k)` → `F`. Agent: `bugfix-adjudicator`,
waves ≤5. The precision gate. `Undecided` preferred over a low-confidence guess.

**S5 Structure** — in `F`, out `⟨H(f), M(f), I(f)⟩`. Deterministic. SZZ runs for every f
(blame needs no build), so bugs that fail later gates still carry real data.

**S6 Build gate (HARD, D11)** — in `F`, out `F_b`.
```
probe(p(f)) ∧ probe(f)  ⇒  f ∈ F_b
otherwise               ⇒  admitted := false, rejectedFor := BUILD_FAILED(classified)
```
Compile-only: failures cost seconds, not minutes. Rejected bugs are recorded, not deleted (D2).

**S7 Coverage + call graph** — in `F_b`, out `X(f), G(p(f))`. Deterministic, pooled.
`X(f) := coverage(p(f), H(f).oldRange)`. This is the admission bar of D3 and the first
ranking dimension.

**S8 Importance ranking (D19)** — in `⟨X(f), G, H(f), S(f)⟩`, out `ω(f)` and an ordering.
Dimensions scored independently:
```
cov(f)        fraction of H(f).oldRange lines COVERED at p(f)
blast(f)      |callers(M(f), depth≤3)| and packagesTouched
symdist(f)    graphDistance(entryPoint(S(f)), M(f))     — Unknown if no symptom
composite     computed ONLY over Known dimensions; scoredOn: [...] recorded
```
*Rationale:* a weighted sum would sink a symptom-less bug to zero, making a missing signal act
as evidence against. A bug ranked on two dimensions is never silently compared with one ranked
on three.

**S9 Symptom** — in `⟨B(f), issue text, comments, tests added⟩`, out
`S(f) = (class, description, entryPoint, confidence, ε*, gaps*)`. Agent:
`symptom-characterizer`, batched, waves ≤5.
`entryPoint` — the outermost stack frame or the action the reporter described — is what S10
targets and what `symdist` measures from. No symptom → `⊥_NoSymptom`, neutral (D19).

**S10 Reproducer acquisition (D16)** — in `⟨f, S(f), M(f), G(p(f))⟩`, out `Rep(f), Ex(f), Φ(f)`.
```
rung 0:  fix added test T   →  run T at p(f); fails ⇒ T1 NATIVE
rung 1:  S(f) has trace     →  repro.botsing            ⇒ T2 SYNTHESIZED
rung 2:  otherwise          →  reproducer-synthesist    ⇒ T2, or T3 REACHED
rung 3:  fallthrough        →  repro.evosuite_sweep + differential filter
rung 4:  fallthrough        →  T4 STATIC
```
The rung-2 loop, budget 3 (D18):
```
agent proposes harness at entryPoint(S(f))       [Φ = FULL]
  jvm.harness.run → { reachedFault, executedMethods, differs, differsOn }
  ¬reached        → inspect branch conditions on chains_to(M(f)), revise args
  reached ¬differs→ infection failed; construct state satisfying the faulty condition
  differs         → SUCCESS; Ex(f) := executedMethods
exhausted at this altitude → retry one boundary inward, Φ degrades FULL→PARTIAL→UNIT (D17)
```

**S11 Path extraction** — in `⟨S(f), M(f), G(p(f)), Ex(f)⟩`, out `Π(f)`. Agent: `path-tracer`,
1 per admitted bug, waves ≤4.
```
Π(f) ⊆ G(p(f)) ∩ Ex(f)        when Rep(f) ∈ {T1, T2}   — an OBSERVED path
Π(f) ⊆ G(p(f))                 when Rep(f) ∈ {T3, T4}   — a HYPOTHESIZED path
```
Each step cites the evidenceId of the query that produced it, and carries the value or state
that carries the fault (a null, a wrong index, an unreleased lock, a stale cache). A gap in
the chain is recorded as a gap with the queries that failed to close it — never bridged by
plausible reasoning. `DISCONNECTED` is a first-class result (reflection, DI, dynamic proxies,
native calls); treating it as failure would pressure the agent into fabricating a chain.

**S12 Metrics** — out `μ(f)`. Pure functions.
```
μ(f) = ⟨ locAdded, locDeleted, filesTouched, hunkCount, testLocRatio, packagesTouched,
         complexityDelta, blastRadius, pathLength, pathFidelity,
         faultLineCoverage : Signal[Map[LineRef, Covered|Uncovered]],
         bugLifetimeDays   : Signal[Int] ⟩
```
If `f ∉ F_b`, coverage fields are `⊥_BuildFailed` — never `0.0`.

**S13 Persist** — idempotent MERGE into Γ plus JSON export. Checkpoint per (stage, candidate).

**S14 Review** — strategist reads Γ statistics and decides widen W / adjust nets / stop. Low
adjudication precision means the NETS mismatch this repo's conventions; low yield means the
WINDOW is too narrow; low T1/T2 rate means the *reproduction* stage is the bottleneck.
Different remedies — distinguishing them is the judgment that justifies this agent.

### Flow

```
R → R*,E → BuildRecipe → K → B(k) → V(k) → F → probe → F_b → X(f),G(p(f)) → ω(f) rank
             [agent]              [agent]        HARD GATE                        │
                                                                                  ↓
                                         S(f) [agent] → Rep(f), Ex(f), Φ(f) → Π(f) [agent]
                                                        rungs 0-4                  │
                                                        [agent only at rung 2]     ↓
                                                                            μ(f) → Γ
```

Everything right of `F` is per-f independent: no f reads another f's results. Parallelism is
structural, not merely attempted.

---

## 13. Framework evaluation metrics

**Path quality — the headline**
Distribution of reproducer tiers (T1/T2/T3/T4); distribution of path fidelity
(FULL/PARTIAL/UNIT); path length distribution; % DISCONNECTED; **fraction of path steps that
are OBSERVED (in `Ex(f)`) rather than merely static** — the single best indicator that the
dataset means what it claims.

**Reproduction effectiveness**
Rung-2 success rate; mean iterations to success; success rate by fidelity altitude (how often
does FULL succeed vs. degrade); Botsing hit rate on stack-trace symptoms; EvoSuite sweep yield.

**Adjudication quality**
Precision on a hand-labeled sample; estimated recall from the random control net; UNDECIDED
rate; inter-run agreement on identical input.

**Data completeness**
% of `F` surviving the build gate, with `rejectedFor` breakdown; Unknown breakdown by reason
(the dataset's honesty report); % with `Known` symptom; % scored on all three ranking
dimensions.

**Evidence integrity**
`findings.record` rejection rate (an agent attempting to assert something unretrieved); mean
evidence citations per claim; % claims traceable to a ServerIssued id.

**Cost**
Subagent invocations per admitted bug; tokens per bug; builds attempted vs succeeded; harness
runs per bug; wall time per bug.

**Controls**
- **Symptom-distance stratification.** "NPE in ConfigLoader.get" names the fault site
  directly; "server returns 500" names something far away. One aggregate statistic across both
  is meaningless — always report stratified by `symdist`.
- **Negative controls.** Run the tracer on commits adjudicated NOT_BUG_FIX. If it confidently
  produces paths through refactors, its confidence is uncalibrated and every other number is
  suspect.
- **Synthesis honesty check.** For T1 bugs (native reproducer exists), *also* run rung 2 and
  compare. If the synthesist cannot reproduce bugs that provably have reproducers, its
  reported failures elsewhere are uninformative.

---

## 14. Assumptions

**Repository**
- A1 Public, cloneable without auth, GitHub-hosted.
- A2 One dominant build ecosystem. Polyglot repos route by dominant language; minority
  languages under-analyzed. Flagged, not solved.
- A3 History not rewritten beyond recognition. Squashed-import repos yield nothing from blame.
- A4 Commit messages and issues in English; non-English should be detected and flagged.

**Bug fixes**
- A5 A fix is substantially contained in one commit or a PR merge commit.
- A6 Deleted or modified lines approximate the fault site. A fix that only ADDS a guard has no
  deleted lines; the fault site is then the added guard's location in the parent.

**Build, execution, analysis**
- A7 Historical revisions may not build, and the failure rate rises with age. Classified and
  recorded — never treated as evidence about the bug.
- A8 Artifacts compiled with line-number debug info. Without it, method resolution is
  `⊥_NoDebugInfo` and paths degrade to file granularity.
- A9 The test suite at p(f) runs deterministically enough for coverage to mean something.
  Flaky suites → `⊥_TestsFailed`.
- A10 Static call graphs over-approximate and miss reflection, DI, dynamic proxies, service
  loaders, native calls. Hypothesized paths (T3/T4) inherit this.
- A11 All execution — build, test, coverage, harness — happens inside a container (D20). No
  network, wall-clock timeout, memory and CPU caps, workspace bind-mount only. Neither
  repository code nor agent-authored code runs on the host. Requires a Docker daemon; first
  runs pay image-pull latency.
- **A17** The differential oracle observes only exceptions and return values (D15). Bugs whose
  sole manifestation is a file write, log line, timing change, or resource leak **cannot be
  confirmed by it** and will land in T3/T4 regardless of how reproducible they really are.
  This is a known blind spot, not a property of those bugs.
- **A18** Reproducing a bug from a synthesized input is an open research problem. A T3/T4
  outcome means *we failed to reproduce it*, never *it is not reproducible*.

**Environment**
- A12 A GitHub PAT is available. Unauthenticated (60 req/hr) cannot support this.
- A13 Neo4j Community runs locally via Docker.
- A14 Disk: full clone + two checkouts per bug + build trees + coverage + harnesses reaches
  tens of GB. `workspace/` needs a retention policy.
- A15 A run is bounded by caps and resumable.

**Epistemics**
- A16 Agent judgments are judgments. Verdicts, symptom classes, synthesized inputs, and paths
  carry confidence and evidence and are labeled DERIVED. Only tool returns are observations.

---

## 15. Settled configuration

| | |
|---|---|
| MCP server name | `causeway` (D21) |
| GitHub auth | PAT available |
| Candidate window | 12 months or 300 commits, whichever is smaller |
| Candidate cap | 60 |
| Adjudication | ~4 batches of 15, waves ≤5 |
| Deep analysis | top 5 bugs by ω; first smoke run 1–2 |
| Rung-2 synthesis budget | 3 iterations per bug (D18) |
| Execution isolation | container for everything past the build gate (D20) |
| Deliverable | Neo4j store of record + JSON export per run |
| No-linked-issue bugs | accepted; `symptomConfidence: low`, `symdist = ⊥_NoSymptom`, neutral in ranking |

Estimated first-run cost: ~14 subagent invocations (1 build-doctor + 4 adjudicator +
1 symptom + ~3 synthesist + 5 tracer).

### Still open

1. Whether to keep the GitHub MCP server registered at all, given D6.
2. Whether to pin an old EvoSuite for its discontinued regression mode, or implement the
   differential filter ourselves over standard generation (currently: ourselves).
3. Target repository for the first run — see §16.

---

## 16. First target repository

Selection criteria for a smoke-test repo: single-module, small (<50k LOC), Maven or sbt,
real test suite, issue tracker with commits linked to issues, builds on a current JDK.

**Strong preference: pick a project that is already in Defects4J.** Then the target repo and
the external calibration set are the same thing — we get known-good ground truth for a subset
of its bugs for free, and can check our extracted paths against instances other researchers
have already validated.

**Recommendation: `jhy/jsoup`** — single-module Maven, in Defects4J, builds on current JDKs,
and its bugs are mostly parser-state bugs where the symptom (wrong parse output) sits well away
from the fault. Good path material.

**Apache projects are disqualified, and this was verified, not assumed.** `apache/commons-csv`
was the original recommendation; a live query returns **zero GitHub issues** for it, because
Apache tracks in JIRA. Every symptom-characterisation input would be missing, and the pipeline
would report a repository with no linked issues when in fact it has a full issue history
somewhere else entirely. `LiveForgeSpec` pins this down as a regression test.

Remaining Defects4J candidates that do use GitHub Issues: `jhy/jsoup`, `google/gson`,
`FasterXML/jackson-core`, `JodaOrg/joda-time`, `mockito/mockito`.

---

## 17. Sources

- Botsing (crash reproduction): https://stamp-project.github.io/botsing/
- EvoSuite: https://www.evosuite.org/ · regression mode: https://www.evosuite.org/evosuiter/
- Call-Chain-Aware LLM-Based Test Generation (2026): https://arxiv.org/pdf/2604.22046
- PySZZ: https://github.com/grosa1/pyszz
- Rosa et al., *Evaluating SZZ Implementations Through a Developer-informed Oracle* (ICSE
  2021): https://arxiv.org/abs/2102.03300
- Comprehensive evaluation of SZZ variants:
  https://www.sciencedirect.com/science/article/pii/S0164121223001243
- AgenticSZZ (2026): https://arxiv.org/abs/2602.02934
- MAS-SZZ (2026): https://arxiv.org/pdf/2604.24398
- Defects4J: https://homes.cs.washington.edu/~rjust/publ/defects4j_issta_2014.pdf
