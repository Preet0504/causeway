---
description: A rough read on candidate count and time before committing to a full run
argument-hint: <github-url> [--window 12m] [--cap 60] [--deep 5]
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Estimate: $ARGUMENTS

This gives a heuristic before-you-commit read, not a guarantee. State that plainly in the output
rather than presenting a number with false precision.

**Real, cheap probe first.** Call `repo_clone` and `history_list_commits` for the stated window
— this is genuinely inexpensive (class E, no agent) and gives a REAL commit count rather than a
guess. Do not estimate this part; retrieve it.

**Everything past that is arithmetic, not a tool call**, using --cap and --deep directly:
adjudication batches at ~15 per batch, waves of at most 5 concurrent; symptom characterization
and path tracing over the top --deep bugs, waves of at most 4-5.

**Rough per-unit timings**, stated as ranges from this project's own observed history, not
invented: a build probe runs 50-85 seconds, a coverage run 150-230 seconds, a native test
reproducer a few minutes each including container startup. Multiply by however many of each this
window's numbers imply, and give a total as a wide range, not a point estimate.

---

## What this showed
Real commit count for the window, the candidate cap's implied adjudication load, the deep set's
implied build/coverage/reproduction load, and a total time range labeled clearly as a heuristic.

## Next
```
If the estimate looks reasonable:  Run: /mine-init <github-url> ...
If the window or cap should change first, adjust the arguments and run /estimate-cost again —
it is cheap to re-check before committing to a run that is not.
```
