---
description: Check the environment is actually ready before starting a mining run
argument-hint: (no arguments)
allowed-tools: Bash, Task, mcp__causeway__*
---

This command is the one deliberate exception to the "no raw tools" rule every other mining
command carries. Its entire job is diagnosing the environment OUTSIDE the causeway MCP surface —
Docker, Neo4j, GitHub auth — none of which any causeway tool can see, because they all assume
the server is already up. You still must not use `Bash` to read or edit this project's source;
use it only for the read-only diagnostic commands below.

Check four things, in order, and stop reporting each one as soon as it fails rather than letting
a downstream check produce a confusing secondary error:

**1. Docker.** `docker info` and check the exit code. If it fails: "Docker is not running. Start
Docker Desktop, then run `/mine-doctor` again." Stop here.

**2. Neo4j.** `docker ps --format '{{.Names}}\t{{.Status}}'`, find the causeway Neo4j container.
If it is not running: "Neo4j container is not up." If it is running but its status still says
`health: starting`, do not treat that as a failure — the healthcheck lags real readiness; instead
try a bolt connection directly (the same retry the server itself does) before concluding
anything.

**3. The causeway server.** Call any cheap causeway tool, such as `graph_list_runs`. If it
returns data, the server is up and connected to Neo4j. If the tool call itself is unavailable,
the MCP server has not attached this session — say plainly that the window needs reloading, since
a server cannot attach mid-session. If the tool call resolves but every run reports as though
nothing was ever persisted, warn that this may mean the server fell back to an in-memory ledger
(no Neo4j at startup) rather than the persistent one, and that this is silent by design elsewhere
in this project, so it needs stating explicitly here.

**4. GitHub authentication.** This cannot be checked without a tool that verifies a token against
the API — if no causeway tool exposes that, say plainly that authentication cannot be checked
from here and will surface at `/mine-init`'s S3 bundling step instead if it is wrong, rather than
claiming a check that was not actually performed.

---

## What this checked
State each of the four results plainly: pass, fail, or not checkable, with the specific reason
for any fail. Do not summarize as "environment looks fine" if any check could not actually run.

## Next
```
If everything passed:  Run: /mine-init <github-url>
If a run is already in progress:  Run: /resume
If anything failed: fix the specific thing named above, then run: /mine-doctor
```
