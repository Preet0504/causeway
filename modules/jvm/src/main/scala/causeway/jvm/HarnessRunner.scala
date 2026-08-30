package causeway.jvm

import causeway.core.MethodRef

import java.nio.file.{Files, Path}

/** What one side of a differential run produced. */
final case class RunOutcome(
    exceptionType: Option[String],
    exceptionSite: Option[String],
    returnValue: Option[String],
    timedOut: Boolean,
    exitCode: Int
):
  def observable: (Option[String], Option[String], Option[String]) =
    (exceptionType, exceptionSite, returnValue)

enum DiffChannel:
  case Exception, Return

final case class DifferentialResult(
    compiled: Boolean,
    compileError: Option[String],
    atParent: Option[RunOutcome],
    atFix: Option[RunOutcome],
    differs: Boolean,
    differsOn: Vector[DiffChannel],
    durationMs: Long,
    /** Methods that ACTUALLY EXECUTED on the buggy revision during this run.
      *
      * The parent side, not the fix: the fault lived there, and this is the execution that
      * manifested it. Intersecting this with the static call graph is what separates an
      * OBSERVED path from a merely HYPOTHESIZED one.
      *
      * Empty when instrumentation could not be staged — a differential answer without a trace
      * is still a valid reproducer, it just cannot support an observed path.
      */
    executedAtParent: Vector[MethodRef] = Vector.empty
):
  /** True only when the input demonstrably triggers the bug. */
  def triggersBug: Boolean = compiled && differs

/** Runs an agent-authored harness against BOTH revisions and compares.
  *
  * This is D14. "Did this input trigger the bug?" normally needs a human to say what correct
  * behaviour was; here it does not, because both revisions exist. If the same input produces
  * different observable behaviour before and after the fix, the fix changed what it does, and
  * the input therefore exercises the bug. No oracle, no human, no guess.
  *
  * Observable difference is deliberately narrow (D15): **thrown exception type and site**, and
  * **returned value**. File writes, log output and timing are excluded because they are too
  * noisy to serve as an oracle — timestamps, ordering and paths differ between runs of identical
  * code. The cost is that a bug manifesting ONLY through those channels can never show
  * `differs = true` here; it lands in tier T3/T4 regardless of how reproducible it really is.
  * That is a limitation of the instrument, not a property of the bug (assumption A17).
  *
  * The harness is agent-authored code, so unlike the build it runs with **no network**.
  */
