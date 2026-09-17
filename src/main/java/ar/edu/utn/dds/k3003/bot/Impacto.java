package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Cuenta qué cambió en el sistema después de una operación.
 *
 * <p>Una donación hecha desde el bot toca tres módulos, pero la respuesta que devuelve Donaciones
 * solo habla de sí misma. Sin esto hay que ir pidiendo el stock, las necesidades y el estado uno
 * por uno para ver si pasó lo que tenía que pasar.
 *
 * <p>Las consultas que arman el relato nunca hacen fallar la operación: si un módulo no contesta,
 * esa línea dice que no contestó. La operación ya ocurrió y devolver un error sería mentir.
 */
@Component
class Impacto {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DonacionesApiClient donaciones;
  private final DonadoresApiClient donadores;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;

  Impacto(
      DonacionesApiClient donaciones,
      DonadoresApiClient donadores,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos) {
    this.donaciones = donaciones;
    this.donadores = donadores;
    this.logistica = logistica;
    this.incentivos = incentivos;
  }

  // ── Donación ───────────────────────────────────────────────────────────────

  String donar(
      String donadorId, String depositoId, String descripcion, String productoId, int cantidad) {
    Foto antes = fotoDeProducto(productoId);

    String respuesta;
    String traza = Traza.nueva();
    try {
      respuesta = donaciones.donar(donadorId, depositoId, descripcion, productoId, cantidad);
    } finally {
      Traza.cerrar();
    }

    try {
      Foto despues = fotoDeProducto(productoId);
      JsonNode donacion = MAPPER.readTree(respuesta);
      return textoDonacion(donacion, antes, despues, traza);
    } catch (Exception e) {
      return "🎁 <b>¡Gracias por donar!</b>\n\n" + Formato.donacion(respuesta);
    }
  }

  private String textoDonacion(JsonNode donacion, Foto antes, Foto despues, String traza) {
    StringBuilder sb = new StringBuilder("🎁 <b>Donación registrada</b>\n\n");
    sb.append("<b>Donaciones</b> · nº ")
        .append(texto(donacion, "id"))
        .append(" · ")
        .append(donacion.path("cantidad").asInt(0))
        .append(" unidades de ")
        .append(antes.nombreProducto)
        .append(" · ")
        .append(texto(donacion, "estado"))
        .append("\n");
    sb.append("<b>Donadores</b> · confirmó que el donador existe y que está habilitado\n");
    sb.append("<b>Logística</b> · ").append(efectoEnStock(antes, despues)).append("\n");

    if (despues.necesidades.isEmpty()) {
      sb.append("<b>Necesidades</b> · no hay ninguna pendiente de este producto\n");
    } else {
      for (JsonNode n : despues.necesidades.subList(0, Math.min(2, despues.necesidades.size()))) {
        sb.append("<b>Necesidad ")
            .append(texto(n, "id"))
            .append("</b> · ")
            .append(Formato.barra(n.path("cantidadActual").asInt(0), n.path("cantidadObjetivo").asInt(0)))
            .append(" ")
            .append(n.path("cantidadActual").asInt(0))
            .append("/")
            .append(n.path("cantidadObjetivo").asInt(0))
            .append("\n");
      }
      sb.append("\n<i>La necesidad no se satisface al donar: cambia cuando Logística reporta la entrega.</i>\n");
    }
    return sb.append(pie(antes, despues, traza)).toString();
  }

  private String efectoEnStock(Foto antes, Foto despues) {
    if (antes.stock == null || despues.stock == null) {
      return "no se pudo leer el stock";
    }
    if (despues.stock > antes.stock) {
      return "stock "
          + antes.stock
          + " → "
          + despues.stock
          + ": no había necesidad que la reciba, quedó guardada";
    }
    return "stock " + antes.stock + " → " + despues.stock + ": la asignó a una necesidad";
  }

  // ── Entrega ────────────────────────────────────────────────────────────────

