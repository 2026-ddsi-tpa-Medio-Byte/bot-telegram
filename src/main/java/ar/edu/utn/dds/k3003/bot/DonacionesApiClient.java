package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Cliente del módulo Donaciones. Se agregó para que un donador identificado pueda donar desde el
 * bot, y para poder mostrarle sus propias donaciones y los productos disponibles.
 */
@Component
public class DonacionesApiClient {

  private static final Logger log = LoggerFactory.getLogger(DonacionesApiClient.class);

  private final RestTemplate rest;
  private final String baseUrl;

  public DonacionesApiClient(
      RestTemplate rest, @Value("${donaciones.url:http://localhost:8080}") String baseUrl) {
    this.rest = rest;
    this.baseUrl = baseUrl;
    log.info("Cliente de Donaciones apuntando a {}", baseUrl);
  }

  public String donar(
      String donadorID, String depositoID, String descripcion, String productoID, int cantidad) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("donadorID", donadorID);
    body.put("depositoID", depositoID);
    body.put("descripcion", descripcion);
    body.put("productoID", productoID);
    body.put("cantidad", cantidad);
    return post("/donaciones", body);
  }

  public String misDonaciones(String donadorID) {
    return get("/donaciones?donadorID=" + donadorID + "&fecha=2020-01-01");
  }

  public String buscarDonacion(String id) {
    return get("/donaciones/" + id);
  }

  public String listarProductos() {
    return get("/productos");
  }

  public String buscarProducto(String id) {
    return get("/productos/" + id);
  }

  public String listarDonaciones() {
    return get("/donaciones");
  }

  public String listarIdentificadores() {
    return get("/identificadores");
  }

  public String crearIdentificador(String tipo, String descripcion) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tipo", tipo);
    body.put("descripcion", descripcion);
    return post("/identificadores", body);
  }

  public String crearProducto(
      String nombre, String descripcion, String categoria, String identificadorID) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nombre", nombre);
    body.put("descripcion", descripcion);
    body.put("categoriaID", categoria);
    body.put("identificadorID", identificadorID);
    return post("/productos", body);
  }

  public String registrarQueja(String donacionId, String descripcion) {
    return post("/donaciones/" + donacionId + "/quejas", descripcion);
  }

  /** Borra donaciones, productos e identificadores. Solo para preparar una demostración. */
  public String reset() {
    try {
      rest.delete(baseUrl + "/donaciones/reset");
      return "listo";
    } catch (HttpStatusCodeException e) {
      throw new RuntimeException(traducir(e));
    } catch (ResourceAccessException e) {
      throw new RuntimeException(sinConexion());
    }
  }

  // ── Helpers HTTP ───────────────────────────────────────────────────────────

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

  /**
   * Traduce los errores de la API a algo que se entienda en un chat.
   *
   * <p>Cada código pide una reacción distinta de quien está del otro lado: revisar el número
   * (404), aceptar que no puede (403), esperar a que la donación cambie de estado (409) o volver a
   * probar en un rato (502). El bot no decide nada de eso: el motivo lo pone Donaciones y se
   * muestra, escapado porque el mensaje viaja como HTML.
   */
  private String traducir(HttpStatusCodeException e) {
    String cuerpo = e.getResponseBodyAsString();
    int codigo = e.getStatusCode().value();
    String mensaje = mensajeDelModulo(cuerpo);

    if (codigo == 404) {
      // Va adentro del 404 porque un producto inexistente es un 404: antes este chequeo estaba
      // después del código y nunca se alcanzaba.
      if (mensaje.contains("Producto no encontrado")) {
        return "Ese producto no existe. Mirá los disponibles con /productos";
      }
      // El detalle dice qué no se encontró: ahora el donador inexistente también es un 404, y sin
      // él no se distingue de un producto mal puesto.
      return "No encontré eso" + detalle(mensaje) + ". Fijate el número que pusiste.";
    }
    if (cuerpo.contains("cantidad donada debe ser mayor")) {
      return "La cantidad tiene que ser mayor a cero.";
    }
    if (cuerpo.contains("No puede donar")) {
      return "No podés donar: tu cuenta está baneada por quejas acumuladas.";
    }
    if (codigo == 403) {
      return "Donaciones no permite esta operación" + detalle(mensaje) + ".";
    }
    if (codigo == 409) {
      // El motivo de Donaciones ya se lee bien («todavía no fue entregada (estado actual:
      // INGRESADA)»): se muestra tal cual, y solo se agrega cómo se destraba.
      String motivo = resumir(mensaje);
      return (motivo.isBlank()
              ? "No se puede en el estado en que está la donación."
              : Formato.esc(motivo))
          + pistaSobreQuejas(mensaje);
    }
    if (codigo == 502) {
      return otroModuloNoResponde(cuerpo);
    }
    return "No se pudo (" + codigo + "). " + Formato.esc(resumir(mensaje));
  }

  /**
   * Donaciones contesta {@code {"error": "<mensaje>"}}: se muestra el mensaje solo, sin el JSON.
   *
   * <p>Se exige que {@code error} sea el único campo porque el error por defecto de Spring también
   * trae uno, con el nombre del código ({@code "Bad Gateway"}), que no dice nada.
   */
  private static String errorDeDonaciones(String cuerpo) {
    JsonNode nodo = Formato.parsear(cuerpo);
    if (nodo != null && nodo.isObject() && nodo.size() == 1 && nodo.path("error").isTextual()) {
      String error = nodo.path("error").asText().trim();
      return error.isBlank() ? null : error;
    }
    return null;
  }

  private static String mensajeDelModulo(String cuerpo) {
    String error = errorDeDonaciones(cuerpo);
    return error != null ? error : cuerpo == null ? "" : cuerpo;
  }

  /** «: motivo», listo para pegar después de la frase, o nada si el módulo no dijo nada. */
  private static String detalle(String mensaje) {
    String limpio = resumir(mensaje);
    if (limpio.endsWith(".")) {
      limpio = limpio.substring(0, limpio.length() - 1);
    }
    return limpio.isBlank() ? "" : ": " + Formato.esc(limpio);
  }

  /**
   * Lo que el mensaje del módulo no dice: cómo se destraba. Solo se agrega si el motivo es uno de
   * los dos de las quejas; el motivo en sí ya está arriba, tal cual lo mandó Donaciones.
   */
  private static String pistaSobreQuejas(String mensaje) {
    if (mensaje.contains("no fue entregada")) {
      return "\n\nUna donación queda entregada cuando se reporta su entrega con /reportarentrega.";
    }
    if (mensaje.contains("ya tiene una queja")) {
      return "\n\nCada donación admite una sola queja.";
    }
    return "";
  }

  /**
   * Un 502 con el cuerpo de Donaciones quiere decir que Donaciones anda pero otro módulo del que
   * depende no le contestó, y el mensaje dice cuál. Sin ese cuerpo lo generó Render: es Donaciones
   * la que está dormida.
   */
  private String otroModuloNoResponde(String cuerpo) {
    String error = errorDeDonaciones(cuerpo);
    if (error == null) {
      return sinConexion();
    }
    java.util.regex.Matcher modulo = MODULO_QUE_FALLO.matcher(error);
    String cual = modulo.find() ? "El módulo " + modulo.group(1) : "Otro módulo del sistema";
    return Formato.esc(cual)
        + " no está respondiendo, así que Donaciones no pudo completar la operación. Puede estar "
        + "despertándose: probá de nuevo en un minuto.\n\nDetalle: "
        + Formato.esc(resumir(error));
  }

  /** Cómo nombra Donaciones al módulo que falló: «El módulo Logística no respondió (...)». */
  private static final java.util.regex.Pattern MODULO_QUE_FALLO =
      java.util.regex.Pattern.compile("El módulo (.+?) (?:no respondió|respondió con error)");

  /**
   * El largo alcanza para los mensajes de Donaciones, que son una o dos oraciones; lo que corta
   * es una página HTML o un stack trace, que no le sirven a nadie en un chat.
   */
  private static String resumir(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return "";
    }
    String limpio = cuerpo.replaceAll("[{}\"]", "").trim();
    return limpio.length() > 300 ? limpio.substring(0, 300) + "..." : limpio;
  }

  private String sinConexion() {
    return "El módulo de Donaciones no responde. Puede estar despertándose: probá de nuevo en un minuto.";
  }
}
