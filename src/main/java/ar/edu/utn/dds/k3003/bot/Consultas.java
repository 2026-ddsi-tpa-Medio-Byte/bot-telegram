package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Las consultas que necesitan más de un pedido para contestar.
 *
 * <p>Las de un solo pedido se resuelven en el despacho con {@link Formato}; acá quedan las que
 * juntan varios —el donador con sus quejas, las necesidades de todos los productos— y por eso
 * tienen que decidir qué mostrar cuando una parte no contesta. El criterio es el de {@link
 * Impacto}: lo que no se pudo ver se dice, nunca se muestra como si no hubiera nada.
 *
 * <p>Los contratos de Logística e Incentivos son los mismos con los que los consulta el MCP, que ya
 * los usa contra Render.
 */
@Component
class Consultas {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Cuántos productos se recorren para juntar las necesidades: Donadores no tiene un listado
   * general, hay que pedirlas producto por producto. Es el mismo límite que usa /estado.
   */
  private static final int PRODUCTOS_A_RECORRER = 10;

  /** Lo que se espera cada consulta secundaria: un módulo caído tarda un minuto en rendirse. */
  private static final int PACIENCIA_SEGUNDOS = 6;

  private final DonacionesApiClient donaciones;
  private final DonadoresApiClient donadores;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;

  Consultas(
      DonacionesApiClient donaciones,
      DonadoresApiClient donadores,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos) {
    this.donaciones = donaciones;
    this.donadores = donadores;
    this.logistica = logistica;
    this.incentivos = incentivos;
  }

  // ── Donaciones ─────────────────────────────────────────────────────────────

  /** Qué se acepta como «todas» al preguntar de quién son las donaciones. */
  static boolean sonTodas(String respuesta) {
    String r = respuesta.trim().toLowerCase();
    return r.equals("todas") || r.equals("todos") || r.equals("toda") || r.equals("todo");
  }

  String donaciones(String quien) {
    if (sonTodas(quien)) {
      return Formato.donaciones(
          donaciones.listarDonaciones(),
          "Todas las donaciones",
          "Todavía no hay ninguna donación registrada.",
          true);
    }
    String id = quien.trim();
    return Formato.donaciones(
        donaciones.misDonaciones(id),
        "Donaciones del donador nº " + Formato.esc(id),
        "El donador nº " + Formato.esc(id) + " no tiene donaciones registradas.",
        false);
  }

  /** Lo que devuelve Donaciones, más dónde mirar el destino, que lo sabe Logística. */
  String donacion(String id) {
    return Formato.donacion(donaciones.buscarDonacion(id.trim()))
        + "\n\nA qué necesidad la mandó Logística: /paquete "
        + Formato.esc(id.trim());
  }

  // ── Donadores y entidades ──────────────────────────────────────────────────

  /**
   * La ficha del donador, si puede donar y sus quejas.
   *
   * <p>La ficha tiene que estar: si el donador no existe, el error sube tal cual. Lo otro se agrega
   * si contesta a tiempo, y si no, se dice.
   */
  String donador(String id) {
    String donadorId = id.trim();
    StringBuilder sb = new StringBuilder(Formato.donador(donadores.buscarDonador(donadorId)));
    JsonNode puede = leer(() -> donadores.puedeDonar(donadorId));
    sb.append("\n\n");
    if (puede == null) {
      sb.append("⚠️ No pude averiguar si puede donar: Donadores no contestó.");
    } else {
      sb.append(
          puede.path("puedeDonar").asBoolean(false)
              ? "✅ Puede donar"
              : "🚫 No puede donar: está bloqueado por quejas");
    }
    String quejas = texto(() -> donadores.quejasDe(donadorId));
    sb.append("\n\n")
        .append(
            quejas == null
                ? "⚠️ No pude leer las quejas: Donadores no contestó."
                : Formato.quejas(quejas, "Quejas"));
    return sb.toString();
  }

