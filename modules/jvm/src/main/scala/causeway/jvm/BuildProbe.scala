package causeway.jvm

import causeway.core.{BuildFailureClass, JvmBuildTool}

import java.nio.file.{Files, Path}
import scala.util.Try

final case class Toolchain(
    buildTool: JvmBuildTool,
    declaredSourceLevel: Option[String],
    wrapperPresent: Boolean,
    markerFiles: Vector[String]
)

final case class BuildRecipe(
    baseImage: String,
    buildTool: JvmBuildTool,
    buildCommand: String,
    testCommand: String,
    cleanCommand: String,
    flags: Vector[String] = Vector.empty
)

enum ProbeOutcome:
  case Succeeded(durationMs: Long)
  case Failed(classification: BuildFailureClass, exitCode: Int, stderrTail: String, durationMs: Long)

/** Detects how a repository builds, and proves it by compiling in a container.
  *
  * Compile only, never tests: a compile failure surfaces in seconds where a full test run takes
  * minutes, and this gates every expensive stage downstream. Two revisions per bug, so the
  * saving compounds.
  */
object BuildProbe:

  /** Marker files, checked in a fixed order so a polyglot repo resolves deterministically. */
  def detect(repo: Path): Option[Toolchain] =
    detectWith(p => Files.exists(repo.resolve(p)), p => readIfPresent(repo.resolve(p)))

  /** Detect from a specific revision rather than the working tree.
    *
    * Build files change over history — a project on Maven in 2016 may be on Gradle now — and
    * this project exists to probe OLD revisions. Reading the working tree when asked about a
    * commit would report today's toolchain for a decade-old build and send the build-doctor
    * after the wrong image entirely.
    */
  def detectAt(readAtCommit: String => Option[String]): Option[Toolchain] =
    detectWith(p => readAtCommit(p).isDefined, readAtCommit)

  private def readIfPresent(p: Path): Option[String] =
    if Files.isRegularFile(p) then Try(Files.readString(p)).toOption else None

  private def detectWith(has: String => Boolean, read: String => Option[String]): Option[Toolchain] =

    val (tool, markers) =
      if has("build.sbt") then (Some(JvmBuildTool.Sbt), Vector("build.sbt"))
      else if has("pom.xml") then (Some(JvmBuildTool.Maven), Vector("pom.xml"))
      else if has("build.gradle") then (Some(JvmBuildTool.Gradle), Vector("build.gradle"))
      else if has("build.gradle.kts") then (Some(JvmBuildTool.Gradle), Vector("build.gradle.kts"))
      else (None, Vector.empty)

    tool.map { t =>
      val wrapper = t match
        case JvmBuildTool.Maven  => has("mvnw")
        case JvmBuildTool.Gradle => has("gradlew")
        case JvmBuildTool.Sbt    => has("sbtw")

      val buildFile = t match
        case JvmBuildTool.Maven  => "pom.xml"
        case JvmBuildTool.Gradle => "build.gradle"
        case JvmBuildTool.Sbt    => "build.sbt"

      Toolchain(t, read(buildFile).flatMap(levelFrom), wrapper, markers)
    }
  /** Read the declared Java level from a build file's TEXT.
    *
    * Evidence for choosing a base image, not a guarantee — the CI workflow is a more reliable
    * source, which is why `build-doctor` reads that first and this is a fallback.
    */
  private def levelFrom(text: String): Option[String] =
    Vector(
      "<maven.compiler.source>([^<]+)</maven.compiler.source>".r,
      "<maven.compiler.release>([^<]+)</maven.compiler.release>".r,
      "<source>([^<]+)</source>".r,
      """sourceCompatibility\s*=\s*['"]?([0-9.]+)""".r
    ).flatMap(_.findFirstMatchIn(text).map(_.group(1).trim)).headOption

  /** Map a declared Java level onto a MAJOR VERSION. */
  private def majorOf(javaLevel: Option[String]): Int =
    javaLevel.map(_.stripPrefix("1.")).flatMap(s => s.takeWhile(_.isDigit).toIntOption) match
      case Some(n) if n <= 8  => 8
      case Some(n) if n <= 11 => 11
      case Some(n) if n <= 17 => 17
      case Some(n) if n <= 21 => 21
      case _                  => 17

  /** Choose a container image for a Java level AND a build tool.
    *
    * The build tool matters, and getting this wrong is silent until you read the exit code.
    * `eclipse-temurin:17-jdk` carries a JDK and nothing else — no `mvn`, no `gradle` — so a
    * Maven recipe run in it fails with exit 127 and classifies as `MISSING_TOOLCHAIN`, which
    * reads as "this repository has no usable build" rather than "we picked the wrong image".
    *
    * Discovered by the build-doctor agent on a real run against jsoup, not by a test: the tests
    * only ever asserted the JDK version in the image name.
    */
  def imageFor(javaLevel: Option[String], tool: JvmBuildTool = JvmBuildTool.Maven): String =
    val major = majorOf(javaLevel)
    tool match
      case JvmBuildTool.Maven  => s"maven:3.9-eclipse-temurin-$major"
      case JvmBuildTool.Gradle => s"gradle:8-jdk$major"
      // sbt is not published as an official image; the Scala community image carries a JDK,
      // Scala and sbt together.
      case JvmBuildTool.Sbt    => s"sbtscala/scala-sbt:eclipse-temurin-$major.0.2_1.10.7_3.3.4"

  /** The plain JDK image, for running compiled code where no build tool is needed. */
  def runtimeImageFor(javaLevel: Option[String]): String =
    s"eclipse-temurin:${majorOf(javaLevel)}-jdk"

  def defaultRecipe(toolchain: Toolchain): BuildRecipe =
    val image = imageFor(toolchain.declaredSourceLevel, toolchain.buildTool)
    toolchain.buildTool match
      case JvmBuildTool.Maven =>
        BuildRecipe(image, JvmBuildTool.Maven,
          "mvn -B -q -DskipTests compile", "mvn -B test", "mvn -B -q clean")
      case JvmBuildTool.Gradle =>
        BuildRecipe(image, JvmBuildTool.Gradle,
          "./gradlew --no-daemon classes", "./gradlew --no-daemon test", "./gradlew --no-daemon clean")
      case JvmBuildTool.Sbt =>
        BuildRecipe(image, JvmBuildTool.Sbt, "sbt -batch compile", "sbt -batch test", "sbt -batch clean")

  /** Classify a failure.
    *
    * This is load-bearing, not cosmetic. `JdkMismatch` is fixed by choosing another image;
    * `DependencyResolution` on a decade-old commit usually cannot be fixed at all. The
    * distribution across a run tells you whether the misses are the repository's fault or the
    * environment's, and therefore whether more provisioning effort would recover history.
    */
  def classify(result: ContainerResult): BuildFailureClass =
    val text = result.combinedTail(200).toLowerCase

    if result.timedOut then BuildFailureClass.Timeout
    else if text.contains("could not resolve dependencies") ||
      text.contains("could not find artifact") ||
      text.contains("failed to collect dependencies") ||
      text.contains("unknownhostexception") ||
      text.contains("could not transfer artifact") then BuildFailureClass.DependencyResolution
    else if text.contains("invalid target release") ||
      text.contains("unsupported class file major version") ||
      text.contains("has been compiled by a more recent version") ||
      text.contains("invalid source release") then BuildFailureClass.JdkMismatch
    else if text.contains("command not found") ||
      text.contains("not found") && (text.contains("mvn") || text.contains("gradle") || text.contains("sbt"))
    then BuildFailureClass.MissingToolchain
    else BuildFailureClass.CompileError

  /** The shell line a probe runs: an optional clean, then the build with its flags.
    *
    * Flags go AFTER the build command, as arguments to it. They used to be prepended to the
    * whole line, producing `-DskipTests mvn -B -q compile` — which is not a command, so the only
    * safe advice was "pass no flags", and a declared parameter that must not be used is not a
    * parameter. A real run reported exactly that as a recipe caveat.
    */
  def shellCommandFor(recipe: BuildRecipe, stale: Boolean): String =
    val clean = if stale then Vector(recipe.cleanCommand, "&&") else Vector.empty
    (clean ++ (recipe.buildCommand +: recipe.flags)).mkString(" ")

  /** Where a build tool leaves its output. */
  private def outputDir(repo: Path, tool: JvmBuildTool): Path = tool match
    case JvmBuildTool.Maven | JvmBuildTool.Sbt => repo.resolve("target")
    case JvmBuildTool.Gradle                   => repo.resolve("build")

  /** Was the existing build output produced by a DIFFERENT toolchain?
    *
    * A working tree is reused across revisions, and a build tool skips recompiling a class whose
    * output is newer than its source — so classes left by an earlier run under another JDK
    * survive a rebuild. On jsoup, `target/test-classes` held Java 11 bytecode while the recipe
    * called for JDK 8: the main sources recompiled, the tests did not, and surefire died with
    * "class file version 55.0" before running a single test. The suite reported ZERO tests, the
    * build reported SUCCESS, and coverage came back near zero — indistinguishable, downstream,
    * from a project that does not test itself.
    *
    * Output present with NO marker is unknown provenance, and unknown is not clean. A fresh
    * clone has no output directory at all, so this costs nothing there.
    */
  def needsClean(repo: Path, tool: JvmBuildTool, image: String): Boolean =
    val marker    = repo.resolve(".causeway").resolve("build-image")
    val lastImage = Try(Files.readString(marker).trim).toOption
    Files.isDirectory(outputDir(repo, tool)) && !lastImage.contains(image)

  /** Compile one revision in a container.
    *
    * Network is ALLOWED here: Maven and Gradle cannot resolve dependencies without it, and a
    * network-free build would fail as DependencyResolution for a reason that has nothing to do
    * with the commit under test. The harness runner is where network is denied.
    */
  def probe(
      repo: Path,
      recipe: BuildRecipe,
      timeoutSec: Int = 600,
      memoryMb: Int = 2048,
      cacheRoot: Option[Path] = None
  ): ProbeOutcome =
    val (mounts, env) = cacheRoot
      .map(DependencyCache.forTool(recipe.buildTool, _))
      .getOrElse((Vector.empty, Map.empty))

    val policy = ContainerPolicy(
      image = recipe.baseImage,
      network = true,
      memoryMb = memoryMb,
      timeoutSec = timeoutSec,
      mounts = mounts,
      env = env
    )

    // Clean when the last build here used a DIFFERENT image, and only then.
    //
    // A working tree is reused across revisions, and a build tool skips recompiling a class
    // whose output is newer than its source — so classes left by an earlier run under another
    // JDK survive. On a real jsoup run, target/test-classes held Java 11 bytecode while the
    // recipe called for JDK 8; the main sources recompiled, the tests did not, and surefire died
    // with "class file version 55.0" before running a single test. The suite reported ZERO tests
    // and the build reported SUCCESS, which downstream reads as a project with no test coverage.
    val stale = needsClean(repo, recipe.buildTool, recipe.baseImage)

    // Flags go AFTER the build command, as arguments to it. They used to be prepended to the
    // whole shell line, producing `-DskipTests mvn -B -q compile` — which is not a command, so
    // the only safe advice was "pass no flags", and a declared parameter that must not be used
    // is not a parameter. A real run reported exactly that as a recipe caveat.
    val cmd = shellCommandFor(recipe, stale)

    val result = Container.exec(policy, repo, cmd)

    // Written after the fact, so a failed build does not claim the tree belongs to this image.
    if result.succeeded then
      Try {
        val marker = repo.resolve(".causeway").resolve("build-image")
        Files.createDirectories(marker.getParent)
        Files.writeString(marker, recipe.baseImage)
      }

    if result.succeeded then ProbeOutcome.Succeeded(result.durationMs)
    else
      ProbeOutcome.Failed(classify(result), result.exitCode, result.combinedTail(30), result.durationMs)
