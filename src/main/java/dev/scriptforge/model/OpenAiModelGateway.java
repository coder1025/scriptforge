package dev.scriptforge.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scriptforge.tools.ToolCatalog;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** OpenAI-compatible Chat Completions tool protocol; no provider SDK or hidden retry. */
public final class OpenAiModelGateway implements ModelGateway {
  private static final int RESPONSE_LIMIT = 1_048_576;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final URI endpoint;
  private final String apiKey;
  private final String model;
  private final Duration timeout;

  public OpenAiModelGateway(
      ObjectMapper mapper, String baseUrl, String apiKey, String model, Duration timeout) {
    this.mapper = mapper;
    if (baseUrl == null || apiKey == null || apiKey.isBlank() || model == null || model.isBlank())
      throw new IllegalArgumentException("MODEL mode requires base URL, API key and model");
    var base = URI.create(baseUrl.replaceAll("/+$", ""));
    if (base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null)
      throw new IllegalArgumentException(
          "Model base URL must not contain user info, query or fragment");
    boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost());
    if (!("https".equals(base.getScheme()) || local && "http".equals(base.getScheme())))
      throw new IllegalArgumentException("Use HTTPS for remote model endpoints");
    this.endpoint = URI.create(base + "/chat/completions");
    this.apiKey = apiKey;
    this.model = model;
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public ModelReply complete(ModelRequest request) {
    try {
      var body = new LinkedHashMap<String, Object>();
      body.put("model", model);
      body.put("messages", request.messages());
      body.put("tools", request.tools());
      body.put("tool_choice", "required");
      body.put("parallel_tool_calls", false);
      body.put("max_completion_tokens", request.maxOutputTokens());
      var httpRequest =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(body), StandardCharsets.UTF_8))
              .build();
      var future = client.sendAsync(httpRequest, info -> new LimitedBodySubscriber(RESPONSE_LIMIT));
      HttpResponse<byte[]> response;
      try {
        response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException ex) {
        future.cancel(true);
        throw new ModelException(
            "TIMEOUT", "Model request timed out; provider usage is unknown. No automatic retry.");
      }
      if (response.statusCode() != 200)
        throw new ModelException(
            "PROVIDER_HTTP_" + response.statusCode(),
            "Model provider returned HTTP "
                + response.statusCode()
                + ". Check endpoint/model configuration; response body omitted.");
      JsonNode root = mapper.readTree(response.body());
      JsonNode choices = root.path("choices");
      if (!choices.isArray() || choices.size() != 1)
        throw invalid("Expected exactly one model choice");
      JsonNode choice = choices.get(0);
      if ("length".equals(choice.path("finish_reason").asText()))
        throw new ModelException(
            "OUTPUT_TRUNCATED",
            "Model output reached its limit; no partial tool call was executed.");
      if ("content_filter".equals(choice.path("finish_reason").asText()))
        throw invalid("Provider filtered model output");
      JsonNode message = choice.path("message");
      if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull())
        throw invalid("Model refused the request");
      JsonNode calls = message.path("tool_calls");
      if (!calls.isArray() || calls.size() != 1)
        throw invalid("Expected one complete tool call per turn");
      JsonNode call = calls.get(0);
      String id = call.path("id").asText("");
      String name = call.path("function").path("name").asText("");
      JsonNode argumentsNode = call.path("function").path("arguments");
      if (id.isBlank()
          || id.length() > 200
          || !"function".equals(call.path("type").asText())
          || !ToolCatalog.allowed(request.role(), name)
          || !argumentsNode.isTextual()) throw invalid("Invalid or unauthorized tool call");
      String arguments = argumentsNode.textValue();
      if (arguments.length() > 100_000 || !mapper.readTree(arguments).isObject())
        throw invalid("Tool arguments must be a bounded JSON object");
      JsonNode usage = root.path("usage");
      return new ModelReply(
          message.path("content").isTextual() ? message.path("content").textValue() : "",
          List.of(new ToolCall(id, name, arguments)),
          tokenCount(usage, "prompt_tokens"),
          tokenCount(usage, "completion_tokens"),
          "MODEL");
    } catch (ModelException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ModelException(
          "INTERRUPTED", "Model call interrupted; provider usage may be unknown.");
    } catch (Exception ex) {
      // Do not persist provider bodies, authorization headers or endpoint query values in
      // user-visible errors.
      throw new ModelException(
          "PROTOCOL_OR_TRANSPORT_ERROR",
          "Model response could not be accepted. Usage may be unknown; inspect provider configuration.");
    }
  }

  private static Long tokenCount(JsonNode usage, String key) {
    JsonNode n = usage.path(key);
    return n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0
        ? n.longValue()
        : null;
  }

  private static ModelException invalid(String message) {
    return new ModelException("INVALID_MODEL_REPLY", message);
  }

  /** Bound bytes before buffering the response, rather than checking after a large allocation. */
  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int max;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int max) {
      this.max = max;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer b : buffers) {
        if (b.remaining() > max - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IllegalStateException("Model response too large"));
          return;
        }
        byte[] chunk = new byte[b.remaining()];
        b.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable t) {
      result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
