package causeway.jvm

import causeway.core.MethodRef

import sootup.callgraph.{CallGraph, ClassHierarchyAnalysisAlgorithm, RapidTypeAnalysisAlgorithm}
import sootup.core.signatures.MethodSignature
import sootup.java.bytecode.frontend.inputlocation.{
  DefaultRuntimeAnalysisInputLocation,
  JavaClassPathAnalysisInputLocation
}
import sootup.java.core.views.JavaView

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

enum CgAlgorithm:
  case Cha, Rta

/** A built call graph, held server-side.
  *
  * Never returned to an agent: a mid-size project's graph has millions of edges. Agents get an
  * id and query through it, which is what keeps context small regardless of repository size.
  */
final class CallGraphHandle(
    val id: String,
    val algorithm: CgAlgorithm,
    private[jvm] val view: JavaView,
    private[jvm] val graph: CallGraph,
    val entryPoints: Vector[MethodRef]
):
  def nodeCount: Int = graph.getMethodSignatures.size
  def edgeCount: Int = graph.callCount

  /** Whether line-number debug information survived compilation.
    *
    * Without it, `methodAtLine` cannot work and every path degrades to file granularity. Worth
    * knowing up front rather than discovering it per query.
    */
  lazy val hasDebugInfo: Boolean =
    view.getClasses.iterator().asScala
      .take(20)
      .flatMap(_.getMethods.asScala)
      .exists(m => Try(m.getBody.getStmts.asScala.exists(_.getPositionInfo.getStmtPosition.getFirstLine > 0)).getOrElse(false))