  /**
   * Las necesidades pendientes de todos los productos, agrupadas por producto.
   *
   * <p>Si Donadores deja de contestar se corta ahí: seguir preguntando por cada producto sumaría la
   * espera entera de cada uno, y la respuesta ya va a estar incompleta igual.
   */
  String necesidades() {
    JsonNode productos = leer(donaciones::listarProductos);
    if (productos == null || !productos.isArray()) {
      return "⚠️ Donaciones no contestó, y sin el catálogo no sé por qué productos preguntar. "
          + "Puede estar despertándose: probá de nuevo en un minuto.";
    }
    if (productos.isEmpty()) {
      return "No hay productos cargados, así que tampoco hay necesidades.";
    }

    StringBuilder sb = new StringBuilder("📋 <b>Necesidades pendientes</b>\n");
    int pendientes = 0;
    int recorridos = 0;
    boolean sinRespuesta = false;
    for (JsonNode p : productos) {
      if (recorridos >= PRODUCTOS_A_RECORRER) {
        break;
      }
      recorridos++;
      String id = p.path("id").asText("");
      JsonNode necesidades = leer(() -> donadores.necesidadesDeProducto(id));
      if (necesidades == null || !necesidades.isArray()) {
        sinRespuesta = true;
        break;
      }
      if (necesidades.isEmpty()) {
        continue;
      }
      sb.append("\n📦 <b>")
          .append(Formato.esc(p.path("nombre").asText("Producto")))
          .append("</b> (nº ")
          .append(Formato.esc(id))
          .append(")\n");
      for (JsonNode n : necesidades) {
        pendientes++;
        sb.append(Formato.necesidadEnLista(n)).append("\n");
      }
    }

    if (sinRespuesta) {
      sb.append(
          pendientes == 0
              ? "\n⚠️ Donadores no contestó: no pude ver ninguna necesidad. Probá en un minuto."
              : "\n⚠️ Donadores dejó de contestar: puede haber más de las que se ven acá.");
    } else if (pendientes == 0) {
      sb.append("\nNo hay necesidades pendientes: las que hay están cubiertas.");
    }
    if (productos.size() > recorridos && !sinRespuesta) {
      sb.append("\n<i>Recorrí los primeros ")
          .append(recorridos)
          .append(" productos de ")
          .append(productos.size())
          .append(".</i>");
    }
    return sb.toString();
  }

  // ── Logística ──────────────────────────────────────────────────────────────

  /**
   * El estado de un paquete. Acepta el número de la donación, porque el paquete se llama «paq-» y
   * ese número, o el código entero, que es la única forma de nombrar uno de una solicitud.
   */
  String paquete(String codigo) {
    String escrito = codigo.trim();
    String paqueteId =
        escrito.toLowerCase().startsWith("paq-") ? "paq-" + escrito.substring(4) : "paq-" + escrito;
    String json = logistica.buscarAsignacionSiExiste(paqueteId);
    if (json == null) {
      return "📦 Logística no tiene el paquete <b>"
          + Formato.esc(paqueteId)
          + "</b>.\n\nSi la donación es de recién, puede estar procesándola en segundo plano: "
          + "probá de nuevo en unos segundos. Si fue a parar al stock porque ninguna necesidad la "
          + "pedía, no tiene paquete. Los pendientes de entrega: /asignaciones";
    }
    return Formato.asignacion(json);
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  String progreso(String donadorId) {
    String id = donadorId.trim();
    return Formato.progreso(id, incentivos.insigniasDe(id), incentivos.misionActual(id));
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  /** Con límite de espera; null si no contestó a tiempo o falló. */
  private static String texto(Supplier<String> consulta) {
    try {
      return java.util.concurrent.CompletableFuture.supplyAsync(consulta)
          .get(PACIENCIA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      return null;
    }
  }

  private static JsonNode leer(Supplier<String> consulta) {
    String json = texto(consulta);
    if (json == null) {
      return null;
    }
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }
}
