# Build log

Running record of what is built, what is tested, and what was learned the hard way.
Design lives in [design.md](design.md); decisions are D1–D24 there.

---

## Status

| Module | State | Tests |
|---|---|---|
| `modules/core` | **done** | 34 passing |
| `modules/mcpserver` | registry + schema enforcement + ledger **done**; MCP transport not wired | 23 passing |
| `modules/vcs` | git ops, recall nets, SZZ | 38 passing |
| `modules/metrics` | churn + importance ranking **done**; coverage impact pending | 17 passing |
| `modules/forge` | GraphQL client + parsers | 24 passing (6 live) |
| `modules/graphstore` | Neo4j store + note store **done** | 23 passing (11 live Neo4j) |
| `modules/jvm` | container runner, build probe, differential oracle, call graphs, coverage, **execution tracing** | 60 passing (19 in real containers) |
| `modules/app` | **MCP server runs**; **42 of 47 tools wired** | 35 passing (real MCP protocol) |
| `modules/llvm` | deferred by D5 | — |

**267 tests passing** — 32 against real Docker containers, Neo4j or the GitHub API, plus 6 driving the real MCP protocol.

```bash
export PATH="$HOME/scoop/shims:$PATH"
set -a && . ./.env && set +a          # GITHUB_TOKEN, NEO4J_* for live tests
sbt -batch "core/testOnly *; mcpserver/testOnly *; vcs/testOnly *; metrics/testOnly *; forge/testOnly *; graphstore/testOnly *; jvm/testOnly *; app/testOnly *"
sbt -batch shutdown          # release Windows file locks, see below
```

`sbt test` alone maps to `testQuick` in sbt 2 and skips unchanged suites — use `testOnly *`
to force a full run.

---

## Environment (verified 2026-08-22)

| | |
|---|---|
| JDK | 17.0.16 (Temurin), `JAVA_HOME=C:\Java\jdk-17.0.16.8-hotspot` |
| sbt | **2.0.7**, installed via `scoop install sbt` — was not present |
| node | 22.18.0 (used by `tools/*.mjs`) |
| git | 2.37.0.windows.1 |
| Docker | 29.4.0, daemon **running** |
| Neo4j | 5.26.29 in `causeway-neo4j`, **healthy** |
| GitHub | authenticated, 5000 req/hr |

**Neo4j runs on NON-STANDARD PORTS: 7475 (HTTP) and 7690 (Bolt).** This machine already hosts
another project's Neo4j (`uce-client-neo4j`, neo4j:5.26) on the default 7474/7687, and the
first `up` failed with "port is already allocated". `ops/docker-compose.yml` and `.env` both
point at the moved ports. Do not assume defaults anywhere.

**`.env` holds the GitHub token and is gitignored.** Source it before running live tests:
`set -a && . ./.env && set +a`. The token currently in it was pasted into a chat transcript and
should be rotated.

---

## Toolchain gotchas (each cost a build cycle)

**sbt 2.x, not 1.x.** It already sets `-deprecation`, `-feature`, `-unchecked`,
`-Wunused:all`, `-source` and `-Werror`. Restating any of them is a hard error
("Flag set repeatedly"), not a warning. `build.sbt` therefore sets **no** scalacOptions.

**`-Werror` is on by default**, so an unused import fails the build. Keep imports tight.

**Jackson 3, not Jackson 2.** `json-schema-validator` 3.x is compiled against
`tools.jackson.databind`, not `com.fasterxml.jackson.databind`. The two `JsonNode` types are
unrelated and cannot be passed between libraries. Consequences:

- pin `com.networknt:json-schema-validator:3.0.6` **and** `tools.jackson.core:jackson-databind`
- use the MCP SDK's `mcp-json-jackson3` binding, never `mcp-json-jackson2`
- Jackson 3 dropped generics on `ObjectNode.set` and `JsonNode.deepCopy` — write `set(k, v)`
  and `deepCopy()`, not `set[JsonNode](...)` / `deepCopy[JsonNode]()`
- Jackson 3 renames accessors: `stringValue()` not `asText()`, `propertyNames()` not `fields()`,
  `values()` not `elements()`

**json-schema-validator 3.x API is nothing like 1.x.** There is no `JsonSchemaFactory` and no
`SpecVersion`. Use:

```scala
val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
val schema   = registry.getSchema(node)            // JsonNode overload exists
val errors   = schema.validate(instance).asScala   // java.util.List[com.networknt.schema.Error]
// Error.getMessage, Error.getInstanceLocation
```

**sbt 2 keeps a persistent server, and it holds its own output jars.** On Windows this shows up
as `AccessDeniedException` renaming `*-tests.jar` on the next run. `sbt -batch shutdown` releases
it; killing the JVM is not necessary. Worth running after any aborted test run.

**Maven Central's `search.maven.org` API times out often.** Listing
`https://repo1.maven.org/maven2/<group-path>/` and reading `maven-maven-metadata.xml` is far
more reliable. `javap -cp <jar> <class>` is the fastest way to confirm an API before writing
against it — worth doing rather than guessing twice.

**Scala 3 enum variance.** An enum case that holds no value of the type parameter must say so
explicitly, or it will not widen:

```scala
enum Signal[+A]:
  case Known[+A](value: A, evidence: Vector[EvidenceId]) extends Signal[A]
  case Unknown(reason: UnknownReason, ...)               extends Signal[Nothing]
```

Without the explicit `extends Signal[Nothing]`, `Unknown` is inferred as `Unknown[A]` and
`map`/`flatMap` will not typecheck.

**Do not put `require` in a sealed trait body** to validate abstract members. The trait
initialises before the subclass constructor parameters are assigned, so it reads 0 and
validates nothing while appearing to. `LineRange` validates from each case class body via
`LineRangeValidation.check`, and `LineRangeSpec` has a test that would catch a regression.

**Duplicate companion objects give nonsense errors.** Defining `object EvidenceId` in two files
produced `Not found: tool — did you mean tool?` rather than a duplicate-definition error. If an
error makes no sense, check for a second object of the same name.

---

## modules/core — done

Files: `ids.scala`, `Signal.scala`, `LineRange.scala`, `Model.scala`, `Evidence.scala`,
`Finding.scala`.

Properties the tests actually pin down:

- **A failed build and a genuinely uncovered line are not equal** (`SignalSpec`). This is the
  headline invariant — under `Option#getOrElse(0.0)` both become `0.0`.
- `Signal` has no `getOrElse`. `fold` is the only exit and receives the reason.
- `Unknown` carries evidence: "we asked and could not tell" is itself citable.
- `zipWith` unions evidence from both sides and keeps the **first** unknown's reason.
- `OldLines` and `NewLines` are distinct types; `OldLines(42,42) != NewLines(42,45)`.
- `Finding` has a private constructor; an unevidenced claim is not representable, and a claim
  citing an id the ledger never issued is rejected.
- `EvidenceId.mint` is deterministic and distinguishes tool / args / payload.

---

## modules/mcpserver — enforcement layer done, transport pending

Files: `Json.scala`, `ToolSpec.scala`, `Envelope.scala`, `ToolRegistry.scala`.

