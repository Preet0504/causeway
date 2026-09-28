# Contributing to Causeway Mini

This project has two AI subagents (`repo-discovery`, `bug-classifier`), a compiled Scala CLI, and a handful of Claude Code chat commands that orchestrate them. This document says how those pieces divide responsibility and what's expected of a change before it's considered done. See `docs/genesis-prompts.md` for how the project actually grew, and `DECISIONS.md` for the full history of individual decisions.

## Who owns what

| Piece | Owns | Never does |
|---|---|---|
| `src/main/scala/causeway/mini/*.scala` (the compiled CLI) | All GitHub API calls, all database reads/writes, all deterministic logic (search qualifiers, schema, JSON parsing) | Judgment calls a fixed rule can't make |
| `.claude/agents/repo-discovery.md` | Translating a plain-language repo request into structured `search-repos` flags | Anything but invoking `tools/causeway search-repos`, enforced by a `PreToolUse` hook, not just its own instructions |
| `.claude/agents/bug-classifier.md` | Scoring a batch of commits on message/diff/PR content | Any file, tool, or network access at all, enforced by `disallowedTools` in its frontmatter |
| `.claude/commands/*.md` (chat commands) | Asking the user questions, calling the CLI and agents in order, rendering results | Running their own SQL, their own `curl`, or duplicating logic the CLI or an agent already owns |

The rule underneath all of it: **agents never touch the database or GitHub directly.** Only the compiled CLI does. If a new agent needs to do either, it's wrong, route it through a CLI subcommand instead.

## Adding a new agent

1. Create `.claude/agents/<name>.md`. Give it an explicit `tools:` (allow-list) or `disallowedTools:` (deny-list) in its frontmatter. **Never omit both**: an agent with neither field gets every tool available, not zero, regardless of what its own prose claims.
2. If it genuinely needs `Bash` for one specific purpose and nothing else, add `allowedBashPattern: "<regex>"` to its frontmatter. `.claude/hooks/restrict_agent_bash.py` reads that field generically for any agent that declares it, no code change to the hook itself is needed.
3. Verify the restriction actually works, not just that the agent behaves when asked nicely: invoke it live with a legitimate request, and separately confirm (by reading the hook's own logic, not by trying to provoke a well-aligned model into misbehaving) that a disallowed command would actually be denied.

## Adding a new chat command

Follow the existing commands' shape: gather what's needed (reusing anything already known from earlier in the session rather than re-asking), use `AskUserQuestion` for any choice with a small option set, call the CLI or an agent to do the actual work, state the result plainly including any run id. If the command needs to find a past run's file, use `tools/causeway list-runs` rather than listing a directory by hand.

## Adding a new CLI subcommand

Add a case to `Causeway.scala`'s dispatch, write MUnit tests for it, and update `Causeway.scala`'s own usage text.

## The bar for calling something done

- **Every fix gets a regression test, and the test gets proven**: temporarily reintroduce the bug, confirm the test fails, restore the fix, confirm it passes. A test that has never been seen to fail hasn't actually been shown to catch anything.
- **Every claim about behavior gets verified live**, against a real GitHub repo and the real database, not just "should work" reasoning. Verification databases and workspace artifacts get cleared afterward.
- **Every documented example is real captured output**, not hand-written or illustrative text.
- **Run the full test suite for anything that touches more than a couple of files**; a small, contained change only needs its own directly-affected spec files.

## Logging a decision

Any real design decision, not just "what was built" but "why this way and not another," gets a new entry in `DECISIONS.md`, under today's date header (add one if today doesn't have one yet). Write it after the decision is made, in past tense, with the actual reasoning, not a tidied-up version of it. This file is forward-only: never edit or reorder a past entry, only add new ones.

## When you're not sure

Ask, rather than guessing at a design decision that could go more than one reasonable way. If a change is small and unambiguous, just make it.
