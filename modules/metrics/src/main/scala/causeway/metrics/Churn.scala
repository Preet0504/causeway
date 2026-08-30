package causeway.metrics

import causeway.core.*

final case class ChurnMetrics(
    linesAdded: Int,
    linesDeleted: Int,
    filesTouched: Int,
    hunkCount: Int,
    packagesTouched: Int,
    testLocRatio: Double
):
  def linesChanged: Int = linesAdded + linesDeleted

/** Size and spread of a change. Pure functions over hunks, so results are reproducible from
  * stored inputs rather than from re-reading a repository that may have moved on.
  */
object Churn:

  /** A path counts as a test by the same rules the recall nets use. Passed in rather than
    * hard-coded so the JVM and LLVM pipelines can disagree about what a test looks like without
    * this module knowing either of them exists.
    */
  def of(hunks: Vector[Hunk], isTestPath: String => Boolean): ChurnMetrics =
    val files = hunks.map(_.file).distinct

    val added   = hunks.map(_.addedLines.size).sum
    val deleted = hunks.map(_.deletedLines.size).sum
    val total   = added + deleted

    val testLines = hunks
      .filter(h => isTestPath(h.file))
      .map(h => h.addedLines.size + h.deletedLines.size)
      .sum

    ChurnMetrics(
      linesAdded = added,
      linesDeleted = deleted,
      filesTouched = files.size,
      hunkCount = hunks.size,
      packagesTouched = files.map(packageOf).distinct.size,
      // Zero changed lines is a real, representable state (an empty commit, a mode change), and
      // 0.0 is the correct ratio for it — not an unknown. Guarding only against the division.
      testLocRatio = if total == 0 then 0.0 else testLines.toDouble / total
    )

  private def packageOf(path: String): String =
    val i = path.lastIndexOf('/')
    if i < 0 then "" else path.substring(0, i)

  /** Days between two instants, or Unknown when the origin was never established.
    *
    * SZZ frequently finds no inducing commit — a guard-only fix deletes no lines, so blame has
    * nothing to work from. That is `NotApplicable`, not a lifetime of zero.
    */
  def lifetimeDays(
      introduced: Signal[java.time.Instant],
      fixed: java.time.Instant
  ): Signal[Long] =
    introduced.map(i => java.time.Duration.between(i, fixed).toDays)
