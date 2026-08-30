package causeway.core

/** A 1-based inclusive line range, tagged with WHICH REVISION its coordinates belong to.
  *
  * This split exists because of a bug class that is invisible in review. Given a fix that
  * replaces line 42 with lines 42-45:
  *
  *   - "was the faulty line covered before the fix?" queries the PARENT with `OldLines(42,42)`
  *   - "did the fix's new code get covered?"          queries the FIX   with `NewLines(42,45)`
  *
  * Ask the parent commit about line 45 and you get the closing brace of a different method — a
  * plausible-looking, entirely wrong answer, with nothing to signal that it went wrong. Making
  * the two sides different types turns that into a compile error.
  */
sealed trait LineRange:
  def start: Int
  def end: Int

  def length: Int              = end - start + 1
  def contains(line: Int)      = line >= start && line <= end
  def toSeq: Seq[Int]          = start to end

private[core] object LineRangeValidation:
  /** Called from each case class body, not the trait body. A `require` in the trait would run
    * during trait initialisation, before the subclass constructor parameters are assigned, and
    * would read 0 for both bounds — validating nothing while appearing to validate.
    */
  def check(start: Int, end: Int): Unit =
    require(start >= 1, s"line numbers are 1-based, got start=$start")
    require(end >= start, s"empty or inverted range: $start..$end")

/** Coordinates in the PARENT commit. The fault lives here. */
final case class OldLines(start: Int, end: Int) extends LineRange:
  LineRangeValidation.check(start, end)
  override def toString = s"old:$start..$end"

/** Coordinates in the FIX commit. The repair lives here. */
final case class NewLines(start: Int, end: Int) extends LineRange:
  LineRangeValidation.check(start, end)
  override def toString = s"new:$start..$end"

object OldLines:
  def single(line: Int): OldLines = OldLines(line, line)

object NewLines:
  def single(line: Int): NewLines = NewLines(line, line)
