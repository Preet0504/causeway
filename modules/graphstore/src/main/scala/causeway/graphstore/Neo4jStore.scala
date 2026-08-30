package causeway.graphstore

import causeway.core.*
import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase, Session}

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class Neo4jConfig(uri: String, user: String, password: String)

object Neo4jConfig:
  /** Read from the environment. The Bolt port is NOT assumed to be 7687: this machine hosts
    * another project's Neo4j on the default ports, so causeway runs on 7690.
    */
  def fromEnv(): Neo4jConfig = Neo4jConfig(
    uri = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7690"),
    user = sys.env.getOrElse("NEO4J_USER", "neo4j"),
    password = sys.env.getOrElse("NEO4J_PASSWORD", "causeway-dev")
  )

final case class CheckpointRow(stage: String, done: Int, pending: Int)

/** A bug as persisted, including the ones that failed admission.
  *
  * `admitted = false` rows are the point, not noise: exporting only the admitted bugs would
  * hide the dataset's selection bias behind a clean-looking file (D2).
  */
final case class BugRow(
    repo: String,
    fixSha: String,
    admitted: Boolean,
    rejectedFor: Option[String],
    importance: Option[Double],
    scoredOn: Vector[String],
    reproducerTier: Option[String],
    pathFidelity: Option[String]
)

final case class FindingRow(
    id: String,
    agent: String,
    claimType: String,
    subject: String,
    confidence: Double,
    claim: String,
    gaps: Vector[String],
    evidenceCount: Int
)

/** Persistence for evidence, findings, bugs, and run checkpoints.
  *
  * Every write is an idempotent `MERGE` on a natural key, so a resumed run overwrites rather
  * than duplicating — which matters because runs are expected to be interrupted by session caps
  * and continued later.
  */
