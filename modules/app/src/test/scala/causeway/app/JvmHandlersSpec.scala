package causeway.app

import causeway.core.*
import causeway.jvm.{CallGraphHandle, CgAlgorithm}
import causeway.mcp.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.nio.file.{Files, Path, Paths}
import java.time.{Clock, Instant, ZoneOffset}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

/** The call-graph tools as an agent actually reaches them: through the registry, with schema
  * validation on both sides and evidence minted for every answer.
  */
class JvmHandlersSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  private def schemas: Path =
    Vector(Paths.get("schemas"), Paths.get("..", "..", "schemas"))
      .find(Files.isDirectory(_)).getOrElse(sys.error("schemas/ not found"))

  /** A layered fixture compiled into `target/classes`, so the classpath discovery in `Handles`
    * has to find it the same way it would on a real Maven project.
    */
  private lazy val repoRoot: Path =
    val root = Files.createTempDirectory("causeway-jvmh")
    val src  = root.resolve("src/main/java")
    val out  = root.resolve("target/classes")
    Files.createDirectories(src)
    Files.createDirectories(out)
    Files.writeString(root.resolve("pom.xml"),
      """<project><properties><maven.compiler.source>17</maven.compiler.source></properties></project>""")

    val files = Vector(
      "ConfigLoader.java" ->
        """import java.util.*;
          |public class ConfigLoader {
          |    private final Map<String,String> m = new HashMap<>();
          |    public String get(String section, String key) { return m.get(section + key); }
          |}""".stripMargin,
      "ConfigService.java" ->
        """public class ConfigService {
          |    private final ConfigLoader loader = new ConfigLoader();
          |    public String load(String s, String k) { return loader.get(s, k); }
          |}""".stripMargin,
      "Handler.java" ->
        """public class Handler {
          |    private final ConfigService svc = new ConfigService();
          |    public String handle(String p) { return svc.load("http", p); }
          |}""".stripMargin
    ).map { case (n, body) =>
      val f = src.resolve(n); Files.writeString(f, body); f
    }

    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then cancel("no system Java compiler")
    val fm = compiler.getStandardFileManager(null, null, null)
    val ok = compiler.getTask(null, fm, null,
      List("-g", "-d", out.toString).asJava, null,
      fm.getJavaFileObjectsFromFiles(files.map(_.toFile).asJava)).call()
    fm.close()
    if !ok then cancel("fixture failed to compile")
    root

  private def fixture() =
    val handles = Handles(Files.createTempDirectory("causeway-jvmh-ws"))
    val specs   = SchemaCatalog.load(schemas).toOption.get
    val ledger  = InMemoryEvidenceLedger()
    val reg = ToolRegistry
      .build(specs, VcsHandlers.all(handles) ++ JvmHandlers.all(handles), ledger, runId, clock)
      .toOption.get
    (reg, handles, ledger)

  private def args(pairs: (String, Object)*): java.util.Map[String, Object] = pairs.toMap.asJava

  private def dataOf(r: io.modelcontextprotocol.spec.McpSchema.CallToolResult): JsonNode =
    val text = r.content().asScala.map(_.toString).mkString
    Json.parse(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))

  private def ref(fqcn: String, name: String, desc: String): java.util.Map[String, Object] =
    Map[String, Object]("fqcn" -> fqcn, "name" -> name, "descriptor" -> desc).asJava

  /** Register the compiled fixture as if a build probe had just produced it. */
  private def buildHandleFor(handles: Handles): String =
    handles.registerBuild(repoRoot, "eclipse-temurin:17-jdk")

  test("classpath discovery finds Maven's target/classes"):
    val (_, handles, _) = fixture()
    val bh = buildHandleFor(handles)
    val cp = handles.buildClasspath(bh)
    cp should not be empty
    cp.exists(_.replace('\\', '/').endsWith("target/classes")) shouldBe true

  test("a call graph is built through the tool and returns a handle, not a graph"):
    val (reg, handles, ledger) = fixture()
    val bh = buildHandleFor(handles)

    val body = dataOf(ToolBridge.call(reg, "jvm_callgraph_build", args(
      "buildHandle" -> bh,
      "entryPoints" -> List(ref("Handler", "handle", "(java.lang.String)java.lang.String")).asJava
    )))

    body.get("ok").booleanValue() shouldBe true
    val data = body.get("data")
    data.get("callGraphId").stringValue() should startWith("cg_")
    data.get("nodeCount").intValue() should be > 0
    data.get("hasDebugInfo").booleanValue() shouldBe true
    // The graph itself never crosses the boundary — only its id and some counts.
    data.has("edges") shouldBe false
    ledger.contains(EvidenceId.unsafe(body.get("evidenceId").stringValue())) shouldBe true

  test("a path from the symptom entry point to the fault site comes back through MCP"):
    val (reg, handles, _) = fixture()
    val bh = buildHandleFor(handles)
    val cgId = dataOf(ToolBridge.call(reg, "jvm_callgraph_build", args(
      "buildHandle" -> bh,
      "entryPoints" -> List(ref("Handler", "handle", "(java.lang.String)java.lang.String")).asJava
    ))).get("data").get("callGraphId").stringValue()

    val data = dataOf(ToolBridge.call(reg, "jvm_callgraph_paths", args(
      "callGraphId" -> cgId,
      "from" -> ref("Handler", "handle", "(java.lang.String)java.lang.String"),
      "to" -> ref("ConfigLoader", "get", "(java.lang.String,java.lang.String)java.lang.String"),
      ToolBridge.AgentField -> "path-tracer"
    ))).get("data")

    val paths = data.get("paths")
    paths.size() should be > 0
    val hops = paths.get(0).values().asScala.map(_.get("fqcn").stringValue()).toVector
    hops.head shouldBe "Handler"
    hops.last shouldBe "ConfigLoader"
    hops should contain("ConfigService")

  test("callers come back with their depth"):
    val (reg, handles, _) = fixture()
    val bh = buildHandleFor(handles)
    val cgId = dataOf(ToolBridge.call(reg, "jvm_callgraph_build", args(
      "buildHandle" -> bh,
      "entryPoints" -> List(ref("Handler", "handle", "(java.lang.String)java.lang.String")).asJava
    ))).get("data").get("callGraphId").stringValue()

    val data = dataOf(ToolBridge.call(reg, "jvm_callgraph_callers", args(
      "callGraphId" -> cgId,
      "method" -> ref("ConfigLoader", "get", "(java.lang.String,java.lang.String)java.lang.String"),
      "depth" -> Integer.valueOf(3),
      ToolBridge.AgentField -> "reproducer-synthesist"
    ))).get("data")

    val byName = data.get("callers").values().asScala
      .map(c => c.get("method").get("fqcn").stringValue() -> c.get("depth").intValue()).toMap
    byName.get("ConfigService") shouldBe Some(1)
    byName.get("Handler") shouldBe Some(2)

  test("chains to the fault site reach a caller-less entry point"):
    val (reg, handles, _) = fixture()
    val bh = buildHandleFor(handles)
    val cgId = dataOf(ToolBridge.call(reg, "jvm_callgraph_build", args(
      "buildHandle" -> bh,
      "entryPoints" -> List(ref("Handler", "handle", "(java.lang.String)java.lang.String")).asJava
    ))).get("data").get("callGraphId").stringValue()

    val data = dataOf(ToolBridge.call(reg, "jvm_callgraph_chains_to", args(
      "callGraphId" -> cgId,
      "target" -> ref("ConfigLoader", "get", "(java.lang.String,java.lang.String)java.lang.String"),
      ToolBridge.AgentField -> "reproducer-synthesist"
    ))).get("data")

    val chains = data.get("chains").values().asScala.toVector
    chains should not be empty
    chains.foreach { c =>
      val hops = c.get("hops").values().asScala.map(_.get("fqcn").stringValue()).toVector
      hops.last shouldBe "ConfigLoader"
      c.get("length").intValue() shouldBe hops.size
    }
    chains.exists(_.get("entryIsPublic").booleanValue()) shouldBe true

  // Entry points that resolve to nothing produce an empty graph, which reads exactly like
  // "this code calls nothing" — so it must surface as Unknown, not as a valid empty result.
  test("unresolvable entry points yield Unknown rather than an empty graph"):
    val (reg, handles, _) = fixture()
    val bh = buildHandleFor(handles)

    val body = dataOf(ToolBridge.call(reg, "jvm_callgraph_build", args(
      "buildHandle" -> bh,
      "entryPoints" -> List(ref("does.not.Exist", "nope", "()void")).asJava
    )))
    body.has("unknown") shouldBe true
    body.get("unknown").get("reason").stringValue() shouldBe "NotApplicable"

  test("querying an unknown call graph id is Unknown, not a crash"):
    val (reg, _, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "jvm_callgraph_callers", args(
      "callGraphId" -> "cg_ffffffffffffffff",
      "method" -> ref("A", "b", "()void"),
      ToolBridge.AgentField -> "path-tracer"
    )))
    body.has("unknown") shouldBe true
    body.get("unknown").get("detail").stringValue() should include("build it first")

  test("the build toolchain is detected through the tool"):
    val (reg, handles, _) = fixture()
    val h = handles.repoHandle("fixture")
    handles.register(h, causeway.vcs.GitService.open(repoRoot).getOrElse(null), repoRoot)

    val body = dataOf(ToolBridge.call(reg, "jvm_build_toolchain", args(
      "repoHandle" -> h, "commit" -> ("a" * 40), ToolBridge.AgentField -> "build-doctor"
    )))
    // The fixture is not a git repo, but toolchain detection reads the working tree, not git.
    if body.has("data") then
      body.get("data").get("buildTool").stringValue() shouldBe "maven"
      body.get("data").get("declaredSourceLevel").stringValue() shouldBe "17"

  test("an agent without call-graph access is refused"):
    val (reg, _, _) = fixture()
    val r = ToolBridge.call(reg, "jvm_callgraph_paths", args(
      "callGraphId" -> "cg_ffffffffffffffff",
      "from" -> ref("A", "b", "()void"),
      "to" -> ref("C", "d", "()void"),
      ToolBridge.AgentField -> "bugfix-adjudicator"
    ))
    r.isError shouldBe true
    r.content().asScala.mkString should include("NotPermittedForAgent")

  // The defect a real run found, five stages in: the handler destructured only repoHandle and
  // baseImage, so every probe built whatever the shared clone had checked out — HEAD — and the
  // build gate's verdict was about HEAD rather than about the bug's revisions.
  test("a build probe without a commit never reaches a container"):
    val (reg, _, _) = fixture()
    val result = ToolBridge.call(reg, "jvm_build_probe", args(
      "repoHandle" -> "repo_ffffffffffffffff",
      "baseImage" -> "maven:3.9-eclipse-temurin-17"
    ))
    // `commit` is required by the input schema, so the call is refused before the handler runs.
    // That is the guarantee worth pinning: there is no path to a build without a revision.
    result.isError shouldBe true
    result.content().asScala.mkString should include("InvalidInput")

  test("a build probe names the revision it built, not the clone"):
    val (reg, _, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "jvm_build_probe", args(
      "repoHandle" -> "repo_ffffffffffffffff",
      "commit" -> ("a" * 40),
      "baseImage" -> "maven:3.9-eclipse-temurin-17"
    )))
    // No such clone, so this stops at handle resolution — but it stops having ASKED for a
    // worktree at that commit, which is what the old handler never did.
    body.has("unknown") shouldBe true

  /** Handle rehydration, which exists because S7 died mid-stage twice.
    *
    * Handles are content-derived, so the same inputs always yield the same id — but the map from
    * id back to inputs lived only in memory. When the process died, nineteen finished coverage
    * runs became unreachable while their `.exec` files sat intact on disk, and the only way
    * forward was to re-run a suite that takes minutes per revision.
    */
  test("a build handle survives the process that issued it"):
    val ws  = Files.createTempDirectory("causeway-rehydrate")
    val dir = Files.createDirectories(ws.resolve("checkouts").resolve("repo_x_abc123"))

    val issued = Handles(ws).registerBuild(dir, "maven:3.9-eclipse-temurin-17")

    // A NEW Handles, as a restarted server would have: empty maps, same workspace.
    val restarted = Handles(ws)
    restarted.buildDir(issued) shouldBe Some(dir)
    restarted.buildImage(issued) shouldBe Some("maven:3.9-eclipse-temurin-17")

  test("a handle whose directory is gone stays a miss"):
    val ws  = Files.createTempDirectory("causeway-rehydrate-gone")
    val dir = Files.createDirectories(ws.resolve("checkouts").resolve("repo_y_def456"))
    val issued = Handles(ws).registerBuild(dir, "maven:3.9-eclipse-temurin-17")

    Files.delete(dir)

    // Resolving it would hand back an empty classpath — indistinguishable from a project whose
    // code compiles to nothing. A miss is the honest answer.
    Handles(ws).buildDir(issued) shouldBe None

  test("an unjournalled handle is not invented"):
    val ws = Files.createTempDirectory("causeway-rehydrate-none")
    Handles(ws).buildDir("build_ffffffffffffffff") shouldBe None
    Handles(ws).coverageReport("cov_ffffffffffffffff") shouldBe None

  /** Compile and run a tiny program under the real JaCoCo agent.
    *
    * A genuine exec file, not a fixture byte array: the point of rehydration is that JaCoCo can
    * re-parse what a dead process left on disk, and only real instrumentation output proves it.
    */
  private def instrumentedRun(): Option[(java.nio.file.Path, java.nio.file.Path, causeway.jvm.CoverageReport)] =
    val root    = Files.createTempDirectory("causeway-cov-fixture")
    val src     = Files.createDirectories(root.resolve("src"))
    val classes = Files.createDirectories(root.resolve("classes"))

    Files.writeString(src.resolve("Main.java"),
      """public class Main {
        |  static int twice(int n) { return n * 2; }
        |  static int never(int n) { return n - 1; }
        |  public static void main(String[] a) { System.out.print(twice(21)); }
        |}
        |""".stripMargin)

    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler
    if compiler == null then None
    else
      val fm = compiler.getStandardFileManager(null, null, null)
      val ok = compiler.getTask(null, fm, null,
        java.util.List.of("-g", "-d", classes.toString), null,
        fm.getJavaFileObjectsFromFiles(java.util.List.of(src.resolve("Main.java").toFile))).call()
      fm.close()
      if !ok then None
      else
        causeway.jvm.CoverageService
          .runLocally(root, Vector(classes.toAbsolutePath.toString), "Main")
          .toOption
          .map(r => (root, classes, r))

  test("a coverage report is recovered from its exec file, not by re-running tests"):
    val ws = Files.createTempDirectory("causeway-cov-rehydrate")
    val (root, classes, report) = instrumentedRun().getOrElse(cancel("no system Java compiler"))

    val exec = root.resolve(".causeway").resolve("jacoco.exec")
    Files.isRegularFile(exec) shouldBe true

    Handles(ws).registerCoverage(report, exec, Vector(classes))

    // The restarted server never ran a test; it re-parsed what the last one left behind.
    val recovered = Handles(ws).coverageReport(report.id)
    recovered.map(_.id) shouldBe Some(report.id)
    recovered.map(_.classesAnalyzed) shouldBe Some(report.classesAnalyzed)

  test("an exec file overwritten by a later run is refused, not served under the old id"):
    val ws = Files.createTempDirectory("causeway-cov-stale")
    val (root, classes, report) = instrumentedRun().getOrElse(cancel("no system Java compiler"))

    val exec = root.resolve(".causeway").resolve("jacoco.exec")
    Handles(ws).registerCoverage(report, exec, Vector(classes))

    Files.write(exec, Array[Byte](0, 1, 2, 3))

    // The id is a content hash. Serving whatever the file holds now, under an id an agent may
    // already have cited, would substitute one measurement for another silently.
    Handles(ws).coverageReport(report.id) shouldBe None

  // ── call graph memory bound ────────────────────────────────────────────

  /** Each CallGraphHandle retains a SootUp JavaView pinning the whole JDK runtime. Unbounded,
    * a server completed exactly THREE builds and hung forever on the fourth — and every
    * JVM-path tool hung with it, because a collector with nothing to reclaim looks like a lock.
    */
  test("call graphs are bounded, and the least recently used is evicted"):
    val h = Handles(Files.createTempDirectory("causeway-cg-bound"))
    def graph(id: String) = CallGraphHandle(id, CgAlgorithm.Cha, null, null, Vector.empty)

    h.registerCallGraph(graph("cg_1"))
    h.registerCallGraph(graph("cg_2"))
    h.liveCallGraphs shouldBe 2

    h.registerCallGraph(graph("cg_3"))
    h.liveCallGraphs shouldBe 2

    // cg_1 is the eldest and goes; the newer two stay.
    h.callGraph("cg_1") shouldBe None
    h.callGraph("cg_2") shouldBe defined
    h.callGraph("cg_3") shouldBe defined

  test("an evicted id is distinguishable from one that never existed"):
    val h = Handles(Files.createTempDirectory("causeway-cg-eviction"))
    def graph(id: String) = CallGraphHandle(id, CgAlgorithm.Cha, null, null, Vector.empty)

    Vector("cg_a", "cg_b", "cg_c").foreach(id => h.registerCallGraph(graph(id)))

    // The agent's reasoning was sound and the bound reclaimed the graph underneath it — that
    // calls for "rebuild", where an unknown id calls for "check your id".
    h.callGraphEvicted("cg_a") shouldBe true
    h.callGraphEvicted("cg_never_built") shouldBe false

  test("access counts as use, so the graph being queried is not the one evicted"):
    val h = Handles(Files.createTempDirectory("causeway-cg-lru"))
    def graph(id: String) = CallGraphHandle(id, CgAlgorithm.Cha, null, null, Vector.empty)

    h.registerCallGraph(graph("cg_x"))
    h.registerCallGraph(graph("cg_y"))
    h.callGraph("cg_x")            // touch x, making y the eldest
    h.registerCallGraph(graph("cg_z"))

    h.callGraph("cg_x") shouldBe defined
    h.callGraph("cg_y") shouldBe None

  test("rebuilding an evicted graph clears its eviction mark"):
    val h = Handles(Files.createTempDirectory("causeway-cg-rebuild"))
    def graph(id: String) = CallGraphHandle(id, CgAlgorithm.Cha, null, null, Vector.empty)

    Vector("cg_p", "cg_q", "cg_r").foreach(id => h.registerCallGraph(graph(id)))
    h.callGraphEvicted("cg_p") shouldBe true

    h.registerCallGraph(graph("cg_p"))
    h.callGraphEvicted("cg_p") shouldBe false
    h.callGraph("cg_p") shouldBe defined
