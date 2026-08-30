# run_f7997c1a240cc238 — jhy/jsoup — final report (v4, 2026-08-27)

Supersedes v3, v2 and v1, all retained on disk.
Export: `run_f7997c1a240cc238_jsoup_final_v4.json` (19 bugs, 100 findings).

Window: 2026-02-23T00:00:00Z .. HEAD 19c758e2e4abc6a1f52193ceab09c22a7c010f8b (138 commits, not truncated).
Recipe: maven:3.9.9-eclipse-temurin-17, `mvn -B -q compile`.

## Headline

**65 of 69 path steps are observed rather than static. 94.2%.**

Of the steps the instrument can report at all: **65 of 67 — 97.0%**.
**Zero steps are unobserved.** Every step not counted as observed is unmeasurable, and both
remaining cases have one named cause.

| | v1 | v2 | v3 | v4 |
|---|---|---|---|---|
| observed / total steps | 0 / 14 | 10 / 14 | 60 / 68 | **65 / 69** |
| strict fraction | 0% | 71.4% | 88.2% | **94.2%** |
| unmeasurable | — | 4 | 6 | **2** |
| bugs at T1_NATIVE | 0 | 5 | 16 of 19 | **18 of 19** |
| bugs with a traced path | 5 | 5 | 16 | **18 of 18 T1** |

A step counts as OBSERVED only when BOTH endpoint methods appear in the executed method set of
the failing run at the parent.

### What the D27 union recovered

v3 carried **6** unmeasurable steps. The union recovered **4 of the 6**:

| bug | v3 | v4 | recovered by |
|---|---|---|---|
| 5ee2e11a | 3 obs / 3 unmeas | **6 obs / 0** | `NodeVisitor#traverse` from frames |
| 3475afc4 | 9 obs / 1 unmeas | **10 obs / 0** | `Attributes#checkNotNull` from frames |
| 7c32b7e1 | 4 obs / 2 unmeas | 4 obs / **2 unmeas** | not recoverable — see below |

The remaining **2** are the two hops either side of `Cleaner#access$100`, a javac-generated
synthetic accessor bridge. JaCoCo does not report synthetic bridges, and they reach a stack trace
only if they happen to be on the stack when something throws — and 7c32b7e1 is an
ASSERTION-channel bug, so nothing unwound and no frames existed to help. This is the shape that
survives any amount of tooling: **a synthetic bridge on a non-throwing run.**

The remaining 4 steps not observed are 49e58c20's 2 (see below) plus these 2.

## Paths, all 18

| bug | tier | fidelity | steps | observed | unmeasurable |
|---|---|---|---|---|---|
| cad054a6 | OBSERVED | FULL | 12 | 12 | 0 |
| 92f1aca5 | OBSERVED | FULL | 10 | 10 | 0 |
| 3475afc4 | OBSERVED | PARTIAL | 10 | 10 | 0 |
| 7c32b7e1 | OBSERVED | FULL | 6 | 4 | 2 |
| 5ee2e11a | OBSERVED | PARTIAL | 6 | 6 | 0 |
| 84ca201d | OBSERVED | FULL | 5 | 5 | 0 |
| bd345ac5 | DISCONNECTED | PARTIAL | 5 | 5 | 0 |
| 9d2241ff | OBSERVED | FULL | 5 | 5 | 0 |
| f10c02e1 | OBSERVED | FULL | 4 | 4 | 0 |
| e87be9db | OBSERVED | FULL | 2 | 2 | 0 |
| 49e58c20 | HYPOTHESIZED | PARTIAL | 2 | 0 | 0 |
| 823709f5 | DISCONNECTED | PARTIAL | 1 | 1 | 0 |
| b035b623 | OBSERVED | FULL | 1 | 1 | 0 |
| 1fb2c97f | OBSERVED | FULL | 0 | 0 | 0 |
| 4e381147 | OBSERVED | FULL | 0 | 0 | 0 |
| 89d31b4c | OBSERVED | FULL | 0 | 0 | 0 |
| b6fabf85 | OBSERVED | FULL | 0 | 0 | 0 |
| fbdd177b | OBSERVED | UNIT | 0 | 0 | 0 |
| **total** | | | **69** | **65** | **2** |

