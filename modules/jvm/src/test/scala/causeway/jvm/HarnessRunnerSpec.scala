package causeway.jvm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** Proves the differential oracle (D14) on a real bug, in real containers.
  *
  * Cancels when Docker is unreachable. This is the design's most novel claim — that behavioural
  * difference across the fix boundary substitutes for a human oracle — so it is tested by
  * actually compiling and running two revisions, not by mocking the comparison.
  */
class HarnessRunnerSpec extends AnyFunSuite with Matchers:

  private val Image = "eclipse-temurin:17-jdk-alpine"

  private def requireDocker(): Unit =
    if !Container.available() then cancel("Docker not reachable — skipping")
    if !Container.imagePresent(Image) then cancel(s"$Image not pulled — skipping")

  private def sourceDir(files: (String, String)*): Path =
    val d = Files.createTempDirectory("causeway-side")
    files.foreach { case (n, c) => Files.writeString(d.resolve(n), c) }
    d

  /** Like [[sourceDir]], but `path` may include directories (e.g. `"src/test/java/Foo.java"`),
    * so a fixture can put a file under a `test`/`tests` directory the way a real checkout does.
    */
  private def sourceTree(files: (String, String)*): Path =
    val d = Files.createTempDirectory("causeway-side")
    files.foreach { case (rel, c) =>
      val p = d.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, c)
    }
    d

  private def work(): Path = Files.createTempDirectory("causeway-harness")

  // The worked example from the design, as compilable Java.
  private val buggyLoader =
    """import java.util.*;
      |public class ConfigLoader {
      |    private final Map<String, Map<String,String>> sections = new HashMap<>();
      |    private final Map<String,String> defaults = new HashMap<>();
      |    public void put(String section, String k, String v) {
      |        sections.computeIfAbsent(section, s -> new HashMap<>()).put(k, v);
      |    }
      |    public void putDefault(String k, String v) { defaults.put(k, v); }
      |    public String get(String section, String key) {
      |        Map<String,String> s = sections.get(section);
      |        return s.get(key);
      |    }
      |}""".stripMargin

  private val fixedLoader = buggyLoader.replace(
    "        Map<String,String> s = sections.get(section);\n        return s.get(key);",
    "        Map<String,String> s = sections.get(section);\n" +
      "        if (s == null) { return defaults.getOrDefault(key, null); }\n" +
      "        return s.get(key);"
  )

  test("the fixture actually differs between revisions"):
    fixedLoader should include("if (s == null)")
    buggyLoader should not include "if (s == null)"

  // The headline claim: no human oracle needed.
  test("an input that triggers the bug shows differs=true across the fix boundary"):
    requireDocker()
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.putDefault("key", "fallback");
        |        return c.get("absent-section", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      harness, Image
    )

    withClue(s"compileError=${r.compileError}; parent=${r.atParent}; fix=${r.atFix}: ") {
      r.compiled shouldBe true
      r.differs shouldBe true
      r.triggersBug shouldBe true
    }

    // The buggy revision throws; the fixed one returns the default. Both channels differ.
    r.atParent.get.exceptionType shouldBe Some("java.lang.NullPointerException")
    r.atFix.get.exceptionType shouldBe None
    r.atFix.get.returnValue shouldBe Some("fallback")
    r.differsOn should contain(DiffChannel.Exception)

  // Reachability is not triggering: this input runs the faulty method and proves nothing.
  test("an input that reaches the fault but does not infect shows differs=false"):
    requireDocker()
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.put("present", "key", "value");
        |        return c.get("present", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      harness, Image
    )

    r.compiled shouldBe true
    r.differs shouldBe false
    r.triggersBug shouldBe false
    r.atParent.get.returnValue shouldBe Some("value")
    r.atFix.get.returnValue shouldBe Some("value")

  test("identical revisions can never differ — guarding against a false-positive oracle"):
    requireDocker()
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        return c.get("absent", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      harness, Image
    )

    r.compiled shouldBe true
    r.differs shouldBe false

  test("a harness that does not compile is reported as such, not as 'did not reproduce'"):
    requireDocker()
    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      "public class Harness { public static Object run() { this is not java } }",
      Image
    )

    r.compiled shouldBe false
    r.differs shouldBe false
    r.compileError shouldBe defined
    // The distinction matters: a compile failure costs the agent a retry, whereas
    // differs=false would be read as evidence the input does not trigger the bug.
    r.triggersBug shouldBe false

  test("a return-value-only difference is detected, with no exception on either side"):
    requireDocker()
    val v1 = "public class Calc { public static int f(int x){ return x / 2; } }"
    val v2 = "public class Calc { public static int f(int x){ return (x + 1) / 2; } }"
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable { return Calc.f(7); }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("Calc.java" -> v1)),
      HarnessRunner.Side(sourceDir("Calc.java" -> v2)),
      harness, Image
    )

    r.differs shouldBe true
    r.differsOn shouldBe Vector(DiffChannel.Return)
    r.atParent.get.returnValue shouldBe Some("3")
    r.atFix.get.returnValue shouldBe Some("4")
    r.atParent.get.exceptionType shouldBe None

  // A17: the oracle is deliberately blind to side effects, and that blindness must be honest.
  test("a difference visible only through stdout is NOT detected — a known blind spot"):
    requireDocker()
    val v1 = """public class P { public static void go(){ System.out.println("old"); } }"""
    val v2 = """public class P { public static void go(){ System.out.println("new"); } }"""
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable { P.go(); return "same"; }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("P.java" -> v1)),
      HarnessRunner.Side(sourceDir("P.java" -> v2)),
      harness, Image
    )

    r.compiled shouldBe true
    // The behaviour genuinely differs, but not through a channel D15 observes. Such a bug lands
    // in T3/T4 regardless of how reproducible it actually is — a limitation of the instrument,
    // not of the bug.
    r.differs shouldBe false

  // The design's central claim, completed: a static path means propagation is POSSIBLE; the
  // executed set means it HAPPENED. Without this, every path is a hypothesis.
  test("a reproducing run records the methods that actually executed"):
    requireDocker()
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.putDefault("key", "fallback");
        |        return c.get("absent-section", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      harness, Image
    )

    r.differs shouldBe true
    r.executedAtParent should not be empty

    val names = r.executedAtParent.map(m => s"${m.fqcn}.${m.name}")
    names should contain("ConfigLoader.get")     // the fault site ran
    names should contain("Harness.run")          // and the input that reached it

  test("a method that exists but never ran is absent from the trace"):
    requireDocker()
    // Append before the FINAL brace. Replacing the first "}" lands inside the class body and
    // produces source that does not compile.
    val withDead =
      buggyLoader.stripSuffix("}") + "    public String neverCalled() { return \"dead\"; }\n}"

    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.putDefault("key", "fallback");
        |        return c.get("absent-section", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> withDead)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      harness, Image
    )

    r.compiled shouldBe true
    // Static analysis would list neverCalled as a method of the class; the trace must not,
    // because a path through it never happened.
    r.executedAtParent.map(_.name) should not contain "neverCalled"

  test("instrumentation can be switched off, and the differential answer still stands"):
    requireDocker()
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.putDefault("key", "fallback");
        |        return c.get("absent-section", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> buggyLoader)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoader)),
      harness, Image, trace = false
    )

    r.differs shouldBe true
    r.executedAtParent shouldBe empty

  // JaCoCo places probes at branch points and method exits, so a method whose call THROWS may
  // record zero covered instructions even though it certainly ran. For an exception-manifesting
  // bug that undercounts exactly the chain leading to the fault. The stack trace fills it in.
  // Found live: `stage` copies the whole checked-out revision, tests included, and the compile
  // step used to `find .` for every `.java` file with no directory boundary — so a repo whose
  // OWN test sources import a test-scope library (JUnit, here) failed to compile on THEIR
  // missing import, not on anything the harness wrote. A reproducer-synthesist burned its entire
  // iteration budget hitting the identical "package org.junit does not exist" wall on every
  // attempt, on a repo whose production code compiled fine standalone.
  test("a repo's own test sources, needing a library this container was never given, do not block the harness"):
    requireDocker()
    val uncompilableTest =
      """import org.junit.Test;
        |public class ConfigLoaderTest {
        |    @Test public void whatever() { }
        |}""".stripMargin

    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable {
        |        ConfigLoader c = new ConfigLoader();
        |        c.putDefault("key", "fallback");
        |        return c.get("absent-section", "key");
        |    }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceTree(
        "src/main/java/ConfigLoader.java" -> buggyLoader,
        "src/test/java/ConfigLoaderTest.java" -> uncompilableTest)),
      HarnessRunner.Side(sourceTree(
        "src/main/java/ConfigLoader.java" -> fixedLoader,
        "src/test/java/ConfigLoaderTest.java" -> uncompilableTest)),
      harness, Image
    )

    withClue(s"compileError=${r.compileError}: ") { r.compiled shouldBe true }
    r.differs shouldBe true
    r.triggersBug shouldBe true

  // Found live, immediately after the fixture above started passing compilation for the first
  // time: `javac` without `-d` leaves a class file NEXT TO its own source, not mirrored under
  // the compile root — so a PACKAGED class compiled from `src/main/java/org/example/Foo.java`
  // landed at `src/main/java/org/example/Foo.class`, and `java -cp . Runner` could never find
  // it: the classloader looks for `./org/example/Foo.class`. Every other fixture in this file
  // uses the default (unnamed) package, where source directory and compile root happen to
  // coincide, which is exactly why this was invisible here while breaking every real repository
  // — essentially all real Java is packaged. Both sides threw the identical NoClassDefFoundError,
  // so this was not merely a crash: it silently read back as `differs=false`, "does not trigger".
  test("a packaged class is found at runtime, not just compiled"):
    requireDocker()
    val fooV1 = "package org.example;\npublic class Foo { public static int f(int x){ return x / 2; } }"
    val fooV2 = "package org.example;\npublic class Foo { public static int f(int x){ return (x + 1) / 2; } }"
    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable { return org.example.Foo.f(7); }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceTree("src/main/java/org/example/Foo.java" -> fooV1)),
      HarnessRunner.Side(sourceTree("src/main/java/org/example/Foo.java" -> fooV2)),
      harness, Image
    )

    withClue(s"parent=${r.atParent}; fix=${r.atFix}: ") {
      r.atParent.get.exceptionType shouldBe None // NoClassDefFoundError here means this regressed
      r.atFix.get.exceptionType shouldBe None
    }
    r.differs shouldBe true
    r.atParent.get.returnValue shouldBe Some("3")
    r.atFix.get.returnValue shouldBe Some("4")

  test("methods that threw rather than returned still appear in the trace"):
    requireDocker()
    val loader =
      """import java.util.*;
        |public class ConfigLoader {
        |    private final Map<String,String> m = new HashMap<>();
        |    public String get(String s) { return m.get(s).trim(); }
        |}""".stripMargin
    val service =
      """public class ConfigService {
        |    private final ConfigLoader l = new ConfigLoader();
        |    public String load(String k) { return l.get(k); }
        |}""".stripMargin
    val fixedLoaderSrc = loader.replace("return m.get(s).trim();",
      "String v = m.get(s); return v == null ? \"fallback\" : v.trim();")

    val harness =
      """public class Harness {
        |    public static Object run() throws Throwable { return new ConfigService().load("absent"); }
        |}""".stripMargin

    val r = HarnessRunner.differential(
      work(),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> loader, "ConfigService.java" -> service)),
      HarnessRunner.Side(sourceDir("ConfigLoader.java" -> fixedLoaderSrc, "ConfigService.java" -> service)),
      harness, Image
    )

    r.differs shouldBe true
    val names = r.executedAtParent.map(m => s"${m.fqcn}.${m.name}")

    // ConfigService.load threw rather than returned, so coverage alone would omit it — and the
    // path from the entry point to the fault would be broken exactly where it matters.
    names should contain("ConfigService.load")
    names should contain("ConfigLoader.get")
