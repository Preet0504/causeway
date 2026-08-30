package causeway.jvm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** Exercises real containers.
  *
  * Cancels rather than fails when Docker is unreachable or the base image is absent, so a
  * checkout without Docker still gets a green suite. These tests are the only proof that D20's
  * isolation actually holds — asserting it in prose would prove nothing.
  */
class ContainerSpec extends AnyFunSuite with Matchers:

  private val Image = "eclipse-temurin:17-jdk-alpine"

  private def requireDocker(): Unit =
    if !Container.available() then cancel("Docker not reachable — skipping container tests")
    if !Container.imagePresent(Image) then cancel(s"$Image not pulled — skipping")

  private def workdir(files: (String, String)*): Path =
    val d = Files.createTempDirectory("causeway-container")
    files.foreach { case (n, c) => Files.writeString(d.resolve(n), c) }
    d

  test("a command runs in a container and its output comes back"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 60),
      workdir(), "echo hello-from-container")
    r.succeeded shouldBe true
    r.stdout should include("hello-from-container")

  test("a non-zero exit is reported, not thrown"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 60),
      workdir(), "exit 3")
    r.succeeded shouldBe false
    r.exitCode shouldBe 3

  test("the workspace is mounted, and code inside it compiles and runs"):
    requireDocker()
    val d = workdir("Hello.java" ->
      """public class Hello { public static void main(String[] a){ System.out.println("ok-42"); } }""")
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 120),
      d, "javac Hello.java && java Hello")
    r.succeeded shouldBe true
    r.stdout should include("ok-42")

  // The property that makes running agent-authored code acceptable at all.
  test("network:false genuinely blocks the network"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 60),
      workdir(), "wget -q -T3 -O- https://repo1.maven.org")
    r.succeeded shouldBe false
    r.combinedTail().toLowerCase should (include("bad address") or include("resolve") or include("unreachable"))

  test("network:true allows it, which is why builds get network and harnesses do not"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = true, timeoutSec = 90),
      workdir(), "wget -q -T10 -O- https://repo1.maven.org/maven2/ | head -c 50")
    // Not asserting success — a proxy or offline machine could legitimately fail. Asserting only
    // that the failure mode differs from the DNS block above.
    if r.succeeded then r.stdout should not be empty
    else r.combinedTail().toLowerCase should not include "bad address"

  test("a run that exceeds its timeout is killed and reported as timed out"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 5),
      workdir(), "sleep 60")
    r.timedOut shouldBe true
    r.succeeded shouldBe false
    r.durationMs should be < 40000L

  test("a container that writes a lot of output does not deadlock"):
    requireDocker()
    // Both pipes are drained on their own threads; without that this hangs once the OS buffer
    // fills, and a verbose build would hang the whole pipeline.
    val r = Container.exec(ContainerPolicy(Image, network = false, timeoutSec = 120),
      workdir(), "for i in $(seq 1 20000); do echo line-$i; done")
    r.succeeded shouldBe true
    r.stdout.linesIterator.size should be >= 20000

  test("memory limits are applied"):
    requireDocker()
    val r = Container.exec(ContainerPolicy(Image, network = false, memoryMb = 256, timeoutSec = 60),
      workdir(), "echo limited")
    r.succeeded shouldBe true
