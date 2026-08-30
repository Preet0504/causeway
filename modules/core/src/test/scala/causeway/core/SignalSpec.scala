package causeway.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SignalSpec extends AnyFunSuite with Matchers:

  private val ev1 = EvidenceId.unsafe("ev_1111111111111111")
  private val ev2 = EvidenceId.unsafe("ev_2222222222222222")
  private val ev3 = EvidenceId.unsafe("ev_3333333333333333")

  test("map transforms a Known and preserves its evidence"):
    val s = Signal.known(21, ev1).map(_ * 2)
    s.fold(v => v shouldBe 42)((_, _) => fail("should be Known"))
    s.evidenceIds shouldBe Vector(ev1)

  test("map leaves an Unknown untouched, reason and all"):
    val s: Signal[Int] = Signal.unknown(UnknownReason.BuildFailed, "no jdk", ev1)
    val mapped = s.map(_ * 2)
    mapped.fold(_ => fail("should stay Unknown")) { (r, d) =>
      r shouldBe UnknownReason.BuildFailed
      d shouldBe "no jdk"
    }

  test("flatMap short-circuits on Unknown without evaluating the function"):
    var called = false
    val s: Signal[Int] = Signal.unknown(UnknownReason.Timeout, "slow", ev1)
    s.flatMap { _ => called = true; Signal.known(1, ev2) }
    called shouldBe false

  test("zipWith unions evidence from both sides"):
    val combined = Signal.known(2, ev1).zipWith(Signal.known(3, ev2))(_ * _)
    combined.fold(v => v shouldBe 6)((_, _) => fail("should be Known"))
    combined.evidenceIds should contain theSameElementsAs Vector(ev1, ev2)

  test("zipWith keeps the FIRST unknown's reason — the earliest failure explains the rest"):
    val first: Signal[Int]  = Signal.unknown(UnknownReason.BuildFailed, "build", ev1)
    val second: Signal[Int] = Signal.unknown(UnknownReason.TestsFailed, "tests", ev2)
    first.zipWith(second)(_ + _).fold(_ => fail("should be Unknown")) { (r, _) =>
      r shouldBe UnknownReason.BuildFailed
    }

  test("zipWith retains evidence even when one side is Unknown"):
    val known: Signal[Int]   = Signal.known(1, ev1)
    val unknown: Signal[Int] = Signal.unknown(UnknownReason.RateLimited, "429", ev2)
    known.zipWith(unknown)(_ + _).evidenceIds should contain theSameElementsAs Vector(ev1, ev2)

  test("an Unknown carries evidence: 'we asked and could not tell' is itself citable"):
    val s: Signal[Int] = Signal.unknown(UnknownReason.NoDebugInfo, "stripped", ev1)
    s.evidenceIds shouldBe Vector(ev1)

  test("sequence collects all values and all evidence"):
    val xs = List(Signal.known(1, ev1), Signal.known(2, ev2), Signal.known(3, ev3))
    val seq = Signal.sequence(xs)
    seq.fold(v => v shouldBe List(1, 2, 3))((_, _) => fail("should be Known"))
    seq.evidenceIds should contain theSameElementsAs Vector(ev1, ev2, ev3)

  test("sequence is Unknown if any element is Unknown"):
    val xs = List(
      Signal.known(1, ev1),
      Signal.unknown(UnknownReason.NotApplicable, "n/a", ev2),
      Signal.known(3, ev3)
    )
    Signal.sequence(xs).isKnown shouldBe false

  test("partition separates what was learned from what was not, with reasons"):
    val xs = List(
      Signal.known(1, ev1),
      Signal.unknown(UnknownReason.BuildFailed, "dep resolution", ev2),
      Signal.known(3, ev3)
    )
    val (values, gaps) = Signal.partition(xs)
    values shouldBe List(1, 3)
    gaps shouldBe List((UnknownReason.BuildFailed, "dep resolution"))

  // The headline invariant. This is the scenario the whole type exists to prevent.
  test("a failed build and a genuinely uncovered line stay distinguishable"):
    val buildFailed: Signal[Double] =
      Signal.unknown(UnknownReason.BuildFailed, "DEPENDENCY_RESOLUTION", ev1)
    val genuinelyZero: Signal[Double] = Signal.known(0.0, ev2)

    // Both would be 0.0 under Option#getOrElse(0.0). They must not be equal here.
    buildFailed should not be genuinelyZero
    buildFailed.isKnown shouldBe false
    genuinelyZero.isKnown shouldBe true

    // And any downstream arithmetic keeps them apart rather than averaging them together.
    val doubledFail = buildFailed.map(_ * 2)
    val doubledZero = genuinelyZero.map(_ * 2)
    doubledFail.isKnown shouldBe false
    doubledZero.fold(v => v shouldBe 0.0)((_, _) => fail("should be Known"))

  test("fold is the only exit and forces the Unknown branch to see the reason"):
    val s: Signal[Int] = Signal.unknown(UnknownReason.NoReproducer, "no input found", ev1)
    val rendered = s.fold(v => s"value=$v") { (r, d) => s"unknown:$r:$d" }
    rendered shouldBe "unknown:NoReproducer:no input found"
