package causeway.vcs

import org.eclipse.jgit.api.Git

import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import java.util.{Date, TimeZone}

/** Builds throwaway git repositories with JGit so the vcs tests need no network and no
  * checked-in fixture repo.
  *
  * Author identity and timestamps are fixed so commit SHAs are reproducible across runs — which
  * matters because evidence ids are derived from content, and a test that produced a different
  * SHA each run could not assert anything about them.
  */
final class TestRepo(val dir: Path):
  private val git = Git.init().setDirectory(dir.toFile).call()
  private var tick = 0

  def write(path: String, content: String): Unit =
    val f = dir.resolve(path)
    Files.createDirectories(f.getParent)
    Files.writeString(f, content)

  def delete(path: String): Unit =
    Files.deleteIfExists(dir.resolve(path))

  def move(from: String, to: String): Unit =
    val target = dir.resolve(to)
    Files.createDirectories(target.getParent)
    Files.move(dir.resolve(from), target)

  /** Stage everything (including deletions) and commit. Returns the new SHA. */
  def commit(message: String): String =
    tick += 1
    git.add().addFilepattern(".").call()
    git.add().addFilepattern(".").setUpdate(true).call()
    val when = Date.from(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(tick * 3600L))
    val tz   = TimeZone.getTimeZone(ZoneOffset.UTC)
    git
      .commit()
      .setMessage(message)
      .setAuthor(org.eclipse.jgit.lib.PersonIdent("Test Author", "test@example.com", when, tz))
      .setCommitter(org.eclipse.jgit.lib.PersonIdent("Test Author", "test@example.com", when, tz))
      .call()
      .getName

  def service: GitService = GitService(git.getRepository)

  def close(): Unit = git.close()

object TestRepo:
  def create(): TestRepo =
    TestRepo(Files.createTempDirectory("causeway-testrepo"))

  private val filler = (1 to 39).map(i => s"// filler line $i").mkString("\n") + "\n"

  private val buggy =
    filler +
      """public String get(String section, String key) {
        |    Section s = sections.get(section);
        |    return s.lookup(key);
        |}
        |""".stripMargin

  /** The worked example from the design: an NPE whose fix REPLACES the faulty line.
    *
    * Returns (repo, parentSha, fixSha). Old line 42 becomes new lines 42-45, which is the shape
    * the Old/New line-range split exists to keep straight — and the shape SZZ can work with,
    * because there is a deleted line to blame.
    */
  def configLoaderBug(): (TestRepo, String, String) =
    val fixed =
      filler +
        """public String get(String section, String key) {
          |    Section s = sections.get(section);
          |    if (s == null) {
          |        return defaults.getOrDefault(key, null);
          |    }
          |    return s.lookup(key, FALLBACK);
          |}
          |""".stripMargin

    build(fixed, "Fix NPE when config section is absent\n\nCloses #412")

  /** The same bug fixed by ADDING a guard and touching nothing else.
    *
    * The original `return s.lookup(key);` survives verbatim, so the diff is a pure insertion
    * with zero deleted lines. This is assumption A6 made concrete: SZZ has nothing to blame,
    * because blame-based fault location works backwards from lines the fix removed. A large
    * share of real fixes look like this, which is part of why blame-based SZZ misses so many.
    */
  def guardOnlyFix(): (TestRepo, String, String) =
    val fixed =
      filler +
        """public String get(String section, String key) {
          |    Section s = sections.get(section);
          |    if (s == null) {
          |        return defaults.getOrDefault(key, null);
          |    }
          |    return s.lookup(key);
          |}
          |""".stripMargin

    build(fixed, "Guard against absent section")

  private def build(fixedContent: String, message: String): (TestRepo, String, String) =
    val r = TestRepo.create()
    r.write("src/main/java/cfg/ConfigLoader.java", buggy)
    val parent = r.commit("Add ConfigLoader")
    r.write("src/main/java/cfg/ConfigLoader.java", fixedContent)
    val fix = r.commit(message)
    (r, parent, fix)
