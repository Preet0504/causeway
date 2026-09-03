package causeway.graphstore

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** RFC 4180 CSV writing. Nothing here touches the network or the filesystem — it takes rows,
  * returns text, and is exercised entirely by unit tests.
  */
object Csv:

  /** Quote a field only when it needs it, per RFC 4180: a value containing the delimiter, a
    * quote, or a newline must be wrapped in quotes with embedded quotes doubled. A commit
    * message or a rationale WILL contain commas, so this is not optional polish — an
    * unescaped field silently shifts every column after it on that row.
    */
  def field(v: String): String =
    if v.isEmpty then ""
    else if v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + v.replace("\"", "\"\"") + "\""
    else v

  def row(cells: Seq[String]): String = cells.map(field).mkString(",")

  def file(header: Seq[String], rows: Seq[Seq[String]]): String =
    (row(header) +: rows.map(row)).mkString("\r\n") + "\r\n"

  /** Short lists that belong in one cell rather than a companion table use `;`, never `,` —
    * commas are already claimed by the CSV delimiter itself, and joining a list with the same
    * character used to separate columns would make the join ambiguous the moment a joined value
    * itself needs quoting.
    */
  def joinList(vs: Seq[String]): String = vs.mkString(";")

object ClaimField:
  /** Read one field from a parsed claim tree, tolerantly.
    *
    * `Finding.claim` is agent-authored JSON, not a fixed contract enforced by any schema — two
    * agents recording the same claimType are not guaranteed to have populated exactly the same
    * keys, and a future agent version may add or rename one. Every accessor here returns an
    * empty result rather than throwing when a field is absent or the wrong shape, on the same
    * principle `Signal[A]` applies everywhere else: a field this export cannot find is reported
    * as not present, never coerced into an empty string that reads identically to "was checked
    * and found to be empty".
    */
  def str(n: JsonNode, field: String): Option[String] =
    Option(n.get(field)).filter(f => f.isTextual).map(_.stringValue())

  def num(n: JsonNode, field: String): Option[Double] =
    Option(n.get(field)).filter(f => f.isNumber).map(_.doubleValue())

  def bool(n: JsonNode, field: String): Option[Boolean] =
    Option(n.get(field)).filter(f => f.isBoolean).map(_.booleanValue())

  def strArray(n: JsonNode, field: String): Vector[String] =
    Option(n.get(field)).filter(_.isArray)
      .map(_.values().asScala.toVector.filter(_.isTextual).map(_.stringValue()))
      .getOrElse(Vector.empty)

  def arrayNode(n: JsonNode, field: String): Vector[JsonNode] =
    Option(n.get(field)).filter(_.isArray).map(_.values().asScala.toVector).getOrElse(Vector.empty)

  def objectNode(n: JsonNode, field: String): Option[JsonNode] =
    Option(n.get(field)).filter(_.isObject)

  // A parser local to this module, not shared with modules/mcpserver's Json helper: graphstore
  // deliberately has no dependency on mcpserver (D5-style layering — the store must not need the
  // transport), so this reads Jackson directly instead.
  private val mapper = JsonMapper.builder().build()

  def parse(claimJson: String): JsonNode =
    Try(mapper.readTree(claimJson)).getOrElse(mapper.createObjectNode())

/** Flattens one bug's graph-native findings (Bug row + its Verdict/Symptom/Path/Reproducer
  * findings) into the researcher-facing `bugs.csv` row shape.
  *
  * The one design decision concentrated here: WHICH confidence number a claim type contributes.
  * `Finding.confidence` is not the same kind of value across claim types — for Verdict and Path
  * it is the only confidence an agent ever recorded, and it varies meaningfully (Verdict ranged
  * 0.65-0.98 on a real run). For Symptom it was observed UNIFORMLY 0.5 across every finding in
  * that same run, a placeholder, while the real varying signal (0.75, 0.65, ...) sits inside the
  * claim JSON's own `confidence` field. Reproducer's shape has not been verified against live
  * data — the causeway server was unreachable when this was written — so it is treated the SAME
  * as Verdict/Path (keep the outer value) rather than guessed the other way, because keeping an
  * unverified column is reversible and dropping one that turns out to matter is not. Every choice
  * here is stated in the generated codebook, not just decided silently in code.
  */
