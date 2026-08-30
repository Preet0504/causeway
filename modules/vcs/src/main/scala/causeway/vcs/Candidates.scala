package causeway.vcs

import causeway.core.CandidateNet

import java.time.Instant
import scala.util.Random

final case class Candidate(
    sha: String,
    matchedNets: Vector[CandidateNet],
    issueRefs: Vector[String],
    touchesSource: Boolean,
    touchesTests: Boolean,
    filesTouched: Int,
    linesChanged: Int
)

final case class CandidateResult(
    candidates: Vector[Candidate],
    scanned: Int,
    perNetCounts: Map[CandidateNet, Int],
    truncated: Boolean
)

/** Recall nets over commit history.
  *
  * These are a RECALL device, not a classifier. Nothing here decides whether a commit is a bug
  * fix — that judgement belongs to the `bugfix-adjudicator` agent, which reads the message, the
  * discussion, and the diff. The nets exist only to reduce thousands of commits to a few hundred
  * worth an agent's attention, and they are deliberately over-inclusive: a false positive costs
  * one cheap adjudication, while a false negative is a bug that never enters the dataset at all.
  */
object Candidates:

  /** Words that appear in fix commits. Broad on purpose. */
  private val FixWords =
    "\\b(fix(e[sd])?|bug|defect|resolve[sd]?|crash|regress(ion)?|npe|leak|incorrect|wrong|broken|fault|patch)\\b".r

  /** Paths that look like tests, across the layouts JVM projects actually use.
    *
    * Public because the forge handlers need the SAME rule: if two places disagree about what a
    * test file is, a commit can be structurally a bug fix in one and not in the other.
    */
  def isTestFile(p: String): Boolean = isTestPath(p)
  def isSourceFile(p: String): Boolean = isSourcePath(p)

  private def isTestPath(p: String): Boolean =
    val lower = p.toLowerCase
    lower.contains("src/test/") || lower.contains("/test/") || lower.startsWith("test/") ||
      lower.endsWith("test.java") || lower.endsWith("tests.java") ||
      lower.endsWith("spec.scala") || lower.endsWith("test.scala") ||
      lower.endsWith("suite.scala") || lower.endsWith("it.java")

  private def isSourcePath(p: String): Boolean =
    val lower = p.toLowerCase
    !isTestPath(lower) &&
      (lower.endsWith(".java") || lower.endsWith(".scala") || lower.endsWith(".kt"))

  /** Structural threshold: a fix that touches half the repository is a rewrite, not a bug fix. */
  private val SmallDiffFiles = 10

  def find(
      git: GitService,
      rev: String = "HEAD",
      since: Option[Instant] = None,
      maxCount: Int = 300,
      nets: Set[CandidateNet] = CandidateNet.values.toSet,
      controlSampleSize: Int = 50
  ): Either[String, CandidateResult] =
    git.listCommits(rev, since, None, maxCount).map { case (commits, truncated) =>
      // Merge commits are skipped: their "diff" against the first parent is the whole merged
      // branch, which makes every structural signal meaningless.
      val subjects = commits.filterNot(_.isMerge)

      val examined = subjects.map { c =>
        val diff = git.diff(c.sha).toOption
        val files = diff.map(_.files.map(_.file)).getOrElse(Vector.empty)

        val touchesSource = files.exists(isSourcePath)
        val touchesTests  = files.exists(isTestPath)
        val filesTouched  = files.size
        val linesChanged  = diff.map(d => d.linesAdded + d.linesDeleted).getOrElse(0)
        val refs          = c.issueRefs

        val lexicalHit =
          nets.contains(CandidateNet.Lexical) &&
            (FixWords.findFirstIn(c.message.toLowerCase).isDefined || refs.nonEmpty)

        // Catches "handle empty section gracefully" — a fix with no fix-words at all, which the
        // lexical net cannot see. Source changed plus a test added in the same commit is the
        // strongest language-agnostic signal available without reading the issue tracker.
        val structuralHit =
          nets.contains(CandidateNet.Structural) &&
            touchesSource && touchesTests && filesTouched <= SmallDiffFiles

        val matched =
          Vector(
            Option.when(lexicalHit)(CandidateNet.Lexical),
            Option.when(structuralHit)(CandidateNet.Structural)
          ).flatten

        (Candidate(c.sha, matched, refs, touchesSource, touchesTests, filesTouched, linesChanged),
         matched.nonEmpty)
      }

      val matchedCandidates = examined.collect { case (c, true) => c }
      val unmatched         = examined.collect { case (c, false) => c }

      // The control sample is how prefilter RECALL gets measured rather than assumed. These
      // commits matched nothing; adjudicating a sample of them anyway reveals what the nets are
      // missing. Seeded from the window so a resumed run draws the same sample — otherwise the
      // recall estimate would change every time the run restarted.
      val control =
        if !nets.contains(CandidateNet.RandomControl) || controlSampleSize <= 0 then Vector.empty
        else
          val seed = s"$rev|${since.map(_.toString).getOrElse("")}|$maxCount".hashCode.toLong
          Random(seed)
            .shuffle(unmatched)
            .take(controlSampleSize)
            .map(c => c.copy(matchedNets = Vector(CandidateNet.RandomControl)))

      val all = matchedCandidates ++ control

      CandidateResult(
        candidates = all,
        scanned = commits.size,
        perNetCounts = CandidateNet.values.toVector
          .map(n => n -> all.count(_.matchedNets.contains(n)))
          .toMap,
        truncated = truncated
      )
    }
