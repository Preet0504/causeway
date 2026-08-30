package causeway.app

import causeway.core.{InMemoryEvidenceLedger, RunId}
import causeway.mcp.{CausewayServer, Json, SchemaCatalog, ToolRegistry}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.io.{BufferedReader, InputStreamReader, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Executors, TimeUnit}

/** Drives the real MCP stdio transport in-process over piped streams.
  *
  * Everything else is tested at the registry or bridge level; this is the only test that
  * exercises the SDK's protocol handling — JSON-RPC framing, `initialize`, `tools/list` and
  * `tools/call` — end to end. A subprocess would prove the same thing but would need a
  * packaged jar and would be far harder to diagnose when it broke.
  */
class TransportSpec extends AnyFunSuite with Matchers:

  private val Timeout = 30

  private def schemas: Path =
    Vector(Paths.get("schemas"), Paths.get("..", "..", "schemas"))
      .find(Files.isDirectory(_))
      .getOrElse(sys.error("schemas/ not found"))

  /** Starts a server on piped streams and gives back a request/response pair. */
  private def withServer[A](f: (String => Unit, () => JsonNode) => A): A =
    val toServer   = PipedOutputStream()
    val serverIn   = PipedInputStream(toServer, 1 << 16)
    val serverOut  = PipedOutputStream()
    val fromServer = PipedInputStream(serverOut, 1 << 20)

    val handles = Handles(Files.createTempDirectory("causeway-transport-ws"))
    val specs   = SchemaCatalog.load(schemas).toOption.get
    val registry = ToolRegistry
      .build(specs, VcsHandlers.all(handles), InMemoryEvidenceLedger(),
        RunId.unsafe("run_0123456789abcdef"))
      .toOption.get

    val config = CausewayServer.Config(schemas, RunId.unsafe("run_0123456789abcdef"))
    val server = CausewayServer.buildServer(registry, config, serverIn, serverOut)

    val reader = BufferedReader(InputStreamReader(fromServer, StandardCharsets.UTF_8))
    val pool   = Executors.newSingleThreadExecutor()

    def send(line: String): Unit =
      toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8))
      toServer.flush()

    /** Read the next JSON-RPC frame, ignoring anything that is not one. */
    def receive(): JsonNode =
      val task = pool.submit(() => {
        var node: JsonNode = null
        while node == null do
          val line = reader.readLine()
          if line == null then throw IllegalStateException("transport closed")
          val t = line.trim
          if t.startsWith("{") then node = Json.parse(t)
        node
      })
      task.get(Timeout.toLong, TimeUnit.SECONDS)

    try f(send, () => receive())
    finally
      pool.shutdownNow()
      scala.util.Try(server.close())
      scala.util.Try(toServer.close())
      handles.closeAll()

  private def initialise(send: String => Unit, recv: () => JsonNode): JsonNode =
    send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
      """"capabilities":{},"clientInfo":{"name":"causeway-test","version":"1.0"}}}""")
    val response = recv()
    send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    response

  test("the server completes an MCP initialize handshake"):
    withServer { (send, recv) =>
      val res = initialise(send, recv)
      res.get("id").intValue() shouldBe 1
      val info = res.get("result").get("serverInfo")
      info.get("name").stringValue() shouldBe "causeway"
      res.get("result").get("capabilities").has("tools") shouldBe true
    }

  test("tools/list advertises exactly the tools that have handlers"):
    withServer { (send, recv) =>
      initialise(send, recv)
      send("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
      val res = recv()

      res.get("id").intValue() shouldBe 2
      val tools = res.get("result").get("tools")
      val names = (0 until tools.size()).map(i => tools.get(i).get("name").stringValue()).toSet

      names should contain("history_diff")
      names should contain("repo_clone")
      names should contain("history_candidates")
      // 47 tools are catalogued but only the vcs handlers are wired, and the protocol must
      // advertise capabilities that exist rather than the whole catalog.
      names.size shouldBe 8
      names should not contain "jvm_harness_run"
    }

  test("an advertised tool carries its input schema"):
    withServer { (send, recv) =>
      initialise(send, recv)
      send("""{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}""")
      val tools = recv().get("result").get("tools")
      val diff = (0 until tools.size()).map(tools.get)
        .find(_.get("name").stringValue() == "history_diff").get

      diff.get("description").stringValue() should include("rename detection")
      val schema = diff.get("inputSchema")
      schema.get("required").toString should include("sha")
      schema.get("properties").has("repoHandle") shouldBe true
    }

  test("tools/call reaches a handler and returns the envelope"):
    withServer { (send, recv) =>
      initialise(send, recv)
      // An unregistered handle: the point is that the call is dispatched and answered, and that
      // an Unknown comes back as a successful, citable result rather than a protocol error.
      send("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"history_commit_meta",""" +
        """"arguments":{"repoHandle":"repo_ffffffffffffffff","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}""")
      val res = recv()

      res.get("id").intValue() shouldBe 4
      val result = res.get("result")
      result.get("isError").booleanValue() shouldBe false

      val text = result.get("content").get(0).get("text").stringValue()
      val body = Json.parse(text)
      body.get("ok").booleanValue() shouldBe true
      body.get("evidenceId").stringValue() should startWith("ev_")
      body.get("unknown").get("reason").stringValue() shouldBe "NotApplicable"
    }

  test("a schema violation comes back as a tool error, not a transport failure"):
    withServer { (send, recv) =>
      initialise(send, recv)
      send("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"history_diff",""" +
        """"arguments":{"repoHandle":"repo_ffffffffffffffff","sha":"abbrev"}}}""")
      val res = recv()

      res.get("id").intValue() shouldBe 5
      // The protocol call succeeds; the TOOL reports the error. A transport-level failure would
      // give the agent nothing to act on.
      res.has("error") shouldBe false
      res.get("result").get("isError").booleanValue() shouldBe true

      // And the error is OURS, in the structured shape every failure uses. The SDK validates
      // inputs by default against the same schema; that is switched off so an agent never has
      // to parse two different error formats for one class of problem.
      val text = res.get("result").get("content").get(0).get("text").stringValue()
      val body = Json.parse(text)
      body.get("ok").booleanValue() shouldBe false
      body.get("error").stringValue() shouldBe "InvalidInput"
      body.get("detail").stringValue() should include("sha")
    }

  test("a refused agent and a malformed argument report in the same shape"):
    withServer { (send, recv) =>
      initialise(send, recv)
      // Malformed argument
      send("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"history_diff",""" +
        """"arguments":{"repoHandle":"repo_ffffffffffffffff","sha":"nope"}}}""")
      val malformed = Json.parse(recv().get("result").get("content").get(0).get("text").stringValue())

      // Refused agent
      send("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"history_diff",""" +
        """"arguments":{"repoHandle":"repo_ffffffffffffffff",""" +
        """"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","_agent":"symptom-characterizer"}}}""")
      val refused = Json.parse(recv().get("result").get("content").get(0).get("text").stringValue())

      // Same keys, different error — which is what lets an agent branch on the outcome.
      malformed.get("error").stringValue() shouldBe "InvalidInput"
      refused.get("error").stringValue() shouldBe "NotPermittedForAgent"
      Vector(malformed, refused).foreach { b =>
        b.get("ok").booleanValue() shouldBe false
        b.has("detail") shouldBe true
      }
    }
