package causeway.core

/** What kind of claim an agent is making. */
enum ClaimType:
  case Verdict, Symptom, BuildRecipe, Reproducer, Path, Strategy

/** Why a finding was refused. */
enum FindingRejection:
  case NoEvidence
  case UnknownEvidence(ids: Vector[EvidenceId])
  case ConfidenceOutOfRange(value: Double)

  def explain: String = this match
    case NoEvidence =>
      "a claim must cite at least one evidence id"
    case UnknownEvidence(ids) =>
      s"cites ${ids.size} evidence id(s) this run's ledger never issued: ${ids.map(_.value).mkString(", ")}. " +
        "Retrieve the fact rather than rephrasing the claim."
    case ConfidenceOutOfRange(v) =>
      s"confidence must be in [0,1], got $v"

/** An agent's claim, bound to the evidence that supports it.
  *
  * The constructor is private and [[Finding.make]] is the only way in, so a claim with no
  * evidence is not representable. That is the mechanical form of the project's founding rule:
  * an agent may not assert a fact it did not retrieve.
  *
  * `gaps` records what could NOT be retrieved, and is as important as the evidence. A
  * low-confidence symptom with stated gaps is useful data; a low-confidence symptom that looks
  * well-evidenced is corrosive.
  */
final case class Finding[+A] private (
    claim: A,
    claimType: ClaimType,
    agent: String,
    subject: String,
    confidence: Double,
    evidence: Vector[EvidenceId],
    gaps: Vector[(UnknownReason, String)]
)

object Finding:

  /** Build a finding, checking every cited id against the ledger.
    *
    * Passing a ledger is not optional. An overload that skipped verification would be used, and
    * then the guarantee would be a convention rather than a property.
    */
  def make[A](
      claim: A,
      claimType: ClaimType,
      agent: String,
      subject: String,
      confidence: Double,
      evidence: Vector[EvidenceId],
      ledger: EvidenceLedger,
      gaps: Vector[(UnknownReason, String)] = Vector.empty
  ): Either[FindingRejection, Finding[A]] =
    if evidence.isEmpty then Left(FindingRejection.NoEvidence)
    else if confidence < 0.0 || confidence > 1.0 then
      Left(FindingRejection.ConfidenceOutOfRange(confidence))
    else
      val unknown = evidence.filterNot(ledger.contains)
      if unknown.nonEmpty then Left(FindingRejection.UnknownEvidence(unknown))
      else
        Right(
          new Finding(claim, claimType, agent, subject, confidence, evidence.distinct, gaps)
        )

  /** Build a finding from signals, inheriting their evidence and gaps automatically.
    *
    * This is the path that should normally be used: it makes the evidence trail a consequence
    * of how the value was obtained rather than something the caller assembles by hand and can
    * get wrong.
    */
  def fromSignals[A](
      claim: A,
      claimType: ClaimType,
      agent: String,
      subject: String,
      confidence: Double,
      signals: Vector[Signal[?]],
      ledger: EvidenceLedger
  ): Either[FindingRejection, Finding[A]] =
    val evidence = signals.flatMap(_.evidenceIds).distinct
    val gaps = signals.collect { case Signal.Unknown(r, d, _) => (r, d) }
    make(claim, claimType, agent, subject, confidence, evidence, ledger, gaps)