`SchemaCatalog.load` reads `schemas/` and **inlines `_common.json`'s `$defs` into every tool
schema**, rewriting `causeway://schemas/_common.json#/$defs/X` to local `#/$defs/X`. This avoids
teaching the validator a custom URI scheme, and keeps the rewrite a small testable function.

`ToolRegistry` is the only place catalog rules are enforced:

- construction **fails** if a handler has no schema (schema-before-invocation, as a startup
  assertion) — a schema with no handler is fine, the catalog precedes the code
- per-agent ACL from `x-causeway.agents`; empty means pipeline-only, `"orchestrator"` bypasses
- input validated before the handler runs; output validated before anything becomes evidence
- a handler exception becomes an `InvocationError`, never a finding
- **a tool may only return the `Unknown` reasons its schema declares** — otherwise the run's
  Unknown breakdown grows categories nobody designed and stops being comparable
- evidence minted from `(tool, canonical args, canonical payload)`; `Json.canonical` sorts keys
  recursively so field order cannot change an id

`Json.canonical` is hand-written rather than configured on the mapper: the property that matters
(same content ⇒ same id) should be readable in one function, not depend on serializer settings
a later change might alter.

### Still to do here

- MCP stdio transport via `io.modelcontextprotocol.sdk:mcp:2.0.1`. Tool results travel as JSON
  text, so the SDK's object model never meets ours.
- Resource pool (2 build slots) — needed before `jvm`.
- `findings_record` and the notes store — need `graphstore` first.

---

---

## modules/vcs — git operations and recall nets done

`GitService` (JGit) and `Candidates` (the three recall nets). Tested entirely offline:
`TestRepo` builds throwaway repositories with JGit itself, with fixed author identity and
timestamps so SHAs are reproducible.

**Rename detection is ON** in the DiffFormatter, and **blame uses `WS_IGNORE_ALL`** — a test
asserts that a whitespace-only reformat does not become the author of every line.

JGit edits are 0-based with an exclusive end; `LineRange` is 1-based inclusive. Conversion is
`OldLines(beginA + 1, endA)`, and an empty side yields `None` rather than a degenerate range.

### What the fixtures taught us

The first ConfigLoader fixture added a null guard and left `return s.lookup(key);` untouched.
The diff correctly reported a **pure insertion: zero deleted lines**. My assertions were wrong,
not the code — and this is **assumption A6 made concrete**: a guard-only fix gives blame-based
SZZ nothing to work backwards from. There are now two fixtures:

- `configLoaderBug()` — the fix REPLACES the faulty line (old 42..42 → new 42..45), so SZZ has
  a line to blame
- `guardOnlyFix()` — insertion only, zero deletions, SZZ will find nothing

Expect a substantial share of real fixes to be the second shape. When SZZ yield looks low, check
this before concluding the repository is unusual.

### Recall nets

Nets are a **recall** device; all precision comes from `bugfix-adjudicator`. Deliberately
over-inclusive: a false positive costs one cheap adjudication, a false negative is a bug that
never enters the dataset.

- **Lexical** — fix words plus issue refs (`#412`, `GH-7`, `CSV-118`; the JIRA-style pattern
  matters, a project using `CSV-118` is not a project without linked issues)
- **Structural** — source changed AND test added in the same commit, small diff. Catches
  "handle empty section gracefully", which has no fix words at all; a test asserts the lexical
  net misses exactly that commit
- **Random control** — samples commits NO net matched, seeded from the window so a resumed run
  draws the same sample. This is how prefilter recall gets **measured** rather than assumed

Merge commits are skipped: their first-parent diff is an entire branch, which makes every
structural signal meaningless.

---

## modules/metrics — churn and importance ranking done

`Importance` is D19 in code. The obvious implementation (normalise, weighted sum) is wrong: a
bug with no characterised symptom would score 0 on `symptomDistance` and sink, making a missing
signal act as evidence against it. Instead each dimension is scored independently, the composite
is the **mean of the KNOWN dimensions only**, and `scoredOn` records which participated.
`ImportanceSpec` has a test that computes what a zero-voting implementation would have produced
and asserts the real result is strictly higher.

Dimension scores **saturate** at absolute thresholds rather than being min-max normalised across
the batch — otherwise a bug's rank would depend on which other bugs happened to be mined
alongside it, and would change on a re-run with a different window. A test asserts a bug scored
alone gets the same number as when scored in a crowd.

---

## modules/forge — done

`Model.scala`, `GraphQl.scala` (pure), `ForgeClient.scala` (HTTP only). Parsing is separated
from transport so the interesting half tests offline; `fixtures/forge/*.json` were **captured
from the live API**, not hand-written, so the parsers are pinned to real response shapes.

The batched bundle query uses GraphQL aliases: 20 commits' stats, CI rollup, associated PR and
linked issues in **one round trip**. Verified live.

### Two findings that changed the design

**`closingIssuesReferences` is often empty even when an issue plainly exists.** jsoup commit
`f10c02e1` is titled "Escape supplementary characters for non-UTF charsets" and issue #2578 has
that exact title — yet GitHub reports no closing reference, because the link was never made with
a keyword. So an empty list means `Unknown(NoLinkedIssue)` **plus a fallback** to issue refs
parsed from the commit message by `modules/vcs`. Reading it as "this project does not link
issues" would be a recall failure disguised as a finding.

**`apache/commons-csv` has zero GitHub issues.** Apache tracks in JIRA. This invalidated the
original first-target recommendation; see design §16. `LiveForgeSpec` keeps it as a regression
test.

**Rate limiting arrives inside a 200 response's `errors` array**, not as an HTTP status — and
also as 403/429 for secondary limits. Both map to `ForgeError.RateLimited` so a throttled run
degrades into recorded Unknowns rather than collapsing.

**JSON null is a `NullNode`, not a Java null.** `Option(node.get("repository"))` wraps a JSON
null in `Some`, so `{"data":{"repository":null}}` silently parsed as an empty result instead of
NotFound. Every optional object access needs `.filterNot(_.isNull)`. Caught by a test.

---

## modules/graphstore — done

`Neo4jStore` (evidence, findings, bugs, checkpoints) and `NoteStore` (file-backed JSONL).

All writes are idempotent `MERGE` on natural keys, so a resumed run overwrites rather than
duplicating. `Neo4jEvidenceLedger` implements core's `EvidenceLedger` against the database, so
the "claim cites an id the ledger never issued" check survives a restart — a test builds a real
`Finding` against it and asserts a fabricated id is refused.

Tests run against the live instance with a unique runId per test and a purge afterwards, and
cancel when Neo4j is unreachable. Testcontainers is the design's stated choice and remains the
right hardening later; a dedicated local instance was faster and sufficient here.

`NoteStore` is deliberately NOT in Neo4j and deliberately mints `note_*`. A test asserts a note
id does not even parse as an `EvidenceId`, and another asserts the `NoteKind` enum contains no
claim-shaped member — if `FACT` or `FINDING` ever appears there, the laundering channel is open
again.

---

## modules/jvm — container runner, build probe, differential oracle

**Verified in real containers**, not asserted in prose: Windows path mounts work,
`--network none` genuinely blocks DNS, resource limits apply, a 20k-line output does not
deadlock (both pipes are drained on their own threads), and a run exceeding its timeout is
killed and reported.

### `sh -c`, never `sh -lc`