final class Neo4jStore(driver: Driver):

  private def write[A](f: Session => A): A =
    Using.resource(driver.session())(s => s.executeWrite(tx => f(tx.asInstanceOf[Session])))

  private def session[A](f: Session => A): A = Using.resource(driver.session())(f)

  /** Constraints and indexes. Safe to run on every startup. */
  def initialise(): Unit = session { s =>
    val stmts = Vector(
      "CREATE CONSTRAINT evidence_id IF NOT EXISTS FOR (e:Evidence) REQUIRE e.id IS UNIQUE",
      "CREATE CONSTRAINT finding_id IF NOT EXISTS FOR (f:Finding) REQUIRE f.id IS UNIQUE",
      "CREATE CONSTRAINT repo_slug IF NOT EXISTS FOR (r:Repository) REQUIRE r.slug IS UNIQUE",
      "CREATE CONSTRAINT commit_key IF NOT EXISTS FOR (c:Commit) REQUIRE (c.repo, c.sha) IS UNIQUE",
      "CREATE CONSTRAINT bug_key IF NOT EXISTS FOR (b:Bug) REQUIRE (b.repo, b.fixSha) IS UNIQUE",
      "CREATE CONSTRAINT run_id IF NOT EXISTS FOR (r:Run) REQUIRE r.id IS UNIQUE",
      "CREATE INDEX checkpoint_key IF NOT EXISTS FOR (c:Checkpoint) ON (c.runId, c.stage)"
    )
    stmts.foreach(q => s.executeWrite(tx => tx.run(q).consume()))
  }

  // ── evidence ───────────────────────────────────────────────────────────

  def putEvidence(e: Evidence): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run(
        """MERGE (e:Evidence {id: $id})
          |SET e.tool = $tool, e.argsHash = $argsHash, e.payloadHash = $payloadHash,
          |    e.at = $at, e.runId = $runId, e.provenance = $provenance""".stripMargin,
        Map[String, Object](
          "id" -> e.id.value,
          "tool" -> e.tool,
          "argsHash" -> e.argsHash,
          "payloadHash" -> e.payloadHash,
          "at" -> e.at.toString,
          "runId" -> e.runId.value,
          "provenance" -> e.provenance.toString
        ).asJava
      ).consume()
    }
  }

  def hasEvidence(id: EvidenceId): Boolean = session { s =>
    s.executeRead { tx =>
      tx.run("MATCH (e:Evidence {id: $id}) RETURN count(e) AS n",
        Map[String, Object]("id" -> id.value).asJava).single().get("n").asInt() > 0
    }
  }

  def evidenceCount(runId: RunId): Int = session { s =>
    s.executeRead { tx =>
      tx.run("MATCH (e:Evidence {runId: $r}) RETURN count(e) AS n",
        Map[String, Object]("r" -> runId.value).asJava).single().get("n").asInt()
    }
  }

  // ── findings ───────────────────────────────────────────────────────────

  /** Persist a finding and link it to every piece of evidence it cites.
    *
    * The `SUPPORTED_BY` edges are the audit property: any claim in the graph can be walked back
    * to the tool call that produced it.
    */
  def putFinding(
      id: String,
      runId: RunId,
      agent: String,
      claimType: String,
      subject: String,
      confidence: Double,
      claimJson: String,
      evidence: Vector[EvidenceId],
      gaps: Vector[(UnknownReason, String)]
  ): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run(
        """MERGE (f:Finding {id: $id})
          |SET f.runId = $runId, f.agent = $agent, f.claimType = $claimType,
          |    f.subject = $subject, f.confidence = $confidence, f.claim = $claim,
          |    f.gaps = $gaps
          |WITH f
          |UNWIND $evidence AS evId
          |MATCH (e:Evidence {id: evId})
          |MERGE (f)-[:SUPPORTED_BY]->(e)""".stripMargin,
        Map[String, Object](
          "id" -> id,
          "runId" -> runId.value,
          "agent" -> agent,
          "claimType" -> claimType,
          "subject" -> subject,
          "confidence" -> Double.box(confidence),
          "claim" -> claimJson,
          // Gaps are stored alongside the claim, not discarded: a low-confidence finding with
          // stated gaps is useful data, one that merely looks well-evidenced is corrosive.
          "gaps" -> gaps.map((r, d) => s"$r: $d").asJava,
          "evidence" -> evidence.map(_.value).asJava
        ).asJava
      ).consume()
    }
  }

  def findingEvidenceCount(findingId: String): Int = session { s =>
    s.executeRead { tx =>
      tx.run("MATCH (:Finding {id: $id})-[:SUPPORTED_BY]->(e:Evidence) RETURN count(e) AS n",
        Map[String, Object]("id" -> findingId).asJava).single().get("n").asInt()
    }
  }

  // ── bugs ───────────────────────────────────────────────────────────────

  /** Upsert a bug. Bugs failing admission are stored with `admitted = false` and a reason —
    * never deleted (D2), or the dataset's selection bias becomes invisible.
    */
  def putBug(
      repo: String,
      fixSha: String,
      admitted: Boolean,
      rejectedFor: Option[String],
      importance: Option[Double],
      scoredOn: Vector[String],
      reproducerTier: Option[String],
      pathFidelity: Option[String]
  ): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run(
        """MERGE (r:Repository {slug: $repo})
          |MERGE (c:Commit {repo: $repo, sha: $fixSha})
          |MERGE (r)-[:HAS_COMMIT]->(c)
          |MERGE (b:Bug {repo: $repo, fixSha: $fixSha})
          |SET b.admitted = $admitted, b.rejectedFor = $rejectedFor,
          |    b.importance = $importance, b.scoredOn = $scoredOn,
          |    b.reproducerTier = $tier, b.pathFidelity = $fidelity
          |MERGE (b)-[:FIXED_BY]->(c)""".stripMargin,
        Map[String, Object](
          "repo" -> repo,
          "fixSha" -> fixSha,
          "admitted" -> Boolean.box(admitted),
          "rejectedFor" -> rejectedFor.orNull,
          "importance" -> importance.map(Double.box).orNull,
          "scoredOn" -> scoredOn.asJava,
          "tier" -> reproducerTier.orNull,
          "fidelity" -> pathFidelity.orNull
        ).asJava
      ).consume()
    }
  }

  def bugCount(repo: String, admittedOnly: Boolean = false): Int = session { s =>
    val q =
      if admittedOnly then "MATCH (b:Bug {repo: $repo, admitted: true}) RETURN count(b) AS n"
      else "MATCH (b:Bug {repo: $repo}) RETURN count(b) AS n"
    s.executeRead(tx => tx.run(q, Map[String, Object]("repo" -> repo).asJava).single().get("n").asInt())
  }

  /** Every bug recorded for a repository, admitted or not. Ordered so an export is stable. */
  def bugsForRepo(repo: String): Vector[BugRow] = session { s =>
    s.executeRead { tx =>
      tx.run(
        """MATCH (b:Bug {repo: $repo})
          |RETURN b.fixSha AS fixSha, b.admitted AS admitted, b.rejectedFor AS rejectedFor,
          |       b.importance AS importance, b.scoredOn AS scoredOn,
          |       b.reproducerTier AS tier, b.pathFidelity AS fidelity
          |ORDER BY b.importance DESC, b.fixSha""".stripMargin,
        Map[String, Object]("repo" -> repo).asJava
      ).list().asScala.toVector.map { r =>
        def opt(k: String) = Option(r.get(k)).filterNot(_.isNull).map(_.asString())
        BugRow(
          repo = repo,
          fixSha = r.get("fixSha").asString(),
          admitted = r.get("admitted").asBoolean(false),
          rejectedFor = opt("rejectedFor"),
          importance = Option(r.get("importance")).filterNot(_.isNull).map(_.asDouble()),
          scoredOn = Option(r.get("scoredOn")).filterNot(_.isNull)
            .map(_.asList(_.asString()).asScala.toVector).getOrElse(Vector.empty),
          reproducerTier = opt("tier"),
          pathFidelity = opt("fidelity")
        )
      }
    }
  }

  /** Move every Evidence row from one run id to another, returning how many moved. */
  def reattributeEvidence(from: RunId, to: RunId): Int = session { s =>
    s.executeWrite { tx =>
      tx.run(
        """MATCH (e:Evidence {runId: $from})
          |SET e.runId = $to
          |RETURN count(e) AS n""".stripMargin,
        Map[String, Object]("from" -> from.value, "to" -> to.value).asJava
      ).single().get("n").asInt()
    }
  }

  def repoForRun(runId: RunId): Option[String] = session { s =>
    s.executeRead { tx =>
      tx.run("MATCH (r:Run {id: $id}) RETURN r.repo AS repo",
        Map[String, Object]("id" -> runId.value).asJava
      ).list().asScala.headOption.map(_.get("repo").asString())
    }
  }

  /** Findings recorded by a run, with the size of the evidence behind each. */
  def findingsForRun(runId: RunId): Vector[FindingRow] = session { s =>
    s.executeRead { tx =>
      tx.run(
        """MATCH (f:Finding {runId: $id})
          |OPTIONAL MATCH (f)-[:SUPPORTED_BY]->(e:Evidence)
          |RETURN f.id AS id, f.agent AS agent, f.claimType AS claimType, f.subject AS subject,
          |       f.confidence AS confidence, f.claim AS claim, f.gaps AS gaps,
          |       count(e) AS evidenceCount
          |ORDER BY f.id""".stripMargin,
        Map[String, Object]("id" -> runId.value).asJava
      ).list().asScala.toVector.map { r =>
        FindingRow(
          id = r.get("id").asString(),
          agent = r.get("agent").asString(""),
          claimType = r.get("claimType").asString(""),
          subject = r.get("subject").asString(""),
          confidence = Option(r.get("confidence")).filterNot(_.isNull).map(_.asDouble()).getOrElse(0.0),
          claim = r.get("claim").asString(""),
          gaps = Option(r.get("gaps")).filterNot(_.isNull)
            .map(_.asList(_.asString()).asScala.toVector).getOrElse(Vector.empty),
          evidenceCount = r.get("evidenceCount").asInt(0)
        )
      }
    }
  }

  /** The Unknown breakdown — the dataset's honesty report. */
  def rejectionBreakdown(repo: String): Map[String, Int] = session { s =>
    s.executeRead { tx =>
      tx.run(
        """MATCH (b:Bug {repo: $repo}) WHERE b.admitted = false
          |RETURN b.rejectedFor AS reason, count(b) AS n""".stripMargin,
        Map[String, Object]("repo" -> repo).asJava
      ).list().asScala.map(r => Option(r.get("reason").asString(null)).getOrElse("unspecified") -> r.get("n").asInt()).toMap
    }
  }

  // ── checkpoints ────────────────────────────────────────────────────────

  /** Associate a run with the repository it is mining.
    *
    * Without this, `resumeState` could only answer "how far did run X get" — but an agent
    * starting work does not know a PRIOR run's id. The question it actually needs answered is
    * "is there an unfinished run for this repository", which requires the link.
    */
  def putRun(runId: RunId, repo: String): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run(
        """MERGE (r:Run {id: $id})
          |SET r.repo = $repo, r.lastSeen = $at""".stripMargin,
        Map[String, Object]("id" -> runId.value, "repo" -> repo, "at" -> Instant.now().toString).asJava
      ).consume()
    }
  }

  /** The most recently active run for a repository, if any. */
  def latestRunFor(repo: String): Option[RunId] = session { s =>
    s.executeRead { tx =>
      tx.run(
        "MATCH (r:Run {repo: $repo}) RETURN r.id AS id ORDER BY r.lastSeen DESC LIMIT 1",
        Map[String, Object]("repo" -> repo).asJava
      ).list().asScala.headOption.flatMap(r => RunId(r.get("id").asString()).toOption)
    }
  }

  /** Record progress through a stage, optionally superseding earlier attempts at that stage.
    *
    * `supersedes` exists because checkpoints MERGE on (runId, stage, key), so a retry that
    * chooses a DIFFERENT key leaves the old row behind — and `resumeState` counts every non-DONE
    * row as outstanding work. A real run hit this: S6 failed under key `all_19`, succeeded on
    * retry under key `all`, and the stage read `done=1, pending=1` forever. Every subsequent
    * resume re-probed 37 revisions that had already passed.
    *
    * Superseded rows are MARKED, never deleted. The failed attempt is a true record of what
    * happened to this run and belongs in the research output (D2); it simply stops counting as
    * work still to do.
    *
    * Explicit rather than inferred. A stage may legitimately carry one key per bug, with some
    * DONE and some FAILED — automatically superseding the failures because a sibling succeeded
    * would hide exactly the outcomes this project exists to record. The caller names what it is
    * replacing, the same way `notes_write` does.
    */
  def checkpoint(
      runId: RunId,
      stage: String,
      key: String,
      status: String,
      detail: String = "",
      supersedes: Vector[String] = Vector.empty
  ): Int =
    session { s =>
      s.executeWrite { tx =>
        tx.run(
          """MERGE (c:Checkpoint {runId: $runId, stage: $stage, key: $key})
            |SET c.status = $status, c.detail = $detail, c.at = $at,
            |    c.superseded = false""".stripMargin,
          Map[String, Object](
            "runId" -> runId.value, "stage" -> stage, "key" -> key,
            "status" -> status, "detail" -> detail, "at" -> Instant.now().toString
          ).asJava
        ).consume()

        if supersedes.isEmpty then 0
        else
          // Same stage only. Superseding across stages would let a later stage erase an earlier
          // one's outstanding work, which is the opposite of what a resume needs to know.
          tx.run(
            """MATCH (c:Checkpoint {runId: $runId, stage: $stage})
              |WHERE c.key IN $keys AND c.key <> $key
              |SET c.superseded = true
              |RETURN count(c) AS n""".stripMargin,
            Map[String, Object](
              "runId" -> runId.value, "stage" -> stage, "key" -> key, "keys" -> supersedes.asJava
            ).asJava
          ).single().get("n").asInt()
      }
    }

  def isDone(runId: RunId, stage: String, key: String): Boolean = session { s =>
    s.executeRead { tx =>
      tx.run(
        "MATCH (c:Checkpoint {runId: $runId, stage: $stage, key: $key}) RETURN c.status AS s",
        Map[String, Object]("runId" -> runId.value, "stage" -> stage, "key" -> key).asJava
      ).list().asScala.headOption.exists(_.get("s").asString() == "DONE")
    }
  }

  def resumeState(runId: RunId): Vector[CheckpointRow] = session { s =>
    s.executeRead { tx =>
      tx.run(
        """MATCH (c:Checkpoint {runId: $runId})
          |WHERE coalesce(c.superseded, false) = false
          |RETURN c.stage AS stage,
          |       sum(CASE WHEN c.status = 'DONE' THEN 1 ELSE 0 END) AS done,
          |       sum(CASE WHEN c.status <> 'DONE' THEN 1 ELSE 0 END) AS pending
          |ORDER BY stage""".stripMargin,
        Map[String, Object]("runId" -> runId.value).asJava
      ).list().asScala.toVector
        .map(r => CheckpointRow(r.get("stage").asString(), r.get("done").asInt(), r.get("pending").asInt()))
    }
  }

  /** Remove everything written by one run. Used by tests to stay isolated on a shared instance. */
  def purgeRun(runId: RunId): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run("MATCH (n) WHERE n.runId = $r DETACH DELETE n",
        Map[String, Object]("r" -> runId.value).asJava).consume()
    }
  }

  def purgeRepo(repo: String): Unit = session { s =>
    s.executeWrite { tx =>
      tx.run("MATCH (n) WHERE n.repo = $repo OR n.slug = $repo DETACH DELETE n",
        Map[String, Object]("repo" -> repo).asJava).consume()
    }
  }

  def close(): Unit = driver.close()

