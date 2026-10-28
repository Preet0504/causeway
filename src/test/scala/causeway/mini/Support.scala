package causeway.mini

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand.FastForwardMode
import org.eclipse.jgit.lib.PersonIdent
import java.util.Date

/** GitHub answered from a function; anything it does not match is a 404, like the real API. */
final class FakeGitHub(routes: PartialFunction[(String, Map[String, String]), ujson.Value]) extends GitHub:
  val calls = scala.collection.mutable.ListBuffer.empty[String]
  def get(path: String, query: Map[String, String] = Map.empty): ujson.Value =
    calls += path
    routes.applyOrElse((path, query), (_: (String, Map[String, String])) => throw GitHubError(404, "Not Found"))

/** A throwaway git repository whose commit dates we control. */
final class TestRepo(val dir: Path):
  val git: Git = Git.init().setDirectory(dir.toFile).setInitialBranch("main").call()

  def commit(files: Map[String, String], message: String, epochSecond: Long): String =
    files.foreach { (name, content) =>
      val f = dir.resolve(name)
      Files.createDirectories(f.getParent)
      Files.writeString(f, content)
      git.add().addFilepattern(name).call()
    }
    val who = new PersonIdent("Tester", "t@example.com", new Date(epochSecond * 1000), java.util.TimeZone.getTimeZone("UTC"))
    git.commit().setMessage(message).setAuthor(who).setCommitter(who).call().getName

  def branch(name: String): Unit = git.checkout().setCreateBranch(true).setName(name).call()
  def checkout(name: String): Unit = git.checkout().setName(name).call()

  def mergeNoFf(name: String, epochSecond: Long): String =
    val who = new PersonIdent("Tester", "t@example.com", new Date(epochSecond * 1000), java.util.TimeZone.getTimeZone("UTC"))
    git.merge().include(git.getRepository.resolve(name)).setFastForward(FastForwardMode.NO_FF)
      .setCommit(true).setMessage(s"Merge $name").call()
    git.getRepository.resolve("HEAD").getName

object TestRepo:
  def create(): TestRepo = TestRepo(Files.createTempDirectory("causeway-test-repo"))

/** Captures what a Cli run prints. */
final class CliHarness(github: GitHub, stdin: String = "", now: Instant = Instant.parse("2026-01-01T00:00:00Z")):
  val data: Path = Files.createTempDirectory("causeway-test-data")
  private val out = new ByteArrayOutputStream()
  private val err = new ByteArrayOutputStream()

  def run(argv: String*): (Int, String, String) = runWith(stdin, argv*)

  def runWith(input: String, argv: String*): (Int, String, String) =
    out.reset(); err.reset()
    val env = Env(data, data.resolve("causeway.db"), () => github,
      new ByteArrayInputStream(input.getBytes(UTF_8)),
      new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8), () => now)
    val code = Cli(env).run(argv)
    (code, out.toString(UTF_8), err.toString(UTF_8))

  def json(argv: String*): ujson.Value =
    val (code, o, e) = run(argv*)
    assert(code == 0, s"exit $code, stderr: $e")
    ujson.read(o)