Tiers: **15 OBSERVED, 2 DISCONNECTED, 1 HYPOTHESIZED**.
Fidelity: **12 FULL, 5 PARTIAL, 1 UNIT**.
**5 paths are genuinely zero-hop** — entry point and fault site are the same method. Recorded as 0
and never inflated to gain a numerator.

## The one bug that is not T1, stated plainly

**1 of 19 is T4_STATIC: 38ea7f89, because its fix ships no test.**

`jvm_tests_for_change` returns `testFilesTouched: []`, `testsAdded: []`, `testLocRatio: 0.0`
(ev_eb17051cc65a8be4b4f3c73d218efb39). Rung 1 ports a project-authored test onto the parent and
runs it there; where there is no test there is nothing to port. This is a property of that commit
and the ladder's floor — **not a failure of the run, the tool, or the bug**. It was never
attempted, because eligibility was settled before spending a container run, and it is recorded on
the Bug node as `reproducerTier: T4_STATIC` rather than left null, so "not eligible" and "not yet
attempted" stay distinguishable.

It is the same commit v1 identified as the run's one confirmed prefilter miss, found only by
RANDOM_CONTROL: invisible to STRUCTURAL because it ships no test, and to LEXICAL because it cites
no issue. The property that hides it from the nets is the property that puts it below rung 1.

## What changed since v3

**D27 was never implemented in `jvm_native_test_run`.** The executed set is specified as coverage
UNION the throw's stack-trace frames, precisely because a method whose call throws records no
JaCoCo probe. `jvm_harness_run` implemented it via `framesOf`; the native runner did not. It now
parses ordinary Java stack traces from build-tool output. Frame-derived entries are identifiable
by an empty descriptor, and the response reports `frameMethodCount` — which ranged from 4 to 74
(9d2241ff, deep `java.net` stacks).

**Selector-scoped porting recovered the two blocked bugs.** Porting every test file a fix touched
meant one uncompilable sibling failed the whole module's test compile. Porting is now scoped to
the selector's classes, with the rest reported in `portSkippedFiles`:
- **4e381147** — 9 run, 7 fail at parent on real RFC 9110 semantics (PUT→GET, POST→GET, HEAD→GET).
- **b035b623** — 3 run, 2 fail; hostless URLs preserved as `href="https:/foo/bar"` instead of dropped.

Both had been T4_STATIC for a reason that was an artefact of the porting strategy, not a property
of the fix.

**A second tool bug, found and fixed while fixing the first.** The union was first written as
`groupBy((fqcn, name))` over coverage ++ frames. D27's class-and-name rule governs matching
*across producers*; applying it to the *result* destroyed overload precision that coverage had.
`StringUtil#resolve(URL,String)` became unrepresentable beside `resolve(String,String)`, and a
path tracer duly scored the hop between them UNOBSERVED when the truth was that the trace could no
longer tell. The same collapse hid `HttpConnection$Response#execute`'s two overloads. Fixed by
keeping coverage entries verbatim and appending only frames whose (class, name) coverage lacked;
after the fix both overload pairs appear distinctly and both paths re-scored OBSERVED.

**One residual collision, stated rather than glossed.** A frame-derived method carries no
descriptor, so if coverage never saw it and it shares a name with a covered overload, it is not
appended and the two stay indistinguishable. 5ee2e11a's hop `fromJsoup(Document) →
fromJsoup(Element)` is exactly this: it scores observed under class-and-name matching, and the
overloads cannot be told apart. That is D27's stated tradeoff — demanding descriptors would empty
the intersection instead.

## The negative that survived, and why it matters

**49e58c20 stays at 0 of 2 observed, on evidence.** Re-run under the union, the trace still does
not contain `SimpleStreamReader#read` or `SimpleBufferedInput#available`. Those endpoints were
genuinely never executed: the reproducer enters at `DataUtil.parseInputStream`, and what ran is
routeB — line 83 inside `private void fill()`, which S11 had already named as a second fault site.

