#!/usr/bin/env python3
"""PreToolUse hook (matcher: Bash).

Generic version of the repo-discovery-only restriction: any subagent can
declare `allowedBashPattern: "<regex>"` in its own .claude/agents/<name>.md
frontmatter to have its Bash calls restricted to commands matching that
pattern, enforced here rather than by instruction alone. Adding a new
restricted agent in the future means adding that one frontmatter field to
its own definition file, never touching this script.

An agent with no `allowedBashPattern` (or no Bash tool at all) is left
completely alone by this hook, same as every main-session Bash call
(which has no `agent_type` at all).
"""
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    yaml = None

AGENTS_DIR = Path(".claude/agents")
SAFE_NAME = re.compile(r"^[A-Za-z0-9_-]+$")


def load_allowed_pattern(agent_type: str) -> str | None:
    if not SAFE_NAME.match(agent_type) or yaml is None:
        return None
    agent_file = AGENTS_DIR / f"{agent_type}.md"
    if not agent_file.is_file():
        return None
    text = agent_file.read_text(encoding="utf-8")
    if not text.startswith("---"):
        return None
    end = text.find("\n---", 3)
    if end == -1:
        return None
    frontmatter = yaml.safe_load(text[3:end]) or {}
    if not isinstance(frontmatter, dict):
        return None
    pattern = frontmatter.get("allowedBashPattern")
    return pattern if isinstance(pattern, str) else None


def main() -> None:
    try:
        data = json.load(sys.stdin)
    except Exception:
        # Malformed input is not this hook's problem to enforce against;
        # fail open rather than blocking unrelated tool calls.
        return

    agent_type = data.get("agent_type")
    if not agent_type:
        return  # main session, not a subagent call at all

    pattern = load_allowed_pattern(agent_type)
    if pattern is None:
        return  # this agent has no declared restriction

    command = data.get("tool_input", {}).get("command", "")
    if re.match(pattern, command):
        return

    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": (
                        f"'{agent_type}' may only run Bash commands matching "
                        f"{pattern!r} (declared in .claude/agents/{agent_type}.md's "
                        f"allowedBashPattern). Refused: {command!r}"
                    ),
                }
            }
        )
    )


if __name__ == "__main__":
    main()