A login shell re-initialises PATH from `/etc/profile` and **discards the image's own `ENV
PATH`**. In `eclipse-temurin` that drops `/opt/java/openjdk/bin`, so `javac` and `java` vanish.
Every containerised build would have failed as `MissingToolchain` — blaming the repository for
our shell flag. Caught by the mount test; the fix is a one-word change with a comment on it.

### Network policy is split, refining D20

D20 says everything past the build gate runs in a container. It cannot all run **without
network**: Maven and Gradle must reach a repository, and a network-free build fails as
`DependencyResolution` for a reason having nothing to do with the commit. So:

- **builds and test runs get network** — the project's own code, and it cannot work otherwise
- **harness runs do not** — agent-authored code, and nothing it legitimately needs is outside
  the workspace

Everything still gets a timeout, a memory cap, a CPU cap, and a workspace mount and nothing else.

### The differential oracle (D14) works

`HarnessRunner` stages both revisions into one container, compiles and runs the harness against
each, and compares. Proven on the ConfigLoader NPE with real compilation:

| Case | Result |
|---|---|
| Input that triggers the bug | `differs=true` — parent throws `NullPointerException`, fix returns `"fallback"` |
| Input that reaches the fault but does not infect | `differs=false` |
| Identical revisions | `differs=false` — no false positives |
| Harness that does not compile | reported as a compile failure, **not** as "did not reproduce" |
| Return-value-only difference | detected, no exception either side |
| Difference visible only via stdout | **not** detected — A17's blind spot, asserted as a test |

That last row is deliberate. It keeps the limitation honest and visible rather than latent.

Implementation notes: the result line uses a `` separator built from `1.toChar` rather
than written as an escape — the escape is processed by the Scala lexer AND again by javac inside
the generated source, which is an easy way to split on the wrong thing and misread every run as
identical. The `Runner` wrapper is generated, not requested from the agent, so a mistake in the
reporting protocol cannot be misread as the bug failing to reproduce.

---

## modules/app — the server runs

`Handles` (server-side state referred to by handle), `VcsHandlers` (adapters over the vcs
services), `Main` (wiring). `mcpserver` stays a pure framework and knows nothing about JGit,
GitHub or Docker; `app` is the only place they meet.

`TransportSpec` drives the **real MCP stdio transport in-process over piped streams**:
`initialize` handshake, `tools/list`, `tools/call`, and error handling. A subprocess would prove
the same thing but needs a packaged jar and is far harder to diagnose. `EndToEndSpec` takes an
MCP-shaped call all the way to JGit and back through schema validation and evidence minting.

### Three things this shook out

**The SDK validates tool inputs itself, by default.** Against the same schema we publish, so it
is not wrong — but it means an agent sees *two different error formats* for one class of
problem: the SDK's plain string, or our structured `{ok:false, error, detail}`. Switched off
with `validateToolInputs(false)` so `ToolRegistry` is the single authority. It already
validates, applies the ACL and mints evidence; consistency for the caller is worth more than a
second check against an identical schema. A test now asserts a malformed argument and a refused
agent come back in the same shape.

**Handlers were returning `Unknown` reasons their schemas never declared.** `history_diff`,
`history_commit_meta`, `history_list_commits`, `history_candidates` and `repo_clone` all
legitimately return `NotApplicable` (unregistered handle, unresolvable sha) but declared only
`Truncated` or nothing. The registry refused them — exactly as designed — and the schemas were
corrected. This is the guard that keeps a run's Unknown breakdown comparable between runs.

**MCP carries no caller identity.** Every Claude Code subagent shares the parent session's
connection, so the transport genuinely cannot tell `path-tracer` from `bugfix-adjudicator`. The
real per-agent ACL is therefore each agent's `tools:` frontmatter, which `sync-catalog.mjs`
verifies against `x-causeway.agents` in both directions. The server's `_agent` argument is
defence in depth, checked when supplied — **not** a boundary against a caller that lies. Written
down in `ToolBridge` so nobody mistakes it for one later.

### Packaging — `sbt app/dist`

**Not a fat jar, deliberately.** The MCP SDK discovers its JSON mapper through `ServiceLoader`;
shading everything into one archive means merging `META-INF/services`, and getting that wrong
fails silently at startup rather than at build time. `app/dist` copies 39 jars (~21 MB) into
`target/dist/lib` and writes `causeway.cmd` / `causeway` launchers. `.mcp.json` invokes
`java -cp "target/dist/lib/*" causeway.app.Main` directly — no script, works on both platforms.

Two sbt 2 traps hit here:

- **`taskKey[File]` is rejected.** sbt 2 caches task results and will not accept `File` or
  `Path` as an output type; use `Unit` (or `Def.uncached`).
- **Scala comments NEST.** A glob pattern inside a doc comment opens a nested comment that
  swallows the rest of `build.sbt`, reported only as "unclosed comment" hundreds of lines away.

### Proven live

The packaged server was driven by a real JSON-RPC client over stdio against a real git
repository: `initialize` handshake, `tools/list` (8 of 47 tools — only those with handlers are
advertised), `repo_clone`, `history_list_commits`, `history_diff`, `history_candidates`, plus
schema rejection and ACL refusal. Evidence ids matched across repeated identical calls, and the
Neo4j-backed ledger was in use.

**The live run found a bug the unit tests had not.** A root commit has no parent, so the diff
passed `null` as the old tree; JGit threw, and the surrounding `Try` turned that into "no files
changed". Every repository's first commit looked empty, and could never match the structural
recall net. Fixed with `EmptyTreeIterator`, with two regression tests. Worth remembering that
the offline fixtures never exercised a root commit's diff — the demo did.

---

## Call graphs — SootUp, not OPAL

**The choice was forced, not preferred.** OPAL 7.0.0 does publish Scala 3 artifacts, but its
POM shows it is built against **Scala 3.7.3**. This project is on **3.3.6 LTS**, and a 3.3
compiler cannot read TASTy emitted by 3.7 — so adopting OPAL would pin the whole project's
Scala version to whatever OPAL was last built with. SootUp 3.0.1 is pure Java and couples to
nothing. Recorded as D25.

Check a library's POM for `scala3-library_3` before assuming a `_3` artifact is usable.

### API, confirmed by javap before writing against it

```scala
val inputs = List(JavaClassPathAnalysisInputLocation(cp), DefaultRuntimeAnalysisInputLocation())
val view   = JavaView(inputs)
val graph  = ClassHierarchyAnalysisAlgorithm(view).initialize(entryPointSignatures)
graph.callTargetsFrom(sig) / callSourcesTo(sig) / getMethodSignatures / callCount
```

The JDK's own classes must be an input location. CHA has to resolve `java.lang.Object` to reason
about virtual dispatch at all; without them the graph silently loses every edge through an
inherited method.

Tests compile real Java with the host JDK's `ToolProvider` compiler and analyse the resulting
bytecode — fast, offline, no container. A hand-built fixture graph would exercise the queries
but not the frontend that has to read genuine class files.

### Two bugs the tests caught

**Two descriptor dialects.** `toRef` emits SootUp's readable form
`(java.lang.String,java.lang.String)java.lang.String`, while anything from raw bytecode uses a
JVM descriptor `(Ljava/lang/String;I)V`. The parser handled only the JVM form, so overload
resolution fell through to "first candidate" and silently picked the **wrong** `ConfigLoader.get`
— after which every caller query for it correctly returned nothing, and three tests failed for
what looked like unrelated reasons. Both dialects are now parsed (a JVM descriptor never
contains a comma or a dot), and an overload that cannot be matched is **refused rather than
guessed**: a wrong method produces a plausible path to the wrong code.

**A BFS that only ever reached depth 1.** `callers` recorded newly-found nodes into `seen`, then
set the next frontier to nodes *not* in `seen` — which excluded everything just discovered. It
looked like it ran to the requested depth and always stopped at one hop.

---

## Coverage — JaCoCo 0.8.15

Split in two on purpose: the container runs the project's own suite under the agent and produces
a `.exec`; the **host** parses it against the class files with `org.jacoco.core`. Parsing is
therefore pure and testable without Docker, and the container needs nothing but the agent jar —
which `org.jacoco.agent.AgentJar.extractTo` writes out from an embedded resource, so there is no
separate download step.

`append=false` on the agent argument is not cosmetic. A stale exec from a previous revision
would silently merge into this one, and the parent commit's coverage would leak into the fix —
corrupting precisely the before/after comparison D3 depends on.

Tests run a real program under the real agent on the host and assert against genuine
instrumentation output: an executed line reads `Covered`, an unexecuted branch reads
`Uncovered`, and a never-called method is uncovered throughout. That is the D3 question
verbatim — was the faulty line exercised before the fix?

### Two things to know before reading any coverage number

**JaCoCo attributes a class's implicit default constructor to the CLASS DECLARATION line.**
`public class ConfigLoader {` reads as covered purely because the class was instantiated. A fix
whose hunk happens to begin on a class declaration will look covered even if none of its real
code ran. Pinned by a test so it stays visible.

**Non-executable lines are excluded from the fraction, not counted as uncovered.** Otherwise a
well-commented method scores worse than an undocumented one. A range with nothing executable
returns `None`, never `0.0`.

Path matching is by **suffix**: JaCoCo knows a class's package and source file name but nothing
about repository layout, so `cfg/ConfigLoader.java` and `src/main/java/cfg/ConfigLoader.java`
resolve to the same file.

---

## Call-graph and build tools wired into the server

`JvmHandlers` exposes `jvm_build_toolchain`, `jvm_build_probe`, `jvm_callgraph_build`,
`jvm_callgraph_paths`, `jvm_callgraph_callers` and `jvm_callgraph_chains_to`. 14 of 47 catalogued
tools now have handlers.

`Handles` gained build and call-graph registries. Classpath discovery is **searched, not
assumed**: which directory holds compiled classes depends on the build tool, and guessing
`target/classes` would silently produce an empty call graph on a Gradle or sbt project —
indistinguishable from a project whose code calls nothing. Known layouts are checked first, then
a depth-bounded search for directories containing `.class` files.

The `NotApplicable` schema gap recurred here exactly as it did for the vcs tools: **every**
handler can be asked about a handle that was never registered. Worth treating as the default
rather than something to rediscover per tool.

---

## SZZ — R-SZZ over JGit, NOT PySZZ

**Checked first, then deviated, and the reason is concrete.** PySZZ requires **srcML** — a native
C++ binary that must be installed per platform and put on `PATH`. The only clean way to depend
on it here is a purpose-built container image, which is a lot of machinery for what D4 demoted
to a *metadata enricher*. So `causeway.vcs.Szz` implements the R-SZZ heuristic directly over
JGit blame, clearly labelled as our implementation rather than the published tool.

Implemented: B-SZZ (all blamed commits) and R-SZZ (most recent only — the variant that won
Rosa et al.'s oracle evaluation). Requesting `AG-SZZ`, `MA-SZZ` or `RA-SZZ` returns Unknown
rather than quietly substituting R-SZZ: **a result labelled `RA-SZZ` that was actually computed
by R-SZZ would be worse than no result.**

Filters, each recorded in the output so a consumer can see what was applied:
`ignore-whitespace` (blame runs with `WS_IGNORE_ALL`), `drop-cosmetic-lines` (a deleted brace or
comment must not blame whoever last reformatted the file), `exclude-commits-after-report` (the
original paper's meta-change filter — a commit made after the bug was reported cannot have
caused it), and `most-recent-only` for R-SZZ.

**`blameless` is a first-class outcome.** A guard-only fix deletes nothing, so blame has no line
to work backwards from — and the literature puts that at ~14% of bug-fixing commits, with a
further 28% needing history traversal beyond blame. An empty `inducing` array means *this method
cannot see the origin*, never *the bug has no origin*. The `guardOnlyFix` fixture pins it.

---

## Recording tools — the enforcement path, wired

`RecordHandlers` exposes `findings_record` and the three note tools. This is the only place in
the system that validates **provenance** rather than shape.

A rejection is a **successful call**, not a protocol error. The agent asked "may I assert this?"
and gets "no, and here is which ids the ledger never issued" — which invites retrieval. An error
would invite rephrasing, which is exactly the wrong reflex.

### Proven live, over the real protocol

```
szz           -> R-SZZ 893c5481 conf=1
               filters: ignore-whitespace, drop-cosmetic-lines, most-recent-only
forged claim  -> accepted=false | this run's ledger never issued: ev_deadbeefdeadbeef.
                 Retrieve the fact rather than rephrasing the claim.
honest claim  -> accepted=true finding_000000009c10c38b
note written  -> note_7da74e19dc1f38fa
cite the note -> isError=true InvalidInput
```

That last pair is the whole design in two lines. A note is written, gets a `note_*` id, and an
attempt to cite it as evidence is refused **by the schema, before the handler ever runs** —
because `findings_record`'s `evidence` items must match `^ev_`. The separation between "what an
agent DOES" and "what an agent may SAY" is a type, not a convention.

### NotApplicable is now declared catalogue-wide

The gap recurred for the vcs tools, then the jvm tools, then the record tools. It is simply
true of every handler: any of them can be asked about a handle, commit or argument that does not
resolve, and that is a determinable-but-absent answer rather than a failure. Declared on all 47
schemas with the rationale recorded in `_common.json`.

---

## First real run — jhy/jsoup

The whole point of the exercise: a repository that did not come from a fixture.

```
serving 40 of 47 catalogued tools
repo_clone      -> branch=master commits=2519
ecosystem       -> JVM markers=pom.xml
candidates      -> 94 of 120 scanned; {"LEXICAL":81,"STRUCTURAL":40,"RANDOM_CONTROL":5}
churn           -> +16/-2 across 4 files, 4 packages, testRatio=0.78
szz             -> R-SZZ 77966f79 conf=1
forge_issue     -> #2580 [MERGED] HTTP redirects use standard URL resolution...
bundle          -> 5 candidates in ONE round trip
```

### It found three bugs no fixture would have

**1. `forge_*` handlers needed `repoHandle`, and the schemas never declared it.** The handlers
derive owner/name from the clone — deliberately, so an agent cannot query one repository while
analysing another's commits — but `repoHandle` was not in the input schema, so every call was
rejected before reaching the handler. Caught because the real run actually called them.

**2. `ISO-8859` was reported as a linked issue.** The JIRA-key pattern `[A-Z]+-\d+` cannot tell
`CSV-118` from `ISO-8859` on shape alone, and a jsoup commit about character escaping tripped
it. Fixed with a short denylist of standards prefixes (`ISO`, `UTF`, `RFC`, `SHA`, `HTTP`, …).
Deliberately SHORT, because the costs are asymmetric: a false positive costs one issue lookup
that returns not-found and is recorded as a gap, whereas a false negative loses a linked issue
entirely and starves symptom characterisation. **When in doubt, let it through.**

**3. Most `#NNNN` references are PULL REQUESTS, not issues.** jsoup squash-merges, so commit
messages end in `(#2580)` — and #2580 is a PR. `issue(number:)` returned "Could not resolve to
an Issue", which reads as *this project does not link issues* when the link was perfectly fine.
Now queried as `issueOrPullRequest`, which resolves both, with the kind recorded. A PR body
carries the same symptom evidence an issue body would; refusing to read it discards exactly the
text symptom characterisation needs.

A related correctness fix fell out of it: **"Could not resolve to an X" is an ANSWER, not a
failure.** It now maps to `NotFound` → `Unknown(NotApplicable)` rather than `ToolUnavailable`,
so an absent issue is recorded as a gap instead of looking like an outage and sending an agent
into retries.

---

## Execution tracing — observed paths, completed

The design's central claim is that a static path means propagation was POSSIBLE while an
executed one means it HAPPENED. That distinction was unbacked until now.

The harness run is instrumented with the JaCoCo agent; the parent side's `.exec` is parsed on
the host into the set of methods that actually ran. `jvm_trace_executed` returns it, and
`jvm_trace_path_in` searches for a path **confined to that set** — a statically plausible hop
absent from the trace is not traversed at all.

Demonstrated over the real protocol:

```
differs      -> true on [EXCEPTION,RETURN]
  at parent  -> java.lang.NullPointerException
  at fix     -> returned fallback
trace        -> tr_c66a03f32077e429 (12 methods executed)
executed     -> Handler.handle, ConfigService.load, ConfigLoader.get, ...
               (ConfigLoader.unusedHelper exists but never ran — correctly absent)
```

### Coverage alone was NOT enough (D27)

The first working run produced a trace missing `Handler.handle` and `ConfigService.load` while
containing `ConfigLoader.get`, which only they can reach. Not a bug in the wiring: **JaCoCo
places probes at branch points and method exits, so a method whose call THROWS records zero
covered instructions** even though it certainly ran.

For a bug that manifests as an exception — the commonest kind this project handles — that
omits precisely the chain leading to the fault. The methods on the path are exactly the ones
undercounted.

The fix is to take the executed set as **coverage UNION the frames of the throw**. The stack
trace names those frames exactly; it is the natural complement, seeing what was still on the
stack when things went wrong while coverage sees what completed. A test pins it: a method that
threw rather than returned must still appear.

That forced a second change. Methods are now matched across producers by **class and name
only**, because JaCoCo emits `(Ljava/lang/String;)V`, SootUp emits `(java.lang.String)void`,
and a stack trace carries no descriptor at all. Overloads collide under that key — accepted
deliberately, since demanding descriptors would empty the intersection and silently demote
every observed path to hypothesised.

---

## Line endings — a silent fixture breaker (D28)

Several tests started failing with no compile error and no obvious cause, including one that
touched neither Docker nor the network. **Python's text-mode write converts `
` to `
` on
Windows**, so every file patched through a Python script had quietly become CRLF. Scala does not
care — except in a multi-line string literal whose content is later searched with `"
"`, which
then never matches. That is exactly how the test fixtures are built.

All sources normalised to LF; any tool that rewrites a source file must pass `newline=''`.

---

## The agent layer, exercised as far as it can be here

The six agents have not RUN — that needs Claude Code restarted with the server registered, and
this session predates the server existing. Two things were checked instead, both of which cover
failures that would otherwise surface mid-run after budget had been spent.

**`tools/check-agents.mjs`** starts the real server, asks what it serves, and compares against
each agent's frontmatter. `sync-catalog` is a static check — a tool can have a schema, be
granted to an agent, and still have no handler. All six agents: every granted tool served.

```
bugfix-adjudicator   granted 13, served 13     path-tracer            granted 19, served 19
build-doctor         granted  8, served  8     reproducer-synthesist  granted 17, served 17
mining-strategist    granted  8, served  8     symptom-characterizer  granted 10, served 10
```

**A procedure walk** then executed each agent's DOCUMENTED steps against jsoup under that
agent's identity, so the ACL was exercised for real. It cannot test judgement; it tests that the
procedure each prompt describes is actually executable with the tools that agent holds.

### It found two schema/handler contradictions

Both were cases where the schema stated the correct intent and the HANDLER was wrong — so the
schema was kept and the handler fixed.

**`graph_resume_state` required a `runId` the caller cannot have.** The strategist's prompt says
to call it first to find an interrupted prior run — but an agent starting work does not know a
previous run's id. The schema asked for `repoUrl`; the handler ignored it and demanded `runId`,
making the tool answerable only when the answer was already known. Now runs are linked to their
repository (a `Run` node written on checkpoint) and resume discovers the latest run for a repo.
No prior run is a valid answer, not an error.

**`jvm_build_toolchain` ignored the commit it was given.** It read the working tree, so asking
about a decade-old revision reported TODAY's build tool and Java level. Build files change over
history — a project on Maven in 2016 may be on Gradle now — and probing old revisions is the
entire purpose. It now reads the build files at the requested commit through git, which also
means `build-doctor` gets the right base image for the revision it is actually probing.

Neither would have been caught by unit tests: both handlers were tested against fixtures that
passed the arguments the handler happened to use.

---

## The first agent run — what it found, and what it lost

The agents ran against jsoup: **236 tool calls, 0 tool failures**, across mining-strategist (1),
build-doctor (1) and bugfix-adjudicator (4 batched waves). Telemetry in
`runs/04cbd17b-.../`. It got as far as adjudication and the build gate; symptom, reproducer
and path stages were never reached.

### Credentials: three bugs stacked, found by two real runs

**1. An unset `${VAR}` in `.mcp.json` does not vanish.** It can survive as the LITERAL string
`${GITHUB_TOKEN}` — perfectly non-empty, and not a credential. It was sent as a Bearer token and
every forge call returned 401. For Neo4j the same value became a password and the store fell
back to in-memory. `env()` now rejects any value that still looks like `${...}`.

**2. Startup claimed `github: authenticated` having checked only that a STRING EXISTED.** No
request was made. That is an unverified claim in the codebase whose founding rule forbids
exactly that — and it cost a twenty-minute run, because the operator had no way to know. There
is now a real `viewer{login}` call at startup: `authenticated as <login>`, or
`TOKEN REJECTED — <error>`. Never asserted, always retrieved.

**3. `.env` loading filtered precedence in two places.** `loadDotEnv` skipped any key already in
the environment — and a placeholder counts as present, so it suppressed the real value from the
file. Precedence is now decided in exactly one function.

Secrets were also removed from `.mcp.json` altogether: the server reads `.env` itself, so there
is nothing left to expand wrongly. Verified against all three scenarios — placeholder, empty
environment, and a genuinely bad token — each now reports the truth.

### The run silently lost every finding

58 `findings_record` calls returned `accepted: true`. Neo4j holds **none** of them — the newest
evidence predates the run by seven minutes. The server had fallen back to the in-memory ledger
and said so on stderr, where nothing surfaces it.

**Root cause, and it is nastier than "the variable was unset".** `.mcp.json` declares
`NEO4J_PASSWORD: "${NEO4J_PASSWORD}"`. An unset variable expands to an **empty string**, not to
nothing — and `sys.env.getOrElse("NEO4J_PASSWORD", "causeway-dev")` sees a key that IS present
and returns `""`. The empty value overrode the working default. The same applied to
`GITHUB_TOKEN`, which the build-doctor independently noticed and wrote down: *"forge_ci_status /
GitHub API is returning HTTP 401 Bad credentials in this environment"*.

Two fixes:

- **The server reads `.env` itself**, and treats an empty value as absent
  (`sys.env.get(k).filter(_.nonEmpty)`). Credentials no longer depend on how Claude Code was
  launched. Verified by starting the server with variables unset AND with them set to empty —
  both now reach Neo4j and GitHub.
- **`findings_record` reports `persisted` separately from `accepted`.** A claim that is accepted
  and then evaporates is exactly the quiet failure this project exists to prevent, so the two
  are now distinct facts and the tool says which it achieved.

### The build-doctor found a bug in our code

Its note records that `eclipse-temurin:17-jdk` has **no `mvn` binary** — exit 127, classified
`MISSING_TOOLCHAIN` — and that `maven:3.9.9-eclipse-temurin-17` works. `BuildProbe.defaultRecipe`
was pairing a plain JDK image with `mvn compile`, so **every Maven probe would have failed** and
been recorded as "this repository has no usable build" rather than "we chose the wrong image".

`imageFor` is now tool-aware (`maven:*`, `gradle:*`, `sbtscala/scala-sbt`), with
`runtimeImageFor` kept for running compiled code where no build tool is needed. The tests only
ever asserted the JDK version in the image name, which is why they missed it.

The same note also worked out things no test would have: that `package`/`verify` triggers
japicmp (which downloads a previous release and can fail independently of the code under test),
that JDK 9 and 10 activate neither pom profile and **silently compile nothing**, and the
call-graph entry points for a library with no `main`. That is the agent layer doing the job it
was designed for.

### What this says about the design

Every deterministic guard behaved correctly under real conditions: `MISSING_TOOLCHAIN` fired
with the right classification, the 401 surfaced as a recorded gap rather than a guess, and the
agents wrote notes instead of asserting. The failure was at the seam between the harness and
the server — the one place nothing was testing.

---

## Coverage and checkout wired (44 of 47 served)

A live `/mine-repo` run stopped at the coverage stage and ASKED how to proceed rather than
inventing a number: `jvm_coverage_run has a schema but no handler`. That is the catalog doing
its job — a schema without a handler is a visible gap, not a silent one — but the gap was worth
closing, so `jvm_coverage_run` and `repo_checkout` now have handlers in `AnalysisHandlers`.

Three decisions inside `coverageRun`, each of which could have gone the fabricating way:

- **`engine: "scoverage"` is refused with `NotApplicable`**, not quietly answered with JaCoCo.
  scoverage instruments at COMPILE time; it cannot be bolted onto an already-built revision. A
  JaCoCo number labelled `scoverage` would be worse than no number, because the label is what a
  downstream reader trusts.
- **No compiled classes → `Unknown(BuildFailed)`.** Coverage over nothing is 0%, and 0% is a
  measurement. The absence of a build is not.
- **The test COUNT is optional.** `jvm_coverage_run`'s output `required` was relaxed to
  `["reportId","engine"]`: `parseTestCounts` reads Surefire and Gradle summaries, and when the
  format is unrecognised the field is simply omitted. Requiring it would have forced a zero.

`parseTestCounts` takes the LAST Surefire summary, not the first — Maven prints one per module
then a total, and the first line is a single module's tally masquerading as the project's.

### Two parameters the system could never honour

Wiring the handler exposed a promise the catalog had been making since the schemas were
written. `jvm_coverage_run` accepted `perTest` ("Record per-test coverage, needed for
executed-subgraph computation. Default true") and `jvm_coverage_lines` accepted `testFilter`
("Restrict to lines executed by this test") — and NOTHING implemented either. A grep for both
names found exactly two hits, in the schemas that declared them.

This is worse than a missing feature. The JaCoCo agent writes ONE exec dump at JVM exit, so the
report is aggregate: a covered line was reached by some test in the run. A caller passing
`perTest: true` would have received that aggregate and computed an executed subgraph as the
UNION over every test in the suite, while believing it had one test's footprint. The parameter
was accepted, ignored, and the wrong answer came back looking right.

Both parameters are now removed, and both tool descriptions state that coverage is aggregate.
Per-test attribution would need a dump between tests (fork-per-test plus `jacoco:dump`, or a
run listener); it is a real feature, not a flag. The executed-subgraph computation does not
depend on it — per D27 it unions coverage with the FAILING execution's stack-trace frames, and
the frames are what carry per-test specificity.

The lesson generalises past this tool: schemas were written before handlers, which is the right
order, but it means an unwired tool's parameters have never been challenged by an
implementation. **Every remaining parameter on an unwired tool is an untested promise.**

### Two sbt 2 behaviours that hide themselves

Both cost a full cycle each because they report SUCCESS while doing nothing.

1. **`test` is incremental in sbt 2** — it delegates to `testQuick` semantics and prints
   `No tests to run` for unchanged modules. It is not the full-suite command it is in sbt 1.
   Use `sbt -batch "app/testOnly *"` (or clean) when you actually want everything to run.
2. **Task results are CACHED, including `Unit`.** `dist` copies files as its entire purpose, so
   its effect lives outside its declared inputs — a second invocation logged `[success]` and
   wrote nothing at all. Fixed with `Def.uncached`. Any task whose value is a side effect on the
   filesystem needs the same treatment, or the log will insist a stale distribution is fresh.

### Stale build output, and the zero-test coverage report

With the agent finally attaching, the run produced a coverage report and `testsRun: 0`. The
surefire dump explained it:

```
org/jsoup/helper/AuthenticationHandlerTest has been compiled by a more recent version of the
Java Runtime (class file version 55.0), this version only recognizes up to 52.0
```

`target/test-classes` held Java 11 bytecode from an EARLIER run, and the recipe called for JDK 8
(jsoup declares `maven.compiler.source` 8, but part of its test tree lives in a profile that
activates only on JDK 11+). Maven recompiles a source whose class file is older than it — these
were newer, so the main sources rebuilt and the tests did not. Surefire died before the first
test. The build reported SUCCESS.

Two separate defects, and the second is the dangerous one.

**A working tree is reused across revisions and toolchains.** `BuildProbe` now writes the image
it built with to `.causeway/build-image` and cleans when the next recipe names a different one.
Output present with NO marker counts as stale: unknown provenance is not the same as clean, and
a fresh clone has no output directory, so the conservative choice is free where it does not
matter.

**A run that executed zero tests is not a coverage measurement.** The exec file still parsed —
the agent attached, Maven's own JVM touched a handful of classes — so the report looked like a
result and said jsoup barely covers itself. `jvm_coverage_run` now returns
`Unknown(TestsFailed)` with the run's tail when the parsed test count is zero. This is exactly
what `Signal` exists for: a broken RUN and a poorly tested PROJECT produce the same number and
must not produce the same answer.

### One reportId for two revisions

With the suite finally running — **2,035 tests, 0 failures** — the id came back identical to the
one the broken zero-test run had produced. It was hashing the exec file's PATH:

```scala
val id = f"cov_${(execFile.toString + classDirs.mkString).hashCode...}"
```

That path is `<clone>/.causeway/jacoco.exec` for every run of a repository. So the parent
revision and the fix — the two measurements this entire project exists to COMPARE — shared an
id, and so did a broken run and the good one that replaced it. An agent citing a `reportId` was
citing "some coverage of this repo", which is not a fact about anything, and the evidence ledger
would have accepted it as one.

Now a SHA-256 over the exec file's contents plus the class directories it was resolved against.
Both halves matter: the probe-to-line mapping comes from the classes, so the same exec analysed
against a different tree is a different report.

This one is worth remembering as a pattern rather than a bug. Every other identifier in the
system is content-derived — that is the whole design of `EvidenceId` — and this one was not,
because it was written as a cache key before it became a citation.

### A dependency cache, because every build started from nothing

Each container began with an empty `~/.m2` and re-downloaded the whole tree — visible in the
logs as minutes of doxia and plexus jars at 50 kB/s. This project builds TWO revisions per bug,
so the waste doubles per bug.

`ContainerPolicy` now carries extra mounts and environment, and `DependencyCache.forTool` maps
each build tool onto its cache location: `-Dmaven.repo.local` for Maven, `GRADLE_USER_HOME` for
Gradle, `COURSIER_CACHE` plus `sbt.ivy.home` for sbt. Environment variables rather than guessing
`$HOME`, because the official images do not agree on which user they run as.

Bind mounts under `workspace/caches`, not named volumes: a fresh named volume is owned by root
and the gradle image does not run as root, so the build would fail on a permission error that
looks nothing like its cause. A bind mount is also inspectable and deletable without docker.

Builds get the cache. **Harness runs do not** — that is agent-authored code running with
`--network none`, and nothing it legitimately needs lives outside the workspace.

### The parameter audit, and what it found

`tools/check-params.mjs` greps the Scala sources for every input parameter every schema
declares. It is deliberately over-eager — a name used for something else counts as a hit —
because a false positive costs one manual check and a false negative costs a silent wrong
answer. It found nine tools, and the list was worse than "features not built yet":

| tool | parameter | verdict |
|---|---|---|
| `history_list_commits` | `until` | **implemented** — an ignored upper bound returns MORE history than asked for, which reads as a complete answer |
| `repo_clone` | `refresh` | **implemented** — asking to re-fetch and silently getting the stale clone |
| `jvm_callgraph_chains_to` | `onlyPublicEntries` | **implemented**, three-valued (below) |
| `export_run` | `format` | **implemented** — `jsonl` streams one object per line |
| `jvm_coverage_run` | `perTest` | **removed** — JaCoCo's dump is aggregate |
| `jvm_coverage_lines` | `testFilter` | **removed** — same reason |
| `history_diff` | `contextLines` | **removed** — widening hunks would corrupt the changed-line ranges this project treats as ground truth; `history_file_at` gives context without touching them |
| `jvm_callgraph_build` | `crossCheckWithSootUp` | **removed** — SootUp IS the engine; OPAL was dropped over the TASTy incompatibility, so there is no second implementation to check against |
| `evidence_attest`, `repro_*` | (all) | unserved tools, recorded with reasons in the checker |

`onlyPublicEntries` is the interesting one. "Public entry" is approximated by having no callers
in the graph, and the old code wrote `callers(...).map(_.isEmpty).getOrElse(false)` — so a
FAILED lookup was recorded as "not public". Turning that into a filter would have dropped chains
because a measurement failed, which is precisely the rule this project is built on: a signal
that could not be computed is neutral, never evidence against. The judgement is now
`Option[Boolean]`, the filter keeps `None`, and the response reports how many chains the filter
removed so an empty result cannot be misread.

**`export_run` was the worst of them.** It wrote checkpoint rows — how far the run got — and
reported `"bugCount": 0` unconditionally, a hard-coded number in the one artefact meant to
outlive the database. It now exports the bugs themselves (including the ones that failed
admission, per D2 — an export of only admitted bugs hides the selection bias behind a cleaner
file), the findings with their evidence counts, and a real count. `byteSize` counts BYTES rather
than characters, which is what tells a truncated file from a complete one.

### The coverage happy path, and why it had never run

The refusal branches were unit-tested; the branch that actually measures something had never
executed. Driving clone -> build_probe -> coverage_run against jsoup over the real server found
three defects in one run, none of which a unit test would have reached.

**The agent never attached.** The command passed `-DargLine="-javaagent:..."`, and jsoup's pom
hard-codes `<argLine>-Xss640k</argLine>` in its surefire configuration. A POM-level `argLine`
OVERRIDES the property, so the flag was accepted and discarded, the suite ran clean, and the
only symptom was a missing exec file. Now `JAVA_TOOL_OPTIONS`, which the JVM honours
unconditionally and ADDITIVELY — the project keeps its own `-Xss640k`.

That change forces a second one: `JAVA_TOOL_OPTIONS` reaches every JVM in the build, Maven's
included, so the agent must run with `append=true`. With `append=false` the LAST JVM to exit is
Maven's, and it would truncate the file and discard the test JVM's data. The stale-data
guarantee that `append=false` was giving is now enforced directly, by deleting the exec file
before the run — which is strictly better, because it also covers the case where the agent
fails to attach and an OLD exec file would otherwise be parsed as this run's result.

**The fallback made things worse.** The command ended `|| $testCommand` — on failure it re-ran
the entire suite without the agent, which cannot produce an exec file. It doubled the wall clock
and guaranteed nothing came back. Removed.

**The diagnosis was thrown away.** When the exec file was missing the caller got
`no exec file at ...\jacoco.exec` and nothing else: the `ContainerResult` was bound in a
for-comprehension that discarded it on the error path. The test run's exit code and output tail
— the only things that explain WHY — now travel with the error, because that is what
`build-doctor` needs to fix the recipe.

### Rebuilding while the server is running

On Windows a jar held open by a live JVM cannot be deleted or renamed, so `sbt app/dist` fails
partway and leaves `target/dist/lib` half-populated — which still STARTS, and serves the wrong
build. Packaging now takes a destination, so a build can be staged and verified while the live
server keeps serving, then swapped:

```sh
sbt -batch "app/distTo target/dist-next"
tools/promote-dist.sh          # refuses, and names the holding PID, if the server is up
```

An ARGUMENT, not an environment variable — that was the first attempt and it silently did
nothing. **sbt 2 keeps a background server alive between invocations, and the server has the
environment it was STARTED with.** `CAUSEWAY_DIST_DIR=... sbt app/dist` therefore rebuilt in
place, against the jars a running server held open. The same staleness bites the build
definition itself: after adding `distTo`, sbt reported `Not a valid key: distTo` until the
server was killed. When a build.sbt change appears not to have taken effect, kill the server
before looking for the bug.

The server holds JARS open too. `sbt app/compile` failed with `AccessDeniedException` moving
`causeway-graphstore*.jar` into place — nothing to do with the MCP server, which reads
`target/dist`; the sbt server itself had the module jar open from an earlier test run.

Verified on the staged build: **44 of 47 tools served**, with `jvm_coverage_run` and
`repo_checkout` present by name. The three unserved are all deliberate — `evidence_attest`
(granted to no agent by D22) and the two external reproducer tools.

---

### Two checkers, and why the second one had to be tuned

`tools/check-params.mjs` (inputs) and `tools/check-outputs.mjs` (responses) both exist for the
same reason: schemas were written before handlers, so nothing challenged a schema until an
implementation disagreed with it.

The output checker caught `repo_clone`'s new `refreshed` field before it shipped. Output is
validated with `additionalProperties: false`, so an undeclared field does not get ignored — the
whole call comes back as an output-validation ERROR, and only on the branch that emits it. The
first `refresh: true` call in production would have failed.

Its first version compared against TOP-LEVEL properties only and flagged 22 of 44 tools,
essentially all nested fields — noise that buries the one real finding. Two changes fixed it:
compare against every property the schema declares at any depth, and treat the names in
`_common.json` as declared everywhere, since shared shapes arrive by `$ref` and the checker does
not resolve those. Two tools stay flagged and are listed as benign with reasons: `graph_query`
returns free-form rows by design, and `export_run`'s field names go into the exported FILE
rather than the response.

A checker that cries wolf gets ignored, so tuning it was not optional.

### The evidence that vanished, and a checker that checked one module

Two failures on the same day, both of the shape this project keeps finding: a command reports
success while doing less than it claims.

**A server that persisted nothing.** `Main` called `Neo4jStore.connect` ONCE at startup. The host
rebooted, Docker began starting the Neo4j container, and the MCP client launched the server the
instant a session opened — before the database was healthy. One attempt failed, the process fell
back to `InMemoryEvidenceLedger` for its whole life, and `store = None` latched with no reconnect.

It is invisible from outside because notes go to the FILESYSTEM. The run looked alive: an
orchestrator note is on disk timestamped 12:37, and `MATCH (e:Evidence) WHERE e.at > '...T08'`
returns **0**. Hours of S7 coverage work, gone. The fallback is silent by construction, because
it is a legitimate mode on a machine with no database at all.

`connectWaiting` now retries — 10 attempts, 3s apart, logging each. Tests pin both directions:
three failing attempts produce two sleeps and an error naming the count, and a reachable database
returns on the first try with no delay, because waiting has to cost nothing in the normal case or
nobody keeps it.

**A suite that ran one module.** `sbt -batch "core/testOnly *" "mcpserver/testOnly *" ...` runs
the first command and prints one summary. Exit 0, one `Tests: succeeded 34` line, and it reads as
a full pass. Several "all tests pass" claims in this log were made on runs of that shape.

What made it slip through is worth recording: `core` and `mcpserver` BOTH report 34. The single
number looked like the module I expected, so nothing prompted a second look. Per-module counts
are the check; the exit code is not.

Real numbers, one invocation per module: core 34, mcpserver 34, vcs 42, forge 18 (+6 canceled
without a network), jvm 76, metrics 17, graphstore 35, app 45 — **301 passing, 0 failing.**

### Three call graphs, then the server stops answering

The orchestrator found this one and characterised it better than any guess I made. Its note:

> a causeway server process completes exactly 3 `jvm_callgraph_build` calls. The 4th hangs
> forever, and from that moment EVERY JVM-path tool in that process hangs forever too.

It ruled out the two obvious explanations by experiment rather than argument. Two sessions ran
the same first three builds and then DIFFERENT fourth revisions; both hung at position four. Then
the discriminating test: re-running the handle that had succeeded as build #3 hung when issued as
build #5. Position, not revision. It also retracted its own earlier note blaming concurrency,
having run everything strictly serially and seen no improvement.

Its suspected cause was the pool sized by `CAUSEWAY_BUILD_SLOTS`. That was wrong in an
instructive way: `CAUSEWAY_BUILD_SLOTS` is set in `.mcp.json` and **no Scala reads it**. The pool
it suspected does not exist. A plausible mechanism named after a real config key is exactly the
kind of hypothesis that survives without evidence.

The actual cause is unbounded retention. `Handles.callGraphs` was a plain `TrieMap` that never
evicted, and every `CallGraphHandle` retains a SootUp `JavaView` — which pins the whole JDK
runtime via `DefaultRuntimeAnalysisInputLocation` plus the project's classes, hundreds of
megabytes each, on top of a ~6500-node graph. The server ran with no `-Xmx`, so on a 19.8 GB host
the default cap is about 5 GB. Three fit. The fourth did not, and the JVM entered a collection
spiral that presents as a hang.

That accounts for the observation a lock cannot: cheap `jvm_method_at_line` calls hung too,
because each opens a full view of its own, while `graph_*`, `notes_*` and `repo_*` kept answering
because they barely allocate.

Three changes. `callGraphs` is an access-ordered LRU bounded at 2 — two because the pipeline
compares a parent against a fix, so a third live view means something is retained that nobody is
reading. An evicted id now returns "rebuild it", which is a different instruction from "no such
id": the agent's reasoning was sound and the bound moved underneath it. And both launchers plus
`.mcp.json` now pass `-Xmx3g -XX:+ExitOnOutOfMemoryError`, so exhaustion is a fast legible death
rather than an infinite silent stall.

**Still unexplained**, and recorded rather than waved away: the same `buildHandle` produced
6159/27172 nodes in one session and 6575/29142 in the next — 7% apart, same entry points, same
CHA algorithm. That is a separate defect from the wedge. Until it is understood, `nodeCount` and
`edgeCount` are approximate, and blast radius must not be compared across server processes.

### An aside on healthchecks

Docker reported `causeway-neo4j` as `health: starting` while the database was already accepting
bolt connections. Waiting on the healthcheck would therefore be STRICTER than the condition that
matters. This is why `connectWaiting` retries the connection itself rather than polling docker:
the only reliable test of "can I talk to the database" is talking to the database.

## Next

0. **Where this stands.** 44 of 47 tools served; 279 unit tests green (6 forge tests cancel
   without a network); the coverage path verified end to end against jsoup — clone, toolchain
   detection, containerised build, 2,035 tests under instrumentation, per-line coverage, and
   checkout. `sync-catalog --check`, `check-params --check` and `check-agents` all pass.
1. `repro_botsing` and `repro_evosuite_sweep` remain unwired. Both are EXTERNAL tools needing
   their own harnessing, so they are a project each rather than a handler each. `evidence_attest`
   is granted to no agent by D22 and is correctly unserved.
2. **Actually run the agents.** Their grants and procedures check out, but their JUDGEMENT is
   untested: whether an adjudicator reaches a sensible verdict, whether a synthesist can write
   a harness that compiles against a real project. That needs Claude Code restarted with
   `causeway` registered — see below.
3. `modules/llvm` — deferred by D5.
