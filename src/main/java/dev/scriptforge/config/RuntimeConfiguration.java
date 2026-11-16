package dev.scriptforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scriptforge.model.DemoModelGateway;
import dev.scriptforge.model.ModelGateway;
import dev.scriptforge.model.OpenAiModelGateway;
import dev.scriptforge.runtime.ContextAssembler;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {
  @Bean
  ModelGateway modelGateway(ForgeProperties p, ObjectMapper mapper) {
    return "DEMO".equals(p.mode())
        ? new DemoModelGateway(mapper)
        : new OpenAiModelGateway(
            mapper,
            p.modelBaseUrl(),
            p.modelApiKey(),
            p.modelName(),
            Duration.ofSeconds(p.modelTimeoutSeconds()));
  }

  @Bean
  ContextAssembler contextAssembler(ObjectMapper mapper) {
    return new ContextAssembler(mapper);
  }
}
