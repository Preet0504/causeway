package causeway.jvm

import causeway.core.{JvmBuildTool, MethodRef}

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.Try

/** One side of a native-test differential. */
final case class NativeTestSide(
    ran: Boolean,
    passed: Boolean,
    detail: String,
    executed: Vector[MethodRef],
    /** How many methods the stack frames contributed that coverage did NOT already have (D27). */
    fromFramesOnly: Int = 0
)

final case class NativeTestResult(
    parent: NativeTestSide,
    fix: NativeTestSide,
    durationMs: Long
):
  /** The differential oracle for rung 1.
    *
    * Both halves are required. A test that fails on both sides is broken rather than
    * discriminating, and one that passes on both sides does not exercise the defect.
    */
  def differs: Boolean = parent.ran && fix.ran && !parent.passed && fix.passed

  /** How the two sides differed, for the same reason `jvm_harness_run` reports it: an assertion
    * failure and a thrown exception are different symptoms, and the path tracer treats them
    * differently when deciding the propagation channel.
    */
  def differsOn: Vector[String] =
    if !differs then Vector.empty
    else if NativeTestRunner.threwException(parent.detail) then Vector("EXCEPTION")
    else Vector("ASSERTION")

/** Runs the PROJECT'S OWN test at two revisions and compares.
  *
  * This is rung 1 of the reproducer ladder, and it is deliberately not built on
  * [[HarnessRunner]]. That runner writes a single `Harness.java`, compiles it with plain `javac`,
  * and invokes a generated `main`. A JUnit test is a method on a test class that needs a JUnit
  * runtime and the project's full test classpath, so it cannot be handed to that path at all.
  *
  * Here the project's own build tool runs its own test, which is what makes the result NATIVE:
  * the reproducer is the one the person who fixed the bug wrote, not a reconstruction of it.
  */
