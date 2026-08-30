package causeway.core

/** Which analysis pipeline a repository routes to. Decided once, at clone time, never revisited.
  * The JVM and LLVM pipelines share nothing but this decision.
  */
enum Ecosystem:
  case Jvm(buildTool: JvmBuildTool)
  case Llvm(buildTool: LlvmBuildTool)
  case Unsupported(detail: String)

enum JvmBuildTool:
  case Sbt, Maven, Gradle

enum LlvmBuildTool:
  case CMake, Bazel, Make

/** Why a build attempt failed.
  *
  * Not cosmetic. `JdkMismatch` is recoverable by choosing another container base image;
  * `DependencyResolution` on a decade-old commit usually is not. The distribution of these
  * across a run tells you whether the misses are the repository's fault or the environment's,
  * and therefore whether more provisioning effort would recover history.
  */
enum BuildFailureClass:
  case DependencyResolution, CompileError, JdkMismatch, Timeout, MissingToolchain

/** A method, as the bytecode sees it. */
final case class MethodRef(
    fqcn: String,
    name: String,
    descriptor: String,
    sourceFile: Option[String] = None,
    declStart: Option[Int] = None,
    declEnd: Option[Int] = None
):
  def signature: String = s"$fqcn.$name$descriptor"
  override def toString: String = signature

enum HunkStatus:
  case Added, Modified, Deleted, Renamed

/** One contiguous change within a file.
  *
  * `oldRange` is where the FAULT was; `newRange` is where the REPAIR is. They are different
  * types precisely so the two cannot be confused — see [[LineRange]].
  */
final case class Hunk(
    file: String,
    status: HunkStatus,
    oldPath: Option[String] = None,
    oldRange: Option[OldLines] = None,
    newRange: Option[NewLines] = None,
    addedLines: Vector[String] = Vector.empty,
    deletedLines: Vector[String] = Vector.empty
):
  def isRename: Boolean = status == HunkStatus.Renamed

enum CoverageStatus:
  case Covered, Uncovered, NotExecutable

/** How a bug's reproducer was obtained. Determines whether its path is observed or hypothesised. */
enum ReproducerTier:
  /** The project's own regression test reproduces it. Free, highest fidelity. */
  case Native
  /** A generated input differentiates parent from fix. */
  case Synthesized
  /** An input reaches the fault site but triggers no observable difference. */
  case Reached
  /** Nothing reproduced it; the path is static only. */
  case Static

/** How far from the symptom's own entry point the reproducer had to be placed. */
enum PathFidelity:
  case Full, Partial, Unit

/** What a path step is backed by. Never collapse these into one confidence number: the
  * distinction between "possible" and "observed" is the main reason this dataset is worth
  * building.
  */
enum PathTier:
  case Observed, Hypothesized, Disconnected

enum SymptomClass:
  case CrashUncaughtException, HangDeadlock, IncorrectOutput, DataCorruptionOrLoss,
    ResourceLeak, PerformanceDegradation, ConcurrencyRace, SecurityExposure,
    BuildOrStartupFailure, ApiContractViolation, Undetermined

enum Verdict:
  case BugFix, NotBugFix, Undecided

/** Which recall net matched a candidate. Nets are a recall device; all precision comes from
  * the adjudicator agent.
  */
enum CandidateNet:
  case Lexical, Structural, RandomControl

/** Kinds of agent note.
  *
  * Deliberately excludes anything claim-shaped. There is no `Fact` or `Finding` member: an
  * assertion about a bug goes through [[Finding]] with evidence, never through a note.
  */
enum NoteKind:
  case Environment, Convention, DeadEnd, Fixture
