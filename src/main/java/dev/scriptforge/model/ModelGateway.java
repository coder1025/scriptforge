package dev.scriptforge.model;

import java.util.List;
import java.util.Map;

/** The model proposes actions. Only the host may execute them or advance task state. */
public interface ModelGateway {
  ModelReply complete(ModelRequest request);

  record ToolCall(String id, String name, String arguments) {}

  record ModelReply(
      String text,
      List<ToolCall> toolCalls,
      Long inputTokens,
      Long outputTokens,
      String provenance) {}

  record ModelRequest(
      String role,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxOutputTokens,
      Map<String, Object> state) {}

  /** A failure may already have incurred provider usage. The host must not blindly retry. */
  final class ModelException extends RuntimeException {
    private final String code;

    public ModelException(String code, String safeMessage) {
      super(safeMessage);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
