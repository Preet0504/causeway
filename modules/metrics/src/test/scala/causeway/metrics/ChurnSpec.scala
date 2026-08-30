package causeway.metrics

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ChurnSpec extends AnyFunSuite with Matchers:

  private val ev = EvidenceId.unsafe("ev_1111111111111111")

  private def isTest(p: String) = p.contains("/test/")

  private def hunk(file: String, added: Int, deleted: Int) =
    Hunk(
      file = file,
      status = HunkStatus.Modified,
      oldRange = if deleted > 0 then Some(OldLines(1, deleted)) else None,
      newRange = if added > 0 then Some(NewLines(1, added)) else None,
      addedLines = Vector.fill(added)("+"),
      deletedLines = Vector.fill(deleted)("-")
    )

  test("counts come from the hunks themselves"):
    val m = Churn.of(Vector(hunk("src/main/A.java", 4, 1)), isTest)
    m.linesAdded shouldBe 4
    m.linesDeleted shouldBe 1
    m.linesChanged shouldBe 5
    m.filesTouched shouldBe 1
    m.hunkCount shouldBe 1

  test("files are counted distinctly even across several hunks"):
    val m = Churn.of(
      Vector(hunk("src/main/A.java", 1, 0), hunk("src/main/A.java", 2, 0), hunk("src/main/B.java", 1, 0)),
      isTest
    )
    m.filesTouched shouldBe 2
    m.hunkCount shouldBe 3

  test("packages touched reflects spread, not file count"):
    val m = Churn.of(
      Vector(
        hunk("src/main/java/a/One.java", 1, 0),
        hunk("src/main/java/a/Two.java", 1, 0),
        hunk("src/main/java/b/Three.java", 1, 0)
      ),
      isTest
    )
    m.filesTouched shouldBe 3
    m.packagesTouched shouldBe 2

  test("test LOC ratio measures how much of the change was test code"):
    val m = Churn.of(
      Vector(hunk("src/main/A.java", 3, 0), hunk("src/test/ATest.java", 1, 0)),
      isTest
    )
    m.testLocRatio shouldBe 0.25 +- 0.0001

  test("a change with no test code has a ratio of zero — which is a real value, not a gap"):
    Churn.of(Vector(hunk("src/main/A.java", 3, 0)), isTest).testLocRatio shouldBe 0.0

  test("an empty change does not divide by zero"):
    Churn.of(Vector.empty, isTest).testLocRatio shouldBe 0.0
    Churn.of(Vector.empty, isTest).filesTouched shouldBe 0

  test("bug lifetime is computed when the origin is known"):
    val introduced = Signal.known(Instant.parse("2024-01-01T00:00:00Z"), ev)
    val fixed      = Instant.parse("2024-03-01T00:00:00Z")
    Churn.lifetimeDays(introduced, fixed).fold(d => d shouldBe 60L)((_, _) => fail("should be Known"))

  // A guard-only fix deletes no lines, so SZZ has nothing to blame. That is NotApplicable —
  // emphatically not a bug that lived for zero days.
  test("with no SZZ origin the lifetime is Unknown, never zero"):
    val noOrigin = Signal.unknown(UnknownReason.NotApplicable, "no deleted lines to blame", ev)
    val result = Churn.lifetimeDays(noOrigin, Instant.parse("2024-03-01T00:00:00Z"))

    result.isKnown shouldBe false
    result.fold(_ => fail("should be Unknown")) { (r, _) => r shouldBe UnknownReason.NotApplicable }
    result should not be Signal.known(0L, ev)
