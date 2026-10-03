package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
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

  /** Lo que se espera por cada módulo al despertarlo antes de dar la respuesta. */
  private static final int ESPERA_AL_DESPERTAR_SEGUNDOS = 20;

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

  // ── Despertar ──────────────────────────────────────────────────────────────

  /**
   * Consulta los cuatro módulos y dice cuáles contestan.
   *
   * <p>En el plan gratuito de Render los servicios se duermen sin tráfico. Conviene correrlo unos
   * minutos antes de mostrar el sistema, para que la primera operación no se coma la espera.
   */
  String despertar() {
    Map<String, Boolean> responden = contactar();

    StringBuilder sb = new StringBuilder("⏰ <b>Despertando los módulos</b>\n\n");
    responden.forEach(
        (modulo, responde) ->
            sb.append(responde ? "✅ <b>" : "⏳ <b>")
                .append(modulo)
                .append(responde ? "</b> · responde\n" : "</b> · arrancando (el pedido ya lo despertó)\n"));

    if (responden.containsValue(false)) {
      sb.append(
          "\nArrancar de cero le lleva a Render uno o dos minutos. Repetí /despertar hasta que "
              + "los cuatro respondan: cada intento los empuja un poco más.\n");
    }
    return sb.toString();
  }

  /**
   * Toca los cuatro módulos a la vez y dice cuáles contestaron.
   *
   * <p>La espera es corta a propósito: un servicio de Render que arranca de cero tarda uno o dos
   * minutos, y no tiene sentido dejar el chat mudo todo ese rato. El pedido que se corta igual
   * dispara el arranque, así que el intento siguiente lo encuentra despierto.
   */
  private Map<String, Boolean> contactar() {
    Map<String, Supplier<String>> consultas = new java.util.LinkedHashMap<>();
    consultas.put("Donaciones", donaciones::listarProductos);
    consultas.put("Donadores", donadores::listarDonadores);
    consultas.put("Logística", logistica::listarDepositos);
    consultas.put("Incentivos", incentivos::listarInsignias);

    Map<String, java.util.concurrent.CompletableFuture<Boolean>> pendientes =
        new java.util.LinkedHashMap<>();
    consultas.forEach(
        (modulo, consulta) ->
            pendientes.put(
                modulo,
                java.util.concurrent.CompletableFuture.supplyAsync(() -> contesta(consulta))
                    .completeOnTimeout(
                        false, ESPERA_AL_DESPERTAR_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS)));

    Map<String, Boolean> responden = new java.util.LinkedHashMap<>();
    pendientes.forEach((modulo, futuro) -> responden.put(modulo, futuro.join()));
    return responden;
  }

  private boolean contesta(Supplier<String> consulta) {
    try {
      consulta.get();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ── Reiniciar ──────────────────────────────────────────────────────────────

  /** Lo que se espera antes de repetir un borrado: Render a veces contesta 502 mientras arranca. */
  private static final long PAUSA_ANTES_DE_REPETIR_MS = 2000;

  /** Cómo terminó el borrado de un módulo. Sin motivo y sin listo, es que no contestó a tiempo. */
  private record Borrado(boolean listo, String motivo) {}

  private static final Borrado BORRADO = new Borrado(true, null);
  private static final Borrado SIN_RESPUESTA = new Borrado(false, null);

  String reiniciar() {
    // Primero se despiertan con consultas, como en preparar: en Render el primer pedido a un
    // servicio dormido se pierde despertándolo, y si ese pedido es el borrado, el chat se queda
    // esperando hasta darlo por perdido. Se borra igual aunque alguno no haya contestado: la
    // consulta que se cortó ya lo puso a arrancar, y para el borrado puede estar listo.
    Map<String, Boolean> despiertos = contactar();

    // Cada módulo tiene su propia base: no hay un orden que respetar, y en paralelo uno caído no
    // deja el chat esperando por los otros tres.
    Map<String, Supplier<String>> borrados = new java.util.LinkedHashMap<>();
    borrados.put("Donaciones", donaciones::reset);
    borrados.put("Donadores", donadores::reset);
    borrados.put("Logística", logistica::limpiarBase);
    borrados.put("Incentivos", incentivos::limpiar);

    Map<String, java.util.concurrent.CompletableFuture<Borrado>> pendientes =
        new java.util.LinkedHashMap<>();
    borrados.forEach(
        (modulo, borrado) ->
            pendientes.put(
                modulo,
                java.util.concurrent.CompletableFuture.supplyAsync(() -> borrar(borrado))
                    .completeOnTimeout(
                        SIN_RESPUESTA,
                        ESPERA_AL_DESPERTAR_SEGUNDOS,
                        java.util.concurrent.TimeUnit.SECONDS)));

    StringBuilder detalle = new StringBuilder();
    boolean todoBorrado = true;
    boolean hayDormidos = false;
    for (Map.Entry<String, java.util.concurrent.CompletableFuture<Borrado>> pendiente :
        pendientes.entrySet()) {
      String modulo = pendiente.getKey();
      Borrado resultado = pendiente.getValue().join();
      if (resultado.listo()) {
        detalle.append("✅ <b>").append(modulo).append("</b> · borrado\n");
        continue;
      }
      todoBorrado = false;
      // Si no contestó ni a la consulta ni al borrado, lo más probable es que siga arrancando. Si
      // contestó la consulta y rechazó el borrado, el motivo lo dice el módulo.
      if (resultado.motivo() == null || !despiertos.getOrDefault(modulo, false)) {
        hayDormidos = true;
        detalle
            .append("😴 <b>")
            .append(modulo)
            .append("</b> · no contestó: está dormido o caído\n");
      } else {
        detalle
            .append("⚠️ <b>")
            .append(modulo)
            .append("</b> · ")
            .append(resultado.motivo())
            .append("\n");
      }
    }

    if (todoBorrado) {
      return "🧹 <b>Sistema reiniciado</b>\n\n"
          + detalle
          + "\nAhora /preparar para cargar las precondiciones.\n";
    }
    // No se sugiere /preparar: cargar sobre una base a medio borrar mezcla datos viejos y nuevos.
    return "🧹 <b>Reinicio incompleto</b>\n\n"
        + detalle
        + "\n"
        + (hayDormidos ? "Render tarda uno o dos minutos en despertar un servicio. " : "")
        + "Repetí /reiniciar en un minuto: borrar de nuevo lo que ya quedó vacío no cambia nada.\n";
  }

  /**
   * Borra, y si falla lo intenta una vez más.
   *
   * <p>Repetirlo es seguro porque borrar es idempotente: dos borrados dejan la base igual de vacía
   * que uno. Con un alta no se podría, porque sin respuesta no se sabe si llegó, y repetirla puede
   * dejar dos donde había una.
   */
  private Borrado borrar(Supplier<String> borrado) {
    try {
      borrado.get();
      return BORRADO;
    } catch (Exception primera) {
      esperar(PAUSA_ANTES_DE_REPETIR_MS);
      try {
        borrado.get();
        return BORRADO;
      } catch (Exception segunda) {
        String motivo = segunda.getMessage();
        return new Borrado(false, motivo == null ? "falló sin decir por qué" : motivo);
      }
    }
  }

  private static void esperar(long milisegundos) {
    try {
      Thread.sleep(milisegundos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ── Preparar ───────────────────────────────────────────────────────────────

  String preparar() {
    long suf = Instant.now().getEpochSecond();
    StringBuilder sb = new StringBuilder("🌱 <b>Precondiciones cargadas</b>\n\n");
    // Se despiertan con consultas antes de escribir: si el primer POST se pierde despertando al
    // servicio, no se puede reintentar sin arriesgar un duplicado.
    Map<String, Boolean> responden = contactar();

    // Sin estos tres no hay nada que cargar, y cargar la mitad es peor que no cargar nada: quedan
    // datos sueltos que no sirven para ningún flujo y hay que limpiarlos a mano.
    String dormidos =
        java.util.stream.Stream.of("Donaciones", "Donadores", "Logística")
            .filter(modulo -> !responden.getOrDefault(modulo, false))
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    if (!dormidos.isEmpty()) {
      return "⚠️ <b>No se cargó nada</b>\n\nTodavía no contestan: "
          + dormidos
          + ".\n\nArrancar de cero le lleva a Render uno o dos minutos. Mandá /despertar hasta que "
          + "los cuatro respondan y volvé a intentar.\n";
    }

    try {
      String identId = id(donaciones.crearIdentificador("CODIGODEBARRAS", "Codigo de barras " + suf));
      // Con CODIGODEBARRAS la descripción necesita al menos tres palabras: es regla del dominio.
      String prodId =
          id(donaciones.crearProducto("Arroz" + suf, "Arroz blanco largo fino", "alimentos", identId));
      sb.append("🏷️ Producto <b>").append(prodId).append("</b> · Arroz").append(suf).append("\n");

      String doc = String.valueOf(suf);
      doc = doc.length() > 8 ? doc.substring(doc.length() - 8) : doc;
      // Con Donadores van las versiones Raw: las otras le anteponen «Donador registrado: » al
      // JSON, y leerle el id a eso cortaba /preparar justo después del producto.
      String donadorId =
          id(
              donadores.registrarDonadorRaw(
                  "Carlos", "Demo", 35, "carlos" + suf + "@demo.com", doc, "Av Corrientes 1234"));
      sb.append("🙋 Donador <b>").append(donadorId).append("</b> · Carlos Demo\n");

      String entidadId =
          id(
              donadores.crearEntidadRaw(
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
              donadores.altaNecesidadRaw(
                  entidadId, 8, "Arroz para el comedor mensual", 20, prodId, "EXTRAORDINARIA"));
      sb.append("📋 Necesidad <b>").append(necesidadId).append("</b> · 20 unidades\n");

      // El comando guiado pide los datos de a uno; lo que hay que tener a mano son los números.
      sb.append("\nSeguí con /donarcomo: cuando te pregunte, el donador es el <b>")
          .append(donadorId)
          .append("</b> y el producto el <b>")
          .append(prodId)
          .append("</b>.\n");
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
        /despertar — los servicios de Render se duermen; hacelo unos minutos antes
        /reiniciar — vacía las cuatro bases
        /preparar — carga las precondiciones de todos los flujos
        /estado — cómo está todo antes de empezar

        <b>Los flujos, en orden</b>
        1. /altanecesidad — una entidad pide algo
        2. /donarcomo — el flujo principal: toca tres módulos
        3. /reportarentrega — recién acá la necesidad se satisface y la donación queda entregada
        4. /quejar — sobre la donación entregada en el paso 3: solo se puede quejar de una
           donación entregada, y deja de estar aceptada
        5. /procesardonador — Incentivos evalúa la misión
        6. /misestadisticas — cómo quedó el donador

        Cada operación muestra qué cambió en cada módulo y el número de traza, que sirve para
        buscar esa misma operación en los logs de Datadog.

        Con 5 quejas el donador queda sospechoso y con 10 baneado. Cada queja tiene que ser
        sobre una donación entregada distinta, así que para mostrar un baneo es más rápido
        /estadodonador.
        """;
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  /**
   * El número de lo que se acaba de crear. Necesita el JSON tal cual lo devuelve el módulo: un
   * método que le anteponga un texto no sirve acá.
   *
   * <p>Sin id se corta en vez de seguir: el paso siguiente lo usaría vacío y el error aparecería
   * más adelante, lejos de su causa.
   */
  private String id(String respuesta) throws Exception {
    String id = MAPPER.readTree(respuesta).path("id").asText("");
    if (id.isBlank()) {
      throw new IllegalStateException("el módulo no devolvió el número de lo que creó");
    }
    return id;
  }

  /** Con límite de espera: un módulo caído tarda minutos, y el estado es lo primero que se mira. */
  private JsonNode leer(Supplier<String> consulta) {
    try {
      return MAPPER.readTree(
          java.util.concurrent.CompletableFuture.supplyAsync(consulta)
              .get(6, java.util.concurrent.TimeUnit.SECONDS));
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
