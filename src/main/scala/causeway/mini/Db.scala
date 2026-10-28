package causeway.mini

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.io.Source
import scala.util.Using

final case class RunRow(
    id: String, createdAt: String, fullName: String, url: String, branch: Option[String],
    pinnedSha: String, localPath: String, since: Option[String], scanLimit: Int, enrichedAt: Option[String]
)

final case class CommitRow(
    sha: String, authoredAt: String, message: String, files: List[String], diff: String, diffTruncated: Boolean
)

final case class LinkRow(
    kind: String, number: Int, title: String, body: String, state: String, url: String, how: String
)

/** The only code that talks SQL. Agents never reach this; only CLI subcommands do. */
final class Db private (val conn: Connection) extends AutoCloseable:
  import Db.*

  def update(sql: String, params: Any*): Int =
    Using.resource(conn.prepareStatement(sql)) { ps => bind(ps, params); ps.executeUpdate() }

  def query[A](sql: String, params: Any*)(read: ResultSet => A): List[A] =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      bind(ps, params)
      Using.resource(ps.executeQuery()) { rs =>
        val out = List.newBuilder[A]
        while rs.next() do out += read(rs)
        out.result()
      }
    }

  def tx[A](body: => A): A =
    conn.setAutoCommit(false)
    try
      val result = body
      conn.commit()
      result
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.setAutoCommit(true)

  def close(): Unit = conn.close()

  // ---- searches and repos -------------------------------------------------

  def saveSearch(queryText: String, checked: List[Checked], now: String): Long = tx {
    val qualifying = checked.count(_.rejection.isEmpty)
    update("INSERT INTO searches(created_at, query, checked, qualifying) VALUES (?,?,?,?)",
      now, queryText, checked.size, qualifying)
    val searchId = query("SELECT last_insert_rowid()")(_.getLong(1)).head
    checked.foreach { ch =>
      val c = ch.candidate
      update(
        """INSERT INTO repos(full_name, url, description, language, stars, forks, size_kb, pushed_at,
          |                  default_branch, closed_issues, merged_prs, rejection_reason, last_search_id, checked_at)
          |VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          |ON CONFLICT(full_name) DO UPDATE SET
          |  url=excluded.url, description=excluded.description, language=excluded.language,
          |  stars=excluded.stars, forks=excluded.forks, size_kb=excluded.size_kb,
          |  pushed_at=excluded.pushed_at, default_branch=excluded.default_branch,
          |  closed_issues=excluded.closed_issues, merged_prs=excluded.merged_prs,
          |  rejection_reason=excluded.rejection_reason, last_search_id=excluded.last_search_id,
          |  checked_at=excluded.checked_at""".stripMargin,
        c.fullName, c.url, c.description, c.language, c.stars, c.forks, c.sizeKb, c.pushedAt,
        c.defaultBranch, ch.closedIssues, ch.mergedPrs, ch.rejection, searchId, now)
    }
    searchId
  }

  def qualifyingRepos(limit: Int): List[ujson.Value] =
    query(
      """SELECT full_name, url, description, language, stars, forks, size_kb, pushed_at,
        |       default_branch, closed_issues, merged_prs, checked_at
        |FROM repos WHERE rejection_reason IS NULL
        |ORDER BY stars DESC, full_name LIMIT ?""".stripMargin, limit) { rs =>
      ujson.Obj(
        "full_name" -> rs.getString(1), "url" -> rs.getString(2),
        "description" -> Json.str(optString(rs, 3)), "language" -> Json.str(optString(rs, 4)),
        "stars" -> rs.getInt(5), "forks" -> rs.getInt(6), "size_kb" -> rs.getInt(7),
        "pushed_at" -> Json.str(optString(rs, 8)), "default_branch" -> Json.str(optString(rs, 9)),
        "closed_issues" -> Json.num(optInt(rs, 10)), "merged_prs" -> Json.num(optInt(rs, 11)),
        "checked_at" -> rs.getString(12))
    }

  // ---- runs and commits ---------------------------------------------------

  def createRun(run: RunRow, commits: List[CommitRow]): Unit = tx {
    update(
      """INSERT INTO runs(id, created_at, full_name, url, branch, pinned_sha, local_path, since, scan_limit)
        |VALUES (?,?,?,?,?,?,?,?,?)""".stripMargin,
      run.id, run.createdAt, run.fullName, run.url, run.branch, run.pinnedSha, run.localPath,
      run.since, run.scanLimit)
    commits.foreach { c =>
      update(
        "INSERT INTO commits(run_id, sha, authored_at, message, files, diff, diff_truncated) VALUES (?,?,?,?,?,?,?)",
        run.id, c.sha, c.authoredAt, c.message, ujson.write(Json.arr(c.files.map(ujson.Str(_)))),
        c.diff, c.diffTruncated)
    }
  }

  def run(id: String): RunRow =
    query(
      """SELECT id, created_at, full_name, url, branch, pinned_sha, local_path, since, scan_limit, enriched_at
        |FROM runs WHERE id = ?""".stripMargin, id) { rs =>
      RunRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), optString(rs, 5),
        rs.getString(6), rs.getString(7), optString(rs, 8), rs.getInt(9), optString(rs, 10))
    }.headOption.getOrElse(throw UsageError(s"no run with id '$id'; see 'list-runs'"))

  def commits(runId: String): List[CommitRow] =
    query(
      """SELECT sha, authored_at, message, files, diff, diff_truncated FROM commits
        |WHERE run_id = ? ORDER BY authored_at DESC, rowid""".stripMargin, runId)(commitRow)

  def unclassifiedCommits(runId: String, limit: Int): List[CommitRow] =
    query(
      """SELECT sha, authored_at, message, files, diff, diff_truncated FROM commits c
        |WHERE run_id = ? AND NOT EXISTS
        |  (SELECT 1 FROM classifications k WHERE k.run_id = c.run_id AND k.sha = c.sha)
        |ORDER BY authored_at DESC, rowid LIMIT ?""".stripMargin, runId, limit)(commitRow)

  private def commitRow(rs: ResultSet): CommitRow =
    CommitRow(rs.getString(1), rs.getString(2), rs.getString(3),
      ujson.read(rs.getString(4)).arr.map(_.str).toList, rs.getString(5), rs.getInt(6) != 0)

  // ---- links --------------------------------------------------------------

  def replaceLinks(runId: String, sha: String, links: List[LinkRow]): Unit = tx {
    update("DELETE FROM links WHERE run_id = ? AND sha = ?", runId, sha)
    links.foreach { l =>
      update("INSERT INTO links(run_id, sha, kind, number, title, body, state, url, how) VALUES (?,?,?,?,?,?,?,?,?)",
        runId, sha, l.kind, l.number, l.title, l.body, l.state, l.url, l.how)
    }
  }

  def markEnriched(runId: String, now: String): Unit =
    update("UPDATE runs SET enriched_at = ? WHERE id = ?", now, runId)

  def links(runId: String, sha: String): List[LinkRow] =
    query("SELECT kind, number, title, body, state, url, how FROM links WHERE run_id = ? AND sha = ? ORDER BY number",
      runId, sha) { rs =>
      LinkRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
        rs.getString(6), rs.getString(7))
    }

  // ---- classifications ----------------------------------------------------

  def saveClassification(runId: String, c: Scored, now: String): Unit =
    update(
      """INSERT OR REPLACE INTO classifications(run_id, sha, is_bug_fix, combined, message_score,
        |  message_explanation, diff_score, diff_explanation, linked_score, linked_explanation, classified_at)
        |VALUES (?,?,?,?,?,?,?,?,?,?,?)""".stripMargin,
      runId, c.sha, Classify.isBugFix(c), Classify.combined(c), c.message.score, c.message.explanation,
      c.diff.score, c.diff.explanation, c.linked.map(_.score), c.linked.map(_.explanation), now)

  /** Commits that have a verdict, newest first. */
  def classifications(runId: String, onlyBugFixes: Boolean): List[ujson.Value] =
    query(
      s"""SELECT k.sha, c.authored_at, c.message, k.is_bug_fix, k.combined,
         |       k.message_score, k.message_explanation, k.diff_score, k.diff_explanation,
         |       k.linked_score, k.linked_explanation
         |FROM classifications k JOIN commits c ON c.run_id = k.run_id AND c.sha = k.sha
         |WHERE k.run_id = ? ${if onlyBugFixes then "AND k.is_bug_fix = 1" else ""}
         |ORDER BY c.authored_at DESC, c.rowid""".stripMargin, runId) { rs =>
      val sha = rs.getString(1)
      ujson.Obj(
        "sha" -> sha, "authored_at" -> rs.getString(2),
        "subject" -> rs.getString(3).linesIterator.nextOption().getOrElse(""),
        "is_bug_fix" -> (rs.getInt(4) != 0), "combined" -> rs.getDouble(5),
        "evidence" -> ujson.Obj(
          "message" -> ujson.Obj("score" -> rs.getDouble(6), "explanation" -> rs.getString(7)),
          "diff"    -> ujson.Obj("score" -> rs.getDouble(8), "explanation" -> rs.getString(9)),
          "linked"  -> optDouble(rs, 10).fold[ujson.Value](ujson.Null)(s =>
            ujson.Obj("score" -> s, "explanation" -> rs.getString(11)))),
        "links" -> Json.arr(links(runId, sha).map(l =>
          ujson.Obj("kind" -> l.kind, "number" -> l.number, "title" -> l.title, "how" -> l.how))))
    }

  // ---- run summaries ------------------------------------------------------

  private val RunSummarySql =
    """SELECT r.id, r.created_at, r.full_name, r.pinned_sha, r.branch, r.enriched_at,
      |  (SELECT COUNT(*) FROM commits c WHERE c.run_id = r.id),
      |  (SELECT COUNT(*) FROM classifications k WHERE k.run_id = r.id),
      |  (SELECT COUNT(*) FROM classifications k WHERE k.run_id = r.id AND k.is_bug_fix = 1)
      |FROM runs r""".stripMargin

  private def summary(rs: ResultSet): ujson.Value =
    ujson.Obj(
      "run" -> rs.getString(1), "created_at" -> rs.getString(2), "repo" -> rs.getString(3),
      "pinned_sha" -> rs.getString(4), "branch" -> Json.str(optString(rs, 5)),
      "enriched" -> optString(rs, 6).isDefined, "commits" -> rs.getInt(7),
      "classified" -> rs.getInt(8), "bug_fixes" -> rs.getInt(9))

  def listRuns(limit: Int): List[ujson.Value] =
    query(s"$RunSummarySql ORDER BY r.created_at DESC, r.rowid DESC LIMIT ?", limit)(summary)

  def runSummary(id: String): ujson.Value =
    query(s"$RunSummarySql WHERE r.id = ?", id)(summary).headOption
      .getOrElse(throw UsageError(s"no run with id '$id'; see 'list-runs'"))