object HarnessRunner:

  /** The contract the agent writes against. */
  val HarnessTemplate: String =
    """public class Harness {
      |    public static Object run() throws Throwable {
      |        // construct input, call the entry point, return whatever it produces
      |    }
      |}""".stripMargin

  private val Marker = "CAUSEWAY_RESULT:"

  /** Frames of the throw, when the run ended in an exception.
    *
    * JaCoCo places its probes at branch points and method exits, so a method whose call THROWS
    * may record zero covered instructions even though it certainly executed. For a bug that
    * manifests as an exception — the commonest kind here — that undercounts precisely the
    * methods on the path to the throw.
    *
    * The stack trace names those frames exactly. It is the natural complement to coverage:
    * coverage sees everything that completed, the trace sees everything that was still on the
    * stack when things went wrong.
    */
  private val FrameMarker = "CAUSEWAY_FRAMES:"

  /** Field separator in the result line.
    *
    * Built from a char code rather than written as a unicode escape: such an escape is processed
    * by the Scala lexer AND again by javac inside the generated source, which is an easy way to
    * split on the wrong thing and misread every run as identical.
    */
  private val Sep: String = 1.toChar.toString

  /** Wrapper that turns a harness invocation into one parseable line.
    *
    * Generated rather than asked for, so the agent cannot get the reporting protocol wrong and
    * have that misread as the bug not reproducing.
    */
  private val runnerSource: String =
    s"""public class Runner {
       |    public static void main(String[] args) {
       |        String type = "", site = "", ret = "", frames = "";
       |        try {
       |            Object r = Harness.run();
       |            ret = (r == null) ? "null" : r.toString();
       |        } catch (Throwable t) {
       |            type = t.getClass().getName();
       |            StackTraceElement[] st = t.getStackTrace();
       |            site = (st.length > 0) ? (st[0].getClassName() + "." + st[0].getMethodName()
       |                   + ":" + st[0].getLineNumber()) : "";
       |            StringBuilder fb = new StringBuilder();
       |            for (StackTraceElement e : st) {
       |                if (fb.length() > 0) fb.append(",");
       |                fb.append(e.getClassName()).append(".").append(e.getMethodName());
       |            }
       |            frames = fb.toString();
       |        }
       |        System.out.println("$Marker" + esc(type) + "\\u0001" + esc(site) + "\\u0001" + esc(ret));
       |        System.out.println("$FrameMarker" + esc(frames));
       |    }
       |    private static String esc(String s) {
       |        return s == null ? "" : s.replace("\\u0001", " ").replace("\\n", " ").replace("\\r", " ");
       |    }
       |}""".stripMargin

  /** One revision's source tree plus the classpath its compiled dependencies live on. */
  final case class Side(sources: Path, classpath: Vector[String] = Vector.empty)

  /** Compile and run the harness against both revisions and compare the outcomes.
    *
    * Both sides are staged into a single container run so the two executions share an
    * environment: differences must come from the code under test, not from a JVM that started
    * with different flags or a filesystem in a different state.
    *
    * With `trace` on the run is instrumented, and the parent side's executed methods are
    * recovered afterwards on the host.
    */
  def differential(
      workdir: Path,
      parent: Side,
      fix: Side,
      harnessSource: String,
      image: String = "eclipse-temurin:17-jdk-alpine",
      timeoutSec: Int = 120,
      memoryMb: Int = 2048,
      trace: Boolean = true
  ): DifferentialResult =
    val started = System.currentTimeMillis()

    stage(workdir.resolve("parent"), parent, harnessSource)
    stage(workdir.resolve("fix"), fix, harnessSource)

    // The agent goes in the shared workdir so both sides load it from one place. Failing to
    // stage it is not fatal: the differential answer stands, only the trace is lost.
    val agent = if trace then CoverageService.extractAgent(workdir).toOption else None

    // The destination is RELATIVE: compile_and_run has already cd'd into /work/parent or
    // /work/fix, so each side writes its own exec beside its own classes. Referring to the
    // shell function argument here instead would need a `$1` that survives Scala interpolation
    // — and `$$1` in a non-interpolated string stays literally `$$1`, which the shell reads as
    // the PID followed by "1". The relative path sidesteps that entirely.
    //
    // append=false so a stale exec from an earlier attempt cannot merge in and attribute
    // methods to a run that never executed them.
    val agentArg =
      if agent.isDefined then
        "-javaagent:/work/jacocoagent.jar=destfile=jacoco.exec,append=false,dumponexit=true"
      else ""

    val script =
      s"""set -e
         |compile_and_run() {
         |  cd /work/$$1
         |  if ! javac -g $$(find . -name '*.java') > compile.log 2>&1; then
         |    echo "COMPILE_FAILED:$$1"
         |    cat compile.log
         |    return 0
         |  fi
         |  java $agentArg -cp . Runner 2>&1 || true
         |}
         |echo "===PARENT==="
         |compile_and_run parent
         |echo "===FIX==="
         |compile_and_run fix
         |""".stripMargin

    val result = Container.exec(
      // No network: this is agent-authored code, and nothing it legitimately needs is outside
      // the workspace.
      ContainerPolicy(image, network = false, memoryMb = memoryMb, timeoutSec = timeoutSec),
      workdir,
      script
    )

    val combined      = result.stdout + "\n" + result.stderr
    val compileFailed = combined.contains("COMPILE_FAILED:")

    if compileFailed then
      DifferentialResult(
        compiled = false,
        compileError = Some(compileErrorFrom(combined)),
        atParent = None, atFix = None,
        differs = false, differsOn = Vector.empty,
        durationMs = System.currentTimeMillis() - started
      )
    else
      val parentOut = parseSection(combined, "===PARENT===", "===FIX===", result)
      val fixOut    = parseSection(combined, "===FIX===", null, result)

      val channels = (parentOut, fixOut) match
        case (Some(p), Some(f)) =>
          Vector(
            Option.when(p.exceptionType != f.exceptionType || p.exceptionSite != f.exceptionSite)(
              DiffChannel.Exception
            ),
            Option.when(p.returnValue != f.returnValue)(DiffChannel.Return)
          ).flatten
        case _ => Vector.empty

      // javac compiles in place here, so the class files sit alongside the sources.
      val fromCoverage =
        if agent.isEmpty then Vector.empty
        else
          CoverageService
            .executedMethods(
              workdir.resolve("parent").resolve("jacoco.exec"),
              Vector(workdir.resolve("parent"))
            )
            .getOrElse(Vector.empty)

      // Frames from the throw on the PARENT side. These fill in exactly what coverage misses:
      // a method that threw rather than returned never reaches its exit probe, so the whole
      // chain leading to the fault would otherwise be invisible.
      //
      // Gated on `trace` even though frames cost nothing and need no agent: a flag that says
      // "no tracing" must mean it, or a caller cannot reason about what it is getting.
      val fromFrames =
        if trace then framesOf(combined, "===PARENT===", "===FIX===") else Vector.empty

      val executed = (fromCoverage ++ fromFrames)
        .groupBy(m => (m.fqcn, m.name))
        .values.map(_.head).toVector
        .sortBy(_.signature)

      DifferentialResult(
        compiled = true,
        compileError = None,
        atParent = parentOut,
        atFix = fixOut,
        differs = channels.nonEmpty,
        differsOn = channels,
        durationMs = System.currentTimeMillis() - started,
        executedAtParent = executed
      )

  private def stage(dir: Path, side: Side, harnessSource: String): Unit =
    Files.createDirectories(dir)
    copyTree(side.sources, dir)
    Files.writeString(dir.resolve("Harness.java"), harnessSource)
    Files.writeString(dir.resolve("Runner.java"), runnerSource)

  private def copyTree(from: Path, to: Path): Unit =
    if Files.isDirectory(from) then
      Files.walk(from).forEach { p =>
        val rel    = from.relativize(p)
        val target = to.resolve(rel.toString)
        if Files.isDirectory(p) then Files.createDirectories(target)
        else
          Files.createDirectories(target.getParent)
          Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }

  /** Stack-trace frames from one section, as methods.
    *
    * A stack trace carries no descriptor, so these MethodRefs have an empty one and are matched
    * by class and name — see `CallGraphService.matchKey`.
    */
  private def framesOf(output: String, from: String, to: String): Vector[MethodRef] =
    val lines = output.linesIterator.toVector
    val start = lines.indexWhere(_.trim == from)
    if start < 0 then Vector.empty
    else
      val end     = if to == null then lines.length else lines.indexWhere(_.trim == to, start + 1)
      val section = lines.slice(start + 1, if end < 0 then lines.length else end)
      section
        .find(_.contains(FrameMarker))
        .map(line => line.substring(line.indexOf(FrameMarker) + FrameMarker.length).trim)
        .filter(_.nonEmpty)
        .toVector
        .flatMap(_.split(",").toVector)
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap { frame =>
          val i = frame.lastIndexOf('.')
          if i <= 0 then None else Some(MethodRef(frame.substring(0, i), frame.substring(i + 1), ""))
        }
        .distinct

  private def compileErrorFrom(output: String): String =
    output.linesIterator
      .dropWhile(!_.startsWith("COMPILE_FAILED:"))
      .take(20)
      .mkString("\n")

  private def parseSection(
      output: String,
      from: String,
      to: String,
      result: ContainerResult
  ): Option[RunOutcome] =
    val lines = output.linesIterator.toVector
    val start = lines.indexWhere(_.trim == from)
    if start < 0 then None
    else
      val end     = if to == null then lines.length else lines.indexWhere(_.trim == to, start + 1)
      val section = lines.slice(start + 1, if end < 0 then lines.length else end)

      section.find(_.contains(Marker)).map { line =>
        val payload = line.substring(line.indexOf(Marker) + Marker.length)
        val parts   = payload.split(java.util.regex.Pattern.quote(Sep), -1)
        def at(i: Int) = parts.lift(i).map(_.trim).filter(_.nonEmpty)

        RunOutcome(
          exceptionType = at(0),
          exceptionSite = at(1),
          returnValue = at(2),
          timedOut = result.timedOut,
          exitCode = result.exitCode
        )
      }