  String reportarEntrega(String paqueteId, String donacionId, String productoId, int cantidad) {
    JsonNode asignacionAntes = leer(() -> logistica.buscarAsignacion(paqueteId));
    JsonNode donacionAntes = leer(() -> donaciones.buscarDonacion(donacionId));
    String necesidadId = primero(asignacionAntes, "necesidadid", "necesidadID");
    JsonNode necesidadAntes =
        necesidadId.isBlank() ? null : leer(() -> donadores.buscarNecesidad(necesidadId));

    String traza = Traza.nueva();
    try {
      logistica.reportarEntrega(paqueteId, donacionId, productoId, cantidad);
    } finally {
      Traza.cerrar();
    }

    JsonNode donacionDespues = leer(() -> donaciones.buscarDonacion(donacionId));
    JsonNode necesidadDespues =
        necesidadId.isBlank() ? null : leer(() -> donadores.buscarNecesidad(necesidadId));

    StringBuilder sb = new StringBuilder("📦 <b>Entrega reportada</b>\n\n");
    sb.append("<b>Logística</b> · paquete ").append(paqueteId);
    String origen = primero(asignacionAntes, "origen");
    if (!origen.isBlank()) {
      sb.append(" · asignado por ").append("MATCHMAKING".equals(origen) ? "matchmaking" : "solicitud");
    }
    sb.append("\n");
    sb.append("<b>Donaciones</b> · donación ")
        .append(donacionId)
        .append(" · ")
        .append(cambio(texto(donacionAntes, "estado"), texto(donacionDespues, "estado")))
        .append("\n");
    if (necesidadDespues != null) {
      int actual = necesidadDespues.path("cantidadActual").asInt(0);
      int objetivo = necesidadDespues.path("cantidadObjetivo").asInt(0);
      sb.append("<b>Necesidad ")
          .append(necesidadId)
          .append("</b> · ")
          .append(necesidadAntes == null ? "" : necesidadAntes.path("cantidadActual").asInt(0) + " → ")
          .append(Formato.barra(actual, objetivo))
          .append(" ")
          .append(actual)
          .append("/")
          .append(objetivo)
          .append(actual >= objetivo && objetivo > 0 ? " ✅ cubierta" : "")
          .append("\n");
    } else {
      sb.append("<b>Necesidad</b> · no se pudo identificar cuál cubría este paquete\n");
    }
    return sb.append("\n🔎 traza <code>").append(traza).append("</code>\n").toString();
  }

  // ── Queja ──────────────────────────────────────────────────────────────────

