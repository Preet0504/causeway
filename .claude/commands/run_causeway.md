---
description: Onboard a target repo and validate it exists on GitHub.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini onboarding flow. Follow these steps in order, and do not skip or reorder them.

## 1. Ask for the repo URL

Ask the user, verbatim:

> What is the url of the repo?

Initialize a counter `retries = 0` for this run.

## 2. Validate the URL against GitHub

From the answer, derive `owner` and `repo`:
- Strip a leading `https://github.com/`, `http://github.com/`, or `git@github.com:` prefix.
- Strip a trailing `.git` and any trailing `/`.
- Split what remains on `/`; the first segment is `owner`, the second is `repo`.
- If no plausible `owner`/`repo` pair can be extracted at all, treat this the same as a failed check below (skip straight to the failure branch, don't bother running curl).

Otherwise, run:

```bash
curl -s -o /dev/null -w "%{http_code}" "https://api.github.com/repos/<owner>/<repo>"
```

- **Status `200`** — the repo exists. Go to step 3.
- **Anything else** (404, error, or no owner/repo could be extracted):
  - Increment `retries`.
  - If `retries` has now reached `3`, stop the onboarding flow. Tell the user plainly that the repo could not be validated after 3 attempts and the run is ending.
  - Otherwise, tell the user clearly that the URL they gave was not reachable as a GitHub repository, and ask again, verbatim:
    > What is the url of the repo?
  - Return to the top of step 2 with the new answer.

## 3. Confirm

Once validated, tell the user which repo was found (`owner/repo`). Continue to step 4 — don't stop here.

## 4. Ask for the mining window

First, briefly explain what "window" means here, in plain language: it's how far back into the repository's commit history Causeway Mini will look for candidate bug-fixing commits. A narrower window is faster but may miss older bugs; a wider window finds more bugs but takes longer to mine.

Then ask the user to choose, offering exactly these options:
- Past 1 month
- Past 3 months
- Past 6 months
- Other (let them specify their own window)

If they pick "Other", ask them to state the window directly (e.g. "past 2 weeks", "since v2.0", "all time").

Don't ask for a bug cap here — that's deliberately deferred to `/inspect_repo`, which can show the user how many commits actually fall in the window before asking them to pick a cap informed by that number.

## 5. Final report

Produce one final message that states, plainly:
- The repo that was validated (`owner/repo`)
- The mining window chosen

Then tell the user to run `/inspect_repo` next, which will clone the repo, show how many commits fall in this window, and ask for a bug cap at that point.

End the message with: "If anything above is wrong, just run /run_causeway again to start over."
