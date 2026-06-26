package ar.edu.utn.dds.k3003.bot;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

  /** RestTemplate con timeout de lectura amplio para soportar el long-polling de Telegram. */
  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(60))
        .build();
  }
}
