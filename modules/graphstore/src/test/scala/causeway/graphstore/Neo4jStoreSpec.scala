package causeway.graphstore

import causeway.core.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Runs against the local causeway Neo4j (bolt://localhost:7690 by default).
  *
  * Cancels rather than fails when it is not reachable, so a checkout without Docker still gets a
  * green suite. Each test writes under a unique runId and purges it afterwards, so the shared
  * instance stays clean — note this machine also hosts another project's Neo4j on the default
  * ports, which is why `NEO4J_URI` must never be assumed.
  */
class Neo4jStoreSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private lazy val connected: Either[String, Neo4jStore] = Neo4jStore.connect()

  private def store(): Neo4jStore =
    connected.fold(err => cancel(s"Neo4j not reachable — skipping ($err)"), identity)

  private var suiteRuns: Vector[RunId] = Vector.empty

  private def freshRun(): RunId =
    val id = RunId.unsafe(f"run_${System.nanoTime() & 0xffffffffffffL}%012x0000")
    suiteRuns = suiteRuns :+ id
    id

  override def beforeAll(): Unit =
    connected.foreach(_.initialise())

  override def afterAll(): Unit =
    connected.foreach { s =>
      suiteRuns.foreach(s.purgeRun)
      s.purgeRepo("test/repo")
      s.close()
    }

  private def evidence(id: String, runId: RunId) =
    Evidence(EvidenceId.unsafe(id), "history_diff", "argsHash", "payloadHash",
      Instant.EPOCH, runId, Provenance.ServerIssued)

  test("initialise is safe to run repeatedly"):
    val s = store()
    s.initialise()
    s.initialise()
    succeed

  test("evidence is persisted and found again"):
    val s = store(); val run = freshRun()
    val id = EvidenceId.unsafe("ev_aaaaaaaaaaaaaaaa")
    s.putEvidence(evidence(id.value, run))

    s.hasEvidence(id) shouldBe true
    s.evidenceCount(run) shouldBe 1

  test("an id that was never issued is not found — the integrity check survives a restart"):
    val s = store()
    s.hasEvidence(EvidenceId.unsafe("ev_ffffffffffffffff")) shouldBe false

  test("writes are idempotent, so a resumed run overwrites rather than duplicating"):
    val s = store(); val run = freshRun()
    val id = EvidenceId.unsafe("ev_bbbbbbbbbbbbbbbb")
    s.putEvidence(evidence(id.value, run))
    s.putEvidence(evidence(id.value, run))
    s.putEvidence(evidence(id.value, run))
    s.evidenceCount(run) shouldBe 1

  // The audit property: any claim can be walked back to the tool call that produced it.
  test("a finding is linked to every piece of evidence it cites"):
    val s = store(); val run = freshRun()
    val e1 = EvidenceId.unsafe("ev_1111111111111111")
    val e2 = EvidenceId.unsafe("ev_2222222222222222")
    s.putEvidence(evidence(e1.value, run))
    s.putEvidence(evidence(e2.value, run))

    val fid = s"finding_${run.value}"
    s.putFinding(fid, run, "bugfix-adjudicator", "Verdict", "abc123", 0.9,
      """{"verdict":"BUG_FIX"}""", Vector(e1, e2), Vector.empty)

    s.findingEvidenceCount(fid) shouldBe 2

  test("gaps are stored with the finding rather than discarded"):
    val s = store(); val run = freshRun()
    val e1 = EvidenceId.unsafe("ev_3333333333333333")
    s.putEvidence(evidence(e1.value, run))

    val fid = s"finding_gaps_${run.value}"
    s.putFinding(fid, run, "symptom-characterizer", "Symptom", "abc", 0.4,
      """{"class":"UNDETERMINED"}""", Vector(e1),
      Vector((UnknownReason.NoLinkedIssue, "no issue on this commit")))

    s.findingEvidenceCount(fid) shouldBe 1

  // D2: a bug failing admission is recorded, never deleted, or the selection bias is invisible.
  test("a bug failing admission is stored with its reason, not dropped"):
    val s = store(); val run = freshRun()
    s.purgeRepo("test/repo")

    s.putBug("test/repo", "a" * 40, admitted = Some(true), None, Some(0.75),
      Vector("faultLineCoverage", "blastRadius"), Some("T1_NATIVE"), Some("FULL"))
    s.putBug("test/repo", "b" * 40, admitted = Some(false), Some("BUILD_FAILED:JDK_MISMATCH"),
      None, Vector.empty, None, None)

    s.bugCount("test/repo") shouldBe 2
    s.bugCount("test/repo", admittedOnly = true) shouldBe 1
    s.rejectionBreakdown("test/repo") shouldBe Map("BUILD_FAILED:JDK_MISMATCH" -> 1)

  test("re-upserting a bug updates it in place"):
    val s = store()
    s.purgeRepo("test/repo")
    s.putBug("test/repo", "c" * 40, admitted = Some(false), Some("BUILD_FAILED"), None, Vector.empty, None, None)
    s.putBug("test/repo", "c" * 40, admitted = Some(true), None, Some(0.9), Vector("blastRadius"),
      Some("T2_SYNTHESIZED"), Some("PARTIAL"))

    s.bugCount("test/repo") shouldBe 1
    s.bugCount("test/repo", admittedOnly = true) shouldBe 1

  test("checkpoints let an interrupted run resume instead of restarting"):
    val s = store(); val run = freshRun()

    s.isDone(run, "adjudication", "sha1") shouldBe false
    s.checkpoint(run, "adjudication", "sha1", "DONE")
    s.isDone(run, "adjudication", "sha1") shouldBe true

    s.checkpoint(run, "adjudication", "sha2", "FAILED", "handler threw")
    s.isDone(run, "adjudication", "sha2") shouldBe false

  test("resume state reports done and pending per stage"):
    val s = store(); val run = freshRun()
    s.checkpoint(run, "structure", "a", "DONE")
    s.checkpoint(run, "structure", "b", "DONE")
    s.checkpoint(run, "structure", "c", "FAILED")
    s.checkpoint(run, "coverage", "a", "DONE")

    val rows = s.resumeState(run)
    rows.find(_.stage == "structure").get shouldBe CheckpointRow("structure", 2, 1)
    rows.find(_.stage == "coverage").get shouldBe CheckpointRow("coverage", 1, 0)

  test("the Neo4j-backed ledger satisfies the EvidenceLedger contract"):
    val s = store(); val run = freshRun()
    val ledger = Neo4jEvidenceLedger(s)
    val id = EvidenceId.unsafe("ev_cccccccccccccccc")

    ledger.contains(id) shouldBe false
    ledger.issue(evidence(id.value, run))
    ledger.contains(id) shouldBe true

    // and a Finding built against it validates for real, against the database
    Finding.make("claim", ClaimType.Verdict, "bugfix-adjudicator", "abc", 0.8,
      Vector(id), ledger).isRight shouldBe true

    Finding.make("claim", ClaimType.Verdict, "bugfix-adjudicator", "abc", 0.8,
      Vector(EvidenceId.unsafe("ev_dddddddddddddddd")), ledger).isLeft shouldBe true

  // These reads exist so `export_run` can export the BUGS. Before them it wrote checkpoint rows
  // and reported bugCount 0 unconditionally — a hard-coded number in the artefact meant to
  // outlive the database.
  test("bugs are read back, rejected ones included"):
    val s = store()
    val run = freshRun()
    s.putRun(run, "test/repo")

    s.putBug("test/repo", "a" * 40, admitted = Some(true), None, Some(0.8),
      Vector("blastRadius", "coverage"), Some("T2"), Some("FULL"))
    s.putBug("test/repo", "b" * 40, admitted = Some(false), Some("NO_LINKED_ISSUE"), None,
      Vector.empty, None, None)

    val bugs = s.bugsForRepo("test/repo")
    bugs.map(_.fixSha) should contain allOf ("a" * 40, "b" * 40)

    val admitted = bugs.find(_.fixSha == "a" * 40).get
    admitted.admitted shouldBe true
    admitted.importance shouldBe Some(0.8)
    // scoredOn travels with the score: a composite over two dimensions and one over seven are
    // not comparable, and the number alone does not say which it is.
    admitted.scoredOn should contain theSameElementsAs Vector("blastRadius", "coverage")
    admitted.reproducerTier shouldBe Some("T2")

    // Rejected bugs are exported, not filtered. D2: hiding them hides the selection bias.
    val rejected = bugs.find(_.fixSha == "b" * 40).get
    rejected.admitted shouldBe false
    rejected.rejectedFor shouldBe Some("NO_LINKED_ISSUE")
    rejected.importance shouldBe None

  test("a run knows which repository it was mining"):
    val s = store()
    val run = freshRun()
    s.putRun(run, "test/repo")
    s.repoForRun(run) shouldBe Some("test/repo")
    s.repoForRun(RunId.unsafe("run_ffffffffffffffff")) shouldBe None

  test("findings are read back with their evidence counts"):
    val s = store()
    val run = freshRun()
    val ev = evidence(f"ev_${System.nanoTime() & 0xffffffffffffL}%012x0000", run)
    s.putEvidence(ev)

    s.putFinding(s"finding_${run.value.drop(4)}", run, "bugfix-adjudicator", "VERDICT",
      "a" * 40, 0.9, """{"verdict":"BUG_FIX"}""", Vector(ev.id),
      Vector((UnknownReason.NoLinkedIssue, "no issue referenced")))

    val found = s.findingsForRun(run)
    found should have size 1
    found.head.agent shouldBe "bugfix-adjudicator"
    found.head.evidenceCount shouldBe 1
    // Gaps travel with the claim. A low-confidence finding that STATES its gaps is useful data;
    // one that merely looks well-evidenced is corrosive.
    found.head.gaps.head should include("NoLinkedIssue")

  test("findings from another run are not exported with this one"):
    val s = store()
    val mine = freshRun()
    val other = freshRun()
    s.putRun(mine, "test/repo")
    s.putRun(other, "test/repo")

    val ev = evidence(f"ev_${System.nanoTime() & 0xffffffffffffL}%012x0000", other)
    s.putEvidence(ev)
    s.putFinding(s"finding_${other.value.drop(4)}", other, "path-tracer", "PATH",
      "c" * 40, 0.5, "{}", Vector(ev.id), Vector.empty)

    s.findingsForRun(mine) shouldBe empty
    s.findingsForRun(other) should have size 1

  // The defect that cost a real run 40 minutes twice over. Checkpoints MERGE on
  // (runId, stage, key), so a retry under a DIFFERENT key strands the old row — and resumeState
  // counts every non-DONE row as outstanding, so the stage can never read as complete again.
  test("a superseded attempt stops counting as work still to do"):
    val s = store()
    val run = freshRun()

    s.checkpoint(run, "S6_BUILD_GATE", "all_19", "FAILED", "blocked by a defect")
    s.checkpoint(run, "S6_BUILD_GATE", "all", "DONE", "19/19 passed")

    // Before superseding, the stage looks half-finished — which is what sent the orchestrator
    // back to re-probe 37 revisions that had already passed.
    val before = s.resumeState(run).find(_.stage == "S6_BUILD_GATE").get
    before.done shouldBe 1
    before.pending shouldBe 1

    s.checkpoint(run, "S6_BUILD_GATE", "all", "DONE", "19/19 passed",
      supersedes = Vector("all_19")) shouldBe 1

    val after = s.resumeState(run).find(_.stage == "S6_BUILD_GATE").get
    after.done shouldBe 1
    after.pending shouldBe 0

  test("superseding marks, it does not delete"):
    val s = store()
    val run = freshRun()
    s.checkpoint(run, "S7", "first", "FAILED", "a real thing that happened")
    s.checkpoint(run, "S7", "second", "DONE", "", supersedes = Vector("first"))

    // The failed attempt is a true record of the run and belongs in the research output (D2).
    // It simply stops counting as outstanding work.
    s.isDone(run, "S7", "first") shouldBe false
    s.resumeState(run).find(_.stage == "S7").get.pending shouldBe 0

  test("superseding never reaches another stage, or the key doing the superseding"):
    val s = store()
    val run = freshRun()
    s.checkpoint(run, "S6", "shared", "FAILED", "")
    s.checkpoint(run, "S7", "shared", "FAILED", "")

    // Same key name, different stage: S6's row must survive. A later stage erasing an earlier
    // stage's outstanding work is the opposite of what a resume needs to know.
    s.checkpoint(run, "S7", "done", "DONE", "", supersedes = Vector("shared")) shouldBe 1
    s.resumeState(run).find(_.stage == "S6").get.pending shouldBe 1

    // And a checkpoint cannot supersede itself into nonexistence.
    s.checkpoint(run, "S6", "shared", "DONE", "", supersedes = Vector("shared")) shouldBe 0
    s.resumeState(run).find(_.stage == "S6").get.done shouldBe 1

  test("a per-item stage keeps its failures visible"):
    val s = store()
    val run = freshRun()
    // Some stages carry one key per bug. Superseding a failure because a SIBLING succeeded would
    // hide exactly the outcomes this project exists to record, which is why supersedes is named
    // explicitly by the caller rather than inferred.
    s.checkpoint(run, "S9_SYMPTOM", "bug_a", "DONE", "")
    s.checkpoint(run, "S9_SYMPTOM", "bug_b", "FAILED", "no linked issue")

    val state = s.resumeState(run).find(_.stage == "S9_SYMPTOM").get
    state.done shouldBe 1
    state.pending shouldBe 1

  test("evidence is re-attributed from a server's startup id to the mining run"):
    val s = store()
    val serverRun = freshRun()
    val miningRun = freshRun()

    val a = evidence(f"ev_${System.nanoTime() & 0xffffffffffffL}%012xaaaa", serverRun)
    val b = evidence(f"ev_${System.nanoTime() & 0xffffffffffffL}%012xbbbb", serverRun)
    s.putEvidence(a); s.putEvidence(b)

    s.evidenceCount(serverRun) shouldBe 2
    s.evidenceCount(miningRun) shouldBe 0

    s.reattributeEvidence(serverRun, miningRun) shouldBe 2

    // The question "what did this run retrieve" now has the right answer, and the old id is
    // empty rather than holding a second copy.
    s.evidenceCount(miningRun) shouldBe 2
    s.evidenceCount(serverRun) shouldBe 0

  test("re-attributing an id with no evidence moves nothing and does not fail"):
    val s = store()
    s.reattributeEvidence(freshRun(), freshRun()) shouldBe 0

  /** The race that cost a real run its evidence: the client starts this server the instant a
    * session opens, which after a host reboot is before the container is healthy.
    */
  test("connecting waits and reports each attempt, rather than giving up at the first refusal"):
    // A port nothing listens on, so every attempt genuinely fails.
    val nowhere = Neo4jConfig("bolt://localhost:1", "neo4j", "irrelevant")
    var retries = Vector.empty[Int]

    val started = System.currentTimeMillis()
    val result = Neo4jStore.connectWaiting(
      nowhere,
      attempts = 3,
      delay = java.time.Duration.ofMillis(50),
      onRetry = (n, _) => retries = retries :+ n
    )
    val elapsed = System.currentTimeMillis() - started

    result.isLeft shouldBe true
    // The count is in the message, so a log line says how long it actually waited.
    result.left.toOption.get should include("after 3 attempts")

    // Two sleeps between three attempts — the last failure returns rather than waiting again.
    retries shouldBe Vector(1, 2)
    elapsed should be >= 100L

  test("a reachable database is returned on the first attempt, with no waiting"):
    val s = store()   // cancels if Neo4j is not running
    val started = System.currentTimeMillis()
    var retried = false

    val result = Neo4jStore.connectWaiting(
      Neo4jConfig.fromEnv(), attempts = 10,
      delay = java.time.Duration.ofSeconds(3),
      onRetry = (_, _) => retried = true
    )

    result.isRight shouldBe true
    // Waiting must cost nothing in the normal case, or nobody will keep it.
    retried shouldBe false
    (System.currentTimeMillis() - started) should be < 3000L
    result.toOption.foreach(_.close())
