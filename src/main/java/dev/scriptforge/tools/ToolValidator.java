package dev.scriptforge.tools;

import static dev.scriptforge.domain.ForgeException.require;

import com.fasterxml.jackson.databind.JsonNode;
import dev.scriptforge.domain.Json;
import org.springframework.stereotype.Component;

/** Validates the JSON Schema subset used by our closed tool catalog, before any side effects. */
@Component
public class ToolValidator {
  private final Json json;

  public ToolValidator(Json json) {
    this.json = json;
  }

  public JsonNode validate(String role, String name, String arguments) {
    require(
        arguments != null && arguments.length() <= 100000,
        "INVALID_ARGUMENT",
        "Tool arguments too large");
    require(ToolCatalog.allowed(role, name), "FORBIDDEN", "Tool not available to this role");
    var definition =
        ToolCatalog.forRole(role).stream()
            .map(json::tree)
            .map(t -> t.path("function"))
            .filter(t -> name.equals(t.path("name").asText()))
            .findFirst()
            .orElseThrow();
    JsonNode value = json.tree(arguments);
    validateNode(value, definition.path("parameters"), "$");
    return value;
  }

  private void validateNode(JsonNode value, JsonNode schema, String path) {
    String type = schema.path("type").asText();
    boolean ok =
        switch (type) {
          case "object" -> value.isObject();
          case "array" -> value.isArray();
          case "string" -> value.isTextual();
          case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
          case "number" -> value.isNumber();
          case "boolean" -> value.isBoolean();
          default -> false;
        };
    require(ok, "INVALID_ARGUMENT", path + " must be " + type);
    if (schema.has("enum")) {
      boolean found = false;
      for (var e : schema.path("enum")) if (e.equals(value)) found = true;
      require(found, "INVALID_ARGUMENT", path + " has an unsupported value");
    }
    if (value.isObject()) {
      for (var required : schema.path("required"))
        require(
            value.has(required.asText()),
            "INVALID_ARGUMENT",
            path + " missing " + required.asText());
      var fields = value.fields();
      while (fields.hasNext()) {
        var f = fields.next();
        require(
            schema.path("properties").has(f.getKey()),
            "INVALID_ARGUMENT",
            path + " unknown field " + f.getKey());
        validateNode(
            f.getValue(), schema.path("properties").path(f.getKey()), path + "." + f.getKey());
      }
    }
    if (value.isArray()) {
      require(
          value.size() >= schema.path("minItems").asInt(0)
              && value.size() <= schema.path("maxItems").asInt(100),
          "INVALID_ARGUMENT",
          path + " array size out of range");
      for (int i = 0; i < value.size(); i++)
        validateNode(value.get(i), schema.path("items"), path + "[" + i + "]");
    }
    if (value.isTextual())
      require(
          value.asText().length() >= schema.path("minLength").asInt(0)
              && value.asText().length() <= schema.path("maxLength").asInt(12000),
          "INVALID_ARGUMENT",
          path + " text length out of range");
    if (value.isNumber())
      require(
          value.asDouble() >= schema.path("minimum").asDouble(-Double.MAX_VALUE)
              && value.asDouble() <= schema.path("maximum").asDouble(Double.MAX_VALUE),
          "INVALID_ARGUMENT",
          path + " numeric value out of range");
  }
}
