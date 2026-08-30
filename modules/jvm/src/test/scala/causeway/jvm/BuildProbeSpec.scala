package causeway.jvm

import causeway.core.{BuildFailureClass, JvmBuildTool}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class BuildProbeSpec extends AnyFunSuite with Matchers:

  private def repoWith(files: (String, String)*): Path =
    val d = Files.createTempDirectory("causeway-probe")
    files.foreach { case (name, content) =>
      val f = d.resolve(name)
      Files.createDirectories(f.getParent)
      Files.writeString(f, content)
    }
    d

  private def failure(text: String, timedOut: Boolean = false) =
    ContainerResult(if timedOut then -1 else 1, "", text, timedOut, 100)

  test("build tools are detected from their marker files"):
    BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get.buildTool shouldBe JvmBuildTool.Maven
    BuildProbe.detect(repoWith("build.sbt" -> "")).get.buildTool shouldBe JvmBuildTool.Sbt
    BuildProbe.detect(repoWith("build.gradle" -> "")).get.buildTool shouldBe JvmBuildTool.Gradle
    BuildProbe.detect(repoWith("build.gradle.kts" -> "")).get.buildTool shouldBe JvmBuildTool.Gradle

  test("a repository with no build file is reported as undetectable, not guessed"):
    BuildProbe.detect(repoWith("README.md" -> "hi")) shouldBe None

  test("detection is deterministic when several markers are present"):
    val poly = repoWith("build.sbt" -> "", "pom.xml" -> "<project/>", "build.gradle" -> "")
    // sbt wins by fixed precedence — the point is that repeated runs agree, not which wins.
    BuildProbe.detect(poly).get.buildTool shouldBe JvmBuildTool.Sbt
    BuildProbe.detect(poly).get.buildTool shouldBe JvmBuildTool.Sbt

  test("a wrapper script is noticed"):
    BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get.wrapperPresent shouldBe false
    BuildProbe.detect(repoWith("pom.xml" -> "<project/>", "mvnw" -> "#!/bin/sh")).get
      .wrapperPresent shouldBe true

  test("the declared Java level is read from the build file"):
    val maven = repoWith("pom.xml" ->
      """<project><properties><maven.compiler.source>11</maven.compiler.source></properties></project>""")
    BuildProbe.detect(maven).get.declaredSourceLevel shouldBe Some("11")

    val gradle = repoWith("build.gradle" -> """sourceCompatibility = '1.8'""")
    BuildProbe.detect(gradle).get.declaredSourceLevel shouldBe Some("1.8")

  test("an absent Java level is None rather than a default that looks declared"):
    BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get.declaredSourceLevel shouldBe None

  // Choosing an image is why JDK_MISMATCH is recoverable: on a bare host it would mean
  // provisioning another JDK; here one string changes.
  test("a Java level maps to a container image, including the 1.x form"):
    BuildProbe.runtimeImageFor(Some("1.8")) should include("8-jdk")
    BuildProbe.runtimeImageFor(Some("8")) should include("8-jdk")
    BuildProbe.runtimeImageFor(Some("11")) should include("11-jdk")
    BuildProbe.runtimeImageFor(Some("17")) should include("17-jdk")
    BuildProbe.runtimeImageFor(Some("21")) should include("21-jdk")
    BuildProbe.runtimeImageFor(None) should include("17-jdk")

  test("an unparseable Java level falls back rather than throwing"):
    BuildProbe.runtimeImageFor(Some("not-a-version")) should include("jdk")

  // Found by the build-doctor agent on a real jsoup run, not by a test: eclipse-temurin carries
  // a JDK and nothing else, so `mvn` exits 127 and classifies as MISSING_TOOLCHAIN — which reads
  // as "this repository has no usable build" rather than "we picked the wrong image".
  test("the image carries the BUILD TOOL, not just the JDK"):
    BuildProbe.imageFor(Some("17"), JvmBuildTool.Maven) should startWith("maven:")
    BuildProbe.imageFor(Some("17"), JvmBuildTool.Maven) should include("17")
    BuildProbe.imageFor(Some("17"), JvmBuildTool.Gradle) should startWith("gradle:")
    BuildProbe.imageFor(Some("11"), JvmBuildTool.Gradle) should include("jdk11")
    BuildProbe.imageFor(Some("17"), JvmBuildTool.Sbt) should include("sbt")

  test("a Maven recipe never pairs a plain JDK image with mvn"):
    val maven = BuildProbe.defaultRecipe(
      BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get)
    maven.buildCommand should startWith("mvn")
    maven.baseImage should not startWith "eclipse-temurin"

  test("recipes are compile-only — a probe must never run tests"):
    val maven = BuildProbe.defaultRecipe(BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get)
    maven.buildCommand should include("skipTests")
    maven.buildCommand should not include "test "
    maven.testCommand should include("test")

  // The classification is what tells you whether a miss is the repo's fault or the environment's.
  test("dependency resolution failures are classified as such"):
    BuildProbe.classify(failure("[ERROR] Failed to execute goal: Could not resolve dependencies for project"))
      .shouldBe(BuildFailureClass.DependencyResolution)
    BuildProbe.classify(failure("Could not transfer artifact org.foo:bar from/to central"))
      .shouldBe(BuildFailureClass.DependencyResolution)
    BuildProbe.classify(failure("java.net.UnknownHostException: repo.maven.apache.org"))
      .shouldBe(BuildFailureClass.DependencyResolution)

  test("a JDK mismatch is distinguished from a compile error — one is recoverable, one is not"):
    BuildProbe.classify(failure("javac: invalid target release: 21"))
      .shouldBe(BuildFailureClass.JdkMismatch)
    BuildProbe.classify(failure("Unsupported class file major version 65"))
      .shouldBe(BuildFailureClass.JdkMismatch)
    BuildProbe.classify(failure("class file has been compiled by a more recent version of Java"))
      .shouldBe(BuildFailureClass.JdkMismatch)

  test("a timeout is a timeout, whatever else the output says"):
    BuildProbe.classify(failure("Could not resolve dependencies", timedOut = true))
      .shouldBe(BuildFailureClass.Timeout)

  test("an ordinary compile error is the fallback classification"):
    BuildProbe.classify(failure("Foo.java:[42,8] cannot find symbol"))
      .shouldBe(BuildFailureClass.CompileError)

  // Every recipe needs a clean, because a working tree is reused across revisions and JDKs.
  test("every recipe carries a clean command"):
    Vector("pom.xml" -> "<project/>", "build.gradle" -> "", "build.sbt" -> "").foreach {
      case (marker, content) =>
        val recipe = BuildProbe.defaultRecipe(BuildProbe.detect(repoWith(marker -> content)).get)
        recipe.cleanCommand should include("clean")
    }

  // The staleness rule, stated directly. The consequence of getting this wrong was observed on
  // jsoup: target/test-classes held Java 11 bytecode, the recipe called for JDK 8, surefire died
  // before the first test, and the run reported SUCCESS with zero tests — which downstream reads
  // as a project that does not test itself.
  test("build output from a different image is stale; from the same image it is not"):
    def staleness(existingMarker: Option[String], hasTarget: Boolean): Boolean =
      val d = Files.createTempDirectory("causeway-stale")
      if hasTarget then Files.createDirectories(d.resolve("target"))
      existingMarker.foreach { img =>
        Files.createDirectories(d.resolve(".causeway"))
        Files.writeString(d.resolve(".causeway").resolve("build-image"), img)
      }
      BuildProbe.needsClean(d, JvmBuildTool.Maven, "maven:3.9-eclipse-temurin-17")

    staleness(Some("maven:3.9-eclipse-temurin-8"), hasTarget = true) shouldBe true
    staleness(Some("maven:3.9-eclipse-temurin-17"), hasTarget = true) shouldBe false

    // No marker and no output is a fresh clone — nothing to clean.
    staleness(None, hasTarget = false) shouldBe false

    // No marker but output PRESENT is unknown provenance, and unknown is not clean.
    staleness(None, hasTarget = true) shouldBe true

  // Reported by a real run as a recipe caveat: "pass no `flags` — BuildProbe prepends them to
  // the shell line." A declared parameter that must not be used is not a parameter.
  test("flags are arguments to the build command, not a prefix to the shell line"):
    val recipe = BuildProbe.defaultRecipe(
      BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get)
      .copy(flags = Vector("-DskipTests", "-Dfoo=bar"))

    val cmd = BuildProbe.shellCommandFor(recipe, stale = false)
    cmd shouldBe "mvn -B -q -DskipTests compile -DskipTests -Dfoo=bar"
    // The old form produced `-DskipTests mvn ...`, which is not a command at all.
    cmd should startWith("mvn")

  test("a stale tree is cleaned before the build, and flags still land on the build"):
    val recipe = BuildProbe.defaultRecipe(
      BuildProbe.detect(repoWith("pom.xml" -> "<project/>")).get)
      .copy(flags = Vector("-Dfoo=bar"))

    val cmd = BuildProbe.shellCommandFor(recipe, stale = true)
    cmd should startWith("mvn -B -q clean &&")
    cmd should endWith("-Dfoo=bar")
