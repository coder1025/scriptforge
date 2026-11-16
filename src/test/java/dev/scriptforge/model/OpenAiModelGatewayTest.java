package dev.scriptforge.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.scriptforge.tools.ToolCatalog;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class OpenAiModelGatewayTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private final AtomicReference<String> payload = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private int status = 200;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = payload.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    payload.set(response("tool_calls", "read_context", "{\"kind\":\"facts\",\"ids\":[]}"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ModelGateway gateway() {
    return new OpenAiModelGateway(
        mapper,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "test-secret",
        "test-model",
        Duration.ofSeconds(3));
  }

  private ModelGateway.ModelRequest request(String role) {
    return new ModelGateway.ModelRequest(
        role,
        List.of(Map.of("role", "user", "content", "demo")),
        ToolCatalog.forRole(role),
        500,
        Map.of("internalSecretShouldNeverBeSent", "PRIVATE"));
  }

  @Test
  void sendsRealProtocolAndPreservesUsageAndCallId() throws Exception {
    var result = gateway().complete(request("WRITER"));
    assertEquals("MODEL", result.provenance());
    assertEquals(80L, result.inputTokens());
    assertEquals("call-1", result.toolCalls().getFirst().id());
    var sent = mapper.readTree(requestBody.get());
    assertEquals("required", sent.path("tool_choice").asText());
    assertFalse(sent.path("parallel_tool_calls").asBoolean());
    assertEquals(6, sent.path("tools").size());
    assertFalse(requestBody.get().contains("PRIVATE"));
  }

  @Test
  void refusesTruncatedOutputWithoutExecutingPartialCall() {
    payload.set(response("length", "read_context", "{"));
    var failure =
        assertThrows(
            ModelGateway.ModelException.class, () -> gateway().complete(request("WRITER")));
    assertEquals("OUTPUT_TRUNCATED", failure.code());
  }

  @Test
  void reviewerCannotReceiveWriterMutationCall() {
    payload.set(response("tool_calls", "patch_script", "{}"));
    assertThrows(ModelGateway.ModelException.class, () -> gateway().complete(request("REVIEWER")));
  }

  @Test
  void httpFailureIsNotRetriedAndProviderBodyIsNotLeaked() {
    status = 429;
    payload.set("test-secret customer-private-data");
    var failure =
        assertThrows(
            ModelGateway.ModelException.class, () -> gateway().complete(request("WRITER")));
    assertEquals(1, calls.get());
    assertFalse(failure.getMessage().contains("test-secret"));
    assertFalse(failure.getMessage().contains("customer-private-data"));
  }

  @Test
  void boundsProviderResponseBeforeParsing() {
    payload.set("x".repeat(1_048_577));
    assertThrows(ModelGateway.ModelException.class, () -> gateway().complete(request("WRITER")));
  }

  @Test
  void missingUsageStaysUnknownInsteadOfZero() throws Exception {
    var tree =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(response("tool_calls", "read_context", "{}"));
    tree.remove("usage");
    payload.set(mapper.writeValueAsString(tree));
    var result = gateway().complete(request("WRITER"));
    assertNull(result.inputTokens());
    assertNull(result.outputTokens());
  }

  private String response(String finish, String name, String args) {
    try {
      return mapper.writeValueAsString(
          Map.of(
              "choices",
              List.of(
                  Map.of(
                      "finish_reason",
                      finish,
                      "message",
                      Map.of(
                          "role",
                          "assistant",
                          "tool_calls",
                          List.of(
                              Map.of(
                                  "id",
                                  "call-1",
                                  "type",
                                  "function",
                                  "function",
                                  Map.of("name", name, "arguments", args)))))),
              "usage",
              Map.of("prompt_tokens", 80, "completion_tokens", 20)));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}
