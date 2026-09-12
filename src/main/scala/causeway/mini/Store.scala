package causeway.mini

import java.io.File
import java.nio.file.Files
import java.sql.{Connection, DriverManager, PreparedStatement}
import scala.io.Source
import scala.jdk.CollectionConverters.*

/** Persists a JSON output from any pipeline stage (an `inspect-repo`
  * evidence file, an `inspect-commits` enriched file, or a `classify-bugs`
  * classified file) into the SQLite repository catalog at
  * `workspace/causeway.db`. Detects which of the three it was given by
  * which fields are present, no separate flag needed.
  *
  * Every table with a natural key is upserted into
  * (`INSERT ... ON CONFLICT ... DO UPDATE`), so storing the same file
  * twice, or storing two different runs against the same repository,
  * updates or reuses existing rows rather than creating duplicates.
  * Fields a later stage doesn't have (an enriched file has no author or
  * committer data, that was stripped by `EnrichCommits`) are preserved via
  * `COALESCE` rather than overwritten with null on conflict.
  */
object Store:

  private val DbPath = "workspace/causeway.db"

  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val filePath = opts.getOrElse("file", fail("--file is required"))
    val file = new File(filePath)
    if !file.exists() then fail(s"File not found: $filePath")

    val json = ujson.read(Files.readString(file.toPath))

    Class.forName("org.sqlite.JDBC")
    new File(DbPath).getAbsoluteFile.getParentFile.mkdirs()
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      conn.setAutoCommit(false)
      val kind = storeInto(conn, json, filePath)
      conn.commit()
      println(s"STORED_KIND=$kind")
      println(s"DATABASE=$DbPath")
    catch
      case e: Exception =>
        conn.rollback()
        fail(s"Store failed, rolled back, no partial write: ${e.getMessage}")
    finally conn.close()
  end run

  /** Applies the schema (idempotent, `CREATE TABLE IF NOT EXISTS`) and
    * upserts one JSON file's data into an already-open connection, without
    * touching commit/rollback or the fixed `workspace/causeway.db` path, so
    * tests can point this at a throwaway database. Returns which of the
    * three JSON kinds it detected and stored.
    */
  private[mini] def storeInto(conn: Connection, json: ujson.Value, filePath: String): String =
    applySchema(conn)

    val kind =
      if json.obj.contains("classifications") then "classified"
      else if json.obj.contains("enrichedCommitCount") then "enriched"
      else if json.obj.contains("commits") then "evidence"
      else fail(s"Unrecognized JSON shape in $filePath, expected an evidence, enriched, or classified file")

    kind match
      case "evidence"   => storeEvidence(conn, json, filePath)
      case "enriched"   => storeEnriched(conn, json, filePath)
      case "classified" => storeClassified(conn, json, filePath)

    kind

  /** `private[mini]`, not `private`: `SearchRepos` also opens a connection
    * to `workspace/causeway.db` directly (it inserts discovered repos as it
    * paginates, rather than writing a JSON file for a later `store` call),
    * and reuses this rather than duplicating schema application.
    */
  private[mini] def applySchema(conn: Connection): Unit =
    val schemaText = Source.fromResource("schema.sql").mkString
    // Strip `--` line comments before splitting on `;`, a leading comment
    // block followed immediately by a statement (no blank statement
    // between them) ends up in the same split chunk, so filtering out
    // chunks that merely *start* with "--" would silently drop the first
    // real statement along with the comment above it.
    val stmt = conn.createStatement()
    try
      schemaText.linesIterator
        .map(line => if line.trim.startsWith("--") then "" else line)
        .mkString("\n")
        .split(";")
        .map(_.trim)
        .filter(_.nonEmpty)
        .foreach(stmt.executeUpdate)
    finally stmt.close()

  // ---------------------------------------------------------------------
  // evidence (from InspectRepo's write mode)
  // ---------------------------------------------------------------------

  private def storeEvidence(conn: Connection, json: ujson.Value, filePath: String): Unit =
    val repoId = upsertRepository(conn, json("owner").str, json("repo").str, json("repoUrl").str)

    val snap = json("repoSnapshot")
    val snapshotId = upsertSnapshot(
      conn,
      repoId,
      snap("remoteName").str,
      snap("branch").str,
      snap("remoteHeadSha").str,
      snap("retrievedAt").str
    )

    val window = json("window")
    val inspectRepoRunId = upsertInspectRepoRun(
      conn,
      runId = json("runId").str,
      repositoryId = repoId,
      snapshotId = snapshotId,
      windowRequested = window("requested").str,
      windowSinceDate = window("sinceDate").str,
      scanCommitLimit = optInt(json("scanCommitLimit")),
      windowCommitCount = json("windowCommitCount").num.toInt,
      sourceFile = filePath
    )

    json("commits").arr.foreach { c =>
      val sha = c("sha").str
      val author = c("author")
      val committer = c("committer")
      upsertCommit(
        conn,
        sha,
        repoId,
        c("shortMessage").str,
        c("fullMessage").str,
        authorName = Some(author("name").str),
        authorEmail = Some(author("email").str),
        authorDate = Some(author("date").str),
        committerName = Some(committer("name").str),
        committerEmail = Some(committer("email").str),
        committerDate = Some(committer("date").str),
        firstSeenInspectRepoRunId = Some(inspectRepoRunId)
      )
      c("parentShas").arr.zipWithIndex.foreach { case (p, idx) =>
        upsertCommitParent(conn, sha, p.str, idx)
      }
      linkRunCommit(conn, inspectRepoRunId, sha)
    }
  end storeEvidence

  // ---------------------------------------------------------------------
  // enriched (from EnrichCommits)
  // ---------------------------------------------------------------------

  private def storeEnriched(conn: Connection, json: ujson.Value, filePath: String): Unit =
    val repoId = upsertRepository(conn, json("owner").str, json("repo").str, json("repoUrl").str)
    val runId = json("runId").str
    val inspectRepoRunId = findRunSurrogateId(conn, "inspect_repo_runs", runId)

    val inspectCommitsRunId = upsertInspectCommitsRun(
      conn,
      runId = runId,
      inspectRepoRunId = inspectRepoRunId,
      enrichedCommitCount = json("enrichedCommitCount").num.toInt,
      sourceFile = filePath
    )

    val knownShas = json("commits").arr.map(_("sha").str).toSet

    json("commits").arr.foreach { c =>
      val sha = c("sha").str
      // Enriched commits carry no author/committer data (EnrichCommits
      // strips it), so pass None for those, upsertCommit preserves
      // whatever was already recorded from an earlier evidence-file store
      // rather than overwriting it with null.
      upsertCommit(
        conn,
        sha,
        repoId,
        c("shortMessage").str,
        c("fullMessage").str,
        authorName = None,
        authorEmail = None,
        authorDate = None,
        committerName = None,
        committerEmail = None,
        committerDate = None,
        firstSeenInspectRepoRunId = None
      )

      c("pullRequests").arr.foreach { pr =>
        val prId = upsertPullRequest(
          conn,
          repoId,
          pr("number").num.toInt,
          title = Some(pr("title").str),
          url = Some(pr("url").str),
          state = Some(pr("state").str),
          firstSeenInspectCommitsRunId = Some(inspectCommitsRunId)
        )
        // GitHub's own resolution of "which PR(s) is this commit associated
        // with" (Commit.associatedPullRequests). Kept distinct from
        // pr_contains_commit below: a commit can be resolved as associated
        // with a PR by GitHub while no longer appearing in that PR's own
        // commit list (a rebase or force-push can drop it), these are two
        // separately-sourced facts that can disagree, not one.
        upsertRelation(
          conn,
          "commit_associated_pr",
          commitSha = Some(sha),
          pullRequestId = Some(prId),
          issueId = None,
          evidence = Some("Commit.associatedPullRequests"),
          inspectCommitsRunId
        )

        // Whether this exact commit sha actually appears in the PR's own
        // commit list (up to the first 100), a fact fetched independently
        // of associatedPullRequests.
        val prCommitShas = pr.obj.get("commitShas").map(_.arr.toList.map(_.str)).getOrElse(Nil)
        val prCommitTotalCount = pr.obj.get("commitTotalCount").map(_.num.toInt).getOrElse(prCommitShas.size)
        if prCommitShas.contains(sha) then
          upsertRelation(
            conn,
            "pr_contains_commit",
            commitSha = Some(sha),
            pullRequestId = Some(prId),
            issueId = None,
            evidence = Some(s"found in PullRequest.commits (PR has $prCommitTotalCount commits)"),
            inspectCommitsRunId
          )

        pr("closingIssues").arr.foreach { issue =>
          val issueId = upsertIssue(
            conn,
            repoId,
            issue("number").num.toInt,
            title = Some(issue("title").str),
            url = Some(issue("url").str),
            firstSeenInspectCommitsRunId = Some(inspectCommitsRunId)
          )
          // Closing an issue is a fact about the PR as a whole, not about
          // any one commit inside it (a PR can have several commits and
          // only one of them, or none directly, actually closes the
          // issue), so this edge is recorded between the PR and the issue,
          // with no commit_sha, rather than attributed to every commit
          // that happens to belong to this PR.
          upsertRelation(
            conn,
            "pr_closes_issue",
            commitSha = None,
            pullRequestId = Some(prId),
            issueId = Some(issueId),
            evidence = Some("PullRequest.closingIssuesReferences"),
            inspectCommitsRunId
          )
        }
      }

      // A raw `#123`-shaped mention in the commit's own message, distinct
      // from GitHub's own authoritative issue-mention tracking (see
      // commit_mentions_issue below): this is an unconfirmed text match, it
      // doesn't know whether #123 is really an issue or a PR in this repo
      // (a merge commit's auto-generated "Merge pull request #1072"
      // message will match its own PR number here too), which is exactly
      // why it's kept as its own weaker relation type rather than folded
      // into a stronger one.
      IssueNumberMention.findAllMatchIn(c("fullMessage").str).map(_.group(1).toInt).distinct.foreach { number =>
        val issueId = upsertIssue(conn, repoId, number, title = None, url = None, firstSeenInspectCommitsRunId = Some(inspectCommitsRunId))
        upsertRelation(conn, "commit_message_references_issue", commitSha = Some(sha), pullRequestId = None, issueId = Some(issueId), evidence = Some(s"#$number"), inspectCommitsRunId)
      }
    }

    // Everything below comes from the issue's own side (its timeline, its
    // body text), not from walking commit -> PR -> issue, established by
    // EnrichCommits.fetchIssueTimelines. Older enriched files (before this
    // was added) simply won't have this key, hence the `.obj.get`.
    json.obj.get("issueTimelines").foreach(_.arr.foreach { timeline =>
      val issueId = upsertIssue(conn, repoId, timeline("number").num.toInt, title = None, url = None, firstSeenInspectCommitsRunId = Some(inspectCommitsRunId))

      // GitHub's own ReferencedEvent: a commit's message mentioned this
      // issue, confirmed by GitHub itself, a stronger signal than the raw
      // #N text match above. Only recorded for commits this run actually
      // knows about (has a `commits` row for), a referenced commit outside
      // the scanned set can't be linked without violating the foreign key,
      // and isn't otherwise useful here.
      timeline("referencedCommits").arr.foreach { rc =>
        val commitSha = rc("sha").str
        if knownShas.contains(commitSha) then
          val crossRepoNote = if rc("isCrossRepository").bool then " (cross-repository)" else ""
          upsertRelation(
            conn,
            "commit_mentions_issue",
            commitSha = Some(commitSha),
            pullRequestId = None,
            issueId = Some(issueId),
            evidence = Some(s"GitHub ReferencedEvent$crossRepoNote"),
            inspectCommitsRunId
          )
      }

      // A PR that cross-references this issue without necessarily closing
      // it (distinct from pr_closes_issue, a PR can do both, or just one).
      timeline("crossReferencingPRs").arr.foreach { pr =>
        val prId = upsertPullRequest(
          conn,
          repoId,
          pr("number").num.toInt,
          title = Some(pr("title").str),
          url = Some(pr("url").str),
          state = None,
          firstSeenInspectCommitsRunId = Some(inspectCommitsRunId)
        )
        upsertRelation(conn, "pr_mentions_issue", commitSha = None, pullRequestId = Some(prId), issueId = Some(issueId), evidence = None, inspectCommitsRunId)
      }

      // A raw commit-SHA-shaped token in the issue's own body text, matched
      // as a prefix against a commit this run actually knows about (a
      // short SHA, as people commonly paste them, is still a prefix of the
      // full one). A plain text heuristic, like commit_message_references_
      // issue above, not a GitHub-confirmed relation.
      val body = timeline("body").str
      ShaMention.findAllMatchIn(body).map(_.group(0)).distinct.foreach { token =>
        knownShas.find(_.startsWith(token.toLowerCase)).foreach { fullSha =>
          upsertRelation(conn, "issue_mentions_commit", commitSha = Some(fullSha), pullRequestId = None, issueId = Some(issueId), evidence = Some(token), inspectCommitsRunId)
        }
      }
    })
  end storeEnriched

  private val IssueNumberMention = """#(\d+)""".r
  private val ShaMention = """(?i)\b[0-9a-f]{7,40}\b""".r

  // ---------------------------------------------------------------------
  // classified (from /classify_bugs)
  // ---------------------------------------------------------------------

  private def storeClassified(conn: Connection, json: ujson.Value, filePath: String): Unit =
    val runId = json("runId").str
    val inspectCommitsRunId = findRunSurrogateId(conn, "inspect_commits_runs", runId)

    val classifyRunId = upsertClassifyBugsRun(
      conn,
      runId = runId,
      inspectCommitsRunId = inspectCommitsRunId,
      bugTarget = json("bugTarget").num.toInt,
      examinedCommitCount = json("examinedCommitCount").num.toInt,
      scannedCommitCount = json("scannedCommitCount").num.toInt,
      stoppedEarly = json("stoppedEarly").bool,
      sourceFile = filePath
    )

    json("classifications").arr.foreach { c =>
      upsertBugClassification(
        conn,
        commitSha = c("sha").str,
        classifyBugsRunId = classifyRunId,
        messageScore = optDouble(c("messageScore")),
        messageExplanation = optStr(c.obj.get("messageExplanation").getOrElse(ujson.Null)),
        diffScore = optDouble(c("diffScore")),
        diffExplanation = optStr(c.obj.get("diffExplanation").getOrElse(ujson.Null)),
        prScore = optDouble(c("prScore")),
        prExplanation = optStr(c.obj.get("prExplanation").getOrElse(ujson.Null)),
        verdict = c.obj.get("verdict").map(_.bool),
        verdictRationale = optStr(c.obj.get("verdictRationale").getOrElse(ujson.Null)),
        countsTowardTarget = c.obj.get("countsTowardTarget").map(_.bool)
      )
    }
  end storeClassified

  // ---------------------------------------------------------------------
  // Per-table upserts
  // ---------------------------------------------------------------------

  /** `private[mini]`: also called directly by `SearchRepos`, a discovered
    * repository is upserted into the one shared `repositories` table the
    * same way a mined one is, so a repo found by search and later mined by
    * `/inspect_repo` is the same row, not two.
    */
  private[mini] def upsertRepository(conn: Connection, owner: String, repo: String, url: String): Long =
    exec(
      conn,
      "INSERT INTO repositories (owner, repo, url) VALUES (?, ?, ?) " +
        "ON CONFLICT(owner, repo) DO UPDATE SET url = excluded.url",
      Seq(owner, repo, url)
    )
    queryId(conn, "SELECT id FROM repositories WHERE owner = ? AND repo = ?", Seq(owner, repo))

  private def upsertSnapshot(
      conn: Connection,
      repositoryId: Long,
      remoteName: String,
      branch: String,
      remoteHeadSha: String,
      retrievedAt: String
  ): Long =
    exec(
      conn,
      "INSERT INTO repository_snapshots (repository_id, remote_name, branch, remote_head_sha, retrieved_at) " +
        "VALUES (?, ?, ?, ?, ?) " +
        "ON CONFLICT(repository_id, remote_name, branch, remote_head_sha) DO UPDATE SET retrieved_at = excluded.retrieved_at",
      Seq(repositoryId, remoteName, branch, remoteHeadSha, retrievedAt)
    )
    queryId(
      conn,
      "SELECT id FROM repository_snapshots WHERE repository_id = ? AND remote_name = ? AND branch = ? AND remote_head_sha = ?",
      Seq(repositoryId, remoteName, branch, remoteHeadSha)
    )

  private def upsertInspectRepoRun(
      conn: Connection,
      runId: String,
      repositoryId: Long,
      snapshotId: Long,
      windowRequested: String,
      windowSinceDate: String,
      scanCommitLimit: Option[Int],
      windowCommitCount: Int,
      sourceFile: String
  ): Long =
    exec(
      conn,
      "INSERT INTO inspect_repo_runs " +
        "(run_id, repository_id, repository_snapshot_id, window_requested, window_since_date, scan_commit_limit, window_commit_count, source_file, created_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now')) " +
        "ON CONFLICT(run_id) DO UPDATE SET " +
        "repository_snapshot_id = excluded.repository_snapshot_id, " +
        "window_requested = excluded.window_requested, " +
        "window_since_date = excluded.window_since_date, " +
        "scan_commit_limit = excluded.scan_commit_limit, " +
        "window_commit_count = excluded.window_commit_count, " +
        "source_file = excluded.source_file",
      Seq(runId, repositoryId, snapshotId, windowRequested, windowSinceDate, scanCommitLimit.orNull, windowCommitCount, sourceFile)
    )
    queryId(conn, "SELECT id FROM inspect_repo_runs WHERE run_id = ?", Seq(runId))

  private def upsertInspectCommitsRun(
      conn: Connection,
      runId: String,
      inspectRepoRunId: Option[Long],
      enrichedCommitCount: Int,
      sourceFile: String
  ): Long =
    exec(
      conn,
      "INSERT INTO inspect_commits_runs (run_id, inspect_repo_run_id, enriched_commit_count, source_file, created_at) " +
        "VALUES (?, ?, ?, ?, datetime('now')) " +
        "ON CONFLICT(run_id) DO UPDATE SET " +
        "inspect_repo_run_id = excluded.inspect_repo_run_id, " +
        "enriched_commit_count = excluded.enriched_commit_count, " +
        "source_file = excluded.source_file",
      Seq(runId, inspectRepoRunId.map(_.asInstanceOf[Any]).orNull, enrichedCommitCount, sourceFile)
    )
    queryId(conn, "SELECT id FROM inspect_commits_runs WHERE run_id = ?", Seq(runId))

  private def upsertClassifyBugsRun(
      conn: Connection,
      runId: String,
      inspectCommitsRunId: Option[Long],
      bugTarget: Int,
      examinedCommitCount: Int,
      scannedCommitCount: Int,
      stoppedEarly: Boolean,
      sourceFile: String
  ): Long =
    exec(
      conn,
      "INSERT INTO classify_bugs_runs " +
        "(run_id, inspect_commits_run_id, bug_target, examined_commit_count, scanned_commit_count, stopped_early, source_file, created_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now')) " +
        "ON CONFLICT(run_id) DO UPDATE SET " +
        "inspect_commits_run_id = excluded.inspect_commits_run_id, " +
        "bug_target = excluded.bug_target, " +
        "examined_commit_count = excluded.examined_commit_count, " +
        "scanned_commit_count = excluded.scanned_commit_count, " +
        "stopped_early = excluded.stopped_early, " +
        "source_file = excluded.source_file",
      Seq(runId, inspectCommitsRunId.map(_.asInstanceOf[Any]).orNull, bugTarget, examinedCommitCount, scannedCommitCount, stoppedEarly, sourceFile)
    )
    queryId(conn, "SELECT id FROM classify_bugs_runs WHERE run_id = ?", Seq(runId))

  private def upsertCommit(
      conn: Connection,
      sha: String,
      repositoryId: Long,
      shortMessage: String,
      fullMessage: String,
      authorName: Option[String],
      authorEmail: Option[String],
      authorDate: Option[String],
      committerName: Option[String],
      committerEmail: Option[String],
      committerDate: Option[String],
      firstSeenInspectRepoRunId: Option[Long]
  ): Unit =
    exec(
      conn,
      "INSERT INTO commits " +
        "(sha, repository_id, short_message, full_message, author_name, author_email, author_date, committer_name, committer_email, committer_date, first_seen_inspect_repo_run_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT(sha) DO UPDATE SET " +
        "short_message = excluded.short_message, " +
        "full_message = excluded.full_message, " +
        "author_name = COALESCE(excluded.author_name, author_name), " +
        "author_email = COALESCE(excluded.author_email, author_email), " +
        "author_date = COALESCE(excluded.author_date, author_date), " +
        "committer_name = COALESCE(excluded.committer_name, committer_name), " +
        "committer_email = COALESCE(excluded.committer_email, committer_email), " +
        "committer_date = COALESCE(excluded.committer_date, committer_date), " +
        "first_seen_inspect_repo_run_id = COALESCE(first_seen_inspect_repo_run_id, excluded.first_seen_inspect_repo_run_id)",
      Seq(
        sha,
        repositoryId,
        shortMessage,
        fullMessage,
        authorName.orNull,
        authorEmail.orNull,
        authorDate.orNull,
        committerName.orNull,
        committerEmail.orNull,
        committerDate.orNull,
        firstSeenInspectRepoRunId.map(_.asInstanceOf[Any]).orNull
      )
    )

  private def upsertCommitParent(conn: Connection, commitSha: String, parentSha: String, parentOrder: Int): Unit =
    exec(
      conn,
      "INSERT INTO commit_parents (commit_sha, parent_sha, parent_order) VALUES (?, ?, ?) " +
        "ON CONFLICT(commit_sha, parent_order) DO UPDATE SET parent_sha = excluded.parent_sha",
      Seq(commitSha, parentSha, parentOrder)
    )

  private def linkRunCommit(conn: Connection, inspectRepoRunId: Long, commitSha: String): Unit =
    exec(
      conn,
      "INSERT INTO run_commits (inspect_repo_run_id, commit_sha) VALUES (?, ?) " +
        "ON CONFLICT(inspect_repo_run_id, commit_sha) DO NOTHING",
      Seq(inspectRepoRunId, commitSha)
    )

  private def upsertPullRequest(
      conn: Connection,
      repositoryId: Long,
      number: Int,
      title: Option[String],
      url: Option[String],
      state: Option[String],
      firstSeenInspectCommitsRunId: Option[Long]
  ): Long =
    exec(
      conn,
      "INSERT INTO pull_requests (repository_id, number, title, url, state, first_seen_inspect_commits_run_id) " +
        "VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT(repository_id, number) DO UPDATE SET " +
        // COALESCE, not a plain overwrite: a PR discovered via an issue's
        // CrossReferencedEvent only gives number/title/url, no state, and
        // that must not wipe out a real state this PR already has from
        // being discovered a richer way (a commit's associatedPullRequests).
        "title = COALESCE(excluded.title, title), url = COALESCE(excluded.url, url), state = COALESCE(excluded.state, state), " +
        "first_seen_inspect_commits_run_id = COALESCE(first_seen_inspect_commits_run_id, excluded.first_seen_inspect_commits_run_id)",
      Seq(repositoryId, number, title.orNull, url.orNull, state.orNull, firstSeenInspectCommitsRunId.map(_.asInstanceOf[Any]).orNull)
    )
    queryId(conn, "SELECT id FROM pull_requests WHERE repository_id = ? AND number = ?", Seq(repositoryId, number))

  private def upsertIssue(
      conn: Connection,
      repositoryId: Long,
      number: Int,
      title: Option[String],
      url: Option[String],
      firstSeenInspectCommitsRunId: Option[Long]
  ): Long =
    exec(
      conn,
      "INSERT INTO issues (repository_id, number, title, url, first_seen_inspect_commits_run_id) " +
        "VALUES (?, ?, ?, ?, ?) " +
        "ON CONFLICT(repository_id, number) DO UPDATE SET " +
        // COALESCE, not a plain overwrite: an issue can be upserted with no
        // title/url known (a bare #N text mention, or a raw SHA match in
        // another issue's body only gives a number), and that must not
        // wipe out a real title/url this issue already has from being
        // discovered a richer way (e.g. a PR's closingIssuesReferences).
        "title = COALESCE(excluded.title, title), url = COALESCE(excluded.url, url), " +
        "first_seen_inspect_commits_run_id = COALESCE(first_seen_inspect_commits_run_id, excluded.first_seen_inspect_commits_run_id)",
      Seq(repositoryId, number, title.orNull, url.orNull, firstSeenInspectCommitsRunId.map(_.asInstanceOf[Any]).orNull)
    )
    queryId(conn, "SELECT id FROM issues WHERE repository_id = ? AND number = ?", Seq(repositoryId, number))

  /** Upserts one typed edge between two of {commit, pull request, issue}
    * into the generic `relations` table. Exactly two of `commitSha`,
    * `pullRequestId`, `issueId` should be `Some`, the third `None`, which
    * two is determined by `relationType` (see schema.sql's comment on the
    * `relations` table for the full list of what each type means). The
    * natural key (an expression index, since SQLite doesn't dedupe NULLs
    * in a plain UNIQUE constraint) is `(relationType, commitSha,
    * pullRequestId, issueId)`, so re-storing the same edge updates its
    * `evidence` rather than duplicating the row.
    */
  private def upsertRelation(
      conn: Connection,
      relationType: String,
      commitSha: Option[String],
      pullRequestId: Option[Long],
      issueId: Option[Long],
      evidence: Option[String],
      firstSeenInspectCommitsRunId: Long
  ): Unit =
    exec(
      conn,
      "INSERT INTO relations (relation_type, commit_sha, pull_request_id, issue_id, evidence, first_seen_inspect_commits_run_id) " +
        "VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT(relation_type, COALESCE(commit_sha, ''), COALESCE(pull_request_id, -1), COALESCE(issue_id, -1)) " +
        "DO UPDATE SET evidence = excluded.evidence",
      Seq(
        relationType,
        commitSha.orNull,
        pullRequestId.map(_.asInstanceOf[Any]).orNull,
        issueId.map(_.asInstanceOf[Any]).orNull,
        evidence.orNull,
        firstSeenInspectCommitsRunId
      )
    )

  private def upsertBugClassification(
      conn: Connection,
      commitSha: String,
      classifyBugsRunId: Long,
      messageScore: Option[Double],
      messageExplanation: Option[String],
      diffScore: Option[Double],
      diffExplanation: Option[String],
      prScore: Option[Double],
      prExplanation: Option[String],
      verdict: Option[Boolean],
      verdictRationale: Option[String],
      countsTowardTarget: Option[Boolean]
  ): Unit =
    exec(
      conn,
      "INSERT INTO bug_classifications " +
        "(commit_sha, classify_bugs_run_id, message_score, message_explanation, diff_score, diff_explanation, pr_score, pr_explanation, verdict, verdict_rationale, counts_toward_target) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT(commit_sha, classify_bugs_run_id) DO UPDATE SET " +
        "message_score = excluded.message_score, message_explanation = excluded.message_explanation, " +
        "diff_score = excluded.diff_score, diff_explanation = excluded.diff_explanation, " +
        "pr_score = excluded.pr_score, pr_explanation = excluded.pr_explanation, " +
        "verdict = excluded.verdict, verdict_rationale = excluded.verdict_rationale, " +
        "counts_toward_target = excluded.counts_toward_target",
      Seq(
        commitSha,
        classifyBugsRunId,
        messageScore.map(_.asInstanceOf[Any]).orNull,
        messageExplanation.orNull,
        diffScore.map(_.asInstanceOf[Any]).orNull,
        diffExplanation.orNull,
        prScore.map(_.asInstanceOf[Any]).orNull,
        prExplanation.orNull,
        verdict.map(_.asInstanceOf[Any]).orNull,
        verdictRationale.orNull,
        countsTowardTarget.map(_.asInstanceOf[Any]).orNull
      )
    )

  /** Looks up the surrogate id of an upstream stage's run row by the shared
    * `run_id`, if that stage was ever stored. Returns None rather than
    * failing when it wasn't, e.g. storing an enriched file without ever
    * having stored its evidence file, so storing later stages independently
    * still works, just without the upstream link resolved.
    */
  private def findRunSurrogateId(conn: Connection, table: String, runId: String): Option[Long] =
    val ps = conn.prepareStatement(s"SELECT id FROM $table WHERE run_id = ?")
    try
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      if rs.next() then Some(rs.getLong(1)) else None
    finally ps.close()

  // ---------------------------------------------------------------------
  // Generic JDBC plumbing
  // ---------------------------------------------------------------------

  private[mini] def exec(conn: Connection, sql: String, params: Seq[Any]): Unit =
    val ps = conn.prepareStatement(sql)
    try
      bind(ps, params)
      ps.executeUpdate()
      ()
    finally ps.close()

  private[mini] def queryId(conn: Connection, sql: String, params: Seq[Any]): Long =
    val ps = conn.prepareStatement(sql)
    try
      bind(ps, params)
      val rs = ps.executeQuery()
      if rs.next() then rs.getLong(1) else fail(s"Upsert did not produce a row for: $sql")
    finally ps.close()

  private def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (p, i) =>
      val idx = i + 1
      p match
        case null           => ps.setNull(idx, java.sql.Types.NULL)
        case s: String      => ps.setString(idx, s)
        case i: Int         => ps.setInt(idx, i)
        case l: Long        => ps.setLong(idx, l)
        case d: Double      => ps.setDouble(idx, d)
        case b: Boolean     => ps.setBoolean(idx, b)
        case other          => ps.setObject(idx, other)
    }

  private def optInt(v: ujson.Value): Option[Int] = v match
    case ujson.Null => None
    case other      => Some(other.num.toInt)

  private def optDouble(v: ujson.Value): Option[Double] = v match
    case ujson.Null => None
    case other      => Some(other.num)

  private def optStr(v: ujson.Value): Option[String] = v match
    case ujson.Null => None
    case other      => Some(other.str)

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

end Store
