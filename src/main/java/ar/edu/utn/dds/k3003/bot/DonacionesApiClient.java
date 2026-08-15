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

  // ── Helpers HTTP ───────────────────────────────────────────────────────────

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

  /** Traduce los errores de la API a algo que se entienda en un chat. */
  private String traducir(HttpStatusCodeException e) {
    String cuerpo = e.getResponseBodyAsString();
    int codigo = e.getStatusCode().value();

    if (codigo == 404) {
      return "No encontré eso. Fijate el número que pusiste.";
    }
    if (cuerpo.contains("cantidad donada debe ser mayor")) {
      return "La cantidad tiene que ser mayor a cero.";
    }
    if (cuerpo.contains("No puede donar")) {
      return "No podés donar: tu cuenta está baneada por quejas acumuladas.";
    }
    if (cuerpo.contains("Producto no encontrado")) {
      return "Ese producto no existe. Mirá los disponibles con /productos";
    }
    return "No se pudo (" + codigo + "). " + resumir(cuerpo);
  }

  private String resumir(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return "";
    }
    String limpio = cuerpo.replaceAll("[{}\"]", "");
    return limpio.length() > 180 ? limpio.substring(0, 180) + "..." : limpio;
  }

  private String sinConexion() {
    return "El módulo de Donaciones no responde. Puede estar despertándose: probá de nuevo en un minuto.";
  }
}
