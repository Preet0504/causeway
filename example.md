# A worked example: mining jsoup

This document walks one real repository through the entire Causeway pipeline. It shows the
command that started the run, what each stage produced, the prompts that were given to agents,
the outputs those agents returned, and the artifacts that ended up in the graph.

## How to read this document

The run described here is real and it completed. It is `run_f7997c1a240cc238`, mining
[jhy/jsoup](https://github.com/jhy/jsoup), all fourteen stages `DONE`. Every figure, agent output
and artifact quoted below was read back out of Neo4j, out of the generated report, or out of the
repository on disk.

Nothing in this document is invented. Where a stage produced a weak or negative result, the weak
result is what appears, because the whole system exists to make the difference between a measured
fact and an assumed one visible. A worked example that quietly upgraded its own outcomes would be
a poor advertisement for it.

The run was executed in four passes, and the movement between them is the clearest demonstration
in this document of what execution buys over analysis.

| pass | observed path steps | bugs at T1 | what changed |
|---|---|---|---|
| v1 | 0 of 14, **0%** | 0 of 19 | rung 1 could not run at all |
| v2 | 10 of 14, **71.4%** | 5 of 19 | rung 1 repaired, deep 5 executed |
| v3 | 60 of 68, **88.2%** | 16 of 19 | rung 1 extended to every admitted bug |
| v4 | 65 of 69, **94.2%** | **18 of 19** | executed set widened to coverage union throw frames |

**Zero steps are unobserved.** Every step not counted as observed is unmeasurable for a stated
structural reason. Two of the five paths in the first executed pass did not survive contact with a
real run, and the pipeline said so rather than quietly keeping them.

---

## Table of contents

1. [The invocation](#1-the-invocation)
2. [S0. Ingest](#s0-ingest-real)
3. [S1. Environment](#s1-environment-real)
4. [S2. Candidates](#s2-candidates-real)
5. [S3. Bundling](#s3-bundling-real)
6. [S4. Adjudication](#s4-adjudication-real)
7. [S5. Structure](#s5-structure-real)
8. [S6. Build gate](#s6-build-gate-real)
9. [S7. Coverage and call graph](#s7-coverage-and-call-graph-real-partial)
10. [S8. Ranking](#s8-ranking-real)
11. [S9. Symptom](#s9-symptom-real)
12. [S10. Reproducer](#s10-reproducer-real)
13. [S11. Path](#s11-path-real)
14. [S12 and S13. Persistence and report](#s12-and-s13-persistence-and-report-real)
15. [The bug in full](#the-bug-in-full)
16. [What the system can answer](#what-the-system-can-answer)
17. [What the system cannot answer](#what-the-system-cannot-answer)

---

## 1. The invocation

```
/mine-repo https://github.com/jhy/jsoup --window 6m --cap 25 --deep 2
```

jsoup is a good first target. It is a widely used HTML parser, roughly 2500 commits, a single
Maven module, a large test suite, no external services needed to run it, and an active issue
tracker where bugs are described in user-visible terms.

The arguments say: look at six months of history, take at most 25 candidates forward, and spend
expensive deep analysis on the top 2.

---

## S0. Ingest (REAL)

Two deterministic tool calls, no agent.

**Call 1.**

```json
{ "name": "repo_clone", "arguments": { "url": "https://github.com/jhy/jsoup.git" } }
```

**Response.**

```json
{
  "ok": true,
  "evidenceId": "ev_928fe6851c6d5512f201da6e62e48569",
  "data": {
    "repoHandle": "repo_d914dc640c66633b",
    "localPath": "workspace/clones/repo_d914dc640c66633b",
    "defaultBranch": "master",
    "headSha": "19c758e2e4abc6a1f52193ceab09c22a7c010f8b",
    "commitCount": 2522
  }
}
```

**Call 2** is `repo_detect_ecosystem`, which finds `pom.xml` and classifies the repository as
`JVM`.

**Checkpoint written.**

```
S0_INGEST / repo_d914dc640c66633b / DONE
  Cloned jhy/jsoup, 2522 commits, head 19c758e2e4abc6a1f52193ceab09c22a7c010f8b,
  default branch master. Ecosystem JVM (pom.xml).
```

The clone is full rather than shallow, because SZZ and blame need complete history.

---

## S1. Environment (REAL)

The first agent dispatch.

### The agent's standing instruction

From [`.claude/agents/build-doctor.md`](.claude/agents/build-doctor.md):

> Your objective: produce a `BuildRecipe` that actually works, and if none does, say so with the
> evidence.
>
> ```
> BuildRecipe = {
>   baseImage, buildTool, buildCommand, testCommand, flags, entryPoints
> }
> ```

Its granted tools are `history_file_at`, `forge_ci_status`, `jvm_build_toolchain`,
`jvm_build_probe`, `findings_record`, and the three notes tools. It is one of only two consumers
of a class P tool, because it needs the probe feedback loop to prove a recipe rather than propose
one.

### The dispatch

```
Determine how jhy/jsoup builds at repoHandle repo_d914dc640c66633b.

Read the CI workflow and the build file, choose a base image, and PROVE the recipe by
probing it. Return a BuildRecipe with call-graph entry points. If nothing works, return
the failure classification with evidence.
```

### What the agent did

1. `notes_read` for `jhy/jsoup`, to pick up anything a previous run had learned.
2. `history_file_at` on `pom.xml`, finding `maven.compiler.source` and the profile structure.
3. `forge_ci_status`, to see which JDK the project's own CI uses.
4. `jvm_build_toolchain`, which reported `buildTool: maven`, `declaredSourceLevel: 8`.
5. `jvm_build_probe`, actually compiling in a container to confirm the choice.

The declared source level is 8, but the project's test tree contains code that only compiles on
JDK 11 and above, guarded by a Maven profile. The agent chose JDK 17 rather than following the
declared level literally, and then proved that choice by probing.

### The recorded result

```
S1_ENVIRONMENT / buildrecipe / DONE
  BuildRecipe verified by probe at HEAD. Image maven:3.9.9-eclipse-temurin-17, JDK 17,
  build 'mvn -B -q compile' (buildHandle build_ade63ebfb3a3f8dc, 49s), test 'mvn -B test'.
```

One `Finding` of type `Strategy` was recorded, citing the probe's evidence identifier. This is
the point of the feedback loop: the recipe is not the agent's opinion about how the project
builds, it is a claim backed by a build that actually succeeded.

---

## S2. Candidates (REAL)

### The agent's standing instruction

From [`.claude/agents/mining-strategist.md`](.claude/agents/mining-strategist.md):

> Your objective: decide WHERE in this repository's history there is minable signal, and WHETHER
> what has been harvested so far is sufficient. You do not analyse individual bugs.

### The dispatch

```
Set the mining window for jhy/jsoup (repoHandle repo_d914dc640c66633b).
Requested window 6 months, candidate cap 25.

Run the recall nets. The RANDOM_CONTROL net is mandatory. Return candidate set K with
per-net tags, and state what the cap costs in recall terms.
```

### What the agent returned

```
S2_CANDIDATES / window_6m / DONE
  Window 2026-02-23T00:00:00Z..HEAD, 138 commits, untruncated.
  Nets: LEXICAL 96, STRUCTURAL 48, union 104 (56 lex-only, 8 struct-only, 40 both),
  unmatched stratum 34.
  K=25 (8 struct-only + 17 both, chronologically spaced).
  RANDOM_CONTROL=15 drawn from the 34 unmatched (44% of stratum).
  Cap binding at 4.2x oversubscription; K non-random within matched stratum so recall
  point estimate is not supportable, only a bound on missed fixes.
```

Several things are worth drawing out here.

**The nets disagree, and that is informative.** 96 commits matched on message text alone, 48 on
diff shape, and only 40 matched both. The 56 lexical-only commits are largely dependency bumps and
release chores whose messages contain the word "fix". The 8 structural-only commits changed a
condition and a test without saying anything useful in the message.

**The cap binds hard.** 104 commits matched some net, and the cap allows 25. The agent chose to
prioritise the 8 structural-only candidates and then fill with commits matching both nets, spaced
chronologically rather than taken as a contiguous block.

**The agent declined to overclaim.** The last sentence is the important one. Because `K` was
selected non-randomly within the matched stratum, a precision figure computed on it does not
generalise to the stratum, and the agent said so instead of producing a recall estimate that
would have looked authoritative.

**The control net makes recall measurable.** 15 commits were drawn at random from the 34 that no
net matched. Nothing selected them, so whatever fraction of them turn out to be bug fixes is an
unbiased estimate of what the prefilter missed.

---

## S3. Bundling (REAL)

No agent. One deterministic tool, batched.

```json
{ "name": "forge_bundle_candidates",
  "arguments": { "repoHandle": "repo_d914dc640c66633b", "shas": [ "...40 shas..." ] } }
```

```
S3_BUNDLING / K25_plus_control15 / DONE
  Bundled 40 candidates (K=25, RANDOM_CONTROL=15) in 3 batched GraphQL round trips.
```

Three round trips for 40 commits. Each returns, per commit: the message, the author, the files
touched, lines added and deleted, whether source and test files were touched, the CI conclusion,
and any linked issue or pull request.

The batching is not a micro-optimisation. GraphQL complexity limits bite well before the useful
batch size does, so the chunk size is tuned to stay under them, and rate limiting is mapped to
`Unknown(RateLimited)` rather than raised as an exception.

---

## S4. Adjudication (REAL)

This is the first stage where judgement decides what enters the dataset.

### The agent's standing instruction

From [`.claude/agents/bugfix-adjudicator.md`](.claude/agents/bugfix-adjudicator.md):

> Your objective: for each candidate in your batch, decide `BUG_FIX`, `NOT_BUG_FIX`, or
> `UNDECIDED`, with evidence.
>
> A genuine bug fix corrects behaviour that was already wrong.

### The dispatch

Three batches, dispatched in a wave:

```
Adjudicate these 14 candidate commits from jhy/jsoup.

For each: decide BUG_FIX, NOT_BUG_FIX or UNDECIDED, and record the verdict with
findings_record citing the evidence you retrieved.

You may not assert anything you did not retrieve. UNDECIDED is a real answer; use it when
the evidence genuinely does not settle the question.

Candidates: 3475afc4, 84ca201d, f10c02e1, aea4a1b9, ...
```

Control candidates were **interleaved** into the same batches rather than adjudicated separately,
so the adjudicator could not treat them differently.

### Representative outputs

Negative verdicts, which are the majority:

| Commit | Confidence | Rationale |
|---|---|---|
| `aea4a1b9af15` | 0.98 | `Dependabot CI action version bump; touchesSource false.` |
| `b965b1e75853` | 0.98 | `maven-release-plugin version bump for next dev iteration; touchesSource false.` |
| `568b605d74bb` | 0.97 | `Changelog/release-date-only edit; touchesSource false.` |
| `d4a8941820c0` | 0.95 | `Test-only simplification ('doesn't need the buffer'); touchesSource false, no production behavior change.` |
| `6d8f80d65d51` | 0.95 | `GitHub funding configuration file only; touchesSource=false, touchesTests=false.` |

Positive verdicts, with the reasoning that separates them from refactors:

**`3475afc43aae`**, confidence 0.90:

```json
{
  "verdict": "BUG_FIX",
  "summary": "Old Attributes.getUserData-style path did `val == null ? EmptyString : (String) val`,
              which threw ClassCastException whenever an internal attribute held a non-String
              object. New code uses `val instanceof String ? (String) val : EmptyString`,
              avoiding the crash. Regression test added."
}
```

**`f10c02e12a15`**, confidence 0.90:

```json
{
  "verdict": "BUG_FIX",
  "summary": "Fixes incorrect low-16-bit codepoint check that could mistranslate supplementary
              characters (e.g. U+100A3 read as U+00A3) under non-UTF charsets, silently
              corrupting output. Message explicitly describes the prior wrong behaviour."
}
```

**`92f1aca55254`**, confidence 0.88:

```json
{
  "verdict": "BUG_FIX",
  "summary": "Tokenizer trimmed tag names, which could shift a name ending in a control character
              into matching a different element's parsing behaviour and expose raw-data content
              as markup on serialization."
}
```

The undecided verdict is instructive:

**`3c8350e2`**: `UNDECIDED`, on the grounds that the change is defence in depth with no cited
defect. The commit hardens a code path but neither the message nor any linked issue claims that
the unhardened path ever misbehaved. Forcing this into `BUG_FIX` would introduce a bug with no
symptom, and forcing it into `NOT_BUG_FIX` would discard a possible real defect. The honest
answer is that the evidence does not settle it.

### The stage result

```
S4_ADJUDICATION / batches_ABC / DONE
  40 candidates adjudicated in 3 blinded batches (K and control interleaved).
  K=25: 18 BUG_FIX, 1 UNDECIDED (3c8350e2 defense-in-depth, no cited defect), 6 NOT_BUG_FIX.
  Precision on K 18/25=72%, 18/24=75% excluding undecided.
  RANDOM_CONTROL=15: 1 BUG_FIX (38ea7f89 OutOfMemoryError missing from one of two symmetric
  catch blocks in Re2jRegex, no regression test), 14 NOT_BUG_FIX.
  Control fix rate 1/15=6.7%; scaled over the 34-commit unmatched stratum gives ~2.3 fixes
  missed by the prefilter.
  F=19 (18 from K + 1 from control).
```

The control net earned its cost immediately. One of the 15 randomly drawn commits, `38ea7f89`,
turned out to be a genuine bug fix that no net had matched: an `OutOfMemoryError` was missing from
one of two otherwise symmetric catch blocks, and the commit message did not describe it as a fix.
Scaling that 6.7% rate over the 34 unmatched commits estimates roughly 2.3 fixes the prefilter
missed in this window.

That number is the point of the control net. Without it, a precision of 72% would look like the
whole story, and the fixes the filter never saw would be invisible.

---

## S5. Structure (REAL)

No agent. Deterministic extraction of exact fault coordinates for all 19 confirmed fixes.

For the worked example commit `3475afc43aae`, `history_diff` returns:

```json
{
  "files": [
    {
      "file": "src/main/java/org/jsoup/nodes/Attributes.java",
      "status": "MODIFIED",
      "oldRanges": [
        { "side": "old", "start": 122, "end": 123 },
        { "side": "old", "start": 125, "end": 125 }
      ],
      "newRanges": [
        { "side": "new", "start": 122, "end": 124 },
        { "side": "new", "start": 126, "end": 126 }
      ],
      "deletedLines": [
        "    // we track boolean attributes as null in values - they're just keys. so returns empty for consumers",
        "    // casts to String, so only for non-internal attributes",
        "    static String checkNotNull(@Nullable Object val) {",
        "        return val == null ? EmptyString : (String) val;"
      ],
      "addedLines": [
        "    /**",
        "     Boolean attributes have null values, and internal attributes may hold arbitrary objects; return empty for either.",
        "     */",
        "    static String checkNotNull(@Nullable Object val) {",
        "        return val instanceof String ? (String) val : EmptyString;"
      ]
    },
    {
      "file": "src/test/java/org/jsoup/select/SelectorTest.java",
      "status": "MODIFIED",
      "newRange": { "side": "new", "start": 159, "end": 165 }
    }
  ],
  "stats": { "linesAdded": 11, "linesDeleted": 3, "filesTouched": 2, "hunkCount": 2 }
}
```

Note the range typing. `oldRange` carries `"side": "old"` and deserialises to `OldLines`. The
fault is at **old** line 125 in the parent revision:

```java
return val == null ? EmptyString : (String) val;
```

The fix at **new** line 126 is:

```java
return val instanceof String ? (String) val : EmptyString;
```

Asking the parent revision about new line 126 would return a different line entirely. The type
split is what prevents that.

### SZZ

```
S5_STRUCTURE_SZZ / szz_all_19 / DONE
  R-SZZ run for all 19 f in F. Every fix resolved to exactly one inducing commit at
  confidence 1.0 with filters ignore-whitespace, drop-cosmetic-lines, most-recent-only.
  Uniform 1.0 is the R-SZZ 'most-recent-only' reporting convention, not independent certainty.
  jvm_method_at_line deferred to after S6: it requires a buildHandle and the fault lives in
  parent coordinates, so resolving old-side lines against the HEAD build would mix coordinate
  systems.
```

Two observations the run recorded rather than glossed:

**The uniform confidence is a convention, not a measurement.** R-SZZ selects the most recent
inducing commit by construction, so it always reports a single answer at full confidence. Reading
those 1.0 values as independent certainty would be wrong, and the checkpoint says so.

**Coordinate discipline forced a reordering.** `jvm_method_at_line` needs a compiled tree to map
lines to methods, and the only build available at this point was HEAD. Resolving old-side line
125 against a HEAD build would have mixed coordinate systems and produced a confident, wrong
method name. The stage deferred that call until after the build gate produced a parent build.

---

## S6. Build gate (REAL)

The hard gate. Both revisions of every candidate bug must compile.

```
S6_BUILD_GATE / all / DONE
  19/19 BUG_FIX commits passed the hard build gate. 37 distinct revisions probed
  (19 fixes + 19 parents, minus 1 overlap: 3475afc4 is both a fix and the parent of 1fb2c97f).
  All succeeded on maven:3.9.9-eclipse-temurin-17 with `mvn -B -q compile`, 51-82s each.
  Zero admitted:false.
```

Each revision is checked out into its own worktree:

```
workspace/checkouts/repo_d914dc640c66633b_3475afc43aae/
workspace/checkouts/repo_d914dc640c66633b_be8c3756a1f2/
...
```

and each probe returns a build handle:

```json
{
  "ok": true,
  "evidenceId": "ev_...",
  "data": {
    "succeeded": true,
    "buildHandle": "build_bdfe2ee2e6db3574",
    "durationSec": 61,
    "classpath": ["workspace/checkouts/repo_d914dc640c66633b_be8c37562cff/target/classes"]
  }
}
```

That handle is the **parent** build, `be8c37562cff`, which is the revision coverage and the call
graph are measured against. The fix's own build carries a different handle. Keeping the two
straight is the same coordinate discipline that separates `OldLines` from `NewLines`.

The overlap noted in the checkpoint is a real property of dense history: `3475afc4` is both a fix
in its own right and the parent of another fix in the same window, so 19 bugs required 37 rather
than 38 distinct builds.

A 19 out of 19 pass rate is unusually good and reflects the window. Six months of recent history
on an actively maintained project resolves its dependencies cleanly. A window reaching back years
would produce `DependencyResolution` failures, and those would be recorded with
`admitted: false` rather than dropped.

---

## S7. Coverage and call graph (REAL, partial)

For each admitted bug, the project's own test suite is run at the parent revision under JaCoCo
instrumentation, and a static call graph is built over the same revision.

Coverage runs completed for all 19 parents, at roughly 150 to 230 seconds each. A representative
result:

```json
{
  "ok": true,
  "evidenceId": "ev_...",
  "data": {
    "reportId": "cov_000000006d4b2797",
    "engine": "jacoco",
    "durationSec": 298,
    "testsRun": 2035,
    "testsFailed": 0,
    "testsSkipped": 0
  }
}
```

Coverage of the faulty line is then queried with the **old-side** range against the **parent**
revision:

```json
{ "name": "jvm_coverage_lines",
  "arguments": {
    "reportId": "cov_000000006d4b2797",
    "file": "src/main/java/org/jsoup/nodes/Attributes.java",
    "range": { "side": "old", "start": 125, "end": 125 }
  } }
```

```json
{ "data": { "file": "src/main/java/org/jsoup/nodes/Attributes.java",
            "lines": [ { "line": 125, "status": "COVERED", "hitCount": 41293 } ] } }
```

The faulty line is executed 41293 times by the existing suite. That is a strong signal for this
bug: the code runs constantly, and the existing tests never triggered the fault, which is exactly
the reachability versus infection distinction. Every one of those 41293 executions reached the
fault. None of them infected state, because every value passed in was in fact a `String`.

Call graphs were built for 3 of the 19 parents before the stage stopped. A representative result:

```json
{ "data": { "callGraphId": "cg_000000001d26b901",
            "algorithm": "CHA", "nodeCount": 6575, "edgeCount": 29142,
            "entryPointCount": 12, "hasDebugInfo": true } }
```

`hasDebugInfo: true` matters. Without the line number table, mapping a line to a method returns
`Unknown(NoDebugInfo)`, and the path cannot be anchored to source coordinates at all.

---

## S8. Ranking (REAL)

`rank_importance` ran over all 19 admitted bugs. The result is degenerate, and the stage recorded
that rather than presenting a ranking it could not support.

```
S8_RANKING / DONE
  rank_importance over all 19 admitted bugs, ev_93009b4e6d0432c138b3cca6cf7320ac.
  Scored on faultLineCoverage ONLY; blastRadius and symptomDistance passed as absent/Unknown
  because both require call graphs.
  RESULT IS DEGENERATE: 16 of 19 tie at composite 1.0 and ranks 1-16 are an alphabetical SHA
  tiebreak, not merit. Only 3 bugs separate: b6fabf85 0.931 (27/29), 92f1aca5 0.750 (3/4),
  38ea7f89 0.500 (1/2).
  jsoup's own suite covers 112 of 116 executable changed lines, so faultLineCoverage has almost
  no discriminating power on this repo.
```

Per-dimension knownness across the 19 admitted bugs:

| dimension | Known |
|---|---|
| faultLineCoverage | 19 of 19 |
| symptomDistance | 4 of 19 |
| blastRadius | 2 of 19 |

Two things happened here that the design anticipated.

**The Unknown dimensions were not coerced to zero.** `blastRadius` and `symptomDistance` were
passed as absent and stayed `Unknown`. Had they been defaulted to 0.0, every bug would have
received a confident-looking composite built mostly from fabricated zeros, and the ranking would
have looked authoritative while being noise.

**`scoredOn` made the degeneracy visible.** Every bug records `scoredOn: ["faultLineCoverage"]`,
a single dimension. A reader who sees sixteen bugs tied at 1.0 alongside that array can tell
immediately that the composite is one number wearing a rank's clothing.

The consequence was that the deep-5 could not be taken as ranks 1 to 5, because ranks 1 to 16 are
an alphabetical tiebreak. The orchestrator substituted an explicit criterion and documented it:
path tractability, meaning a known entry point, the highest symptom confidence (0.75 to 0.90, the
top five of nineteen), a real linked issue with a reporter narrative, and subsystem diversity.

It also recorded the bias that substitution introduces: selecting on symptom confidence enriches
toward bugs reported by users who wrote good bug reports.

---

## S9. Symptom (REAL)

### The agent's standing instruction

From [`.claude/agents/symptom-characterizer.md`](.claude/agents/symptom-characterizer.md):

> Your objective: reconstruct the OBSERVABLE MANIFESTATION of the fault, and identify the
> **entry point** where it surfaced.
>
> You do not explain the cause. The path-tracer owns that. You answer: what went wrong, as seen
> from outside, and where was it seen?

### The dispatch

```
Characterize the symptom for these bugs in jhy/jsoup.

For each: what did an observer actually SEE, and through which public entry point did it
surface? Classify the symptom. Record gaps explicitly where the evidence is thin.

Do not explain the cause.

Bugs: 3475afc43aae, 84ca201d9f9e, ...
```

### Output for `84ca201d9f9e`

This bug has a linked issue, so the symptom is reported rather than inferred:

```json
{
  "class": "DATA_CORRUPTION_OR_LOSS",
  "secondaryClasses": ["INCORRECT_OUTPUT"],
  "description": "Reporter (issue #2572, 'W3CDom drops some XML node types'):
    'W3CDom.fromJsoup(...) currently drops XML processing instructions and comments outside
    the root element, and converts CDataNode instances to ordinary text nodes.'
    No verbatim stack trace or exception was reported; this is a silent, observable
    data-loss/mistranslation during DOM conversion, not a crash. Commit 84ca201d confirms via
    its message ('Preserve XML nodes in W3CDom conversion ... retains processing instructions,
    document comments, and CDATA sections') and closes #2572 ('Fixes #2572').",
  "entryPoint": {
    "fqcn": "org.jsoup.helper.W3CDom",
    "name": "fromJsoup",
    "descriptor": "(Lorg/jsoup/nodes/Document;)Lorg/w3c/dom/Document;",
    "sourceFile": "src/main/java/org/jsoup/helper/W3CDom.java"
  },
  "confidence": 0.75,
  "gaps": [
    { "reason": "NoReproducer",
      "detail": "No stack trace or exception text; the issue describes observed conversion
                 output, not a captured error." }
  ]
}
```

### Output for `3475afc43aae`

This bug has **no** linked issue, so the agent had to work from the code:

```json
{
  "class": "CRASH_UNCAUGHT_EXCEPTION",
  "description": "No linked issue and no PR found (forge_pr_for_commit: NotApplicable).
    Commit message: 'Handle non-string internal attribute values; test for internal attribute
    data'. Reading the parent-commit code (be8c3756) at the cited lines confirms the mechanism:
    Attributes.checkNotNull(Object val) unconditionally cast any non-null value to String,
    `return val == null ? EmptyString : (String) val;`, but the class's own userData() method
    stores a Map<String,Object> as an internal attribute slot value. Any read path
    (get/getIgnoreCase/attribute) that reached an internal attribute slot holding a non-String
    object (e.g. the internal user-data map) would throw ClassCastException. The fix changes the
    check to `return val instanceof String ? (String) val : EmptyString;` and adds the comment
    'internal attributes may hold arbitrary objects; return empty for either.'",
  "entryPoint": {
    "fqcn": "org.jsoup.nodes.Attributes",
    "name": "get",
    "descriptor": "(Ljava/lang/String;)Ljava/lang/String;",
    "sourceFile": "src/main/java/org/jsoup/nodes/Attributes.java"
  },
  "confidence": 0.65,
  "gaps": [
    { "reason": "NoLinkedIssue",
      "detail": "No GitHub issue reference in the commit message; forge_pr_for_commit returned
                 NotApplicable for this sha." },
    { "reason": "NoReproducer",
      "detail": "No reporter-observed stack trace; the ClassCastException path is inferred from
                 reading the parent-vs-fix diff of checkNotNull, not from an executed
                 reproduction." }
  ]
}
```

This output is worth studying, because it shows the discipline working under pressure.

The agent had thin evidence: no issue, no pull request, no stack trace. It did not invent a
reporter. It stated plainly that `forge_pr_for_commit` returned `NotApplicable`, read the parent
revision's source, and reconstructed the mechanism from the code it retrieved. It then lowered its
own confidence to 0.65 and recorded **two explicit gaps** naming exactly what it could not obtain.

The second gap is the one that matters most: it says the `ClassCastException` is *inferred from
reading the diff, not from an executed reproduction*. That single sentence tells any downstream
consumer that this symptom is a hypothesis about behaviour rather than an observation of it, and
it is precisely the claim that S10 exists to upgrade.

---

## S10. Reproducer (REAL)

The ladder is walked cheapest first. Rung 1 asks whether the fix shipped a regression test, and if
so runs it at both revisions. All five deep bugs are eligible: every fix ships test changes, with
`testLocRatio` between 0.40 and 0.77.

The first pass returned nothing at all, and the reason turned out to be a defect rather than a
property of the repository. Both passes are shown, because the difference between them is the
whole point of the rung.

### First pass: five T4_STATIC

```
S10_REPRODUCER / DONE
  RUNG 2 (repro_botsing) was eliminated globally before it was attempted: ZERO of the 19
  admitted bugs carried a verbatim stack trace in any issue, PR, or commit message. jsoup
  reports are reproduction-code-and-prose style. Botsing needs a stack trace, so the rung is
  inapplicable repo-wide, not merely unsuccessful.

  RUNG 1 is ELIGIBLE for all 5, but no test was run at any parent.
```

Result: five `T4_STATIC`, zero at T1, T2 or T3. Every path was therefore hypothesised, and the
headline came out at 0 percent observed.

### What was actually wrong

The first invocation of `jvm_native_test_run` on `84ca201d` returned `failsAtParent: false,
differs: false`. Read naively that says the project's own regression test does not capture its own
bug, which is close to absurd for a test the fixer wrote for exactly that purpose.

The parent side had printed `Tests run: 0`. All three test methods are **added by the fix** and do
not exist at the parent. Verified across all five bugs: 0 of 3, 0 of 1, 0 of 4, 0 of 3 and 0 of 1
test methods present at each parent.

Two separate causes:

**The handler guard was too narrow.** It read
`if !r.parent.ran && !r.fix.ran then Unknown(TestsFailed)`. The `&&` meant the guard fired only
when *neither* side ran. The added-test case is `parent.ran = false, fix.ran = true`, which fell
through to the data branch and computed `failsAtParent = r.parent.ran && !r.parent.passed`, which
is `false`. The guard is now `||` and its detail names which side was silent.

**No test porting.** The runner executed each revision's own worktree. A fix that adds its
regression test, which is the common shape and the one the ladder is written around, could
therefore never produce a T1. The fix's test sources are now overlaid onto the parent worktree for
the duration of the parent run only. Production code at the parent is untouched, which is what
keeps the differential honest: the test is new, the code under test is not.

Restore runs in a `finally`. Worktrees are cached under `workspace/checkouts` and reused by every
later build probe, coverage run and call graph at that revision, so a ported test left behind would
silently contaminate all of them. The tree was verified byte-identical afterwards.

The response now carries `testsPorted` and `portedTestFiles`, so a reader can tell which tests ran
where.

### After the repairs: eighteen T1_NATIVE

Rung 1 was first re-run over the deep 5, then extended to all 19 admitted bugs, then re-run once
more after two further repairs.

| tier | first pass | deep 5 | all 19 | final |
|---|---|---|---|---|
| T1_NATIVE | 0 | 5 | 16 | **18** |
| T2_SYNTHESIZED | 0 | 0 | 0 | 0 |
| T3_REACHED | 0 | 0 | 0 | 0 |
| T4_STATIC | 5 | 0 | 3 | **1** |

Two further defects surfaced during the extension, both in the porting mechanism.

**Porting was file-granular.** A fix whose test file contained one method calling an API the fix
introduced could not compile at the parent, and because the whole file was ported, no selector in
it could run. Scoping the port to the selector's own class recovered two bugs.

**The remaining T4 is correct.** `38ea7f89` ships no regression test at all, so rung 1 has nothing
to port. It is recorded as `T4_STATIC` rather than left null, so "not eligible" and "not yet
attempted" stay distinguishable. It is also the run's one confirmed prefilter miss, and the
symmetry is worth noting: the property that hides a commit from both recall nets, shipping no test
and citing no issue, is the same property that puts it below the ladder's floor.

All five differ across the fix boundary: fail at the parent, pass at the fix.

| bug | selector | channel | symptom at parent | trace |
|---|---|---|---|---|
| `84ca201d` | W3CDomTest, 3 methods | ASSERTION | expected 4/8/7 node types, got 3/10/1 | `tr_bd0969f99798ad76`, 521 methods |
| `7c32b7e1` | CleanerTest, 3 methods | ASSERTION | `<a REL="external" rel="external">` | `tr_788e70b4987b5b11`, 609 |
| `823709f5` | DataUtilTest, 1 method | EXCEPTION | `ValidationException: Object must not be null` | `tr_8088f187785b0243`, 587 |
| `b6fabf85` | TraversorTest, 1 method | ASSERTION | expected `div;i;two;` got `div;i;u;two;` | `tr_755f056a19aed675`, 487 |
| `49e58c20` | DataUtilTest, 1 method | EXCEPTION | `java.io.IOException: Stream closed.` | `tr_830bada4e145b64b`, 127 |

Rung 3 was never reached and no agent was dispatched for reproduction. Rung 1 answered for all
five, which is the ladder working exactly as designed: the cheapest rung is also the highest
fidelity, because the reproducer is the one the person who fixed the bug wrote.

Two details the stage recorded that are worth keeping.

**A selector choice.** For `823709f5` the fix also adds two `ConnectTest` methods, but those need
a local webserver and harness execution runs with `--network none`, so `DataUtilTest` was used
instead.

**Not every shipped test discriminates.** Two sibling `TraversorTest` methods pass at the parent as
well as at the fix. Because both sides demonstrably ran, that is a genuine negative rather than a
vacuous one.

### The reusable lesson

`differs: false` from rung 1 means nothing unless the selector actually ran at the **parent**. The
response exposed no `ran` flag, so before the fix the only tell was reading `resultAtParent` for
`Tests run: 0`, and that field is emitted only when the parent produced failure detail. In the
worst case the false negative was undetectable from the response alone.

---
## S11. Path (REAL)

The deliverable. Five dispatches produced five path findings. The routes were traced first without execution, and
then re-scored against the five traces rung 1 produced.

Paths were eventually traced for all 18 bugs that reached T1.

| bug | tier | fidelity | steps | observed | unmeasurable |
|---|---|---|---|---|---|
| `cad054a6` | OBSERVED | FULL | 12 | 12 | 0 |
| `92f1aca5` | OBSERVED | FULL | 10 | 10 | 0 |
| `3475afc4` | OBSERVED | PARTIAL | 10 | 10 | 0 |
| `7c32b7e1` | OBSERVED | FULL | 6 | 4 | 2 |
| `5ee2e11a` | OBSERVED | PARTIAL | 6 | 6 | 0 |
| `84ca201d` | OBSERVED | FULL | 5 | 5 | 0 |
| `bd345ac5` | **DISCONNECTED** | PARTIAL | 5 | 5 | 0 |
| `9d2241ff` | OBSERVED | FULL | 5 | 5 | 0 |
| `f10c02e1` | OBSERVED | FULL | 4 | 4 | 0 |
| `e87be9db` | OBSERVED | FULL | 2 | 2 | 0 |
| `49e58c20` | **HYPOTHESIZED** | PARTIAL | 2 | **0** | 0 |
| `823709f5` | **DISCONNECTED** | PARTIAL | 1 | 1 | 0 |
| `b035b623` | OBSERVED | FULL | 1 | 1 | 0 |
| five zero-hop paths | OBSERVED | FULL or UNIT | 0 | 0 | 0 |
| **total** | | | **69** | **65** | **2** |

Final tiers: **15 OBSERVED, 2 DISCONNECTED, 1 HYPOTHESIZED**. Fidelity: 12 FULL, 5 PARTIAL,
1 UNIT.

Five paths are genuinely zero-hop, meaning the entry point and the fault site are the same method.
They are recorded as 0 and never inflated to gain a numerator, so they contribute to neither side
of the fraction.

A hop counts as OBSERVED only when **both** endpoint methods appear in the executed set of the
failing run at the parent. That is method-level evidence. JaCoCo records that a method ran, not
that a particular call edge was taken, so "observed" means both ends of the hop executed, not that
the edge itself was witnessed.

### The W3CDom path in full

This is the bug whose symptom was characterized in S9. The tracer returned a route of four hops
across five nodes, from the entry point the reporter named to the dispatch chain carrying the
fault.

```json
{
  "pathTier": "HYPOTHESIZED",
  "pathFidelity": "FULL",
  "resolution": "METHOD_LEVEL",
  "parent": "598a895abe717a18d9f8b2df27d8c19799e7ae64",
  "entryPoint": "org.jsoup.helper.W3CDom#fromJsoup(org.jsoup.nodes.Document)->org.w3c.dom.Document",
  "faultMethods": [
    "org.jsoup.helper.W3CDom#convert(org.jsoup.nodes.Element,org.w3c.dom.Document)->void",
    "org.jsoup.helper.W3CDom$W3CBuilder#head(org.jsoup.nodes.Node,int)->void"
  ],
  "hopCount": 4,
  "basis": "SOURCE_READ_AT_PARENT. No call graph was built for this bug and nothing was executed;
            every hop is a literal, unconditional call statement read verbatim from the parent
            revision, not a call-graph edge",
  "summary": "Three distinct node-type losses share one root cause: document-level structure is
              handled by ad-hoc special-casing in fromJsoup instead of by the traversal, so the
              traversal is seeded at the root ELEMENT and the visitor's instanceof dispatch chain
              has no arm for the XML-only node types."
}
```

The hops, condensed.

**Hop 0.** `fromJsoup(Document)` to `fromJsoup(Element)`, a `DIRECT_CALL` whose carrier is
recorded as `none, pure delegation`. The body is `return fromJsoup((Element) in);` with the
comment "just method API backcompat". The reporter's entry point and the fault-enclosing method
are one hop apart, and that hop carries no logic.

**Hop 1.** `fromJsoup(Element)` to `convert(Element, Document)`, old lines 205 and 208 to 216.
Carrier: an out-of-band doctype hoist plus overload resolution. The doctype is pulled from
`inDoc.documentType()` and appended to the output **before any traversal runs**, so document-level
nodes are reproduced by hand-written special cases rather than by the walk. The call site is
`convert(inDoc != null ? inDoc : in, out)`, and the ternary's least upper bound is `Element`, so
the `Element` overload is selected even when the runtime argument is the whole Document.

**Hop 2.** `convert` to `NodeVisitor#traverse`, old lines 259 to 261. This is the **primary
carrier**:

```java
Element rootEl = in instanceof Document ? in.firstElementChild() : in;
builder.traverse(rootEl);
```

`firstElementChild()` selects one child of the `#root` node and silently discards the rest of
`source.childNodes()`, which is every comment and XML declaration positioned before or after the
root element. The tracer classified this precisely:

> This is a REACHABILITY loss, not a dispatch loss: `W3CBuilder.head` already handled
> `org.jsoup.nodes.Comment` correctly, so a document-level comment would have converted fine had
> it ever been presented. It simply never arrives.

**Hop 3.** `traverse` to `W3CBuilder#head`, an `INTERFACE_CALLBACK`. The carrier is the traversal
root boundary contract, and the tracer cited the interface javadoc: "Traversal never advances
outside the original root subtree." That contract is what converts hop 2's narrowed root into
guaranteed loss. The discarded siblings are unreachable **by specification**, not by accident.

**Hop 4, TERMINAL.** The ordered `instanceof` dispatch chain, old lines roughly 366 to 405. Two
independent defects live in one chain:

- **CDATA flattening.** `CDataNode extends TextNode`, so a CDATA section inside the root is
  visited and matched, but by the `TextNode` arm that precedes any CDATA-specific test. Node type
  identity is destroyed with no exception and no diagnostic. A subtype captured by a superclass
  arm placed earlier in the chain.
- **Processing instruction drop.** `XmlDeclaration extends LeafNode`, so it matches no arm and
  reaches the terminal `else`, whose own comment reads "unhandled". The else was authored as a
  doctype carve-out and silently absorbed `XmlDeclaration` as collateral.

The tracer drew the consequence explicitly: processing instructions are lost by **two independent
mechanisms depending on position**, outside the root by hop 2 and inside the root by this else.

### What the tracer recorded about its own limits

This is the part worth reading closely, because it is where the discipline shows. Everything
quoted here was written **before** any reproducer existed, when the tracer had only source reads
to work from.

**On the tier:**

> HYPOTHESIZED, not OBSERVED. Nothing was executed at the parent this run, there is no trace, so
> Ex(f) does not exist and no hop can be described as something an execution actually did. Note
> this route is additionally weaker than a standard HYPOTHESIZED path: the tiering rule admits Pi
> subset of G(p(f)), but no call graph was built for this bug, so G(p(f)) does not exist either.
> The backing is textual source reading at the parent SHA. For hops 0 to 2 that is arguably
> STRONGER than a call-graph edge, they are unconditional, non-virtual, statically resolved call
> statements with no dispatch ambiguity. Hop 3 is the exception: it is an interface callback whose
> target I established from the default method body and javadoc rather than from a resolved
> virtual edge.

The agent identified that its path was weaker than its own tier normally implies, said so, and
then argued that three of the four hops were nonetheless better evidenced than a graph edge would
be. That is a more useful statement than either "observed" or "unknown".

Execution then settled it. The `W3CDomTest` reproducer ran at the parent, produced a 521-method
trace, and **all five hops had both endpoints in it**. The route the tracer assembled from reading
source turned out to be the route that runs, and the tier moved from HYPOTHESIZED to OBSERVED
without a single hop being revised.

**On fidelity, and why it is a separate number:**

> FULL. The route is unbroken from the public entry point the reporter named to the terminal
> dispatch chain that carries the fault; no hop is bridged by plausible reasoning and every hop
> cites a retrieved source read. Fidelity here answers "is the route complete", which it is; the
> "was it executed" question is carried entirely by the HYPOTHESIZED tier, and the two are
> deliberately not collapsed into one number.

**On what it could not rule out:**

> None enumerated. Without a call graph I cannot assert this is the only route from `fromJsoup` to
> the fault sites, I can only assert that this route exists and is direct. Recorded as a gap.

**On coverage:**

> All 11 executable changed old-side lines were COVERED at the parent. The pre-fix suite therefore
> reached the fault region and the defect shipped anyway: the gap was in assertions, not in reach.
> JaCoCo coverage is AGGREGATE over the whole test run, so COVERED means some test executed the
> line, never an identified one.

It also recorded a **latent coupling**: a changed line at old 436, `dest.getParentNode() instanceof
Element`, which dropped nothing on its own but silently encoded the invariant hop 2 established.
The tracer classified it as fault surface rather than fault carrier, and recorded it as coupled,
not as a cause.

### The disconnected path, which is a finding rather than a failure

`823709f5` returned **zero paths** from the symptom surface `StreamParser#complete()` to the fault
`DataUtil#detectCharset`, with `searchExhausted: false`. The tracer's explanation:

> The defect travels by object state: a sniffed stream closed in one method, observed as a
> null-buffer ValidationException in another. A static call graph structurally cannot express this
> route.

This is the most informative result in the run. The system asked for a path, the structure it had
could not contain one, and rather than assembling a plausible route it recorded `DISCONNECTED`
with the reason.

Execution **strengthened** that verdict rather than overturning it. The reproducer confirmed that
`DataUtil#streamParser` and `DataUtil#detectCharset` both execute, so the entry to fault leg is now
observed rather than merely statically possible. But `StreamParser#complete()`, which S9 recorded
as the symptom surface, did not execute at all; `StreamParser#parse` and `#close` did. The
corrupting write is confirmed to run, the observing read is confirmed to be reached by a route
carrying no call edge from it, and the tier stays DISCONNECTED. Execution cannot manufacture an
edge that does not exist.

An independent note from the same run reached the same conclusion from the other direction: jsoup
IO faults propagate through pooled-buffer object state, so call-graph path queries between the
parser and helper packages are a dead end by construction rather than by budget.

### The zero-hop path

`b6fabf85` recorded a path of length **0**: the entry point and the fault site are the same
method. It was recorded as zero and not inflated to one, so it contributes nothing to either side
of the observed fraction. Its tracer additionally matched `history_blame` against the reporter's
own text to corroborate the regression-introducing commit `967306fb`.

The first pass filed this bug under `Unknown(NoDebugInfo)`, reasoning that an infinite loop yields
no trace by nature. That reasoning was wrong, and execution showed why: the regression test fails
**by assertion in 1.5 seconds** without ever looping, because it captures the revisit that causes
the loop rather than a diverging input. A trace exists, and the tier is OBSERVED.

### What execution corrected

Two of the five paths did not survive contact with a real run, and this is the most valuable
output of the second pass.

**`49e58c20`: the recorded route is not the route that runs.** The tracer recorded route A,
`SimpleStreamReader#read` to an inherited `FilterInputStream#available()` to
`SimpleBufferedInput#available()`. Neither endpoint executed. What executed was route B, which the
tracer had already named as a second fault site: old line 83, `if (in.available() < 1) break;`,
inside `private void fill()`, and `fill()` ran. The throwing `available()` is the test's own
anonymous `FilterInputStream`, called inline from `fill()`, which is why no jsoup `available()`
appears in the trace at all. The reproducer enters at `DataUtil.parseInputStream`, not at
`SimpleStreamReader#read`.

Execution did not refute the analysis, it **disambiguated** it. The tracer had offered two routes
and could not say which carried the defect. It is scored strictly at 0 of 2, because route A is
the route recorded as this bug's path.

**`823709f5`: the symptom surface moved**, as described above.

Neither correction was available from reading source. Both required running the thing.

---

## S12 and S13. Persistence and report (REAL)

19 `Bug` nodes, 19 `Commit` nodes, 72 `Finding` nodes and 894 `Evidence` nodes were written. The
export is at `runs/run_f7997c1a240cc238_jsoup_final.json` and the report at
`runs/run_f7997c1a240cc238_jsoup_REPORT.md`.

The headline, quoted from the final report:

> **65 of 69 path steps are observed rather than static. 94.2%.**
>
> Of the steps the instrument can report at all: **65 of 67, 97.0%**. **Zero steps are
> unobserved.** Every step not counted as observed is unmeasurable, and both remaining cases have
> one named cause.

| measure | value |
|---|---|
| Window | 138 commits, not truncated |
| Adjudicated | 40 (25 non-control, 15 control) |
| Verdicts | 19 BUG_FIX, 20 NOT_BUG_FIX, 1 UNDECIDED |
| Adjudication precision | 19 of 40, 47.5% |
| Admitted at build gate | 19 of 19, zero rejected |
| Coverage of changed lines | 112 of 116 executable, 96.6% |
| Reproducer tiers | **18 T1_NATIVE**, 1 T4_STATIC |
| Path tiers | **15 OBSERVED**, 2 DISCONNECTED, 1 HYPOTHESIZED |
| Path fidelity | 12 FULL, 5 PARTIAL, 1 UNIT |
| **Observed path steps** | **65 of 69, 94.2%** |

### The instrument's blind spot, found by controlled comparison

Several tracers reported statically unavoidable methods missing from their traces. Rather than
accept it, the run settled the question with an experiment.

`W3CDom#fromJsoup(Document)` is a single delegating statement and exists identically at two
different parents. In an ASSERTION-channel trace it is **present**. In an EXCEPTION-channel trace
where a `NullPointerException` unwound through it, it is **absent**, while its own callee and the
method below that are both present.

Same method, same body, same class. The only difference is whether an exception unwound through
it.

JaCoCo marks a method executed only when a probe fires, and probes sit at branch targets and at
method exit. A method entered and exited abnormally by a propagating exception, with no branch
before the throw point, fires nothing and is reported as not executed although it certainly ran.
Straight-line one-statement methods are exactly that shape, which is why a method containing
branches survives and a delegating overload does not.

The bias is selective rather than uniform: it falls on EXCEPTION-channel bugs, which are precisely
the ones whose propagation route is most interesting. Scoring those hops as unobserved would
understate the observed fraction exactly where the dataset is most valuable.

The design already anticipated this. The executed set is specified as coverage **union** the
throw's stack-trace frames, and the harness runner implemented it. The native test runner did not.
Adding the union recovered 4 of the 6 unmeasurable steps on evidence rather than by
reclassification, including `Attributes#checkNotNull`, the frame that actually threw the
`ClassCastException`.

The 2 that remain flank `Cleaner#access$100`, a javac-generated synthetic accessor bridge. JaCoCo
does not report synthetic bridges, and they reach a stack trace only if something throws while
they are on it. That bug is ASSERTION-channel, so nothing unwound and no frames existed to help.
A synthetic bridge on a non-throwing run is the shape that survives any amount of tooling.

### The call-graph ceiling, measured and lifted

The earlier constraint of roughly three `jvm_callgraph_build` calls per server process was real at
a 3 gigabyte heap and is gone at 6. Measured serially in one process: four build probes, then four
call graphs, **all four succeeded**, including the third and fourth that previously killed the
process.

| # | callGraphId | nodes / edges |
|---|---|---|
| 1 | `cg_000000006fa945a2` | 6047 / 26310 |
| 2 | `cg_00000000718e59f7` | 5854 / 26880 |
| 3 | `cg_00000000229bf03c` | 5888 / 27120 |
| 4 | `cg_000000004c0a833f` | 5891 / 27100 |

The two-entry eviction bound remains and still governs strategy, but its character changed:
querying graph 1 after graph 4 returned a clean `Unknown(NotApplicable)` naming its own remedy,
while graph 3 stayed queryable. A citable non-answer costing one rebuild is a different thing from
a process death that loses every handle.

Parallel JVM calls were deliberately not retested, to keep the heap measurement clean, so that
half of the earlier finding stands unrefuted.

### On recall, and what the report refused to state

The report re-ran the nets with `controlSampleSize 0` and found that 18 of the 19 confirmed bug
fixes appear in the matched set. The nineteenth, `38ea7f89`, was found only by the random control
net, and it is invisible to both nets by construction:

- it shipped **no regression test**, so STRUCTURAL, which requires both source and test changes,
  cannot match it
- it carries **no issue reference**, so LEXICAL, which on this repository is effectively a
  detector for a `#nnnn` reference, cannot match it

The report then declined to state a recall percentage:

> **A single recall percentage cannot honestly be stated.** It would need the bug-fix count of the
> matched stratum, and only 25 of 104 matched commits were adjudicated, using a sample deliberately
> enriched toward STRUCTURAL. Extrapolating 18/25 across 104 would inherit that bias.

It also bounded the claim it did make:

> Control yield: 1 genuine bug fix in 15 sampled from the 34-commit unmatched stratum, implying
> about 2.3 missed bug fixes there. With n=15 and one success the interval is very wide. Read this
> as proof the miss rate is non-zero, not as a count.

---

## The bug in full

Bringing one bug together, here is everything the run recorded about `84ca201d9f9e`.

**Identity.** `jhy/jsoup`, fix `84ca201d9f9ef4bc7ff281a38613a7bef9090984`, parent
`598a895abe717a18d9f8b2df27d8c19799e7ae64`, closing issue #2572, titled "W3CDom drops some XML
node types".

**Adjudication.** `BUG_FIX` at confidence 0.85, on the grounds that the issue is labelled a bug
and describes a concrete defect in conversion fidelity.

**Symptom.** `DATA_CORRUPTION_OR_LOSS`, secondary `INCORRECT_OUTPUT`, at confidence 0.75. The
reporter observed that `W3CDom.fromJsoup(...)` drops XML processing instructions and comments
outside the root element and converts CDATA nodes to ordinary text. No stack trace, because
nothing threw. Entry point `org.jsoup.helper.W3CDom#fromJsoup(Document)`. One gap recorded,
`NoReproducer`, because the issue describes observed output rather than a captured error.

**Build gate.** Both revisions compiled on `maven:3.9.9-eclipse-temurin-17`.

**Coverage.** All 11 executable changed old-side lines were `COVERED` at the parent. The suite
reached the fault region and the defect shipped anyway.

**Ranking.** Composite 1.0, `scoredOn: ["faultLineCoverage"]`, tied with fifteen others. Selected
into the deep five on documented path tractability rather than on rank.

**Reproducer.** `T1_NATIVE`. Three `W3CDomTest` methods, all added by the fix, ported onto the
parent worktree and run there. They fail at the parent on an assertion (expected 4, 8 and 7 node
types, got 3, 10 and 1) and pass at the fix. Trace `tr_bd0969f99798ad76`, 521 methods.

**Path.** 4 hops across 5 nodes, `OBSERVED`, fidelity `FULL`. All five hops had both endpoints in
the trace, so the route assembled from reading source turned out to be the route that runs.
Three distinct node-type losses traced to one root cause: document-level structure handled by
special-casing in `fromJsoup` rather than by the traversal, so the walk is seeded below document
level and the dispatch chain has no arm for XML-only node types.

**Provenance.** Every hop cites the evidence identifiers of the source reads that back it. The
path finding alone cites eleven distinct identifiers.

---

## What the system can answer

These are questions the completed run supports, answered with what it actually found.

### About paths

- *How far does a defect travel before someone notices?* Path lengths here were 0, 1, 2, 5 and 6
  hops. The zero-hop case is a defect whose entry point and fault site are the same method.
- *Does the route an analyst reconstructs match the route that runs?* Sometimes not. Of five
  paths, three were confirmed hop for hop, one was corrected outright, and one had its symptom
  surface moved. That ratio is itself a result, and it is only obtainable by executing.
- *Can every defect be expressed as a path at all?* No, and the run has a concrete instance.
  `823709f5` propagates through object state rather than call edges, and a static call graph
  structurally cannot express it. One of five deep bugs came back `DISCONNECTED`.
- *Where does a defect actually change behaviour?* The W3CDom path separates a reachability loss
  at hop 2, where nodes are never presented to the visitor, from two dispatch losses at hop 4,
  where nodes are presented and mishandled. One reported symptom, three distinct carriers.

### About testing

- *Which faults were covered but not caught?* All five deep bugs had their fault lines covered at
  the parent and shipped broken anyway. Across all 19 admitted bugs, 112 of 116 changed lines were
  covered, 96.6%. On this repository coverage is a weak fault predictor and the gap is in
  assertions rather than in reach. The five regression tests the fixes shipped are precisely the
  assertions that were missing, which is why running them at the parent works.
- *Does every test a fix ships discriminate?* No. Two sibling `TraversorTest` methods pass at the
  parent as well as at the fix, and because both sides demonstrably ran, that is a genuine
  negative rather than a vacuous one.
- *Which fixes ship regression tests?* All five deep bugs did, with `testLocRatio` between 0.40
  and 0.77. That is what makes them T1-eligible.

### About the mining process

- *What does the prefilter miss?* One confirmed miss in 15 control samples, and the reason is
  structural: `38ea7f89` shipped no test and referenced no issue, so neither net can see it by
  construction.
- *How discriminating is the ranking?* Not at all, on this repository. Sixteen of nineteen bugs
  tied at 1.0 because the only Known dimension was one on which jsoup scores uniformly well.

### About the evidence itself

- *What is this claim based on?* Traverse `SUPPORTED_BY` from any of the 72 findings into the 894
  evidence rows.
- *What could not be determined, and why?* The `Unknown` breakdown, 22 records across five
  reasons, each naming the call that failed to determine it.
- *Which dimensions were actually measured?* `faultLineCoverage` 19 of 19, `symptomDistance` 4 of
  19, `blastRadius` 2 of 19.

### An example query

```cypher
// The deep set, with what each score was actually computed over.
MATCH (b:Bug {repo: 'jhy/jsoup', admitted: true})
WHERE b.pathFidelity IS NOT NULL
RETURN b.fixSha, b.importance, b.scoredOn, b.reproducerTier, b.pathFidelity
ORDER BY b.importance DESC
```

---

## What the system cannot answer

The completed run makes the limits concrete rather than hypothetical.

**Edge-level claims.** The 94.2% is method-level evidence. JaCoCo records that a method ran, not
that a particular call edge was taken, so an observed hop means both of its endpoints executed
during the failing run, not that the edge between them was witnessed. Two hops in `7c32b7e1` are
unmeasurable rather than unobserved, because they flank a synthetic bridge the instrument does not
report at all and nothing threw on that run to put it in a stack trace.

**Anything about the bug that ships no test.** `38ea7f89` has no reproducer and no traced path,
because rung 1 needs a project-authored test to port and that commit has none.

**Defects that propagate through state rather than through calls.** Demonstrated by `823709f5`.
Call-graph reachability cannot express a route where a stream is closed in one method and observed
as a null buffer in another.

**Bugs with no stack trace, through the crash-reproducer rung.** Zero of 19 admitted bugs carried a
verbatim stack trace, so rung 2 is inapplicable to this repository rather than merely
unsuccessful.

**A single recall number.** Only 25 of 104 matched candidates were adjudicated, from a sample
deliberately enriched toward one net, so extrapolating would inherit that bias. The control net
supports only the claim that the miss rate is non-zero.

**Comparative importance on this repository.** The ranking is degenerate. Sixteen bugs tie, and
the deep five were selected on a documented substitute criterion that enriches toward bugs whose
reporters wrote good bug reports.

**Bugs never fixed, fixes spread across several commits, concurrency and timing defects, and
anything manifesting only through file or log side effects.** These are excluded by construction,
as described in the design.