object Db:
  def open(path: Path): Db =
    Option(path.getParent).foreach(Files.createDirectories(_))
    init(DriverManager.getConnection(s"jdbc:sqlite:${path.toAbsolutePath}"))

  def inMemory(): Db = init(DriverManager.getConnection("jdbc:sqlite::memory:"))

  private def init(conn: Connection): Db =
    val db = new Db(conn)
    db.update("PRAGMA foreign_keys = ON")
    val ddl = Using.resource(Source.fromResource("schema.sql", getClass.getClassLoader))(_.mkString)
    // Strip comment lines first so a semicolon inside a comment cannot split a statement.
    ddl.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).foreach(db.update(_))
    db

  private def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { (raw, i) =>
      val idx = i + 1
      val p = raw match
        case Some(v) => v
        case None    => null
        case other   => other
      p match
        case null       => ps.setObject(idx, null)
        case s: String  => ps.setString(idx, s)
        case n: Int     => ps.setInt(idx, n)
        case n: Long    => ps.setLong(idx, n)
        case d: Double  => ps.setDouble(idx, d)
        case b: Boolean => ps.setInt(idx, if b then 1 else 0)
        case other      => throw IllegalArgumentException(s"cannot bind ${other.getClass}")
    }

  def optString(rs: ResultSet, col: Int): Option[String] = Option(rs.getString(col))
  def optInt(rs: ResultSet, col: Int): Option[Int] =
    val v = rs.getInt(col)
    if rs.wasNull then None else Some(v)
  def optDouble(rs: ResultSet, col: Int): Option[Double] =
    val v = rs.getDouble(col)
    if rs.wasNull then None else Some(v)
