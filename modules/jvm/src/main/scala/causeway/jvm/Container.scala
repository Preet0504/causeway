package causeway.jvm

import causeway.core.JvmBuildTool

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class ContainerResult(
    exitCode: Int,
    stdout: String,
    stderr: String,
    timedOut: Boolean,
    durationMs: Long
):
  def succeeded: Boolean = exitCode == 0 && !timedOut
  def combinedTail(lines: Int = 40): String =
    (stdout.linesIterator.toVector ++ stderr.linesIterator.toVector).takeRight(lines).mkString("\n")

/** Resource and isolation policy for one container run.
  *
  * `network` is the interesting field. D20 puts everything past the build gate in a container,
  * but it cannot be network-free across the board: Maven and Gradle must reach a repository to
  * resolve dependencies, and a build with no network fails as `DEPENDENCY_RESOLUTION` for a
  * reason that has nothing to do with the commit. So:
  *
  *   - **builds and test runs get network** — they are the project's own code, and they cannot
  *     work without it
  *   - **harness runs do not** — that is agent-authored code, and nothing it legitimately needs
  *     lives outside the workspace
  *
  * Everything still gets a wall-clock timeout, a memory cap, a CPU cap, and a workspace mount
  * and nothing else.
  */
/** A host directory bound into the container, beyond the workspace mount. */
final case class Mount(hostPath: Path, containerPath: String, readOnly: Boolean = false)

final case class ContainerPolicy(
    image: String,
    network: Boolean,
    memoryMb: Int = 2048,
    cpus: Double = 2.0,
    timeoutSec: Int = 600,
    /** Extra mounts, used for the dependency cache. Empty for harness runs. */
    mounts: Vector[Mount] = Vector.empty,
    /** Environment for the command, used to point build tools at the cache. */
    env: Map[String, String] = Map.empty
)

/** Where each build tool keeps downloaded dependencies, and how to redirect it.
  *
  * Without this, every container starts with an empty cache and re-downloads the entire
  * dependency tree. That is minutes per build, and this project builds TWO revisions per bug —
  * the parent and the fix — so it doubles again.
  *
  * Bind-mounted host directories rather than named volumes: a fresh named volume is owned by
  * root, and the official gradle image does not run as root, so the build would fail on a
  * permission error that looks nothing like its cause. A bind mount under the workspace is also
  * inspectable and deletable without docker.
  *
  * Only BUILDS get a cache. Harness runs are agent-authored code with no network, and nothing
  * they legitimately need lives outside the workspace.
  */
object DependencyCache:
  def forTool(tool: JvmBuildTool, cacheRoot: Path): (Vector[Mount], Map[String, String]) =
    def mount(name: String, at: String) =
      val host = cacheRoot.resolve(name)
      Files.createDirectories(host)
      Mount(host, at)

    tool match
      case JvmBuildTool.Maven =>
        (Vector(mount("m2", "/cache/m2")), Map("MAVEN_OPTS" -> "-Dmaven.repo.local=/cache/m2"))
      case JvmBuildTool.Gradle =>
        (Vector(mount("gradle", "/cache/gradle")), Map("GRADLE_USER_HOME" -> "/cache/gradle"))
      case JvmBuildTool.Sbt =>
        // sbt resolves through coursier, which honours COURSIER_CACHE; ivy2 still holds
        // locally-published artifacts for builds that predate coursier.
        (Vector(mount("coursier", "/cache/coursier"), mount("ivy2", "/cache/ivy2")),
         Map("COURSIER_CACHE" -> "/cache/coursier", "SBT_OPTS" -> "-Dsbt.ivy.home=/cache/ivy2"))

/** Runs a command inside a container.
  *
  * Nothing from a mined repository, and nothing written by an agent, executes on the host.
  */
object Container:

  def available(): Boolean =
    Try(run(Vector("docker", "version", "--format", "{{.Server.Version}}"), None, 15).succeeded)
      .getOrElse(false)

  def imagePresent(image: String): Boolean =
    Try {
      run(Vector("docker", "image", "inspect", image), None, 30).succeeded
    }.getOrElse(false)

  def pull(image: String, timeoutSec: Int = 600): ContainerResult =
    run(Vector("docker", "pull", image), None, timeoutSec)

  /** Execute `shellCommand` in `image`, with `mount` bound at /work.
    *
    * The mount is passed as an absolute host path. On Windows that is a `F:\...` style path,
    * which Docker Desktop accepts directly — note this is invoked through ProcessBuilder, not a
    * shell, so there is no MSYS path mangling to work around.
    */
  def exec(policy: ContainerPolicy, mount: Path, shellCommand: String): ContainerResult =
    val args = Vector.newBuilder[String]
    args += "docker" += "run" += "--rm"
    if !policy.network then args += "--network" += "none"
    args += "--memory" += s"${policy.memoryMb}m"
    args += "--cpus" += policy.cpus.toString
    args += "-v" += s"${mount.toAbsolutePath.toString}:/work"
    policy.mounts.foreach { m =>
      args += "-v" += s"${m.hostPath.toAbsolutePath.toString}:${m.containerPath}${if m.readOnly then ":ro" else ""}"
    }
    policy.env.foreach { (k, v) => args += "-e" += s"$k=$v" }
    args += "-w" += "/work"
    args += policy.image
    // `sh -c`, NOT `sh -lc`. A login shell re-initialises PATH from /etc/profile and discards
    // the image's own ENV PATH — in eclipse-temurin that drops /opt/java/openjdk/bin, so javac
    // and java vanish. Every build would then fail as MissingToolchain, blaming the repository
    // for our shell flag.
    args += "sh" += "-c" += shellCommand

    run(args.result(), Some(mount.toFile), policy.timeoutSec)

  private def run(command: Vector[String], cwd: Option[File], timeoutSec: Int): ContainerResult =
    val started = System.currentTimeMillis()
    val pb      = ProcessBuilder(command.asJava)
    cwd.foreach(pb.directory)

    val proc = pb.start()
    // Drain both pipes on their own threads: a container that fills the stdout buffer while we
    // wait on the process would deadlock, and a build that produces a lot of output is normal.
    val outBuf = StringBuilder()
    val errBuf = StringBuilder()
    val outT   = drain(proc.getInputStream, outBuf)
    val errT   = drain(proc.getErrorStream, errBuf)

    val finished = proc.waitFor(timeoutSec.toLong, TimeUnit.SECONDS)
    if !finished then
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)

    outT.join(5000)
    errT.join(5000)

    ContainerResult(
      exitCode = if finished then proc.exitValue() else -1,
      stdout = outBuf.toString,
      stderr = errBuf.toString,
      timedOut = !finished,
      durationMs = System.currentTimeMillis() - started
    )

  private def drain(is: java.io.InputStream, into: StringBuilder): Thread =
    val t = Thread { () =>
      Try {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(is, "UTF-8"))
        var line   = reader.readLine()
        while line != null do
          into.synchronized(into.append(line).append('\n'))
          line = reader.readLine()
      }
      ()
    }
    t.setDaemon(true)
    t.start()
    t
