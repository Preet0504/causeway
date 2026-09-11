package causeway.mini

import munit.FunSuite
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.nio.file.Files
import java.time.{Instant, ZoneOffset}

/** Regression test for a correctness bug: the original window filter sorted
  * commits newest first and stopped as soon as it saw one commit older than
  * the requested window, assuming every commit after it (further back in the
  * walk) must also be older. That assumption is false for real Git
  * histories, a commit's timestamp is whatever its committer's clock said,
  * and a parent can legitimately have a later timestamp than its own child
  * (clock skew, a backdated commit, a rebase). This test builds exactly that
  * shape by hand and checks the grandparent commit, which has a genuinely
  * recent timestamp despite sitting "behind" a deliberately backdated
  * commit in the walk, is still found.
  */
class InspectRepoWindowSpec extends FunSuite:

  private def personAt(instant: Instant): PersonIdent =
    new PersonIdent("Test", "test@example.com", instant, ZoneOffset.UTC)

  private def deleteRecursively(f: File): Unit =
    if f.isDirectory then f.listFiles().foreach(deleteRecursively)
    f.delete()

  test("finds a commit with a recent timestamp even when a deliberately backdated commit sits between it and HEAD") {
    val tempDir = Files.createTempDirectory("inspectrepo-window-spec").toFile
    try
      val git = Git.init().setDirectory(tempDir).call()

      val cutoff = Instant.parse("2026-06-01T00:00:00Z")

      // The grandparent: created first in the DAG, but its clock was correct,
      // so its timestamp is genuinely recent and belongs in the window.
      val grandparentTime = Instant.parse("2026-07-01T00:00:00Z")
      // The middle commit: deliberately backdated to simulate clock skew,
      // its own timestamp is genuinely outside the window.
      val skewedTime = Instant.parse("2020-01-01T00:00:00Z")
      // HEAD: a normal, recent commit.
      val headTime = Instant.parse("2026-08-01T00:00:00Z")

      def commit(fileName: String, at: Instant): String =
        Files.writeString(new File(tempDir, fileName).toPath, fileName)
        git.add().addFilepattern(fileName).call()
        val ident = personAt(at)
        git.commit().setMessage(s"commit at $at").setAuthor(ident).setCommitter(ident).call().getName

      val grandparentSha = commit("a.txt", grandparentTime)
      val skewedSha = commit("b.txt", skewedTime)
      val headSha = commit("c.txt", headTime)

      val headObjectId = git.getRepository.resolve("HEAD")
      val found = InspectRepo.findCommitsInWindow(git.getRepository, headObjectId, cutoff.getEpochSecond)
      val foundShas = found.map(_.getName).toSet

      assert(
        foundShas.contains(grandparentSha),
        "the grandparent commit has a genuinely recent timestamp and must be found, even though a backdated commit sits between it and HEAD in the walk"
      )
      assert(foundShas.contains(headSha), "HEAD's own commit must be found")
      assert(
        !foundShas.contains(skewedSha),
        "the deliberately backdated commit is genuinely outside the window and must be excluded"
      )

      git.close()
    finally deleteRecursively(tempDir)
  }