final case class BugRecord(
    runId: String, repo: String, fixSha: String,
    admitted: Boolean, admittedReason: Option[String],
    parentSha: Option[String], oldRanges: Vector[RangeRow], newRanges: Vector[RangeRow],
    verdict: Option[String], verdictConfidence: Option[Double], verdictRationale: Option[String],
    symptomClass: Option[String], symptomSecondaryClasses: Vector[String],
    symptomDescription: Option[String], symptomConfidence: Option[Double],
    symptomEntryPoint: Option[String], symptomEntryPointFile: Option[String],
    symptomGaps: Vector[String],
    reproducerTier: Option[String], reproducerConfidence: Option[Double],
    reproducerTestSelector: Option[String], reproducerDiffers: Option[Boolean],
    reproducerDiffersOn: Vector[String], reproducerResultAtParent: Option[String],
    reproducerResultAtFix: Option[String], reproducerTraceId: Option[String],
    reproducerExecutedMethodCount: Option[Double],
    pathTier: Option[String], pathFidelity: Option[String], pathConfidence: Option[Double],
    pathHopCount: Option[Double], pathObservedSteps: Option[Double],
    pathUnmeasurableSteps: Option[Double], pathInfectionPoint: Option[String],
    pathPropagationChannel: Option[String],
    importance: Option[Double], scoredOn: Vector[String],
    findingCount: Int, evidenceCount: Int
)

/** One hop of one bug's path, denormalised out of the Path finding's `claim.hops[]` array. */
final case class PathHopRecord(
    runId: String, fixSha: String, hopN: Int,
    fromMethod: Option[String], toMethod: Option[String], relation: Option[String],
    carrier: Option[String], oldLines: Option[String], executed: Option[Boolean],
    evidenceIds: Vector[String]
)

