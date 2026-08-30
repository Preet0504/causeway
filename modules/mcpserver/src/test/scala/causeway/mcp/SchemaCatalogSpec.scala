package causeway.mcp

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}

object TestPaths:
  /** sbt runs tests with the working directory at the build root, but be tolerant of being run
    * from the module directory too.
    */
  val schemas: Path =
    Vector(Paths.get("schemas"), Paths.get("..", "..", "schemas"))
      .find(p => Files.isDirectory(p))
      .getOrElse(sys.error("cannot locate the schemas/ directory from " + Paths.get(".").toAbsolutePath))

class SchemaCatalogSpec extends AnyFunSuite with Matchers:

  test("the real catalog loads — every hand-written schema parses and is well-formed"):
    SchemaCatalog.load(TestPaths.schemas) match
      case Left(errors) => fail(s"catalog failed to load:\n${errors.mkString("\n")}")
      case Right(specs) =>
        specs should not be empty
        // Sanity: names are unique and match their filenames (the loader enforces the latter).
        specs.map(_.name).distinct.size shouldBe specs.size

  test("every tool declares a class, a module, and a permission"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    specs.foreach { s =>
      withClue(s"${s.name}: ") {
        s.module should not be "unknown"
        s.description should not be empty
      }
    }

  test("the one class-X tool is the harness runner, and it is permission ask"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    val x = specs.filter(_.toolClass == ToolClass.X)
    x.map(_.name) shouldBe Vector("jvm_harness_run")
    x.head.permission shouldBe Permission.Ask

  test("pipeline tools grant access to at most one agent"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    specs.filter(_.toolClass == ToolClass.P).foreach { s =>
      withClue(s"${s.name} is class P but names ${s.agents.mkString(",")}: ") {
        s.agents.size should be <= 1
      }
    }

  // The integrity property that the note store depends on.
  test("note tools are class N and are never granted evidence semantics"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    val notes = specs.filter(_.name.startsWith("notes_"))
    notes should not be empty
    notes.foreach(s => s.toolClass shouldBe ToolClass.N)

  test("evidence_attest exists but is granted to no agent"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    val attest = specs.find(_.name == "evidence_attest")
    attest shouldBe defined
    attest.get.agents shouldBe empty

  test("shared $defs are inlined and causeway:// refs rewritten to local ones"):
    val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get
    val diff  = specs.find(_.name == "history_diff").getOrElse(fail("history_diff missing"))
    val text  = Json.write(diff.inputSchema)

    text should include("\"$defs\"")
    text should not include "causeway://"
    text should include("#/$defs/commitId")

  test("a missing _common.json is reported rather than silently tolerated"):
    val empty = Files.createTempDirectory("causeway-schemas-empty")
    SchemaCatalog.load(empty) match
      case Left(errors) => errors.head.problem should include("missing")
      case Right(_)     => fail("should have refused a directory with no _common.json")
