package causeway.core

/** Why a value could not be determined.
  *
  * Closed set. A tool needing a reason not listed here adds the case here first — never an
  * improvised string, because these are aggregated across a run into the dataset's honesty
  * report, and free text would make that report unreadable.
  */
enum UnknownReason:
  case BuildFailed
  case TestsFailed
  case Timeout
  case NoLinkedIssue
  case NoSymptom
  case NoDebugInfo
  case NoReproducer
  case RateLimited
  case NotApplicable
  case Truncated
  case ToolUnavailable

/** A value that was retrieved, or an explicit statement that it could not be.
  *
  * This replaces `Option` throughout the project, and the difference is the entire point. With
  * `Option`, the first `getOrElse(0.0)` silently converts "the build failed so we have no
  * coverage number" into "coverage was zero" — a build failure and a genuinely untested line
  * become indistinguishable, and a missing signal starts acting as evidence against a bug.
  *
  * So: no `getOrElse`, no `getOrDefault`, no conversion to `Option`. `Unknown` propagates
  * through every combinator carrying the reason it arose, and the only way out is [[fold]],
  * which forces the caller to handle both cases in view of that reason.
  *
  * `Unknown` extends `Signal[Nothing]` rather than `Signal[A]`: it holds no value, so by
  * covariance a single Unknown flows into any `Signal[B]` without a cast.
  */
enum Signal[+A]:
  case Known[+A](value: A, evidence: Vector[EvidenceId]) extends Signal[A]
  case Unknown(reason: UnknownReason, detail: String, evidence: Vector[EvidenceId])
      extends Signal[Nothing]

  def isKnown: Boolean = this match
    case _: Known[?] => true
    case _: Unknown  => false

  /** Every evidence id backing this signal.
    *
    * Present whether known or unknown: an Unknown is itself a retrieved fact — "we called the
    * tool and it could not determine this, for this reason" — and it is citable. It should be
    * cited, because that is what distinguishes a recorded gap from a silent omission.
    */
  def evidenceIds: Vector[EvidenceId] = this match
    case Known(_, ev)      => ev
    case Unknown(_, _, ev) => ev

  def map[B](f: A => B): Signal[B] = this match
    case Known(a, ev) => Known(f(a), ev)
    case u: Unknown   => u

  def flatMap[B](f: A => Signal[B]): Signal[B] = this match
    case Known(a, _) => f(a)
    case u: Unknown  => u

  /** Combine two signals, unioning their evidence.
    *
    * If either side is Unknown the result is Unknown, and the FIRST unknown's reason survives:
    * the earliest failure is the one that explains everything after it. Evidence from both
    * sides is retained either way — a derived value is supported by every payload that went
    * into it, not just the last one.
    */
  def zipWith[B, C](other: Signal[B])(f: (A, B) => C): Signal[C] = (this, other) match
    case (Known(a, ev1), Known(b, ev2)) => Known(f(a, b), (ev1 ++ ev2).distinct)
    case (u: Unknown, o)                => Unknown(u.reason, u.detail, (u.evidence ++ o.evidenceIds).distinct)
    case (t, u: Unknown)                => Unknown(u.reason, u.detail, (t.evidenceIds ++ u.evidence).distinct)

  /** The only way to consume a Signal. Both branches must be supplied, and the Unknown branch
    * receives the reason, so a caller cannot collapse the distinction without seeing it.
    */
  def fold[B](onKnown: A => B)(onUnknown: (UnknownReason, String) => B): B = this match
    case Known(a, _)           => onKnown(a)
    case Unknown(r, detail, _) => onUnknown(r, detail)

object Signal:

  def known[A](a: A, ev: EvidenceId): Signal[A] = Known(a, Vector(ev))

  def known[A](a: A, ev: Vector[EvidenceId]): Signal[A] =
    require(ev.nonEmpty, "a Known signal must cite at least one evidence id")
    Known(a, ev)

  def unknown(reason: UnknownReason, detail: String, ev: EvidenceId): Signal[Nothing] =
    Unknown(reason, detail, Vector(ev))

  /** Collect signals, keeping all evidence. Unknown if any element is Unknown.
    *
    * Note there is deliberately no empty-list identity that manufactures an evidence id: an
    * empty input yields a Known with no evidence, which `Finding` will then refuse. Fabricating
    * a placeholder id to make the fold typecheck would forge exactly the thing the ledger
    * exists to make unforgeable.
    */
  def sequence[A](xs: List[Signal[A]]): Signal[List[A]] =
    xs.foldRight[Signal[List[A]]](Known(List.empty[A], Vector.empty)) { (s, acc) =>
      s.zipWith(acc)(_ :: _)
    }

  /** Partition for reporting: what we learned, and what we could not learn and why. This is
    * what produces a run's Unknown breakdown.
    */
  def partition[A](xs: List[Signal[A]]): (List[A], List[(UnknownReason, String)]) =
    xs.foldRight((List.empty[A], List.empty[(UnknownReason, String)])) { case (s, (ks, us)) =>
      s.fold(a => (a :: ks, us))((r, d) => (ks, (r, d) :: us))
    }
