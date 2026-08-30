#!/bin/sh
# Swap a staged distribution into target/dist.
#
# Why this exists: on Windows a JAR held open by a running JVM cannot be deleted or renamed, so
# `sbt app/dist` fails PARTWAY THROUGH while the live MCP server is up — leaving a half-populated
# lib directory that starts but serves the wrong thing. Building to CAUSEWAY_DIST_DIR and swapping
# afterwards keeps the live server intact until the moment it is replaced.
#
#   sbt -batch "app/distTo target/dist-next"
#   tools/promote-dist.sh
#
# The target is an ARGUMENT, not an environment variable: sbt 2 keeps a server alive between
# invocations, and that server has the environment it was started with, so a client-side export
# is invisible to the build.
#
# Exits 1 if the swap is blocked; the staged build is left untouched so it can be retried once
# the server is stopped (Claude Code restarts MCP servers when a session restarts).
set -e
cd "$(dirname "$0")/.."

[ -d target/dist-next ] || { echo "nothing staged at target/dist-next"; exit 1; }

if [ -d target/dist ]; then
  mv target/dist target/dist-old 2>/dev/null || {
    echo "blocked: target/dist is held open by a running server."
    echo "stop it, then re-run this script. Live processes:"
    powershell -NoProfile -Command       "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*causeway.app.Main*' } | Select-Object ProcessId, CommandLine | Format-List"       2>/dev/null || true
    exit 1
  }
fi

# ROLL BACK if the second move fails. Without this the first move has already happened and
# target/dist simply does not exist — which is worse than not promoting, because the next
# session starts a server against a missing classpath. Seen for real: the swap failed with
# EPERM (a Windows handle that had not been released yet) and left no dist at all.
if ! mv target/dist-next target/dist 2>/dev/null; then
  echo "could not move the staged build into place."
  if [ -d target/dist-old ]; then
    mv target/dist-old target/dist && echo "rolled back: target/dist is the previous build, intact."
  fi
  echo "the staged build is still at target/dist-next; retry once nothing holds it."
  exit 1
fi

rm -rf target/dist-old
echo "promoted: target/dist <- target/dist-next ($(ls target/dist/lib | wc -l | tr -d ' ') jars)"
