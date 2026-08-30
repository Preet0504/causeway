package causeway.jvm

import causeway.core.{CoverageStatus, MethodRef, OldLines, JvmBuildTool}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

/** Runs a real program under the real JaCoCo agent and parses the real exec file.
  *
  * On the host, not in a container — coverage parsing is pure, and proving it against genuine
  * instrumentation output is what matters. The fixture has one branch that is exercised and one
  * that is not, which is exactly the shape D3's admission criterion asks about: was the faulty
  * line covered before the fix?
  */
class CoverageServiceSpec extends AnyFunSuite with Matchers:

  private lazy val fixture: (Path, Path) =
    val root = Files.createTempDirectory("causeway-cov")
    val src  = root.resolve("src")
    val out  = root.resolve("classes")
    Files.createDirectories(src.resolve("cfg"))
    Files.createDirectories(out)

    // Line numbers matter to the assertions, so the layout is deliberate:
    //  6: if (s == null)          <- taken
    //  7:   return "fallback";    <- COVERED
    //  9: return s.trim();        <- NOT covered (never reached with a non-null section)
    // 18: return "inner";         <- COVERED, and reachable ONLY through the nested class
    // 23: return "after";           <- an OUTER-class probe AFTER the nested class, so the outer
    //                                  class's line span covers 18 and the merge is exercised
    Files.writeString(src.resolve("cfg/ConfigLoader.java"),
      """package cfg;
        |
        |public class ConfigLoader {
        |
        |    public String get(String s) {
        |        if (s == null) {
        |            return "fallback";
        |        }
        |        return s.trim();
        |    }
        |
        |    public String neverCalled() {
        |        return "dead";
        |    }
        |
        |    public static class Inner {
        |        public String hit() {
        |            return "inner";
        |        }
        |    }
        |
        |    public String after() {
        |        return "after";
        |    }
        |}
        |""".stripMargin)

    Files.writeString(src.resolve("Main.java"),
      """import cfg.ConfigLoader;
        |public class Main {
        |    public static void main(String[] a) {
        |        System.out.println(new ConfigLoader().get(null));
        |        System.out.println(new ConfigLoader.Inner().hit());
        |    }
        |}
        |""".stripMargin)

    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then cancel("no system Java compiler")
    val fm = compiler.getStandardFileManager(null, null, null)
    val files = Vector(src.resolve("cfg/ConfigLoader.java"), src.resolve("Main.java")).map(_.toFile)
    val ok = compiler.getTask(null, fm, null, List("-g", "-d", out.toString).asJava, null,
      fm.getJavaFileObjectsFromFiles(files.asJava)).call()
    fm.close()
    if !ok then cancel("fixture failed to compile")
    (root, out)

  private lazy val report: CoverageReport =
    val (root, classes) = fixture
    CoverageService.runLocally(root, Vector(classes.toAbsolutePath.toString), "Main") match
      case Right(r) => r
      case Left(e)  => fail(s"coverage run failed: $e")

  test("the agent jar can be extracted"):
    val dir = Files.createTempDirectory("causeway-agent")
    val jar = CoverageService.extractAgent(dir).toOption.get
    Files.isRegularFile(jar) shouldBe true
    Files.size(jar) should be > 100000L

  // A stale exec from another revision merging into this one would attribute the parent's
  // coverage to the fix, which is exactly the comparison D3 depends on.
  test("the agent argument disables append"):
    val arg = CoverageService.agentArg("/x/jacocoagent.jar", "/x/jacoco.exec")
    arg should include("-javaagent:/x/jacocoagent.jar=")
    arg should include("append=false")
    arg should include("dumponexit=true")

  test("a real run produces a parseable report"):
    report.classesAnalyzed should be >= 2
    report.files.map(_.pathSuffix) should contain("cfg/ConfigLoader.java")

  test("an executed line is reported as covered"):
    val covered = report.linesFor("src/main/java/cfg/ConfigLoader.java", OldLines(7, 7))
    covered.head.status shouldBe CoverageStatus.Covered

  // The D3 question, on real instrumentation: was the faulty line exercised before the fix?
  test("a line that was never executed is reported as uncovered, not as missing"):
    val uncovered = report.linesFor("src/main/java/cfg/ConfigLoader.java", OldLines(9, 9))
    uncovered.head.status shouldBe CoverageStatus.Uncovered

  test("a method never called at all is uncovered throughout"):
    val dead = report.linesFor("src/main/java/cfg/ConfigLoader.java", OldLines(13, 13))
    dead.head.status shouldBe CoverageStatus.Uncovered

  // Counting a comment or a blank line as uncovered would make a well-documented method look
  // worse than an undocumented one.
  test("non-executable lines are excluded from the fraction rather than counted against"):
    // Line 2 is blank: nothing executable, so there is no fraction to report — None, not 0.0.
    report.coveredFraction("cfg/ConfigLoader.java", OldLines(2, 2)) shouldBe None

    val real = report.coveredFraction("cfg/ConfigLoader.java", OldLines(6, 9))
    real shouldBe defined
    real.get should (be > 0.0 and be < 1.0) // one branch taken, one not

  // Worth knowing before reading any coverage number: JaCoCo attributes a class's implicit
  // default constructor to the CLASS DECLARATION line. Line 3 is `public class ConfigLoader {`
  // and shows as covered purely because the class was instantiated. A fix whose hunk happens to
  // start on a class declaration will look covered even if none of its real code ran.
  test("a class declaration line carries the implicit constructor and reads as covered"):
    val decl = report.linesFor("cfg/ConfigLoader.java", OldLines(3, 3))
    decl.head.status shouldBe CoverageStatus.Covered

  // Every class reports its whole getFirstLine..getLastLine span, so the OUTER class also has an
  // entry for a line that lives inside a nested class — with no probe there, hence NotExecutable.
  // Merging must keep the owning class's Covered. Taking the max ordinal instead kept
  // NotExecutable, and on real repositories that erased most of every multi-class source file:
  // parser state machines and connection helpers read as almost entirely non-executable while
  // small single-class files looked fine.
  test("a line inside a nested class keeps its covered status when classes are merged"):
    val inner = report.linesFor("cfg/ConfigLoader.java", OldLines(18, 18))
    inner.head.status shouldBe CoverageStatus.Covered

  test("merging never downgrades a covered line to non-executable"):
    val all = report.linesFor("cfg/ConfigLoader.java", OldLines(1, 25))
    all.filter(_.status == CoverageStatus.Covered).map(_.line) should contain allOf (7, 18)

  test("a path is matched by suffix, since JaCoCo knows packages and not repo layout"):
    val a = report.linesFor("cfg/ConfigLoader.java", OldLines(7, 7))
    val b = report.linesFor("src/main/java/cfg/ConfigLoader.java", OldLines(7, 7))
    val c = report.linesFor("some/deep/nested/prefix/cfg/ConfigLoader.java", OldLines(7, 7))
    a shouldBe b
    b shouldBe c

  test("an unknown file yields nothing rather than a confident zero"):
    report.linesFor("no/such/File.java", OldLines(1, 10)) shouldBe empty
    report.coveredFraction("no/such/File.java", OldLines(1, 10)) shouldBe None

  test("a file summary reports covered over executable"):
    val (covered, executable) = report.fileSummary("cfg/ConfigLoader.java").get
    executable should be > 0
    covered should be > 0
    covered should be < executable // neverCalled() drags it below 100%

  test("parsing without class files is refused rather than silently reporting nothing"):
    val (root, _) = fixture
    val exec = root.resolve(".causeway/jacoco.exec")
    CoverageService.parse(exec, Vector.empty).isLeft shouldBe true

  test("a missing exec file is reported"):
    CoverageService.parse(Path.of("does/not/exist.exec"), Vector(Path.of("."))).isLeft shouldBe true

  // Pinned because the failure mode is SILENT: the wrong mechanism attaches nothing, the suite
  // passes, and the only evidence is an exec file that never appears.
  test("the agent is attached through JAVA_TOOL_OPTIONS, not -DargLine"):
    val cmd = CoverageService.instrumentedCommand("mvn -B test")

    cmd should include("JAVA_TOOL_OPTIONS")
    // A POM-level <argLine> silently overrides the property form. jsoup pins -Xss640k that way.
    cmd should not include "-DargLine"
    cmd should endWith("mvn -B test")

  test("coverage appends, because every JVM in the build inherits the agent"):
    // With append=false the last JVM to exit is Maven's, and it would truncate the file over
    // the test JVM's data.
    CoverageService.instrumentedCommand("mvn -B test") should include("append=true")

  test("a failed instrumented run is not retried uninstrumented"):
    // The old command ended `|| $testCommand`, which re-ran the whole suite without the agent:
    // twice the wall clock, and still no exec file.
    CoverageService.instrumentedCommand("mvn -B test") should not include "||"

  test("the project's own JVM flags survive"):
    // Additive, not replacing — this is the whole reason JAVA_TOOL_OPTIONS is used.
    val cmd = CoverageService.instrumentedCommand("mvn -B test -DargLine=-Xss640k")
    cmd should include("-Xss640k")
    cmd should include("JAVA_TOOL_OPTIONS")

  // The id identifies a MEASUREMENT. It used to hash the exec file's PATH, which is
  // `<clone>/.causeway/jacoco.exec` for every run of a repository — so the parent revision and
  // the fix, the two measurements this project exists to compare, shared an id.
  test("two different measurements get different ids"):
    val dir = Files.createTempDirectory("causeway-covid")
    val classes = Vector(dir.resolve("classes"))
    Files.createDirectories(classes.head)

    def idFor(bytes: Array[Byte]): String =
      val exec = dir.resolve("jacoco.exec")
      Files.write(exec, bytes)
      // Parsing an arbitrary byte array fails; the id is what is under test, so reach it
      // directly through the same path the report uses.
      CoverageService.reportIdFor(exec, classes)

    val a = idFor(Array[Byte](1, 2, 3))
    val b = idFor(Array[Byte](4, 5, 6))

    a should not be b
    a should fullyMatch regex "cov_[0-9a-f]{16}"

    // Same content at the same path is the same measurement, so the id is stable.
    idFor(Array[Byte](1, 2, 3)) shouldBe a

  test("the same exec analysed against different classes is a different report"):
    val dir = Files.createTempDirectory("causeway-covid2")
    val exec = dir.resolve("jacoco.exec")
    Files.write(exec, Array[Byte](9, 9, 9))
    Files.createDirectories(dir.resolve("one"))
    Files.createDirectories(dir.resolve("two"))

    // The probe-to-line mapping comes from the classes, so they are part of the measurement.
    CoverageService.reportIdFor(exec, Vector(dir.resolve("one"))) should
      not be CoverageService.reportIdFor(exec, Vector(dir.resolve("two")))

  // Found on a real run, at S7, after nineteen coverage runs had already completed. The merge
  // used `maxBy(_.status.ordinal)` against `Covered, Uncovered, NotExecutable` — selecting the
  // LEAST informative status, the inverse of what its own comment claimed.
  test("merging class spans keeps the strongest claim about a line"):
    import CoverageStatus.*

    // Every JaCoCo class reports its whole getFirstLine..getLastLine span, so a nested class's
    // siblings report NotExecutable on lines they do not own.
    CoverageService.strength(Covered) should be > CoverageService.strength(Uncovered)
    CoverageService.strength(Uncovered) should be > CoverageService.strength(NotExecutable)

    // The consequence, stated as the property that actually matters: an owner's answer must
    // survive any number of non-owners.
    val claims = Vector(NotExecutable, NotExecutable, Covered, NotExecutable)
    claims.maxBy(CoverageService.strength) shouldBe Covered

    // Uncovered still beats NotExecutable: the line IS executable, it just never ran. Collapsing
    // that distinction is what turns "this line was never tested" into "this line is a comment".
    Vector(NotExecutable, Uncovered).maxBy(CoverageService.strength) shouldBe Uncovered

  // Independent of the enum's declaration order, which is what made the original fail silently.
  test("the precedence does not depend on how the enum is declared"):
    CoverageStatus.values.toVector.maxBy(CoverageService.strength) shouldBe CoverageStatus.Covered

  // ── native test runner (reproducer ladder rung 1) ──────────────────────

  // The selector flag differs per build tool, and passing the wrong one runs the WHOLE suite.
  // That suite would then pass at the parent, and the run would conclude that a fix's own
  // regression test does not capture its own bug.
  test("the test selector is translated per build tool"):
    val sel = "SelectorTest#attributeSelectorHandlesInternalData"

    val maven = NativeTestRunner.testCommandFor(JvmBuildTool.Maven, sel)
    maven should include("-Dtest=")
    maven should include(sel)
    // Without this, a selector that matches nothing FAILS the maven build instead of reporting
    // that nothing matched, and the two are different findings.
    maven should include("-DfailIfNoSpecifiedTests=false")

    val gradle = NativeTestRunner.testCommandFor(JvmBuildTool.Gradle, sel)
    gradle should include("--tests")
    // Gradle separates class and method with a dot, not a hash.
    gradle should include("SelectorTest.attributeSelectorHandlesInternalData")
    gradle should not include "#"

  test("a run that executed no test is not a pass"):
    // The dangerous case: the build succeeds, the selector matched nothing, the exit code is 0.
    // Reading that as "passed" would report that a regression test does not reproduce its bug.
    NativeTestRunner.ranAnyTest("BUILD SUCCESS") shouldBe false
    NativeTestRunner.ranAnyTest("Tests run: 0, Failures: 0, Errors: 0, Skipped: 0") shouldBe false

    NativeTestRunner.ranAnyTest("Tests run: 1, Failures: 1, Errors: 0, Skipped: 0") shouldBe true
    NativeTestRunner.ranAnyTest("3 tests completed, 1 failed") shouldBe true

  test("an assertion failure and a thrown exception are distinguished"):
    // The path tracer uses this to decide the propagation channel, so collapsing them would
    // lose the difference between a crash and a wrong value.
    NativeTestRunner.threwException(
      "java.lang.ClassCastException: class java.util.HashMap cannot be cast") shouldBe true
    NativeTestRunner.threwException(
      "org.opentest4j.AssertionFailedError: expected: <true> but was: <false>") shouldBe false

  test("the differential requires BOTH halves"):
    def side(ran: Boolean, passed: Boolean, detail: String = "") =
      NativeTestSide(ran, passed, detail, Vector.empty)

    def result(p: NativeTestSide, f: NativeTestSide) = NativeTestResult(p, f, 0L)

    // Fails at parent, passes at fix. This is the only combination that proves anything.
    result(side(true, false, "AssertionFailedError"), side(true, true)).differs shouldBe true

    // Fails on both sides: the test is broken, not discriminating.
    result(side(true, false), side(true, false)).differs shouldBe false

    // Passes on both sides: the test does not exercise the defect.
    result(side(true, true), side(true, true)).differs shouldBe false

    // Never ran at the parent: nothing was demonstrated either way.
    result(side(false, false), side(true, true)).differs shouldBe false

  test("differsOn reports the channel only when the sides actually differ"):
    def r(pd: String) = NativeTestResult(
      NativeTestSide(true, false, pd, Vector.empty),
      NativeTestSide(true, true, "", Vector.empty), 0L)

    r("org.opentest4j.AssertionFailedError: expected").differsOn shouldBe Vector("ASSERTION")
    r("java.lang.ClassCastException at Attributes.checkNotNull").differsOn shouldBe Vector("EXCEPTION")

    // No difference means no channel, rather than a default one.
    NativeTestResult(NativeTestSide(true, true, "", Vector.empty),
                     NativeTestSide(true, true, "", Vector.empty), 0L).differsOn shouldBe empty

  test("portTests overlays the fix's tests on the parent and restore puts the tree back"):
    val root   = Files.createTempDirectory("causeway-port")
    val parent = Files.createDirectories(root.resolve("parent/src/test/java/p"))
    val fix    = Files.createDirectories(root.resolve("fix/src/test/java/p"))

    val added    = "src/test/java/p/AddedTest.java"
    val modified = "src/test/java/p/ModifiedTest.java"

    // The fix ADDS one test and MODIFIES another. Only the modified one exists at the parent.
    Files.writeString(root.resolve("fix").resolve(added), "fix-added")
    Files.writeString(root.resolve("fix").resolve(modified), "fix-modified")
    Files.writeString(root.resolve("parent").resolve(modified), "parent-original")

    val displaced = NativeTestRunner.portTests(
      root.resolve("parent"), root.resolve("fix"), Vector(added, modified, "src/test/java/p/Gone.java"))

    // A file the fix deleted has nothing to port and must not fail the run.
    displaced.size shouldBe 2
    Files.readString(root.resolve("parent").resolve(added)) shouldBe "fix-added"
    Files.readString(root.resolve("parent").resolve(modified)) shouldBe "fix-modified"

    NativeTestRunner.restore(displaced)

    // The added file must be GONE, not left behind as an empty or stale test, and the modified
    // one must hold the parent's own content again. Worktrees are cached and reused, so a
    // failure here silently contaminates every later probe at this revision.
    Files.exists(root.resolve("parent").resolve(added)) shouldBe false
    Files.readString(root.resolve("parent").resolve(modified)) shouldBe "parent-original"
    Files.exists(root.resolve("parent").resolve(modified + ".causeway-orig")) shouldBe false

  test("stack frames are parsed from build-tool output, because a throwing method records no probe"):
    val out =
      """|[ERROR] org.jsoup.helper.DataUtilTest.sniff -- Time elapsed: 1.6 s <<< ERROR!
         |java.io.IOException: Stream closed.
         |	at org.jsoup.internal.SimpleBufferedInput.fill(SimpleBufferedInput.java:83)
         |	at java.base/java.io.FilterInputStream.available(FilterInputStream.java:158)
         |	at org.jsoup.nodes.Attributes$Dataset.<init>(Attributes.java:12)
         |[INFO] BUILD FAILURE""".stripMargin

    val frames = NativeTestRunner.stackFramesOf(out)
    val keys   = frames.map(m => (m.fqcn, m.name))

    keys should contain (("org.jsoup.internal.SimpleBufferedInput", "fill"))
    // The JDK 9+ module prefix is stripped, so JDK frames land under their plain class name.
    keys should contain (("java.io.FilterInputStream", "available"))
    // Inner classes and <init> must survive.
    keys should contain (("org.jsoup.nodes.Attributes$Dataset", "<init>"))
    // Non-frame lines contribute nothing.
    frames.size shouldBe 3
    // A stack trace carries no descriptor; D27 matches by class and name only.
    frames.foreach(_.descriptor shouldBe "")

  test("selector classes are extracted for both Maven and Gradle selector dialects"):
    NativeTestRunner.selectorClasses("W3CDomTest#a+b") shouldBe Vector("W3CDomTest")
    NativeTestRunner.selectorClasses("org.jsoup.select.SelectorTest.attrData") shouldBe Vector("SelectorTest")
    NativeTestRunner.selectorClasses("ATest#m,BTest#n") shouldBe Vector("ATest", "BTest")

  test("porting is scoped to the selector's classes, so an uncompilable sibling cannot block it"):
    val changed = Vector(
      "src/test/java/org/jsoup/helper/HttpConnectionTest.java",
      "src/test/java/org/jsoup/integration/ConnectTest.java")

    // This is the 4e381147 shape: the selector wants ConnectTest, while HttpConnectionTest calls
    // an API the fix introduced and cannot compile at the parent.
    val (port, skip) = NativeTestRunner.scopePortFiles(changed, "ConnectTest#redirectsPostToGet")
    port shouldBe Vector("src/test/java/org/jsoup/integration/ConnectTest.java")
    skip shouldBe Vector("src/test/java/org/jsoup/helper/HttpConnectionTest.java")

    // A selector naming no changed file ports nothing rather than everything: the test already
    // exists at the parent and the parent's own version is the right one to run.
    val (none, all) = NativeTestRunner.scopePortFiles(changed, "SomeOtherTest#x")
    none shouldBe empty
    all.size shouldBe 2

  test("the D27 union preserves coverage overloads and appends only frame-only methods"):
    // Coverage knows two overloads of the same name, with real descriptors.
    val coverage = Vector(
      MethodRef("org.jsoup.internal.StringUtil", "resolve", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
      MethodRef("org.jsoup.internal.StringUtil", "resolve", "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;"))

    // Frames name one method coverage already has, and one it does not.
    val frames = Vector(
      MethodRef("org.jsoup.internal.StringUtil", "resolve", ""),
      MethodRef("org.jsoup.nodes.Attributes", "checkNotNull", ""))

    val coveredKeys = coverage.map(m => (m.fqcn, m.name)).toSet
    val frameOnly   = frames.filterNot(m => coveredKeys.contains((m.fqcn, m.name))).distinct
    val executed    = coverage ++ frameOnly

    // Both overloads survive: collapsing them would make a hop between them unscoreable.
    executed.count(m => m.name == "resolve") shouldBe 2
    // The frame that duplicates a covered (class, name) is NOT appended as a descriptor-less twin.
    executed.count(m => m.name == "resolve" && m.descriptor.isEmpty) shouldBe 0
    // The genuinely frame-only method IS added — this is the method that threw.
    executed.map(m => (m.fqcn, m.name)) should contain (("org.jsoup.nodes.Attributes", "checkNotNull"))
    frameOnly.size shouldBe 1
