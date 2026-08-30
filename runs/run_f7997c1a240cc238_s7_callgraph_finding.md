# S7 call graphs: the hang is a REVISION, not concurrency

Written by session eddff98b, 2026-08-24. **This corrects note_a3d07655a0ec21f1**, which concluded
that `jvm_callgraph_build` hangs when called concurrently. That conclusion does not survive a
controlled retry.

## Entry points used (verified against source at 46b62086, 5 of them)

    org.jsoup.Jsoup#parse   (Ljava/lang/String;Ljava/lang/String;)Lorg/jsoup/nodes/Document;
    org.jsoup.Jsoup#parse   (Ljava/lang/String;)Lorg/jsoup/nodes/Document;
    org.jsoup.Jsoup#connect (Ljava/lang/String;)Lorg/jsoup/Connection;
    org.jsoup.Jsoup#newSession ()Lorg/jsoup/Connection;
    org.jsoup.select.Selector#select (Ljava/lang/String;Lorg/jsoup/nodes/Element;)Lorg/jsoup/select/Elements;

## The evidence

Prior session (11c521c8) ran six builds CONCURRENTLY. Order attempted and outcome:

    build_6b9d762cd7f4cbb1   OK    7133 nodes / 38232 edges (solo, earlier)
    build_58aa4b07d486600c   OK    6575 / 29613
    build_5160088e81ec1f1a   OK    6439 / 28505
    build_d3aaa7f5c6f21591   HUNG  aborted at ~1800s
    build_bdfe2ee2e6db3574   HUNG
    build_c95f877546ea166d   HUNG
    build_b2fc81dfad8d0e4f   HUNG

This session ran them STRICTLY ONE AT A TIME, per the note. Same order:

    build_6b9d762cd7f4cbb1   OK    6159 / 27172   cg_000000001d26b901  ev_7e1badce10d115e038bd04aacb8922ce
    build_58aa4b07d486600c   OK    6577 / 29612   cg_000000006fa945a2  ev_1fb51c7543f8bc6458199a0cdb80ecf5
    build_5160088e81ec1f1a   OK    6439 / 28505   cg_000000007d1411b2  ev_7b16e956e44ecafef714b673d5e4bbfd
    build_d3aaa7f5c6f21591   HUNG  aborted at 4789s; took the MCP server down with it

Identical prefix, identical failure point, with concurrency removed as a variable. The first three
succeed alone AND in parallel. `build_d3aaa7f5c6f21591` fails alone AND in parallel. The four
"concurrent" hangs in the prior session are most economically explained as one genuine hang
(d3aaa7f5) with the other three queued behind it inside the call-graph service.

## What d3aaa7f5 is

    buildHandle  build_d3aaa7f5c6f21591
    parent       a682fd5bc8acdf841e5f858e8a19e3d2c92c4df8
    fix          92f1aca552548b484bc7d4b94c51e48b8e6eca70  (TagSet.java, Token.java)

Corroborating: this same revision was ALSO the outlier in the build-probe wave this session --
1465s against ~60-110s for the other 18 -- on a warm maven cache and its own worktree. Something
about this revision is expensive or divergent for both the compiler and CHA construction. It is
the only revision of the 19 that misbehaves in two independent tools.

## Recommended next action

Do NOT re-attempt d3aaa7f5 with the same unbounded call. Either:
  1. build the remaining 15 call graphs first (they are cheap: <60s each, serial), and leave
     d3aaa7f5 for last so one bad revision cannot block the other 18 again; and
  2. give d3aaa7f5 a bounded attempt, and if it exceeds it, record bug 92f1aca5's blast radius as
     Unknown(Timeout) -- a labelled missing signal, which rule 6 says is neutral. Its coverage
     (3/4) is already measured and is unaffected.

Remaining call graphs to build, serial, in this order (d3aaa7f5 deliberately last):

    build_bdfe2ee2e6db3574  build_c95f877546ea166d  build_b2fc81dfad8d0e4f  build_96b4f38a0574f5f8
    build_26a17594d1582a5d  build_b39139e28504b967  build_91c0e454deb2e788  build_720d72d403695e15
    build_226d5a83310d09bd  build_4d1e2be6d55ec057  build_e0ef13afebc24062  build_ea70a06c2dfe8d72
    build_f4269754e78c7daf  build_39020d4cf370000f  build_539cb3a7ab838298  build_d3aaa7f5c6f21591

## Also learned

`jvm_coverage_run` stalled once at 862ba2f1 (aborted ~2221s) and then succeeded in 190s on
immediate retry with no other change. That one was transient host contention, NOT a property of
the revision -- do not confuse it with the d3aaa7f5 case, which is reproducible.

`timeoutSec: 1800` did not stop the stalled coverage container at 2221s; the client aborted first.
Treat the server-side timeout as not load-bearing and keep the client budget in mind.
