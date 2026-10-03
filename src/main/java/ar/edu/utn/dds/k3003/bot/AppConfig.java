package ar.edu.utn.dds.k3003.bot;

import java.time.Clock;
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
        // Va en un interceptor y no en cada cliente para que ninguna llamada nueva se olvide de
        // mandar la traza: si falta en una, el recorrido en Datadog queda cortado justo ahí.
        .interceptors(
            (request, body, ejecucion) -> {
              String traza = Traza.actual();
              if (traza != null) {
                request.getHeaders().set(Traza.HEADER, traza);
              }
              return ejecucion.execute(request, body);
            })
        .build();
  }

  /** Un bean y no Clock.systemUTC() suelto, para que los tests del login puedan adelantar la hora. */
  @Bean
  public Clock reloj() {
    return Clock.systemUTC();
  }
}
