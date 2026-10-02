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

  /** Igual que el anterior pero devuelve el JSON tal cual, para poder formatearlo o leerle el id. */
  public String registrarDonadorRaw(
      String nombre, String apellido, int edad, String email, String documento, String domicilio) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nombre", nombre);
    body.put("apellido", apellido);
    body.put("edad", edad);
    body.put("email", email);
    body.put("nroDocumento", documento);
    body.put("domicilio", domicilio);
    return postRaw("/donadores", body);
  }

  public String puedeDonar(String id) {
    return get("/donadores/" + id + "/puede-donar");
  }

  public String quejasDe(String id) {
    return get("/donadores/" + id + "/quejas");
  }

  public String cambiarEstadoDonador(String id, String estado) {
    return patchRaw("/donadores/" + id + "/estado", Map.of("estado", estado));
  }

  public String cambiarCategoriaDonador(String id, String categoria) {
    return patchRaw("/donadores/" + id + "/categoria", Map.of("categoria", categoria));
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
    return post("/entidades", cuerpoEntidad(razonSocial, domicilio, telefono, correo),
        "Entidad creada");
  }

  public String crearEntidadRaw(
      String razonSocial, String domicilio, String telefono, String correo) {
    return postRaw("/entidades", cuerpoEntidad(razonSocial, domicilio, telefono, correo));
  }

  public String editarEntidad(
      String id, String razonSocial, String domicilio, String telefono, String correo) {
    return put("/entidades/" + id, cuerpoEntidad(razonSocial, domicilio, telefono, correo),
        "Entidad actualizada");
  }

  public String editarEntidadRaw(
      String id, String razonSocial, String domicilio, String telefono, String correo) {
    return putRaw("/entidades/" + id, cuerpoEntidad(razonSocial, domicilio, telefono, correo));
  }

  private Map<String, Object> cuerpoEntidad(
      String razonSocial, String domicilio, String telefono, String correo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("razonSocial", razonSocial);
    body.put("domicilio", domicilio);
    body.put("telefono", telefono);
    body.put("correo", correo);
    return body;
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
    return post(
        "/necesidades",
        cuerpoNecesidad(entidadID, urgencia, descripcion, cantidadObjetivo, productoID, tipo),
        "Necesidad creada");
  }

  public String altaNecesidadRaw(
      String entidadID,
      int urgencia,
      String descripcion,
      int cantidadObjetivo,
      String productoID,
      String tipo) {
    return postRaw(
        "/necesidades",
        cuerpoNecesidad(entidadID, urgencia, descripcion, cantidadObjetivo, productoID, tipo));
  }

  public String modificarNecesidadRaw(
      String id,
      int urgencia,
      String descripcion,
      int cantidadObjetivo,
      String productoID,
      String tipo) {
    return putRaw(
        "/necesidades/" + id,
        cuerpoNecesidad(null, urgencia, descripcion, cantidadObjetivo, productoID, tipo));
  }

  private Map<String, Object> cuerpoNecesidad(
      String entidadID,
      int urgencia,
      String descripcion,
      int cantidadObjetivo,
      String productoID,
      String tipo) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (entidadID != null) {
      body.put("entidadID", entidadID);
    }
    body.put("nivelDeUrgencia", urgencia);
    body.put("descripcion", descripcion);
    body.put("cantidadObjetivo", cantidadObjetivo);
    body.put("productoSolicitadoID", productoID);
    body.put("tipo", tipo);
    return body;
  }

  /** Donadores no expone todas las necesidades juntas: hay que pedirlas por producto. */
  public String necesidadesDeProducto(String productoId) {
    return get("/necesidades?productoID=" + productoId.trim());
  }

  /** Borra donadores, entidades y necesidades. Solo para preparar una demostración. */
  public String reset() {
    try {
      rest.delete(baseUrl + "/reset");
      return "listo";
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
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
      return Reintento.siNoResponde(() -> rest.getForObject(baseUrl + path, String.class));
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String post(String path, Object body, String okMsg) {
    return okMsg + ": " + postRaw(path, body);
  }

  /** Devuelve el JSON sin adornos, para poder formatearlo o leerle el id. */
  private String postRaw(String path, Object body) {
    try {
      return rest.postForObject(baseUrl + path, body, String.class);
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String putRaw(String path, Object body) {
    try {
      return rest.exchange(
              baseUrl + path,
              org.springframework.http.HttpMethod.PUT,
              new org.springframework.http.HttpEntity<>(body),
              String.class)
          .getBody();
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  private String patchRaw(String path, Object body) {
    try {
      return rest.exchange(
              baseUrl + path,
              org.springframework.http.HttpMethod.PATCH,
              new org.springframework.http.HttpEntity<>(body),
              String.class)
          .getBody();
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

  /**
   * Donadores contesta los errores en texto plano, así que el cuerpo ya es el motivo.
   *
   * <p>El 404 lleva el motivo porque ahora lo usan más casos: borrar una necesidad que no existe
   * antes era un 400 que mostraba el mensaje, y con un «No encontrado.» pelado se perdía. El 409 es
   * el alta de un donador repetido, y lo que suele pasar ahí es alguien que ya estaba registrado.
   */
  private String traducir(HttpStatusCodeException e) {
    int codigo = e.getStatusCode().value();
    String motivo = resumir(e.getResponseBodyAsString());
    if (codigo == 404) {
      return motivo.isBlank() ? "No encontrado." : "No encontrado: " + Formato.esc(motivo);
    }
    if (codigo == 409) {
      return "No se pudo: "
          + Formato.esc(motivo)
          + (motivo.contains("donador")
              ? "\n\nSi ya estabas registrado no hace falta hacerlo de nuevo: entrá con /entrar y "
                  + "tu número (/donadores los lista)."
              : "");
    }
    return "Solicitud rechazada (" + codigo + "): " + Formato.esc(motivo);
  }

  /** Un stack trace o una página de error entera no le sirven a nadie en un chat. */
  private static String resumir(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return "";
    }
    String limpio = cuerpo.trim();
    return limpio.length() > 300 ? limpio.substring(0, 300) + "..." : limpio;
  }

  private String sinConexion() {
    return "No se pudo conectar con el módulo Donadores en " + baseUrl;
  }
}
