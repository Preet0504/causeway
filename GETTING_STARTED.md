# Getting Started

By the end of this guide, Causeway Mini will be fully working on your machine, and you'll have run it start to finish once: a real GitHub repo mined, its commits enriched with GitHub context, and at least one genuine bug fix found and explained. For the full reference on what each command does, see [README.md](README.md).

Every step here, Windows and Linux both, was actually run while writing this guide, not written from documentation knowledge alone.

## 1. Install a JDK

Causeway Mini's build tool, sbt, requires JDK 17 or newer.

**Windows**: install [Eclipse Temurin 17](https://adoptium.net/) using the `.msi` installer, accepting the defaults.

**Linux**:
```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 17.0.16-tem
```

Confirm it worked:
```
java -version
```
You should see something like:
```
openjdk version "17.0.16" 2025-07-15
OpenJDK Runtime Environment Temurin-17.0.16+8 (build 17.0.16+8)
```

## 2. Install sbt

**Windows**, using [Scoop](https://scoop.sh/):
```powershell
scoop install sbt
```

**Linux**, continuing with SDKMAN from step 1:
```bash
sdk install sbt
```

## 3. Get a bash shell

`tools/causeway`, the CLI every chat command calls, is a bash script, so you need bash to run it.

**Windows**: you already have **Windows Terminal** built in, nothing to install there. Install [Git for Windows](https://git-scm.com/download/win), which adds a **Git Bash** profile that Windows Terminal automatically detects. From here on, open a new Windows Terminal tab, pick **Git Bash** from the dropdown, and run every command in this guide from there, not the PowerShell or cmd tabs.

**Linux**: you already have one, nothing to do here.

## 4. Install Python and PyYAML (required)

One of the AI agents this project uses, `repo-discovery`, is given raw Bash access so it can run one specific command on your behalf (`tools/causeway search-repos ...`) when you run `/discover_repos`. On its own, that access has no limit: nothing would stop it from running some other shell command instead, including one that reads or writes `workspace/causeway.db` directly, or deletes files. A hook (`.claude/hooks/restrict_agent_bash.py`) is what actually enforces "only that one command, nothing else," and that hook is written in Python. Without Python installed, the hook can't run at all. This was confirmed directly against this exact project: it fails *open*, meaning the restriction just silently stops applying, with no warning that it's gone. That's why this step isn't optional.

**Windows**: install Python from [python.org](https://www.python.org/downloads/), then:
```powershell
pip install pyyaml
```

**Linux**:
```bash
sudo apt install python3 python3-pip
pip3 install pyyaml
```

Confirm it worked:
```
python --version
python -c "import yaml; print('PyYAML', yaml.__version__)"
```
You should see:
```
Python 3.12.0
PyYAML 6.0.1
```
(Exact versions may differ, what matters is both commands succeed with no error.)

## 5. Clone this repo and confirm sbt can see it

```bash
cd path/to/repo-bugs-extraction
sbt --version
```
You should see:
```
sbt version in this project: 2.0.7
```

## 6. Get a GitHub token

Causeway Mini only ever reads public repositories, so give it a token scoped for exactly that, nothing more.

1. Go to [github.com/settings/personal-access-tokens/new](https://github.com/settings/personal-access-tokens/new).
2. Under "Repository access", choose **Public Repositories (read-only)**.
3. Click **Generate token** and copy it, you won't see it again.

## 7. Give Causeway Mini the token

At the repo root, create a file named `.env`:
```
GITHUB_TOKEN=<paste the token you just copied>
```
This file is already listed in `.gitignore`, it will never get committed.

## 8. Build and verify

```bash
tools/causeway -h
```
The first run compiles the project, you'll see `tools/causeway: source changed, rebuilding ...` on stderr first. Once it finishes, you should see the subcommand list, starting with:
```
Usage: causeway <subcommand> [args...]

Subcommands:
  search-repos          Discover candidate repositories from a structured search specification, ...
```
If you see that, everything above is confirmed working.

## 9. Run the workflow, start to finish

Open this project's folder in Claude Code. Everything from here happens in the chat, not the terminal. Every example below is real output from actually running this project's own pipeline while writing this guide, not illustrative text.

Don't already have a specific repo in mind? Type `/discover_repos` and describe what you're looking for in plain language, for example "actively maintained, fairly popular Java libraries related to JSON parsing, at least 1000 stars." It hands that to a search agent and checks each result before showing you anything:
```
QUALIFY owner=chinabugotech repo=hutool qualifies=true stars=30272 language=Java
QUALIFY owner=opendataloader-project repo=opendataloader-pdf qualifies=true stars=29295 language=Java
QUALIFY owner=redisson repo=redisson qualifies=true stars=24396 language=Java
QUALIFY owner=apple repo=pkl qualifies=true stars=11523 language=Java
QUALIFY owner=jwtk repo=jjwt qualifies=true stars=11137 language=Java
```
Already searched before and want to revisit an old result instead of searching again? Type `/list_qualifying_repos`:
```
REPO owner=chinabugotech repo=hutool stars=30272 ... alreadyMined=false
REPO owner=opendataloader-project repo=opendataloader-pdf stars=29295 ... alreadyMined=false
TOTAL_QUALIFYING=5
SHOWN=5
```
Either way, once you land on a repo, continue below.

**Type `/inspect_repo`.** It asks for a repo and a window:
```
What is the url of the repo? https://github.com/stleary/JSON-java
How far back should Causeway Mini look for candidate bug-fixing commits? Past 3 months
```
It clones the repo, checks its remotes and branches (asking you to choose only if there's more than one, here there's one of each, so nothing to pick), then shows you how many commits fell in that window and asks how many to look at closely:
```
There are 31 commits in this window. How many should be scanned in detail? 6
```
It writes those 6 commits to an evidence file and reports a run id:
```
RUN_ID=71dbab24-17b2-4135-a63b-0f6f05e6584e
```
Worth saving if you ever want to come back to this exact run later: `/list_runs` lists your mining history by date, like `git log`, and lets you pick one back up (more on that at the end of this section).

**Type `/inspect_commits`.** It already knows the evidence file from the step above, no need to tell it again. It fetches each commit's linked GitHub pull requests/issues and computes its code diff:
```
Enriching 6 of 31 window commits (scanCommitLimit=6)
ENRICHED_COUNT=6
SAMPLE sha=8746735758 prCount=1 diffFileCount=0 isMergeCommit=true
SAMPLE sha=a25f83a0e4 prCount=1 diffFileCount=3 isMergeCommit=false
SAMPLE sha=359b43d8df prCount=1 diffFileCount=5 isMergeCommit=false
```

**Type `/classify_bugs`.** It tells you how many enriched commits are available and asks your bug target:
```
Enriched commits available: 6. How many genuine bug fixes would you like to find, at most? 1
```
It sends the commits to an AI reviewer in batches, reads back a score for each one's message, diff, and linked PR/issue content, and decides genuine bug fix or not, newest commit first:

| sha | message | diff | PR/issue | verdict |
|---|---|---|---|---|
| 8746735758 | 0.85 | n/a (merge) | 0.90 | not counted |
| a25f83a0e4 | 0.20 | 0.40 | 0.50 | not a bug fix |
| 359b43d8df | 0.55 | 0.40 | 0.75 | not a bug fix |
| bcbbc89568 | 0.90 | 0.75 | 0.85 | **bug fix** |
| 4f859fdf3b | 0.75 | n/a (merge) | 0.85 | not counted |
| 3dd0ec02f2 | 0.70 | 0.65 | 0.85 | extra (found, past target) |

Two commits genuinely turned out to be bug fixes, one more than the target of 1: `bcbbc89568` ("Fixes corner case where strict mode isn't working when JSONTokener's next() and back() are called first," with two new regression tests proving the corrected behavior) was the more confident pick, so it's the one counted. `3dd0ec02f2` (closing a real CWE-91 XML element injection vulnerability) was also genuine, just found after the target was already met, so it's kept and shown, marked as extra rather than discarded. Since the target was reached, examination stopped there:
```
Examined 6 of 6 scanned commits.
Final file: workspace/exports/stleary-JSON-java/json/run_71dbab24-..._classified.json
```

That's a complete run: a real repo mined, enriched with GitHub context, and a real bug fix found with an explanation of why it counts as one.

`/run_causeway` runs all three of the steps above as a single command instead of three separate ones, asking the same questions, just without stopping to report in between. Here's a real, separate example, a fast pass against `jwtk/jjwt`'s past month (4 commits, all scanned): the commits included a genuine fix for a JWT parsing bug (closing a real issue, with a regression test proving the fix) that, interestingly, was reverted by the very next commit in the same window, a good reminder that a bug-fix verdict is about that one commit's own evidence, not what happened to it afterward:
```
Examined 4 of 4 scanned commits.
```
| sha | message | diff | PR/issue | verdict |
|---|---|---|---|---|
| fb71496164 | 0.55 | 0.40 | 0.50 | not a bug fix (README-only) |
| 1b768013e8 | 0.30 | 0.35 | n/a | not a bug fix (reverts the fix below) |
| 9e5c9ccbf1 | 0.02 | 0.02 | 0.05 | not a bug fix (dependency bump) |
| 95d0022311 | 0.85 | 0.85 | 0.90 | **bug fix** |

See [README.md](README.md#the-commands-in-order) for every command's full behavior in depth.

Lost track of a run id? Type `/list_runs`, it lists your mining history, most recent first, like `git log`:
```
RUN stage=pass run_id=5ba89ecd-... owner=jwtk repo=jjwt window=past-1-month ... stagesReached=inspect-repo,inspect-commits,classify-bugs
RUN stage=pass run_id=71dbab24-... owner=stleary repo=JSON-java window=past-3-months ... stagesReached=inspect-repo,inspect-commits,classify-bugs
SHOWN=2
```

## Troubleshooting

Match the exact error text you're seeing to one of these.

**`GITHUB_TOKEN environment variable is required`**
Step 7's `.env` file is missing, misnamed, or not at the repo root. Confirm it's a file literally named `.env` in the same folder as `build.sbt`, containing a `GITHUB_TOKEN=` line with no quotes around the value.

**A jar-file, lock, or named-pipe error from sbt** (`AccessDeniedException`, `FileSystemException: ... cannot be accessed by the system`, `Couldn't open file ...pipe...`, `failed to connect to server`)
A leftover `java` process from an earlier attempt, or one an editor/IDE started in the background, is still holding a build file open. Find and stop it, then retry:
- Windows (PowerShell): `Get-Process -Name java`, then `Stop-Process -Id <id> -Force` for each one listed.
- Linux: `pkill -f sbt-launch.jar`.

**`Could not find or load main class causeway.mini.Causeway` / `ClassNotFoundException`**
The cached classpath under `target/` is stale or was built for a different setup than the one now running. Delete it and let `tools/causeway` rebuild from scratch:
```bash
rm -rf target
tools/causeway -h
```

**`401 Bad credentials`** (from any command that talks to GitHub)
Your token is invalid, expired, or was revoked. Generate a new one (step 6) and replace the value in `.env`.

**Cloning a repository hangs or fails with a network error**
Check your internet connection, and whether a corporate proxy or firewall is blocking outbound HTTPS to `github.com`.
