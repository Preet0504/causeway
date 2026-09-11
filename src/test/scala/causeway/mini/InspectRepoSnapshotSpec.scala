package causeway.mini

import munit.FunSuite
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.nio.file.Files
import java.time.{Instant, ZoneOffset}

/** Regression test for a correctness bug: a reused local clone with no
  * fetch silently serves whatever the remote looked like the last time this
  * tool ran against it, not what it actually looks like now, even though
  * the repo URL and all other arguments are identical. This builds a local
  * repo to stand in for "the remote" (no real network involved, so this
  * runs the same everywhere and doesn't depend on GitHub being reachable),
  * clones it the same way InspectRepo does, then pushes a new commit to
  * that stand-in remote and confirms resolveRemoteHead picks it up after a
  * fetch, proving the pinned SHA tracks the remote rather than staying
  * frozen at whatever the original clone saw.
  */
class InspectRepoSnapshotSpec extends FunSuite:

  private def personAt(instant: Instant): PersonIdent =
    new PersonIdent("Test", "test@example.com", instant, ZoneOffset.UTC)

  private def deleteRecursively(f: File): Unit =
    if f.isDirectory then f.listFiles().foreach(deleteRecursively)
    f.delete()

  private def commitFile(git: Git, dir: File, fileName: String, at: Instant): String =
    Files.writeString(new File(dir, fileName).toPath, fileName)
    git.add().addFilepattern(fileName).call()
    val ident = personAt(at)
    git.commit().setMessage(s"commit at $at").setAuthor(ident).setCommitter(ident).call().getName

  test("resolveRemoteHead tracks new commits pushed to the remote after a fetch, not the SHA seen at the original clone") {
    val remoteDir = Files.createTempDirectory("inspectrepo-snapshot-remote").toFile
    val localDir = Files.createTempDirectory("inspectrepo-snapshot-local").toFile
    try
      // Stand-in "remote": a plain local repo InspectRepo's clone can point at.
      val remoteGit = Git.init().setDirectory(remoteDir).call()
      val firstSha = commitFile(remoteGit, remoteDir, "a.txt", Instant.parse("2026-07-01T00:00:00Z"))

      // Clone it exactly the way InspectRepo does.
      val localGit = Git.cloneRepository().setURI(remoteDir.getAbsolutePath).setDirectory(localDir).call()

      val (shaAfterClone, branchAfterClone, fallbackAfterClone) =
        InspectRepo.resolveRemoteHead(localGit.getRepository)
      assertEquals(shaAfterClone.getName, firstSha, "immediately after cloning, the pinned SHA must match the remote's only commit")
      assert(!fallbackAfterClone, "the remote-tracking ref should resolve directly, no fallback needed")

      // Someone pushes new work to "the remote" after our clone already exists.
      val secondSha = commitFile(remoteGit, remoteDir, "b.txt", Instant.parse("2026-08-01T00:00:00Z"))
      assertNotEquals(firstSha, secondSha)

      // Reuse the existing local clone (as InspectRepo does on a second run)
      // and fetch, exactly the sequence InspectRepo's main() follows.
      localGit.fetch().setRemote("origin").call()

      val (shaAfterFetch, branchAfterFetch, fallbackAfterFetch) =
        InspectRepo.resolveRemoteHead(localGit.getRepository)
      assertEquals(
        shaAfterFetch.getName,
        secondSha,
        "after fetching, the pinned SHA must reflect the new commit pushed to the remote, not the SHA seen at the original clone"
      )
      assertEquals(branchAfterFetch, branchAfterClone, "the branch name itself should stay the same across the fetch")
      assert(!fallbackAfterFetch, "the remote-tracking ref should still resolve directly after a fetch, no fallback needed")

      remoteGit.close()
      localGit.close()
    finally
      deleteRecursively(remoteDir)
      deleteRecursively(localDir)
  }
