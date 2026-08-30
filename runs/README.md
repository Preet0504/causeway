# runs/

One directory per session, holding the record of what the agents actually did. This is
research output, not scratch — it is tracked in git, unlike `workspace/`.

```
runs/
├── rates.json                  # per-MTok prices for cost accounting; fill these in
└── <session-id>/
    ├── manifest.json           # repo, window, caps, config snapshot, linked runIds
    ├── telemetry/
    │   ├── tool-calls.jsonl    # every tool call, attributed to the agent that made it
    │   └── agent-turns.jsonl   # subagent start/stop, effort level
    ├── usage.json              # tokens and cost, from tools/collect-usage.mjs
    └── report.md               # human-readable run summary
```

## Where each piece comes from

| Data | Source | Why |
|---|---|---|
| Tool calls, agent attribution | `.claude/hooks/telemetry.mjs` (PostToolUse, SubagentStart/Stop) | Hooks carry `agent_type`, so attribution is free — no need to thread an agent id through 47 tool schemas |
| Tokens, cost | `tools/collect-usage.mjs`, run after the session | **No hook payload carries token or cost data.** It exists only in the session transcript |
| Decisions, verdicts, confidence | `findings.record` → Neo4j, exported by `export.run` | Decisions are evidence-bearing and belong in the graph, not in telemetry |

## Two things telemetry is deliberately not

**Not an evidence source.** Nothing here may back a claim. `tool-calls.jsonl` records that a
call happened and what shape its arguments were — not what it returned. Evidence lives in the
ledger, keyed by `evidenceId`. If you find yourself wanting to cite telemetry, you want the
graph instead.

**Not a payload store.** Arguments are summarized to keys and sizes. A single
`jvm_harness_run` call can carry a few hundred lines of generated Java; logging it here would
balloon these files and create a second, unversioned copy of content that belongs in the run
record proper.

## Collecting usage

```bash
node tools/collect-usage.mjs <session-id>
node tools/collect-usage.mjs path/to/transcript.jsonl
```

If it reports zero messages with usage, the transcript format changed — fix the parser. It
exits non-zero in that case rather than writing a file full of zeros that would quietly read
as "this run was free".
