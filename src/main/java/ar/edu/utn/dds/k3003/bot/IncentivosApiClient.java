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

  public String insigniasDe(String donadorId) {
    return get("/donadores/" + donadorId.trim() + "/insignias");
  }

  public String crearInsignia(String id, String nombre, String descripcion) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("id", id);
    body.put("nombre", nombre);
    body.put("descripcion", descripcion);
    return post("/insignias", body);
  }

  /** Una misión otorga una insignia al donador que pasa de una categoría a la siguiente. */
  public String crearMision(
      String id,
      String nombre,
      String insigniaId,
      String categoriaInicio,
      String categoriaFin,
      String tipo) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("id", id);
    body.put("nombre", nombre);
    body.put("insigniaID", insigniaId);
    body.put("categoriaInicio", categoriaInicio);
    body.put("categoriaFin", categoriaFin);
    body.put("tipo", tipo);
    return post("/misiones", body);
  }

  public String asignarMision(String donadorId, java.util.Map<String, Object> mision) {
    return post("/donadores/" + donadorId.trim() + "/mision-actual", mision);
  }

  public String limpiar() {
    return post("/admin/clear", null);
  }

  private String get(String path) {
    try {
      return Reintento.siNoResponde(() -> rest.getForObject(baseUrl + path, String.class));
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