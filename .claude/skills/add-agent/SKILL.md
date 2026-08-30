---
name: add-agent
description: Add a reasoning subagent to the pipeline, or decide whether one is warranted at all. Use before creating any new .claude/agents/*.md. Applies the distinct-question test that keeps agent boundaries aligned with reasoning objectives rather than tool categories.
---

# Adding an agent

Most proposals that reach this skill should not become agents. Apply the tests first.

## Test 1 — write the question

State what the agent decides as a single question, in one sentence, without naming a tool or a
technique. The six existing agents:

| Agent | Question |
|---|---|
| `mining-strategist` | Where is minable signal, and are we done? |
| `bugfix-adjudicator` | Is this a genuine bug fix? |
| `build-doctor` | How do I build, test, and enter this project? |
| `symptom-characterizer` | What did this look like from outside, and where did it surface? |
| `reproducer-synthesist` | What input makes this bug manifest? |
| `path-tracer` | What route connects the symptom to the fault? |

If you cannot write the question without saying "using the call graph" or "by running
coverage", you are describing a **capability**, not a question. Build a tool.

## Test 2 — is it named after a tool?

`callgraph-agent`, `coverage-agent`, `git-agent`, `metrics-agent` all fail. These are services
regardless of how the work is grouped. This is one of the project's founding rules and the one
most likely to be violated by accident.

## Test 3 — does an existing question already cover it?

Six questions is enough for a pipeline this size. If your candidate is a sub-step of an
existing one, extend that agent's prompt rather than splitting. Splitting costs a fresh context
that must re-derive what the parent already knew.

Split only when the two halves have **different failure modes or different budgets**. The
synthesist/tracer split passes on both counts: synthesis is an iterative compile-run-adapt loop
with its own 3-iteration budget and is skipped entirely when a reproducer arrives free from
rungs 0/1/3, while the tracer always runs.

## Test 4 — is the decision adaptive?

An agent must choose its next action based on what a tool actually returned. If the procedure
is fixed — always call A, then B, then C — it is a script, and it belongs in the `/mine-repo`
command as deterministic stages.

---

If all four pass, write the agent.

## Frontmatter

```yaml
---
name: kebab-case-name
description: What it decides and when to invoke it. Written for the dispatcher, not the agent.
tools: mcp__causeway__..., mcp__causeway__...
model: sonnet | opus
---
```

- **`tools`**: least privilege. List only what the question requires. An agent that does not
  need `history_diff` must not have it.
- **`model`**: `sonnet` for text-shaped batched judgments (adjudication, symptom, build
  recipes). `opus` for deep tool interaction with branching strategy (strategy, synthesis, path
  tracing).

## Body

Cover, in this order:

1. **Objective** — the question, restated as an instruction.
2. **Procedure** — adaptive, explicitly. Say what evidence resolves which specific doubt, and
   say *not* to run every available call by reflex. Give the escalation triggers.
3. **Evidence weighting** — what is strong, what is weak, and **what is neutral**. State
   explicitly that a missing signal never counts against; that rule is violated by omission
   more than by intent.
4. **Missing-data behaviour** — which `Unknown(reason)` applies, and that it is neutral.
5. **Output** — via `findings_record`, with evidence ids. Note that a rejection means the agent
   cited something it did not retrieve, and the fix is to retrieve it, not to rephrase.
6. **Hard rules** — what it must never assert. Be concrete: name the specific fabrications this
   agent would be tempted toward.

## Budget

Decide and write down: batched or one-per-item, and the wave cap. Adjudication-shaped
judgments batch (~15 per invocation); deep tool interaction does not. Waves are ≤5 for batched
agents, ≤4 for per-item agents. Unbounded fan-out is what makes this pipeline unusable on a
normal subscription.

## Propagate

1. `reference/design.md` §11 — the agent roster table.
2. `reference/design.md` §9 — the agent × tool access matrix.
3. `.claude/commands/mine-repo.md` — where in the pipeline it gets dispatched, and its wave cap.
4. A decision entry in the log if this changes the architecture rather than extending it.
