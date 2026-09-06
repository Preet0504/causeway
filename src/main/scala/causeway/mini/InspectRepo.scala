package causeway.mini

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevWalk}

import java.io.File
import java.nio.file.Files
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** Clones (or reuses) a repo, walks its commit history from HEAD, and writes
  * every commit within a date window to a JSON evidence file. Deterministic:
  * given the same repo state and arguments, the output is the same.
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
    val cap = opts.get("cap").map(_.toInt)
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

    val sinceEpochSeconds =
      LocalDate.parse(sinceDateStr).atStartOfDay(ZoneOffset.UTC).toInstant.getEpochSecond

    val repository = git.getRepository
    val head = repository.resolve("HEAD")
    if head == null then fail(s"Repository at ${workspaceDir.getPath} has no HEAD commit")

    val walk = new RevWalk(repository)
    walk.markStart(walk.parseCommit(head))
    walk.sort(RevSort.COMMIT_TIME_DESC)

    // Sorted COMMIT_TIME_DESC, so the first commit older than the cutoff means
    // every commit after it is older too — stop walking there instead of
    // visiting the entire history just to filter most of it back out.
    val windowCommits = scala.collection.mutable.ListBuffer[RevCommit]()
    val it = walk.iterator()
    var reachedCutoff = false
    while it.hasNext && !reachedCutoff do
      val c = it.next()
      if c.getCommitTime.toLong >= sinceEpochSeconds then windowCommits += c
      else reachedCutoff = true
    walk.dispose()

    if countOnly then
      // Preview mode: just report how many commits fall in the window, so the
      // orchestrator can ask for a bug cap informed by that number. No evidence
      // file is written — nothing here has been decided yet.
      git.close()
      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
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
        "bugCap" -> cap.map(c => ujson.Num(c.toDouble)).getOrElse(ujson.Null),
        "windowCommitCount" -> windowCommits.size,
        "commits" -> commitArr
      )

      val exportsDir = new File("workspace/exports")
      exportsDir.mkdirs()
      val outFile = new File(exportsDir, s"run_$runId.json")
      Files.write(outFile.toPath, ujson.write(evidence, indent = 2).getBytes("UTF-8"))

      git.close()

      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
      println(s"EVIDENCE_FILE=${outFile.getPath}")
      println(s"RUN_ID=$runId")
  end main

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
