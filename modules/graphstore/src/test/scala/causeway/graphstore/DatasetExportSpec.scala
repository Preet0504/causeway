package causeway.graphstore

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The dataset export is the researcher-facing deliverable, and its two most dangerous failure
  * modes are both silent: a mis-escaped CSV cell shifts every column after it on that row with
  * no error, and a wrong confidence field produces a column of plausible-looking numbers that
  * mean the opposite of what a reader assumes. Both are pinned here.
  */
class DatasetExportSpec extends AnyFunSuite with Matchers:

  // ── Csv ──────────────────────────────────────────────────────────────────

  test("a plain field is not quoted"):
    Csv.field("T1_NATIVE") shouldBe "T1_NATIVE"

  test("a field containing the delimiter is quoted"):
    Csv.field("fix, then a comma") shouldBe "\"fix, then a comma\""

  test("a field containing a quote has it doubled, and the whole field quoted"):
    Csv.field("""said "hello"""") shouldBe "\"said \"\"hello\"\"\""

  test("a field containing a newline is quoted"):
    Csv.field("line one\nline two") shouldBe "\"line one\nline two\""

  test("a commit message with a comma does not shift subsequent columns"):
    val text = Csv.row(Seq("abc123", "Fix null check, add test", "BUG_FIX"))
    // Naive comma-splitting a real CSV parser would run must recover exactly 3 fields, not 4.
    text shouldBe """abc123,"Fix null check, add test",BUG_FIX"""

  test("joinList uses semicolon, never comma, so a joined cell never needs its own quoting"):
    Csv.joinList(Seq("faultLineCoverage", "blastRadius")) shouldBe "faultLineCoverage;blastRadius"

  test("file renders a header row and uses CRLF line endings per RFC 4180"):
    val out = Csv.file(Seq("a", "b"), Seq(Seq("1", "2")))
    out shouldBe "a,b\r\n1,2\r\n"

  // ── ClaimField: tolerant reading ────────────────────────────────────────

  private def claim(json: String) = ClaimField.parse(json)

  test("a present string field is read"):
    ClaimField.str(claim("""{"verdict":"BUG_FIX"}"""), "verdict") shouldBe Some("BUG_FIX")

  test("an absent field returns None, not an empty string"):
    ClaimField.str(claim("""{"verdict":"BUG_FIX"}"""), "rationale") shouldBe None

  test("a field present but of the wrong type returns None rather than throwing"):
    ClaimField.str(claim("""{"confidence":0.75}"""), "confidence") shouldBe None
    ClaimField.num(claim("""{"confidence":"high"}"""), "confidence") shouldBe None

  test("malformed JSON parses to an empty object rather than throwing"):
    ClaimField.str(claim("not valid json{{{"), "anything") shouldBe None

  // ── the confidence resolution rule ──────────────────────────────────────
  //
  // This is the decision the whole conversation converged on: Symptom's real signal is nested
  // inside the claim; Verdict and Path have no inner confidence at all, so the outer
  // Finding.confidence IS the signal and must be kept.

  private def fwe(claimType: String, confidence: Double, claimJson: String, subject: String = "abc123") =
    FindingWithEvidence(
      findingId = s"f_$claimType", subject = subject, runId = "run_x", agent = "test-agent",
      claimType = claimType, confidence = confidence, claimJson = claimJson,
      gaps = Vector.empty, evidenceIds = Vector("ev_1")
    )

  private val bug = BugRow("jhy/jsoup", "abc123", admitted = true, None, Some(1.0),
    Vector("faultLineCoverage"), None, None)

  test("symptom_confidence comes from the claim's OWN confidence, not the outer Finding.confidence"):
    // Observed on a real run: the outer Finding.confidence was a uniform 0.5 placeholder across
    // every symptom finding, while the claim's own confidence (0.75, 0.65, ...) carried the
    // actual varying signal. Getting this backwards silently deletes the only real number.
    val f = fwe("Symptom", confidence = 0.5, claimJson = """{"class":"CRASH","confidence":0.75}""")
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.symptomConfidence shouldBe Some(0.75)
    rec.symptomConfidence should not be Some(0.5)

  test("verdict_confidence comes from the outer Finding.confidence, since verdict claims carry no inner one"):
    val f = fwe("Verdict", confidence = 0.92, claimJson = """{"verdict":"BUG_FIX","summary":"..."}""")
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.verdictConfidence shouldBe Some(0.92)

  test("path_confidence comes from the outer Finding.confidence, same treatment as verdict"):
    val f = fwe("Path", confidence = 0.86, claimJson = """{"pathTier":"OBSERVED","hops":[]}""")
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.pathConfidence shouldBe Some(0.86)

  test("parent_sha and the changed-line ranges flow from BugRow through to the rendered row"):
    // This is the whole point of persisting S5's output onto the Bug node at all: a user reading
    // bugs.csv should be able to check out parent_sha and know exactly which lines to look at,
    // without re-deriving the diff themselves or guessing which parent of a merge is the real one.
    val withRanges = bug.copy(
      parentSha = Some("be8c3756a1f2"),
      oldRanges = Vector(RangeRow("src/main/java/A.java", 122, 123), RangeRow("src/main/java/A.java", 125, 125)),
      newRanges = Vector(RangeRow("src/main/java/A.java", 122, 124))
    )
    val rec = DatasetAssembler.bugRecords("run_x", Vector(withRanges), Vector.empty).head
    val row = DatasetAssembler.bugRow(rec)
    row(DatasetAssembler.BugColumns.indexOf("parent_sha")) shouldBe "be8c3756a1f2"
    row(DatasetAssembler.BugColumns.indexOf("old_ranges")) shouldBe
      "src/main/java/A.java:122-123;src/main/java/A.java:125-125"
    row(DatasetAssembler.BugColumns.indexOf("new_ranges")) shouldBe "src/main/java/A.java:122-124"

  test("RangeRow round-trips through its string encoding, since that is how it is stored on the Bug node"):
    val r = RangeRow("src/main/java/org/jsoup/nodes/Attributes.java", 122, 123)
    RangeRow.parse(r.toString) shouldBe Some(r)
    RangeRow.parse("not a range") shouldBe None

  test("a bug with no findings at all still produces a row, with the absent columns empty"):
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector.empty).head
    rec.verdict shouldBe None
    rec.pathTier shouldBe None
    rec.findingCount shouldBe 0
    // Rendering must not throw on an all-empty record.
    DatasetAssembler.bugRow(rec).size shouldBe DatasetAssembler.BugColumns.size

  // ── path hops and the observed/unmeasurable/unobserved distinction ─────

  test("a hop with executed:true counts as observed; executed:false counts as neither"):
    val claimJson =
      """{"pathTier":"OBSERVED","hops":[
        |  {"n":0,"from":"A#f","to":"B#g","relation":"DIRECT_CALL","executed":true,"evidence":["ev_1"]},
        |  {"n":1,"from":"B#g","to":"C#h","relation":"DIRECT_CALL","executed":false,"evidence":["ev_2"]}
        |]}""".stripMargin
    val f = fwe("Path", confidence = 0.8, claimJson = claimJson)
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.pathHopCount shouldBe Some(2.0)
    rec.pathObservedSteps shouldBe Some(1.0)
    rec.pathUnmeasurableSteps shouldBe Some(0.0)

  test("a hop with NO executed key is unmeasurable, distinct from one confirmed not to have run"):
    // A synthetic accessor bridge JaCoCo does not report is neither confirmed executed nor
    // confirmed not-executed; it is a third state, and collapsing it into "not observed" would
    // understate the fraction exactly where the underlying route is genuinely unknown rather
    // than genuinely refuted.
    val claimJson = """{"hops":[{"n":0,"from":"A#f","to":"B#g","relation":"DIRECT_CALL"}]}"""
    val f = fwe("Path", confidence = 0.7, claimJson = claimJson)
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.pathObservedSteps shouldBe Some(0.0)
    rec.pathUnmeasurableSteps shouldBe Some(1.0)

  test("a zero-hop path yields hopCount 0 and no observed_fraction, not a division by zero"):
    val f = fwe("Path", confidence = 0.9, claimJson = """{"pathTier":"OBSERVED","hops":[]}""")
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    rec.pathHopCount shouldBe Some(0.0)
    val row = DatasetAssembler.bugRow(rec)
    val idx = DatasetAssembler.BugColumns.indexOf("path_observed_fraction")
    row(idx) shouldBe ""

  test("path_observed_fraction is the ratio, for a path with a mix of both"):
    val claimJson =
      """{"hops":[
        |  {"n":0,"from":"A","to":"B","executed":true},
        |  {"n":1,"from":"B","to":"C","executed":true},
        |  {"n":2,"from":"C","to":"D","executed":false},
        |  {"n":3,"from":"D","to":"E","executed":true}
        |]}""".stripMargin
    val f = fwe("Path", confidence = 0.75, claimJson = claimJson)
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector(f)).head
    val row = DatasetAssembler.bugRow(rec)
    val idx = DatasetAssembler.BugColumns.indexOf("path_observed_fraction")
    row(idx).toDouble shouldBe (0.75 +- 0.0001)

  test("pathHops denormalises every hop of every Path finding, keyed by fix_sha and hop_n"):
    val claimJson =
      """{"hops":[
        |  {"n":0,"from":"A#f","to":"B#g","relation":"DIRECT_CALL","carrier":"delegation",
        |   "oldLines":"12-14","executed":true,"evidence":["ev_1","ev_2"]}
        |]}""".stripMargin
    val f = fwe("Path", confidence = 0.8, claimJson = claimJson, subject = "abc123")
    val hops = DatasetAssembler.pathHops("run_x", Vector(f))
    hops.size shouldBe 1
    hops.head.fixSha shouldBe "abc123"
    hops.head.hopN shouldBe 0
    hops.head.fromMethod shouldBe Some("A#f")
    hops.head.evidenceIds shouldBe Vector("ev_1", "ev_2")

  test("hop n falls back to array index when the claim omits it"):
    val claimJson = """{"hops":[{"from":"A","to":"B"},{"from":"B","to":"C"}]}"""
    val f = fwe("Path", confidence = 0.6, claimJson = claimJson)
    val hops = DatasetAssembler.pathHops("run_x", Vector(f))
    hops.map(_.hopN) shouldBe Vector(0, 1)

  // ── row/column shape stays consistent ───────────────────────────────────

  test("every rendered row has exactly as many cells as its header has columns"):
    val rec = DatasetAssembler.bugRecords("run_x", Vector(bug), Vector.empty).head
    DatasetAssembler.bugRow(rec).size shouldBe DatasetAssembler.BugColumns.size

    val hop = PathHopRecord("run_x", "abc123", 0, None, None, None, None, None, None, Vector.empty)
    DatasetAssembler.pathHopRow(hop).size shouldBe DatasetAssembler.PathHopColumns.size

    val finding = fwe("Verdict", 0.9, """{"verdict":"BUG_FIX"}""")
    DatasetAssembler.findingRow(finding).size shouldBe DatasetAssembler.FindingColumns.size

    val evidence = EvidenceRow("ev_1", "history_diff", "h1", "h2", "2026-01-01T00:00:00Z", "run_x", "ServerIssued")
    DatasetAssembler.evidenceRow(evidence).size shouldBe DatasetAssembler.EvidenceColumns.size

  test("findings.csv reports the raw Finding.confidence uniformly, unlike bugs.csv's curated columns"):
    // The ledger must not apply the same per-claim-type substitution bugs.csv does — findings.csv
    // exists specifically so the curation in bugs.csv can be checked against the source.
    val f = fwe("Symptom", confidence = 0.5, claimJson = """{"confidence":0.75}""")
    DatasetAssembler.findingRow(f)(DatasetAssembler.FindingColumns.indexOf("confidence")) shouldBe "0.5"

  test("the generated codebook names the actual column headers, so it cannot silently drift"):
    val text = DatasetAssembler.codebook("run_x", "2026-01-01T00:00:00Z")
    text should include("run_x")
    text should include("bugs.csv")
    text should include("path_hops.csv")
    text should include("OBSERVED")
    text should include("DISCONNECTED")
