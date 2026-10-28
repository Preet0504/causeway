package causeway.mini

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter}
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.treewalk.{AbstractTreeIterator, CanonicalTreeParser, EmptyTreeIterator}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Where a clone ended up: the exact commit everything after this step is anchored to. */
final case class Pinned(sha: String, branch: Option[String])

object RepoMiner:
  /** How much diff text is kept per commit. The full diff stays in the clone. */
  val MaxDiffChars = 20000

  /** `https://github.com/o/r`, `https://github.com/o/r.git`, `git@github.com:o/r.git`, or `o/r`. */
  def parseRepoName(url: String): Option[String] =
    val https = """^https?://github\.com/([\w.-]+)/([\w.-]+?)(?:\.git)?/?$""".r
    val ssh   = """^git@github\.com:([\w.-]+)/([\w.-]+?)(?:\.git)?$""".r
    val short = """^([\w.-]+)/([\w.-]+)$""".r
    url.trim match
      case https(o, r) => Some(s"$o/$r")
      case ssh(o, r)   => Some(s"$o/$r")
      case short(o, r) => Some(s"$o/$r")
      case _           => None

  def cloneUrl(url: String): String =
    if url.matches("""^[\w.-]+/[\w.-]+$""") then s"https://github.com/$url.git" else url

  /**
   * Clones (or refreshes an existing clone of) `url` into `dir`, then detaches HEAD at one exact commit:
   * `rev` if given, else the tip of `branch`, else the remote's default branch.
   */
  def cloneAt(url: String, dir: Path, branch: Option[String], rev: Option[String]): Pinned =
    val existing = Files.isDirectory(dir.resolve(".git"))
    val git =
      if existing then
        val g = Git.open(dir.toFile)
        g.fetch().setRemote("origin").setRemoveDeletedRefs(true).call()
        g
      else
        Option(dir.getParent).foreach(Files.createDirectories(_))
        Git.cloneRepository().setURI(cloneUrl(url)).setDirectory(dir.toFile).setCloneAllBranches(true).call()
    Using.resource(git) { g =>
      val repo = g.getRepository
      val (target, branchName) = (rev, branch) match
        case (Some(r), _) =>
          (Option(repo.resolve(r)).getOrElse(throw UsageError(s"commit '$r' not found in $url")), branch)
        case (None, Some(b)) =>
          (Option(repo.resolve(s"refs/remotes/origin/$b")).getOrElse(throw UsageError(s"branch '$b' not found in $url")), Some(b))
        case (None, None) if existing =>
          // The clone's own HEAD is detached at an older pin; ask the remote what its default is.
          val head = Git.lsRemoteRepository().setRemote(cloneUrl(url)).call().asScala.find(_.getName == "HEAD")
          (head.map(_.getObjectId).getOrElse(throw UsageError(s"$url has no default branch")), None)
        case (None, None) =>
          (repo.resolve("HEAD"), Option(repo.getBranch))
      // parseCommit peels tags and fails loudly if the id is not a commit at all.
      val commit = Using.resource(new RevWalk(repo))(_.parseCommit(target))
      g.checkout().setName(commit.getName).setForced(true).call()
      Pinned(commit.getName, branchName)
    }

  /**
   * Non-merge commits reachable from `sha`, newest first, at most `limit`, authored at or after `since`.
   * Merge commits are skipped because their diff against the first parent is the whole merged branch,
   * which says nothing about one bug fix. The walk never stops early on a date: commit timestamps are
   * not monotonic in real histories (rebases, clock skew), so an old-looking commit can sit above new ones.
   */
  def readCommits(dir: Path, sha: String, since: Option[Instant], limit: Int): List[CommitRow] =
    Using.resource(Git.open(dir.toFile)) { git =>
      val repo = git.getRepository
      Using.resources(new RevWalk(repo), repo.newObjectReader()) { (walk, reader) =>
        walk.markStart(walk.parseCommit(ObjectId.fromString(sha)))
        val out = List.newBuilder[CommitRow]
        var n = 0
        val it = walk.iterator()
        while n < limit && it.hasNext do
          val c = it.next()
          val authored = c.getAuthorIdent.getWhen.toInstant
          if c.getParentCount <= 1 && since.forall(s => !authored.isBefore(s)) then
            out += toRow(repo, walk, reader, c, authored)
            n += 1
        out.result()
      }
    }

  private def toRow(repo: org.eclipse.jgit.lib.Repository, walk: RevWalk,
                    reader: org.eclipse.jgit.lib.ObjectReader, c: RevCommit, authored: Instant): CommitRow =
    val before: AbstractTreeIterator =
      if c.getParentCount == 0 then new EmptyTreeIterator()
      else new CanonicalTreeParser(null, reader, walk.parseCommit(c.getParent(0)).getTree)
    val after = new CanonicalTreeParser(null, reader, c.getTree)
    val sink = new BoundedOut(MaxDiffChars)
    Using.resource(new DiffFormatter(sink)) { df =>
      df.setRepository(repo)
      df.setDetectRenames(true)
      val entries = df.scan(before, after)
      df.format(entries)
      df.flush()
      val files = entries.asScala.map(e => if e.getChangeType == DiffEntry.ChangeType.DELETE then e.getOldPath else e.getNewPath).toList
      CommitRow(c.getName, authored.toString, c.getFullMessage.trim, files, sink.text, sink.truncated)
    }

  /** Keeps the first `max` bytes written and remembers whether anything was dropped. */
  private final class BoundedOut(max: Int) extends OutputStream:
    private val buf = new java.io.ByteArrayOutputStream()
    var truncated = false
    override def write(b: Int): Unit =
      if buf.size < max then buf.write(b) else truncated = true
    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      val room = max - buf.size
      if len <= room then buf.write(b, off, len)
      else
        if room > 0 then buf.write(b, off, room)
        truncated = true
    def text: String = new String(buf.toByteArray, UTF_8)