object Neo4jStore:

  def connect(cfg: Neo4jConfig = Neo4jConfig.fromEnv()): Either[String, Neo4jStore] =
    Try {
      val d = GraphDatabase.driver(cfg.uri, AuthTokens.basic(cfg.user, cfg.password))
      d.verifyConnectivity()
      Neo4jStore(d)
    }.toEither.left.map(t => s"cannot connect to ${cfg.uri}: ${t.getMessage}")

  /** Connect, waiting for the database to come up.
    *
    * A single attempt at startup loses a RACE that happens routinely: the host reboots, Docker
    * starts the container, and the MCP client starts this server the moment a session opens —
    * well before Neo4j reports healthy. One attempt then fails, the server falls back to an
    * in-memory ledger for the life of the process, and every evidenceId it issues dies with it.
    *
    * That is not hypothetical. On 2026-08-24 a server started after a reboot ran for hours and
    * persisted nothing: notes reached the filesystem, so the run LOOKED alive, while zero
    * evidence rows were written. The failure is silent by construction, because the fallback is
    * a legitimate mode for a machine with no database at all.
    *
    * Waiting is nearly free when the database is up — the first attempt succeeds — and cheap
    * insurance when it is not.
    */
  def connectWaiting(
      cfg: Neo4jConfig = Neo4jConfig.fromEnv(),
      attempts: Int = 10,
      delay: java.time.Duration = java.time.Duration.ofSeconds(3),
      onRetry: (Int, String) => Unit = (_, _) => ()
  ): Either[String, Neo4jStore] =
    @annotation.tailrec
    def go(n: Int): Either[String, Neo4jStore] =
      connect(cfg) match
        case Right(s) => Right(s)
        case Left(err) if n >= attempts => Left(s"$err (after $attempts attempts)")
        case Left(err) =>
          onRetry(n, err)
          Thread.sleep(delay.toMillis)
          go(n + 1)
    go(1)

/** An [[EvidenceLedger]] backed by Neo4j, so the integrity check survives a restart.
  *
  * A small in-memory cache fronts it because `Finding` construction validates every cited id,
  * and a run makes far more validations than it does distinct issuances.
  */
final class Neo4jEvidenceLedger(store: Neo4jStore) extends EvidenceLedger:
  private val seen = scala.collection.mutable.HashSet.empty[String]

  def issue(e: Evidence): Unit =
    store.putEvidence(e)
    seen.add(e.id.value)

  override def reattribute(from: RunId, to: RunId): Int =
    if from == to then 0 else store.reattributeEvidence(from, to)

  def contains(id: EvidenceId): Boolean =
    seen.contains(id.value) || {
      val there = store.hasEvidence(id)
      if there then seen.add(id.value)
      there
    }

  def get(id: EvidenceId): Option[Evidence] = None // not needed by the enforcement path

  def size: Int = seen.size
