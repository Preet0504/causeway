package causeway.vcs

import causeway.core.OldLines

import java.time.Instant

/** Which SZZ heuristic to apply. */
enum SzzVariant:
  /** Every commit that last touched a line the fix removed. High recall, low precision. */
  case BSzz

  /** Of those, only the most recent. Rosa et al. (ICSE 2021) found this variant best against a
    * developer-informed oracle of 2,304 instances.
    */
  case RSzz

final case class InducingCandidate(
    sha: String,
    author: String,
    date: Instant,
    viaLines: Vector[Int],
    confidence: Double
)

final case class SzzResult(
    variant: SzzVariant,
    candidates: Vector[InducingCandidate],
    filtersApplied: Vector[String],
    deletedLineCount: Int
):
  /** True when the fix removed nothing, so blame had nothing to work backwards from.
    *
    * Emphatically not "this bug has no origin" — see [[Szz]].
    */
  def blameless: Boolean = deletedLineCount == 0

/** Fault-introducing commit identification, blame-based.
  *
  * ## What this is, and what it is not
  *
  * This implements the **R-SZZ heuristic**, not PySZZ. That is a deliberate deviation from the
  * design's "wrap an existing tool" instinct, and the reason is concrete: PySZZ requires
  * **srcML**, a native binary that must be installed per-platform and put on `PATH`. Standing up
  * a container image carrying srcML — the only clean way to depend on it here — is a lot of
  * machinery for what D4 demoted to a metadata enricher. The published variants remain the
  * reference if fidelity ever matters more than it does now.
  *
  * ## The known ceiling
  *
  * Blame-based SZZ has a measured limit, and it is not small. A study of 2,102 validated
  * bug-fixing commits found **28% of bug-inducing commits require traversing history beyond
  * blame results**, and **14% are blameless** — the fix deleted nothing, so there is no line to
  * blame at all. A guard-only fix, which merely adds a null check, is exactly that shape and is
  * extremely common.
  *
  * So a `blameless` result means **this method cannot see the origin**, never "the bug has no
  * origin". Every candidate is recorded as a candidate with its confidence, and the caller is
  * expected to treat an empty result as `Unknown(NotApplicable)` rather than as a finding.
  */
object Szz:

  /** Lines that are only whitespace, a comment, or a brace.
    *
    * Blaming these attributes a fault to whoever last reformatted the file. Blame already runs
    * with whitespace ignored; this catches the rest.
    */
  private def isCosmetic(line: String): Boolean =
    val t = line.trim
    t.isEmpty || t == "{" || t == "}" || t == "});" || t == ");" ||
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")

  /** Find the commits that last touched the lines this fix removed.
    *
    * @param issueDate when the bug was reported, if known. A commit made AFTER the bug was
    *                  already reported cannot have caused it — the original SZZ paper's
    *                  meta-change filter. Omitting it simply skips that filter rather than
    *                  guessing a date.
    */
  def inducingCommits(
      git: GitService,
      fixSha: String,
      variant: SzzVariant = SzzVariant.RSzz,
      issueDate: Option[Instant] = None
  ): Either[String, SzzResult] =
    for
      fix    <- git.commitMeta(fixSha)
      parent <- fix.parents.headOption.toRight(s"$fixSha is a root commit and has no parent")
      diff   <- git.diff(fixSha)
    yield
      val filters = scala.collection.mutable.ArrayBuffer("ignore-whitespace")

      // Only lines the fix DELETED or replaced carry information about where the fault was:
      // if a line had to be removed to fix the bug, that line was probably wrong.
      val deletions = diff.allHunks.flatMap { h =>
        h.oldRange match
          case None => Vector.empty
          case Some(range) =>
            val substantive = range.toSeq.toVector.zipWithIndex.filter { case (_, i) =>
              !h.deletedLines.lift(i).exists(isCosmetic)
            }.map(_._1)
            substantive.map(line => (h.file, line))
      }

      if deletions.nonEmpty then filters += "drop-cosmetic-lines"

      val blamed = deletions.groupBy(_._1).toVector.flatMap { case (file, entries) =>
        val lines = entries.map(_._2).sorted
        // Blame each contiguous run in one call rather than line by line.
        runs(lines).flatMap { case (from, to) =>
          git.blame(parent, file, OldLines(from, to)).getOrElse(Vector.empty)
        }
      }

      val byCommit = blamed
        .filterNot(_.commit == fixSha)
        .groupBy(_.commit)

      if issueDate.isDefined then filters += "exclude-commits-after-report"

      val candidates = byCommit.toVector
        .map { case (sha, lines) =>
          InducingCandidate(
            sha = sha,
            author = lines.head.author,
            date = lines.head.date,
            viaLines = lines.map(_.line).sorted.distinct,
            // Weight by how many of the removed lines a commit is responsible for. Not a
            // probability — a relative signal among candidates for the same fix.
            confidence = lines.size.toDouble / math.max(1, blamed.size)
          )
        }
        // A commit made after the bug was reported cannot have introduced it.
        .filter(c => issueDate.forall(d => !c.date.isAfter(d)))
        .sortBy(c => (-c.date.toEpochMilli, c.sha))

      val selected = variant match
        case SzzVariant.BSzz => candidates
        case SzzVariant.RSzz =>
          if candidates.isEmpty then Vector.empty
          else
            filters += "most-recent-only"
            // R for recent: the newest surviving candidate. Confidence is reset to 1.0 because
            // the variant asserts this one, not a share of a field.
            Vector(candidates.head.copy(confidence = 1.0))

      SzzResult(variant, selected, filters.toVector, deletions.size)

  /** Collapse sorted line numbers into contiguous runs. */
  private[vcs] def runs(lines: Vector[Int]): Vector[(Int, Int)] =
    if lines.isEmpty then Vector.empty
    else
      val sorted = lines.distinct.sorted
      sorted.tail
        .foldLeft(Vector((sorted.head, sorted.head))) { (acc, n) =>
          val (from, to) = acc.last
          if n == to + 1 then acc.init :+ (from, n) else acc :+ (n, n)
        }
