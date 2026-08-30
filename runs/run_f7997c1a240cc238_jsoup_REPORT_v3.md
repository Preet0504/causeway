# run_f7997c1a240cc238 — jhy/jsoup — final report (v3, 2026-08-26)

Supersedes v2 (same day) and v1 (2026-08-25). Both are retained on disk.
Export: `run_f7997c1a240cc238_jsoup_final_v3.json` (19 bugs, 98 findings).

Window: 2026-02-23T00:00:00Z .. HEAD 19c758e2e4abc6a1f52193ceab09c22a7c010f8b (138 commits, not truncated).
Recipe: maven:3.9.9-eclipse-temurin-17, `mvn -B -q compile`.

## Headline

**60 of 68 path steps are observed rather than static. 88.2%.**

Of the steps the instrument is *capable* of reporting, **60 of 62 — 96.8%**.
**Zero steps are unobserved.** Every step not counted as observed is unmeasurable for a
structural reason given below, not a route that failed to run.

| | v1 | v2 | v3 |
|---|---|---|---|
| observed / total steps | 0 / 14 | 10 / 14 | **60 / 68** |
| strict observed fraction | 0% | 71.4% | **88.2%** |
| bugs at T1_NATIVE | 0 | 5 | **16 of 19** |
| bugs with a traced path | 5 | 5 | **16 of 19** |

A step counts as OBSERVED only when BOTH endpoint methods appear in the executed method set of
the failing run at the parent. This is method-level evidence: JaCoCo records that a method ran,
not that a particular call edge was taken.

## Reproducers: 16 of 19 at T1_NATIVE

All 19 admitted bugs were put through rung 1. Every one of the 16 conversions fails at its parent
and passes at its fix.

| bug | selector | channel | parent symptom |
|---|---|---|---|
| 84ca201d | W3CDomTest, 3 | ASSERTION | expected 4/8/7 node types, got 3/10/1 |
| 7c32b7e1 | CleanerTest, 3 | ASSERTION | `<a REL="external" rel="external">` |
| 823709f5 | DataUtilTest, 1 | EXCEPTION | ValidationException: Object must not be null |
| b6fabf85 | TraversorTest, 1 | ASSERTION | `div;i;two;` → `div;i;u;two;` |
| 49e58c20 | DataUtilTest, 1 | EXCEPTION | IOException: Stream closed. |
| 3475afc4 | SelectorTest, 1 | EXCEPTION | ClassCastException: HashMap → String |
| 1fb2c97f | HttpConnectionTest#inputStream | ASSERTION | expected ValidationException, none thrown |
| 5ee2e11a | W3CDomTest, 1 | EXCEPTION | NullPointerException |
| 89d31b4c | HttpConnectionTest, 2 | ASSERTION | 3/3 fail — raw CRLF in multipart field name |
| 92f1aca5 | HtmlParserTest, 1 | ASSERTION | control-char tag → empty output |
| bd345ac5 | XmlTreeBuilderTest, 3 | ASSERTION | max depth 2147483647, expected 512 |
| cad054a6 | HtmlParserTest, 2 | EXCEPTION | "Bug: no template insertion mode on stack!" |
| e87be9db | NodeTest, 6 | ASSERTION | 5/6 — cycle guard absent |
| f10c02e1 | EntitiesTest, 2 | ASSERTION | supplementary chars emitted raw |
| fbdd177b | CharacterReaderTest, 1 | ASSERTION | expected true, was false |
| 9d2241ff | ConnectTest, 3 | ASSERTION | 5 run, 3 fail + 2 error on mime types |

### The 3 non-conversions, and why they are results rather than gaps

Each is recorded on its Bug node as `reproducerTier: T4_STATIC`, never left null, so
"not eligible" and "not yet attempted" cannot be confused.

**38ea7f89 — ships no test at all.** `jvm_tests_for_change` returns `testFilesTouched: []`,
`testLocRatio: 0.0`. Rung 1 needs a project-authored test to port. Not attempted, because
eligibility was determined before spending a container run.

