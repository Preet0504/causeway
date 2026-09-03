package causeway.jvm

import causeway.core.JvmBuildTool

import causeway.core.{CoverageStatus, LineRange, MethodRef}

import org.jacoco.core.analysis.{Analyzer, CoverageBuilder, ICounter}
import org.jacoco.core.tools.ExecFileLoader

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class LineCoverage(line: Int, status: CoverageStatus, coveredInstructions: Int)

final case class FileCoverage(
    packageName: String,
    sourceFile: String,
    lines: Map[Int, LineCoverage]
):
  /** Repo-relative path suffix this file corresponds to, e.g. `cfg/ConfigLoader.java`. */
  def pathSuffix: String =
    if packageName.isEmpty then sourceFile else s"$packageName/$sourceFile"

final case class CoverageReport(
    id: String,
    files: Vector[FileCoverage],
    classesAnalyzed: Int
):
  /** Coverage of a line range in a file.
    *
    * The path is matched by suffix, because JaCoCo knows a class's package and source file name
    * but not the repository layout it came from — `src/main/java/cfg/ConfigLoader.java` and
    * `cfg/ConfigLoader.java` are the same file seen from two places.
    */
  def linesFor(repoPath: String, range: LineRange): Vector[LineCoverage] =
    val normalised = repoPath.replace('\\', '/')
    files
      .find(f => normalised.endsWith(f.pathSuffix))
      .map(f => range.toSeq.toVector.map(l =>
        f.lines.getOrElse(l, LineCoverage(l, CoverageStatus.NotExecutable, 0))))
      .getOrElse(Vector.empty)

  /** Fraction of EXECUTABLE lines in a range that were covered.
    *
    * Non-executable lines — blank lines, comments, a closing brace — are excluded from both
    * sides rather than counted as uncovered, which would make a well-tested method with a long
    * comment look worse than one without.
    */
  def coveredFraction(repoPath: String, range: LineRange): Option[Double] =
    val lines = linesFor(repoPath, range).filter(_.status != CoverageStatus.NotExecutable)
    if lines.isEmpty then None
    else Some(lines.count(_.status == CoverageStatus.Covered).toDouble / lines.size)

  def fileSummary(repoPath: String): Option[(Int, Int)] =
    val normalised = repoPath.replace('\\', '/')
    files.find(f => normalised.endsWith(f.pathSuffix)).map { f =>
      val executable = f.lines.values.count(_.status != CoverageStatus.NotExecutable)
      val covered    = f.lines.values.count(_.status == CoverageStatus.Covered)
      (covered, executable)
    }

/** Coverage measurement.
  *
  * Two halves on purpose: the container runs the project's own test suite under the JaCoCo
  * agent and produces a `.exec` file; the host parses it against the class files. Parsing is
  * therefore pure and testable without Docker, and the container needs nothing but the agent.
  *
  * Coverage is always of the REPOSITORY'S OWN suite. That is the point — the number is meant to
  * reflect the project's actual testing discipline, not a suite we generated to flatter it.
  */
