# run_f7997c1a240cc238 — jhy/jsoup — final report (v2, 2026-08-26)

Supersedes the v1 report of 2026-08-25. v1 is retained at `run_f7997c1a240cc238_jsoup_final.json`
and `_REPORT.md`; this session's export is `run_f7997c1a240cc238_jsoup_final_v2.json`.

Window: 2026-02-23T00:00:00Z .. HEAD 19c758e2e4abc6a1f52193ceab09c22a7c010f8b (138 commits, not truncated).
Recipe: maven:3.9.9-eclipse-temurin-17, `mvn -B -q compile`.
Every figure traces to a tool response; evidence ids are in the export.

## Headline

**10 of 14 path steps are observed rather than static. 71.4%.**

v1 reported 0 of 14. The change is not a re-reading of the same data: five reproducers were
executed at their parent commits for the first time, and the executed method sets were
intersected with the recorded path hops.

A hop counts as OBSERVED only when BOTH endpoint methods appear in the executed set of the
failing run at the parent. This is METHOD-level evidence. JaCoCo records that a method ran, not
that a particular call edge was taken, so "observed" means both ends of the hop were executed by
the reproducing run — not that the edge itself was recorded.

| bug | steps | observed | why not all |
|---|---|---|---|
| 84ca201d W3CDom | 5 | 5 | — |
| 7c32b7e1 Cleaner | 6 | 4 | 2 hops incident to a synthetic accessor JaCoCo does not report |
| 823709f5 StreamParser/DataUtil | 1 | 1 | — |
| b6fabf85 NodeTraversor | 0 | 0 | zero-hop path: entry point IS the fault site |
| 49e58c20 SimpleBufferedInput | 2 | 0 | the reproducer exercises a different route than the one recorded |
| **total** | **14** | **10** | |

Two caveats on that 71.4%:

- **The 7c32b7e1 shortfall is an instrument limit, not a gap in the route.** Both unobserved hops
  are incident to `Cleaner#access$100`, a javac-generated synthetic bridge that JaCoCo does not
  report. The methods on either side of it both executed. Those hops are *unmeasurable*, not
  refuted. Counting them observed gives 12/14 = 85.7%; that number is defensible but it credits
  the trace with something it did not show, so the headline is the strict one.
- **b6fabf85 contributes 0 to both numerator and denominator.** Its path is genuinely zero-hop.
  It was not inflated to 1 to gain a numerator.

## Reproducer tiers — the change

| tier | v1 | v2 |
|---|---|---|
| T1_NATIVE | 0 | **5** |
| T2_SYNTHESIZED | 0 | 0 |
| T3_REACHED | 0 | 0 |
| T4_STATIC | 5 | 0 |

All five differ across the fix boundary: fail at parent, pass at fix.

| bug | selector | channel | parent symptom | trace |
|---|---|---|---|---|
| 84ca201d | W3CDomTest, 3 methods | ASSERTION | expected 4/8/7 node types, got 3/10/1 | tr_bd0969f99798ad76, 521 methods |
| 7c32b7e1 | CleanerTest, 3 methods | ASSERTION | `<a REL="external" rel="external">` | tr_788e70b4987b5b11, 609 |
| 823709f5 | DataUtilTest, 1 method | EXCEPTION | ValidationException: Object must not be null | tr_8088f187785b0243, 587 |
| b6fabf85 | TraversorTest, 1 method | ASSERTION | expected `div;i;two;` got `div;i;u;two;` | tr_755f056a19aed675, 487 |
| 49e58c20 | DataUtilTest, 1 method | EXCEPTION | java.io.IOException: Stream closed. | tr_830bada4e145b64b, 127 |

One selector choice worth recording: for 823709f5 the fix also adds two `ConnectTest` methods.
Those need the netty local webserver, and execution runs `--network none`, so `DataUtilTest` was
used instead.

**Rung 2 (Botsing) remains inapplicable repo-wide**, unchanged from v1: no admitted bug carries a
verbatim stack trace in its issue. Rung 3 was never reached and no agent was dispatched for
reproduction — rung 1 answered for all five, which is the ladder working as designed.

## The tool defect this exposed, and the fix

v1's "0% observed" was **not** a property of jsoup. It was a defect in `jvm_native_test_run`.

The first attempt on 84ca201d returned `failsAtParent: false, differs: false` — read naively,
"the project's own regression test does not capture its own bug". The parent side had printed
`Tests run: 0`. All three test methods are ADDED by the fix and do not exist at the parent.
Verified across all five: 0 of 3, 0 of 1, 0 of 4, 0 of 3, 0 of 1 methods present at each parent.

Two separate causes, both now fixed:

