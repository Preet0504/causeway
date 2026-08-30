ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "causeway"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// sbt 2.x already sets -deprecation, -feature, -unchecked, -Wunused:all, -source and -Werror
// by default. Restating any of them is an error ("set repeatedly"), so this build adds none.

val scalatestV     = "3.2.19"
val jsonSchemaV    = "3.0.6"
val jacksonV       = "3.0.4"
val mcpV           = "2.0.1"
val jgitV          = "7.3.0.202506031305-r"
val neo4jDriverV   = "5.28.5"
val sootupV        = "3.0.1"
val jacocoV        = "0.8.15"

lazy val commonSettings = Seq(
  libraryDependencies += "org.scalatest" %% "scalatest" % scalatestV % Test,
  // In-process. Forking was tried to release Windows file locks and did NOT: the sbt server
  // still holds module jars open after a test run, and the next `compile` still fails with
  // AccessDeniedException moving a jar into place. `sbt -batch shutdown` between a test run and
  // the next compile is the thing that actually works.
  Test / fork := false
)

// Domain vocabulary. No I/O, no dependencies. Everything else is typed against this.
lazy val core = (project in file("modules/core"))
  .settings(name := "causeway-core")
  .settings(commonSettings)

// Transport, tool registry, schema enforcement, evidence ledger, ACLs, resource pooling.
// The only place enforcement lives.
lazy val mcpserver = (project in file("modules/mcpserver"))
  .dependsOn(core)
  .settings(name := "causeway-mcpserver")
  .settings(commonSettings)
  .settings(
    // Jackson 3 (`tools.jackson`), not Jackson 2 (`com.fasterxml`). json-schema-validator 3.x
    // is built against Jackson 3, so the MCP SDK must use its jackson3 binding to match —
    // mixing the two means JsonNode from one library cannot be passed to the other.
    libraryDependencies ++= Seq(
      "com.networknt"                 % "json-schema-validator" % jsonSchemaV,
      "tools.jackson.core"            % "jackson-databind"      % jacksonV,
      "io.modelcontextprotocol.sdk"   % "mcp"                   % mcpV,
      "io.modelcontextprotocol.sdk"   % "mcp-json-jackson3"     % mcpV
    )
  )

// JGit: cloning, history traversal, diffing, blame, candidate recall nets.
lazy val vcs = (project in file("modules/vcs"))
  .dependsOn(core)
  .settings(name := "causeway-vcs")
  .settings(commonSettings)
  .settings(
    libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit" % jgitV
  )

// GitHub GraphQL v4. Retrieval only — no judgement lives here (D6).
lazy val forge = (project in file("modules/forge"))
  .dependsOn(core)
  .settings(name := "causeway-forge")
  .settings(commonSettings)
  .settings(
    // HTTP comes from java.net.http in the JDK; only JSON needs a dependency.
    libraryDependencies += "tools.jackson.core" % "jackson-databind" % jacksonV
  )

// Pure total functions over Signal. No I/O, so every metric is reproducible from stored inputs.
lazy val metrics = (project in file("modules/metrics"))
  .dependsOn(core)
  .settings(name := "causeway-metrics")
  .settings(commonSettings)

// Neo4j persistence and the (deliberately separate, non-evidentiary) note store.
lazy val graphstore = (project in file("modules/graphstore"))
  .dependsOn(core)
  .settings(name := "causeway-graphstore")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.neo4j.driver" % "neo4j-java-driver" % neo4jDriverV,
      "tools.jackson.core" % "jackson-databind" % jacksonV
    )
  )

// Ecosystem-complete JVM unit: containerised build probe, static analysis, coverage, harness
// runner. modules/llvm will be its structural twin with no code shared beyond core (D5).
lazy val jvm = (project in file("modules/jvm"))
  .dependsOn(core)
  .settings(name := "causeway-jvm")
  .settings(commonSettings)
  .settings(
    // SootUp, not OPAL, for call graphs — and the reason is a hard constraint rather than a
    // preference. OPAL 7.0.0 publishes Scala 3 artifacts but is built against Scala 3.7.3,
    // and a 3.3 compiler cannot read TASTy emitted by 3.7. Adopting it would pin this whole
    // project's Scala version to whatever OPAL was last built with, forever. SootUp is pure
    // Java, so it couples to nothing. OPAL remains available as a cross-check if the build
    // ever moves to 3.7+.
    libraryDependencies ++= Seq(
      "org.soot-oss" % "sootup.core"                   % sootupV,
      "org.soot-oss" % "sootup.java.core"              % sootupV,
      "org.soot-oss" % "sootup.java.bytecode.frontend" % sootupV,
      "org.soot-oss" % "sootup.callgraph"              % sootupV,
      // org.jacoco.core parses .exec files and analyses class files; org.jacoco.agent carries
      // the instrumentation agent as an embedded resource, extracted at runtime by AgentJar.
      "org.jacoco"   % "org.jacoco.core"                % jacocoV,
      "org.jacoco"   % "org.jacoco.agent"               % jacocoV
    )
  )

