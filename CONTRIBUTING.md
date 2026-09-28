# Contributing to Causeway Mini

Thanks for looking at this. Causeway Mini is small on purpose, but that means a careful outside read, a fix, or a new capability from someone who wasn't in the room when it was built is genuinely valuable, it's exactly the kind of check this project needs. This document says how the project is put together, how to propose a change, and what's expected of it before it's considered done.

If anything here is unclear or wrong, that's worth a PR on its own.

## How to contribute

1. **Open an issue first for anything beyond a small fix**, a new agent, a new command, a schema change, a change to how an existing pipeline stage behaves. A short back-and-forth before code is written saves a rewritten PR later.
2. **Branch off `causeway-mini`**, even if you already have push access. Direct pushes to `causeway-mini` bypass review; a branch plus a pull request doesn't, and costs nothing extra.
   ```bash
   git checkout causeway-mini
   git pull
   git checkout -b your-change-name
   ```
3. **Make the change**, following the ownership rules and the "bar for calling something done" below.
4. **Open a pull request against `causeway-mini`** (not `main`, that's a different, unrelated project on this same repo). Describe what changed and why, and what you ran to verify it.
   ```bash
   git push -u origin your-change-name
   gh pr create --base causeway-mini
   ```
5. Expect review before merge. A PR that skips the verification bar below will get asked for it, not merged around it.

## Who owns what

| Piece | Owns | Never does |
|---|---|---|
| `src/main/scala/causeway/mini/*.scala` (the compiled CLI) | All GitHub API calls, all database reads/writes, all deterministic logic (search qualifiers, schema, JSON parsing) | Judgment calls a fixed rule can't make |
| `.claude/agents/repo-discovery.md` | Translating a plain-language repo request into structured `search-repos` flags | Anything but invoking `tools/causeway search-repos`, enforced by a `PreToolUse` hook, not just its own instructions |
| `.claude/agents/bug-classifier.md` | Scoring a batch of commits on message/diff/PR content | Any file, tool, or network access at all, enforced by `disallowedTools` in its frontmatter |
| `.claude/commands/*.md` (chat commands) | Asking the user questions, calling the CLI and agents in order, rendering results | Running their own SQL, their own `curl`, or duplicating logic the CLI or an agent already owns |

The rule underneath all of it: **agents never touch the database or GitHub directly.** Only the compiled CLI does. If a new agent needs to do either, route it through a CLI subcommand instead, don't give the agent the access itself.

## Adding a new agent

1. Create the file:
   ```bash
   touch .claude/agents/your-agent-name.md
   ```
2. Give it frontmatter with an explicit `tools:` (allow-list) or `disallowedTools:` (deny-list). **Never leave both out**: an agent with neither gets every tool available, not zero, no matter what its own body text claims.
   ```markdown
   ---
   description: One sentence a router can use to decide when to invoke this agent.
   tools: Bash
   ---

   Your agent's instructions go here.
   ```
   If the agent needs no tools at all (pure judgment/scoring work, like `bug-classifier`), use `disallowedTools:` listing everything relevant instead, there's no frontmatter syntax for literally zero tools.
3. If it genuinely needs `Bash` for one specific purpose and nothing else, add `allowedBashPattern` to the same frontmatter block:
   ```yaml
   allowedBashPattern: "^\\s*tools/causeway\\s+your-subcommand\\b"
   ```
   `.claude/hooks/restrict_agent_bash.py` reads this field generically for any agent that declares it, no change to the hook itself is needed.
4. Verify the restriction actually holds, not just that the agent behaves when asked nicely: invoke it live with a legitimate request and confirm it still works, and separately read through the hook's own logic to confirm a disallowed command would be denied (don't try to provoke a well-aligned model into misbehaving just to test the fence, that's the wrong level to verify an enforcement mechanism at).

## Adding a new chat command

```bash
touch .claude/commands/your-command-name.md
```

Give it frontmatter with at least a `description:`. In the body: gather what's needed (reuse anything already known from earlier in the session rather than re-asking), use `AskUserQuestion` for any choice with a small option set, call the CLI or an agent to do the actual work, state the result plainly including any run id. If the command needs to find a past run's file, call `tools/causeway list-runs` rather than listing a directory by hand.

## Adding a new CLI subcommand

1. Add the logic as a new object in `src/main/scala/causeway/mini/`, with a `def run(args: Array[String]): Unit` entry point, matching the shape of an existing one (`SearchRepos.scala` is a reasonably self-contained example).
2. Register it in `Causeway.scala`'s dispatch:
   ```scala
   case "your-subcommand" => YourObject.run(rest)
   ```
3. Add it to `Causeway.scala`'s own usage text.
4. Write MUnit tests for it under `src/test/scala/causeway/mini/`.
5. Run it live against a real GitHub repository or the real database before calling it done, not just a passing test suite.

## The bar for calling something done

- **Every fix gets a regression test, and the test gets proven**: temporarily reintroduce the bug, confirm the test fails, restore the fix, confirm it passes. A test that has never been seen to fail hasn't actually been shown to catch anything.
- **Every claim about behavior gets verified live**, against a real GitHub repo and the real database, not just "should work" reasoning. Clear out any verification database rows or workspace files afterward.
- **Every documented example is real captured output**, not hand-written or illustrative text.
- **Run the full test suite for anything that touches more than a couple of files**; a small, contained change only needs its own directly-affected spec files.

## Logging a decision

Any real design decision, not just what was built but why this way and not another, gets a new entry in `DECISIONS.md`, under today's date header (add one if today doesn't have one yet). Write it after the decision is made, in past tense, with the actual reasoning. This file is forward-only: never edit or reorder a past entry, only add new ones. If your PR includes a real design decision, add the entry as part of it.

## If you're not sure

Ask in the issue or the PR, rather than guessing at a design decision that could reasonably go more than one way. If a change is small and unambiguous, just make it.

For how this project actually got built, phase by phase, see `docs/genesis-prompts.md`. For the full history of individual decisions, see `DECISIONS.md`.
