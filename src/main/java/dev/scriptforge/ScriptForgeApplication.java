package dev.scriptforge;

import dev.scriptforge.config.ForgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ForgeProperties.class)
public class ScriptForgeApplication {
  public static void main(String[] args) {
    SpringApplication.run(ScriptForgeApplication.class, args);
  }
}
