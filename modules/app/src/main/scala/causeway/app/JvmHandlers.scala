package causeway.app

import causeway.core.{BuildFailureClass, JvmBuildTool, MethodRef, UnknownReason}
import causeway.jvm.*
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import causeway.vcs.Candidates
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** MCP handlers over the JVM ecosystem services.
  *
  * Adapters only. Which image to try, or which path matters, is judgement and belongs to the
  * agents; these translate arguments in and shape results to the schema.
  */
object JvmHandlers:

  def all(handles: Handles): Vector[ToolHandler] = Vector(
    buildToolchain(handles),
    buildProbe(handles),
    callgraphBuild(handles),
    callgraphPaths(handles),
    callgraphCallers(handles),
    callgraphChainsTo(handles),
    methodAtLine(handles),
    methodBody(handles),
    classHierarchy(handles),
    testsForChange(handles),
    harnessRun(handles),
    traceExecuted(handles),
    tracePathIn(handles)
  )

  // ── helpers ────────────────────────────────────────────────────────────

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def bool(a: JsonNode, f: String): Option[Boolean] =
    Option(a.get(f)).filter(_.isBoolean).map(_.booleanValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val a = Json.mapper.createArrayNode()
    node.set(field, a)
    a

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(detail: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, detail)

  private def methodRef(n: JsonNode): Option[MethodRef] =
    for
      node <- Option(n).filterNot(_.isNull)
      fqcn <- str(node, "fqcn")
      name <- str(node, "name")
      desc <- str(node, "descriptor")
    yield MethodRef(fqcn, name, desc, str(node, "sourceFile"),
      int(node, "declStart"), int(node, "declEnd"))

  private def methodRefs(a: JsonNode, field: String): Vector[MethodRef] =
    Option(a.get(field)).filter(_.isArray)
      .map(_.values().asScala.toVector.flatMap(methodRef))
      .getOrElse(Vector.empty)

  /** `additionalProperties: false` in the schema, so only known fields. */
  private def refNode(m: MethodRef): ObjectNode =
    val n = Json.obj()
    n.put("fqcn", m.fqcn)
    n.put("name", m.name)
    n.put("descriptor", m.descriptor)
    m.sourceFile.foreach(n.put("sourceFile", _))
    m.declStart.foreach(n.put("declStart", _))
    m.declEnd.foreach(n.put("declEnd", _))
    n

  private def failureName(c: BuildFailureClass): String = c match
    case BuildFailureClass.DependencyResolution => "DEPENDENCY_RESOLUTION"
    case BuildFailureClass.CompileError         => "COMPILE_ERROR"
    case BuildFailureClass.JdkMismatch          => "JDK_MISMATCH"
    case BuildFailureClass.Timeout              => "TIMEOUT"
    case BuildFailureClass.MissingToolchain     => "MISSING_TOOLCHAIN"

  private def toolName(t: JvmBuildTool): String = t match
    case JvmBuildTool.Sbt    => "sbt"
    case JvmBuildTool.Maven  => "maven"
    case JvmBuildTool.Gradle => "gradle"

  private def withCallGraph(handles: Handles, a: JsonNode)(
      f: CallGraphHandle => HandlerResult
  ): HandlerResult =
    str(a, "callGraphId") match
      case None => na("callGraphId missing")
      case Some(id) =>
        handles.callGraph(id) match
          case Some(h) => f(h)
          // An evicted id is a DIFFERENT situation from one that never existed: the agent's
          // reasoning was sound, the memory bound reclaimed the graph underneath it. Saying so
          // tells it to rebuild rather than to doubt the id.
          case None if handles.callGraphEvicted(id) =>
            na(s"call graph '$id' was evicted to bound memory — rebuild it with " +
               "jvm_callgraph_build, and query it before building another")
          case None => na(s"no call graph '$id' — build it first")

  private def withView(handles: Handles, buildHandle: String)(
      f: sootup.java.core.views.JavaView => HandlerResult
  ): HandlerResult =
    val cp = handles.buildClasspath(buildHandle)
    if cp.isEmpty then na(s"no build registered for '$buildHandle'")
    else
      CallGraphService.openView(cp) match
        case Left(err)   => HandlerResult.Undetermined(UnknownReason.ToolUnavailable, err)
        case Right(view) => f(view)

  // ── build ──────────────────────────────────────────────────────────────

  private def buildToolchain(handles: Handles): ToolHandler =
    handler("jvm_build_toolchain") { a =>
      (str(a, "repoHandle").flatMap(handles.git), str(a, "commit")) match
        case (None, _) => na("no clone registered for that repoHandle")
        case (_, None) => na("commit is required")
        case (Some(git), Some(commit)) =>
          // Read the build files AT THE COMMIT, not from the working tree. Build files change
          // over history, and reporting today's toolchain for a decade-old revision would send
          // build-doctor after the wrong base image entirely.
          BuildProbe.detectAt(path => git.fileAt(commit, path).toOption) match
            case None => na("no JVM build file found at this revision")
            case Some(tc) =>
              val n = Json.obj()
              n.put("buildTool", toolName(tc.buildTool))
              tc.declaredSourceLevel.foreach(n.put("declaredSourceLevel", _))
              n.put("wrapperPresent", tc.wrapperPresent)
              val ms = arr(n, "markerFiles")
              tc.markerFiles.foreach(ms.add)
              HandlerResult.Data(n)
    }

  /** Compile ONE REVISION in a container. Compile only — never tests.
    *
    * The revision is the whole point. This handler used to ignore its `commit` parameter and
    * build whatever the shared clone happened to have checked out — which is HEAD, or worse,
    * whatever some earlier stage left behind. Every build-gate verdict was therefore a verdict
    * about HEAD, and D11 gates admission on buildability of the BUG's revisions, so the gate
    * was measuring the wrong thing while looking like it worked.
    *
    * Each commit gets its own worktree rather than moving the shared clone, so two probes can
    * run at once without fighting over the checkout, and the clone stays on its default branch.
    * The dependency cache is shared across worktrees, so this costs disk, not downloads.
    */
  private def buildToolNamed(s: String): Option[JvmBuildTool] = s.toLowerCase match
    case "maven"  => Some(JvmBuildTool.Maven)
    case "gradle" => Some(JvmBuildTool.Gradle)
    case "sbt"    => Some(JvmBuildTool.Sbt)
    case _        => None

  private def buildProbe(handles: Handles): ToolHandler = handler("jvm_build_probe") { a =>
    (str(a, "repoHandle"), str(a, "commit"), str(a, "baseImage")) match
      case (None, _, _) => na("repoHandle is required")
      case (_, None, _) => na("commit is required — a build probe is about one revision")
      case (_, _, None) => na("baseImage is required")
      case (Some(handle), Some(commit), Some(image)) =>
        if !Container.available() then
          HandlerResult.Undetermined(UnknownReason.ToolUnavailable, "Docker daemon not reachable")
        else
          handles.worktreeAt(handle, commit) match
            case Left(err) => na(err)
            case Right(dir) =>
              // The DECLARED tool wins when build-doctor supplies one. It has read the CI
              // workflow and the pom profiles; marker-file detection has read neither, and
              // silently overriding a deliberate choice with a guess is how a polyglot repo
              // gets built with the wrong tool.
              val tool = str(a, "buildTool").flatMap(buildToolNamed)
                .orElse(BuildProbe.detect(dir).map(_.buildTool))
                .getOrElse(JvmBuildTool.Maven)
              val base = BuildProbe.defaultRecipe(
                Toolchain(tool, str(a, "declaredSourceLevel"), wrapperPresent = false, Vector.empty)
              )
              val recipe = base.copy(
                baseImage = image,
                buildCommand = str(a, "command").getOrElse(base.buildCommand),
                flags = Option(a.get("flags")).filter(_.isArray)
                  .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty)
              )

              BuildProbe.probe(dir, recipe, int(a, "timeoutSec").getOrElse(600),
                               cacheRoot = Some(handles.cacheRoot)) match
                case ProbeOutcome.Succeeded(ms) =>
                  val buildHandle = handles.registerBuild(dir, recipe.baseImage)
                  val n = Json.obj()
                  n.put("succeeded", true)
                  n.put("buildHandle", buildHandle)
                  n.put("durationSec", (ms / 1000).toInt)
                  arr(n, "artifacts")
                  val cp = arr(n, "classpath")
                  handles.buildClasspath(buildHandle).foreach(cp.add)
                  HandlerResult.Data(n)

                case ProbeOutcome.Failed(cls, exit, tail, ms) =>
                  // A failed build is DATA, not an error: the classification tells build-doctor
                  // whether another base image would fix it.
                  val n = Json.obj()
                  n.put("succeeded", false)
                  n.put("failureClass", failureName(cls))
                  n.put("exitCode", exit)
                  n.put("stderrTail", tail.take(4000))
                  n.put("durationSec", (ms / 1000).toInt)
                  HandlerResult.Data(n)
  }

  // ── call graphs ────────────────────────────────────────────────────────

  private def callgraphBuild(handles: Handles): ToolHandler = handler("jvm_callgraph_build") { a =>
    str(a, "buildHandle") match
      case None => na("buildHandle missing")
      case Some(bh) =>
        val cp = handles.buildClasspath(bh)
        if cp.isEmpty then na(s"no build registered for '$bh'")
        else
          val entries = methodRefs(a, "entryPoints")
          val algo = str(a, "algorithm").map(_.toUpperCase) match
            case Some("RTA") => CgAlgorithm.Rta
            case _           => CgAlgorithm.Cha

          CallGraphService.build(cp, entries, algo) match
            case Left(err) =>
              // No resolvable entry point yields an EMPTY graph, which reads exactly like
              // "this code calls nothing". Surfaced as Unknown rather than a valid result.
              HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
            case Right(h) =>
              handles.registerCallGraph(h)
              val n = Json.obj()
              n.put("callGraphId", h.id)
              n.put("algorithm", if h.algorithm == CgAlgorithm.Rta then "RTA" else "CHA")
              n.put("nodeCount", h.nodeCount)
              n.put("edgeCount", h.edgeCount)
              n.put("entryPointCount", h.entryPoints.size)
              n.put("hasDebugInfo", h.hasDebugInfo)
              HandlerResult.Data(n)
  }

  private def callgraphPaths(handles: Handles): ToolHandler = handler("jvm_callgraph_paths") { a =>
    withCallGraph(handles, a) { h =>
      (methodRef(a.get("from")), methodRef(a.get("to"))) match
        case (Some(from), Some(to)) =>
          val maxPaths = int(a, "maxPaths").getOrElse(10)
          CallGraphService.paths(h, from, to, int(a, "maxLen").getOrElse(12), maxPaths) match
            case Left(err) => na(err)
            case Right((found, exhausted)) =>
              // An empty result is a real answer. Reflection, DI, dynamic proxies and native
              // calls are invisible to a static graph, and a disconnected fault site is a
              // finding rather than a failure.
              val n = Json.obj()
              val ps = arr(n, "paths")
              found.foreach { path =>
                val one = Json.mapper.createArrayNode()
                path.foreach(m => one.add(refNode(m)))
                ps.add(one)
              }
              n.put("searchExhausted", exhausted)
              n.put("truncated", found.size >= maxPaths)
              HandlerResult.Data(n)
        case _ => na("both from and to are required")
    }
  }

  private def callgraphCallers(handles: Handles): ToolHandler =
    handler("jvm_callgraph_callers") { a =>
      withCallGraph(handles, a) { h =>
        methodRef(a.get("method")) match
          case None => na("method is required")
          case Some(m) =>
            CallGraphService.callers(h, m, int(a, "depth").getOrElse(3)) match
              case Left(err) => na(err)
              case Right(callers) =>
                val n = Json.obj()
                val cs = arr(n, "callers")
                callers.foreach { (ref, d) =>
                  val c = Json.obj()
                  c.set("method", refNode(ref))
                  c.put("depth", d)
                  cs.add(c)
                }
                n.put("totalCount", callers.size)
                n.put("truncated", false)
                HandlerResult.Data(n)
      }
    }

  private def callgraphChainsTo(handles: Handles): ToolHandler =
    handler("jvm_callgraph_chains_to") { a =>
      withCallGraph(handles, a) { h =>
        methodRef(a.get("target")) match
          case None => na("target is required")
          case Some(t) =>
            val maxChains = int(a, "maxChains").getOrElse(20)
            val onlyPublic = bool(a, "onlyPublicEntries").getOrElse(true)

            CallGraphService.chainsTo(h, t, int(a, "maxLen").getOrElse(10), maxChains) match
              case Left(err) => na(err)
              case Right(chains) =>
                // "Public entry" is approximated by having no callers in this graph: nothing
                // above it means execution can only start there. Three-valued on purpose — when
                // the caller lookup itself fails the answer is UNKNOWN, not false.
                def entryIsPublic(chain: Vector[MethodRef]): Option[Boolean] =
                  CallGraphService.callers(h, chain.head, 1).map(_.isEmpty).toOption

                val kept = chains.map(c => (c, entryIsPublic(c))).filter {
                  // A signal that could not be computed is neutral. Dropping a chain because the
                  // caller lookup failed would turn a missing measurement into evidence that the
                  // entry point is private, and the synthesist would never see the chain at all.
                  case (_, judgement) => !onlyPublic || judgement.forall(identity)
                }

                val n = Json.obj()
                val cs = arr(n, "chains")
                kept.foreach { (chain, judgement) =>
                  val c = Json.obj()
                  val hops = arr(c, "hops")
                  chain.foreach(m => hops.add(refNode(m)))
                  judgement.foreach(c.put("entryIsPublic", _))
                  c.put("length", chain.size)
                  cs.add(c)
                }
                n.put("truncated", chains.size >= maxChains)
                // Filtering happens AFTER truncation, so the caller needs to know a filter ran
                // to read an empty result correctly.
                if onlyPublic && kept.size < chains.size then
                  n.put("filteredNonPublicEntries", chains.size - kept.size)
                HandlerResult.Data(n)
      }
    }

  // ── method-level queries ───────────────────────────────────────────────

  /** The method enclosing a file and line — the linchpin joining git coordinates to code. */
  private def methodAtLine(handles: Handles): ToolHandler = handler("jvm_method_at_line") { a =>
    (str(a, "buildHandle"), str(a, "file"), int(a, "line")) match
      case (Some(bh), Some(file), Some(line)) =>
        withView(handles, bh) { view =>
          CallGraphService.methodAtLineIn(view, file, line) match
            case Left(err) if err.contains("line-number") =>
              // Stripped debug info is a distinct, recoverable condition: rebuild with -g.
              // Reporting it as NotApplicable would hide a fixable environment problem.
              HandlerResult.Undetermined(UnknownReason.NoDebugInfo, err)
            case Left(err) => na(err)
            case Right(m) =>
              val n = Json.obj()
              n.set("method", refNode(m))
              HandlerResult.Data(n)
        }
      case _ => na("buildHandle, file and line are required")
  }

  private def methodBody(handles: Handles): ToolHandler = handler("jvm_method_body") { a =>
    (str(a, "buildHandle"), methodRef(a.get("method"))) match
      case (Some(bh), Some(ref)) =>
        withView(handles, bh) { view =>
          CallGraphService.methodShapeIn(view, ref) match
            case Left(err) => na(err)
            case Right((range, branches)) =>
              val n = Json.obj()
              n.set("method", refNode(ref))

              val source = for
                (from, to) <- range
                dir        <- handles.buildDir(bh)
                rel        <- ref.sourceFile
                file = dir.resolve(rel)
                if Files.isRegularFile(file)
              yield Files.readAllLines(file).asScala.toVector
                .slice(math.max(0, from - 1), to).mkString("\n")

              n.put("source", source.getOrElse(""))
              range.foreach((from, _) => n.put("sourceStartLine", from))
              n.put("branchCount", branches)
              // Cyclomatic complexity is branches plus one for the single entry path.
              n.put("cyclomaticComplexity", branches + 1)
              HandlerResult.Data(n)
        }
      case _ => na("buildHandle and method are required")
  }

  /** Supertypes, subtypes and overrides — often why a path looks disconnected. */
  private def classHierarchy(handles: Handles): ToolHandler = handler("jvm_class_hierarchy") { a =>
    withCallGraph(handles, a) { h =>
      str(a, "fqcn") match
        case None => na("fqcn is required")
        case Some(fqcn) =>
          CallGraphService.classHierarchy(h, fqcn) match
            case Left(err) => na(err)
            case Right((supers, subs, overrides)) =>
              val n = Json.obj()
              n.put("fqcn", fqcn)
              val sup = arr(n, "supertypes"); supers.foreach(sup.add)
              val sub = arr(n, "subtypes"); subs.foreach(sub.add)
              val ov  = arr(n, "overrides")
              overrides.take(200).foreach { m =>
                val o = Json.obj()
                o.set("method", refNode(m))
                o.put("declaredIn", m.fqcn)
                ov.add(o)
              }
              HandlerResult.Data(n)
    }
  }

  /** Tests touched by a commit.
    *
    * A regression test added alongside a production change is strong evidence of a bug fix. Its
    * ABSENCE is neutral — many projects ship fixes without tests, and the reproducer ladder
    * exists precisely to handle that.
    */
  private def testsForChange(handles: Handles): ToolHandler =
    handler("jvm_tests_for_change") { a =>
      (str(a, "repoHandle").flatMap(handles.git), str(a, "commit")) match
        case (None, _) => na("no clone registered for that repoHandle")
        case (_, None) => na("commit is required")
        case (Some(git), Some(sha)) =>
          git.diff(sha) match
            case Left(err) => na(err)
            case Right(d) =>
              val testHunks = d.allHunks.filter(h => Candidates.isTestFile(h.file))
              val n = Json.obj()
              val files = arr(n, "testFilesTouched")
              testHunks.map(_.file).distinct.foreach(files.add)

              val added    = arr(n, "testsAdded")
              val modified = arr(n, "testsModified")
              val testMethod =
                """(?:public|private|protected)?\s*(?:static\s+)?[\w<>\[\]]+\s+(\w*[Tt]est\w*)\s*\(""".r

              testHunks.foreach { h =>
                h.addedLines.foreach { line =>
                  testMethod.findFirstMatchIn(line).foreach { m =>
                    val o = Json.obj()
                    o.put("file", h.file)
                    o.put("method", m.group(1))
                    h.newRange.foreach(r => o.put("startLine", r.start))
                    added.add(o)
                  }
                }
                if h.deletedLines.nonEmpty && h.addedLines.nonEmpty then
                  val o = Json.obj()
                  o.put("file", h.file)
                  o.put("method", "(modified)")
                  modified.add(o)
              }

              val total     = d.allHunks.map(h => h.addedLines.size + h.deletedLines.size).sum
              val testLines = testHunks.map(h => h.addedLines.size + h.deletedLines.size).sum
              n.put("testLocRatio", if total == 0 then 0.0 else testLines.toDouble / total)
              HandlerResult.Data(n)
    }

  /** The differential oracle (D14).
    *
    * Compiles an agent-authored harness against BOTH revisions and compares. Runs with NO
    * network, unlike a build: this is agent-authored code, and nothing it legitimately needs
    * lives outside the workspace.
    */
  private def harnessRun(handles: Handles): ToolHandler = handler("jvm_harness_run") { a =>
    val repoHandle = str(a, "repoHandle")
    (repoHandle.flatMap(handles.git), str(a, "fixSha"), str(a, "harnessSource"),
     methodRef(a.get("entryPoint"))) match
      case (None, _, _, _)       => na("no clone registered for that repoHandle")
      case (_, None, _, _)       => na("fixSha is required")
      case (_, _, None, _)       => na("harnessSource is required")
      case (_, _, _, None)       => na("entryPoint is required")
      case (Some(git), Some(fixSha), Some(source), Some(_)) =>
        if !Container.available() then
          HandlerResult.Undetermined(UnknownReason.ToolUnavailable, "Docker daemon not reachable")
        else
          git.commitMeta(fixSha).map(_.parents.headOption) match
            case Left(err)         => na(err)
            case Right(None)       => na(s"$fixSha has no parent to differentiate against")
            case Right(Some(parent)) =>
              val work = handles.workspace.resolve("harness")
                .resolve(f"${fixSha.take(12)}-${source.hashCode & 0xffffff}%06x")
              Files.createDirectories(work)
              val pDir = work.resolve("src-parent")
              val fDir = work.resolve("src-fix")

              // Both revisions are materialised at once: a working-tree checkout can only hold
              // one, and the whole point is to run the same input against both.
              val staged = git.checkoutTo(parent, pDir).isRight && git.checkoutTo(fixSha, fDir).isRight

              if !staged then
                HandlerResult.Undetermined(UnknownReason.BuildFailed,
                  "could not materialise both revisions for differential execution")
              else
                val r = HarnessRunner.differential(
                  work, HarnessRunner.Side(pDir), HarnessRunner.Side(fDir), source,
                  timeoutSec = int(a, "timeoutSec").getOrElse(120)
                )
                val n = Json.obj()
                n.put("compiled", r.compiled)
                r.compileError.foreach(e => n.put("compileError", e.take(4000)))
                n.put("differs", r.differs)
                val ch = arr(n, "differsOn")
                r.differsOn.foreach(c =>
                  ch.add(if c == DiffChannel.Exception then "EXCEPTION" else "RETURN"))

                def outcome(o: Option[RunOutcome], field: String): Unit = o.foreach { x =>
                  val d = Json.obj()
                  x.exceptionType.foreach(d.put("exceptionType", _))
                  x.exceptionSite.foreach(d.put("exceptionSite", _))
                  x.returnValue.foreach(d.put("returnValue", _))
                  d.put("timedOut", x.timedOut)
                  n.set(field, d)
                }
                outcome(r.atParent, "resultAtParent")
                outcome(r.atFix, "resultAtFix")

                // The trace is what lets a later path be called OBSERVED rather than merely
                // statically reachable. Absent when instrumentation could not be staged — the
                // differential answer still stands, it just cannot support an observed path.
                if r.executedAtParent.nonEmpty then
                  val traceId = handles.registerTrace(r.executedAtParent)
                  n.put("traceId", traceId)
                  n.put("executedMethodCount", r.executedAtParent.size)

                HandlerResult.Data(n)
  }

  /** Methods that actually executed during a reproducing run. */
  private def traceExecuted(handles: Handles): ToolHandler = handler("jvm_trace_executed") { a =>
    str(a, "traceId").flatMap(handles.trace) match
      case None => na("no trace with that id — run a harness with instrumentation first")
      case Some(methods) =>
        val filter = str(a, "packageFilter")
        val kept   = filter.fold(methods)(p => methods.filter(_.fqcn.startsWith(p)))
        val capped = kept.take(2000)

        val n = Json.obj()
        n.put("traceId", str(a, "traceId").get)
        val ms = arr(n, "methods")
        capped.foreach(m => ms.add(refNode(m)))
        n.put("totalCount", kept.size)
        n.put("truncated", kept.size > capped.size)
        HandlerResult.Data(n)
  }

  /** A path confined to what actually ran.
    *
    * The difference between "propagation was possible" and "propagation happened".
    */
  private def tracePathIn(handles: Handles): ToolHandler = handler("jvm_trace_path_in") { a =>
    (str(a, "traceId").flatMap(handles.trace), str(a, "callGraphId").flatMap(handles.callGraph),
     methodRef(a.get("from")), methodRef(a.get("to"))) match
      case (None, _, _, _) => na("no trace with that id")
      case (_, None, _, _) => na("no call graph with that id — build it first")
      case (_, _, None, _) | (_, _, _, None) => na("both from and to are required")
      case (Some(executed), Some(cg), Some(from), Some(to)) =>
        CallGraphService.pathIn(cg, executed, from, to, int(a, "maxLen").getOrElse(15)) match
          case Left(err) => na(err)
          case Right((path, unvouched)) =>
            val n = Json.obj()
            val ps = arr(n, "path")
            path.foreach(m => ps.add(refNode(m)))
            // Every hop vouched for by the trace is what licenses calling this OBSERVED.
            n.put("allHopsExecuted", path.nonEmpty && unvouched.isEmpty)
            val missing = arr(n, "missingHops")
            unvouched.foreach(m => missing.add(refNode(m)))
            HandlerResult.Data(n)
  }

  /** Resolve a relative path against a clone, for handlers that take file paths. */
  private[app] def resolve(base: Path, p: String): Path =
    val candidate = Path.of(p)
    if candidate.isAbsolute then candidate else base.resolve(p)
