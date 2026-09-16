package causeway.mini

import java.io.File
import java.sql.{Connection, DriverManager}

/** Lists runs of the pipeline (`inspect-repo`, `inspect-commits`, or
  * `classify-bugs`), each tagged with which repository and window it
  * belongs to, and the exact JSON file it produced (`source_file`,
  * already recorded on every run table). Exists so a chat command that
  * needs to pick "which evidence/enriched file" can ask the database,
  * which already knows which repo and window each run belongs to,
  * instead of crawling `workspace/exports/<owner>-<repo>/json/`, where
  * a run is still just a `run_<uuid>...` file, with no window or date
  * visible without opening it or checking file timestamps. Also how a
  * person gets back to one specific run they already know the id of
  * (`--run-id`), across every stage that run reached, since the same
  * `run_id` is shared end to end across one mining pass. And, with no
  * flags at all, a `git log`-style browsable history: one row per
  * mining pass, newest first, so a person who doesn't already have a
  * run id in hand can still see what they've run before and when, and
  * pick one to look at more closely with `--run-id`. Purely a read: no
  * network call, no writes.
  */
object ListRuns:

  private val DbPath = "workspace/causeway.db"

  private[mini] case class RunSummary(
      stage: String,
      runId: String,
      owner: String,
      repo: String,
      windowRequested: String,
      windowSinceDate: String,
      sourceFile: String,
      createdAt: String,
      bugTarget: Option[Int] = None,
      examinedCommitCount: Option[Int] = None,
      stoppedEarly: Option[Boolean] = None,
      stagesReached: Option[String] = None
  )

  /** Entry point for the `list-runs` subcommand of the unified `Causeway`
    * CLI (see `Causeway.scala`). Three modes:
    *   - `--run-id <id>`: look up that exact run across all three stage
    *     tables (a mining pass shares one `run_id` end to end, but may
    *     not have reached every stage yet), ignoring `--stage`/`--limit`.
    *   - `--stage inspect-repo|inspect-commits|classify-bugs [--limit N]`:
    *     the most recent N runs of that one stage, newest first.
    *   - neither flag: the most recent N mining passes overall, newest
    *     first, one row per pass (not per stage), each showing which
    *     stages it reached so far, like `git log` for this catalog.
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)

    Class.forName("org.sqlite.JDBC")
    if !new File(DbPath).exists() then
      println("SHOWN=0")
      return
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      val limit = opts.get("limit").map(_.toInt).getOrElse(10)
      val runs = (opts.get("run-id"), opts.get("stage")) match
        case (Some(runId), _) => fetchByRunId(conn, runId)
        case (None, Some(stage)) =>
          stage match
            case "inspect-repo"    => fetchInspectRepoRuns(conn, limit)
            case "inspect-commits" => fetchInspectCommitsRuns(conn, limit)
            case "classify-bugs"   => fetchClassifyBugsRuns(conn, limit)
            case other              => fail(s"Unknown --stage '$other', expected inspect-repo, inspect-commits, or classify-bugs")
        case (None, None) => fetchRunHistory(conn, limit)

      runs.foreach { r =>
        val extra = (r.bugTarget, r.examinedCommitCount, r.stoppedEarly) match
          case (Some(target), Some(examined), Some(stopped)) =>
            s" bugTarget=$target examinedCommitCount=$examined stoppedEarly=$stopped"
          case _ => ""
        val stages = r.stagesReached.map(s => s" stagesReached=$s").getOrElse("")
        println(
          s"RUN stage=${r.stage} run_id=${r.runId} owner=${r.owner} repo=${r.repo} " +
            s"window=${r.windowRequested} since=${r.windowSinceDate} " +
            s"createdAt=${r.createdAt} sourceFile=${r.sourceFile}$extra$stages"
        )
      }
      println(s"SHOWN=${runs.size}")
    finally conn.close()
  end run

  /** One row per mining pass (`run_id`), newest first, anchored on
    * `inspect_repo_runs` since that's where every pass necessarily
    * starts and where `run_id` is minted. `stagesReached` says how far
    * that pass got: `inspect-repo`, or `inspect-repo,inspect-commits`,
    * or all three, checked via `EXISTS` against the other two stage
    * tables rather than joining and risking duplicate/missing rows.
    * This is the "browse my history, then pick one" view; `--run-id`
    * on a row from here gives the full per-stage detail.
    */
  private[mini] def fetchRunHistory(conn: Connection, limit: Int): List[RunSummary] =
    val sql =
      """SELECT ir.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, ir.source_file, ir.created_at,
        |  EXISTS(SELECT 1 FROM inspect_commits_runs ic WHERE ic.run_id = ir.run_id) AS reached_inspect_commits,
        |  EXISTS(SELECT 1 FROM classify_bugs_runs cb WHERE cb.run_id = ir.run_id) AS reached_classify_bugs
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
        val stages = List("inspect-repo") ++
          (if rs.getBoolean("reached_inspect_commits") then List("inspect-commits") else Nil) ++
          (if rs.getBoolean("reached_classify_bugs") then List("classify-bugs") else Nil)
        buf += RunSummary(
          stage = "pass",
          runId = rs.getString("run_id"),
          owner = rs.getString("owner"),
          repo = rs.getString("repo"),
          windowRequested = rs.getString("window_requested"),
          windowSinceDate = rs.getString("window_since_date"),
          sourceFile = rs.getString("source_file"),
          createdAt = rs.getString("created_at"),
          stagesReached = Some(stages.mkString(","))
        )
      buf.toList
    finally ps.close()

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
          stage = "inspect-repo",
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
          stage = "inspect-commits",
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

  /** Every `classify_bugs_runs` row, newest first, joined back through
    * `inspect_commits_runs`/`inspect_repo_runs` to the repository it
    * belongs to (both `LEFT JOIN`s, same nullable-FK reasoning as
    * `fetchInspectCommitsRuns`). Also carries `bug_target`,
    * `examined_commit_count`, and `stopped_early`, since those are the
    * facts someone revisiting a classification run actually wants to
    * see, not just where its file is.
    */
  private[mini] def fetchClassifyBugsRuns(conn: Connection, limit: Int): List[RunSummary] =
    val sql =
      """SELECT cb.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, cb.source_file, cb.created_at,
        |  cb.bug_target, cb.examined_commit_count, cb.stopped_early
        |FROM classify_bugs_runs cb
        |LEFT JOIN inspect_commits_runs ic ON ic.id = cb.inspect_commits_run_id
        |LEFT JOIN inspect_repo_runs ir ON ir.id = ic.inspect_repo_run_id
        |LEFT JOIN repositories r ON r.id = ir.repository_id
        |ORDER BY cb.created_at DESC
        |LIMIT ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[RunSummary]()
      while rs.next() do
        buf += RunSummary(
          stage = "classify-bugs",
          runId = rs.getString("run_id"),
          owner = Option(rs.getString("owner")).getOrElse("?"),
          repo = Option(rs.getString("repo")).getOrElse("?"),
          windowRequested = Option(rs.getString("window_requested")).getOrElse("?"),
          windowSinceDate = Option(rs.getString("window_since_date")).getOrElse("?"),
          sourceFile = rs.getString("source_file"),
          createdAt = rs.getString("created_at"),
          bugTarget = Some(rs.getInt("bug_target")),
          examinedCommitCount = Some(rs.getInt("examined_commit_count")),
          stoppedEarly = Some(rs.getBoolean("stopped_early"))
        )
      buf.toList
    finally ps.close()

  /** Looks up one exact `run_id` across all three stage tables, newest
    * stage last (inspect-repo, then inspect-commits, then classify-bugs),
    * not by recency. A mining pass shares one `run_id` end to end (minted
    * by `/inspect_repo`, carried forward by `/inspect_commits` and
    * `/classify_bugs`), but may not have reached every stage yet, so this
    * returns however many of the three rows actually exist for it, not a
    * fixed count.
    */
  private[mini] def fetchByRunId(conn: Connection, runId: String): List[RunSummary] =
    val ir = fetchOneInspectRepoRun(conn, runId)
    val ic = fetchOneInspectCommitsRun(conn, runId)
    val cb = fetchOneClassifyBugsRun(conn, runId)
    ir.toList ++ ic.toList ++ cb.toList

  private def fetchOneInspectRepoRun(conn: Connection, runId: String): Option[RunSummary] =
    val sql =
      """SELECT ir.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, ir.source_file, ir.created_at
        |FROM inspect_repo_runs ir
        |JOIN repositories r ON r.id = ir.repository_id
        |WHERE ir.run_id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      if !rs.next() then None
      else
        Some(
          RunSummary(
            stage = "inspect-repo",
            runId = rs.getString("run_id"),
            owner = rs.getString("owner"),
            repo = rs.getString("repo"),
            windowRequested = rs.getString("window_requested"),
            windowSinceDate = rs.getString("window_since_date"),
            sourceFile = rs.getString("source_file"),
            createdAt = rs.getString("created_at")
          )
        )
    finally ps.close()

  private def fetchOneInspectCommitsRun(conn: Connection, runId: String): Option[RunSummary] =
    val sql =
      """SELECT ic.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, ic.source_file, ic.created_at
        |FROM inspect_commits_runs ic
        |LEFT JOIN inspect_repo_runs ir ON ir.id = ic.inspect_repo_run_id
        |LEFT JOIN repositories r ON r.id = ir.repository_id
        |WHERE ic.run_id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      if !rs.next() then None
      else
        Some(
          RunSummary(
            stage = "inspect-commits",
            runId = rs.getString("run_id"),
            owner = Option(rs.getString("owner")).getOrElse("?"),
            repo = Option(rs.getString("repo")).getOrElse("?"),
            windowRequested = Option(rs.getString("window_requested")).getOrElse("?"),
            windowSinceDate = Option(rs.getString("window_since_date")).getOrElse("?"),
            sourceFile = rs.getString("source_file"),
            createdAt = rs.getString("created_at")
          )
        )
    finally ps.close()

  private def fetchOneClassifyBugsRun(conn: Connection, runId: String): Option[RunSummary] =
    val sql =
      """SELECT cb.run_id, r.owner, r.repo, ir.window_requested, ir.window_since_date, cb.source_file, cb.created_at,
        |  cb.bug_target, cb.examined_commit_count, cb.stopped_early
        |FROM classify_bugs_runs cb
        |LEFT JOIN inspect_commits_runs ic ON ic.id = cb.inspect_commits_run_id
        |LEFT JOIN inspect_repo_runs ir ON ir.id = ic.inspect_repo_run_id
        |LEFT JOIN repositories r ON r.id = ir.repository_id
        |WHERE cb.run_id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      if !rs.next() then None
      else
        Some(
          RunSummary(
            stage = "classify-bugs",
            runId = rs.getString("run_id"),
            owner = Option(rs.getString("owner")).getOrElse("?"),
            repo = Option(rs.getString("repo")).getOrElse("?"),
            windowRequested = Option(rs.getString("window_requested")).getOrElse("?"),
            windowSinceDate = Option(rs.getString("window_since_date")).getOrElse("?"),
            sourceFile = rs.getString("source_file"),
            createdAt = rs.getString("created_at"),
            bugTarget = Some(rs.getInt("bug_target")),
            examinedCommitCount = Some(rs.getInt("examined_commit_count")),
            stoppedEarly = Some(rs.getBoolean("stopped_early"))
          )
        )
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
