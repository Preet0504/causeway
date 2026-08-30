package causeway.metrics

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ImportanceSpec extends AnyFunSuite with Matchers:

  private val ev = EvidenceId.unsafe("ev_1111111111111111")

  private def known[A](a: A): Signal[A] = Signal.known(a, ev)
  private def missing(r: UnknownReason): Signal[Nothing] = Signal.unknown(r, "n/a", ev)

  private def dims(
      subject: String,
      cov: Signal[Double] = known(0.5),
      blast: Signal[Int] = known(25),
      dist: Signal[Int] = known(5)
  ) = Dimensions(subject, cov, blast, dist)

  test("a fully-known bug is scored on all three dimensions"):
    val r = Importance.score(dims("a"))
    r.scoredOn should contain theSameElementsAs Vector(
      "faultLineCoverage", "blastRadius", "symptomDistance"
    )
    r.composite shouldBe 0.5 +- 0.001 // 0.5, 25/50, 5/10 -> mean 0.5

  // This is D19. The obvious weighted-sum implementation fails this test, and failing it would
  // make a missing signal act as evidence against a bug.
  test("a bug with no symptom is NOT penalised — the dimension abstains, it does not vote zero"):
    val withSymptom    = Importance.score(dims("with", dist = known(5)))
    val withoutSymptom = Importance.score(dims("without", dist = missing(UnknownReason.NoSymptom)))

    withoutSymptom.scoredOn should not contain "symptomDistance"
    withoutSymptom.scoredOn.size shouldBe 2

    // Its composite is the mean of what IS known (0.5 and 0.5), so it is unchanged — not dragged
    // down by a zero standing in for the missing dimension.
    withoutSymptom.composite shouldBe withSymptom.composite +- 0.001

  test("a zero-vote implementation would have scored it strictly lower — guarding against that"):
    val known3    = Importance.score(dims("a", dist = known(5)))
    val unknown1  = Importance.score(dims("b", dist = missing(UnknownReason.NoSymptom)))
    val zeroVoted = (0.5 + 0.5 + 0.0) / 3.0

    unknown1.composite should be > zeroVoted
    unknown1.composite shouldBe known3.composite +- 0.001

  test("a bug with nothing known scores zero but records that it was scored on nothing"):
    val r = Importance.score(
      dims("empty",
        cov = missing(UnknownReason.BuildFailed),
        blast = missing(UnknownReason.NoDebugInfo),
        dist = missing(UnknownReason.NoSymptom))
    )
    r.scoredOn shouldBe empty
    r.composite shouldBe 0.0
    // and the caller can tell this apart from a genuine 0.0 across three known dimensions
    val genuineZero = Importance.score(dims("zero", known(0.0), known(0), known(0)))
    genuineZero.scoredOn.size shouldBe 3
    genuineZero.composite shouldBe 0.0

  test("dimension scores saturate rather than being normalised across the batch"):
    // Absolute scales keep scores comparable between runs; min-max over the batch would make a
    // bug's rank depend on which other bugs happened to be mined alongside it.
    val huge = Importance.score(dims("huge", blast = known(10_000), dist = known(10_000)))
    huge.dimensions("blastRadius") shouldBe 1.0
    huge.dimensions("symptomDistance") shouldBe 1.0

  test("a bug scored alone gets the same numbers as when scored in a crowd"):
    val alone = Importance.rank(Vector(dims("x", known(0.8), known(40), known(8)))).head
    val crowd = Importance
      .rank(Vector(
        dims("x", known(0.8), known(40), known(8)),
        dims("y", known(0.1), known(1), known(1)),
        dims("z", known(0.9), known(50), known(10))
      ))
      .find(_.subject == "x").get

    alone.composite shouldBe crowd.composite +- 0.0001

  test("ranking orders by composite, highest first"):
    val ranked = Importance.rank(Vector(
      dims("low",  known(0.1), known(5),  known(1)),
      dims("high", known(0.9), known(50), known(10)),
      dims("mid",  known(0.5), known(25), known(5))
    ))
    ranked.map(_.subject) shouldBe Vector("high", "mid", "low")
    ranked.map(_.rank) shouldBe Vector(1, 2, 3)

  test("at equal composite, the bug we know more about ranks higher"):
    val ranked = Importance.rank(Vector(
      dims("less", known(0.5), known(25), missing(UnknownReason.NoSymptom)),
      dims("more", known(0.5), known(25), known(5))
    ))
    ranked.head.subject shouldBe "more"
    ranked.head.scoredOn.size shouldBe 3

  test("coverage outside [0,1] is clamped rather than skewing the composite"):
    val r = Importance.score(dims("odd", cov = known(1.7)))
    r.dimensions("faultLineCoverage") shouldBe 1.0
