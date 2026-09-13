package causeway.mini

import java.io.File
import java.sql.{Connection, DriverManager}

/** Lists recent runs of one pipeline stage (`inspect-repo` or
  * `inspect-commits`), each tagged with which repository and window it
  * belongs to, and the exact JSON file it produced (`source_file`,
  * already recorded on every run table). Exists so a chat command that
  * needs to pick "which evidence/enriched file" can ask the database,
  * which already knows which repo and window each run belongs to,
  * instead of blindly listing `workspace/exports/`, where every file is
  * named only `run_<uuid>...`, with no repo or window visible in the
  * filename itself. Purely a read: no network call, no writes.
  */
object ListRuns:

  private val DbPath = "workspace/causeway.db"

  private[mini] case class RunSummary(
      runId: String,
      owner: String,
      repo: String,
      windowRequested: String,
      windowSinceDate: String,
      sourceFile: String,
      createdAt: String
  )

  /** Entry point for the `list-runs` subcommand of the unified `Causeway`
    * CLI (see `Causeway.scala`).
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val stage = opts.getOrElse("stage", fail("--stage is required (inspect-repo or inspect-commits)"))
    val limit = opts.get("limit").map(_.toInt).getOrElse(10)

    Class.forName("org.sqlite.JDBC")
    if !new File(DbPath).exists() then
      println("SHOWN=0")
      return
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      val runs = stage match
        case "inspect-repo"    => fetchInspectRepoRuns(conn, limit)
        case "inspect-commits" => fetchInspectCommitsRuns(conn, limit)
        case other              => fail(s"Unknown --stage '$other', expected inspect-repo or inspect-commits")

      runs.foreach { r =>
        println(
          s"RUN run_id=${r.runId} owner=${r.owner} repo=${r.repo} " +
            s"window=${r.windowRequested} since=${r.windowSinceDate} " +
            s"createdAt=${r.createdAt} sourceFile=${r.sourceFile}"
        )
      }
      println(s"SHOWN=${runs.size}")
    finally conn.close()
  end run

  /** Every `inspect_repo_runs` row, newest first, joined to the repository
    * it mined. This is what `/inspect_commits` uses to pick an evidence
    * file when one isn't already known from earlier in the session.
    */
  private[mini] def fetchInspectRepoRuns(conn: Connection, limit: Int): List[RunSummary] =
    val sql =
      """SELECT ir.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, ir.source_file, ir.created_at
        |FROM inspect_repo_runs ir
        |JOIN repositories r ON r.id = ir.repository_id
        |ORDER BY ir.created_at DESC
        |LIMIT ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[RunSummary]()
      while rs.next() do
        buf += RunSummary(
          runId = rs.getString("run_id"),
          owner = rs.getString("owner"),
          repo = rs.getString("repo"),
          windowRequested = rs.getString("window_requested"),
          windowSinceDate = rs.getString("window_since_date"),
          sourceFile = rs.getString("source_file"),
          createdAt = rs.getString("created_at")
        )
      buf.toList
    finally ps.close()

  /** Every `inspect_commits_runs` row, newest first, joined back through
    * the `inspect_repo_runs` row it enriched to the repository it belongs
    * to. This is what `/classify_bugs` uses to pick an enriched file. The
    * join to `inspect_repo_runs`/`repositories` is a `LEFT JOIN`, not an
    * inner one: `inspect_commits_runs.inspect_repo_run_id` is nullable
    * (an enriched file stored before its evidence file exists, or a
    * fabricated/hand-built one for testing, would leave it unset), and an
    * enrichment run is still real and worth listing even without a
    * resolvable parent, just with `owner`/`repo`/window shown as `?`
    * rather than silently dropped by an inner join.
    */
  private[mini] def fetchInspectCommitsRuns(conn: Connection, limit: Int): List[RunSummary] =
    val sql =
      """SELECT ic.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, ic.source_file, ic.created_at
        |FROM inspect_commits_runs ic
        |LEFT JOIN inspect_repo_runs ir ON ir.id = ic.inspect_repo_run_id
        |LEFT JOIN repositories r ON r.id = ir.repository_id
        |ORDER BY ic.created_at DESC
        |LIMIT ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[RunSummary]()
      while rs.next() do
        buf += RunSummary(
          runId = rs.getString("run_id"),
          owner = Option(rs.getString("owner")).getOrElse("?"),
          repo = Option(rs.getString("repo")).getOrElse("?"),
          windowRequested = Option(rs.getString("window_requested")).getOrElse("?"),
          windowSinceDate = Option(rs.getString("window_since_date")).getOrElse("?"),
          sourceFile = rs.getString("source_file"),
          createdAt = rs.getString("created_at")
        )
      buf.toList
    finally ps.close()

  private def parseArgs(args: Array[String]): Map[String, String] =
    val map = scala.collection.mutable.Map[String, String]()
    var i = 0
    while i < args.length do
      val a = args(i)
      if a.startsWith("--") && i + 1 < args.length then
        map(a.drop(2)) = args(i + 1)
        i += 2
      else i += 1
    map.toMap

  private def fail(msg: String): Nothing =
    System.err.println(s"ERROR: $msg")
    sys.exit(1)

end ListRuns
