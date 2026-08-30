package causeway.vcs

import causeway.core.*

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.{DiffFormatter, RawText, RawTextComparator}
import org.eclipse.jgit.lib.{ObjectId, Repository}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.treewalk.filter.{AndTreeFilter, PathFilterGroup, TreeFilter}
import org.eclipse.jgit.treewalk.{AbstractTreeIterator, CanonicalTreeParser, EmptyTreeIterator, TreeWalk}
import org.eclipse.jgit.util.io.DisabledOutputStream

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class CommitInfo(
    sha: String,
    author: String,
    authorEmail: String,
    authorDate: Instant,
    commitDate: Instant,
    subject: String,
    message: String,
    parents: Vector[String],
    isMerge: Boolean
):
  /** Issue references as written, across the conventions projects actually use.
    *
    * Deliberately broad: a project that writes `CSV-118` rather than `#118` is not a project
    * without linked issues, and treating it as one would be a recall failure disguised as a
    * finding about the repository.
    */
  def issueRefs: Vector[String] =
    val hash    = "#\\d+".r.findAllIn(message).toVector
    val ghStyle = "\\bGH-\\d+".r.findAllIn(message).toVector
    val jira    = "\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b".r.findAllIn(message).toVector
      .filterNot(r => CommitInfo.NotIssueKeys.contains(r.takeWhile(_ != '-')))
    (hash ++ ghStyle ++ jira).distinct

object CommitInfo:
  /** Prefixes that look like JIRA keys but are standards, encodings or algorithms.
    *
    * Found on a real repository, not in a fixture: a jsoup commit about character escaping was
    * reported as referencing issue "ISO-8859". The JIRA pattern `[A-Z]+-\d+` cannot tell
    * `CSV-118` from `ISO-8859` on shape alone.
    *
    * The list is deliberately SHORT. The costs are asymmetric: a false positive means one
    * wasted issue lookup that returns not-found and is recorded as a gap, whereas a false
    * negative loses a linked issue entirely and starves symptom characterisation. When in
    * doubt, let it through.
    */
  val NotIssueKeys: Set[String] = Set(
    "ISO", "UTF", "UTF8", "UTF16", "ASCII", "ANSI", "RFC", "SHA", "MD", "CRC",
    "HTTP", "HTTPS", "TLS", "SSL", "AES", "RSA", "UTC", "GMT", "IEEE", "JSR", "JDK", "JEP"
  )

final case class FileDiff(
    file: String,
    oldPath: Option[String],
    status: HunkStatus,
    hunks: Vector[Hunk]
)

final case class DiffResult(
    files: Vector[FileDiff],
    linesAdded: Int,
    linesDeleted: Int
):
  def filesTouched: Int = files.size
  def hunkCount: Int    = files.map(_.hunks.size).sum
  def allHunks: Vector[Hunk] = files.flatMap(_.hunks)

final case class BlameLine(line: Int, commit: String, author: String, date: Instant)

/** Git operations over a local clone.
  *
  * Everything here is deterministic and side-effect-free apart from cloning itself. Nothing in
  * this file makes a judgement: `Candidates` matches patterns, it does not decide whether a
  * commit is a bug fix.
  */
