package causeway.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class FindingSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")

  private def ledgerWith(ids: EvidenceId*): InMemoryEvidenceLedger =
    val l = InMemoryEvidenceLedger()
    ids.foreach { id =>
      l.issue(Evidence(id, "history_diff", "argshash", "payloadhash", Instant.EPOCH, runId,
        Provenance.ServerIssued))
    }
    l

  private val ev1 = EvidenceId.unsafe("ev_1111111111111111")
  private val ev2 = EvidenceId.unsafe("ev_2222222222222222")
  private val forged = EvidenceId.unsafe("ev_deadbeefdeadbeef")

  test("a claim with evidence the ledger issued is accepted"):
    val result = Finding.make(
      claim = Verdict.BugFix, claimType = ClaimType.Verdict, agent = "bugfix-adjudicator",
      subject = "abc", confidence = 0.9, evidence = Vector(ev1),
      ledger = ledgerWith(ev1)
    )
    result.map(_.claim) shouldBe Right(Verdict.BugFix)

  test("a claim with NO evidence is not representable"):
    val result = Finding.make(
      claim = Verdict.BugFix, claimType = ClaimType.Verdict, agent = "bugfix-adjudicator",
      subject = "abc", confidence = 0.9, evidence = Vector.empty,
      ledger = ledgerWith(ev1)
    )
    result shouldBe Left(FindingRejection.NoEvidence)

  // The core integrity property: an agent cannot assert what it did not retrieve.
  test("a claim citing an id the ledger never issued is rejected"):
    val result = Finding.make(
      claim = Verdict.BugFix, claimType = ClaimType.Verdict, agent = "bugfix-adjudicator",
      subject = "abc", confidence = 0.9, evidence = Vector(ev1, forged),
      ledger = ledgerWith(ev1)
    )
    result match
      case Left(FindingRejection.UnknownEvidence(ids)) => ids shouldBe Vector(forged)
      case other => fail(s"expected UnknownEvidence, got $other")

  test("the rejection explains that retrieving, not rephrasing, is the fix"):
    FindingRejection.UnknownEvidence(Vector(forged)).explain should include("Retrieve the fact")

  test("confidence outside [0,1] is refused"):
    def attempt(c: Double) = Finding.make(
      claim = "x", claimType = ClaimType.Symptom, agent = "symptom-characterizer",
      subject = "abc", confidence = c, evidence = Vector(ev1), ledger = ledgerWith(ev1)
    )
    attempt(1.5) shouldBe Left(FindingRejection.ConfidenceOutOfRange(1.5))
    attempt(-0.1) shouldBe Left(FindingRejection.ConfidenceOutOfRange(-0.1))
    attempt(0.0).isRight shouldBe true
    attempt(1.0).isRight shouldBe true

  test("duplicate evidence ids are collapsed"):
    val r = Finding.make(
      claim = "x", claimType = ClaimType.Path, agent = "path-tracer", subject = "abc",
      confidence = 0.5, evidence = Vector(ev1, ev1, ev2), ledger = ledgerWith(ev1, ev2)
    )
    r.map(_.evidence) shouldBe Right(Vector(ev1, ev2))

  test("fromSignals inherits evidence and records gaps automatically"):
    val ledger = ledgerWith(ev1, ev2)
    val coverage: Signal[Double] = Signal.known(0.75, ev1)
    val lifetime: Signal[Int]    = Signal.unknown(UnknownReason.NotApplicable, "no SZZ origin", ev2)

    val r = Finding.fromSignals(
      claim = "summary", claimType = ClaimType.Path, agent = "path-tracer", subject = "abc",
      confidence = 0.8, signals = Vector(coverage, lifetime), ledger = ledger
    )

    r match
      case Right(f) =>
        f.evidence should contain theSameElementsAs Vector(ev1, ev2)
        f.gaps shouldBe Vector((UnknownReason.NotApplicable, "no SZZ origin"))
      case Left(e) => fail(s"expected success, got ${e.explain}")

  test("a gap is recorded rather than hidden — the finding still stands, but says what is missing"):
    val ledger = ledgerWith(ev1)
    val missing: Signal[Double] = Signal.unknown(UnknownReason.BuildFailed, "JDK_MISMATCH", ev1)
    val r = Finding.fromSignals(
      claim = "bug", claimType = ClaimType.Verdict, agent = "bugfix-adjudicator", subject = "abc",
      confidence = 0.6, signals = Vector(missing), ledger = ledger
    )
    r.map(_.gaps.size) shouldBe Right(1)