/** Package a launchable distribution.
  *
  * Not a fat jar, deliberately. The MCP SDK discovers its JSON mapper through `ServiceLoader`,
  * and shading everything into one archive means merging `META-INF/services` entries — get that
  * wrong and the failure is a silent "no mapper found" at startup rather than a build error.
  * Copying jars into a `lib` directory and launching with a wildcard classpath sidesteps the
  * whole class of problem and stays debuggable: you can see exactly which jars are on the path.
  *
  * (Note for editors: Scala comments NEST, so a slash-star sequence inside this block would
  * open a nested comment and swallow the rest of the file. Keep glob patterns out of here.)
  */
/** Copy the runtime classpath into `distDir/lib` and write the two launchers.
  *
  * Shared by `dist` and `distTo` so the staged and in-place builds cannot drift apart.
  */
def packageDist(distDir: File, cp: Def.Classpath, conv: xsbti.FileConverter, log: Logger): Unit = {
  val libDir = distDir / "lib"
  IO.delete(distDir)
  IO.createDirectory(libDir)

  val copied = cp.flatMap { entry =>
    val f = conv.toPath(entry.data).toFile
    if (!f.exists) None
    else if (f.isDirectory) {
      // Loose class directories cannot go on a wildcard classpath; jar them up.
      val jar = libDir / s"${f.getName}-classes.jar"
      IO.jar(Path.allSubpaths(f).toSeq, jar, new java.util.jar.Manifest, None)
      Some(jar)
    } else {
      val target = libDir / f.getName
      IO.copyFile(f, target)
      Some(target)
    }
  }.distinct

  // Windows launcher. CAUSEWAY_HOME lets the server find schemas/ regardless of the working
  // directory Claude Code happens to start it in.
  IO.write(distDir / "causeway.cmd",
    """@echo off
      |setlocal
      |set "HERE=%~dp0"
      |if "%CAUSEWAY_HOME%"=="" set "CAUSEWAY_HOME=%HERE%..\.."
      |if "%CAUSEWAY_SCHEMAS%"=="" set "CAUSEWAY_SCHEMAS=%CAUSEWAY_HOME%\schemas"
      |java -Xmx6g -XX:+ExitOnOutOfMemoryError -cp "%HERE%lib\*" causeway.app.Main %*
      |""".stripMargin.replace("\n", "\r\n"))

  IO.write(distDir / "causeway",
    """#!/bin/sh
      |HERE="$(cd "$(dirname "$0")" && pwd)"
      |: "${CAUSEWAY_HOME:=$HERE/../..}"
      |: "${CAUSEWAY_SCHEMAS:=$CAUSEWAY_HOME/schemas}"
      |export CAUSEWAY_HOME CAUSEWAY_SCHEMAS
      |exec java -Xmx6g -XX:+ExitOnOutOfMemoryError -cp "$HERE/lib/*" causeway.app.Main "$@"
      |""".stripMargin)
  (distDir / "causeway").setExecutable(true)

  log.info(s"dist: ${copied.size} jars -> $libDir")
  log.info(s"launch: ${distDir / "causeway.cmd"}")
}

// Unit, not File: sbt 2 caches task results and rejects File/Path as an output type.
lazy val dist = taskKey[Unit]("Package a self-contained launchable distribution under target/dist")

/** `distTo <dir>` — same packaging, somewhere else.
  *
  * An ARGUMENT rather than an environment variable, because sbt 2 keeps a background server
  * alive between invocations and the server has the environment it was STARTED with. Setting
  * CAUSEWAY_DIST_DIR on the client changed nothing and the build happily rebuilt in place,
  * which on Windows means failing against the jars a running server holds open.
  */
lazy val distTo = inputKey[Unit]("Package the distribution into a named directory")

// Assembly: wires the deterministic services into the MCP server as tool handlers.
// mcpserver stays a pure framework and knows nothing about JGit, GitHub or Docker.
lazy val app = (project in file("modules/app"))
  .dependsOn(core, mcpserver, vcs, forge, graphstore, jvm, metrics)
  .settings(name := "causeway-app")
  .settings(commonSettings)
  .settings(
    Compile / mainClass := Some("causeway.app.Main"),
    // Def.uncached: sbt 2 caches task results, and a Unit result caches trivially. `dist` COPIES
    // files as its whole purpose, so the effect lives entirely outside the declared inputs — a
    // cached second run reports success having written nothing, which is how a stale distribution
    // gets served while the log says the build succeeded.
    dist := Def.uncached {
      packageDist(
        (ThisBuild / baseDirectory).value / "target" / "dist",
        (Runtime / fullClasspath).value, fileConverter.value, streams.value.log)
    },
    distTo := Def.uncached {
      val args = sbt.complete.DefaultParsers.spaceDelimited("<dir>").parsed
      require(args.size == 1, "usage: app/distTo <directory>")
      packageDist(file(args.head), (Runtime / fullClasspath).value,
                  fileConverter.value, streams.value.log)
    }
  )

lazy val root = (project in file("."))
  .aggregate(core, mcpserver, vcs, metrics, forge, graphstore, jvm, app)
  .settings(name := "causeway", publish / skip := true)
