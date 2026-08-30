package causeway.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LineRangeSpec extends AnyFunSuite with Matchers:

  test("a valid range reports its length and membership"):
    val r = OldLines(42, 45)
    r.length shouldBe 4
    r.contains(42) shouldBe true
    r.contains(45) shouldBe true
    r.contains(46) shouldBe false
    r.toSeq shouldBe Seq(42, 43, 44, 45)

  // This is the test that would have caught putting `require` in the trait body, where it runs
  // before the case class parameters are assigned and validates 0 against 0 forever.
  test("validation actually fires on construction — not silently skipped"):
    an[IllegalArgumentException] should be thrownBy OldLines(0, 5)
    an[IllegalArgumentException] should be thrownBy NewLines(0, 5)
    an[IllegalArgumentException] should be thrownBy OldLines(-3, 1)

  test("an inverted range is refused"):
    an[IllegalArgumentException] should be thrownBy OldLines(10, 4)
    an[IllegalArgumentException] should be thrownBy NewLines(10, 4)

  test("a single-line range is valid"):
    OldLines.single(42).length shouldBe 1
    NewLines.single(1).start shouldBe 1

  test("old and new sides are distinct types carrying distinct meaning"):
    val fault  = OldLines(42, 42) // where the bug was, in parent coordinates
    val repair = NewLines(42, 45) // where the fix is, in fix coordinates

    // Same numeric start, different revisions. They must never compare equal, or the
    // "query the parent with new-side numbers" bug becomes invisible again.
    fault.start shouldBe repair.start
    (fault: LineRange) should not be (repair: LineRange)

    fault.toString shouldBe "old:42..42"
    repair.toString shouldBe "new:42..45"

  test("a LineRange can be discriminated by side at runtime"):
    def sideOf(r: LineRange): String = r match
      case _: OldLines => "parent"
      case _: NewLines => "fix"

    sideOf(OldLines(1, 2)) shouldBe "parent"
    sideOf(NewLines(1, 2)) shouldBe "fix"