**4e381147 and b035b623 — the regression test exercises an API the fix introduced.**
`HttpConnection.redirectMethod` occurs 0 times at the parent and 2 at the fix;
`StringUtil.isHttpScheme` and `hasHttpScheme` are both 0 at the parent. The ported test cannot
compile against the parent's production code, so nothing runs there.

This is a **property of the ladder, not of jsoup**: rung 1 requires the ported test to COMPILE at
the parent, and where a fix adds both the repair and the API its test calls, the bug can never
reach T1. Recorded as note_267c5ec0182beec5 for the next repository.

It is amplified by porting being **file-granular**. On 4e381147 the behavioural `ConnectTest`
methods do not touch the new API and would have run, but `HttpConnectionTest.java` is ported
alongside and one uncompilable file fails the whole test compile, so no selector in any file can
run. Per-file porting scoped to the selector's class would likely recover 4e381147 and part of
b035b623. Not implemented.

All three surfaced as `Unknown(TestsFailed)` from the guard patched earlier in this run. Before
that patch they would have been three silent `differs: false` — the same false negative that
produced v1's 0%.

## The methodological finding: traces omit frames that threw

Two path-tracers independently reported statically unavoidable methods missing from their traces.
Rather than accept it, a controlled comparison settled it:

`org.jsoup.helper.W3CDom#fromJsoup(org.jsoup.nodes.Document)` is `return fromJsoup((Element) in);`
and exists identically at both relevant parents.
- In `tr_bd0969f99798ad76` (84ca201d, **assertion** channel): **present**.
- In `tr_c78a574b29d8aaab` (5ee2e11a, **NPE** unwinds through it): **absent**, while its own
  callee `fromJsoup(Element)` and the deeper `convert()` are present.

Same method, same body, same class. The only difference is whether an exception unwound through it.

**JaCoCo marks a method executed only when a probe fires. Probes sit at branch targets and at
method exit. A method entered and exited abnormally by a propagating exception, with no branch
before the throw point, fires nothing and is reported as not executed although it certainly ran.**
Straight-line one-statement methods — delegating overloads, default interface methods, small
helpers — are exactly that shape. Methods containing branches survive, because probes fire before
the exception occurs further down; that is why `convert()` appears and the one-line
`fromJsoup(Document)` does not.

Second instance, independently verified: `org.jsoup.nodes.Attributes#checkNotNull`, the frame that
actually **threw** the ClassCastException in 3475afc4, is absent from `tr_edff3850a86229d5` while
its caller `getIgnoreCase` is present.

**Consequence, and it is a bias not a nuisance:** these hops are UNMEASURABLE, not unobserved, and
the effect falls selectively on EXCEPTION-channel bugs — precisely the bugs whose propagation route
is most interesting. Scoring them as unobserved would understate the observed fraction exactly
where the dataset is most valuable. Recorded as note_0d737bf5d478c0c8; waves 2 and 3 were briefed
on it and applied it during tracing.

All 6 unmeasurable steps in this run come from two causes: 3 from exception-exit omission
(5ee2e11a), 1 from the same cause in 3475afc4, and 2 from javac synthetic accessor bridges
(`Cleaner#access$100` in 7c32b7e1), which JaCoCo also does not report.

## Paths, all 16

| bug | tier | fidelity | steps | observed | unmeasurable |
|---|---|---|---|---|---|
| 92f1aca5 | OBSERVED | FULL | 10 | 10 | 0 |
| cad054a6 | OBSERVED | FULL | 12 | 12 | 0 |
| 3475afc4 | OBSERVED | PARTIAL | 10 | 9 | 1 |
| 7c32b7e1 | OBSERVED | FULL | 6 | 4 | 2 |
| 5ee2e11a | OBSERVED | PARTIAL | 6 | 3 | 3 |
| 84ca201d | OBSERVED | FULL | 5 | 5 | 0 |
| 9d2241ff | OBSERVED | FULL | 5 | 5 | 0 |
| bd345ac5 | DISCONNECTED | PARTIAL | 5 | 5 | 0 |
| f10c02e1 | OBSERVED | FULL | 4 | 4 | 0 |
| e87be9db | OBSERVED | FULL | 2 | 2 | 0 |
| 49e58c20 | HYPOTHESIZED | PARTIAL | 2 | 0 | 0 |
| 823709f5 | DISCONNECTED | PARTIAL | 1 | 1 | 0 |
| 1fb2c97f | OBSERVED | FULL | 0 | 0 | 0 |
| 89d31b4c | OBSERVED | FULL | 0 | 0 | 0 |
| b6fabf85 | OBSERVED | FULL | 0 | 0 | 0 |
| fbdd177b | OBSERVED | UNIT | 0 | 0 | 0 |
| **total** | | | **68** | **60** | **6** |