1. **The handler guard was too narrow.** `AnalysisHandlers.nativeTestRun` had
   `if !r.parent.ran && !r.fix.ran then Unknown(TestsFailed)`. The AND meant the guard fired only
   when NEITHER side ran. The added-test case is `parent.ran=false, fix.ran=true`, which fell
   through to the data branch and computed `failsAtParent = r.parent.ran && !r.parent.passed` =
   false. The comment directly above the guard described exactly the hazard the condition failed
   to catch. It is now `||`, and the detail names which side was silent. `NativeTestRunner` was
   never at fault: `ranAnyTest` correctly returns false on `Tests run: 0`.
2. **No test porting.** `differential` ran each revision's own worktree, so a fix that ADDS its
   regression test — the common shape, and the one the ladder is written around — could never
   produce T1. The fix's test sources are now overlaid onto the parent worktree for the duration
   of the parent run only; production code at the parent is untouched. Restore runs in a
   `finally`, because worktrees are cached and reused and a stray ported test would silently
   contaminate every later probe, coverage run and call graph at that revision. Verified clean
   after the run: no `.causeway-orig` files, zero ported methods remaining in the parent tree.

Schema updated first (`testsPorted`, `portedTestFiles`). `sync-catalog`, `check-outputs` and
`check-params` all pass; jvm 82 tests and app 54 tests pass, including a new test asserting that
port-then-restore leaves the tree byte-identical. Recorded as note_abaf54dee70775fc.

**The reusable lesson: `differs: false` from rung 1 means nothing unless the selector actually ran
at the PARENT.** The response exposes no `ran` flag, so before this fix the only tell was reading
`resultAtParent` for `Tests run: 0` — and that field is emitted only when the parent produced
failure detail, so in the worst case the false negative was undetectable from the response alone.

## What execution corrected in the paths

Two of the five paths did not survive contact with a real execution, and this is the most valuable
output of the re-run.

**49e58c20 — the recorded route is not the route that runs.** S11 recorded routeA:
`SimpleStreamReader#read` → inherited `FilterInputStream#available()` →
`SimpleBufferedInput#available()`. Neither endpoint executed. What executed was routeB, which S11
had already named as a second fault site at `SimpleBufferedInput.java` old line 83. That line,
`if (in.available() < 1) break;`, sits inside `private void fill()` (old lines 68–93), and
`fill()` ran. The throwing `available()` is the test's own anonymous `FilterInputStream`, called
inline from `fill()` — which is why no jsoup `available()` appears in the trace at all. The
reproducer enters at `DataUtil.parseInputStream`, not at `SimpleStreamReader#read`.
Execution did not refute the analysis; it **disambiguated** it. S11 offered two routes and could
not say which carried the defect. Scored strictly at 0 of 2, because routeA is the route recorded
as this bug's path.

**823709f5 — the entry→fault leg is now observed; the symptom surface moved.**
`DataUtil#streamParser` → `DataUtil#detectCharset` both executed, so that leg is OBSERVED rather
than merely statically possible. But `StreamParser#complete()`, recorded by S11 as the symptom
surface, did NOT execute; `StreamParser#parse` and `#close` did. The tier stays **DISCONNECTED**
and that verdict is strengthened, not weakened: the corrupting write is confirmed executed, and
the observing read is confirmed reached by a route carrying no call edge from it. Execution
cannot manufacture an edge that does not exist.

**b6fabf85 — v1 was wrong that this bug is untraceable.** v1 recorded it under
Unknown(NoDebugInfo), reasoning that an infinite loop yields no trace by nature. The regression
test fails by **assertion in 1.5 seconds** without looping: it captures the *revisit* that causes
the loop (`div;i;u;two;` — the replacement `u` visited in head) rather than a diverging input. A
trace exists. Tier OBSERVED, still zero-hop.

Also worth keeping: two sibling TraversorTest methods
(`visitsChildrenInsertedInHead`, `siblingInsertionsOnlyVisitFutureNodesDuringHead`) **pass at the
parent**. Post-fix that is a genuine negative rather than a vacuous one, because both sides
demonstrably ran. Not every test a fix ships discriminates.

Path tiers: **3 OBSERVED, 1 HYPOTHESIZED, 1 DISCONNECTED** (v1: 0 / 4 / 1).
Fidelity unchanged: 3 FULL, 2 PARTIAL, 0 UNIT.

## Call-graph ceiling — measured, and lifted

The prior ceiling (~3 `jvm_callgraph_build` per server process, then a crash) was real at the old
3g heap and is **gone at 6g**. Measured serially in one process: 4 build probes (4 distinct
handles, 80–84s) then 4 call graphs, **all four succeeded** — including the 3rd and 4th that
previously killed the process. The server stayed alive throughout. The live process was confirmed
running `-Xmx6g -XX:+ExitOnOutOfMemoryError`, which is both the new headroom and the old crash
mechanism.

