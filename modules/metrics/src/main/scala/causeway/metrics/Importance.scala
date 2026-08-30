package causeway.metrics

import causeway.core.*

/** One bug's ranking dimensions, each independently known or not.
  *
  *   - `faultLineCoverage`: fraction of the fix's OLD-side lines the pre-fix suite executed.
  *     Unknown when the build failed or the suite would not run.
  *   - `blastRadius`: how many methods can reach the fault site.
  *   - `symptomDistance`: graph hops from where the symptom surfaced to the fault. Unknown when
  *     no symptom could be characterised.
  */
final case class Dimensions(
    subject: String,
    faultLineCoverage: Signal[Double],
    blastRadius: Signal[Int],
    symptomDistance: Signal[Int]
)

final case class Ranked(
    subject: String,
    composite: Double,
    dimensions: Map[String, Double],
    scoredOn: Vector[String],
    rank: Int
)

/** Ranks bugs by how much a path through them is worth extracting.
  *
  * The whole design of this object is D19. The obvious implementation — normalise each dimension
  * and take a weighted sum — is wrong here, because a bug with no characterised symptom would
  * score 0 on `symptomDistance` and sink to the bottom of the list. That would make a MISSING
  * SIGNAL act as evidence against the bug, which is precisely the rule the project forbids.
  *
  * So each dimension is scored independently, the composite is the MEAN OF THE KNOWN dimensions
  * only, and `scoredOn` records which ones participated. A bug ranked on two dimensions is never
  * silently compared against one ranked on three: the caller can see the difference and stratify
  * on it.
  */
object Importance:

  /** Higher is better on every dimension:
    *   - coverage: already 0..1, higher means the fault site was actually exercised
    *   - blast radius: more reachable-from means the path matters more; saturating at 50
    *   - symptom distance: further from the fault means a more interesting path; saturating at 10
    *
    * Saturation rather than min-max normalisation across the batch, deliberately: min-max makes a
    * bug's score depend on which other bugs happened to be in the same run, so the same bug would
    * rank differently on a re-run with a different window. Absolute scales keep scores comparable
    * between runs, which resumability requires.
    */
  private val BlastSaturation    = 50.0
  private val DistanceSaturation = 10.0

  private def scoreCoverage(v: Double): Double = v.max(0.0).min(1.0)
  private def scoreBlast(v: Int): Double       = (v.toDouble / BlastSaturation).min(1.0)
  private def scoreDistance(v: Int): Double    = (v.toDouble / DistanceSaturation).min(1.0)

  def score(d: Dimensions): Ranked =
    val parts: Vector[(String, Option[Double])] = Vector(
      "faultLineCoverage" -> d.faultLineCoverage.fold(v => Some(scoreCoverage(v)))((_, _) => None),
      "blastRadius"       -> d.blastRadius.fold(v => Some(scoreBlast(v)))((_, _) => None),
      "symptomDistance"   -> d.symptomDistance.fold(v => Some(scoreDistance(v)))((_, _) => None)
    )

    val known = parts.collect { case (name, Some(v)) => name -> v }

    Ranked(
      subject = d.subject,
      // Mean over KNOWN dimensions. An unknown dimension abstains; it does not vote zero.
      composite = if known.isEmpty then 0.0 else known.map(_._2).sum / known.size,
      dimensions = known.toMap,
      scoredOn = known.map(_._1),
      rank = 0
    )

  /** Rank a batch, highest composite first.
    *
    * Ties break on the number of dimensions scored, so a bug we know more about outranks one we
    * know less about at the same score — the honest ordering when the evidence differs.
    */
  def rank(bugs: Vector[Dimensions]): Vector[Ranked] =
    bugs
      .map(score)
      .sortBy(r => (-r.composite, -r.scoredOn.size, r.subject))
      .zipWithIndex
      .map { case (r, i) => r.copy(rank = i + 1) }
