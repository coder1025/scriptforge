package dev.scriptforge.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreateTask(
    @NotBlank @Size(max = 100) String requestId,
    @NotBlank @Size(max = 160) String title,
    @NotBlank @Size(min = 10, max = 6000) String brief,
    @NotBlank @Size(max = 40) String language,
    @Min(30) @Max(1800) int targetSeconds,
    @NotEmpty @Size(max = 50) List<@Valid Fact> facts,
    @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> constraints,
    @Min(8) @Max(200) Integer maxModelCalls,
    @Min(50000) @Max(2000000) Long maxTokens,
    @Size(max = 36) String templateId) {
  public record Fact(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String id,
      @NotBlank @Size(max = 1500) String text,
      @NotBlank @Size(max = 500) String source) {}
}
