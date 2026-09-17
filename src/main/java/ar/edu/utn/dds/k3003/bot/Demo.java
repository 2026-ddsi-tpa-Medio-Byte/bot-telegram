package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lo que hace falta para mostrar el sistema sin preparar nada a mano.
 *
 * <p>Son tres cosas: dejar las cuatro bases vacías, cargar las precondiciones que cada flujo
 * necesita, y poder ver de un vistazo cómo está todo antes y después de cada operación.
 *
 * <p>El depósito se crea con el identificador que el bot usa por defecto al donar. Si se creara con
 * otro, donar sin indicar depósito iría a uno inexistente y Logística no podría asignar nada.
 */
@Component
class Demo {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Cuántos productos se recorren para contar necesidades y stock: hay dos consultas por cada uno. */
  private static final int PRODUCTOS_A_RECORRER = 10;

  private final DonacionesApiClient donaciones;
  private final DonadoresApiClient donadores;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;
  private final String depositoPorDefecto;

  Demo(
      DonacionesApiClient donaciones,
      DonadoresApiClient donadores,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos,
      @Value("${deposito.default:DEP-UTN-01}") String depositoPorDefecto) {
    this.donaciones = donaciones;
    this.donadores = donadores;
    this.logistica = logistica;
    this.incentivos = incentivos;
    this.depositoPorDefecto = depositoPorDefecto;
  }

  // ── Reiniciar ──────────────────────────────────────────────────────────────

  String reiniciar() {
    StringBuilder sb = new StringBuilder("🧹 <b>Sistema reiniciado</b>\n\n");
    sb.append(intentar("Donaciones", donaciones::reset));
    sb.append(intentar("Donadores", donadores::reset));
    sb.append(intentar("Logística", logistica::limpiarBase));
    sb.append(intentar("Incentivos", incentivos::limpiar));
    return sb.append("\nAhora /preparar para cargar las precondiciones.\n").toString();
  }

  private String intentar(String modulo, Supplier<String> operacion) {
    try {
      operacion.get();
      return "✅ <b>" + modulo + "</b> · borrado\n";
    } catch (Exception e) {
      return "⚠️ <b>" + modulo + "</b> · " + e.getMessage() + "\n";
    }
  }

  // ── Preparar ───────────────────────────────────────────────────────────────

  String preparar() {
    long suf = Instant.now().getEpochSecond();
    StringBuilder sb = new StringBuilder("🌱 <b>Precondiciones cargadas</b>\n\n");
    try {
      String identId = id(donaciones.crearIdentificador("CODIGODEBARRAS", "Codigo de barras " + suf));
      // Con CODIGODEBARRAS la descripción necesita al menos tres palabras: es regla del dominio.
      String prodId =
          id(donaciones.crearProducto("Arroz" + suf, "Arroz blanco largo fino", "alimentos", identId));
      sb.append("🏷️ Producto <b>").append(prodId).append("</b> · Arroz").append(suf).append("\n");

      String doc = String.valueOf(suf);
      doc = doc.length() > 8 ? doc.substring(doc.length() - 8) : doc;
      String donadorId =
          id(
              donadores.registrarDonador(
                  "Carlos", "Demo", 35, "carlos" + suf + "@demo.com", doc, "Av Corrientes 1234"));
      sb.append("🙋 Donador <b>").append(donadorId).append("</b> · Carlos Demo\n");

      String entidadId =
          id(
              donadores.crearEntidad(
                  "Comedor Solidario " + suf, "Av Rivadavia 5000", "1144445555", "c" + suf + "@d.com"));
      sb.append("🏢 Entidad <b>").append(entidadId).append("</b>\n");

      try {
        logistica.crearDeposito(
            depositoPorDefecto, "Deposito Central UTN", "Av Medrano 951", 5000, "SUB_ATENDIDOS");
        sb.append("🏬 Depósito <b>").append(depositoPorDefecto).append("</b>\n");
      } catch (Exception e) {
        sb.append("⚠️ Depósito ")
            .append(depositoPorDefecto)
            .append(": ")
            .append(e.getMessage())
            .append("\n");
      }

      try {
        incentivos.crearInsignia("ins-" + suf, "Solidario " + suf, "Donacion realizada");
        incentivos.crearMision(
            "mis-" + suf, "Mision Solidaria " + suf, "ins-" + suf, "OCASIONAL", "COLABORADOR",
            "COMPLETITUD");
        sb.append("🏅 Insignia y misión creadas\n");
      } catch (Exception e) {
        sb.append("⚠️ Incentivos: ").append(e.getMessage()).append("\n");
      }

      String necesidadId =
          id(
              donadores.altaNecesidad(
                  entidadId, 8, "Arroz para el comedor mensual", 20, prodId, "EXTRAORDINARIA"));
      sb.append("📋 Necesidad <b>").append(necesidadId).append("</b> · 20 unidades\n");

      sb.append("\nSeguí con: <code>/donarcomo ")
          .append(donadorId)
          .append(";")
          .append(prodId)
          .append(";10;Diez kilos de arroz</code>\n");
      return sb.toString();
    } catch (Exception e) {
      return sb + "\n⚠️ Se cortó acá: " + e.getMessage();
    }
  }

