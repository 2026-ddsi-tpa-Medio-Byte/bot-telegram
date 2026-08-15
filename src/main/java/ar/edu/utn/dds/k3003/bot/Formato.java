package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Convierte las respuestas JSON de la API en texto legible para Telegram.
 *
 * <p>Antes el bot devolvía el JSON crudo, que en el celular se lee mal y expone la estructura
 * interna. Acá cada entidad tiene su propio formato, mostrando solo lo que le importa a quien
 * está del otro lado.
 */
final class Formato {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Formato() {}

  static JsonNode parsear(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  // ── Donador ────────────────────────────────────────────────────────────────

  static String donador(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    return "👤 *"
        + txt(n, "nombre")
        + " "
        + txt(n, "apellido")
        + "*\n"
        + "Nº "
        + txt(n, "id")
        + " · documento "
        + txt(n, "nroDocumento")
        + "\n"
        + txt(n, "edad")
        + " años · "
        + txt(n, "email")
        + "\n"
        + txt(n, "domicilio")
        + "\n"
        + estadoConIcono(n.path("estado").asText(""));
  }

  static String listaDonadores(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay donadores registrados todavía.";
    }
    StringBuilder sb = new StringBuilder("👥 *Donadores* (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• ")
          .append(txt(n, "nombre"))
          .append(" ")
          .append(txt(n, "apellido"))
          .append("  —  nº ")
          .append(txt(n, "id"))
          .append("\n  ")
          .append(iconoEstado(n.path("estado").asText("")))
          .append(" ")
          .append(minuscula(n.path("estado").asText("")));
    }
    return sb.toString();
  }

  static String estadisticas(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    JsonNode insignias = n.path("insigniasID");
    int cuantas = insignias.isArray() ? insignias.size() : 0;

    StringBuilder sb = new StringBuilder();
    sb.append("📊 *")
        .append(txt(n, "nombre"))
        .append(" ")
        .append(txt(n, "apellido"))
        .append("*\n\n")
        .append(estadoConIcono(n.path("estado").asText("")))
        .append("\n🏅 Categoría: ")
        .append(txt(n, "categoria"));

    if (cuantas > 0) {
      sb.append("\n\n✨ Insignias ganadas: ").append(cuantas);
      for (JsonNode i : insignias) {
        sb.append("\n   • ").append(i.asText());
      }
    } else {
      sb.append("\n\n✨ Todavía no ganaste ninguna insignia.");
    }

    String mision = n.path("misionActualID").asText("");
    if (!mision.isBlank() && !"null".equals(mision)) {
      sb.append("\n🎯 Misión en curso: ").append(mision);
    }
    return sb.toString();
  }

  // ── Entidad ────────────────────────────────────────────────────────────────

  static String entidad(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    return "🏢 *"
        + txt(n, "razonSocial")
        + "*  —  nº "
        + txt(n, "id")
        + "\n📍 "
        + txt(n, "domicilio")
        + "\n📞 "
        + txt(n, "telefono")
        + "\n✉️ "
        + txt(n, "correo");
  }

  static String listaEntidades(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay entidades cargadas todavía.";
    }
    StringBuilder sb = new StringBuilder("🏢 *Entidades* (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• ")
          .append(txt(n, "razonSocial"))
          .append("  —  nº ")
          .append(txt(n, "id"))
          .append("\n  📍 ")
          .append(txt(n, "domicilio"));
    }
    return sb.toString();
  }

  // ── Necesidad ──────────────────────────────────────────────────────────────

  static String necesidad(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    int objetivo = n.path("cantidadObjetivo").asInt(0);
    int actual = n.path("cantidadActual").asInt(0);

    return "📋 *Necesidad nº "
        + txt(n, "id")
        + "*\n"
        + txt(n, "descripcion")
        + "\n\n"
        + barra(actual, objetivo)
        + "  "
        + actual
        + " de "
        + objetivo
        + (actual >= objetivo && objetivo > 0 ? "  ✅ completa" : "")
        + "\n\n"
        + urgencia(n.path("nivelDeUrgencia").asInt(0))
        + "\n📦 Producto nº "
        + txt(n, "productoSolicitadoID")
        + "\n🔁 "
        + capitalizar(n.path("tipo").asText(""))
        + "\n🏢 Entidad nº "
        + txt(n, "entidadID");
  }

  // ── Donación ───────────────────────────────────────────────────────────────

  static String donacion(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    return "📦 *Donación nº "
        + txt(n, "id")
        + "*\n"
        + txt(n, "descripcion")
        + "\n\n"
        + n.path("cantidad").asInt(0)
        + " unidades del producto nº "
        + txt(n, "productoID")
        + "\n"
        + estadoDonacion(n.path("estado").asText(""))
        + "\n🏬 Depósito "
        + txt(n, "depositoID");
  }

  static String listaDonaciones(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "Todavía no hiciste ninguna donación.";
    }
    StringBuilder sb = new StringBuilder("📦 *Tus donaciones* (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• nº ")
          .append(txt(n, "id"))
          .append(" · ")
          .append(n.path("cantidad").asInt(0))
          .append(" unidades\n  ")
          .append(estadoDonacion(n.path("estado").asText("")));
    }
    return sb.toString();
  }

  // ── Piezas ─────────────────────────────────────────────────────────────────

  private static String txt(JsonNode n, String campo) {
    String v = n.path(campo).asText("");
    return v.isBlank() || "null".equals(v) ? "—" : v;
  }

  private static String iconoEstado(String estado) {
    return switch (estado.toUpperCase()) {
      case "VERIFICADO" -> "✅";
      case "SOSPECHOSO" -> "⚠️";
      case "BANEADO" -> "🚫";
      default -> "•";
    };
  }

  private static String estadoConIcono(String estado) {
    if (estado.isBlank()) {
      return "";
    }
    String detalle =
        switch (estado.toUpperCase()) {
          case "VERIFICADO" -> "podés donar sin problemas";
          case "SOSPECHOSO" -> "tenés quejas acumuladas";
          case "BANEADO" -> "no podés donar";
          default -> "";
        };
    return iconoEstado(estado)
        + " Estado: "
        + capitalizar(estado)
        + (detalle.isBlank() ? "" : "  (" + detalle + ")");
  }

  private static String estadoDonacion(String estado) {
    return switch (estado.toUpperCase()) {
      case "INGRESADA" -> "⏳ Ingresada, esperando asignación";
      case "ACEPTADA" -> "✅ Aceptada y entregada";
      case "RECHAZADA" -> "❌ Rechazada";
      case "CONQUEJA" -> "⚠️ Con queja";
      default -> "• " + estado;
    };
  }

  private static String urgencia(int nivel) {
    String icono = nivel >= 8 ? "🔴" : nivel >= 5 ? "🟡" : "🟢";
    return icono + " Urgencia " + nivel + " de 10";
  }

  /** Barra de progreso de diez casilleros. */
  private static String barra(int actual, int objetivo) {
    if (objetivo <= 0) {
      return "";
    }
    int llenos = Math.min(10, Math.round(actual * 10f / objetivo));
    return "▓".repeat(llenos) + "░".repeat(10 - llenos);
  }

  private static String capitalizar(String s) {
    if (s == null || s.isBlank()) {
      return "";
    }
    return s.charAt(0) + s.substring(1).toLowerCase();
  }

  private static String minuscula(String s) {
    return s == null ? "" : s.toLowerCase();
  }
}