| # | callGraphId | nodes / edges |
|---|---|---|
| 1 | cg_000000006fa945a2 | 6047 / 26310 |
| 2 | cg_00000000718e59f7 | 5854 / 26880 |
| 3 | cg_00000000229bf03c | 5888 / 27120 |
| 4 | cg_000000004c0a833f | 5891 / 27100 |

**The 2-entry LRU eviction remains and still governs strategy**, but its character has changed:
querying graph #1 after #4 returned a clean `Unknown(NotApplicable)` naming its own remedy, while
#3 stayed queryable. That is a citable non-answer costing one rebuild, not a process death losing
every handle. Build-then-extract is still the right pattern; a hard ceiling of 3 is not.
note_8ddb9e7cd4259034 superseded by note_22ea91ca98de8e8f.

**Not retested, deliberately: parallel `jvm_*` calls.** Everything above was serial to keep the
heap measurement clean. That half of the old note stands unrefuted.

Incidental: `build_58aa4b07d486600c` reproduced exactly for parent 598a895a, a third independent
confirmation that build handles are deterministic across server processes.

## Unchanged from v1

Carried forward as stated, not re-derived:

- Candidates: LEXICAL 96/138, STRUCTURAL 48/138, 104 matched by at least one non-control net,
  34 unmatched, 15 sampled by RANDOM_CONTROL.
- Adjudication: 40 candidates → 19 BUG_FIX / 20 NOT_BUG_FIX / 1 UNDECIDED, precision 47.5% across
  a mixed enriched-plus-control sample.
- **A single prefilter recall percentage still cannot honestly be stated.** Only 25 of 104 matched
  commits were adjudicated, on a sample enriched toward STRUCTURAL. The one confirmed miss
  (38ea7f89, found only by the control net) stands, as does the control yield of 1 in 15 from the
  unmatched stratum.
- Admission: 19 admitted, 0 rejected. Coverage of changed lines at parent 112/116 = 96.6%.
- **Ranking is still degenerate**: scored on `faultLineCoverage` only, 16 of 19 tie at 1.0. The
  deep-5 was selected on path tractability, not importance. That substitute criterion enriches
  toward bugs whose reporters wrote good bug reports, and it still does.
- Coverage remains a weak fault predictor: all five had their fault line COVERED at the parent and
  shipped broken anyway. The gap was in assertions, not reach — and the five new T1 reproducers
  are precisely the assertions that were missing.

## Unknown breakdown

| reason | count | what it means |
|---|---|---|
| NotApplicable | 3 | 49e58c20 routeA endpoints absent from the trace; 823709f5 symptom surface absent; unresolved blast radius |
| NoDebugInfo | 2 | `Cleaner#access$100` synthetic bridge not reported by JaCoCo; JDK-internal FilterInputStream hop outside instrumented class dirs |
| ToolUnavailable | 0 | **cleared** — the JVM tool path was healthy throughout this session |
| NoReproducer | 0 | **cleared** — all five deep bugs now execute at their parent |
| Truncated | 1 | still only 25 of 104 matched candidates adjudicated |

Per-dimension knownness across the 19 admitted bugs is unchanged: faultLineCoverage 19/19,
symptomDistance 4/19, blastRadius 2/19.

## What a follow-up run should do first

1. **Adjudicate a random subsample of the 104 matched commits.** This is now the largest hole in
   the dataset and the only one blocking a stated recall figure. It was correctly deferred while
   the headline was 0% observed; that reason is gone.
2. **Run the other 14 admitted bugs through rung 1.** The tool can now do what it always claimed
   to. All were adjudicated BUG_FIX and most ship regression tests, so the observed fraction
   should extend well beyond the deep 5 for roughly 5 minutes of container time each.
3. **Re-trace 49e58c20 against routeB** and record it as the primary route, now that execution has
   settled which of the two candidate routes carries the defect.
4. Spend call graphs freely but serially, extracting from each before building the next.

## Provenance note

The five REPRODUCER and five PATH findings recorded in this session were produced by the
orchestrator directly, not by dispatched subagents — rung 1 costs no agent, and the path
observations are set intersections over tool returns. `findings_record`'s `agent` field is a
closed enum with no orchestrator member, so those findings carry `reproducer-synthesist` and
`path-tracer` labels with the true producer stated inside each claim body.

Recording the five new PATH findings **overwrote** the v1 PATH findings, so the original tracers'
detailed hop narratives now live only in `run_f7997c1a240cc238_jsoup_final.json` (v1), retained on
disk. Findings went 68 → 73: five REPRODUCER added, five PATH replaced in place.