object CoverageService:

  /** Write the JaCoCo agent jar somewhere the container can mount it. */
  def extractAgent(into: Path): Either[String, Path] =
    Try {
      Files.createDirectories(into)
      val target = into.resolve("jacocoagent.jar")
      org.jacoco.agent.AgentJar.extractTo(target.toFile)
      target
    }.toEither.left.map(t => s"cannot extract JaCoCo agent: ${t.getMessage}")

  /** The `-javaagent` argument for a given exec destination.
    *
    * `append=false` matters: a stale exec file from a previous revision would silently merge
    * with this one, and coverage attributed to the parent commit would leak into the fix.
    *
    * `excludes`, colon-separated JaCoCo patterns, is empty by default: `runLocally` attaches this
    * straight to a generated harness's own `java` invocation, where nothing but the harness's and
    * the repo's classes ever load, so there is nothing to exclude. `instrumentedCommand` below
    * passes a real value, because it is the one caller where the agent ends up on a JVM that is
    * NOT the test JVM.
    */
  def agentArg(agentJar: String, execFile: String, append: Boolean = false, excludes: String = ""): String =
    val ex = if excludes.isEmpty then "" else s",excludes=$excludes"
    s"-javaagent:$agentJar=destfile=$execFile,append=$append,dumponexit=true$ex"

  /** Wrap a project's test command so the forked test JVM runs under the agent.
    *
    * `JAVA_TOOL_OPTIONS`, not `-DargLine`. Surefire's `argLine` PROPERTY is overridden by a
    * POM-level `<argLine>`, and hard-coding one is common — jsoup pins `-Xss640k` that way — so
    * the property attaches nothing, the suite runs clean, and the only symptom is a missing exec
    * file. The JVM honours `JAVA_TOOL_OPTIONS` unconditionally and ADDITIVELY, so the project
    * keeps its own flags.
    *
    * The cost is that every JVM in the build inherits it, Maven's own included, which is why the
    * caller runs with `append = true`: with `append = false` the last JVM to exit truncates the
    * file and throws away the test JVM's data.
    *
    * Maven's own JVM does not just sit idle carrying the agent, either: for a project whose
    * compiler plugin runs `javac` IN-PROCESS (the default — no `fork`), that JVM *is* the one
    * that compiles the module, so the agent ends up instrumenting javac's own classes too. javac's
    * attribution pass is deeply recursive (`Attr.attribTree` calling back into `visitApply` /
    * `visitSelect` on every nested call expression), and JaCoCo's probe insertion adds a stack
    * frame's worth of overhead to each of those methods — enough, on real source, to blow the
    * default thread stack and abort the compile with `StackOverflowError`, reported by Maven only
    * as "An unknown compilation problem occurred". Found live: `jvm_native_test_run` ported a
    * fix's regression test onto the parent worktree and every attempt came back "ran no test at
    * the parent", for every selector tried, on every bug in the run — traced to exactly this by
    * reproducing the container command by hand and diffing instrumented against uninstrumented.
    * `excludes` here keeps the agent off the compiler's and the build tool's own classes, which
    * were never the thing being measured anyway — only the repository's tests and the production
    * code they exercise are.
    *
    * No `|| <command>` fallback. Re-running the suite without the agent cannot produce an exec
    * file — it doubles the wall clock and still yields nothing.
    */
  val defaultAgentExcludes = "com.sun.tools.*:org.apache.maven.*:org.codehaus.*:jdk.*"

  def instrumentedCommand(
      testCommand: String,
      agentJar: String = "/work/.causeway/jacocoagent.jar",
      execFile: String = "/work/.causeway/jacoco.exec",
      excludes: String = defaultAgentExcludes
  ): String =
    val arg = agentArg(agentJar, execFile, append = true, excludes = excludes)
    s"""export JAVA_TOOL_OPTIONS="$arg"; $testCommand"""

  /** Parse a `.exec` against the class files that produced it.
    *
    * Both are required. The exec file records probe hits by class id; without the classes there
    * is nothing to map them onto, and JaCoCo reports nothing at all rather than failing.
    */
  def parse(execFile: Path, classDirs: Vector[Path]): Either[String, CoverageReport] =
    if !Files.isRegularFile(execFile) then Left(s"no exec file at $execFile")
    else if classDirs.isEmpty then Left("no class directories to analyse against")
    else
      Try {
        val loader = ExecFileLoader()
        loader.load(execFile.toFile)

        val builder  = CoverageBuilder()
        val analyzer = Analyzer(loader.getExecutionDataStore, builder)
        classDirs.filter(Files.exists(_)).foreach(d => analyzer.analyzeAll(d.toFile: File))

        val files = builder.getClasses.asScala.toVector
          .groupBy(c => (Option(c.getPackageName).getOrElse(""), Option(c.getSourceFileName).getOrElse("")))
          .filter { case ((_, src), _) => src.nonEmpty }
          .map { case ((pkg, src), classes) =>
            val lines = classes.flatMap { c =>
              (c.getFirstLine to c.getLastLine).filter(_ > 0).map { l =>
                val counter = c.getLine(l).getInstructionCounter
                val status = counter.getStatus match
                  case ICounter.FULLY_COVERED  => CoverageStatus.Covered
                  case ICounter.PARTLY_COVERED => CoverageStatus.Covered
                  case ICounter.NOT_COVERED    => CoverageStatus.Uncovered
                  case _                       => CoverageStatus.NotExecutable
                l -> LineCoverage(l, status, counter.getCoveredCount)
              }
            }
            // Nested and anonymous classes share a source file; merging keeps the STRONGEST
            // status per line, since a line covered through any class IS covered.
            val merged = lines.groupBy(_._1).map { case (l, entries) =>
              l -> entries.map(_._2).maxBy(c => strength(c.status))
            }
            FileCoverage(pkg.replace('\\', '/'), src, merged)
          }
          .toVector
          .sortBy(_.pathSuffix)

        // Identified by CONTENT, not by where the file sat. The path is
        // `<clone>/.causeway/jacoco.exec` for every run of a repository, so a path-derived id
        // was the same for the parent revision and the fix — the two measurements this project
        // exists to compare — and the same again for a broken run and the good one that
        // replaced it. An agent citing a reportId would have been citing "some coverage of this
        // repo", which is not a fact about anything.
        CoverageReport(reportIdFor(execFile, classDirs), files, builder.getClasses.size)
      }.toEither.left.map(t => s"cannot parse coverage: ${Option(t.getMessage).getOrElse(t.toString)}")

  /** How much a status CLAIMS about a line, most to least.
    *
    * Explicit, not `ordinal`. Every JaCoCo class reports its whole `getFirstLine..getLastLine`
    * span, so in a file with nested or anonymous classes the siblings that do not own a line
    * still report on it — as `NotExecutable`, having no probe there. The merge has to let the
    * class that DOES own the line win, and that is a statement about the statuses' meaning, not
    * about the order someone happened to declare them in.
    *
    * The original wrote `maxBy(_.status.ordinal)` against `Covered, Uncovered, NotExecutable`,
    * which selected the least informative answer — the inverse of what its own comment claimed.
    * Whole multi-class files (parser state machines, connection helpers) came back almost
    * entirely NOT_EXECUTABLE, while single-class files looked perfect, so it survived every
    * spot check. Reordering the enum would silently flip `ordinal` back; it cannot flip this.
    */
  private[jvm] def strength(s: CoverageStatus): Int = s match
    case CoverageStatus.Covered       => 2  // a probe fired here
    case CoverageStatus.Uncovered     => 1  // a probe exists here and did not fire
    case CoverageStatus.NotExecutable => 0  // no probe — says nothing about the line

  /** A content hash of the measurement: the exec data plus the classes it was resolved against.
    *
    * Both halves matter. The same exec file analysed against different class directories is a
    * different report, because the mapping from probes to lines comes from the classes.
    */
  private[jvm] def reportIdFor(execFile: Path, classDirs: Vector[Path]): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(Files.readAllBytes(execFile))
    classDirs.map(_.toString).sorted.foreach(d => digest.update(d.getBytes("UTF-8")))
    f"cov_${digest.digest().take(8).map(b => f"$b%02x").mkString}"

  /** Methods that ACTUALLY EXECUTED during an instrumented run.
    *
    * This is what turns a statically possible path into an observed one. A static call graph
    * over-approximates — reflection, dependency injection, dead branches, virtual dispatch to
    * implementations that never run — so a path through it means propagation is POSSIBLE. The
    * intersection with this set means it HAPPENED.
    *
    * A method counts as executed when any of its instructions were covered. Partial coverage
    * still means the method ran, which is the question being asked here.
    */
  def executedMethods(execFile: Path, classDirs: Vector[Path]): Either[String, Vector[MethodRef]] =
    if !Files.isRegularFile(execFile) then Left(s"no exec file at $execFile")
    else
      Try {
        val loader = ExecFileLoader()
        loader.load(execFile.toFile)

        val builder  = CoverageBuilder()
        val analyzer = Analyzer(loader.getExecutionDataStore, builder)
        classDirs.filter(Files.exists(_)).foreach(d => analyzer.analyzeAll(d.toFile: File))

        builder.getClasses.asScala.toVector.flatMap { cls =>
          val fqcn = cls.getName.replace('/', '.')
          cls.getMethods.asScala.toVector
            .filter(_.getInstructionCounter.getCoveredCount > 0)
            .map(m => MethodRef(fqcn, m.getName, m.getDesc))
        }.distinct.sortBy(_.signature)
      }.toEither.left.map(t => s"cannot read executed methods: ${Option(t.getMessage).getOrElse(t.toString)}")

  /** Run a project's test suite under the agent, in a container, and parse the result.
    *
    * Network is ALLOWED: the project's own build must resolve dependencies to run its tests.
    * The harness runner is where network is denied — that is agent-authored code, this is not.
    */
  def runInContainer(
      repo: Path,
      testCommand: String,
      image: String,
      classDirs: Vector[Path],
      timeoutSec: Int = 1800,
      memoryMb: Int = 2048,
      buildTool: JvmBuildTool = JvmBuildTool.Maven,
      cacheRoot: Option[Path] = None
  ): Either[String, (CoverageReport, ContainerResult)] =
    val agentDir = repo.resolve(".causeway")
    val execFile = agentDir.resolve("jacoco.exec")

    extractAgent(agentDir).flatMap { _ =>
      // Delete BEFORE the run, not append=false during it. A file left by an earlier revision
      // would otherwise be parsed as this run's result the moment the agent fails to attach —
      // reporting the parent commit's coverage for the fix, with nothing to indicate it.
      Try(Files.deleteIfExists(execFile))

      // JAVA_TOOL_OPTIONS, not -DargLine. Surefire's `-DargLine` property is OVERRIDDEN by a
      // POM-level <argLine>, and hard-coding one is common (jsoup pins -Xss640k that way), so
      // the property silently attaches nothing and the run completes looking healthy. The JVM
      // honours JAVA_TOOL_OPTIONS unconditionally and ADDITIVELY, leaving the project's own
      // flags intact.
      //
      // The cost is that every JVM in the build picks it up, including Maven's own, so the
      // agent must APPEND — with append=false the last JVM to exit (Maven) would truncate the
      // file and throw away the test JVM's data. Deleting up front keeps append honest.
      val cmd    = instrumentedCommand(testCommand)
      val (mounts, cacheEnv) = cacheRoot
        .map(DependencyCache.forTool(buildTool, _))
        .getOrElse((Vector.empty, Map.empty))

      val result = Container.exec(
        ContainerPolicy(image, network = true, memoryMb = memoryMb, timeoutSec = timeoutSec,
                        mounts = mounts, env = cacheEnv), repo, cmd)

      parse(execFile, classDirs) match
        case Right(report) => Right((report, result))
        // The parse error alone ("no exec file") says nothing a caller can act on. What failed
        // is the TEST RUN, and its tail is exactly what build-doctor needs to fix the recipe.
        case Left(err) =>
          Left(s"""$err
                  |test run: exit=${result.exitCode} timedOut=${result.timedOut} ${result.durationMs / 1000}s
                  |${result.combinedTail(40)}""".stripMargin)
    }

  /** Run an already-built program under the agent on the host.
    *
    * Used for reproducer runs, where the "suite" is a single harness rather than the project's
    * tests, and for tests of this service itself.
    */
  def runLocally(
      workdir: Path,
      classpath: Vector[String],
      mainClass: String,
      timeoutSec: Int = 120
  ): Either[String, CoverageReport] =
    val agentDir = workdir.resolve(".causeway")
    for
      agent <- extractAgent(agentDir)
      exec  = agentDir.resolve("jacoco.exec")
      _ <- Try {
        val cmd = Vector(
          "java", agentArg(agent.toAbsolutePath.toString, exec.toAbsolutePath.toString),
          "-cp", classpath.mkString(File.pathSeparator), mainClass
        )
        val proc = ProcessBuilder(cmd.asJava).directory(workdir.toFile).redirectErrorStream(true).start()
        Using.resource(proc.getInputStream)(_.readAllBytes())
        if !proc.waitFor(timeoutSec.toLong, java.util.concurrent.TimeUnit.SECONDS) then
          proc.destroyForcibly()
          throw IllegalStateException(s"run exceeded ${timeoutSec}s")
        ()
      }.toEither.left.map(t => s"cannot run under agent: ${t.getMessage}")
      report <- parse(exec, classpath.map(Path.of(_)))
    yield report
