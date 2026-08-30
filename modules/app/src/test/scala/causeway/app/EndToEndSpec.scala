package causeway.app

import causeway.core.*
import causeway.mcp.*
import org.eclipse.jgit.api.Git
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.nio.file.{Files, Path, Paths}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.{Date, TimeZone}
import scala.jdk.CollectionConverters.*

/** The whole stack, in one place: an MCP-shaped call goes in, and a schema-validated,
  * evidence-bearing envelope comes out, having actually touched a real git repository.
  *
  * Every layer the design cares about is exercised here — schema loading, input validation,
  * per-agent access, real JGit work, output validation, evidence minting, and the envelope.
  */
class EndToEndSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  private def schemas: Path =
    Vector(Paths.get("schemas"), Paths.get("..", "..", "schemas"))
      .find(Files.isDirectory(_))
      .getOrElse(sys.error("schemas/ not found"))

  /** A real repository with the ConfigLoader bug, built by JGit. */
  private def buildRepo(): Path =
    val dir = Files.createTempDirectory("causeway-e2e")
    val git = Git.init().setDirectory(dir.toFile).call()

    def commit(msg: String, tick: Int): Unit =
      git.add().addFilepattern(".").call()
      val when = Date.from(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(tick * 3600L))
      val who  = org.eclipse.jgit.lib.PersonIdent("Test Author", "t@example.com", when,
        TimeZone.getTimeZone(ZoneOffset.UTC))
      git.commit().setMessage(msg).setAuthor(who).setCommitter(who).call()

    val filler = (1 to 39).map(i => s"// filler $i").mkString("\n") + "\n"
    Files.createDirectories(dir.resolve("src/main/java/cfg"))
    Files.writeString(dir.resolve("src/main/java/cfg/ConfigLoader.java"),
      filler + "public String get(String s, String k) {\n    Section x = m.get(s);\n    return x.lookup(k);\n}\n")
    commit("Add ConfigLoader", 1)

    Files.writeString(dir.resolve("src/main/java/cfg/ConfigLoader.java"),
      filler + "public String get(String s, String k) {\n    Section x = m.get(s);\n" +
        "    if (x == null) { return null; }\n    return x.lookup(k, D);\n}\n")
    Files.createDirectories(dir.resolve("src/test/java/cfg"))
    Files.writeString(dir.resolve("src/test/java/cfg/ConfigLoaderTest.java"), "class ConfigLoaderTest {}")
    commit("Fix NPE when config section is absent\n\nCloses #412", 2)

    Files.writeString(dir.resolve("pom.xml"), "<project/>")
    commit("Add pom", 3)

    git.close()
    dir

  private def fixture() =
    val dir = buildRepo()
    val ws  = Files.createTempDirectory("causeway-ws")
    val h   = Handles(ws)
    val svc = causeway.vcs.GitService.open(dir).toOption.get
    h.register("repo_aaaaaaaaaaaaaaaa", svc, dir)

    val specs = SchemaCatalog.load(schemas).toOption.get
    val ledger = InMemoryEvidenceLedger()
    val reg = ToolRegistry.build(specs, VcsHandlers.all(h), ledger, runId, clock).toOption.get
    (reg, ledger, svc)

  private def args(pairs: (String, Object)*): java.util.Map[String, Object] = pairs.toMap.asJava

  private def dataOf(r: io.modelcontextprotocol.spec.McpSchema.CallToolResult): JsonNode =
    val text = r.content().asScala.map(_.toString).mkString
    val start = text.indexOf('{')
    Json.parse(text.substring(start, text.lastIndexOf('}') + 1))

  test("the registry serves exactly the handlers that have schemas"):
    val (reg, _, _) = fixture()
    reg.servedNames should contain allOf ("repo_clone", "history_diff", "history_candidates")
    reg.servedNames.foreach(n => reg.spec(n) shouldBe defined)

  // The full path: MCP call -> validation -> JGit -> output validation -> evidence -> envelope.
  test("history_diff runs end to end against a real repository"):
    val (reg, ledger, svc) = fixture()
    val fixSha = svc.listCommits(maxCount = 10).toOption.get._1(1).sha

    val result = ToolBridge.call(reg, "history_diff", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa",
      "sha" -> fixSha,
      ToolBridge.AgentField -> "path-tracer"
    ))

    result.isError shouldBe false
    val body = dataOf(result)
    body.get("ok").booleanValue() shouldBe true
    body.get("evidenceId").stringValue() should startWith("ev_")

    val data = body.get("data")
    // The fix commit touches the source AND adds a regression test — two files, which is
    // exactly the shape the structural recall net looks for.
    data.get("stats").get("filesTouched").intValue() shouldBe 2
    val touched = data.get("files").values().asScala.map(_.get("file").stringValue()).toVector
    touched.exists(_.endsWith("ConfigLoader.java")) shouldBe true
    touched.exists(_.endsWith("ConfigLoaderTest.java")) shouldBe true

    // The evidence really was recorded — a claim citing it would now validate.
    ledger.contains(EvidenceId.unsafe(body.get("evidenceId").stringValue())) shouldBe true

  test("old and new ranges survive the round trip with their side tags intact"):
    val (reg, _, svc) = fixture()
    val fixSha = svc.listCommits(maxCount = 10).toOption.get._1(1).sha

    val data = dataOf(ToolBridge.call(reg, "history_diff", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa", "sha" -> fixSha,
      ToolBridge.AgentField -> "path-tracer"))).get("data")

    val hunk = data.get("files").get(0)
    // Whichever side is present must be labelled, so a consumer cannot query the parent
    // revision using fix-side line numbers.
    if hunk.has("oldRange") then hunk.get("oldRange").get("side").stringValue() shouldBe "old"
    if hunk.has("newRange") then hunk.get("newRange").get("side").stringValue() shouldBe "new"
    (hunk.has("oldRange") || hunk.has("newRange")) shouldBe true

  test("the recall nets run end to end and report per-net counts"):
    val (reg, _, _) = fixture()
    val data = dataOf(ToolBridge.call(reg, "history_candidates", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa",
      "nets" -> List("LEXICAL", "STRUCTURAL").asJava,
      "controlSampleSize" -> Integer.valueOf(0)
    ))).get("data")

    data.get("scanned").intValue() should be >= 3
    val shas = data.get("candidates").values().asScala.map(_.get("sha").stringValue()).toVector
    shas should not be empty
    data.get("perNetCounts").get("LEXICAL").intValue() should be >= 1

  test("issue references are surfaced through the tool, not just the library"):
    val (reg, _, svc) = fixture()
    val fixSha = svc.listCommits(maxCount = 10).toOption.get._1(1).sha

    val data = dataOf(ToolBridge.call(reg, "history_commit_meta", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa", "sha" -> fixSha,
      ToolBridge.AgentField -> "symptom-characterizer"))).get("data")

    data.get("issueRefs").values().asScala.map(_.stringValue()).toVector should contain("#412")

  test("an agent calling a tool it is not granted is refused at the boundary"):
    val (reg, ledger, svc) = fixture()
    val fixSha = svc.listCommits(maxCount = 10).toOption.get._1(1).sha
    val before = ledger.size

    val result = ToolBridge.call(reg, "history_diff", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa", "sha" -> fixSha,
      ToolBridge.AgentField -> "symptom-characterizer"))

    result.isError shouldBe true
    ledger.size shouldBe before   // a refused call leaves no evidence behind

  test("an unregistered repo handle yields Unknown, not a crash"):
    val (reg, _, _) = fixture()
    val result = ToolBridge.call(reg, "history_commit_meta", args(
      "repoHandle" -> "repo_ffffffffffffffff",
      "sha" -> ("a" * 40),
      ToolBridge.AgentField -> "path-tracer"))

    result.isError shouldBe false        // Unknown is a successful, citable outcome
    val body = dataOf(result)
    body.has("unknown") shouldBe true
    body.get("unknown").get("reason").stringValue() shouldBe "NotApplicable"

  test("a bad sha fails input validation before any git work happens"):
    val (reg, _, _) = fixture()
    val result = ToolBridge.call(reg, "history_diff", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa", "sha" -> "abbrev",
      ToolBridge.AgentField -> "path-tracer"))

    result.isError shouldBe true
    result.content().asScala.mkString should include("InvalidInput")

  test("repeating a call produces the same evidence id"):
    val (reg, _, svc) = fixture()
    val sha = svc.listCommits(maxCount = 10).toOption.get._1.head.sha
    def idOf() = dataOf(ToolBridge.call(reg, "history_commit_meta", args(
      "repoHandle" -> "repo_aaaaaaaaaaaaaaaa", "sha" -> sha,
      ToolBridge.AgentField -> "path-tracer"))).get("evidenceId").stringValue()

    idOf() shouldBe idOf()
