package causeway.core

import java.security.MessageDigest

/** Opaque, validated identifiers.
  *
  * Every id in this file is a String underneath but is not interchangeable with one. Passing a
  * build handle where a report id belongs is a compile error rather than a runtime surprise
  * three tool calls later.
  */

opaque type CommitId = String

object CommitId:
  private val Sha = "[0-9a-f]{40}".r

  /** Abbreviated SHAs are refused: ambiguity is not resolvable downstream, and a 7-char prefix
    * that is unique today may not be after the next fetch.
    */
  def apply(s: String): Either[String, CommitId] =
    if Sha.matches(s) then Right(s)
    else Left(s"not a 40-hex commit id: '$s'")

  def unsafe(s: String): CommitId =
    apply(s).fold(m => throw IllegalArgumentException(m), identity)

  extension (c: CommitId) def value: String = c

/** Identifier for one mining run. */
opaque type RunId = String

object RunId:
  private val Pattern = "run_[0-9a-f]{16}".r

  def apply(s: String): Either[String, RunId] =
    if Pattern.matches(s) then Right(s) else Left(s"not a run id: '$s'")

  def unsafe(s: String): RunId =
    apply(s).fold(m => throw IllegalArgumentException(m), identity)

  extension (r: RunId) def value: String = r

/** Server-issued id for one retrieved payload. The only thing a Finding may cite. */
opaque type EvidenceId = String

object EvidenceId:
  private val Pattern = "ev_[0-9a-f]{16,64}".r

  def apply(s: String): Either[String, EvidenceId] =
    if Pattern.matches(s) then Right(s) else Left(s"not an evidence id: '$s'")

  def unsafe(s: String): EvidenceId =
    apply(s).fold(m => throw IllegalArgumentException(m), identity)

  extension (e: EvidenceId) def value: String = e

  /** Mint an id from the content that produced it.
    *
    * Deterministic: the same tool, called with the same arguments, yielding the same payload
    * produces the same id. That makes the ledger verifiable after the fact — an id can be
    * recomputed from the stored triple to confirm nothing was substituted.
    */
  def mint(tool: String, argsCanonical: String, payloadCanonical: String): EvidenceId =
    unsafe(s"ev_${sha256(s"$tool|$argsCanonical|$payloadCanonical").take(32)}")

  def sha256(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString

/** Id for an agent note.
  *
  * Deliberately a different type with a different prefix from [[EvidenceId]]. Notes influence
  * what an agent DOES; evidence determines what an agent may SAY. A note cannot be cited as
  * support for a claim — not by convention, but because `Finding` accepts only `EvidenceId`
  * and there is no conversion between the two.
  */
opaque type NoteId = String

object NoteId:
  private val Pattern = "note_[0-9a-f]{16}".r

  def apply(s: String): Either[String, NoteId] =
    if Pattern.matches(s) then Right(s) else Left(s"not a note id: '$s'")

  def unsafe(s: String): NoteId =
    apply(s).fold(m => throw IllegalArgumentException(m), identity)

  extension (n: NoteId) def value: String = n

/** Handles to server-side state.
  *
  * These exist so large objects never enter agent context. A call graph has millions of edges;
  * the agent gets a `CallGraphId` and queries it.
  */
opaque type RepoHandle = String
object RepoHandle:
  def apply(s: String): RepoHandle = s
  extension (h: RepoHandle) def value: String = h

opaque type BuildHandle = String
object BuildHandle:
  def apply(s: String): BuildHandle = s
  extension (h: BuildHandle) def value: String = h

opaque type CallGraphId = String
object CallGraphId:
  def apply(s: String): CallGraphId = s
  extension (h: CallGraphId) def value: String = h

opaque type ReportId = String
object ReportId:
  def apply(s: String): ReportId = s
  extension (h: ReportId) def value: String = h

opaque type TraceId = String
object TraceId:
  def apply(s: String): TraceId = s
  extension (h: TraceId) def value: String = h
