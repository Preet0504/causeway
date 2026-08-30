# notes/

Per-repository knowledge that is expensive to re-derive, written and read by agents through
`notes.write` / `notes.read` / `notes.list_subjects`. Persists across runs.

```
notes/
└── <owner>__<name>/
    └── notes.jsonl
```

## The rule that makes this safe

> **Notes influence what an agent DOES. Evidence determines what an agent may SAY.**

Without that line, notes are a laundering channel: agent A writes a belief, agent B reads it
as fact, and a claim ends up tracing to an earlier agent's guess instead of a tool return —
exactly what the evidence ledger exists to prevent.

It is enforced structurally, not by asking nicely:

- Note tools return **`note_*` ids**. `findings.record` requires **`^ev_`**. A note is
  therefore uncitable — not discouraged, *impossible*.
- The `kind` enum has **no `FACT` or `FINDING` member**. You can record how the repo builds;
  you cannot record what a bug is.
- Anything read from a note is a **hint**: possibly stale, possibly written by an agent that
  was wrong. Verify through tools before asserting.

## Kinds

| Kind | For | Example |
|---|---|---|
| `ENVIRONMENT` | how this repo builds and runs | "needs JDK 11; the maven wrapper is broken pre-2021, invoke mvn directly" |
| `CONVENTION` | project habits | "issues are referenced as CSV-nnn, not #nnn; fixes rarely use the word 'fix'" |
| `DEAD_END` | an approach that failed, and why | "harness at CSVParser.parse cannot reach the fault — needs a Reader over a real file, not a String" |
| `FIXTURE` | setup a harness needs | "tests load resources from src/test/resources/csv/; harness must set user.dir" |

`DEAD_END` earns the most budget back. The synthesist gets 3 iterations per bug; without
notes it can spend all three rediscovering the same blocked path it hit on the previous bug.

## Hygiene

Use `supersedes` when a note becomes wrong rather than adding a contradicting one — an agent
reading two opposing notes has no way to tell which is current, and will pick badly.

Call `notes.list_subjects` before `notes.read`. It returns kinds and subjects without bodies,
so a large note store does not flood agent context with irrelevant history.
