---
description: Mine a public GitHub repository for symptom-to-fault paths on important, well-covered bugs
argument-hint: <github-url> [--window 12m] [--cap 60] [--deep 5] [--resume <runId>]
---

Mine bug paths from: $ARGUMENTS

Run the stages in order. Each stage's output is the next stage's input, and every stage
checkpoints, so an interrupted run resumes rather than restarts. Defaults are deliberately
small — window 12 months or 300 commits, candidate cap 60, deep analysis on the top 5.

**Retrying a stage: reuse the key, or name what you replace.** Checkpoints are identified by
`(runId, stage, key)`, and `graph_resume_state` counts every non-DONE row as work still
outstanding. A retry under a NEW key therefore leaves the failed row behind forever, and the
stage can never read as complete — a real run failed S6 under `all_19`, succeeded under `all`,
and re-probed 37 already-passing revisions on every subsequent resume. Either reuse the original
key so the row is overwritten, or pass `supersedes: ["<old key>"]`, which marks the earlier
attempt without deleting it. Never supersede a SIBLING's failure: a stage with one key per bug
is supposed to show some DONE and some FAILED.

---

**S0 — Ingest.** `repo_clone`, then `repo_detect_ecosystem`. If unsupported, stop and report
the detection evidence. Register a runId. Call `graph_resume_state`: if this repo has an
incomplete run, resume it instead of starting fresh.

**S1 — Environment.** Dispatch `build-doctor`. It returns a `BuildRecipe` (container base
image, build tool, commands, flags, call-graph entry points), cached for the whole run. If no
recipe works, stop here — every downstream number would be Unknown.

**S2 — Candidates.** Dispatch `mining-strategist`. It sets the window and which recall nets to
run, and returns candidate set K with per-net tags. The random control net must run: it is how
prefilter recall gets measured.

**S3 — Bundling.** `forge_bundle_candidates` over K, batched. Deterministic.

**S4 — Adjudication.** Partition K into batches of ~15. Dispatch `bugfix-adjudicator` in waves
of at most 5 concurrent. F := candidates verdicted BUG_FIX.

**S5 — Structure.** For each f in F: `history_diff`, `jvm_method_at_line` on old-side ranges,
`szz_baseline`. Deterministic. SZZ runs for every f — blame needs no build, so bugs that fail
later gates still carry real data. Checkpoint.

**S6 — Build gate (HARD).** For each f in F: `jvm_build_probe` on parent(f) and on f, in
containers. Both must succeed. Failures are recorded with `admitted: false` and the
classification — they are NOT deleted from the graph.

**S7 — Coverage and call graph.** For f in F_b: `jvm_coverage_run` at parent(f),
`jvm_callgraph_build` at parent(f). Coverage of the changed lines is queried with the OLD-side
ranges. The server pools these; do not try to parallelize them yourself.

**S8 — Ranking.** `rank_importance` over coverage of changed lines, blast radius, and
symptom distance. Scores each dimension independently and composites only over Known
dimensions, recording `scoredOn`. Take the top `--deep` bugs for the remaining stages.

**S9 — Symptom.** Dispatch `symptom-characterizer` over the selected bugs, batched, waves of
at most 5. Its `entryPoint` output is what S10 aims at and what path length is measured from.

**S10 — Reproducer.** Walk the ladder per bug, cheapest rung first:

1. Fix added a regression test → `jvm_native_test_run` with that test's selector. It runs the
   project's own test at BOTH revisions and reports `differs`. It should fail at parent(f) and
   pass at f. → **T1 NATIVE**, and the parent side is instrumented, so it returns a `traceId`
   that makes an OBSERVED path possible in S11. This is the cheapest rung and the only one that
   yields a reproducer nobody had to synthesize; try it for every fix that touches a test file.
   Do NOT use `jvm_harness_run` for this: it compiles a single `Harness.java` with plain `javac`
   and cannot run a JUnit test.
2. Symptom carries a stack trace → `repro_botsing` → **T2**
3. Otherwise → dispatch `reproducer-synthesist`, budget 3 iterations → **T2** or **T3**
4. Fall through → `repro_evosuite_sweep` with differential filter
5. Nothing reproduces → **T4 STATIC**

Only rung 3 costs an agent. Do not dispatch the synthesist for bugs whose reproducer came free.

**S11 — Path.** Dispatch `path-tracer`, one per admitted bug, waves of at most 4. Paths on
T1/T2 bugs are confined to what actually executed; T3/T4 paths are static and must be tiered
as such.

**S12 — Metrics and persistence.** `metrics_*`, then `graph_upsert_bug` and `export_run`.

**S13 — Report.** Summarize:

- candidates examined per net, adjudication precision, estimated recall from the control net
- bugs admitted vs rejected, with the `rejectedFor` breakdown
- reproducer tier distribution (T1/T2/T3/T4) and path fidelity (FULL/PARTIAL/UNIT)
- **fraction of path steps observed rather than static** — the headline number
- Unknown breakdown by reason, in full. This is the dataset's honesty report and tells the
  user what their data can and cannot support.

Every figure you report must come from a tool response or a graph query. If a stage produced
nothing, say so plainly rather than omitting it.