Tiers: **13 OBSERVED, 2 DISCONNECTED, 1 HYPOTHESIZED**.
Fidelity: **10 FULL, 5 PARTIAL, 1 UNIT**.
**4 paths are genuinely zero-hop** — entry point and fault site are the same method. Recorded as 0
and never inflated to gain a numerator.

### Results worth keeping

**49e58c20 is the only path with observed steps of zero, and that is informative.** The recorded
routeA (`SimpleStreamReader#read` → inherited `FilterInputStream#available()` →
`SimpleBufferedInput#available()`) simply did not run: the reproducer enters at
`DataUtil.parseInputStream`. What ran is routeB, which S11 had already named as a second fault
site at `SimpleBufferedInput.java` old line 83, inside `private void fill()`. Execution did not
refute the analysis, it disambiguated it — S11 offered two routes and could not say which carried
the defect. Scored strictly at 0 of 2, because routeA is the route on record.

**Two bugs are DISCONNECTED for opposite reasons, and both survive execution.**
823709f5's corrupting write and observing read share no call edge; the entry→fault leg is now
observed, but `StreamParser#complete()` — S11's symptom surface — did not execute at all.
bd345ac5 is a configuration-default bug: both fault sites execute once at `Parser` construction,
writing `Integer.MAX_VALUE` into a field, and the S9 entry point is a downstream *consumer* of
that field with no edge back. All 5 of its hops are observed and it is still DISCONNECTED. A
route can be fully observed and still not be a call-graph path.

**Three S9 entry points were refuted by execution.** 3475afc4 reached the fault through
`Attributes#getIgnoreCase`, not `Attributes#get`. fbdd177b never touches `Jsoup.parse` at all —
the test constructs a `CharacterReader` directly, which is why it is the run's only UNIT fidelity
path. 89d31b4c's two symptoms have genuinely disjoint entry points, and the S9 entry applies to
only one of them. Every S9 symptom finding in this run carries confidence 0.50, and that number
now looks well calibrated.

**e87be9db and 1fb2c97f are absent-guard faults.** The fault is a check that does not exist, and a
missing call cannot appear in a trace by definition. Both are reported as such rather than forced
into an observed propagation route. 1fb2c97f is subtler: a validation call *did* execute, but
against the wrong local (`Validate.notNullParam(value, ...)` where the null argument was
`inputStream`), so it behaves as a missing guard while appearing in the trace.

## Unchanged, and still the honest limits

- Candidates: LEXICAL 96/138, STRUCTURAL 48/138, 104 matched, 34 unmatched, 15 control-sampled.
- Adjudication: 40 candidates → 19 BUG_FIX / 20 NOT_BUG_FIX / 1 UNDECIDED, precision 47.5% on a
  mixed enriched-plus-control sample.
- **Prefilter recall still cannot be stated as a single number.** Only 25 of 104 matched commits
  were adjudicated, on a sample enriched toward STRUCTURAL. The one confirmed miss (38ea7f89,
  found only by RANDOM_CONTROL) stands. This is now the largest hole in the dataset.
- Admission: 19 admitted, 0 rejected. Coverage of changed lines at parent 112/116 = 96.6%.
- **Ranking is still degenerate.** `importance` is now persisted for all 19 (it had silently
  dropped for 14 of them), but `scoredOn` is still `["faultLineCoverage"]` alone and 16 of 19 tie
  at 1.0. `blastRadius` and `symptomDistance` remain Unknown. The deep-5 was chosen on path
  tractability, not importance, and that substitute criterion still enriches toward bugs whose
  reporters wrote good reports.