  String queja(String donacionId, String descripcion) {
    JsonNode donacionAntes = leer(() -> donaciones.buscarDonacion(donacionId));
    String donadorId = texto(donacionAntes, "donadorID");
    JsonNode donadorAntes =
        donadorId.isBlank() ? null : leer(() -> donadores.buscarDonador(donadorId));

    String traza = Traza.nueva();
    try {
      donaciones.registrarQueja(donacionId, descripcion);
    } finally {
      Traza.cerrar();
    }

    JsonNode donacionDespues = leer(() -> donaciones.buscarDonacion(donacionId));
    JsonNode donadorDespues =
        donadorId.isBlank() ? null : leer(() -> donadores.buscarDonador(donadorId));
    JsonNode quejas = donadorId.isBlank() ? null : leer(() -> donadores.quejasDe(donadorId));

    StringBuilder sb = new StringBuilder("📣 <b>Queja registrada</b>\n\n");
    sb.append("<b>Donaciones</b> · donación ")
        .append(donacionId)
        .append(" · ")
        .append(cambio(texto(donacionAntes, "estado"), texto(donacionDespues, "estado")))
        .append("\n");
    String estadoAntes = texto(donadorAntes, "estado");
    String estadoDespues = texto(donadorDespues, "estado");
    sb.append("<b>Donadores</b> · donador ")
        .append(donadorId)
        .append(" · ")
        .append(cambio(estadoAntes, estadoDespues));
    if (quejas != null && quejas.isArray()) {
      sb.append(" · ").append(quejas.size()).append(" quejas");
    }
    sb.append("\n");
    if (!estadoAntes.equals(estadoDespues)) {
      sb.append(
          "BANEADO".equals(estadoDespues)
              ? "\n🚫 <b>Desde ahora no puede donar</b>: Donaciones le va a rechazar las donaciones.\n"
              : "\n⚠️ Quedó marcado como sospechoso. Si siguen las quejas, termina baneado.\n");
    }
    return sb.append("\n🔎 traza <code>").append(traza).append("</code>\n").toString();
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  String procesar(String donadorId) {
    JsonNode donadorAntes = leer(() -> donadores.buscarDonador(donadorId));
    JsonNode insigniasAntes = leer(() -> incentivos.insigniasDe(donadorId));

    String traza = Traza.nueva();
    try {
      incentivos.procesarDonador(donadorId);
    } finally {
      Traza.cerrar();
    }

    JsonNode donadorDespues = leer(() -> donadores.buscarDonador(donadorId));
    JsonNode insigniasDespues = leer(() -> incentivos.insigniasDe(donadorId));

    int antes = cantidad(insigniasAntes);
    int despues = cantidad(insigniasDespues);
    StringBuilder sb = new StringBuilder("🔄 <b>Donador procesado en Incentivos</b>\n\n");
    sb.append("<b>Incentivos</b> · insignias ").append(antes).append(" → ").append(despues);
    sb.append(
        despues > antes
            ? " 🏅 ¡ganó una!\n"
            : despues < antes ? " · perdió progreso, normalmente por una queja\n"
                : " · todavía no cumple la misión\n");
    sb.append("<b>Donadores</b> · categoría ")
        .append(cambio(texto(donadorAntes, "categoria"), texto(donadorDespues, "categoria")))
        .append("\n");
    return sb.append("\n🔎 traza <code>").append(traza).append("</code>\n").toString();
  }

  private static int cantidad(JsonNode lista) {
    return lista != null && lista.isArray() ? lista.size() : 0;
  }

  // ── Piezas ─────────────────────────────────────────────────────────────────

  /** Lo que se mira alrededor de una donación: el producto, sus necesidades y su stock. */
  private static class Foto {
    String nombreProducto = "el producto";
    List<JsonNode> necesidades = new ArrayList<>();
    Integer stock;
    boolean faltoAlgo;
  }

  private Foto fotoDeProducto(String productoId) {
    Foto f = new Foto();
    JsonNode producto = leer(() -> donaciones.buscarProducto(productoId));
    if (producto != null && !texto(producto, "nombre").isBlank()) {
      f.nombreProducto = texto(producto, "nombre");
    }
    JsonNode necesidades = leer(() -> donadores.necesidadesDeProducto(productoId));
    if (necesidades != null && necesidades.isArray()) {
      necesidades.forEach(f.necesidades::add);
    } else {
      f.faltoAlgo = true;
    }
    JsonNode stock = leer(() -> logistica.consultarStock(productoId));
    if (stock != null) {
      f.stock = stock.path("disponible").asInt(0);
    } else {
      f.faltoAlgo = true;
    }
    return f;
  }

  private String pie(Foto antes, Foto despues, String traza) {
    String aviso =
        antes.faltoAlgo || despues.faltoAlgo
            ? "\n⚠️ Algún módulo no contestó: el resumen puede estar incompleto.\n"
            : "";
    return aviso + "\n🔎 traza <code>" + traza + "</code>\n";
  }

  private JsonNode leer(Supplier<String> consulta) {
    try {
      return MAPPER.readTree(consulta.get());
    } catch (Exception e) {
      return null;
    }
  }

  private static String texto(JsonNode nodo, String campo) {
    return nodo == null ? "" : nodo.path(campo).asText("");
  }

  /** Logística devuelve los campos en minúscula aunque su Swagger los declare en camelCase. */
  private static String primero(JsonNode nodo, String... campos) {
    for (String campo : campos) {
      String valor = texto(nodo, campo);
      if (!valor.isBlank()) {
        return valor;
      }
    }
    return "";
  }

  private static String cambio(String antes, String despues) {
    String a = antes.isBlank() ? "—" : antes;
    String d = despues.isBlank() ? "—" : despues;
    return a.equals(d) ? "sigue en " + a : a + " → " + d;
  }
}