final class GitService(val repo: Repository):

  private def git = Git(repo)

  def resolve(rev: String): Either[String, String] =
    Option(repo.resolve(rev)).map(_.name).toRight(s"cannot resolve '$rev'")

  def headSha: Either[String, String] = resolve("HEAD")

  def defaultBranch: String =
    Try(repo.getBranch).toOption.filter(_ != null).getOrElse("HEAD")

  def commitCount: Int =
    Using.resource(RevWalk(repo)) { walk =>
      Option(repo.resolve("HEAD")) match
        case None => 0
        case Some(head) =>
          walk.markStart(walk.parseCommit(head))
          walk.iterator().asScala.size
    }

  /** Walk history within a window.
    *
    * `maxCount` is a hard cap and truncation is reported rather than hidden — a silently
    * truncated candidate list would look like a repository with few bug fixes.
    */
  def listCommits(
      rev: String = "HEAD",
      since: Option[Instant] = None,
      until: Option[Instant] = None,
      maxCount: Int = 300,
      paths: Vector[String] = Vector.empty
  ): Either[String, (Vector[CommitInfo], Boolean)] =
    resolve(rev).map { _ =>
      Using.resource(RevWalk(repo)) { walk =>
        walk.markStart(walk.parseCommit(repo.resolve(rev)))

        // ANY_DIFF as well as the path group: without it the walk keeps every commit whose
        // TREE contains the path, which is nearly all of them. With it, only commits that
        // actually touched those paths survive.
        if paths.nonEmpty then
          walk.setTreeFilter(AndTreeFilter.create(
            PathFilterGroup.createFromStrings(paths.asJava), TreeFilter.ANY_DIFF))
        val all = walk
          .iterator()
          .asScala
          .map(toInfo)
          // filter, not takeWhile: the walk is roughly newest-first, but commit dates are not
          // monotonic along it — a rebase or a corrected clock puts an older date ahead of a
          // newer one, and takeWhile would silently end the history there.
          .filter(c => since.forall(s => !c.commitDate.isBefore(s)))
          .filter(c => until.forall(u => !c.commitDate.isAfter(u)))
          .take(maxCount + 1)
          .toVector
        (all.take(maxCount), all.size > maxCount)
      }
    }

  /** Bring this clone up to date with its remote.
    *
    * Fetch only, never a reset: the working tree may hold a checkout another stage is using, and
    * history mining reads OBJECTS, which a fetch delivers in full. Tags come too — release tags
    * are how a symptom report's "broken in 1.14.3" becomes a commit.
    */
  def fetch(): Either[String, Int] =
    Try {
      Git(repo).fetch().setRemote("origin").setTagOpt(TagOpt.FETCH_TAGS).call()
        .getTrackingRefUpdates.size()
    }.toEither.left.map(t => s"cannot fetch: ${Option(t.getMessage).getOrElse(t.toString)}")

  def commitMeta(sha: String): Either[String, CommitInfo] =
    Try {
      Using.resource(RevWalk(repo))(walk => toInfo(walk.parseCommit(ObjectId.fromString(sha))))
    }.toEither.left.map(t => s"cannot read commit '$sha': ${t.getMessage}")

  private def toInfo(c: RevCommit): CommitInfo =
    val ident = c.getAuthorIdent
    CommitInfo(
      sha = c.getName,
      author = ident.getName,
      authorEmail = ident.getEmailAddress,
      authorDate = ident.getWhenAsInstant,
      commitDate = c.getCommitterIdent.getWhenAsInstant,
      subject = c.getShortMessage,
      message = c.getFullMessage,
      parents = c.getParents.toVector.map(_.getName),
      isMerge = c.getParentCount > 1
    )

  /** Diff a commit against a parent, with rename detection ON.
    *
    * Rename detection is not optional. Without it a renamed file reads as delete-all plus
    * add-all: blame lineage is destroyed, SZZ blames the rename instead of the fault, and the
    * churn metrics report a rewrite where nothing changed.
    */
  def diff(sha: String, parentIndex: Int = 0): Either[String, DiffResult] =
    Try {
      Using.resource(RevWalk(repo)) { walk =>
        val commit = walk.parseCommit(ObjectId.fromString(sha))
        val parent =
          if commit.getParentCount > parentIndex then
            Some(walk.parseCommit(commit.getParent(parentIndex).getId))
          else None

        Using.resource(DiffFormatter(DisabledOutputStream.INSTANCE)) { df =>
          df.setRepository(repo)
          df.setDetectRenames(true)
          df.setDiffComparator(RawTextComparator.DEFAULT)

          // A ROOT commit has no parent. Passing null here makes JGit throw, and the Try
          // around this method would swallow it into "no files changed" — so the first commit
          // of every repository would silently report touching nothing, and would never match
          // the structural recall net. An empty tree is the correct other side of that diff.
          val oldTree: AbstractTreeIterator =
            parent.map(p => treeParser(p.getTree.getId)).getOrElse(EmptyTreeIterator())
          val newTree = treeParser(commit.getTree.getId)
          val entries = df.scan(oldTree, newTree).asScala.toVector

          val files = entries.map { e =>
            val header = df.toFileHeader(e)
            val edits  = header.toEditList.asScala.toVector

            val oldText = loadText(e.getOldId.toObjectId)
            val newText = loadText(e.getNewId.toObjectId)

            val hunks = edits.map { ed =>
              Hunk(
                file = if e.getNewPath == "/dev/null" then e.getOldPath else e.getNewPath,
                status = status(e.getChangeType.name),
                oldPath = Option(e.getOldPath).filter(_ != e.getNewPath).filter(_ != "/dev/null"),
                // JGit edits are 0-based with an exclusive end; LineRange is 1-based inclusive.
                // An empty side means pure insertion or pure deletion, hence the None.
                oldRange = if ed.getEndA > ed.getBeginA then Some(OldLines(ed.getBeginA + 1, ed.getEndA)) else None,
                newRange = if ed.getEndB > ed.getBeginB then Some(NewLines(ed.getBeginB + 1, ed.getEndB)) else None,
                deletedLines = slice(oldText, ed.getBeginA, ed.getEndA),
                addedLines = slice(newText, ed.getBeginB, ed.getEndB)
              )
            }

            FileDiff(
              file = if e.getNewPath == "/dev/null" then e.getOldPath else e.getNewPath,
              oldPath = Option(e.getOldPath).filter(_ != e.getNewPath).filter(_ != "/dev/null"),
              status = status(e.getChangeType.name),
              hunks = hunks
            )
          }

          DiffResult(
            files = files,
            linesAdded = files.flatMap(_.hunks).map(_.addedLines.size).sum,
            linesDeleted = files.flatMap(_.hunks).map(_.deletedLines.size).sum
          )
        }
      }
    }.toEither.left.map(t => s"cannot diff '$sha': ${t.getMessage}")

  private def status(changeType: String): HunkStatus = changeType match
    case "ADD"    => HunkStatus.Added
    case "DELETE" => HunkStatus.Deleted
    case "RENAME" => HunkStatus.Renamed
    case "COPY"   => HunkStatus.Renamed
    case _        => HunkStatus.Modified

  private def treeParser(treeId: ObjectId): CanonicalTreeParser =
    Using.resource(repo.newObjectReader()) { reader =>
      val p = CanonicalTreeParser()
      p.reset(reader, treeId)
      p
    }

  private def loadText(id: ObjectId): Option[RawText] =
    if id == null || ObjectId.zeroId == id then None
    else Try(RawText(repo.open(id).getBytes)).toOption

  private def slice(text: Option[RawText], from: Int, to: Int): Vector[String] =
    text match
      case None => Vector.empty
      case Some(t) =>
        (from until math.min(to, t.size())).map(i => t.getString(i)).toVector

  /** Read a file as it existed at a commit. */
  def fileAt(sha: String, path: String): Either[String, String] =
    Try {
      Using.resource(RevWalk(repo)) { walk =>
        val commit = walk.parseCommit(ObjectId.fromString(sha))
        // forPath returns null when the path is absent, so it must be checked BEFORE being
        // handed to Using.resource, which would otherwise NPE on the null.
        val tw = TreeWalk.forPath(repo, path, commit.getTree)
        if tw == null then throw IllegalStateException(s"'$path' not found at $sha")
        Using.resource(tw)(w => String(repo.open(w.getObjectId(0)).getBytes, "UTF-8"))
      }
    }.toEither.left.map(t => s"cannot read '$path' at '$sha': ${t.getMessage}")

  /** Blame a line range, ignoring whitespace-only change.
    *
    * Without WS_IGNORE_ALL, a project-wide reformat becomes the author of every line in the
    * repository and SZZ attributes every fault to it.
    */
  def blame(sha: String, path: String, range: OldLines): Either[String, Vector[BlameLine]] =
    Try {
      val result = git
        .blame()
        .setStartCommit(ObjectId.fromString(sha))
        .setFilePath(path)
        .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
        .call()

      if result == null then throw IllegalStateException(s"no blame for '$path' at $sha")

      range.toSeq.toVector.flatMap { line =>
        val idx = line - 1
        if idx < 0 || idx >= result.getResultContents.size() then None
        else
          val c = result.getSourceCommit(idx)
          Option(c).map { commit =>
            BlameLine(line, commit.getName, commit.getAuthorIdent.getName,
              commit.getAuthorIdent.getWhenAsInstant)
          }
      }
    }.toEither.left.map(t => s"cannot blame '$path' at '$sha': ${t.getMessage}")

  /** Materialise a commit's tree into a directory.
    *
    * Written out file by file rather than by checking the branch out, because the differential
    * oracle needs BOTH revisions present at once. A working-tree checkout can only hold one, and
    * two clones of a large repository to compare two commits is a lot of disk for no gain.
    */
  def checkoutTo(sha: String, into: Path): Either[String, Int] =
    Try {
      Files.createDirectories(into)
      Using.resource(RevWalk(repo)) { walk =>
        val commit = walk.parseCommit(ObjectId.fromString(sha))
        Using.resource(TreeWalk(repo)) { tw =>
          tw.addTree(commit.getTree)
          tw.setRecursive(true)
          var written = 0
          while tw.next() do
            val target = into.resolve(tw.getPathString)
            Files.createDirectories(target.getParent)
            Files.write(target, repo.open(tw.getObjectId(0)).getBytes)
            written += 1
          written
        }
      }
    }.toEither.left.map(t => s"cannot materialise '$sha': ${t.getMessage}")

  def close(): Unit = repo.close()

object GitService:

  def open(dir: Path): Either[String, GitService] =
    Try(Git.open(dir.toFile).getRepository)
      .toEither
      .map(GitService(_))
      .left.map(t => s"cannot open repository at $dir: ${t.getMessage}")

  /** Clone in full.
    *
    * Never shallow: blame and SZZ need complete history, and a shallow clone would silently
    * truncate lineage rather than fail.
    */
  def cloneRepo(url: String, dir: Path): Either[String, GitService] =
    Try {
      Git.cloneRepository().setURI(url).setDirectory(dir.toFile).setBare(false).call().getRepository
    }.toEither.map(GitService(_)).left.map(t => s"cannot clone $url: ${t.getMessage}")
