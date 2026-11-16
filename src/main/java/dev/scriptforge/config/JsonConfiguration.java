package dev.scriptforge.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictJson() {
    return builder -> {
      builder.featuresToEnable(
          DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
          JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      builder.postConfigurer(
          mapper ->
              mapper
                  .getFactory()
                  .setStreamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(60)
                          .maxStringLength(150000)
                          .build()));
    };
  }
}
