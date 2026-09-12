package causeway.mini

import java.io.File
import java.sql.{Connection, DriverManager}

/** Lists repositories that currently qualify (see `QualifyRepos`), across
  * every search ever run, not just the most recent one — that's the whole
  * point of storing every discovered repository rather than only the one
  * a person goes on to mine. Purely a read: no network call, no writes.
  * Database access belongs here, in the CLI, the same way GitHub access
  * does, `/list_qualifying_repos` never runs SQL of its own.
  */
object ListQualifyingRepos:

  private val DbPath = "workspace/causeway.db"

  /** Entry point for the `list-qualifying-repos` subcommand of the unified
    * `Causeway` CLI (see `Causeway.scala`).
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val limit = opts.get("limit").map(_.toInt).getOrElse(fail("--limit is required"))

    Class.forName("org.sqlite.JDBC")
    if !new File(DbPath).exists() then
      println("TOTAL_QUALIFYING=0")
      println("SHOWN=0")
      return
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      val totalQualifying = countQualifying(conn)
      val rows = fetchTopQualifying(conn, limit)

      rows.foreach { r =>
        println(
          s"REPO owner=${r.owner} repo=${r.repo} stars=${r.stars.getOrElse("?")} " +
            s"language=${r.language.getOrElse("?")} sizeKb=${r.sizeKb.getOrElse("?")} " +
            s"closedIssues=${r.closedIssueCount} openIssues=${r.openIssueCount} " +
            s"mergedPrs=${r.mergedPrCount} openPrs=${r.openPrCount} testFiles=${r.testFileCount} " +
            s"alreadyMined=${r.alreadyMined} url=${r.url}"
        )
      }

      println(s"TOTAL_QUALIFYING=$totalQualifying")
      println(s"SHOWN=${rows.size}")
    finally conn.close()
  end run

  private[mini] case class QualifyingRepo(
      owner: String,
      repo: String,
      url: String,
      stars: Option[Int],
      language: Option[String],
      sizeKb: Option[Int],
      closedIssueCount: Int,
      openIssueCount: Int,
      mergedPrCount: Int,
      openPrCount: Int,
      testFileCount: Int,
      alreadyMined: Boolean
  )

  private[mini] def countQualifying(conn: Connection): Int =
    val stmt = conn.createStatement()
    try
      // "Qualifies" has no stored column, it's exactly rejection_reason IS
      // NULL (and has actually been checked at all, checked_at IS NOT NULL,
      // so a never-qualified repository with both columns NULL isn't
      // miscounted as qualifying).
      val rs = stmt.executeQuery("SELECT COUNT(*) FROM repositories WHERE checked_at IS NOT NULL AND rejection_reason IS NULL")
      rs.next()
      rs.getInt(1)
    finally stmt.close()

  /** Every currently-qualifying repository, most starred first, at most
    * `limit` of them. `current_stars`/`current_language`/`current_size_kb`
    * on `repositories` are the single source of truth for "what does this
    * repo currently look like" (see `Store.upsertRepository`), kept fresh
    * by whichever of `search-repos`/`qualify-repos` last saw the repo, so
    * no join over `search_repos_run_repositories` is needed here. Qualification
    * counts (issues/PRs/tests) live directly on `repositories` too (see
    * `schema.sql`).
    */
  private[mini] def fetchTopQualifying(conn: Connection, limit: Int): List[QualifyingRepo] =
    val sql =
      """SELECT r.owner, r.repo, r.url, r.closed_issue_count, r.open_issue_count,
        |  r.merged_pr_count, r.open_pr_count, r.test_file_count,
        |  r.current_stars, r.current_language, r.current_size_kb,
        |  EXISTS (SELECT 1 FROM inspect_repo_runs ir WHERE ir.repository_id = r.id) AS already_mined
        |FROM repositories r
        |WHERE r.checked_at IS NOT NULL AND r.rejection_reason IS NULL
        |ORDER BY r.current_stars DESC
        |LIMIT ?""".stripMargin

    val ps = conn.prepareStatement(sql)
    try
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[QualifyingRepo]()
      while rs.next() do
        // wasNull() reflects only the most recently read column, so each
        // nullable int must be checked immediately after its own getInt,
        // before any other column is read.
        val starsValue = rs.getInt("current_stars")
        val starsWasNull = rs.wasNull()
        val sizeKbValue = rs.getInt("current_size_kb")
        val sizeKbWasNull = rs.wasNull()
        val language = rs.getString("current_language")
        buf += QualifyingRepo(
          owner = rs.getString("owner"),
          repo = rs.getString("repo"),
          url = rs.getString("url"),
          stars = if starsWasNull then None else Some(starsValue),
          language = Option(language),
          sizeKb = if sizeKbWasNull then None else Some(sizeKbValue),
          closedIssueCount = rs.getInt("closed_issue_count"),
          openIssueCount = rs.getInt("open_issue_count"),
          mergedPrCount = rs.getInt("merged_pr_count"),
          openPrCount = rs.getInt("open_pr_count"),
          testFileCount = rs.getInt("test_file_count"),
          alreadyMined = rs.getBoolean("already_mined")
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

end ListQualifyingRepos
