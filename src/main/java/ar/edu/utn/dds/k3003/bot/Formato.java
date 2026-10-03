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
    return "👤 <b>"
        + txt(n, "nombre")
        + " "
        + txt(n, "apellido")
        + "</b>\n"
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
        + estadoConIcono(n.path("estado").asText(""))
        + categoria(n);
  }

  /**
   * Sin esto, cambiarle la categoría a un donador devolvía su ficha sin la categoría y no había
   * forma de ver que el cambio se hizo. Si todavía no tiene, no se muestra: un «—» no dice nada.
   */
  private static String categoria(JsonNode n) {
    String categoria = txt(n, "categoria");
    return "—".equals(categoria) ? "" : "\n🏅 Categoría: " + categoria;
  }

  static String listaDonadores(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay donadores registrados todavía.";
    }
    StringBuilder sb = new StringBuilder("👥 <b>Donadores</b> (" + arr.size() + ")\n");
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

  /** Cuántas quejas se listan: las de un donador por banear llegan a diez y no hace falta más. */
  private static final int QUEJAS_A_MOSTRAR = 10;

  /** @param titulo ya escapado: lo arma el bot, no viene de la API */
  static String quejas(String json, String titulo) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return "No pude leer las quejas.";
    }
    if (arr.isEmpty()) {
      return "Ese donador no tiene ninguna queja. 👍";
    }
    StringBuilder sb = new StringBuilder("⚠️ <b>" + titulo + " (" + arr.size() + ")</b>\n");
    int mostradas = 0;
    for (JsonNode q : arr) {
      if (mostradas++ >= QUEJAS_A_MOSTRAR) {
        sb.append("\n… y ").append(arr.size() - QUEJAS_A_MOSTRAR).append(" más");
        break;
      }
      sb.append("\n• donación nº ")
          .append(txt(q, "donacionID"))
          .append(q.hasNonNull("fecha") ? " · " + txt(q, "fecha") : "")
          .append("\n  ")
          .append(txt(q, "descripcion"));
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
    sb.append("📊 <b>")
        .append(txt(n, "nombre"))
        .append(" ")
        .append(txt(n, "apellido"))
        .append("</b>\n\n")
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
    return "🏢 <b>"
        + txt(n, "razonSocial")
        + "</b>  —  nº "
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
    StringBuilder sb = new StringBuilder("🏢 <b>Entidades</b> (" + arr.size() + ")\n");
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

    return "📋 <b>Necesidad nº "
        + txt(n, "id")
        + "</b>\n"
        + txt(n, "descripcion")
        + "\n\n"
        + barra(actual, objetivo)
        + "  "
        + actual
        + " de "
        + objetivo
        + (actual >= objetivo && objetivo > 0 ? "  ✅ completa" : "")
        + (actual < objetivo ? "\nFaltan <b>" + (objetivo - actual) + "</b> unidades" : "")
        + "\n\n"
        + urgencia(n.path("nivelDeUrgencia").asInt(0))
        + "\n📦 Producto nº "
        + txt(n, "productoSolicitadoID")
        + "\n🔁 "
        + capitalizar(n.path("tipo").asText(""))
        + "\n🏢 Entidad nº "
        + txt(n, "entidadID");
  }

  /**
   * Una necesidad en un renglón, para los listados por producto.
   *
   * <p>Lo que se mira primero en un listado es cuánto falta, así que va escrito y no solo en la
   * barra.
   */
  static String necesidadEnLista(JsonNode n) {
    int objetivo = n.path("cantidadObjetivo").asInt(0);
    int actual = n.path("cantidadActual").asInt(0);
    return "• nº "
        + txt(n, "id")
        + " · entidad nº "
        + txt(n, "entidadID")
        + " · "
        + barra(actual, objetivo)
        + " "
        + actual
        + "/"
        + objetivo
        + (actual < objetivo ? " · faltan " + (objetivo - actual) : "")
        + "\n  "
        + txt(n, "descripcion")
        + " · urgencia "
        + n.path("nivelDeUrgencia").asInt(0);
  }

  // ── Donación ───────────────────────────────────────────────────────────────

  /**
   * Una donación tal como la devuelve Donaciones: el estado es el de ahora. El módulo no guarda por
   * qué estados pasó, así que no se muestra un historial que no existe.
   */
  static String donacion(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    return "📦 <b>Donación nº "
        + txt(n, "id")
        + "</b>\n"
        + txt(n, "descripcion")
        + "\n\n"
        + n.path("cantidad").asInt(0)
        + " unidades del producto nº "
        + txt(n, "productoID")
        + "\n"
        + estadoDonacion(n.path("estado").asText(""))
        + "\n🙋 Donador nº "
        + txt(n, "donadorID")
        + "\n🏬 Depósito "
        + txt(n, "depositoID");
  }

  /** Cuántas donaciones se listan como mucho: las precondiciones de la demo cargan veinte. */
  private static final int DONACIONES_A_MOSTRAR = 30;

  /**
   * Un listado de donaciones para el admin.
   *
   * @param conDonador en el listado de todas importa de quién es cada una; en el de un donador no
   */
  static String donaciones(String json, String titulo, String siNoHay, boolean conDonador) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return siNoHay;
    }
    StringBuilder sb = new StringBuilder("📦 <b>" + titulo + "</b> (" + arr.size() + ")\n");
    int mostradas = 0;
    for (JsonNode n : arr) {
      if (mostradas++ >= DONACIONES_A_MOSTRAR) {
        sb.append("\n… y ").append(arr.size() - DONACIONES_A_MOSTRAR).append(" más");
        break;
      }
      sb.append("\n• nº ")
          .append(txt(n, "id"))
          .append(conDonador ? " · donador nº " + txt(n, "donadorID") : "")
          .append(" · ")
          .append(n.path("cantidad").asInt(0))
          .append(" u. del producto nº ")
          .append(txt(n, "productoID"))
          .append("\n  ")
          .append(estadoDonacion(n.path("estado").asText("")));
    }
    return sb.toString();
  }

  // ── Catálogo de Donaciones ─────────────────────────────────────────────────

  static String producto(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    return "📦 <b>Producto nº "
        + txt(n, "id")
        + "</b> · "
        + txt(n, "nombre")
        + "\n"
        + txt(n, "descripcion")
        + "\n🗂️ Categoría "
        + txt(n, "categoriaID")
        + " · identificador nº "
        + txt(n, "identificadorID");
  }

  static String listaIdentificadores(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay identificadores cargados todavía. /crearidentificador da de alta uno.";
    }
    StringBuilder sb = new StringBuilder("🏷️ <b>Identificadores</b> (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• nº ")
          .append(txt(n, "id"))
          .append(" · ")
          .append(nombreDelTipo(n))
          .append(" · ")
          .append(txt(n, "descripcion"));
    }
    return sb.append(
            "\n\n<i>Con código de barras, la descripción del producto necesita 3 o más palabras; "
                + "con QR, el nombre una cantidad par de letras.</i>")
        .toString();
  }

  private static String nombreDelTipo(JsonNode identificador) {
    return switch (identificador.path("tipo").asText("").toUpperCase()) {
      case "CODIGODEBARRAS" -> "Código de barras";
      case "QR" -> "QR";
      default -> txt(identificador, "tipo");
    };
  }

  static String listaDonaciones(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "Todavía no hiciste ninguna donación.";
    }
    StringBuilder sb = new StringBuilder("📦 <b>Tus donaciones</b> (" + arr.size() + ")\n");
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

  // ── Identificador ──────────────────────────────────────────────────────────

  /**
   * La regla que cada tipo le impone a los productos se repite acá porque recién se nota al crear
   * un producto, y ahí el rechazo llega sin que se entienda de dónde sale. Es solo informativa: la
   * aplica Donaciones.
   */
  static String identificador(String json) {
    JsonNode n = parsear(json);
    if (n == null) {
      return json;
    }
    String tipo = n.path("tipo").asText("").toUpperCase();
    String regla =
        switch (tipo) {
          case "CODIGODEBARRAS" ->
              "\n\n<i>Los productos con este identificador necesitan una descripción de 3 o más "
                  + "palabras.</i>";
          case "QR" ->
              "\n\n<i>Los productos con este identificador necesitan un nombre con una cantidad par "
                  + "de letras.</i>";
          default -> "";
        };
    return "🏷️ <b>Identificador nº "
        + txt(n, "id")
        + "</b>\n"
        + nombreDelTipo(n)
        + " · "
        + txt(n, "descripcion")
        + regla;
  }

  // ── Logística ──────────────────────────────────────────────────────────────

  /**
   * Los depósitos con su capacidad y, si viene, su stock.
   *
   * <p>Entiende las dos formas en que los devuelve Logística: {@code /api/depositos} trae el id en
   * {@code depositoid} y el stock real como número; {@code /depositos} trae {@code id} y el stock
   * como una lista que llega vacía aunque haya unidades. De esa no se muestra el stock: sería un
   * cero que nadie contó.
   */
  static String listaDepositos(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay depósitos registrados todavía.";
    }
    StringBuilder sb = new StringBuilder("🏬 <b>Depósitos</b> (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      int capacidad = n.path("capacidadMaxima").asInt(0);
      sb.append("\n• <b>")
          .append(esc(primero(n, "depositoid", "id")))
          .append("</b>")
          .append(n.has("nombre") && !n.path("nombre").asText("").isBlank() ? " — " + txt(n, "nombre") : "")
          .append("\n  📍 ")
          .append(txt(n, "direccion"));
      if (n.path("stockActual").isNumber()) {
        sb.append("\n  📦 Stock: ")
            .append(n.path("stockActual").asInt(0))
            .append(" de ")
            .append(capacidad)
            .append(" unidades");
      } else {
        sb.append("\n  📦 Capacidad: ").append(capacidad);
      }
      if (!n.path("algoritmo").asText("").isBlank()) {
        sb.append("\n  ⚙️ ").append(nombreDelAlgoritmo(n.path("algoritmo").asText("")));
      }
    }
    return sb.toString();
  }

  /** Logística escribe distinto el mismo algoritmo según el endpoint: acá se muestran igual. */
  private static String nombreDelAlgoritmo(String algoritmo) {
    return switch (algoritmo.toUpperCase().replaceAll("[^A-Z]", "")) {
      case "SUBATENDIDOS" -> "Sub-atendidos";
      case "PRIOSCORE", "PRIORIDADPORSCORE" -> "Prioridad por score";
      case "NULL" -> "Sin algoritmo configurado";
      default -> esc(algoritmo);
    };
  }

  /** El stock de un producto en cada depósito, como lo devuelve {@code /stock/{id}/detalle}. */
  static String stockPorDeposito(String productoId, String json) {
    JsonNode n = parsear(json);
    if (n == null || !n.isObject()) {
      return json;
    }
    JsonNode depositos = n.path("depositos");
    StringBuilder sb =
        new StringBuilder("📦 <b>Stock del producto nº " + esc(productoId) + "</b>\n");
    if (!depositos.isArray() || depositos.isEmpty()) {
      return sb.append("\nNo hay unidades guardadas de ese producto en ningún depósito.").toString();
    }
    for (JsonNode d : depositos) {
      sb.append("\n• ")
          .append(esc(primero(d, "depositoid", "depositoID")))
          .append(" · ")
          .append(d.path("disponibleEnDeposito").asInt(0))
          .append(" unidades");
    }
    return sb.append("\n\nTotal: <b>")
        .append(n.path("totalDisponible").asInt(0))
        .append("</b> unidades")
        .toString();
  }

  /** Cuántos paquetes pendientes se listan como mucho, para que el mensaje entre en Telegram. */
  private static final int PAQUETES_A_MOSTRAR = 25;

  /**
   * Los paquetes armados que esperan la entrega.
   *
   * <p>Los campos vienen en minúscula ({@code paqueteid}, {@code necesidadid}) aunque el Swagger de
   * Logística los declare en camelCase: se leen los dos.
   */
  static String listaAsignaciones(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay paquetes pendientes de entrega.";
    }
    StringBuilder sb =
        new StringBuilder("📦 <b>Paquetes pendientes de entrega</b> (" + arr.size() + ")\n");
    int mostrados = 0;
    for (JsonNode a : arr) {
      if (mostrados++ >= PAQUETES_A_MOSTRAR) {
        sb.append("\n… y ").append(arr.size() - PAQUETES_A_MOSTRAR).append(" más");
        break;
      }
      sb.append("\n• <b>")
          .append(esc(primero(a, "paqueteid", "paqueteID")))
          .append("</b> → necesidad nº ")
          .append(esc(primero(a, "necesidadid", "necesidadID")))
          .append("\n  ")
          .append(a.path("cantidad").asInt(0))
          .append(" unidades del producto nº ")
          .append(esc(primero(a, "productoid", "productoID")))
          .append(" · ")
          .append(origenCorto(a.path("origen").asText("")));
    }
    return sb.append("\n\n/paquete muestra uno en detalle.").toString();
  }

  /** Un paquete, como lo devuelve {@code /api/asignaciones/paquetes/{id}}. */
  static String asignacion(String json) {
    JsonNode a = parsear(json);
    if (a == null || !a.isObject()) {
      return json;
    }
    String estado = a.path("estado").asText("").toUpperCase();
    String donacion = primero(a, "donacionid", "donacionID");
    String fecha = a.path("fecha").asText("");
    return "📦 <b>Paquete "
        + esc(primero(a, "paqueteid", "paqueteID"))
        + "</b>\n"
        + switch (estado) {
          case "ASIGNADA" -> "⏳ Asignado, pendiente de entrega";
          case "COMPLETADA" -> "✅ Entregado";
          default -> "• " + esc(estado);
        }
        + "\n\n"
        + a.path("cantidad").asInt(0)
        + " unidades del producto nº "
        + esc(primero(a, "productoid", "productoID"))
        + " para la necesidad nº "
        + esc(primero(a, "necesidadid", "necesidadID"))
        + "\n"
        + (donacion.isBlank() ? "🏬 Sin donación: salió del stock" : "🎁 Donación nº " + esc(donacion))
        + "\n🔀 Asignado "
        + origenLargo(a.path("origen").asText(""))
        + (fecha.length() >= 16 ? "\n📅 " + esc(fecha.substring(0, 16).replace('T', ' ')) : "");
  }

  private static String origenCorto(String origen) {
    return switch (origen.toUpperCase()) {
      case "MATCHMAKING" -> "matchmaking";
      case "SOLICITUD_DONADORES" -> "solicitud";
      default -> esc(origen.toLowerCase());
    };
  }

  /** Los dos orígenes que conoce Logística: quien lo usa para algo es Incentivos. */
  private static String origenLargo(String origen) {
    return switch (origen.toUpperCase()) {
      case "MATCHMAKING" -> "por matchmaking, al donar";
      case "SOLICITUD_DONADORES" -> "al crear la necesidad, con stock que ya había";
      default -> esc(origen);
    };
  }

  static String stock(String productoId, String json) {
    JsonNode n = parsear(json);
    int disponible = 0;
    if (n != null) {
      if (n.has("disponible")) {
        disponible = n.path("disponible").asInt(0);
      } else if (n.isNumber()) {
        disponible = n.asInt(0);
      } else if (n.has("cantidad")) {
        disponible = n.path("cantidad").asInt(0);
      } else if (n.has("stock")) {
        disponible = n.path("stock").asInt(0);
      }
    } else {
      try {
        disponible = Integer.parseInt(json.trim());
      } catch (Exception ignored) {
        return json;
      }
    }
    return "📦 <b>Stock de producto nº " + esc(productoId) + "</b>\n"
        + "Disponibles en depósito: <b>" + disponible + "</b> unidades";
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  static String listaInsignias(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay insignias cargadas en Incentivos todavía.";
    }
    StringBuilder sb = new StringBuilder("🏅 <b>Catálogo de Insignias</b> (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• <b>")
          .append(txt(n, "nombre"))
          .append("</b> (ID: ")
          .append(txt(n, "id"))
          .append(")\n  ")
          .append(txt(n, "descripcion"));
    }
    return sb.toString();
  }

  static String listaMisiones(String json) {
    JsonNode arr = parsear(json);
    if (arr == null || !arr.isArray()) {
      return json;
    }
    if (arr.isEmpty()) {
      return "No hay misiones cargadas en Incentivos todavía.";
    }
    StringBuilder sb = new StringBuilder("🎯 <b>Misiones de Incentivos</b> (" + arr.size() + ")\n");
    for (JsonNode n : arr) {
      sb.append("\n• <b>")
          .append(txt(n, "nombre"))
          .append("</b> (ID: ")
          .append(txt(n, "id"))
          .append(")\n  🏅 Insignia: ")
          .append(txt(n, "insigniaID"))
          .append("\n  📈 Nivel: ")
          .append(txt(n, "categoriaInicio"))
          .append(" ➔ ")
          .append(txt(n, "categoriaFin"));
    }
    return sb.toString();
  }

  /**
   * Las insignias y la misión en curso de un donador.
   *
   * @param misionJson null si no tiene misión: Incentivos lo dice con un 404
   */
  static String progreso(String donadorId, String insigniasJson, String misionJson) {
    StringBuilder sb =
        new StringBuilder("🏅 <b>Progreso del donador nº " + esc(donadorId) + "</b>\n\n");
    JsonNode insignias = parsear(insigniasJson);
    if (insignias == null || !insignias.isArray()) {
      sb.append("⚠️ No pude leer las insignias.");
    } else if (insignias.isEmpty()) {
      sb.append("✨ Todavía no ganó ninguna insignia.");
    } else {
      sb.append("✨ Insignias (").append(insignias.size()).append(")");
      for (JsonNode i : insignias) {
        // Incentivos devuelve las insignias enteras; si alguna vez fueran solo los ids, también.
        String nombre = i.isTextual() ? esc(i.asText()) : txt(i, "nombre");
        sb.append("\n   • ").append("—".equals(nombre) ? txt(i, "id") : nombre);
      }
    }
    JsonNode mision = misionJson == null ? null : parsear(misionJson);
    if (mision == null || !mision.isObject()) {
      return sb.append("\n\n🎯 Sin misión en curso.").toString();
    }
    String nombre = txt(mision, "nombre");
    return sb.append("\n\n🎯 Misión en curso: <b>")
        .append("—".equals(nombre) ? txt(mision, "id") : nombre)
        .append("</b>\n   de ")
        .append(capitalizar(mision.path("categoriaInicio").asText("")))
        .append(" a ")
        .append(capitalizar(mision.path("categoriaFin").asText("")))
        .append(" · otorga la insignia ")
        .append(txt(mision, "insigniaID"))
        .toString();
  }

  // ── Piezas ─────────────────────────────────────────────────────────────────

  /** Logística devuelve los campos en minúscula aunque su Swagger los declare en camelCase. */
  private static String primero(JsonNode nodo, String... campos) {
    for (String campo : campos) {
      String valor = nodo.path(campo).asText("");
      if (!valor.isBlank() && !"null".equals(valor)) {
        return valor;
      }
    }
    return "";
  }

  /**
   * Lee un campo y lo deja listo para meter en el HTML del mensaje.
   *
   * <p>Escapa lo que Telegram interpretaría como etiqueta: si alguien registra una entidad
   * llamada «Pan &amp; Circo», sin escapar el mensaje entero sería rechazado.
   */
  private static String txt(JsonNode n, String campo) {
    String v = n.path(campo).asText("");
    return v.isBlank() || "null".equals(v) ? "—" : esc(v);
  }

  /**
   * Package-private para que los clientes de la API escapen también los mensajes de error: el
   * motivo de un rechazo viene del módulo y puede traer un {@code <} que Telegram tomaría como
   * etiqueta.
   */
  static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
  /** Package-private para que Impacto muestre el progreso con la misma barra que el resto. */
  static String barra(int actual, int objetivo) {
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
