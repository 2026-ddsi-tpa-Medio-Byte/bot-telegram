package ar.edu.utn.dds.k3003.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente HTTP hacia el módulo Logística. Permite consultar depósitos, stock
 * y reportar la entrega de paquetes.
 */
@Component
public class LogisticaApiClient {

  private static final Logger log = LoggerFactory.getLogger(LogisticaApiClient.class);

  private final RestTemplate rest;
  private final String baseUrl;

  public LogisticaApiClient(
      RestTemplate rest,
      @Value("${logistica.url:https://logistica-i4rf.onrender.com}") String baseUrl) {
    this.rest = rest;
    this.baseUrl = baseUrl;
    log.info("Cliente de Logística apuntando a {}", baseUrl);
  }

  public String listarDepositos() {
    return get("/depositos");
  }

  public String consultarStock(String productoId) {
    return get("/stock/" + productoId.trim());
  }

  public String reportarEntrega(
      String paqueteId, String donacionId, String productoId, int cantidad) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paqueteid", paqueteId != null ? paqueteId.trim() : "");
    body.put("donacionID", donacionId != null ? donacionId.trim() : "");
    body.put("productoid", productoId != null ? productoId.trim() : "");
    body.put("cantidad", cantidad);
    return post("/api/asignaciones/reportar-entrega", body);
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
      return "No encontrado en Logística (404). Verificá los identificadores ingresados.";
    }
    return "Logística respondió con error (" + codigo + "): " + resumir(cuerpo);
  }

  private String sinConexion() {
    return "Logística no responde ("
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