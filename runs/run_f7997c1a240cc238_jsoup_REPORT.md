# run_f7997c1a240cc238 — jhy/jsoup — final report

Window: 2026-02-23T00:00:00Z .. HEAD 19c758e2e4abc6a1f52193ceab09c22a7c010f8b (138 commits, not truncated).
Recipe: maven:3.9.9-eclipse-temurin-17, `mvn -B -q compile`. All stages DONE, 0 pending.
Every figure below traces to a tool response; evidence ids are in the export.

## Headline

**0 of 14 path steps were observed rather than static. 0%.**

Not one path step in this run is execution-backed. Nothing was executed at any parent commit,
so no reproducer exists and `jvm_trace_*` was never callable. This is the run's principal
weakness and it is not a rounding detail — it is the difference between a traced route and an
argued one.

## Candidates and adjudication

| net | matched |
|---|---|
| LEXICAL | 96 of 138 |
| STRUCTURAL | 48 of 138 |
| matched by at least one non-control net | 104 |
| matched by NO net (unmatched stratum) | 34 |
| RANDOM_CONTROL sampled from that stratum | 15 |

Adjudicated: **40** candidates (25 non-control + 15 control).

| verdict | n |
|---|---|
| BUG_FIX | 19 |
| NOT_BUG_FIX | 20 |
| UNDECIDED | 1 |

Adjudication precision **19/40 = 47.5%**. This mixes the enriched non-control sample with the
random control sample; it is not the precision of either net alone.

## Prefilter recall — measured, and the one confirmed miss

Re-ran the nets over the identical window with `controlSampleSize 0`. 18 of the 19 confirmed
bug fixes appear in the matched set. One does not:

**38ea7f89 (Re2jRegex, OOM not caught alongside StackOverflowError)** was found ONLY by the
random control net. It is invisible to both nets by construction:
- it shipped **no regression test**, so STRUCTURAL (needs touchesSource AND touchesTests) cannot match it;
- it carries **no issue reference**, so LEXICAL (on this repo, effectively a has-a-`#nnnn` detector) cannot match it.

Control yield: **1 genuine bug fix in 15 sampled** from the 34-commit unmatched stratum, implying
~2.3 missed bug fixes there. With n=15 and one success the interval is very wide — read this as
proof the miss rate is non-zero, not as a count.

**A single recall percentage cannot honestly be stated.** It would need the bug-fix count of the
matched stratum, and only 25 of 104 matched commits were adjudicated, using a sample deliberately
enriched toward STRUCTURAL. Extrapolating 18/25 across 104 would inherit that bias.

## Admission

19 admitted, 0 rejected at the build gate — every parent and fix compiled. `rejectionBreakdown`
is empty because nothing failed admission, not because failures were discarded.

Coverage of changed lines at parent: **112 of 116 executable lines COVERED (96.6%)**.

## Ranking — degenerate, and reported as such

`rank_importance` scored on `faultLineCoverage` ONLY. `blastRadius` and `symptomDistance` were
passed as absent and stayed Unknown; neither was coerced to 0.

**16 of 19 bugs tie at composite 1.0.** Ranks 1–16 are an alphabetical SHA tiebreak. Only three
separate: b6fabf85 (0.931), 92f1aca5 (0.750), 38ea7f89 (0.500). jsoup's suite covers essentially
every changed line, so this dimension has almost no discriminating power here.

The deep-5 was therefore NOT taken as ranks 1–5. It was selected on **path tractability** —
known entry point, highest symptom confidence (0.75–0.90, the top 5 of 19), a real linked issue
with a reporter narrative, and subsystem diversity. This is a documented substitute criterion,
not a measure of importance, and it enriches toward bugs reported by users who wrote good bug
reports.

## Reproducer tiers

| tier | n |
|---|---|
| T1_NATIVE | 0 |
| T2_SYNTHESIZED | 0 |
| T3_REACHED | 0 |
| T4_STATIC | 5 |

