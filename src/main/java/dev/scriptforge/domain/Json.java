package dev.scriptforge.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialize data", e);
    }
  }

  public JsonNode tree(String value) {
    try {
      return mapper.readTree(value == null ? "{}" : value);
    } catch (Exception e) {
      throw new ForgeException("INVALID_ARGUMENT", "Invalid JSON");
    }
  }

  public JsonNode tree(Object value) {
    return mapper.valueToTree(value);
  }

  public Map<String, Object> map(String value) {
    try {
      return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored object", e);
    }
  }

  public Object read(String value) {
    try {
      return mapper.readValue(value, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored JSON", e);
    }
  }

  public String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(write(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> object(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }
}
