package causeway.mini

import munit.FunSuite
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.nio.file.Files
import java.time.{Instant, ZoneOffset}

/** Covers the exact scenario this whole redesign exists for: a clone with
  * more than one remote configured, e.g. a fork's `origin` pointing at the
  * fork and `upstream` pointing at the original repository. The tool must
  * never assume which one is "the" remote, it lists every configured
  * remote so a person can choose. This confirms listConfiguredRemotes
  * actually surfaces all of them, not just whichever happens to be first
  * or named "origin".
  */
class InspectRepoRemotesSpec extends FunSuite:

  private def personAt(instant: Instant): PersonIdent =
    new PersonIdent("Test", "test@example.com", instant, ZoneOffset.UTC)

  private def deleteRecursively(f: File): Unit =
    if f.isDirectory then f.listFiles().foreach(deleteRecursively)
    f.delete()

  test("lists every configured remote, not just origin, when a clone has a fork-style upstream too") {
    val originRemoteDir = Files.createTempDirectory("inspectrepo-remotes-origin").toFile
    val upstreamRemoteDir = Files.createTempDirectory("inspectrepo-remotes-upstream").toFile
    val localDir = Files.createTempDirectory("inspectrepo-remotes-local").toFile
    try
      val originGit = Git.init().setDirectory(originRemoteDir).call()
      val ident = personAt(Instant.parse("2026-07-01T00:00:00Z"))
      Files.writeString(new File(originRemoteDir, "a.txt").toPath, "a")
      originGit.add().addFilepattern("a.txt").call()
      originGit.commit().setMessage("origin commit").setAuthor(ident).setCommitter(ident).call()

      val upstreamGit = Git.init().setDirectory(upstreamRemoteDir).call()
      Files.writeString(new File(upstreamRemoteDir, "b.txt").toPath, "b")
      upstreamGit.add().addFilepattern("b.txt").call()
      upstreamGit.commit().setMessage("upstream commit").setAuthor(ident).setCommitter(ident).call()

      // Clone the "fork" (origin), then add a second remote pointing at the
      // "original" repo (upstream), exactly the shape a real fork clone has.
      val localGit = Git.cloneRepository().setURI(originRemoteDir.getAbsolutePath).setDirectory(localDir).call()
      localGit.remoteAdd().setName("upstream").setUri(new org.eclipse.jgit.transport.URIish(upstreamRemoteDir.getAbsolutePath)).call()

      val remotes = InspectRepo.listConfiguredRemotes(localGit.getRepository).toMap

      assertEquals(remotes.size, 2, s"expected exactly 2 remotes, got: $remotes")
      assert(remotes.contains("origin"), "origin (the fork) must be listed")
      assert(remotes.contains("upstream"), "upstream (the original repo) must also be listed, not silently ignored")
      assertEquals(remotes("origin"), originRemoteDir.getAbsolutePath)
      assertEquals(remotes("upstream"), upstreamRemoteDir.getAbsolutePath)

      originGit.close()
      upstreamGit.close()
      localGit.close()
    finally
      deleteRecursively(originRemoteDir)
      deleteRecursively(upstreamRemoteDir)
      deleteRecursively(localDir)
  }
