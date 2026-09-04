# A worked example: mining JSON-java

This document walks one real repository through the entire Causeway pipeline. It shows the
commands that started and resumed the run, what each stage produced, the prompts given to
agents, the outputs those agents returned, and the artifacts that ended up in the graph.

## How to read this document

The run described here is real and it completed. It is `run_71e86d4ef19eeb85`, mining
[stleary/JSON-java](https://github.com/stleary/JSON-java), thirteen bugs admitted, all stages
`DONE`. Every figure, agent output and artifact quoted below was read back out of Neo4j, out of
`workspace/exports/run_71e86d4ef19eeb85/dataset/`, or out of the repository on disk.

Nothing in this document is invented. Where a stage produced a weak or degenerate result, the
weak result is what appears, because the whole system exists to make the difference between a
measured fact and an assumed one visible. This run's ranking stage produced exactly that kind of
weak result on its first pass, and the second half of this document is what fixing it, live,
against this exact data, actually looked like.

**This run happened in two ranking passes, and the movement between them is the clearest
demonstration in this document of what the design gets wrong when it is untested, and right once
it is.**

| pass | ranking dimensions Known | composite spread | deep set chosen by |
|---|---|---|---|
| single-stage (first run through this repo, `run_e0e31baadbf2444a`, 4 bugs) | coverage only | degenerate — most bugs tied at 1.0 | documented substitute: file diversity |
| single-stage rerun on this run (13 bugs) | coverage only | degenerate — 6 of 13 tied at 1.0 | documented substitute: file diversity |
| **two-stage, corrected** (S8a → S9 → S8b) | coverage, blast radius, symptom distance | **10 distinct values, 0.37 down to 0.0** | **the ranking itself, for the first time** |

The redesign is in [`.claude/commands/mine-select.md`](.claude/commands/mine-select.md). This
document is the evidence that motivated it and the record of testing it against real data.

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
9. [S7. Coverage and call graph](#s7-coverage-and-call-graph-real)
10. [S8. Ranking — degenerate, then fixed](#s8-ranking-real-degenerate-then-fixed)
11. [S9. Symptom](#s9-symptom-real)
12. [S10. Reproducer](#s10-reproducer-real)
13. [S11. Path](#s11-path-real)
14. [S12 and S13. Metrics and report](#s12-and-s13-metrics-and-report-real)
15. [The bug in full](#the-bug-in-full)
16. [What the system can answer](#what-the-system-can-answer)
17. [What the system cannot answer](#what-the-system-cannot-answer)

---

## 1. The invocation

```
/mine-init https://github.com/stleary/JSON-java
```

JSON-java is a good target for the same reasons the design's smoke-test criteria ask for: a
single Maven module, a widely used JSON/XML parsing library, roughly 1,900 commits at the time of
this run, no external services needed to build or test it, and an active issue tracker — several
of the bugs below were reported as CVEs with reproducer code attached, which is about as strong
as symptom evidence gets.

The run was driven phase by phase rather than through `/mine-repo`, because it was resumed and
re-ranked partway through:

```
/mine-init ...        → run_71e86d4ef19eeb85
/mine-adjudicate <runId>
/mine-admit <runId>
/mine-select <runId>          # FIRST PASS — single-stage, the degenerate result below
/mine-trace <runId>
/mine-finalize <runId>
  ... (this session)
/mine-select <runId>          # SECOND PASS — two-stage, after the redesign
/mine-trace <runId>           # only the 3 newly-selected bugs needed S10/S11
/export_dataset <runId>       # re-run, reflecting the corrected deep set
```

---

## S0. Ingest (REAL)

```json
{ "name": "repo_clone", "arguments": { "url": "https://github.com/stleary/JSON-java" } }
```

```json
{
  "ok": true,
  "data": {
    "repoHandle": "repo_8ccd74d5ce5983cb",
    "headSha": "4f859fdf3b5669894c5ed8a305ee5cd5f1fe2b7a",
    "headDate": "2026-08-24T17:22:44Z"
  }
}
```

`repo_detect_ecosystem` found `pom.xml` and `build.gradle` both present. Maven won — the recipe
below proves that choice rather than assuming it.

---

## S1. Environment (REAL)

`build-doctor` probed the repository and returned a `BuildRecipe`:

```
baseImage:    maven:3.9-eclipse-temurin-8
buildTool:    maven
buildCommand: mvn clean compile -D maven.compiler.source=8 -D maven.compiler.target=8
testCommand:  mvn test -D maven.compiler.source=8 -D maven.compiler.target=8
entryPoints:  the library's four public parsing constructors/factories
              (JSONObject, JSONArray, JSONTokener, XML.toJSONObject)
```

`eclipse-temurin:8-jdk` bare has no `mvn` on its own, which is why the base image names the Maven
variant explicitly rather than a bare JDK image — a detail the probe surfaced by failing once
first.

---

## S2. Candidates (REAL)

`mining-strategist` ran the nets over a 12-month window, capped at 60 commits (the window itself
held 110 commits — real truncation, not padding):

```
Window: since 2025-09-03, HEAD 4f859fdf, 110 commits in window, 60 scanned (cap), truncated=true.
Nets:   LEXICAL 21, STRUCTURAL 13, RANDOM_CONTROL 10.
Union:  25 net-matched (12 lexical-only, 4 structural-only, 9 matched by both).
K = 35 (25 net-matched + 10 control), batched into 3 waves of ≤15 for adjudication.
```

The strategist's own read on this: *"Both LEXICAL and STRUCTURAL nets produced healthy,
non-trivial yield with 9 commits matched by both. No widening indicated on this first pass...
STRUCTURAL contributed 4 commits LEXICAL missed, so it is earning its place rather than
duplicating lexical."*

The cap bound hard here too — the window held 110 commits and only 60 were ever scanned, which is
why the strategist recorded `truncated=true` rather than reporting the window as exhausted.

---

## S3. Bundling (REAL)

No agent. `forge_bundle_candidates` over the 35 candidates, batched to stay under GraphQL
complexity limits. Each returns message, author, files touched, lines added/deleted, CI
conclusion, and any linked issue.

---

## S4. Adjudication (REAL)

`bugfix-adjudicator`, three batches, one wave, control interleaved into every batch rather than
adjudicated separately.

```
35 candidates adjudicated. Verdicts: 13 BUG_FIX, 22 NOT_BUG_FIX, 0 UNDECIDED.
```

Several "fix" commits turned out, once the diff was actually read rather than the message taken
at face value, to be pure SonarQube lint cleanup or documentation updates, and were correctly
verdicted `NOT_BUG_FIX`. One admitted bug, `8cbb4d5b`, sits right at that boundary and is worth
naming precisely because it did NOT get filtered out: it began life as SonarQube-flagged
"reliability" cleanup (an `Iterator.next()` that didn't throw `NoSuchElementException` when
exhausted), with no independent bug report and no regression test. The adjudicator verdicted it
`BUG_FIX` at a comparatively low 0.65 confidence — real Iterator-contract behavior was wrong, even
though nothing in production ever demonstrably relied on it — rather than either forcing it into
a clean bug-fix narrative or discarding it. It is the run's lowest-confidence admitted bug for
exactly that reason, and every downstream stage reflects that: no linked issue, no reporter
narrative, a symptom confidence of 0.3.

---

## S5. Structure (REAL)

No agent. `history_diff` plus `szz_baseline` for all 13 confirmed fixes, deterministic.

The type discipline this stage exists to enforce shows up concretely in `995fb840` (forceList
array loss): its **old**-side fault lines are `XML.java:394` and `XML.java:454`, in the parent
revision `e635f4023811c80c02b38428ec58e42b397f820f`. Its **new**-side fix lines are
`XML.java:394` and `XML.java:454-457` — overlapping numbers that name different code, because one
set is parent coordinates and the other is fix coordinates. Three of the thirteen admitted bugs
(`510a03ac`, `e2cfb5a6`, `3dd0ec02`) are pure additions with **no old-side range at all** — the
fix adds code that has no pre-fix counterpart to point at. That is not a gap S5 failed to fill; it
is the correct, honest answer for a fix shaped that way, and it propagates cleanly through every
later stage that depends on an old-side fault site (see S8b below).

---

## S6. Build gate (REAL)

```
13/13 admitted, 0 rejected.
23 unique build probes (13 fix commits + 10 unique parent commits — several bugs share a parent),
every one succeeded on maven:3.9-eclipse-temurin-8.
```

Three bugs share parent `e635f4023811c80c02b38428ec58e42b397f820f` alone (`534ce3c4`, `995fb840`,
`9d14246b`), and two share `1795e8cf26229229e7ab6fe4aeab0f51ffecda5a` (`94854a1d`, `e2cfb5a6`) —
dense history in an actively maintained repository, the same property the jsoup run's build gate
observed.

---

## S7. Coverage and call graph (REAL)

Coverage of the changed lines was measured against the parent revision for all 13 admitted bugs.
8 of 13 had a measurable fraction; the other 5 correctly returned no signal rather than a
fabricated zero — for 3 of those, S5 already explained why (no old-side lines to measure at all),
and the other 2 hit lines JaCoCo could not attribute to a single test in isolation.

No call graph was built at this stage. That is the point S8's first pass got wrong, and the
subject of the next section.

---

## S8. Ranking (REAL, degenerate, then fixed)

### The first pass: degenerate, same as jsoup's

`rank_importance` ran over all 13 admitted bugs on `faultLineCoverage` alone — `blastRadius` and
`symptomDistance` were never Known, because no call graph had been built yet and the original
single-stage design computed both dimensions *before* any bug-specific work existed to root one
on.

```
8 of 13 bugs scored (measurable coverage), 5 tied at NO composite (Unknown, not zero).
Of the 8 scored: 534ce3c4, 8cbb4d5b, 995fb840, ab92bb90, c073157a, fce83c91 ALL tied at 1.0.
```

Six bugs indistinguishable at a perfect score, on the one dimension every bug that had any signal
at all happened to score identically on. This is the exact shape of degeneracy the jsoup run
documented in its own S8 — different repository, same root cause, same failure mode. It is what
motivated actually fixing it rather than documenting it a second time and moving on.

### Two design flaws, found by testing the fix against this exact data

The redesign put `blastRadius` and `symptomDistance` behind S9's real, bug-specific entry point,
on the theory that this alone would fix things. Live-testing it against these 13 bugs found two
more problems the prose redesign had not anticipated.

**Flaw 1: `blastRadius` still needs S9, not just symptomDistance.** The first draft kept
`blastRadius` in the pre-S9 stage, reasoning that "how many callers a method has" doesn't depend
on knowing where a symptom surfaced. Testing it on `94854a1d` (the `mustEscape` regression)
showed the opposite: a call graph rooted only at the library's generic parsing entry points
returned **zero callers** for `XML#mustEscape(int)`, even though the fix's whole point was that a
real caller — `XMLTokener#unescapeEntity` — needed to reach it and, at the time of this
regression's *introduction*, didn't. A graph rooted at the wrong place doesn't just miss the
symptom's own route to the fault; it can miss the fault method's callers entirely, silently. Both
dimensions moved into S8b, rooted at S9's entry point together.

**Flaw 2: the S8a→S9 superset cutoff landed inside a tie.** The rule was "take the top
`min(|F_b|, 10)`." With 5 of 13 bugs tied at *no* composite, a flat top-10 cut kept 2 of those 5
and dropped 3 — arbitrarily, by `rank_importance`'s own alphabetical SHA tiebreak, not by merit.
Two of the three dropped bugs (`94854a1d`, `e2cfb5a6`) were already known from earlier work on
this exact repository to have excellent OBSERVED paths. The fix: widen the superset to the whole
tie group when the cutoff falls inside one. Applied here, that widened S_c from 10 to all 13.

### S8b: the corrected result

For each of the 13 bugs, a call graph was built at `parent(f)` rooted at that bug's own S9
`entryPoint`. `jvm_callgraph_callers` on the fault-site method gave `blastRadius`;
`jvm_callgraph_paths` from the entry point to the fault site gave `symptomDistance`. Three bugs
(the pure additions from S5) have no old-side fault-site method to root a query on, so both
dimensions stayed Unknown for them — the same honest absence as their coverage, for the same
underlying reason.

```json
{ "name": "rank_importance", "arguments": { "runId": "run_71e86d4ef19eeb85", "bugs": [ /* 13 */ ] } }
```

```
rank  fixSha     composite  blastRadius  symptomDistance
1     534ce3c4   0.37       7 callers    6 hops
2     ab92bb90   0.30       5 callers    5 hops
3     c073157a   0.15       5 callers    2 hops
4     995fb840   0.13       3 callers    2 hops
5     94854a1d   0.12       2 callers    2 hops
6     fce83c91   0.06       1 caller     1 hop
7     94e34000   0.03       3 callers    0 hops (entry point IS the fault site)
8=9   8cbb4d5b,
      9d14246b   0.00       0 callers    0 hops
10    187706978  0.00       0 callers    Unknown — connectivity itself is the finding, see below
11-13 3dd0ec02,
      510a03ac,
      e2cfb5a6   —          Unknown      Unknown — pure additions, no old-side fault site
```

Ten distinct composite values where the first pass had six bugs tied at a flat 1.0. The two
genuine ties left (`8cbb4d5b`, `9d14246b`) are real: both are zero-caller, zero-hop bugs — one is
the SonarQube-only Iterator fix from S4, the other a `ClassCastException` in a rarely-called
overload family.

**`187706978` is the most interesting zero in this table, and it is not a graph-building error.**
Its fix adds a validation call from `XMLTokener#unescapeEntity` into `XML#mustEscape` — at the
*parent* revision, that call does not exist yet, because adding it is the fix. `blastRadius = 0`
at the parent is therefore the correct, informative answer: the bug existed *because* nothing
called the validator, not despite something calling it. The composite lands it at rank 10, tied
with the zero-signal bugs, for a genuinely different reason than they are — a distinction the
composite number alone cannot carry, which is exactly why `scoredOn` and the underlying dimension
values are persisted alongside it rather than the composite standing alone.

Persisted per bug via `graph_upsert_bug`, checkpointed under `S8_RANKING` with `key:
"final-two-stage-live-verify"` — the checkpoint's own `detail` field states this reasoning inline,
not just the numbers.

**The deep set is now the ranking itself**, top `min(13, 5)`: `534ce3c4`, `ab92bb90`, `c073157a`,
`995fb840`, `94854a1d`. Two of the five (`c073157a`, `94854a1d`) were already in the original
single-stage deep set, chosen there by the file-diversity substitute criterion. Three
(`534ce3c4`, `ab92bb90`, `995fb840`) were not, and had no reproducer or path work done for them
until the ranking correctly surfaced them.

---

## S9. Symptom (REAL)

Two representative outputs, chosen because they show the range: one with a maintainer-confirmed
CVE and reproducer code attached, one with nothing but a diff to read.

### `ab92bb90` — reporter-quoted PoC, maintainer-confirmed

```json
{
  "class": "PERFORMANCE_DEGRADATION",
  "secondaryClasses": ["SECURITY_EXPOSURE"],
  "description": "Reporter (waydeshi, issue #1063, CVE-2026-59171): 'parsing a JSON or XML
    document containing a very large numeric literal ... constructs a BigInteger or BigDecimal
    object from the raw string with no length limit ... a moderately sized request body can cause
    a server thread to block for several seconds.' PoC measured: 10,000 digits 17ms, 100,000
    digits 123ms, 500,000 digits 2,975ms, 1,000,000 digits 11,909ms. Maintainer stleary
    confirmed: 'the problem has been recreated and confirmed... this implementation will have a
    1000-char fixed limit for parsing big numbers.'",
  "entryPoint": {
    "fqcn": "org.json.JSONObject", "name": "stringToValue",
    "descriptor": "(Ljava/lang/String;)Ljava/lang/Object;"
  },
  "confidence": 0.88,
  "gaps": []
}
```

### `8cbb4d5b` — no report, no reproducer, worked from the diff alone

```json
{
  "class": "API_CONTRACT_VIOLATION",
  "description": "No independently reported bug or crash. Raised purely against SonarCloud
    RELIABILITY findings (java:S2272, 'Iterator.next() should throw NoSuchElementException').
    Reading the diff: XML's anonymous codePointIterator's next() gained
    'if (!hasNext()) { throw new NoSuchElementException(); }' at its top. No stack trace or
    verbatim error text anywhere in the PR thread.",
  "entryPoint": { "fqcn": "org.json.XML$1$1", "name": "next", "descriptor": "()Ljava/lang/Integer;" },
  "confidence": 0.3,
  "gaps": [
    { "reason": "NoLinkedIssue", "detail": "forge_pr_for_commit returned linkedIssues: []; the PR itself is the only forge record." },
    { "reason": "NoSymptom", "detail": "No user narrative, no stack trace, anywhere in the thread." },
    { "reason": "NoReproducer", "detail": "No regression test was added for this specific change." }
  ]
}
```

The confidence gap between these two — 0.88 against 0.3 — is not noise. It is the symptom
characterizer correctly reporting how much it actually had to work with, and it is legible later:
`8cbb4d5b` is one of the two zero-composite ties in S8b, and its S9 confidence explains part of
why nothing downstream ever gave it a stronger signal to rank on.

---

## S10. Reproducer (REAL)

Rung 1 (`jvm_native_test_run`) closed 4 of the 5 deep bugs outright — every one of them shipped a
regression test with the fix:

```
ab92bb90  T1_NATIVE  JSONObjectTest#testMaxNumberLength
                       fails at parent: BUILD FAILURE / assertion on Number vs String
                       passes at fix
995fb840  T1_NATIVE  XMLConfigurationTest#testForceListEmptyAndEmptyTagsMixed
                       fails at parent: ComparisonFailure
                       expected {"root":{"id":[1,2]}} got {"root":{"id":[2]}}
                       passes at fix
c073157a  T1_NATIVE  (from the original single-stage deep set, unchanged)
94854a1d  T1_NATIVE  (from the original single-stage deep set, unchanged)
```

### The one bug that walked the whole ladder, and a real gap it found

`534ce3c4` ships **no test at all** — `jvm_tests_for_change` returned `testFilesTouched: []`,
confirmed against the diff. Rung 1 was never eligible. Rung 2
(`repro_botsing`, search-based crash reproduction from a stack trace) should have been the next
try — the symptom carries two verbatim stack traces from the reporters. It turned out to be
**unregistered**: `schemas/tools/repro_botsing.json` exists and is fully specified, but no
implementation exists anywhere in `modules/`, so it is never served. Same story for
`repro_evosuite_sweep` (rung 4). Both are real, documented gaps, not something to route around
silently.

Rung 3 — `reproducer-synthesist`, budget 3 iterations — closed it in 2:

```
Iteration 1: harness defined main(String[]); the generated Runner.java calls Harness.run()
             directly. Compile failure, not a reasoning miss — recorded as a FIXTURE note so a
             later run doesn't spend a real iteration on the same mistake.
Iteration 2: XML.toJSONObject("<a>&#;</a>")
             parent e635f402...: throws unchecked StringIndexOutOfBoundsException
             fix    534ce3c4...: throws checked JSONException
             differs: true, differsOn: EXCEPTION → T3_REACHED
```

`T3_REACHED`, not `T1` or `T2` — the harness call shape matches the real public API exactly, but
nothing established that a production caller ever sends this specific malformed input. That
distinction carries forward into S11's fidelity, not its tier.

---

## S11. Path (REAL)

All five deep bugs traced. Tiers and fidelity:

| bug | tier | fidelity | why |
|---|---|---|---|
| `ab92bb90` | OBSERVED | FULL | T1 reproducer, all 7 hops confirmed executed |
| `995fb840` | OBSERVED | FULL | T1 reproducer, all 3 hops confirmed executed |
| `c073157a`, `94854a1d` | OBSERVED | FULL | from the original deep set, unchanged |
| `534ce3c4` | OBSERVED | **PARTIAL** | T3 reproducer — the route that ran is confirmed, but nothing shows a real caller sends this input |

### The route for `ab92bb90`

```
1. org.json.JSONObject.<init>(String)                          [public entry point]
2. org.json.JSONObject.<init>(String, JSONParserConfiguration)
3. org.json.JSONObject.<init>(JSONTokener, JSONParserConfiguration)
4. org.json.JSONObject.parseJSONObject(JSONTokener, JSONParserConfiguration, boolean)
5. org.json.JSONTokener.nextSimpleValue(char)
6. org.json.JSONObject.stringToValue(String)     ← fault site, old line 2703
7. org.json.JSONObject.stringToNumber(String)     ← fault carrier: unbounded `new BigInteger(val)`
```

Every hop's endpoints appear in the executed set of `tr_09d2aa0f9947e236`
(`allHopsExecuted: true`, `missingHops: []`). The tracer also flagged, honestly, that the hop from
the *test method itself* to `JSONObject.<init>(String)` is not graph-confirmed — the call graph
was rooted at library entry points, which by design excludes test-class call sites. Recorded as a
gap rather than silently assumed.

### A real instrumentation gap, found writing this document

Building the table above required reading `path_hops.csv` from the re-exported dataset, and three
of the five deep bugs' rows came back with hop counts but **empty method names** — the structured
per-hop data never made it into the export, even though the path itself is fully real, fully
evidenced, and correctly tiered in `findings.csv`. The cause: `export_dataset` reads each hop from
`claim.hops[].{from,to,relation,carrier,oldLines,executed,evidence}` — an exact shape that
[`path-tracer.md`](.claude/agents/path-tracer.md) never documented before this run, so agents were
free to invent reasonable-sounding field names of their own. Two older bugs in this same run
happen to have the right shape; three new ones do not. The agent definition now states the exact
contract. This document's own table above was assembled from the agents' evidenced prose reports,
not from the (currently incomplete) CSV — which is itself the point: the finding was real and
correctly persisted as a `Finding`, the *denormalized secondary artifact* was what silently lost
it, and those are different failure surfaces with different blast radii.

---

## S12 and S13. Metrics and report (REAL, partial)

`metrics_churn` ran for the five deep-set bugs (pure diff functions, cheap):

| bug | linesAdded | linesDeleted | filesTouched | testLocRatio |
|---|---|---|---|---|
| `534ce3c4` | 68 | 8 | 1 | 0.0 (no test — matches S10) |
| `ab92bb90` | 3825 | 3889 | 3 | 0.999 |
| `c073157a` | 22 | 4 | 2 | 0.69 |
| `995fb840` | 113 | 2 | 2 | 0.94 |
| `94854a1d` | 45 | 2 | 2 | 0.79 |

`ab92bb90`'s huge diffstat is real and not a red flag: `testLocRatio` near 1.0 means it's almost
entirely a rewrite of one test file's formatting bundled with the actual fix, confirmed by reading
the diff rather than assumed from the line count alone.

**`metrics_complexity_delta`, `metrics_coverage_impact`, and `metrics_bug_lifetime` were never run
for this run.** `/mine-finalize`'s S12 names all four; only churn happened. This is a real,
verified gap in how this run was actually executed — not a claim that the tools don't work, only
that this run's own history doesn't contain a call to them. Stated here rather than glossed over,
for the same reason every other gap in this document is.

**Headline**, from `export_dataset`'s own counts: 13 bugs admitted, 13/13 built at both revisions,
4 of 5 deep bugs at `T1_NATIVE`, all 5 traced `OBSERVED`, 4 of 5 at `FULL` path fidelity.

---

## The bug in full

Bringing one bug together end to end: `ab92bb90`, CVE-2026-59171.

**Identity.** `stleary/JSON-java`, fix `ab92bb9088a37f9e346cd1dda66f6a3c40a201e0`, parent
`c91d821dbef3d4386775f45a1fc2ee559aeb78e3`, issue #1063.

**Adjudication.** `BUG_FIX` at 0.7 — the commit message and merged PR title both explicitly state
"Fixes CVE-2026-59171."

**Symptom.** `PERFORMANCE_DEGRADATION`, secondary `SECURITY_EXPOSURE`, confidence 0.88. Reporter's
own PoC timings quoted verbatim (10k digits 17ms up to 1M digits 11,909ms), maintainer confirmed
and committed to a fix strategy in the same thread. Entry point `JSONObject#stringToValue(String)`.

**Ranking.** First pass: composite 1.0, tied with 5 others, no discriminating power. Second pass,
after the two-stage redesign: composite **0.30**, rank 2 of 13, `scoredOn: [blastRadius,
symptomDistance]` — blastRadius 5 real callers, symptomDistance 5 real hops, both measured against
a call graph rooted at this bug's own entry point.

**Reproducer.** `T1_NATIVE`. `JSONObjectTest#testMaxNumberLength`, fails at parent (assertion on
`Number` vs `String` typing), passes at fix. Trace `tr_09d2aa0f9947e236`, 43 executed methods.

**Path.** 7 hops, `OBSERVED`, fidelity `FULL`. `JSONObject(String)` down through two more
constructor overloads, `parseJSONObject`, `JSONTokener#nextSimpleValue`, to the fault site
`stringToValue` and its carrier `stringToNumber`, where an unguarded `new BigInteger(val)` on an
attacker-controlled digit string does the actual damage. Every hop's endpoints confirmed in the
executed trace.

**Fix.** A `string.length() <= 1000` guard immediately before the conversion, so an oversized
literal falls through to an unconverted `String` instead of paying for the construction.

---

## What the system can answer

### About ranking, specifically

- *Does the ranking actually discriminate?* Not on the first pass — six bugs tied at a flat 1.0,
  a specific instance of the same degeneracy the jsoup example independently found. On the second
  pass, ten distinct values.
- *Why does a call graph need to be rooted per-bug rather than built once generically?* Because a
  generically-rooted graph returns zero callers for a method that has real ones, silently, if the
  generic roots never happen to reach it — demonstrated concretely on `94854a1d`.
- *Is a superset cutoff safe to apply blindly?* No — demonstrated concretely: a flat top-10 cut
  landed inside a 5-way tie and would have dropped two bugs already known to have excellent
  traced paths, for no reason connected to their actual importance.

### About paths and reproducers

- *Does every fix that closes a CVE ship a test?* Not always — `534ce3c4` closes a real,
  reporter-demonstrated crash with zero shipped tests, and needed the full reproducer ladder,
  landing at the ladder's synthesized rung rather than its native one.
- *What does a synthesized reproducer's `PARTIAL` fidelity actually withhold?* Not that the route
  is wrong — it is confirmed executed. Only that no evidence shows a real caller sends this exact
  malformed input, versus a native test where the project's own authors already asserted that it
  should.

### About the evidence itself

- *What is this claim based on?* Every finding above cites the evidence identifiers of the tool
  calls that produced it — 72+ across the deep set alone.
- *What could not be determined, and why?* 3 of 13 bugs have Unknown blast radius and symptom
  distance, for a stated, structural reason (no old-side fault-site method exists), not a budget
  or tooling failure.

---

## What the system cannot answer

**A ranking is only as good as what has been measured when it runs.** The first pass here was not
wrong reasoning applied to good data — it was correct reasoning applied to a dataset that hadn't
yet been given the dimensions it needed. The fix was in the *order* stages run in, not in the
formula.

**Every gap this document names is a gap in this specific run, not a limitation claimed for the
tool.** `metrics_complexity_delta`, `metrics_coverage_impact`, and `metrics_bug_lifetime` exist
and are implemented; they simply were never called this run. `repro_botsing` and
`repro_evosuite_sweep` are the opposite: specified and referenced, but genuinely unimplemented.
Those are different kinds of absence, and this document tries not to blur them.

**The path-tracer's structured output can be right in the finding and still lose data in the
export.** `path_hops.csv` is a denormalized secondary view; `findings.csv` is closer to the
ledger. A gap in the secondary view is real and worth fixing, but it is not the same claim as "the
path was never traced" — three of this run's deep bugs are proof of that distinction.

**Bugs whose fix is a pure addition have no old-side fault-site method by construction**, and
therefore no blast radius or symptom distance a call graph can measure — `510a03ac`, `e2cfb5a6`,
and `3dd0ec02` in this run. That is not a tooling gap to close; it is what "the fault lives in
old-side lines" (design §10) structurally cannot express for code that did not exist yet.