This is the strongest evidence that the union is doing its job rather than inflating the number. A
tool that "recovered" these would have been hiding a true negative. Execution did not refute the
analysis; it disambiguated it, choosing between two routes S11 could not separate.

**Two bugs remain DISCONNECTED and both survive execution.** 823709f5's corrupting write and
observing read share no call edge. bd345ac5 is a configuration-default bug whose fault sites
execute once at `Parser` construction, with the S9 entry point a downstream consumer of the field
and no edge back — all 5 hops observed, and still not a call-graph path.

## Unchanged, and still the honest limits

- Candidates: LEXICAL 96/138, STRUCTURAL 48/138, 104 matched, 34 unmatched, 15 control-sampled.
- Adjudication: 40 candidates → 19 BUG_FIX / 20 NOT_BUG_FIX / 1 UNDECIDED, precision 47.5%.
- **Prefilter recall still cannot be stated as a single number.** Only 25 of 104 matched commits
  were adjudicated, on a sample enriched toward STRUCTURAL. This is now the largest hole.
- Admission: 19 admitted, 0 rejected. Coverage of changed lines at parent 112/116 = 96.6%.
- **Ranking is still degenerate**: `scoredOn` is `["faultLineCoverage"]` alone, 16 of 19 tie at 1.0,
  `blastRadius` and `symptomDistance` still Unknown.
- Four S9 entry points were refuted by execution (3475afc4, fbdd177b, 89d31b4c, and 49e58c20's
  route); two were explicitly confirmed (4e381147, b035b623). Every S9 symptom finding carries
  confidence 0.50, which now looks well calibrated.
- `path-tracer` still has no route to a `buildHandle`, so all 18 paths were built from source
  reads cross-checked against `jvm_trace_executed`. That is why 5 paths are PARTIAL.

## Unknown breakdown

| reason | count | meaning |
|---|---|---|
| NoDebugInfo | 2 | the two hops either side of `Cleaner#access$100`, a synthetic bridge on a non-throwing run |
| NotApplicable | 3 | 49e58c20 routeA endpoints genuinely absent; 823709f5 symptom surface absent; b035b623's absent guard |
| Truncated | 1 | 25 of 104 matched candidates adjudicated |
| TestsFailed | 0 | **cleared** — both compile-blocked bugs now convert |
| NoReproducer | 0 | cleared |
| ToolUnavailable | 0 | cleared |

## What a follow-up run should do first

1. **Adjudicate a random subsample of the 104 matched commits.** The only thing blocking a stated
   recall figure, and now clearly the weakest part of the dataset.
2. **Build call graphs for the deep set** to get `blastRadius` and `symptomDistance` so ranking
   stops being degenerate. The old ~3-graph ceiling is gone at 6g.
3. **Give `path-tracer` a route to a buildHandle**, then re-trace the 5 PARTIAL paths.
4. Re-trace 49e58c20 against routeB and record it as the primary route.

## Operational notes

b035b623's first attempt ran ~7555s and aborted at the *client* idle timeout with the server alive
and the parent worktree correctly restored. A stray `maven:3.9-eclipse-temurin-8` container had
been up 8 hours and was killed; the retry pinned `baseImage` to temurin-17 and finished in 358s.
Which of the two mattered was not isolated — the JDK-8 image is suspicious, since the handler
defaults the image from the pom's declared source level and the recipe for this run is temurin-17.

Schema was updated before each handler change; `sync-catalog`, `check-outputs` and `check-params`
pass. jvm 86 tests and app 54 tests pass, including four new specs covering stack-frame parsing,
selector-class extraction in both dialects, scoped porting with restore, and overload preservation
in the union.
