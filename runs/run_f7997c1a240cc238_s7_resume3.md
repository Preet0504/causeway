# S7 state after session e39d57d5 (2026-08-24). READ THIS BEFORE RESUMING run_f7997c1a240cc238.

## The blocker, and the rule that follows from it

A causeway server process completes exactly THREE jvm_callgraph_build calls. The fourth hangs
forever, and from that moment every JVM-path tool in that process hangs forever too --
jvm_method_at_line included. Only a server RESTART clears it. See note_1a17e350ba889e71, which
supersedes note_a3d07655a0ec21f1 (concurrency) and corrects
run_f7997c1a240cc238_s7_callgraph_finding.md (bad revision). Both prior explanations are refuted:
serial execution does not help, and re-running an already-successful handle at position 5 hangs.

CONSEQUENCE: do not plan a 19-graph sweep. It cannot finish. Budget 3 JVM-heavy calls per server
lifetime and spend them on bugs already selected.

## What this session completed

All 19 parent build probes re-ran and SUCCEEDED (53-85s each at width 5). buildHandles are
deterministic and reproduced EXACTLY as predicted -- the table in
run_f7997c1a240cc238_s7_resume.tsv remains correct and is the authoritative input list.
jvm_build_probe is NOT affected by the wedge; probing is safe and cheap on a warm cache.

Three call graphs built (these cg_ ids died with the process; sizes recorded for reference only):
    build_6b9d762cd7f4cbb1  ->  6575 nodes / 29142 edges   ev_837b982a328b504b5019ab7f111f5a62
    build_58aa4b07d486600c  ->  6573 / 29606               ev_15e3f5bcaf2cf24da18ff092b7639e4a
    build_5160088e81ec1f1a  ->  6439 / 28505               ev_9cdb2f06d349bd98c081b957c202494b

## The ranking problem this run has NOT solved

rank_importance over coverage alone (ev_93009b4e6d0432c138b3cca6cf7320ac, all 19 bugs):
SIXTEEN of nineteen tie at composite 1.0. Ranks 1-16 are an alphabetical tiebreak, not merit.
Only three bugs separate at all:

    b6fabf85  0.931   (27/29 lines covered)
    92f1aca5  0.750   (3/4)
    38ea7f89  0.500   (1/2)

jsoup's suite covers essentially every changed line, so faultLineCoverage has almost NO
discriminating power on this repo. Blast radius and symptom distance carry the entire ranking
signal -- which makes the wedged call-graph service a bigger problem than it first appears. Do
NOT take a coverage-only top-5; it would be an arbitrary alphabetical slice.

## Recommended order for the next session

1. Restart causeway. Confirm Neo4j answers on bolt://localhost:7690 BEFORE it starts, and that
   exactly one causeway.app.Main is running (see note_378bbab6b5f67656 -- the ledger is chosen
   once at startup and never reconnects; a server started first persists NOTHING).
2. Run S9 (symptom-characterizer) FIRST, before any call graph. It needs forge_* and history_*,
   which are unaffected by the wedge, and it yields symptomDistance -- the dimension that can
   actually break the 16-way tie. Watch that it does not spend the JVM budget on
   jvm_tests_for_change.
3. Re-rank on coverage + symptom distance. NOW pick the --deep 5.
4. Only then spend call-graph budget: 3 graphs per server process, restarting between batches,
   on the selected bugs only.

## Coverage reports

All 19 reportIds from the prior session are unrecoverable (parsed into an in-memory TrieMap,
never written to disk). They do NOT need re-deriving to be TRUSTED -- the 19 fractions in
run_f7997c1a240cc238_s7_resume2.tsv each carry a valid ledger evidence id and rank_importance
accepts them as plain numbers. Re-run coverage only for bugs whose reports must be QUERIED
(i.e. the --deep selection at S11), not for all 19.

## Data-quality caveat

The same buildHandle yields different graph sizes across server processes: build_6b9d762cd7f4cbb1
gave 6159/27172 last session and 6575/29142 this one, ~7% apart, with identical entry points and
algorithm. Do not compare blast radius across processes.