object NativeTestRunner:

  /** Build the selector flag for a build tool.
    *
    * Maven and Gradle disagree on both the flag and the separator, and passing the wrong one
    * silently runs the WHOLE suite, which would then "pass" at the parent and report that the
    * regression test does not capture the bug.
    */
  def selectorFlag(tool: JvmBuildTool, selector: String): String = tool match
    case JvmBuildTool.Maven  => s"""-Dtest="$selector" -DfailIfNoSpecifiedTests=false"""
    case JvmBuildTool.Gradle => s"""--tests "${selector.replace('#', '.')}""""
    case JvmBuildTool.Sbt    => s"""testOnly ${selector.replace("#", " -- -z ")}"""

  def testCommandFor(tool: JvmBuildTool, selector: String): String = tool match
    case JvmBuildTool.Maven  => s"mvn -B test ${selectorFlag(tool, selector)}"
    case JvmBuildTool.Gradle => s"./gradlew --no-daemon test ${selectorFlag(tool, selector)}"
    case JvmBuildTool.Sbt    => s"""sbt -batch "${selectorFlag(tool, selector)}""""

  /** Did the run produce a test result at all?
    *
    * A selector matching nothing is the dangerous case: the build succeeds, no test runs, and the
    * exit code says everything is fine. Treating that as "passed" would report that a regression
    * test does not reproduce its own bug.
    */
  def ranAnyTest(output: String): Boolean =
    val surefire = """Tests run: (\d+)""".r
    val gradle   = """(\d+) tests? completed""".r
    surefire.findAllMatchIn(output).exists(_.group(1).toInt > 0) ||
      gradle.findAllMatchIn(output).exists(_.group(1).toInt > 0) ||
      output.contains("Total number of tests run:")

  /** The simple class names a build-tool selector names.
    *
    * Used to SCOPE test porting. Porting every test file a fix touched means one uncompilable
    * sibling — typically a test calling an API the fix introduced — fails the whole module's test
    * compile, and then no selector in any file can run, including selectors with nothing to do
    * with that sibling. Observed on two bugs in run_f7997c1a240cc238.
    *
    * Handles the Maven form `Class#a+b` and the Gradle form `pkg.Class.method`, singly or
    * comma-separated.
    */
  private[jvm] def selectorClasses(selector: String): Vector[String] =
    selector.split(",").toVector.map(_.trim).filter(_.nonEmpty).flatMap { part =>
      val cls =
        if part.contains("#") then part.substring(0, part.indexOf('#'))
        else
          // Gradle names the method with a dot, so the last segment is the method, not the class.
          val i = part.lastIndexOf('.')
          if i > 0 then part.substring(0, i) else part
      val simple = cls.substring(cls.lastIndexOf('.') + 1).trim
      if simple.isEmpty then None else Some(simple)
    }.distinct

  /** Split the fix's changed test files into (port, skip) for this selector. */
  private[causeway] def scopePortFiles(
      files: Vector[String], selector: String
  ): (Vector[String], Vector[String]) =
    val wanted = selectorClasses(selector).toSet
    files.partition { f =>
      val base = f.substring(f.lastIndexOf('/') + 1)
      val stem = if base.contains(".") then base.substring(0, base.lastIndexOf('.')) else base
      wanted.contains(stem)
    }

  /** Stack-trace frames from the build tool's own output.
    *
    * D27: the executed set is coverage UNION the throw's frames. A method whose call THROWS
    * records no JaCoCo probe — probes sit at branch targets and method exits, and an abnormal
    * exit reaches neither — so coverage alone omits exactly the chain leading to the fault on an
    * exception-manifesting bug. The frames name that chain precisely.
    *
    * Unlike [[HarnessRunner]] there is no generated Runner emitting a marker line here: surefire
    * and Gradle print ordinary Java stack traces, so they are parsed as such. The descriptor is
    * left EMPTY because a stack trace carries none; per D27 methods are matched across producers
    * by class and name only, which widens the set slightly where overloads collide. Demanding
    * descriptors instead would empty the intersection and silently demote every observed path.
    *
    * The optional `module/` prefix that JDK 9+ frames carry (`java.base/java.io.Foo.bar`) is
    * stripped, so JDK frames land under their plain class name.
    */
  private[jvm] def stackFramesOf(output: String): Vector[MethodRef] =
    val Frame = """^\s*at\s+(?:[\w.]+/)?([\w$.]+)\.([\w$<>]+)\(.*""".r
    output.linesIterator.collect { case Frame(cls, name) => MethodRef(cls, name, "") }
      .toVector
      .distinct

  def threwException(detail: String): Boolean =
    val d = detail.toLowerCase
    (d.contains("exception") || d.contains("error:")) &&
      !d.contains("assertionfailederror") && !d.contains("assertionerror")

  /** The failure text, or an empty string when the side passed. */
  private def failureDetail(result: ContainerResult): String =
    val lines = (result.stdout.linesIterator.toVector ++ result.stderr.linesIterator.toVector)
    val interesting = lines.filter { l =>
      l.contains("FAILURE") || l.contains("FAILED") || l.contains("Exception") ||
        l.contains("AssertionFailedError") || l.contains("expected:") || l.contains("<<<")
    }
    (if interesting.nonEmpty then interesting else lines.takeRight(20)).take(20).mkString("\n")

  /** One file displaced from the parent tree so the fix's version can stand in its place. */
  private[jvm] final case class Displaced(target: Path, backup: Option[Path])

  /** Overlay the fix's test sources onto the parent worktree.
    *
    * Rung 1 assumes the regression test exists at the parent. For a fix that ADDS its test — the
    * common shape, and the one the ladder is written around — it does not, so running each
    * revision's own tree executes nothing at the parent and reports that the bug is not captured
    * by its own regression test. Only TEST sources move. The parent's production code is the
    * thing under test and is never touched, which is what keeps the differential honest.
    */
  private[jvm] def portTests(parentTree: Path, fixTree: Path, files: Vector[String]): Vector[Displaced] =
    files.flatMap { rel =>
      val from = fixTree.resolve(rel)
      // A file the fix DELETED has nothing to port; skip rather than failing the whole run.
      if !Files.isRegularFile(from) then None
      else
        val to = parentTree.resolve(rel)
        val backup =
          if Files.isRegularFile(to) then
            val b = to.resolveSibling(to.getFileName.toString + ".causeway-orig")
            Files.move(to, b, StandardCopyOption.REPLACE_EXISTING)
            Some(b)
          else
            Option(to.getParent).foreach(Files.createDirectories(_))
            None
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        Some(Displaced(to, backup))
    }

  /** Put the parent worktree back exactly as it was.
    *
    * Worktrees are cached under workspace/checkouts and reused by every later build probe,
    * coverage run and call graph at that revision. A ported test left behind would contaminate
    * all of them silently, so this runs in a `finally` and never on the happy path alone.
    */
  private[jvm] def restore(displaced: Vector[Displaced]): Unit =
    displaced.foreach { d =>
      Try(Files.deleteIfExists(d.target))
      d.backup.foreach(b => Try(Files.move(b, d.target, StandardCopyOption.REPLACE_EXISTING)))
    }

  /** Run one side, instrumented, and report what happened.
    *
    * `classDirs` is used only to resolve the executed set. When it is empty the run still yields
    * a pass or fail verdict, and the executed set is simply absent rather than wrong.
    */
  private def runSide(
      worktree: Path,
      tool: JvmBuildTool,
      selector: String,
      image: String,
      classDirs: Vector[Path],
      timeoutSec: Int,
      cacheRoot: Option[Path],
      instrument: Boolean
  ): NativeTestSide =
    val agentDir = worktree.resolve(".causeway")
    val execFile = agentDir.resolve("jacoco.exec")

    // Delete first. A stale exec from an earlier attempt would otherwise be parsed as this run's
    // executed set, and an OBSERVED path would be built from methods this test never ran.
    Try(Files.deleteIfExists(execFile))

    val plain = testCommandFor(tool, selector)
    val cmd =
      if instrument then
        CoverageService.extractAgent(agentDir)
        CoverageService.instrumentedCommand(plain)
      else plain

    val (mounts, env) = cacheRoot
      .map(DependencyCache.forTool(tool, _))
      .getOrElse((Vector.empty, Map.empty))

    val result = Container.exec(
      ContainerPolicy(image, network = true, timeoutSec = timeoutSec, mounts = mounts, env = env),
      worktree, cmd)

    val output = result.stdout + "\n" + result.stderr
    val ran    = ranAnyTest(output)

    val fromCoverage =
      if !instrument || classDirs.isEmpty then Vector.empty
      else CoverageService.executedMethods(execFile, classDirs).getOrElse(Vector.empty)

    // D27. Gated on `instrument` for the same reason HarnessRunner gates it: a caller that asked
    // for no tracing must get none, even though frames are free.
    val fromFrames = if instrument then stackFramesOf(output) else Vector.empty

    // D27's class-and-name rule governs MATCHING ACROSS PRODUCERS, not the shape of the result.
    // Coverage entries carry real descriptors and are kept verbatim, including distinct overloads;
    // only frames whose (class, name) coverage did not already report are appended, and those
    // carry an empty descriptor because a stack trace has none.
    //
    // Collapsing the whole set by (class, name) instead — as an earlier version of this did —
    // silently destroys overload precision that coverage HAD. It made StringUtil#resolve(URL,
    // String) unrepresentable next to resolve(String,String), and a path tracer duly scored the
    // hop between them UNOBSERVED when the truth was that the trace could no longer tell.
    val covered   = fromCoverage.map(m => (m.fqcn, m.name)).toSet
    val frameOnly = fromFrames.filterNot(m => covered.contains((m.fqcn, m.name))).distinct

    val executed = (fromCoverage ++ frameOnly).sortBy(_.signature)
    val added    = frameOnly.size

    NativeTestSide(
      ran = ran,
      // A side "passed" only if a test actually ran AND the command succeeded. Both halves
      // matter: an exit code of zero with no tests executed is not a pass.
      passed = ran && result.succeeded,
      detail = if ran && result.succeeded then "" else failureDetail(result),
      executed = executed,
      fromFramesOnly = added
    )

  /** Run the named test at the parent and at the fix, and compare.
    *
    * Only the PARENT side is instrumented. The executed set is meant to answer "what ran while
    * the symptom occurred", and the symptom occurs at the parent. Instrumenting the fix as well
    * would double the cost to record a trace of the repaired behaviour, which no stage consumes.
    */
  def differential(
      parentTree: Path,
      fixTree: Path,
      tool: JvmBuildTool,
      selector: String,
      image: String,
      parentClassDirs: Vector[Path],
      timeoutSec: Int = 900,
      cacheRoot: Option[Path] = None,
      portTestFiles: Vector[String] = Vector.empty
  ): NativeTestResult =
    val started = System.currentTimeMillis()

    // The fix's tests stand in at the parent only for the duration of the parent run.
    val displaced = portTests(parentTree, fixTree, portTestFiles)
    val parent =
      try
        runSide(parentTree, tool, selector, image, parentClassDirs,
                timeoutSec, cacheRoot, instrument = true)
      finally restore(displaced)
    val fix    = runSide(fixTree, tool, selector, image, Vector.empty,
                         timeoutSec, cacheRoot, instrument = false)

    NativeTestResult(parent, fix, System.currentTimeMillis() - started)
