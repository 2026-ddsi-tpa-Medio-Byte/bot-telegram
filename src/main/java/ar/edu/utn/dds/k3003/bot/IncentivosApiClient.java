package ar.edu.utn.dds.k3003.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente HTTP hacia el módulo Incentivos. Permite consultar insignias, misiones
 * y procesar la evaluación de un donador.
 */
@Component
public class IncentivosApiClient {

  private static final Logger log = LoggerFactory.getLogger(IncentivosApiClient.class);

  private final RestTemplate rest;
  private final String baseUrl;

  public IncentivosApiClient(
      RestTemplate rest,
      @Value("${incentivos.url:https://incentivos-wtbd.onrender.com}") String baseUrl) {
    this.rest = rest;
    this.baseUrl = baseUrl;
    log.info("Cliente de Incentivos apuntando a {}", baseUrl);
  }

  public String listarInsignias() {
    return get("/insignias");
  }

  public String listarMisiones() {
    return get("/misiones");
  }

  public String procesarDonador(String donadorId) {
    return post("/donadores/" + donadorId.trim() + "/procesar", null);
  }

  private String get(String path) {
    try {
      return rest.getForObject(baseUrl + path, String.class);
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String post(String path, Object body) {
    try {
      return rest.postForObject(baseUrl + path, body, String.class);
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String traducir(HttpStatusCodeException e) {
    String cuerpo = e.getResponseBodyAsString();
    int codigo = e.getStatusCode().value();
    if (codigo == 404) {
      return "No encontrado en Incentivos (404). Verificá el identificador ingresado.";
    }
    return "Incentivos respondió con error (" + codigo + "): " + resumir(cuerpo);
  }

  private String sinConexion() {
    return "Incentivos no responde ("
        + baseUrl
        + "). Los servicios de Render se duermen: puede tardar hasta un minuto en despertar. Reintentá en unos segundos.";
  }

  private String resumir(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return "sin detalle.";
    }
    cuerpo = cuerpo.replaceAll("<[^>]*>", "").trim();
    return cuerpo.length() > 140 ? cuerpo.substring(0, 140) + "..." : cuerpo;
  }
}