object DatasetAssembler:

  private val PerBugTypes = Set("Verdict", "Symptom", "Path", "Reproducer")

  /** One [[BugRecord]] per admitted-or-not [[BugRow]], folding in whichever findings exist for
    * it. A bug with no Path finding yet (never reached S11) still gets a row — the absent
    * columns are the honest report that this stage has not run for it, not an error.
    */
  def bugRecords(
      runId: String, bugs: Vector[BugRow], findings: Vector[FindingWithEvidence]
  ): Vector[BugRecord] =
    val byFix = findings.groupBy(_.subject)
    bugs.map { b =>
      val fs = byFix.getOrElse(b.fixSha, Vector.empty)
      def one(claimType: String): Option[FindingWithEvidence] = fs.find(_.claimType == claimType)

      val verdict    = one("Verdict")
      val symptom    = one("Symptom")
      val path       = one("Path")
      val reproducer = one("Reproducer")

      val symptomClaim = symptom.map(f => ClaimField.parse(f.claimJson))
      val pathClaim     = path.map(f => ClaimField.parse(f.claimJson))
      val reproClaim    = reproducer.map(f => ClaimField.parse(f.claimJson))

      val hops = pathClaim.map(c => ClaimField.arrayNode(c, "hops")).getOrElse(Vector.empty)
      val observedSteps     = hops.count(h => ClaimField.bool(h, "executed").contains(true))
      val unmeasurableSteps = hops.count(h => ClaimField.bool(h, "executed").isEmpty)

      val allEvidence = fs.flatMap(_.evidenceIds).distinct

      BugRecord(
        runId = runId, repo = b.repo, fixSha = b.fixSha,
        admitted = b.admitted, admittedReason = b.rejectedFor,
        parentSha = b.parentSha, oldRanges = b.oldRanges, newRanges = b.newRanges,

        verdict = verdict.map(f => ClaimField.parse(f.claimJson))
          .flatMap(c => ClaimField.str(c, "verdict")),
        verdictConfidence = verdict.map(_.confidence),
        verdictRationale = verdict.map(f => ClaimField.parse(f.claimJson))
          .flatMap(c => ClaimField.str(c, "rationale").orElse(ClaimField.str(c, "summary"))),

        symptomClass = symptomClaim.flatMap(c => ClaimField.str(c, "class")),
        symptomSecondaryClasses = symptomClaim.map(c => ClaimField.strArray(c, "secondaryClasses"))
          .getOrElse(Vector.empty),
        symptomDescription = symptomClaim.flatMap(c => ClaimField.str(c, "description")),
        // The resolved rule: Symptom's OWN confidence, not the Finding wrapper's uniform 0.5.
        symptomConfidence = symptomClaim.flatMap(c => ClaimField.num(c, "confidence")),
        symptomEntryPoint = symptomClaim.flatMap(c => ClaimField.objectNode(c, "entryPoint"))
          .flatMap { ep =>
            for
              fqcn <- ClaimField.str(ep, "fqcn"); name <- ClaimField.str(ep, "name")
              desc <- ClaimField.str(ep, "descriptor")
            yield s"$fqcn#$name($desc)"
          },
        symptomEntryPointFile = symptomClaim.flatMap(c => ClaimField.objectNode(c, "entryPoint"))
          .flatMap(ep => ClaimField.str(ep, "sourceFile")),
        symptomGaps = symptomClaim.map(c => ClaimField.arrayNode(c, "gaps").flatMap { g =>
          for r <- ClaimField.str(g, "reason"); d <- ClaimField.str(g, "detail") yield s"$r:$d"
        }).getOrElse(Vector.empty),

        reproducerTier = b.reproducerTier,
        // Unverified against live data as of this writing (see class doc) — kept, not guessed away.
        reproducerConfidence = reproducer.map(_.confidence),
        reproducerTestSelector = reproClaim.flatMap(c => ClaimField.str(c, "testSelector")),
        reproducerDiffers = reproClaim.flatMap(c => ClaimField.bool(c, "differs")),
        reproducerDiffersOn = reproClaim.map(c => ClaimField.strArray(c, "differsOn")).getOrElse(Vector.empty),
        reproducerResultAtParent = reproClaim.flatMap(c => ClaimField.str(c, "resultAtParent")),
        reproducerResultAtFix = reproClaim.flatMap(c => ClaimField.str(c, "resultAtFix")),
        reproducerTraceId = reproClaim.flatMap(c => ClaimField.str(c, "traceId")),
        reproducerExecutedMethodCount = reproClaim.flatMap(c => ClaimField.num(c, "executedMethodCount")),

        pathTier = pathClaim.flatMap(c => ClaimField.str(c, "pathTier")),
        pathFidelity = b.pathFidelity,
        pathConfidence = path.map(_.confidence),
        pathHopCount = if hops.nonEmpty || path.isDefined then Some(hops.size.toDouble) else None,
        pathObservedSteps = if path.isDefined then Some(observedSteps.toDouble) else None,
        pathUnmeasurableSteps = if path.isDefined then Some(unmeasurableSteps.toDouble) else None,
        pathInfectionPoint = pathClaim.flatMap(c => ClaimField.str(c, "infectionPoint")),
        pathPropagationChannel = pathClaim.flatMap(c => ClaimField.str(c, "propagationChannel")),

        importance = b.importance, scoredOn = b.scoredOn,
        findingCount = fs.size, evidenceCount = allEvidence.size
      )
    }

  /** Every hop of every Path finding, denormalised. A zero-hop path (entry point is the fault
    * site) contributes no rows here by construction, not by omission — there is nothing to list.
    */
  def pathHops(runId: String, findings: Vector[FindingWithEvidence]): Vector[PathHopRecord] =
    findings.filter(_.claimType == "Path").flatMap { f =>
      val claim = ClaimField.parse(f.claimJson)
      ClaimField.arrayNode(claim, "hops").zipWithIndex.map { (h, i) =>
        val n = ClaimField.num(h, "n").map(_.toInt).getOrElse(i)
        PathHopRecord(
          runId = runId, fixSha = f.subject, hopN = n,
          fromMethod = ClaimField.str(h, "from"), toMethod = ClaimField.str(h, "to"),
          relation = ClaimField.str(h, "relation"), carrier = ClaimField.str(h, "carrier"),
          oldLines = ClaimField.str(h, "oldLines"), executed = ClaimField.bool(h, "executed"),
          evidenceIds = ClaimField.strArray(h, "evidence")
        )
      }
    }

  // ── rendering ────────────────────────────────────────────────────────────

  private def s(o: Option[String]): String = o.getOrElse("")
  private def d(o: Option[Double]): String = o.map(v => if v == v.toLong then v.toLong.toString else v.toString).getOrElse("")
  private def b(o: Option[Boolean]): String = o.map(_.toString).getOrElse("")

  val BugColumns: Seq[String] = Seq(
    "run_id", "repo", "fix_sha", "admitted", "admitted_reason",
    "parent_sha", "old_ranges", "new_ranges",
    "verdict", "verdict_confidence", "verdict_rationale",
    "symptom_class", "symptom_secondary_classes", "symptom_description", "symptom_confidence",
    "symptom_entry_point", "symptom_entry_point_file", "symptom_gaps",
    "reproducer_tier", "reproducer_confidence", "reproducer_test_selector",
    "reproducer_differs", "reproducer_differs_on", "reproducer_result_at_parent",
    "reproducer_result_at_fix", "reproducer_trace_id", "reproducer_executed_method_count",
    "path_tier", "path_fidelity", "path_confidence", "path_hop_count",
    "path_observed_steps", "path_unmeasurable_steps", "path_observed_fraction",
    "path_infection_point", "path_propagation_channel",
    "importance", "scored_on", "finding_count", "evidence_count"
  )

  def bugRow(r: BugRecord): Seq[String] =
    val observedFraction = for hc <- r.pathHopCount if hc > 0; os <- r.pathObservedSteps
      yield (os / hc).toString
    Seq(
      r.runId, r.repo, r.fixSha, r.admitted.toString, s(r.admittedReason),
      s(r.parentSha), Csv.joinList(r.oldRanges.map(_.toString)), Csv.joinList(r.newRanges.map(_.toString)),
      s(r.verdict), d(r.verdictConfidence), s(r.verdictRationale),
      s(r.symptomClass), Csv.joinList(r.symptomSecondaryClasses), s(r.symptomDescription),
      d(r.symptomConfidence), s(r.symptomEntryPoint), s(r.symptomEntryPointFile),
      Csv.joinList(r.symptomGaps),
      s(r.reproducerTier), d(r.reproducerConfidence), s(r.reproducerTestSelector),
      b(r.reproducerDiffers), Csv.joinList(r.reproducerDiffersOn), s(r.reproducerResultAtParent),
      s(r.reproducerResultAtFix), s(r.reproducerTraceId), d(r.reproducerExecutedMethodCount),
      s(r.pathTier), s(r.pathFidelity), d(r.pathConfidence), d(r.pathHopCount),
      d(r.pathObservedSteps), d(r.pathUnmeasurableSteps), observedFraction.getOrElse(""),
      s(r.pathInfectionPoint), s(r.pathPropagationChannel),
      d(r.importance), Csv.joinList(r.scoredOn), r.findingCount.toString, r.evidenceCount.toString
    )

  val PathHopColumns: Seq[String] =
    Seq("run_id", "fix_sha", "hop_n", "from_method", "to_method", "relation", "carrier",
        "old_lines", "executed", "evidence_ids")

  def pathHopRow(r: PathHopRecord): Seq[String] = Seq(
    r.runId, r.fixSha, r.hopN.toString, s(r.fromMethod), s(r.toMethod), s(r.relation),
    s(r.carrier), s(r.oldLines), b(r.executed), Csv.joinList(r.evidenceIds)
  )

  val FindingColumns: Seq[String] =
    Seq("run_id", "fix_sha", "finding_id", "claim_type", "agent", "confidence",
        "evidence_ids", "gap_reasons")

  /** `confidence` here is ALWAYS the raw `Finding.confidence`, uniformly, for every claim type —
    * unlike `bugs.csv`'s per-claim-type columns. This file is the ledger: it reports what the
    * graph actually says, not a curated view. The curation decisions live in `bugRow` and are
    * explained in the codebook; this table exists so they can be checked against the source.
    */
  def findingRow(f: FindingWithEvidence): Seq[String] = Seq(
    f.runId, f.subject, f.findingId, f.claimType, f.agent, f.confidence.toString,
    Csv.joinList(f.evidenceIds), Csv.joinList(f.gaps)
  )

  val EvidenceColumns: Seq[String] =
    Seq("evidence_id", "tool", "args_hash", "payload_hash", "at", "run_id", "provenance")

  def evidenceRow(e: EvidenceRow): Seq[String] =
    Seq(e.id, e.tool, e.argsHash, e.payloadHash, e.at, e.runId, e.provenance)

  val SchemaVersion = "1.0"

  /** The companion document every export ships with. CSV headers cannot carry a type, a unit,
    * an enum's legend, or the mental model that makes a column mean anything, so a reader with no
    * prior context cannot fully understand the dataset from the CSVs alone — this is what makes
    * that possible. Generated fresh per export rather than shipped as a static file, so it can
    * never drift out of sync with the schema version that actually produced the accompanying
    * files.
    */
  def codebook(runId: String, generatedAt: String): String =
    s"""# Causeway dataset codebook
       |
       |Generated $generatedAt for run `$runId`. Schema version $SchemaVersion.
       |
       |## 1. Overview
       |
       |Each row in `bugs.csv` is one commit adjudicated as a genuine bug fix during a mining run,
       |together with everything the pipeline determined about it: where the fault was, what an
       |observer saw, whether a reproducer was obtained, and the ordered route connecting the two.
       |
       |Both endpoints of that route are known by construction — the fix commit names the fault,
       |the symptom names where it surfaced — so this is not fault localization. The deliverable is
       |the PATH between two known points, and the central distinction in this dataset is whether
       |that path was OBSERVED (confirmed by watching code execute) or only HYPOTHESIZED (drawn
       |from static structure with nothing to confirm it). A bug can also be DISCONNECTED: a
       |defect that propagates through shared object state rather than through call edges has no
       |route a call graph can express, and that is a recorded finding, not a missing row.
       |
       |A missing value is never a negative result. Where a column is empty, a stage has not run
       |or could not determine an answer for a stated reason (see `admitted_reason` and the gap
       |columns) — it is never coerced to zero or false.
       |
       |## 2. Files and their keys
       |
       || file | grain | primary key | foreign key |
       ||---|---|---|---|
       || `bugs.csv` | one row per bug | `(run_id, fix_sha)` | — |
       || `path_hops.csv` | one row per path hop | `(run_id, fix_sha, hop_n)` | `(run_id, fix_sha) -> bugs.csv` |
       || `findings.csv` | one row per agent claim | `finding_id` | `(run_id, fix_sha) -> bugs.csv`, with the exception noted below |
       || `evidence.csv` | one row per retrieved tool payload | `evidence_id` | referenced by `findings.csv.evidence_ids` |
       || `run_metadata.json` | one record for the whole run | `run_id` | — |
       |
       |Exception: two claim types in `findings.csv` — `BuildRecipe` and `Strategy` — describe the
       |run as a whole rather than any one bug, and do not join to `bugs.csv`. They appear only in
       |`run_metadata.json` instead.
       |
       |`repo` in `bugs.csv` is a convenience column, not part of the key: one mining run targets
       |exactly one repository, so it is fully determined by `run_id`.
       |
       |`findings.csv.evidence_ids` and `path_hops.csv.evidence_ids` are semicolon-joined lists of
       |`evidence.csv.evidence_id` values — a many-to-many relationship represented as a delimited
       |column rather than a bridge table. Split on `;` to recover the individual ids.
       |
       |## 3. bugs.csv column reference
       |
       |`bugs.csv` is a CURATED view: where a claim type carries more than one confidence-shaped
       |number, one column is chosen and the choice is stated below. `findings.csv` is the ledger
       |underneath it and always reports the raw `Finding.confidence` for every claim, regardless
       |of this curation, so any choice made here can be checked against the source.
       |
       || column | meaning |
       ||---|---|
       || `admitted` | `true` if both the fix and its parent revision compiled (the hard build gate). |
       || `admitted_reason` | Set only when `admitted=false`. One of: `DependencyResolution`, `JdkMismatch`, `MissingToolchain`, `CompileError`, `Timeout`. |
       || `parent_sha` | The pre-fix revision. Resolved once by S5, not re-derived by the reader: a fix commit with more than one parent does not have an obviously correct "the buggy one" without doing the same resolution S5 already did. |
       || `old_ranges` | The changed lines in PARENT coordinates, `file:start-end` pairs joined by `;`. This is what the fault actually looks like: check out `parent_sha`, and these are the exact lines to inspect or revert-inject to reproduce the pre-fix behaviour. |
       || `new_ranges` | The same spans in FIX coordinates. Never mix the two: asking the parent revision about a new-side line number returns a different, unrelated line, plausibly and silently. |
       || `verdict` | `BUG_FIX`, `NOT_BUG_FIX`, or `UNDECIDED` from adjudication. Only `BUG_FIX` rows proceed past S4. |
       || `verdict_confidence` | The adjudicator's own `Finding.confidence` — confirmed to vary meaningfully (0.65-0.98 on a real run) and is the only confidence value this claim type carries. |
       || `symptom_class` | Primary symptom category, e.g. `CRASH_UNCAUGHT_EXCEPTION`, `DATA_CORRUPTION_OR_LOSS`, `INCORRECT_OUTPUT`. |
       || `symptom_confidence` | The symptom claim's OWN confidence field, not the outer `Finding.confidence`. On a real run the outer value was a uniform 0.5 across every symptom finding and carried no signal; the real varying number was nested inside the claim. |
       || `symptom_entry_point` | `fqcn#name(descriptor)` of the public method where the symptom surfaced. This, not the fault site, is where path length is measured from. |
       || `symptom_gaps` | Semicolon-joined `reason:detail` pairs the characterizing agent could not resolve, e.g. `NoLinkedIssue:...`. |
       || `reproducer_tier` | `T1_NATIVE` (the project's own regression test, ported to the parent and run there) down to `T4_STATIC` (nothing reproduces; the path is necessarily hypothesised). |
       || `reproducer_confidence` | UNVERIFIED as of schema version $SchemaVersion — kept rather than dropped because a column is cheap to ignore and expensive to have silently deleted. Treated the same as verdict/path (outer `Finding.confidence` kept) until confirmed against live data. |
       || `reproducer_differs_on` | How the parent and fix diverged: `EXCEPTION`, `RETURN`, or `ASSERTION`. |
       || `path_tier` | `OBSERVED` (every hop confirmed by execution), `HYPOTHESIZED` (drawn from the static call graph alone), or `DISCONNECTED` (no route exists between the two endpoints — a finding about the defect's shape, not a failure to trace one). |
       || `path_confidence` | The path-tracer's own `Finding.confidence`; confirmed to vary meaningfully, same treatment as `verdict_confidence`. |
       || `path_observed_steps` / `path_hop_count` | A hop counts as observed only when BOTH its endpoint methods appear in the reproducer's executed method set. This is METHOD-level evidence — the instrumentation records that a method ran, not that a specific call edge was taken between two running methods. |
       || `path_unmeasurable_steps` | Hops whose executed status could not be determined at all (for example, a javac-generated synthetic accessor bridge the coverage instrument does not report), distinct from a hop that was checked and found not to have run. |
       || `path_observed_fraction` | Derived: `path_observed_steps / path_hop_count`, undefined for a zero-hop path. Not returned by any tool — computed here as the number most researchers will want to sort or filter by. |
       || `importance` | Composite ranking score in [0,1], computed ONLY over the dimensions listed in `scored_on`. |
       || `scored_on` | Semicolon-joined list of which ranking dimensions were actually Known for this bug. Two bugs' `importance` values are only comparable if `scored_on` matches — a composite over three dimensions and one over seven are not the same kind of number. |
       |
       |## 4. Worked join, structurally
       |
       |Given one row in `bugs.csv` with `fix_sha = abc123`:
       |
       |```
       |bugs.csv        1 row   where fix_sha = abc123
       |path_hops.csv   N rows  where fix_sha = abc123, one per hop, ordered by hop_n
       |findings.csv    M rows  where fix_sha = abc123, one per agent claim about this bug
       |evidence.csv    K rows  where evidence_id appears in any of those M findings' evidence_ids
       |```
       |
       |A fully worked real bug, traced through every stage with the actual agent output at each
       |step, is in `example.md` in the project repository. This codebook documents the shape;
       |that document shows one real instance of it end to end.
       |
       |## 5. Known caveats
       |
       |- `path_tier=OBSERVED` is method-level evidence, not edge-level. See `path_observed_steps`.
       |- `path_tier=DISCONNECTED` is a positive finding about the defect's propagation mechanism
       |  (typically shared mutable state rather than a call), not a tracing failure.
       |- Confidence values are NOT calibrated across claim types. A 0.75 from an adjudicator and a
       |  0.75 from a path-tracer are not the same kind of number and should not be averaged.
       |- `reproducer_confidence` is unverified — see the note in section 3.
       |- Rejected bugs (`admitted=false`) are retained deliberately, with every column past
       |  `admitted_reason` absent rather than populated. Excluding them would hide the dataset's
       |  own selection bias behind a cleaner-looking file.
       |""".stripMargin
