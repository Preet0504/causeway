package causeway.core

import java.time.Instant

/** How much a piece of evidence can be trusted. */
enum Provenance:
  /** Minted by the server from a payload it retrieved itself. Verifiable: the hash can be
    * recomputed from the recorded tool, arguments, and payload.
    */
  case ServerIssued

  /** Transcribed by an agent from somewhere outside the ledger. Forgeable, and marked as such
    * so consumers can tell the two apart. No agent is currently granted the ability to produce
    * this — see the `evidence.attest` schema.
    */
  case Attested

/** One retrieved payload, recorded so that any claim citing it can be traced back. */
final case class Evidence(
    id: EvidenceId,
    tool: String,
    argsHash: String,
    payloadHash: String,
    at: Instant,
    runId: RunId,
    provenance: Provenance
)

/** Append-only record of every payload the server issued during a run.
  *
  * The point of the interface is that [[Finding]] construction can be checked against it: a
  * claim citing an id that was never issued is rejected, so an agent cannot assert something it
  * did not retrieve. Implementations persist; the in-memory one is for tests.
  */
trait EvidenceLedger:
  def issue(e: Evidence): Unit
  def contains(id: EvidenceId): Boolean
  def get(id: EvidenceId): Option[Evidence]
  def size: Int

  /** Re-attribute evidence issued under `from` to `to`.
    *
    * A server process mints its own run id at startup because it has not yet been told which
    * MINING run it is serving — the orchestrator supplies that later, on the first call that
    * carries one, and a run outlives several server processes anyway. Without this, one logical
    * run's evidence ends up scattered across a server id per restart, and anything asking "what
    * did this run retrieve" answers zero.
    *
    * Default is a no-op: an in-memory ledger used by a single test has nothing to reconcile.
    */
  def reattribute(from: RunId, to: RunId): Int = 0

final class InMemoryEvidenceLedger extends EvidenceLedger:
  private val store = scala.collection.mutable.LinkedHashMap.empty[EvidenceId, Evidence]

  def issue(e: Evidence): Unit    = store.update(e.id, e)
  def contains(id: EvidenceId)    = store.contains(id)
  def get(id: EvidenceId)         = store.get(id)
  def size: Int                   = store.size
  def all: Vector[Evidence]       = store.values.toVector
