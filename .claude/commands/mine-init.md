---
description: Phase 1 of 6 — clone, detect ecosystem, build recipe, find candidates
argument-hint: <github-url> [--window 12m] [--cap 60] [--deep 5]
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above: `Task`, to dispatch
reasoning agents, and the causeway MCP surface. You do NOT have `Read`, `Glob`, `Grep`, `Edit`,
`Write`, or `Bash`. This is deliberate: you are mining a target repository, not developing
causeway itself, and you must never read or modify this project's own source. If a task here
feels like it needs raw file access, call the matching causeway tool, or say that no such tool
exists yet — never reach past the restriction.

Set up mining for: $ARGUMENTS

This is phase 1 of 6. It produces the environment and the candidate set; it never adjudicates
which candidates are real bugs — that is `/mine-adjudicate`'s job, next.

**Before starting: check for existing work.** Call `graph_resume_state` with the repository URL.
If an incomplete run already exists for it, STOP and tell the user to run `/resume <runId>`
instead of starting a fresh one — a second run against the same repository fragments evidence
across two runIds for no reason.

**S0 — Ingest.** `repo_clone`, then `repo_detect_ecosystem`. If the ecosystem is unsupported,
stop and report the detection evidence plainly; do not proceed. Register a runId.

**S1 — Environment.** Dispatch `build-doctor`, and give it the `headSha` from S0's `repo_clone`
response explicitly in the dispatch prompt. `build-doctor` has no tool that resolves a branch
name or lists commits — every tool it is granted (`history_file_at`, `jvm_build_toolchain`,
`jvm_build_probe`) requires a full 40-hex commit SHA already in hand, so it cannot discover a
starting commit on its own. Passing only the repoHandle and forgetting the SHA leaves it unable
to read a single file; this was found by actually running this stage, not hypothesized.

Also give it the exact `repoSlug` (`owner/name`, from the URL you were given) to use for any
`notes_write`/`notes_read` call it makes — state it as a literal string, not something to infer.
`notes_write`'s `repoSlug` is free text with no cross-check against the actual repository, and a
repo whose ownership moved (a transferred GitHub org, a renamed fork) leaves the OLD name
readable in commit history, README credits, or package metadata. An agent left to infer the
slug from repository content picked the former maintainer's org instead of the current one —
found live, mining `stleary/JSON-java`: `build-doctor` wrote its note under
`douglascrockford/JSON-java` (the project's original maintainer, still visible throughout the
repo's history), and `notes_read` for the run's real slug came back empty. Every later dispatch
in this run and its downstream phases must pass the same rule: state the canonical slug
explicitly, never let an agent derive it.

It returns a `BuildRecipe` (container base image, build tool, commands, flags, call-graph
entry points), proved by an actual probe and cached for the rest of the run. If no recipe
works, stop here — every downstream number would be Unknown, and there is nothing phase 2 could
do about it.

**S2 — Candidates.** Dispatch `mining-strategist`. It sets the window and which recall nets to
run, and returns candidate set K with per-net tags. The random control net is mandatory: it is
the only way prefilter recall ever gets measured, and skipping it cannot be recovered later
without re-running this phase.

**S3 — Bundling.** `forge_bundle_candidates` over K, batched. Deterministic, no agent.

**Checkpoint every stage** under `(runId, stage, key)` via `graph_checkpoint`, passing `repo` so
the run becomes findable by repository, not only by an id nobody has yet.

---

When you finish, or when you stop early at S0 or S1's gate, close with exactly this shape:

## What this phase did
State the ecosystem detected, the build recipe (image, build tool), and the candidate numbers:
how many commits in the window, how many matched each recall net, the union, the control sample
size, and K after the cap. Every number here must come from a tool response, not be estimated.

## Look deeper
```
/inspect-bugs <runId>        — nothing to show yet; candidates are not bugs until S4 adjudicates
/mine-doctor                 — if a stage failed for an environment reason, check setup first
```

## Next
```
Run: /mine-adjudicate <runId>
```

If S0 or S1 stopped the run early, replace "Next" with the specific fix needed (unsupported
ecosystem, or which build approach to try) and do not suggest `/mine-adjudicate` — there is
nothing for it to adjudicate yet.
