package dev.scriptforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("scriptforge")
public record ForgeProperties(
    String mode,
    boolean readOnly,
    String adminToken,
    String modelBaseUrl,
    String modelApiKey,
    String modelName,
    int modelTimeoutSeconds,
    int maxOutputTokens,
    int inputBudget,
    boolean schedulerEnabled) {
  public ForgeProperties {
    if (!"DEMO".equals(mode) && !"MODEL".equals(mode))
      throw new IllegalArgumentException("SCRIPT_FORGE_MODE must be DEMO or MODEL");
    if (modelTimeoutSeconds < 1 || modelTimeoutSeconds > 120)
      throw new IllegalArgumentException("Model timeout must be 1..120 seconds");
    if (maxOutputTokens < 256 || maxOutputTokens > 16384)
      throw new IllegalArgumentException("Output token budget must be 256..16384");
    if (inputBudget < 8000 || inputBudget > 200000)
      throw new IllegalArgumentException("Input budget must be 8000..200000");
  }

  public boolean modelConfigured() {
    return "DEMO".equals(mode) || (!modelApiKey.isBlank() && !modelName.isBlank());
  }
}
