package causeway.jvm

import causeway.core.MethodRef
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

/** Builds real bytecode with the host JDK's compiler and analyses it with SootUp.
  *
  * No container needed: this is pure static analysis over class files, so it is fast and
  * offline. The bytecode is genuine, which matters — a hand-built fixture graph would test the
  * queries but not the frontend that has to read real class files.
  */
class CallGraphServiceSpec extends AnyFunSuite with Matchers:

  /** A deliberately layered program: entry -> service -> loader -> fault. */
  private lazy val classesDir: Path =
    val src = Files.createTempDirectory("causeway-cg-src")
    val out = Files.createTempDirectory("causeway-cg-out")

    def write(name: String, body: String): Path =
      val f = src.resolve(name)
      Files.writeString(f, body)
      f

    val files = Vector(
      write("ConfigLoader.java",
        """import java.util.*;
          |public class ConfigLoader {
          |    private final Map<String, Map<String,String>> sections = new HashMap<>();
          |    public String get(String section, String key) {
          |        Map<String,String> s = sections.get(section);
          |        return s.get(key);
          |    }
          |    public String get(String key) { return get("default", key); }
          |}""".stripMargin),
      write("ConfigService.java",
        """public class ConfigService {
          |    private final ConfigLoader loader = new ConfigLoader();
          |    public String load(String section, String key) { return loader.get(section, key); }
          |    public String unrelated() { return "nothing"; }
          |}""".stripMargin),
      write("Handler.java",
        """public class Handler {
          |    private final ConfigService svc = new ConfigService();
          |    public String handle(String path) { return svc.load("http", path); }
          |}""".stripMargin),
      write("Isolated.java",
        """public class Isolated {
          |    public String alone() { return "never calls the loader"; }
          |}""".stripMargin)
    )

    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then cancel("no system Java compiler (JRE rather than JDK?)")
    val fm = compiler.getStandardFileManager(null, null, null)
    val units = fm.getJavaFileObjectsFromFiles(files.map(_.toFile).asJava)
    // -g keeps line numbers; without it method-at-line resolution is impossible.
    val ok = compiler.getTask(null, fm, null, List("-g", "-d", out.toString).asJava, null, units).call()
    fm.close()
    if !ok then cancel("fixture failed to compile")
    out

  private def handle(entryPoints: Vector[MethodRef] = Vector.empty): CallGraphHandle =
    CallGraphService.build(Vector(classesDir.toString), entryPoints) match
      case Right(h) => h
      case Left(e)  => fail(s"call graph build failed: $e")

  private val handlerEntry = MethodRef("Handler", "handle", "(java.lang.String)java.lang.String")
  private val faultSite    = MethodRef("ConfigLoader", "get", "(java.lang.String,java.lang.String)java.lang.String")

  test("a call graph is built over real bytecode"):
    val h = handle(Vector(handlerEntry))
    h.nodeCount should be > 0
    h.edgeCount should be > 0
    h.algorithm shouldBe CgAlgorithm.Cha

  test("compiling with -g leaves line-number information behind"):
    handle(Vector(handlerEntry)).hasDebugInfo shouldBe true

  // A graph with no resolvable entry points is empty, which reads exactly like "this code calls
  // nothing" — so it must fail loudly rather than return a plausible-looking empty graph.
  test("entry points that resolve to nothing are an error, not an empty graph"):
    CallGraphService.build(
      Vector(classesDir.toString),
      Vector(MethodRef("does.not.Exist", "nope", "()V"))
    ) match
      case Left(msg) => msg should include("entry points")
      case Right(_)  => fail("should have refused to build from unresolvable entry points")

  test("direct callees are reported"):
    val h = handle(Vector(handlerEntry))
    val callees = CallGraphService.callees(h, handlerEntry).toOption.get
    callees.map(_.signature) should contain("ConfigService.load(java.lang.String,java.lang.String)java.lang.String")

  test("a path is found from the symptom entry point down to the fault site"):
    val h = handle(Vector(handlerEntry))
    val (found, _) = CallGraphService.paths(h, handlerEntry, faultSite).toOption.get

    found should not be empty
    val path = found.head
    path.head.fqcn shouldBe "Handler"
    path.last.fqcn shouldBe "ConfigLoader"
    // Handler -> ConfigService -> ConfigLoader: the intermediate hop is the part worth having.
    path.map(_.fqcn) should contain("ConfigService")
    path.length should be >= 3

  // Not a failure. Reflection, DI, dynamic proxies and native calls are all invisible here, and
  // an unconnected fault site is a genuine, interesting result.
  test("an unconnected method yields no path rather than an invented one"):
    val h = handle(Vector(handlerEntry))
    val isolated = MethodRef("Isolated", "alone", "()java.lang.String")
    val (found, _) = CallGraphService.paths(h, handlerEntry, isolated).toOption.get
    found shouldBe empty

  test("callers are found transitively, with their depth"):
    val h = handle(Vector(handlerEntry))
    val callers = CallGraphService.callers(h, faultSite, depth = 3).toOption.get
    val byName = callers.map((m, d) => m.fqcn -> d).toMap

    byName.keys should contain("ConfigService")
    byName("ConfigService") shouldBe 1
    byName.get("Handler") shouldBe Some(2)

  test("depth bounds how far callers are followed"):
    val h = handle(Vector(handlerEntry))
    val shallow = CallGraphService.callers(h, faultSite, depth = 1).toOption.get
    shallow.map(_._1.fqcn) should contain("ConfigService")
    shallow.map(_._1.fqcn) should not contain "Handler"

  test("chains to a target start from an entry point and end at it"):
    val h = handle(Vector(handlerEntry))
    val chains = CallGraphService.chainsTo(h, faultSite, maxChains = 5).toOption.get

    chains should not be empty
    chains.foreach { c =>
      c.last.fqcn shouldBe "ConfigLoader"
      c.size should be >= 2
    }
    chains.exists(_.head.fqcn == "Handler") shouldBe true

  test("an unresolvable method is reported rather than silently returning nothing"):
    val h = handle(Vector(handlerEntry))
    CallGraphService.callees(h, MethodRef("Nope", "nope", "()V")).isLeft shouldBe true

  // Overloads are common, and picking the wrong one silently misdirects an entire path.
  test("an overload is disambiguated by parameter count"):
    val h = handle(Vector(handlerEntry))
    val oneArg = MethodRef("ConfigLoader", "get", "(java.lang.String)java.lang.String")
    val twoArg = faultSite

    val a = CallGraphService.resolve(h.view, oneArg).get
    val b = CallGraphService.resolve(h.view, twoArg).get
    a.getParameterCount shouldBe 1
    b.getParameterCount shouldBe 2

  test("JVM descriptors are parsed for parameter count"):
    CallGraphService.descriptorParamCount("()V") shouldBe Some(0)
    CallGraphService.descriptorParamCount("(Ljava/lang/String;)V") shouldBe Some(1)
    CallGraphService.descriptorParamCount("(Ljava/lang/String;I)V") shouldBe Some(2)
    CallGraphService.descriptorParamCount("([Ljava/lang/String;II)V") shouldBe Some(3)
    CallGraphService.descriptorParamCount("(JD)V") shouldBe Some(2)
    CallGraphService.descriptorParamCount("nonsense") shouldBe None

  test("RTA is available as a second algorithm"):
    val h = CallGraphService.build(
      Vector(classesDir.toString), Vector(handlerEntry), CgAlgorithm.Rta
    ).toOption.get
    h.algorithm shouldBe CgAlgorithm.Rta
    h.nodeCount should be > 0

  // Regression: toRef emits SootUp's readable form, not a JVM descriptor. Parsing only the JVM
  // form made overload resolution fall through to "first candidate" and pick the wrong method.
  test("both descriptor dialects are counted correctly"):
    CallGraphService.descriptorParamCount("(java.lang.String,java.lang.String)java.lang.String") shouldBe Some(2)
    CallGraphService.descriptorParamCount("(java.lang.String)java.lang.String") shouldBe Some(1)
    CallGraphService.descriptorParamCount("()java.lang.String") shouldBe Some(0)
    CallGraphService.descriptorParamCount("(int,long)void") shouldBe Some(2)

  test("an overload that cannot be matched is refused rather than guessed"):
    val h = handle(Vector(handlerEntry))
    // Two `get` overloads exist; a descriptor claiming five parameters matches neither.
    CallGraphService.resolve(h.view, MethodRef("ConfigLoader", "get", "(a,b,c,d,e)x")) shouldBe None
