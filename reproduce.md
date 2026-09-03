# Reproduce this workflow

A step-by-step guide. Every command is given exactly as you should type it. Anywhere the
guide shows `<owner>/<repo>`, replace it with the actual GitHub repository you want to mine.
Causeway works on any JVM-based repository, not one specific project.

---

## 1. Install what you need

You need four things: a Java 17 JDK, sbt, Docker, and Node.js.

### On Mac

Open Terminal and run:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install openjdk@17 sbt node
brew install --cask docker
```

After installing, open the **Docker** app from your Applications folder once, so it finishes
its first-time setup. Leave it running in the background; Docker only works while its app is
open.

### On Windows

Open PowerShell and run:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install sbt.sbt
winget install OpenJS.NodeJS.LTS
winget install Docker.DockerDesktop
```

After installing, open **Docker Desktop** from the Start menu once, so it finishes its
first-time setup. Leave it running in the background; Docker only works while this app is
open. If you'd rather not use `winget`, you can download Docker Desktop directly from
[docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) and
install it the normal way instead of the command above.

You will also need **Git Bash**, which comes bundled with Git for Windows
([git-scm.com](https://git-scm.com/downloads/win)). Several commands below use bash syntax and
should be run in Git Bash, not PowerShell; each one is labeled.

### Check everything installed correctly

```bash
java -version
sbt --version
docker --version
node --version
```

Each should print a version number. `java -version` should say `17` somewhere in its output.

---

## 2. Get the code and open Claude Code

```bash
git clone <this-repository-url>
cd repo-bugs-extraction
claude
```

`claude` opens Claude Code inside this project folder. Everything from here on happens by
typing commands into Claude Code, not into your regular terminal, unless a step says otherwise.

---

## 3. Get a GitHub token

Causeway needs a GitHub personal access token to read commit history, issues, and pull
requests from the repository you mine.

1. Go to [github.com/settings/tokens](https://github.com/settings/tokens) in your browser.
2. Click **Generate new token**, then **Generate new token (classic)**.
3. Give it a name, e.g. `causeway`.
4. Under **Select scopes**, check **`public_repo`**.
5. Click **Generate token** at the bottom.
6. Copy the token now. GitHub only shows it once, and it starts with `ghp_`.

---

## 4. Create the `.env` file

In your terminal (bash), from the project folder:

```bash
touch .env
```

In PowerShell instead:

```powershell
New-Item .env -ItemType File
```

Open the new `.env` file in any text editor and paste this in, replacing the token with the
one you copied:

```
GITHUB_TOKEN=ghp_your_token_here
NEO4J_URI=bolt://localhost:7690
NEO4J_USER=neo4j
NEO4J_PASSWORD=causeway-dev
```

Save the file.

---

## 5. Start the database

Causeway stores everything it finds in a Neo4j database, running in a Docker container. With
Docker Desktop open, run this once, in bash:

```bash
docker run -d --name causeway-neo4j \
  -p 7475:7474 -p 7690:7687 \
  -e NEO4J_AUTH=neo4j/causeway-dev \
  -e NEO4J_server_memory_heap_max__size=2G \
  -e NEO4J_server_memory_pagecache_size=512m \
  neo4j:5-community
```

This only needs to be run once, ever. After that the container stays on your machine, and you
can start it again later with `docker start causeway-neo4j` if it's ever stopped, without
needing the long command again.

---

## 6. Build the server

Back in bash, from the project folder:

```bash
export PATH="$HOME/scoop/shims:$PATH"
set -a && . ./.env && set +a
sbt -batch "app/dist"
```

This compiles the project and packages it. The first run can take several minutes while sbt
downloads dependencies; later runs are much faster. You should see `[success]` printed near the
end.

---

## 7. Open Claude Code and check the setup

If Claude Code was already open, close it and run `claude` again from the project folder, so it
picks up the build you just made.

Then type:

```
/mine-doctor
```

This checks that Docker is running, Neo4j is reachable, and the causeway server started
correctly. If anything fails, it tells you exactly what to fix. Don't move on until this
passes.

---

## 8. Run a mining pass

Run these one at a time, in order. Replace `<owner>/<repo>` with the actual repository, and
`<runId>` with the run ID printed by the first command.

```
/mine-init https://github.com/<owner>/<repo>
```
Clones the repository, figures out how it builds, and finds candidate commits that might be bug
fixes. When it finishes, it prints a `runId`; copy it, you'll need it for every command after
this one. Real output, from actually mining `stleary/JSON-java`:

```
## What this phase did
Ecosystem: JVM (Maven; pom.xml and build.gradle are both present, and Maven wins). Build recipe:
proved via jvm_build_probe. baseImage maven:3.9-eclipse-temurin-8 (bare eclipse-temurin:8-jdk
has no mvn), buildCommand `mvn clean compile -D maven.compiler.source=8 -D
maven.compiler.target=8`, testCommand `mvn test` with the same flags, entry points the library's
four public parsing constructors/factories (JSONObject, JSONArray, JSONTokener,
XML.toJSONObject).

Candidates: window 12 months back from HEAD, capped at 60 commits. 60 scanned, truncated=true
(more exist further back). LEXICAL matched 21, STRUCTURAL matched 13, RANDOM_CONTROL sampled
its full 10. 9 commits matched both nets. K = 35 after the cap.

## Next
Run: /mine-adjudicate run_71e86d4ef19eeb85
```

```
/mine-adjudicate <runId>
```
Decides which candidates are genuine bug fixes and finds the exact lines each one changed. Real
output from the same run:

```
## What this phase did
35 candidates adjudicated across 3 batches, one wave. Verdicts: 13 BUG_FIX, 22 NOT_BUG_FIX,
0 UNDECIDED. Every verdict backed by the commit's linked GitHub issue and diff, not the commit
message alone: several "fix" commits turned out to be pure Sonarqube lint cleanup or doc
updates once the diff was actually read, and were correctly verdicted NOT_BUG_FIX.

## Next
Run: /mine-admit run_71e86d4ef19eeb85
```

```
/mine-admit <runId>
```
Confirms each bug fix actually compiles, before and after the fix. This step uses Docker
containers and can take a while. For 13 bugs, this step alone took about 15 minutes.

```
## What this phase did
13/13 admitted, 0 rejected. 23 unique build probes (13 fix commits + 10 unique parent commits,
several bugs sharing a parent), every one succeeded.

## Next
Run: /mine-select run_71e86d4ef19eeb85
```

```
/mine-select <runId>
```
Ranks the confirmed bugs and picks the most important ones to study in depth.

```
## What this phase did
Ranked on fault-line coverage (the other two ranking dimensions aren't available yet at this
stage: symptom distance needs the next phase's output, and blast radius needs a call graph
built per bug). 8 of 13 bugs had measurable coverage; the other 5 correctly show no score at all
rather than a fabricated zero. Ranking was degenerate at the top (six bugs tied at a perfect
1.0), so the deep set was chosen on a documented alternative: diversity across the affected
files, six bugs selected.

## Next
Run: /mine-trace run_71e86d4ef19eeb85
```

```
/mine-trace <runId>
```
Tries to reproduce each selected bug, then traces exactly how it caused the problem a user
would have seen. This is the slowest step; expect several minutes per bug.

```
## What this phase did
Reproducer tiers: 4 T1_NATIVE (the project's own regression test fails at the parent commit and
passes at the fix, the strongest possible reproducer), 1 T2_SYNTHESIZED (no regression test
existed, so a small Java harness was written and confirmed to trigger the bug), 1 T3_REACHED at
unit fidelity (the fault could only be reached through reflection; no real caller in the
codebase ever exercises it, and the trace honestly says so rather than claiming otherwise).

Path tiers: 5 of 6 bugs fully OBSERVED (every hop in the symptom-to-fault route is confirmed by
both the static call graph and what actually executed). The 6th is correctly HYPOTHESIZED: a
real route exists in the code, but nothing that ran actually took it, and the report says so
rather than rounding up.

Headline: 20 of 21 traced path steps (95%) were OBSERVED rather than static.

## Next
Run: /mine-finalize run_71e86d4ef19eeb85
```

```
/mine-finalize <runId>
```
Writes the final result to disk and prints a summary.

```
## What this phase did
Wrote workspace/exports/run_71e86d4ef19eeb85/dataset/: bugs.csv (24,065 B), path_hops.csv
(1,765 B), findings.csv (12,191 B), evidence.csv (24,247 B), run_metadata.json (6,588 B),
CODEBOOK.md (8,283 B). 13 bugs total, 13 admitted.
```

Each command tells you, at the end, exactly which command to run next, so you don't need to
memorize this list.

### Or, run it all at once

If you don't want to stop and check results between steps:

```
/mine-repo https://github.com/<owner>/<repo>
```

This runs all six steps above back to back, automatically.

---

## 9. Where to find your results

After `/mine-finalize` (or `/mine-repo`) finishes, your results are written to:

```
workspace/exports/<runId>/dataset/
```

Inside that folder:

| File | What it is |
|---|---|
| `bugs.csv` | One row per bug: which commit fixed it, which lines changed, what the symptom was, whether it reproduced, and the traced path. Open this in Excel, Numbers, or any spreadsheet app. |
| `path_hops.csv` | The step-by-step route each bug's traced path took, one row per step. |
| `findings.csv` | Every claim the tool made, with a link to the evidence behind it. |
| `evidence.csv` | The record of every piece of data retrieved to support those claims. |
| `run_metadata.json` | How the run was configured: which repository, which window of history, which build settings. |
| `CODEBOOK.md` | An explanation of every column in the files above. Read this before opening `bugs.csv`. |

Nothing else in this repository changes. The cloned repository you mined lives in
`workspace/clones/`, and the full database lives inside the Neo4j Docker container, not as
files you need to look at directly.

---

## 10. Look at results without leaving Claude Code

```
/inspect-bugs <runId>
```
Lists every bug found so far, one line each.

```
/inspect-bug <runId> <fixSha>
```
Shows everything known about one specific bug: its symptom, the exact lines that changed, and
its full traced path.

```
/generate-report <runId>
```
Prints a full written summary of the run: how many candidates were found, how many turned out
to be real bugs, and the headline numbers.

```
/cite-bug <runId> <fixSha>
```
Prints a ready-to-use citation for one bug, for a paper or report.

---

## 11. If you stop partway through

```
/list-runs
```
Shows every run you've started and how far each one got.

```
/resume <runId>
```
Continues a run exactly where it left off, so you don't need to remember which step you were on.

---

## 12. Other commands

These are for specific situations, not part of the normal flow:

```
/reproduce-bug <runId> <fixSha>     try to reproduce one bug on its own
/promote-bug <runId> <fixSha>       study one bug in depth that wasn't originally selected
/verify-bug <runId> <fixSha>        double-check one bug's data is complete before citing it
/explain-gap <runId> <fixSha>       find out why a specific field is missing for one bug
/export-dataset <runId>             re-write the files from step 9 at any point in the run
/widen-window <runId> --window 24m  search further back in history for more bugs
/estimate-cost <github-url>         get a rough idea of time and scale before starting
/mine-batch <url-1> <url-2> ...     mine several repositories one after another
/fix-checkpoint <runId> <stage> <key>   repair a stuck step if a run reports the same
                                         work as unfinished after it actually completed
```
