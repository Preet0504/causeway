package causeway.mini

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ObjectId, PersonIdent, Ref, Repository}
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevWalk}

import java.io.File
import java.nio.file.Files
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** Clones (or reuses) a repo, fetches and pins the exact remote HEAD commit
  * for this run, walks history from that pinned commit, and writes every
  * commit within a date window to a JSON evidence file. The repo URL alone
  * does not define repository state, a repo's HEAD moves, so every run
  * fetches from the remote and records the resolved default branch, the
  * exact commit SHA it pointed to, and when that was retrieved, in the
  * evidence file's `repoSnapshot`. Given that recorded snapshot and the same
  * arguments, which commits are found and their data is deterministic; the
  * run ID is not, it is a fresh random identifier on every invocation so
  * repeated runs don't collide on their output file name.
  */
object InspectRepo:

  def main(args: Array[String]): Unit =
    val opts = parseArgs(args)

    val repoUrl = opts.getOrElse("repo-url", fail("--repo-url is required"))
    val sinceDateStr = opts.getOrElse(
      "since-date",
      fail("--since-date is required (ISO date, e.g. 2026-06-05)")
    )
    val windowLabel = opts.getOrElse("window-label", sinceDateStr)
    val scanCommitLimit = opts.get("scan-commit-limit").map(_.toInt)
    val countOnly = opts.get("count-only").contains("true")

    val (owner, repo) = parseOwnerRepo(repoUrl)
    val workspaceDir = new File(s"workspace/$owner-$repo")

    val git =
      if workspaceDir.exists() && new File(workspaceDir, ".git").exists() then
        System.err.println(s"Reusing existing clone at ${workspaceDir.getPath}")
        Git.open(workspaceDir)
      else
        System.err.println(s"Cloning $repoUrl into ${workspaceDir.getPath} ...")
        workspaceDir.getParentFile.mkdirs()
        Git.cloneRepository()
          .setURI(repoUrl)
          .setDirectory(workspaceDir)
          .call()

    // Always fetch, whether this clone is brand new or reused. A reused
    // clone's local refs reflect whatever the remote looked like the last
    // time this tool ran against it, not necessarily now, and the repo URL
    // alone never tells us that. This is what actually keeps results honest
    // across separate runs against the same repo on different days.
    System.err.println(s"Fetching latest from origin ...")
    git.fetch().setRemote("origin").call()

    val repository = git.getRepository
    val retrievedAt = Instant.now()
    val (remoteHeadSha, defaultBranch, usedFallback) = resolveRemoteHead(repository)
    if remoteHeadSha == null then
      fail(s"Repository at ${workspaceDir.getPath} has no resolvable HEAD after fetch")
    if usedFallback then
      System.err.println(
        s"WARNING: could not resolve origin/HEAD after fetch, falling back to local HEAD (${remoteHeadSha.getName}). " +
          "This may not reflect the actual current remote state."
      )
    System.err.println(s"Pinned remote HEAD: $defaultBranch @ ${remoteHeadSha.getName} (retrieved $retrievedAt)")

    val sinceEpochSeconds =
      LocalDate.parse(sinceDateStr).atStartOfDay(ZoneOffset.UTC).toInstant.getEpochSecond

    val windowCommits = findCommitsInWindow(repository, remoteHeadSha, sinceEpochSeconds)

    if countOnly then
      // Preview mode: just report how many commits fall in the window, so the
      // orchestrator can ask for a scan commit limit informed by that number.
      // No evidence file is written — nothing here has been decided yet.
      git.close()
      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
      println(s"REMOTE_HEAD_SHA=${remoteHeadSha.getName}")
    else
      def personObj(p: PersonIdent) =
        ujson.Obj(
          "name" -> p.getName,
          "email" -> p.getEmailAddress,
          "date" -> p.getWhenAsInstant.toString
        )

      val commitArr = ujson.Arr.from(
        windowCommits.map { c =>
          ujson.Obj(
            "sha" -> c.getName,
            "shortMessage" -> c.getShortMessage,
            "fullMessage" -> c.getFullMessage,
            "author" -> personObj(c.getAuthorIdent),
            "committer" -> personObj(c.getCommitterIdent),
            "commitDate" -> Instant.ofEpochSecond(c.getCommitTime.toLong).toString,
            "parentShas" -> ujson.Arr.from(c.getParents.toList.map(_.getName))
          )
        }
      )

      val runId = UUID.randomUUID().toString

      val evidence = ujson.Obj(
        "runId" -> runId,
        "repoUrl" -> repoUrl,
        "owner" -> owner,
        "repo" -> repo,
        "clonePath" -> workspaceDir.getPath,
        "window" -> ujson.Obj(
          "requested" -> windowLabel,
          "sinceDate" -> sinceDateStr
        ),
        "repoSnapshot" -> ujson.Obj(
          "defaultBranch" -> defaultBranch,
          "remoteHeadSha" -> remoteHeadSha.getName,
          "retrievedAt" -> retrievedAt.toString,
          "usedLocalHeadFallback" -> usedFallback
        ),
        "scanCommitLimit" -> scanCommitLimit.map(c => ujson.Num(c.toDouble)).getOrElse(ujson.Null),
        "windowCommitCount" -> windowCommits.size,
        "commits" -> commitArr
      )

      val exportsDir = new File("workspace/exports")
      exportsDir.mkdirs()
      val outFile = new File(exportsDir, s"run_$runId.json")
      Files.write(outFile.toPath, ujson.write(evidence, indent = 2).getBytes("UTF-8"))

      git.close()

      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
      println(s"REMOTE_HEAD_SHA=${remoteHeadSha.getName}")
      println(s"EVIDENCE_FILE=${outFile.getPath}")
      println(s"RUN_ID=$runId")
  end main

  /** Resolves the pinned starting point for this run: the SHA that the
    * remote's default branch points to right now (as of the most recent
    * fetch), plus that branch's name. Later operations must walk from this
    * returned SHA, not from local `HEAD`, local `HEAD` is not refreshed by
    * a plain fetch at all (fetch only updates remote-tracking refs), so
    * using it here would silently reproduce the exact staleness bug this
    * function exists to fix.
    *
    * Deliberately does not rely on `refs/remotes/origin/HEAD`: that
    * symbolic ref is not reliably present (JGit's CloneCommand does not
    * always set it up the way plain `git clone` does). Instead, uses
    * `repository.getBranch()` for the branch name, this reads the locally
    * checked-out branch from `.git/HEAD` and is unaffected by fetches, so
    * it stays a stable label across reused clones, then looks up
    * `refs/remotes/origin/<that branch>`, which a plain fetch does reliably
    * update per Git's default fetch refspec, for the current SHA.
    *
    * Returns (the resolved commit id, or null if nothing resolves; branch
    * label; whether the last-resort local `HEAD` fallback was used, which
    * only happens if the remote-tracking ref itself is missing).
    */
  private[mini] def resolveRemoteHead(repository: Repository): (ObjectId, String, Boolean) =
    val branchName = repository.getBranch
    val remoteTrackingRef: Ref = repository.getRefDatabase.findRef(s"refs/remotes/origin/$branchName")
    if remoteTrackingRef != null && remoteTrackingRef.getObjectId != null then
      (remoteTrackingRef.getObjectId, branchName, false)
    else
      val fallback = repository.resolve("HEAD")
      if fallback == null then (null, branchName, true) else (fallback, branchName, true)

  /** Every commit reachable from `startPoint` whose commit time is at or
    * after `sinceEpochSeconds`, newest first. Does not stop early: Git
    * commit timestamps are not guaranteed to be monotonic along any
    * traversal order. Clock skew, rebases, and merges of
    * independently-developed branches can all produce a commit whose
    * timestamp is out of step with its neighbors in the walk (JGit's own
    * test suite covers exactly this case: a backdated child emitted before
    * a parent with a later timestamp). Stopping at the first commit found
    * to be outside the window would silently miss any later commit, still
    * reachable, that legitimately belongs in it, so every reachable commit
    * is visited and filtered individually instead.
    */
  private[mini] def findCommitsInWindow(
      repository: Repository,
      startPoint: ObjectId,
      sinceEpochSeconds: Long
  ): List[RevCommit] =
    val walk = new RevWalk(repository)
    try
      walk.markStart(walk.parseCommit(startPoint))
      walk.sort(RevSort.COMMIT_TIME_DESC)
      val windowCommits = scala.collection.mutable.ListBuffer[RevCommit]()
      val it = walk.iterator()
      while it.hasNext do
        val c = it.next()
        if c.getCommitTime.toLong >= sinceEpochSeconds then windowCommits += c
      windowCommits.toList
    finally walk.dispose()

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

  private def parseOwnerRepo(url: String): (String, String) =
    val stripped = url
      .replaceFirst("^git@github\\.com:", "")
      .replaceFirst("^https?://github\\.com/", "")
      .replaceFirst("\\.git/?$", "")
      .stripSuffix("/")
    val parts = stripped.split("/")
    if parts.length < 2 then fail(s"Could not parse owner/repo from $url")
    (parts(0), parts(1))

  private def fail(msg: String): Nothing =
    System.err.println(s"ERROR: $msg")
    sys.exit(1)

end InspectRepo