  // ── Estado ─────────────────────────────────────────────────────────────────

  String estado() {
    JsonNode productos = leer(donaciones::listarProductos);
    int necesidades = 0;
    int pendientes = 0;
    int stock = 0;
    int recorridos = 0;
    if (productos != null && productos.isArray()) {
      for (JsonNode p : productos) {
        if (recorridos++ >= PRODUCTOS_A_RECORRER) {
          break;
        }
        String id = p.path("id").asText("");
        JsonNode ns = leer(() -> donadores.necesidadesDeProducto(id));
        if (ns != null && ns.isArray()) {
          for (JsonNode n : ns) {
            necesidades++;
            if (n.path("cantidadActual").asInt(0) < n.path("cantidadObjetivo").asInt(1)) {
              pendientes++;
            }
          }
        }
        // El listado de depósitos trae el stock vacío aunque haya unidades: el dato real está acá.
        JsonNode s = leer(() -> logistica.consultarStock(id));
        if (s != null) {
          stock += s.path("disponible").asInt(0);
        }
      }
    }

    StringBuilder sb = new StringBuilder("📊 <b>Estado del sistema</b>\n\n");
    sb.append("🎁 <b>Donaciones</b> · ")
        .append(cuenta(productos, "productos"))
        .append(" · ")
        .append(cuenta(leer(donaciones::listarDonaciones), "donaciones"))
        .append("\n");
    sb.append("🙋 <b>Donadores</b> · ")
        .append(cuenta(leer(donadores::listarDonadores), "donadores"))
        .append(" · ")
        .append(cuenta(leer(donadores::listarEntidades), "entidades"))
        .append(" · ")
        .append(necesidades)
        .append(" necesidades (")
        .append(pendientes)
        .append(" pendientes)\n");
    sb.append("🏬 <b>Logística</b> · ")
        .append(cuenta(leer(logistica::listarDepositos), "depósitos"))
        .append(" · ")
        .append(stock)
        .append(" unidades en stock\n");
    sb.append("🏅 <b>Incentivos</b> · ")
        .append(cuenta(leer(incentivos::listarInsignias), "insignias"))
        .append(" · ")
        .append(cuenta(leer(incentivos::listarMisiones), "misiones"))
        .append("\n");
    return sb.toString();
  }

  // ── Guion ──────────────────────────────────────────────────────────────────

  String guion() {
    return """
        🎬 <b>Cómo mostrar el sistema</b>

        <b>Preparación</b>
        /reiniciar — vacía las cuatro bases
        /preparar — carga las precondiciones de todos los flujos
        /estado — cómo está todo antes de empezar

        <b>Los flujos, en orden</b>
        1. /altanecesidad — una entidad pide algo
        2. /donarcomo — el flujo principal: toca tres módulos
        3. /reportarentrega — recién acá la necesidad se satisface
        4. /quejar — la donación deja de estar aceptada
        5. /procesardonador — Incentivos evalúa la misión
        6. /misestadisticas — cómo quedó el donador

        Cada operación muestra qué cambió en cada módulo y el número de traza, que sirve para
        buscar esa misma operación en los logs de Datadog.
        """;
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  private String id(String respuesta) throws Exception {
    return MAPPER.readTree(respuesta).path("id").asText();
  }

  private JsonNode leer(Supplier<String> consulta) {
    try {
      return MAPPER.readTree(consulta.get());
    } catch (Exception e) {
      return null;
    }
  }

  private String cuenta(JsonNode lista, String plural) {
    if (lista == null) {
      return "? " + plural;
    }
    return (lista.isArray() ? lista.size() : 0) + " " + plural;
  }
}