- Coverage remains a weak fault predictor: every deep bug had its fault line COVERED at the parent
  and shipped broken anyway. The 16 new T1 reproducers are the assertions that were missing.

## Unknown breakdown

| reason | count | meaning |
|---|---|---|
| NoDebugInfo | 6 | 4 hops omitted because a method exited via a propagating exception; 2 synthetic accessor bridges |
| NotApplicable | 3 | 49e58c20 routeA endpoints absent; 823709f5 symptom surface absent; unresolved blast radius |
| TestsFailed | 2 | 4e381147, b035b623 — ported test cannot compile at the parent |
| ToolUnavailable | 1 | no buildHandle existed for most parents during Phase 2 (see below) |
| Truncated | 1 | 25 of 104 matched candidates adjudicated |
| NoReproducer | 0 | cleared |

## An agent-capability gap worth fixing

Every one of the 11 Phase 2 tracers reported the same limitation: **no `buildHandle` or
`callGraphId` existed for its parent, and `path-tracer` is not granted `jvm_build_probe` or
`jvm_callgraph_build`**, so `jvm_method_at_line`, `jvm_method_body`, `jvm_callgraph_paths` and
`jvm_trace_path_in` were all unusable. Every path in Phase 2 was therefore built from
`history_file_at` source reading, cross-checked against `jvm_trace_executed` — which needs no
handle and was the one JVM tool that always worked.

The results are sound, but the fidelity column is doing real work here: 5 PARTIAL ratings are
partial largely because edges were confirmed by reading the literal call statement rather than by
a retrieved call-graph edge. Either grant `path-tracer` a probe, or have the orchestrator build
and pass handles before dispatching. `tools/check-agents.mjs` verifies that granted tools are
served; it cannot detect a tool that is served, granted, and useless without a handle the agent
cannot obtain.

Two operational notes: parallel `jvm_trace_executed` is now demonstrably safe — three waves of up
to 4 concurrent agents, no instability — which refutes part of the old blanket warning against
parallel `jvm_*` calls, though concurrent `jvm_callgraph_build` was deliberately forbidden and
remains untested. And cad054a6's native run took 1652s against a 200–350s average; cause not
investigated.

## What a follow-up run should do first

1. **Adjudicate a random subsample of the 104 matched commits.** The only thing blocking a stated
   recall figure, and now the weakest part of the dataset.
2. **Build call graphs for the deep set to get `blastRadius` and `symptomDistance`.** The ~3-graph
   ceiling is gone at 6g (4 built serially this run), so ranking can stop being degenerate. Build
   one at a time and extract before building the next; expect eviction to return a clean
   `Unknown(NotApplicable)` on older handles.
3. **Give `path-tracer` a route to a buildHandle**, then re-trace the 5 PARTIAL paths to see how
   many become FULL.
4. **Implement selector-scoped test porting** and retry 4e381147 and b035b623.
5. Re-trace 49e58c20 against routeB and record it as the primary route.

## Provenance

Phase 1 (16 reproducers) and all persistence were done by the orchestrator directly; rung 1 costs
no agent. Phase 2 dispatched 11 `path-tracer` subagents in 3 waves of ≤4.

Three findings were re-recorded after the exception-probe discovery, because they had been scored
before the rule existed: 3475afc4 (1 hop unobserved → unmeasurable), 5ee2e11a (3 hops unobserved →
unmeasurable, tier HYPOTHESIZED → OBSERVED), and 7c32b7e1 (counts normalised to the three-way
scheme). Agent claims of missing methods were re-verified directly against the traces rather than
taken on trust; both checks confirmed the agents.

`findings_record` replaces by (subject, claimType), so each re-record overwrote its predecessor.
The v1 PATH findings' original hop narratives survive only in
`run_f7997c1a240cc238_jsoup_final.json`. The `agent` enum has no orchestrator member, so
orchestrator-produced findings carry `reproducer-synthesist` / `path-tracer` labels with the true
producer named inside each claim body.
