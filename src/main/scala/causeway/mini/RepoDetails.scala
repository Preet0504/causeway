package causeway.mini

import java.io.File
import java.sql.{Connection, DriverManager}

/** Looks up everything the catalog knows about one specific repository, for
  * the moment someone actually picks it to mine, not for every row of a
  * results table. `/discover_repos` and `/list_qualifying_repos` deliberately
  * keep their result tables lean (stars, language, size, issue/PR counts,
  * already-mined) rather than cramming in `description`/`topics`/license/
  * archived-fork status/creation date for every candidate shown; this is
  * the one-repo lookup that surfaces the rest, as a last look before
  * pointing someone at `/inspect_repo`. Purely a database read, no network
  * call, no writes, the same as `list-qualifying-repos`.
  */
object RepoDetails:

  private val DbPath = "workspace/causeway.db"

  /** Entry point for the `repo-details` subcommand of the unified
    * `Causeway` CLI (see `Causeway.scala`).
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val owner = opts.getOrElse("owner", fail("--owner is required"))
    val repo = opts.getOrElse("repo", fail("--repo is required"))

    Class.forName("org.sqlite.JDBC")
    if !new File(DbPath).exists() then fail(s"No repository found for $owner/$repo, $DbPath doesn't exist yet")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      fetchDetails(conn, owner, repo) match
        case None => fail(s"No repository found for $owner/$repo, has it been discovered or mined yet?")
        case Some(d) =>
          println(s"DESCRIPTION=${d.description.getOrElse("")}")
          println(s"TOPICS=${d.topics.mkString(",")}")
          println(s"FORKS_COUNT=${d.forksCount.map(_.toString).getOrElse("?")}")
          println(s"DEFAULT_BRANCH=${d.defaultBranch.getOrElse("?")}")
          println(s"REPO_CREATED_AT=${d.repoCreatedAt.getOrElse("?")}")
          println(s"CURRENT_STARS=${d.currentStars.map(_.toString).getOrElse("?")}")
          println(s"CURRENT_LANGUAGE=${d.currentLanguage.getOrElse("?")}")
          println(s"CURRENT_SIZE_KB=${d.currentSizeKb.map(_.toString).getOrElse("?")}")
          println(s"CURRENT_ARCHIVED=${d.currentArchived.map(_.toString).getOrElse("?")}")
          println(s"CURRENT_FORK=${d.currentFork.map(_.toString).getOrElse("?")}")
          println(s"CURRENT_LICENSE=${d.currentLicense.getOrElse("?")}")
    finally conn.close()
  end run

  private[mini] case class RepoDetail(
      description: Option[String],
      topics: List[String],
      forksCount: Option[Int],
      defaultBranch: Option[String],
      repoCreatedAt: Option[String],
      currentStars: Option[Int],
      currentLanguage: Option[String],
      currentSizeKb: Option[Int],
      currentArchived: Option[Boolean],
      currentFork: Option[Boolean],
      currentLicense: Option[String]
  )

  private[mini] def fetchDetails(conn: Connection, owner: String, repo: String): Option[RepoDetail] =
    val ps = conn.prepareStatement(
      "SELECT description, topics, forks_count, default_branch, repo_created_at, " +
        "current_stars, current_language, current_size_kb, current_archived, current_fork, current_license " +
        "FROM repositories WHERE owner = ? AND repo = ?"
    )
    try
      ps.setString(1, owner)
      ps.setString(2, repo)
      val rs = ps.executeQuery()
      if !rs.next() then None
      else
        val forksCountValue = rs.getInt("forks_count")
        val forksCountWasNull = rs.wasNull()
        val currentStarsValue = rs.getInt("current_stars")
        val currentStarsWasNull = rs.wasNull()
        val currentSizeKbValue = rs.getInt("current_size_kb")
        val currentSizeKbWasNull = rs.wasNull()
        val currentArchivedValue = rs.getBoolean("current_archived")
        val currentArchivedWasNull = rs.wasNull()
        val currentForkValue = rs.getBoolean("current_fork")
        val currentForkWasNull = rs.wasNull()
        Some(
          RepoDetail(
            description = Option(rs.getString("description")),
            topics = Option(rs.getString("topics")).map(_.split(",").toList).getOrElse(Nil),
            forksCount = if forksCountWasNull then None else Some(forksCountValue),
            defaultBranch = Option(rs.getString("default_branch")),
            repoCreatedAt = Option(rs.getString("repo_created_at")),
            currentStars = if currentStarsWasNull then None else Some(currentStarsValue),
            currentLanguage = Option(rs.getString("current_language")),
            currentSizeKb = if currentSizeKbWasNull then None else Some(currentSizeKbValue),
            currentArchived = if currentArchivedWasNull then None else Some(currentArchivedValue),
            currentFork = if currentForkWasNull then None else Some(currentForkValue),
            currentLicense = Option(rs.getString("current_license"))
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

end RepoDetails