**Rung 2 (Botsing) was eliminated repo-wide before it was attempted**: zero of the 19 admitted
bugs carried a verbatim stack trace anywhere — jsoup reports are reproduction-code-and-prose.
Rung 2 is inapplicable here, not merely unsuccessful.

**All 5 deep bugs are T1-eligible** — every fix ships regression tests (testLocRatio 0.40–0.77).
None were run at the parent, so no T1 is claimed. **Each needs exactly one test run at its parent
to convert to T1_NATIVE.** That is the cheapest, highest-value work remaining.

## Paths

| bug | tier | fidelity | hops | graph-backed |
|---|---|---|---|---|
| 7c32b7e1 Cleaner | HYPOTHESIZED | FULL | 6 | yes |
| 823709f5 StreamParser/DataUtil | DISCONNECTED | PARTIAL | 1 (+disconnected leg) | yes |
| b6fabf85 NodeTraversor | HYPOTHESIZED | FULL | 0 | no (not needed) |
| 84ca201d W3CDom | HYPOTHESIZED | FULL | 5 | no |
| 49e58c20 SimpleBufferedInput | HYPOTHESIZED | PARTIAL | 2 | no |

Tiers: 4 HYPOTHESIZED, 1 DISCONNECTED, **0 OBSERVED**. Fidelity: 3 FULL, 2 PARTIAL, 0 UNIT.

Two results worth keeping:
- **823709f5 is DISCONNECTED and that is the finding, not a failure.** A path query from the
  symptom surface `StreamParser#complete()` to the fault `DataUtil#detectCharset` returned zero
  paths, searchExhausted false. The defect travels by object state — a sniffed stream closed in
  one method, observed as a null-buffer ValidationException in another. A static call graph
  structurally cannot express this route.
- **b6fabf85 is a zero-hop path**: entry point and fault site are the same method. Recorded as
  0 and not inflated. Its tracer also matched `history_blame` against the reporter's own text to
  corroborate the regression-introducing commit 967306fb.

Coverage proved a weak fault predictor on all five: every one had its fault line COVERED at the
parent and shipped broken anyway. The gap was in assertions, not reach.

## Unknown breakdown, in full

| reason | count | what it means |
|---|---|---|
| NotApplicable | 9 | mostly missing blast radius / unresolvable per-line coverage after handle loss |
| ToolUnavailable | 5 | no call graph for 3 of 5 deep bugs; JVM tool path unusable late in the run |
| NoReproducer | 5 | all five deep bugs — nothing executed at parent |
| NoDebugInfo | 2 | JDK-internal hop (FilterInputStream); infinite loop yields no trace by nature |
| Truncated | 1 | only 25 of 104 matched candidates adjudicated |

Per-dimension knownness across the 19 admitted bugs:
- faultLineCoverage: **19/19 Known**
- symptomDistance: **4/19 Known**
- blastRadius: **2/19 Known**

## What limited this run

`jvm_callgraph_build` destabilises the causeway server: it crashed the process outright three
times, and parallel JVM calls crash it too. Each crash loses every in-memory handle (repo, build,
callgraph, coverage report) while Neo4j state survives intact. The client respawns the server, so
crashes are recoverable, but the practical ceiling is ~2 call graphs per process.

Working pattern, recorded in note_8ddb9e7cd4259034: build a graph and extract what you need in the
very next calls, one at a time, then pass the RESULTS (whose evidence ids stay valid forever) to
agents rather than a handle that will be dead before they use it.

## What a follow-up run should do first

1. Run the five shipped regression tests at their parents. Converts 5 bugs from T4 to T1 and
   turns the headline from 0% observed into a real number. Cheapest win available.
2. Adjudicate a RANDOM subsample of the 104 matched commits, to make prefilter recall computable.
3. Only then spend call graphs, two per process, on bugs whose entry point and fault site
   genuinely differ.