object CallGraphService:

  /** Build a call graph over a classpath.
    *
    * The JDK's own classes are included as an input location: class hierarchy analysis has to
    * resolve `java.lang.Object` and friends to reason about virtual dispatch at all, and a graph
    * built without them silently loses every edge through an inherited method.
    */
  def build(
      classpath: Vector[String],
      entryPoints: Vector[MethodRef],
      algorithm: CgAlgorithm = CgAlgorithm.Cha,
      includeRuntime: Boolean = true
  ): Either[String, CallGraphHandle] =
    Try {
      val inputs = new java.util.ArrayList[sootup.core.inputlocation.AnalysisInputLocation]()
      classpath.foreach(cp => inputs.add(JavaClassPathAnalysisInputLocation(cp)))
      if includeRuntime then inputs.add(DefaultRuntimeAnalysisInputLocation())

      val view = JavaView(inputs)

      val algo = algorithm match
        case CgAlgorithm.Cha => ClassHierarchyAnalysisAlgorithm(view)
        case CgAlgorithm.Rta => RapidTypeAnalysisAlgorithm(view)

      val signatures = entryPoints.flatMap(m => resolve(view, m))

      // With no resolvable entry points a call graph would be empty, which reads exactly like
      // "this code calls nothing" — so fail loudly instead.
      if entryPoints.nonEmpty && signatures.isEmpty then
        throw IllegalArgumentException(
          s"none of the ${entryPoints.size} entry points resolved against this classpath"
        )

      val graph =
        if signatures.isEmpty then algo.initialize()
        else algo.initialize(signatures.asJava)

      val id = f"cg_${(classpath.mkString + algorithm).hashCode.toLong & 0xffffffffL}%016x"
      CallGraphHandle(id, algorithm, view, graph, entryPoints)
    }.toEither.left.map(t => s"cannot build call graph: ${Option(t.getMessage).getOrElse(t.toString)}")

  /** Open a view over a classpath without building a call graph.
    *
    * Method-at-line and hierarchy questions need only the class files. Forcing a caller to
    * build a call graph first would cost minutes for a question answerable in milliseconds.
    */
  def openView(classpath: Vector[String], includeRuntime: Boolean = true): Either[String, JavaView] =
    Try {
      val inputs = new java.util.ArrayList[sootup.core.inputlocation.AnalysisInputLocation]()
      classpath.foreach(cp => inputs.add(JavaClassPathAnalysisInputLocation(cp)))
      if includeRuntime then inputs.add(DefaultRuntimeAnalysisInputLocation())
      JavaView(inputs)
    }.toEither.left.map(t => s"cannot open view: ${Option(t.getMessage).getOrElse(t.toString)}")

  /** Find the method signature matching a MethodRef.
    *
    * Matches on class and name, and on parameter count when the ref carries a descriptor —
    * overloads are common and picking the wrong one silently misdirects a whole path.
    */
  private[jvm] def resolve(view: JavaView, ref: MethodRef): Option[MethodSignature] =
    val candidates = view.getClasses.iterator().asScala
      .filter(_.getType.getFullyQualifiedName == ref.fqcn)
      .flatMap(_.getMethods.asScala)
      .map(_.getSignature)
      .filter(_.getName == ref.name)
      .toVector

    if candidates.isEmpty then None
    else if candidates.size == 1 then Some(candidates.head)
    else
      // With several overloads, guessing is worse than failing: the wrong `get` produces a
      // plausible path to the wrong code. Fall back to the first candidate ONLY when the
      // descriptor gives us nothing to discriminate on.
      descriptorParamCount(ref.descriptor) match
        case Some(n) => candidates.find(_.getParameterCount == n)
        case None    => Some(candidates.head)

  /** Count parameters in a method descriptor.
    *
    * TWO formats reach this. `toRef` emits SootUp's readable form —
    * `(java.lang.String,int)java.lang.String` — while anything derived from raw bytecode uses a
    * JVM descriptor, `(Ljava/lang/String;I)V`. Parsing only one of them made overload resolution
    * fall back to "first candidate", which silently picked the wrong `get` and made every
    * caller query for it come back empty. Distinguishable because a JVM descriptor never
    * contains a comma or a dot.
    */
  private[jvm] def descriptorParamCount(descriptor: String): Option[Int] =
    val open  = descriptor.indexOf('(')
    val close = descriptor.indexOf(')')
    if open < 0 || close < open then None
    else
      val params = descriptor.substring(open + 1, close)
      if params.isEmpty then Some(0)
      else if params.contains(',') || params.contains('.') then
        Some(params.split(",").count(_.trim.nonEmpty))
      else
        var i = 0
        var n = 0
        while i < params.length do
          params.charAt(i) match
            case '[' => i += 1
            case 'L' =>
              val end = params.indexOf(';', i)
              i = if end < 0 then params.length else end + 1
              n += 1
            case _ =>
              i += 1
              n += 1
        Some(n)

  private[jvm] def toRef(sig: MethodSignature): MethodRef =
    MethodRef(
      fqcn = sig.getDeclClassType.getFullyQualifiedName,
      name = sig.getName,
      descriptor = sig.getParameterTypes.asScala.map(_.toString).mkString("(", ",", ")") +
        sig.getType.toString
    )

  /** Direct callees of a method. */
  def callees(h: CallGraphHandle, method: MethodRef): Either[String, Vector[MethodRef]] =
    withSignature(h, method)(sig =>
      h.graph.callTargetsFrom(sig).asScala.toVector.map(toRef).distinct.sortBy(_.signature)
    )

  /** Transitive callers to a bounded depth.
    *
    * Also the blast-radius input for importance ranking: how much of the program can reach the
    * fault site.
    */
  def callers(
      h: CallGraphHandle,
      method: MethodRef,
      depth: Int = 3
  ): Either[String, Vector[(MethodRef, Int)]] =
    withSignature(h, method) { sig =>
      val seen = scala.collection.mutable.LinkedHashMap.empty[MethodSignature, Int]
      var frontier = Vector(sig)
      var d = 1
      while d <= depth && frontier.nonEmpty do
        val next = frontier.flatMap(s =>
          Try(h.graph.callSourcesTo(s).asScala.toVector).getOrElse(Vector.empty))
        // The next frontier is exactly what was NEWLY discovered at this depth. Filtering
        // against `seen` after recording into it would exclude everything just found, and the
        // search would stop at depth 1 while looking like it had run to `depth`.
        val newly = next.filter(s => s != sig && !seen.contains(s)).distinct
        newly.foreach(s => seen.update(s, d))
        frontier = newly
        d += 1
      seen.toVector.map((s, dep) => (toRef(s), dep))
    }

  /** Shortest call paths between two methods.
    *
    * An empty result is a real answer, not an error. Reflection, dependency injection, dynamic
    * proxies and native calls are all invisible to a static call graph, and a fault site that
    * stays unconnected is a genuine finding.
    */
  def paths(
      h: CallGraphHandle,
      from: MethodRef,
      to: MethodRef,
      maxLen: Int = 12,
      maxPaths: Int = 10
  ): Either[String, (Vector[Vector[MethodRef]], Boolean)] =
    for
      src <- resolve(h.view, from).toRight(s"cannot resolve ${from.signature}")
      dst <- resolve(h.view, to).toRight(s"cannot resolve ${to.signature}")
    yield
      val found = scala.collection.mutable.ArrayBuffer.empty[Vector[MethodSignature]]
      val visited = scala.collection.mutable.HashSet(src)
      var frontier = Vector(Vector(src))
      var exhausted = true

      var len = 0
      while len < maxLen && frontier.nonEmpty && found.size < maxPaths do
        val next = scala.collection.mutable.ArrayBuffer.empty[Vector[MethodSignature]]
        frontier.foreach { path =>
          if found.size < maxPaths then
            val targets = Try(h.graph.callTargetsFrom(path.last).asScala.toVector).getOrElse(Vector.empty)
            targets.foreach { t =>
              if t == dst then found += (path :+ t)
              else if !visited.contains(t) then
                visited.add(t)
                next += (path :+ t)
            }
        }
        if next.nonEmpty && len + 1 >= maxLen then exhausted = false
        frontier = next.toVector
        len += 1

      (found.toVector.map(_.map(toRef)), exhausted)

  /** A key that matches a method across descriptor dialects.
    *
    * Three producers, three dialects: JaCoCo reports `(Ljava/lang/String;)V`, SootUp reports
    * `(java.lang.String)void`, and a Java stack trace carries **no descriptor at all**. The
    * same method therefore has three different `descriptor` strings, so an executed set can
    * never be intersected with call-graph nodes by equality. Only class and name are common
    * to all three.
    *
    * Overloads collide under this key. That is a deliberate trade: a collision widens the
    * executed set slightly, whereas requiring descriptors would make the intersection empty
    * and silently demote every observed path to hypothesised.
    */
  private[jvm] def matchKey(m: MethodRef): (String, String) = (m.fqcn, m.name)

  /** A path confined to methods that ACTUALLY EXECUTED.
    *
    * This is the difference between "propagation was possible" and "propagation happened". A
    * hop that is statically plausible but absent from the trace does not belong in an observed
    * path, so it is not traversed at all.
    */
  def pathIn(
      h: CallGraphHandle,
      executed: Vector[MethodRef],
      from: MethodRef,
      to: MethodRef,
      maxLen: Int = 15
  ): Either[String, (Vector[MethodRef], Vector[MethodRef])] =
    val allowed = executed.map(matchKey).toSet

    for
      src <- resolve(h.view, from).toRight(s"cannot resolve ${from.signature}")
      dst <- resolve(h.view, to).toRight(s"cannot resolve ${to.signature}")
    yield
      val visited = scala.collection.mutable.HashSet(src)
      var frontier = Vector(Vector(src))
      var found: Option[Vector[MethodSignature]] = None
      var len = 0

      while len < maxLen && frontier.nonEmpty && found.isEmpty do
        val next = scala.collection.mutable.ArrayBuffer.empty[Vector[MethodSignature]]
        frontier.foreach { path =>
          if found.isEmpty then
            val targets = Try(h.graph.callTargetsFrom(path.last).asScala.toVector).getOrElse(Vector.empty)
            targets.foreach { t =>
              if t == dst then found = Some(path :+ t)
              else if !visited.contains(t) && allowed.contains(matchKey(toRef(t))) then
                visited.add(t)
                next += (path :+ t)
            }
        }
        frontier = next.toVector
        len += 1

      val path = found.map(_.map(toRef)).getOrElse(Vector.empty)
      // Hops on the path that the trace does NOT vouch for. Empty means every hop executed,
      // which is what licenses calling the path OBSERVED.
      val unvouched = path.filterNot(m => allowed.contains(matchKey(m)))
      (path, unvouched)

  /** Call chains from public entry points down to a target.
    *
    * This is what the reproducer synthesist needs: what to call, in what order, and with what
    * shape of argument, to get execution as far as the fault.
    */
  def chainsTo(
      h: CallGraphHandle,
      target: MethodRef,
      maxLen: Int = 10,
      maxChains: Int = 20
  ): Either[String, Vector[Vector[MethodRef]]] =
    withSignature(h, target) { sig =>
      val chains = scala.collection.mutable.ArrayBuffer.empty[Vector[MethodSignature]]
      val visited = scala.collection.mutable.HashSet(sig)
      var frontier = Vector(Vector(sig))
      var len = 0

      while len < maxLen && frontier.nonEmpty && chains.size < maxChains do
        val next = scala.collection.mutable.ArrayBuffer.empty[Vector[MethodSignature]]
        frontier.foreach { chain =>
          val sources = Try(h.graph.callSourcesTo(chain.head).asScala.toVector).getOrElse(Vector.empty)
          if sources.isEmpty && chain.size > 1 then chains += chain
          else
            sources.foreach { s =>
              if !visited.contains(s) then
                visited.add(s)
                next += (s +: chain)
            }
        }
        frontier = next.toVector
        len += 1

      // Anything still on the frontier is a chain we ran out of budget on; it is still a usable
      // entry point, so keep it rather than discarding partial work.
      (chains.toVector ++ frontier).take(maxChains).map(_.map(toRef))
    }

  private def withSignature[A](h: CallGraphHandle, m: MethodRef)(f: MethodSignature => A): Either[String, A] =
    resolve(h.view, m).toRight(s"cannot resolve ${m.signature} in this call graph").map(f)

  /** Declaration line range of a method, from the bytecode's line-number table.
    *
    * Returns None when the class was compiled without `-g`. That is the difference between
    * method-level and file-level path resolution, so it is worth surfacing rather than
    * silently degrading.
    */
  private[jvm] def lineRangeOf(view: JavaView, sig: MethodSignature): Option[(Int, Int)] =
    for
      cls    <- view.getClass(sig.getDeclClassType).toScala
      method <- cls.getMethod(sig.getSubSignature).toScala
      body   <- Try(method.getBody).toOption
      lines = body.getStmts.asScala.toVector
                .map(_.getPositionInfo.getStmtPosition.getFirstLine)
                .filter(_ > 0)
      if lines.nonEmpty
    yield (lines.min, lines.max)

  /** The method enclosing a given file and line.
    *
    * This is the linchpin joining git coordinates (file + line) to code coordinates (methods).
    * The source file is matched by suffix because bytecode records a bare file name and a
    * package, not the repository layout it came from.
    *
    * When several methods enclose the line — which happens with lambdas and nested classes
    * compiled into synthetic methods — the SMALLEST range wins, since that is the innermost
    * declaration.
    */
  def methodAtLine(h: CallGraphHandle, file: String, line: Int): Either[String, MethodRef] =
    methodAtLineIn(h.view, file, line, h.hasDebugInfo)

  def methodAtLineIn(
      view: JavaView,
      file: String,
      line: Int,
      hasDebugInfo: Boolean = true
  ): Either[String, MethodRef] =
    val wanted = file.replace(92.toChar, '/')

    val matches = view.getClasses.iterator().asScala.flatMap { cls =>
      val srcFile = Option(cls.getClassSource).flatMap(s => Try(cls.getType.getClassName + ".java").toOption)
      val pkg     = cls.getType.getPackageName.getName.replace('.', '/')
      val suffix  = if pkg.isEmpty then srcFile.getOrElse("") else s"$pkg/${srcFile.getOrElse("")}"

      if suffix.nonEmpty && !wanted.endsWith(suffix) then Iterator.empty
      else
        cls.getMethods.asScala.iterator.flatMap { m =>
          lineRangeOf(view, m.getSignature) match
            case Some((from, to)) if line >= from && line <= to =>
              Iterator.single((toRef(m.getSignature), to - from))
            case _ => Iterator.empty
        }
    }.toVector

    if matches.isEmpty then
      if !hasDebugInfo then
        Left(s"no line-number information in this build; cannot resolve $file:$line to a method")
      else Left(s"no method encloses $file:$line")
    else Right(matches.minBy(_._2)._1)

  /** Supertypes, subtypes and overrides of a type.
    *
    * Often the reason a path looks disconnected: the connection runs through an interface
    * rather than a direct call edge.
    */
  def classHierarchy(
      h: CallGraphHandle,
      fqcn: String
  ): Either[String, (Vector[String], Vector[String], Vector[MethodRef])] =
    classHierarchyIn(h.view, fqcn)

  def classHierarchyIn(
      view: JavaView,
      fqcn: String
  ): Either[String, (Vector[String], Vector[String], Vector[MethodRef])] =
    val all = view.getClasses.iterator().asScala.toVector
    all.find(_.getType.getFullyQualifiedName == fqcn) match
      case None => Left(s"no class '$fqcn' in this call graph")
      case Some(cls) =>
        val supers = (
          Option(cls.getSuperclass.orElse(null)).map(_.getFullyQualifiedName).toVector ++
            cls.getInterfaces.asScala.toVector.map(_.getFullyQualifiedName)
        ).distinct.sorted

        val subs = all
          .filter { c =>
            val parent = Option(c.getSuperclass.orElse(null)).map(_.getFullyQualifiedName)
            parent.contains(fqcn) || c.getInterfaces.asScala.exists(_.getFullyQualifiedName == fqcn)
          }
          .map(_.getType.getFullyQualifiedName).sorted

        val overrides = subs.flatMap { s =>
          all.find(_.getType.getFullyQualifiedName == s).toVector.flatMap(_.getMethods.asScala.toVector)
        }.map(m => toRef(m.getSignature)).distinct.sortBy(_.signature)

        Right((supers, subs, overrides))

  /** Declaration range and branch count of a method, without a call graph. */
  def methodShapeIn(view: JavaView, ref: MethodRef): Either[String, (Option[(Int, Int)], Int)] =
    resolve(view, ref).toRight(s"cannot resolve ${ref.signature}").map { sig =>
      val range = lineRangeOf(view, sig)
      val branches = (for
        cls    <- view.getClass(sig.getDeclClassType).toScala
        method <- cls.getMethod(sig.getSubSignature).toScala
        body   <- Try(method.getBody).toOption
      yield body.getStmts.asScala.count(_.branches())).getOrElse(0)
      (range, branches)
    }

  /** Bytecode-derived shape of a method: branch count and the methods it invokes. */
  def methodShape(
      h: CallGraphHandle,
      ref: MethodRef
  ): Either[String, (Option[(Int, Int)], Int, Vector[MethodRef])] =
    resolve(h.view, ref).toRight(s"cannot resolve ${ref.signature}").map { sig =>
      val range   = lineRangeOf(h.view, sig)
      val invoked = Try(h.graph.callTargetsFrom(sig).asScala.toVector.map(toRef)).getOrElse(Vector.empty)
      val branches = (for
        cls    <- h.view.getClass(sig.getDeclClassType).toScala
        method <- cls.getMethod(sig.getSubSignature).toScala
        body   <- Try(method.getBody).toOption
      yield body.getStmts.asScala.count(_.branches())).getOrElse(0)
      (range, branches, invoked.distinct.sortBy(_.signature))
    }
