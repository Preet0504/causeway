package causeway.app

import causeway.core.{CoverageStatus, JvmBuildTool, OldLines, UnknownReason}
import causeway.jvm.{BuildProbe, Container, CoverageService, NativeTestRunner}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import causeway.vcs.{Candidates, Szz, SzzVariant}
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.nio.file.Path
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** SZZ and coverage lookups. */
object AnalysisHandlers:

  def all(handles: Handles): Vector[ToolHandler] = Vector(
    szzBaseline(handles),
    coverageRun(handles),
    nativeTestRun(handles),
    coverageLines(handles),
    repoCheckout(handles)
  )

  /** Test counts, parsed from build output when the format is recognised.
    *
    * Returns None rather than a guess. The schema does not require these fields precisely so
    * that an unparseable format costs a bonus, not a fabricated number.
    */
  private[app] def parseTestCounts(output: String): Option[(Int, Int, Int)] =
    val surefire = """Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""".r
    val gradle   = """(\d+) tests completed(?:, (\d+) failed)?(?:, (\d+) skipped)?""".r

    // Maven prints a per-module line and then a total; the LAST is the one that counts.
    val sf = surefire.findAllMatchIn(output).toVector.lastOption.map { m =>
      (m.group(1).toInt, m.group(2).toInt + m.group(3).toInt, m.group(4).toInt)
    }
    sf.orElse(gradle.findFirstMatchIn(output).map { m =>
      def g(i: Int) = Option(m.group(i)).map(_.toInt).getOrElse(0)
      (g(1), g(2), g(3))
    })

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val x = Json.mapper.createArrayNode()
    node.set(field, x)
    x

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(detail: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, detail)

  private def statusName(s: CoverageStatus): String = s match
    case CoverageStatus.Covered       => "COVERED"
    case CoverageStatus.Uncovered     => "UNCOVERED"
    case CoverageStatus.NotExecutable => "NOT_EXECUTABLE"

  /** Blame-based fault-introducing commit identification.
    *
    * Only B-SZZ and R-SZZ are implemented (see `causeway.vcs.Szz` for why PySZZ is not wrapped).
    * Asking for a variant we do not implement returns Unknown rather than quietly substituting
    * a different one — a result labelled `RA-SZZ` that was actually computed by R-SZZ would be
    * worse than no result.
    */
  private def szzBaseline(handles: Handles): ToolHandler = handler("szz_baseline") { a =>
    // Variant support is checked FIRST: it does not depend on the repository, and reporting
    // "no such clone" for a request we could never have served either way would send the caller
    // off fixing the wrong thing.
    val requested = str(a, "variant").getOrElse("R-SZZ")
    val variant = requested.toUpperCase match
      case "R-SZZ" => Some(SzzVariant.RSzz)
      case "B-SZZ" => Some(SzzVariant.BSzz)
      case _       => None

    if variant.isEmpty then
      na(s"variant '$requested' is not implemented here; only B-SZZ and R-SZZ are")
    else
      (str(a, "repoHandle").flatMap(handles.git), str(a, "fixSha")) match
        case (None, _) => na("no clone registered for that repoHandle")
        case (_, None) => na("fixSha is required")
        case (Some(git), Some(fixSha)) =>
          val v = variant.get
          val issueDate = str(a, "issueDate").flatMap(s => Try(Instant.parse(s)).toOption)
          Szz.inducingCommits(git, fixSha, v, issueDate) match
            case Left(err) => na(err)
            case Right(res) =>
              val n = Json.obj()
              n.put("variant", if v == SzzVariant.RSzz then "R-SZZ" else "B-SZZ")
              val inducing = arr(n, "inducing")
              res.candidates.foreach { c =>
                val o = Json.obj()
                o.put("sha", c.sha)
                o.put("confidence", c.confidence)
                val via = arr(o, "viaLines")
                c.viaLines.foreach(via.add)
                val filters = arr(o, "filtersApplied")
                res.filtersApplied.foreach(filters.add)
                inducing.add(o)
              }
              // An empty list here means blame could not see an origin — a guard-only fix
              // deletes nothing, and roughly one bug-fix in seven is that shape. It does NOT
              // mean the bug had no origin, and the empty `inducing` array plus the recorded
              // filters is what lets a consumer tell those apart.
              HandlerResult.Data(n)
  }

  /** Run the project's OWN test suite under the JaCoCo agent, in a container.
    *
    * Network is allowed: the project's build must resolve its dependencies to run its tests.
    * That is the opposite of the harness runner, which executes agent-authored code and is
    * denied the network entirely.
    *
    * Coverage always measures the repository's own suite. That is the point of D3 — the number
    * is meant to reflect the project's actual testing discipline, not a suite we generated to
    * flatter it.
    */
  private def coverageRun(handles: Handles): ToolHandler = handler("jvm_coverage_run") { a =>
    // The tests must run where the CLASSES came from. This used to resolve the shared clone
    // from repoHandle and run the suite there, while the classpath pointed at the build's own
    // tree — so the measured revision and the built revision were only the same by accident.
    // The build handle already names the right directory, so ask it.
    val dir = str(a, "buildHandle").flatMap(handles.buildDir)

    (dir, str(a, "buildHandle"), str(a, "engine")) match
      case (None, Some(_), _) =>
        na("that buildHandle has no build directory — run jvm_build_probe for this commit first")
      case (None, _, _) => na("buildHandle is required")
      case (_, None, _) => na("buildHandle is required")
      case (_, _, None) => na("engine is required (jacoco or scoverage)")
      case (Some(repoDir), Some(bh), Some(engine)) =>
        // repoHandle and commit are not needed to LOCATE anything any more — the build handle
        // does that — so they are used to CHECK. A caller that pairs one commit's build handle
        // with another commit's coordinates would otherwise get coverage for a revision it did
        // not ask about, and nothing in the response would say so.
        val expected = for
          h <- str(a, "repoHandle")
          c <- str(a, "commit")
        yield s"${h}_${c.take(12)}"

        val mismatch = expected.filter(_ != repoDir.getFileName.toString)

        val classDirs = handles.buildClasspath(bh).map(Path.of(_))

        if mismatch.isDefined then
          na(s"buildHandle '$bh' was built at ${repoDir.getFileName}, " +
             s"but this call names ${mismatch.get} — probe the commit you mean to measure")
        else if engine != "jacoco" then
          // scoverage instruments at COMPILE time via an sbt plugin, so it cannot be bolted
          // onto an already-built revision the way a Java agent can. Saying so is better than
          // running jacoco and labelling the result scoverage.
          HandlerResult.Undetermined(UnknownReason.NotApplicable,
            s"engine '$engine' is not implemented here; only jacoco, which attaches at run time")
        else if !Container.available() then
          HandlerResult.Undetermined(UnknownReason.ToolUnavailable, "Docker daemon not reachable")
        else if classDirs.isEmpty then
          HandlerResult.Undetermined(UnknownReason.BuildFailed,
            s"no compiled classes for '$bh' — coverage needs a successful build to measure against")
        else
          val image = handles.buildImage(bh).getOrElse("maven:3.9-eclipse-temurin-17")
          val cmd   = str(a, "testCommand").getOrElse("mvn -B test")

          // The build tool is read from the image the build actually used, so the cache is
          // pointed at the right place rather than assumed to be Maven's.
          val tool =
            if image.startsWith("gradle:") then JvmBuildTool.Gradle
            else if image.contains("sbt") then JvmBuildTool.Sbt
            else JvmBuildTool.Maven

          CoverageService.runInContainer(
            repoDir, cmd, image, classDirs,
            timeoutSec = int(a, "timeoutSec").getOrElse(1800),
            buildTool = tool,
            cacheRoot = Some(handles.cacheRoot)
          ) match
            case Left(err) =>
              // A suite that will not run is a recorded gap, not a crash: the bug keeps its
              // git-level data and coverage stays Unknown with a reason.
              HandlerResult.Undetermined(UnknownReason.TestsFailed, err.take(2000))
            case Right((report, result)) =>
              val counts = parseTestCounts(result.combinedTail(400))

              // A suite that ran ZERO tests did not measure anything. The exec file still parses
              // — the agent attached, Maven's own JVM touched a few classes — so the report looks
              // like a real result and says the project barely covers itself.
              //
              // Seen on jsoup: stale JDK 11 test classes in a JDK 8 container killed surefire
              // before the first test, the build reported success, and coverage came back near
              // zero. That is a broken RUN, not a poorly tested project, and the difference is
              // the whole reason coverage is a Signal rather than a number.
              if counts.exists((run, _, _) => run == 0) then
                HandlerResult.Undetermined(UnknownReason.TestsFailed,
                  s"""the suite executed 0 tests, so there is nothing to measure
                     |test run: exit=${result.exitCode} timedOut=${result.timedOut}
                     |${result.combinedTail(30)}""".stripMargin)
              else
                // Record the exec file too: a later process can then re-parse this report in
                // seconds instead of re-running a suite that takes minutes.
                handles.registerCoverage(report, repoDir.resolve(".causeway").resolve("jacoco.exec"),
                                         classDirs)
                val n = Json.obj()
                n.put("reportId", report.id)
                n.put("engine", "jacoco")
                n.put("durationSec", (result.durationMs / 1000).toInt)
                counts.foreach { (run, failed, skipped) =>
                  n.put("testsRun", run)
                  n.put("testsFailed", failed)
                  n.put("testsSkipped", skipped)
                }
                HandlerResult.Data(n)
  }

  /** Rung 1 of the reproducer ladder: run the project's OWN test at both revisions.
    *
    * Separate from `jvm_harness_run` because that tool writes one `Harness.java`, compiles it
    * with plain `javac`, and calls a generated `main`. A JUnit test needs a JUnit runtime and the
    * project's test classpath, so it cannot travel that path. Here the project's own build tool
    * runs its own test, which is what makes the reproducer NATIVE rather than reconstructed.
    */
  private def nativeTestRun(handles: Handles): ToolHandler = handler("jvm_native_test_run") { a =>
    (str(a, "repoHandle"), str(a, "fixSha"), str(a, "testSelector")) match
      case (None, _, _) => na("repoHandle is required")
      case (_, None, _) => na("fixSha is required")
      case (_, _, None) => na("testSelector is required")
      case (Some(repo), Some(fixSha), Some(selector)) =>
        if !Container.available() then
          HandlerResult.Undetermined(UnknownReason.ToolUnavailable, "Docker daemon not reachable")
        else
          // The PARENT is where the symptom occurs, so it has to be resolved rather than
          // supplied: a caller passing the wrong parent would produce a differential across the
          // wrong boundary and the result would look entirely normal.
          val parentSha = handles.git(repo).flatMap(_.commitMeta(fixSha).toOption)
            .flatMap(_.parents.headOption)

          parentSha match
            case None => na(s"cannot resolve the parent of '$fixSha'")
            case Some(parent) =>
              (handles.worktreeAt(repo, parent), handles.worktreeAt(repo, fixSha)) match
                case (Left(err), _) => na(s"parent worktree: $err")
                case (_, Left(err)) => na(s"fix worktree: $err")
                case (Right(parentTree), Right(fixTree)) =>
                  // The fix's own test sources. A fix that ADDS its regression test leaves the
                  // parent with no such test at all, so without this the parent side runs
                  // nothing and the bug looks unreproducible by the very test written for it.
                  val changedTests = handles.git(repo)
                    .flatMap(_.diff(fixSha).toOption)
                    .map(_.files.map(_.file).filter(Candidates.isTestFile))
                    .getOrElse(Vector.empty)

                  // Scope to the selector's classes. A sibling test file the fix also changed is
                  // left at the parent's own version: if it calls an API the fix introduced it
                  // cannot compile there, and one uncompilable file fails the WHOLE module's test
                  // compile, blocking selectors that never touched it.
                  val (portFiles, portSkipped) =
                    NativeTestRunner.scopePortFiles(changedTests, selector)

                  val tool = BuildProbe.detect(parentTree).map(_.buildTool)
                    .getOrElse(JvmBuildTool.Maven)
                  val image = str(a, "baseImage")
                    .getOrElse(BuildProbe.imageFor(
                      BuildProbe.detect(parentTree).flatMap(_.declaredSourceLevel), tool))

                  val classDirs = Vector(parentTree.resolve("target").resolve("classes"))
                    .filter(java.nio.file.Files.isDirectory(_))

                  val r = NativeTestRunner.differential(
                    parentTree, fixTree, tool, selector, image, classDirs,
                    timeoutSec = int(a, "timeoutSec").getOrElse(900),
                    cacheRoot = Some(handles.cacheRoot),
                    portTestFiles = portFiles)

                  if !r.parent.ran || !r.fix.ran then
                    // EITHER side failing to run a test is fatal to the differential, and the
                    // one-sided case is the dangerous one: parent silent, fix green, which
                    // computes failsAtParent=false and reports a bug as not captured by its own
                    // regression test. Naming the silent side matters too — a test that will not
                    // COMPILE against the parent's production code is a real answer about the
                    // fix, not a typo in the selector.
                    val where =
                      if !r.parent.ran && !r.fix.ran then "at either revision"
                      else if !r.parent.ran then "at the parent (ported test did not run there)"
                      else "at the fix"
                    HandlerResult.Undetermined(UnknownReason.TestsFailed,
                      s"selector '$selector' ran no test $where")
                  else
                    val n = Json.obj()
                    n.put("testSelector", selector)
                    n.put("failsAtParent", r.parent.ran && !r.parent.passed)
                    n.put("passesAtFix", r.fix.passed)
                    n.put("differs", r.differs)
                    val on = arr(n, "differsOn"); r.differsOn.foreach(on.add)
                    if r.parent.detail.nonEmpty then
                      n.put("resultAtParent", r.parent.detail.take(2000))
                    n.put("resultAtFix", if r.fix.passed then "PASS" else r.fix.detail.take(2000))
                    n.put("durationSec", (r.durationMs / 1000).toInt)
                    n.put("testsPorted", portFiles.nonEmpty)
                    val ported = arr(n, "portedTestFiles"); portFiles.foreach(ported.add)
                    val skipped = arr(n, "portSkippedFiles"); portSkipped.foreach(skipped.add)
                    n.put("frameMethodCount", r.parent.fromFramesOnly)

                    // The trace comes from the PARENT run, so the executed set is what ran while
                    // the symptom occurred. This is what lets S11 report OBSERVED.
                    if r.parent.executed.nonEmpty then
                      n.put("traceId", handles.registerTrace(r.parent.executed))
                      n.put("executedMethodCount", r.parent.executed.size)

                    HandlerResult.Data(n)
  }

  /** Materialise a revision on disk. */
  private def repoCheckout(handles: Handles): ToolHandler = handler("repo_checkout") { a =>
    (str(a, "repoHandle"), str(a, "commit")) match
      // Same routine `jvm_build_probe` uses, so a probe and an explicit checkout of the same
      // revision cannot end up looking at different trees.
      case (Some(h), Some(commit)) =>
        handles.worktreeAt(h, commit) match
          case Left(err) => na(err)
          case Right(target) =>
            val n = Json.obj()
            n.put("worktreePath", target.toAbsolutePath.toString)
            n.put("commit", commit)
            HandlerResult.Data(n)
      case _ => na("repoHandle and commit are required")
  }

  private def coverageLines(handles: Handles): ToolHandler = handler("jvm_coverage_lines") { a =>
    (str(a, "reportId").flatMap(handles.coverageReport), str(a, "file"), Option(a.get("range"))) match
      case (None, _, _) => na("no coverage report with that id — run coverage first")
      case (_, None, _) => na("file is required")
      case (_, _, None) => na("range is required")
      case (Some(report), Some(file), Some(rangeNode)) =>
        val range = OldLines(rangeNode.get("start").intValue(), rangeNode.get("end").intValue())
        val lines = report.linesFor(file, range)

        if lines.isEmpty then
          // The file is absent from the report. Distinct from "present but uncovered", and
          // reporting zeros here would turn an unmeasured file into an untested one.
          na(s"'$file' does not appear in coverage report ${report.id}")
        else
          val n = Json.obj()
          n.put("file", file)
          val out = arr(n, "lines")
          lines.foreach { l =>
            val o = Json.obj()
            o.put("line", l.line)
            o.put("status", statusName(l.status))
            o.put("hitCount", l.coveredInstructions)
            out.add(o)
          }
          HandlerResult.Data(n)
  }
