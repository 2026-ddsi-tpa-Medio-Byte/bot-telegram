package ar.edu.utn.dds.k3003.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente HTTP hacia el módulo "Donadores y Entidades". Devuelve texto listo para mostrar en el
 * bot; ante errores lanza RuntimeException con un mensaje amigable (el bot lo captura).
 */
@Component
public class DonadoresApiClient {

  private final RestTemplate rest;
  private final String baseUrl;

  public DonadoresApiClient(
      RestTemplate rest, @Value("${donadores.url:http://localhost:8080}") String baseUrl) {
    this.rest = rest;
    this.baseUrl = baseUrl;
  }

  // ── Donadores ───────────────────────────────────────────────────────────────

  public String registrarDonador(
      String nombre, String apellido, int edad, String email, String documento, String domicilio) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nombre", nombre);
    body.put("apellido", apellido);
    body.put("edad", edad);
    body.put("email", email);
    body.put("nroDocumento", documento);
    body.put("domicilio", domicilio);
    return post("/donadores", body, "Donador registrado");
  }

  public String estadisticasDonador(String id) {
    return get("/donadores/" + id + "/estadisticas");
  }

  public String buscarDonador(String id) {
    return get("/donadores/" + id);
  }

  public String listarDonadores() {
    return get("/donadores");
  }

  // ── Entidades ────────────────────────────────────────────────────────────────

  public String crearEntidad(String razonSocial, String domicilio, String telefono, String correo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("razonSocial", razonSocial);
    body.put("domicilio", domicilio);
    body.put("telefono", telefono);
    body.put("correo", correo);
    return post("/entidades", body, "Entidad creada");
  }

  public String editarEntidad(
      String id, String razonSocial, String domicilio, String telefono, String correo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("razonSocial", razonSocial);
    body.put("domicilio", domicilio);
    body.put("telefono", telefono);
    body.put("correo", correo);
    return put("/entidades/" + id, body, "Entidad actualizada");
  }

  public String buscarEntidad(String id) {
    return get("/entidades/" + id);
  }

  public String listarEntidades() {
    return get("/entidades");
  }

  // ── Necesidades ──────────────────────────────────────────────────────────────

  public String altaNecesidad(
      String entidadID,
      int urgencia,
      String descripcion,
      int cantidadObjetivo,
      String productoID,
      String tipo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("entidadID", entidadID);
    body.put("nivelDeUrgencia", urgencia);
    body.put("descripcion", descripcion);
    body.put("cantidadObjetivo", cantidadObjetivo);
    body.put("productoSolicitadoID", productoID);
    body.put("tipo", tipo);
    return post("/necesidades", body, "Necesidad creada");
  }

  public String buscarNecesidad(String id) {
    return get("/necesidades/" + id);
  }

  public String modificarNecesidad(
      String id,
      int urgencia,
      String descripcion,
      int cantidadObjetivo,
      String productoID,
      String tipo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nivelDeUrgencia", urgencia);
    body.put("descripcion", descripcion);
    body.put("cantidadObjetivo", cantidadObjetivo);
    body.put("productoSolicitadoID", productoID);
    body.put("tipo", tipo);
    return put("/necesidades/" + id, body, "Necesidad actualizada");
  }

  public String borrarNecesidad(String id) {
    delete("/necesidades/" + id);
    return "Necesidad " + id + " eliminada";
  }

  // ── Helpers HTTP ─────────────────────────────────────────────────────────────

  private String get(String path) {
    try {
      return rest.getForObject(baseUrl + path, String.class);
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String post(String path, Object body, String okMsg) {
    try {
      String resp = rest.postForObject(baseUrl + path, body, String.class);
      return okMsg + ": " + resp;
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String put(String path, Object body, String okMsg) {
    try {
      org.springframework.http.ResponseEntity<String> resp =
          rest.exchange(
              baseUrl + path,
              org.springframework.http.HttpMethod.PUT,
              new org.springframework.http.HttpEntity<>(body),
              String.class);
      return okMsg + ": " + resp.getBody();
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private void delete(String path) {
    try {
      rest.delete(baseUrl + path);
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String traducir(HttpStatusCodeException e) {
    if (e.getStatusCode().value() == 404) {
      return "No encontrado.";
    }
    return "Solicitud rechazada (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString();
  }

  private String sinConexion() {
    return "No se pudo conectar con el módulo Donadores en " + baseUrl;
  }
}
