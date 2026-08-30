package causeway.app

import causeway.core.{InMemoryEvidenceLedger, RunId}
import causeway.forge.ForgeClient
import causeway.graphstore.{Neo4jEvidenceLedger, Neo4jStore, NoteStore}
import causeway.mcp.CausewayServer

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Entry point registered in `.mcp.json`.
  *
  * Wiring only. The rules live in `ToolRegistry`, the protocol in `ToolBridge`, and the work in
  * the service modules.
  */
object Main:

  /** Read `.env` from the working directory, WITHOUT overriding anything already set.
    *
    * `.mcp.json` refers to `${NEO4J_PASSWORD}` and `${GITHUB_TOKEN}`, which resolve from the
    * environment Claude Code itself was launched in. Start Claude Code from a shell that never
    * sourced `.env` and they are simply absent — the server then falls back to an in-memory
    * ledger and announces it on stderr, where nobody sees it. A whole run's findings are
    * accepted and then lost at exit.
    *
    * Reading the file here removes that failure mode entirely. Precedence is decided in ONE
    * place, `env` below — filtering here as well meant a placeholder in the environment counted
    * as "already set" and suppressed the real value from the file.
    */
  private def loadDotEnv(): Map[String, String] =
    val f = Paths.get(".env")
    if !Files.isRegularFile(f) then Map.empty
    else
      Files.readAllLines(f).asScala.toVector
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#") && l.contains("="))
        .map { l =>
          val i = l.indexOf('=')
          l.substring(0, i).trim -> l.substring(i + 1).trim.stripPrefix("\"").stripSuffix("\"")
        }
        .toMap

  def main(args: Array[String]): Unit =
    val dotEnv = loadDotEnv()

    /** A value that is still a `${...}` placeholder never expanded.
      *
      * `.mcp.json` refers to `${GITHUB_TOKEN}`. When that variable is absent from the
      * environment Claude Code itself was launched in, the placeholder can survive VERBATIM —
      * a perfectly non-empty string that is not a credential. Treating "non-empty" as "set"
      * sent the literal text as a Bearer token and every forge call came back 401.
      */
    def unexpanded(v: String): Boolean = v.startsWith("${") && v.endsWith("}")

    def env(k: String): Option[String] =
      sys.env.get(k).filter(v => v.nonEmpty && !unexpanded(v))
        .orElse(dotEnv.get(k).filter(_.nonEmpty))

    val schemasDir = Paths.get(env("CAUSEWAY_SCHEMAS").getOrElse("schemas"))
    val workspace  = Paths.get(env("CAUSEWAY_WORKSPACE").getOrElse("./workspace"))

    val runId = env("CAUSEWAY_RUN_ID")
      .flatMap(s => RunId(s).toOption)
      .getOrElse(RunId.unsafe(f"run_${System.nanoTime() & 0xffffffffffffL}%012x0000"))

    val handles = Handles(workspace)

    // A persistent ledger when Neo4j is reachable, in-memory otherwise. The evidence check
    // works either way; only its survival across a restart differs. Falling back is announced
    // on stderr rather than silently degrading, because a run whose evidence vanishes on
    // restart is a materially different run.
    val neo4j = causeway.graphstore.Neo4jConfig(
      uri = env("NEO4J_URI").getOrElse("bolt://localhost:7690"),
      user = env("NEO4J_USER").getOrElse("neo4j"),
      password = env("NEO4J_PASSWORD").getOrElse("causeway-dev")
    )

    // Wait for the database rather than deciding on one attempt. See Neo4jStore.connectWaiting:
    // the MCP client starts this server the instant a session opens, which after a host reboot
    // is well before the container is healthy, and losing that race silently costs the whole
    // run's evidence.
    val (ledger, store, note) = Neo4jStore.connectWaiting(
      neo4j,
      onRetry = (n, err) => System.err.println(s"waiting for Neo4j (attempt $n): $err")
    ) match
      case Right(s) =>
        s.initialise()
        (Neo4jEvidenceLedger(s), Some(s), "evidence ledger: Neo4j (persistent)")
      case Left(err) =>
        (InMemoryEvidenceLedger(), None, s"evidence ledger: in-memory — Neo4j unreachable ($err)")

    // Notes live on the filesystem, deliberately NOT in the evidence graph.
    val notes = NoteStore(Paths.get(env("CAUSEWAY_NOTES").getOrElse("notes")))

    val config   = CausewayServer.Config(schemasDir, runId)
    // Absent credentials degrade the forge tools into a recorded gap rather than stopping the
    // server: git-level mining still works without GitHub.
    // Verified, not assumed. One request at startup buys the difference between "a token is
    // present" and "the token works", and the run is long enough that finding out later is
    // expensive.
    val forge = env("GITHUB_TOKEN").map(causeway.forge.ForgeClient(_))
    val forgeStatus = forge match
      case None => "github: no token found in environment or .env — forge tools will report gaps"
      case Some(c) =>
        c.verify() match
          case Right(login) => s"github: authenticated as $login"
          case Left(err)    => s"github: TOKEN REJECTED — ${err.explain.take(120)}"

    val handlers =
      VcsHandlers.all(handles) ++
        JvmHandlers.all(handles) ++
        AnalysisHandlers.all(handles) ++
        MetricsHandlers.all(handles) ++
        ForgeHandlers(handles, forge).all ++
        GraphHandlers(store, handles).all ++
        RecordHandlers(ledger, notes, store).all

    CausewayServer.buildRegistry(config, handlers, ledger) match
      case Left(problems) =>
        System.err.println("causeway failed to start:")
        problems.foreach(p => System.err.println(s"  - $p"))
        sys.exit(1)

      case Right(registry) =>
        System.err.println(s"causeway run ${runId.value}")
        System.err.println(note)
        System.err.println(forgeStatus)
        sys.addShutdownHook(handles.closeAll())
        CausewayServer.serve(registry, config)
