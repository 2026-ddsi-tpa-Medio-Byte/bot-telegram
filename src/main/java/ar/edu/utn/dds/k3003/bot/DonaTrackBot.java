package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bot de Telegram de DonaTrack (UI - Entrega 4).
 *
 * <p>El donador entra con su número y a partir de ahí el bot lo recuerda: puede ver sus datos,
 * sus donaciones y donar sin repetir quién es. El admin tiene el manejo completo de entidades,
 * necesidades y el estado de los donadores. Las respuestas se muestran formateadas, no como el
 * JSON que devuelve la API.
 */
@Component
public class DonaTrackBot {

  private static final Logger log = LoggerFactory.getLogger(DonaTrackBot.class);

  private final TelegramClient telegram;
  private final DonadoresApiClient api;
  private final DonacionesApiClient donaciones;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;
  private final Impacto impacto;
  private final Demo demo;
  private final Formularios formularios;
  private final String depositoPorDefecto;

  private final Map<Long, Sesion> sesiones = new ConcurrentHashMap<>();

  /** Los formularios en curso, uno por chat: lo que la persona escriba es la respuesta pendiente. */
  private final Map<Long, Formulario> conversaciones = new ConcurrentHashMap<>();
  private volatile long offset = 0;
  private volatile boolean running = true;

  public DonaTrackBot(
      TelegramClient telegram,
      DonadoresApiClient api,
      DonacionesApiClient donaciones,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos,
      Impacto impacto,
      Demo demo,
      Formularios formularios,
      @Value("${deposito.default:DEP-UTN-01}") String depositoPorDefecto) {
    this.telegram = telegram;
    this.api = api;
    this.donaciones = donaciones;
    this.logistica = logistica;
    this.incentivos = incentivos;
    this.impacto = impacto;
    this.demo = demo;
    this.formularios = formularios;
    this.depositoPorDefecto = depositoPorDefecto;
  }

  @PostConstruct
  public void start() {
    if (!telegram.hayToken()) {
      log.warn(
          "TELEGRAM_BOT_TOKEN no configurado: el bot NO se inicia. "
              + "Obtené un token de @BotFather, configurá TELEGRAM_BOT_TOKEN y reiniciá.");
      return;
    }
    // No daemon: este hilo es el que mantiene viva la aplicación. La app no levanta
    // servidor web (web-application-type=none), así que con un hilo daemon el proceso
    // terminaría apenas arranca y el bot dejaría de escuchar.
    Thread t = new Thread(this::pollLoop, "telegram-poll");
    t.setDaemon(false);
    t.start();
    log.info("Bot de Telegram iniciado (long-polling). Escuchando mensajes...");
    log.info("Depósito por defecto para las donaciones: {}", depositoPorDefecto);
    log.info("Para detenerlo: Ctrl+C");
  }

  public void stop() {
    this.running = false;
  }

  private void pollLoop() {
    while (running) {
      JsonNode result = telegram.getUpdates(offset);
      if (result == null || !result.isArray()) {
        sleep(2000);
        continue;
      }
      for (JsonNode upd : result) {
        offset = upd.path("update_id").asLong() + 1;
        JsonNode msg = upd.path("message");
        if (msg.isMissingNode()) {
          continue;
        }
        long chatId = msg.path("chat").path("id").asLong();
        String text = msg.path("text").asText("").trim();
        if (!text.isEmpty()) {
          handle(chatId, text);
        }
      }
    }
  }

  /** Procesa un comando. Nunca lanza: ante error responde con el mensaje al usuario. */
  void handle(long chatId, String text) {
    String cmd = text.split("\\s+", 2)[0].toLowerCase();
    if (cmd.contains("@")) {
      cmd = cmd.substring(0, cmd.indexOf('@'));
    }
    String args = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
    Sesion s = sesiones.computeIfAbsent(chatId, k -> new Sesion());

    try {
      if (respondeUnFormulario(chatId, cmd, text)) {
        return;
      }
      switch (cmd) {
        case "/start" -> telegram.sendMessage(chatId, bienvenida());
        case "/help" -> telegram.sendMessage(chatId, menuSegun(s));
        case "/cancelar" ->
            telegram.sendMessage(chatId, "No hay nada a medio hacer. /help para ver qué podés hacer.");
        case "/salir" -> {
          s.salir();
          telegram.sendMessage(chatId, "Listo, cerraste la sesión. /start para volver a entrar.");
        }

        // ── Elegir rol ──────────────────────────────────────────────────────
        case "/soy_donador" -> {
          s.comoDonador();
          telegram.sendMessage(chatId, puertaDonador());
        }
        case "/soy_admin" -> {
          s.comoAdmin();
          telegram.sendMessage(chatId, menuAdmin());
        }

        // ── Donador: entrar ─────────────────────────────────────────────────
        case "/entrar" -> {
          String id = requerido(args, "/entrar <tu número de donador>");
          String json = api.buscarDonador(id);
          JsonNode d = Formato.parsear(json);
          String nombre = d == null ? id : d.path("nombre").asText(id);
          s.identificar(id, nombre);
          telegram.sendMessage(
              chatId, "👋 Hola <b>" + nombre + "</b>, entraste.\n\n" + menuDonadorAdentro(s));
        }
        case "/registrarse" -> {
          if (args.isBlank()) {
            iniciar(chatId, formularios.registro(s));
            return;
          }
          String[] p =
              campos(args, 6, "/registrarse nombre;apellido;edad;email;documento;domicilio");
          String json = api.registrarDonadorRaw(p[0], p[1], parseInt(p[2]), p[3], p[4], p[5]);
          JsonNode d = Formato.parsear(json);
          if (d != null && !d.path("id").asText("").isBlank()) {
            String id = d.path("id").asText();
            s.identificar(id, p[0]);
            telegram.sendMessage(
                chatId,
                "🎉 Listo <b>"
                    + p[0]
                    + "</b>, quedaste registrado con el número <b>"
                    + id
                    + "</b>.\n"
                    + "Anotátelo: con eso entrás la próxima vez con /entrar "
                    + id
                    + "\n\n"
                    + menuDonadorAdentro(s));
          } else {
            telegram.sendMessage(chatId, "Registrado: " + json);
          }
        }

        // ── Donador: ya adentro ─────────────────────────────────────────────
        case "/perfil" -> {
          exigirIdentificado(s);
          telegram.sendMessage(chatId, Formato.donador(api.buscarDonador(s.donadorId())));
        }
        case "/misdatos" -> {
          exigirIdentificado(s);
          telegram.sendMessage(chatId, Formato.donador(api.buscarDonador(s.donadorId())));
        }
        case "/misestadisticas" -> {
          exigirIdentificado(s);
          telegram.sendMessage(
              chatId, Formato.estadisticas(api.estadisticasDonador(s.donadorId())));
        }
        case "/misdonaciones" -> {
          exigirIdentificado(s);
          telegram.sendMessage(
              chatId, Formato.listaDonaciones(donaciones.misDonaciones(s.donadorId())));
        }
        case "/donar" -> {
          exigirIdentificado(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.donar(s));
            return;
          }
          String[] p = campos(args, 3, "/donar productoID;cantidad;descripcion");
          telegram.sendMessage(
              chatId,
              impacto.donar(s.donadorId(), depositoPorDefecto, p[2], p[0], parseInt(p[1])));
        }
        case "/donarcomo" -> {
          // Para la demostración: el admin recorre los flujos de punta a punta sin tener que
          // cambiar de rol y volver a entrar como donador en el medio.
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.donarComo());
            return;
          }
          String[] p = campos(args, 4, "/donarcomo donadorID;productoID;cantidad;descripcion");
          telegram.sendMessage(
              chatId, impacto.donar(p[0], depositoPorDefecto, p[3], p[1], parseInt(p[2])));
        }
        case "/productos" -> telegram.sendMessage(chatId, productos());
        case "/puedodonar" -> {
          exigirIdentificado(s);
          telegram.sendMessage(chatId, puedeDonar(s.donadorId()));
        }

        // ── Consultas abiertas ──────────────────────────────────────────────
        case "/donador" ->
            telegram.sendMessage(
                chatId, Formato.donador(api.buscarDonador(requerido(args, "/donador <número>"))));
        case "/donadores" -> telegram.sendMessage(chatId, Formato.listaDonadores(api.listarDonadores()));
        case "/estadisticas" ->
            telegram.sendMessage(
                chatId,
                Formato.estadisticas(
                    api.estadisticasDonador(requerido(args, "/estadisticas <número>"))));

        // ── Admin: entidades ────────────────────────────────────────────────
        case "/crearentidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.entidad());
            return;
          }
          String[] p = campos(args, 4, "/crearentidad razonSocial;domicilio;telefono;correo");
          String json = api.crearEntidadRaw(p[0], p[1], p[2], p[3]);
          telegram.sendMessage(chatId, "✅ Entidad creada\n\n" + Formato.entidad(json));
        }
        case "/editarentidad" -> {
          exigirAdmin(s);
          String[] p = campos(args, 5, "/editarentidad id;razonSocial;domicilio;telefono;correo");
          String json = api.editarEntidadRaw(p[0], p[1], p[2], p[3], p[4]);
          telegram.sendMessage(chatId, "✅ Entidad actualizada\n\n" + Formato.entidad(json));
        }
        case "/entidad" ->
            telegram.sendMessage(
                chatId, Formato.entidad(api.buscarEntidad(requerido(args, "/entidad <número>"))));
        case "/entidades" -> telegram.sendMessage(chatId, Formato.listaEntidades(api.listarEntidades()));

        // ── Admin: necesidades ──────────────────────────────────────────────
        case "/altanecesidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.necesidad());
            return;
          }
          String[] p =
              campos(
                  args,
                  6,
                  "/altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo");
          String json =
              api.altaNecesidadRaw(
                  p[0], parseInt(p[1]), p[2], parseInt(p[3]), p[4], p[5].toUpperCase());
          telegram.sendMessage(chatId, "✅ Necesidad creada\n\n" + Formato.necesidad(json));
        }
        case "/necesidad" ->
            telegram.sendMessage(
                chatId,
                Formato.necesidad(api.buscarNecesidad(requerido(args, "/necesidad <número>"))));
        case "/modificarnecesidad" -> {
          exigirAdmin(s);
          String[] p =
              campos(
                  args,
                  6,
                  "/modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo");
          String json =
              api.modificarNecesidadRaw(
                  p[0], parseInt(p[1]), p[2], parseInt(p[3]), p[4], p[5].toUpperCase());
          telegram.sendMessage(chatId, "✅ Necesidad actualizada\n\n" + Formato.necesidad(json));
        }
        case "/borrarnecesidad" -> {
          exigirAdmin(s);
          telegram.sendMessage(
              chatId, "🗑️ " + api.borrarNecesidad(requerido(args, "/borrarnecesidad <número>")));
        }

        // ── Admin: poder sobre los donadores ────────────────────────────────
        case "/estadodonador" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.estadoDonador());
            return;
          }
          String[] p = campos(args, 2, "/estadodonador id;VERIFICADO|SOSPECHOSO|BANEADO");
          String json = api.cambiarEstadoDonador(p[0], p[1].toUpperCase());
          telegram.sendMessage(chatId, "✅ Estado cambiado\n\n" + Formato.donador(json));
        }
        case "/categoriadonador" -> {
          exigirAdmin(s);
          String[] p = campos(args, 2, "/categoriadonador id;categoria");
          String json = api.cambiarCategoriaDonador(p[0], p[1]);
          telegram.sendMessage(chatId, "✅ Categoría cambiada\n\n" + Formato.donador(json));
        }
        case "/quejas" -> {
          exigirAdmin(s);
          telegram.sendMessage(
              chatId, quejas(requerido(args, "/quejas <número de donador>")));
        }

        // ── Logística ───────────────────────────────────────────────────────
        case "/depositos" ->
            telegram.sendMessage(chatId, Formato.listaDepositos(logistica.listarDepositos()));
        case "/stock" -> {
          String prodId = requerido(args, "/stock <productoID>");
          telegram.sendMessage(chatId, Formato.stock(prodId, logistica.consultarStock(prodId)));
        }
        case "/reportarentrega" -> {
          exigirAdmin(s);
          // Logística nombra cada paquete "paq-" + el id de la donación. Pedir el paquete sería
          // pedir un dato que nadie tiene a mano: no hay forma de listarlos.
          if (args.isBlank()) {
            iniciar(chatId, formularios.entrega());
            return;
          }
          String[] p = campos(args, 3, "/reportarentrega donacionId;productoId;cantidad");
          telegram.sendMessage(
              chatId, impacto.reportarEntrega("paq-" + p[0], p[0], p[1], parseInt(p[2])));
        }
        case "/creardeposito" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.deposito());
            return;
          }
          String[] p = campos(args, 4, "/creardeposito id;nombre;direccion;capacidad");
          logistica.crearDeposito(p[0], p[1], p[2], parseInt(p[3]), "SUB_ATENDIDOS");
          telegram.sendMessage(chatId, "🏬 Depósito <b>" + p[0] + "</b> creado.");
        }
        case "/algoritmo" -> {
          exigirAdmin(s);
          String[] p = campos(args, 2, "/algoritmo depositoId;SUB_ATENDIDOS|PRIORIDAD_POR_SCORE");
          logistica.configurarAlgoritmo(p[0], p[1].toUpperCase());
          telegram.sendMessage(
              chatId,
              "⚙️ El depósito <b>"
                  + p[0]
                  + "</b> ahora asigna con <b>"
                  + p[1].toUpperCase()
                  + "</b>.\nEs el criterio con el que elige a cuál necesidad le manda cada donación.");
        }

        // ── Incentivos ──────────────────────────────────────────────────────
        case "/insignias" ->
            telegram.sendMessage(chatId, Formato.listaInsignias(incentivos.listarInsignias()));
        case "/misiones" ->
            telegram.sendMessage(chatId, Formato.listaMisiones(incentivos.listarMisiones()));
        case "/procesardonador" -> {
          exigirAdmin(s);
          String donId = requerido(args, "/procesardonador <número de donador>");
          telegram.sendMessage(chatId, impacto.procesar(donId));
        }
        case "/crearinsignia" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.insignia());
            return;
          }
          String[] p = campos(args, 3, "/crearinsignia id;nombre;descripcion");
          incentivos.crearInsignia(p[0], p[1], p[2]);
          telegram.sendMessage(chatId, "🏅 Insignia <b>" + p[0] + "</b> creada.");
        }
        case "/crearmision" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.mision());
            return;
          }
          String[] p =
              campos(
                  args, 5, "/crearmision id;nombre;insigniaID;categoriaInicio;categoriaFin");
          incentivos.crearMision(p[0], p[1], p[2], p[3], p[4], "COMPLETITUD");
          telegram.sendMessage(
              chatId,
              "🎯 Misión <b>" + p[0] + "</b> creada: otorga la insignia " + p[2] + ".");
        }

        // ── Catálogo de Donaciones ──────────────────────────────────────────
        case "/crearidentificador" -> {
          exigirAdmin(s);
          String[] p = campos(args, 2, "/crearidentificador CODIGODEBARRAS|QR;descripcion");
          telegram.sendMessage(
              chatId,
              "🏷️ Identificador creado:\n"
                  + donaciones.crearIdentificador(p[0].toUpperCase(), p[1]));
        }
        case "/crearproducto" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.producto());
            return;
          }
          String[] p = campos(args, 4, "/crearproducto nombre;descripcion;categoria;identificadorID");
          telegram.sendMessage(
              chatId, "📦 Producto creado:\n" + donaciones.crearProducto(p[0], p[1], p[2], p[3]));
        }
        case "/quejar" -> {
          if (args.isBlank()) {
            iniciar(chatId, formularios.queja());
            return;
          }
          String[] p = campos(args, 2, "/quejar donacionId;que paso");
          telegram.sendMessage(chatId, impacto.queja(p[0], p[1]));
        }

        // ── Demostración ────────────────────────────────────────────────────
        case "/demo" -> telegram.sendMessage(chatId, demo.guion());
        case "/despertar" -> telegram.sendMessage(chatId, demo.despertar());
        case "/estado" -> telegram.sendMessage(chatId, demo.estado());
        case "/reiniciar" -> {
          exigirAdmin(s);
          telegram.sendMessage(chatId, demo.reiniciar());
        }
        case "/preparar" -> {
          exigirAdmin(s);
          telegram.sendMessage(chatId, demo.preparar());
        }

        default -> telegram.sendMessage(chatId, noEntendi(s, cmd));
      }
    } catch (RuntimeException e) {
      telegram.sendMessage(chatId, "⚠️ " + e.getMessage());
    }
  }

  // ── Conversaciones guiadas ─────────────────────────────────────────────────

  /**
   * Si hay un formulario en curso, lo que la persona escriba es la respuesta a la pregunta
   * pendiente.
   *
   * <p>Si en el medio manda otro comando, se abandona el formulario y se atiende el comando:
   * insistir con la pregunta sería pelearse con alguien que ya cambió de idea.
   *
   * @return true si el mensaje ya quedó atendido acá
   */
  private boolean respondeUnFormulario(long chatId, String cmd, String text) {
    Formulario enCurso = conversaciones.get(chatId);
    if (enCurso == null) {
      return false;
    }
    if ("/cancelar".equals(cmd)) {
      conversaciones.remove(chatId);
      telegram.sendMessage(chatId, "Listo, lo dejamos acá. /help para ver qué más podés hacer.");
      return true;
    }
    if (text.startsWith("/")) {
      conversaciones.remove(chatId);
      return false;
    }
    try {
      telegram.sendMessage(chatId, enCurso.responder(text));
    } finally {
      // Aunque la operación falle: el formulario ya se consumió y la persona tiene que poder
      // volver a empezar en vez de quedar atrapada contestando preguntas que no avanzan.
      if (enCurso.termino()) {
        conversaciones.remove(chatId);
      }
    }
    return true;
  }

  /** Arranca una conversación guiada y hace la primera pregunta. */
  private void iniciar(long chatId, Formulario formulario) {
    conversaciones.put(chatId, formulario);
    telegram.sendMessage(chatId, formulario.primeraPregunta());
  }

  // ── Textos ─────────────────────────────────────────────────────────────────

  private String bienvenida() {
    return """
        👋 <b>Bienvenido a DonaTrack</b>

        Un sistema para que lo que se dona llegue a donde hace falta.

        ¿Cómo querés entrar?

        🧑 /soy_donador
           donar, ver tus donaciones y tus insignias

        🛠️ /soy_admin
           administrar entidades, necesidades y donadores""";
  }

  private String puertaDonador() {
    return """
        🧑 <b>Modo donador</b>

        ¿Ya estás registrado?

        ✅ Sí → /entrar <tu número>
           por ejemplo: /entrar 1

        🆕 No, es mi primera vez → /registrarse
           Te voy preguntando los datos de a uno, no hace falta que los sepas de memoria.

        ¿No te acordás tu número? /donadores te los lista.""";
  }

  private String menuDonadorAdentro(Sesion s) {
    return "Esto es lo que podés hacer:\n\n"
        + "🎁 /donar — te voy preguntando qué, cuánto y para qué\n"
        + "📦 /productos — qué se puede donar\n"
        + "📋 /misdonaciones — tus donaciones y su estado\n"
        + "👤 /perfil — tus datos\n"
        + "📊 /misestadisticas — categoría e insignias\n"
        + "❓ /puedodonar — si tenés la cuenta habilitada\n"
        + "🏅 /insignias — catálogo de insignias\n"
        + "🎯 /misiones — misiones para ganar insignias\n"
        + "🚪 /salir";
  }

  private String menuSegun(Sesion s) {
    return switch (s.rol()) {
      case ADMIN -> menuAdmin();
      case DONADOR -> s.estaIdentificado() ? menuDonadorAdentro(s) : puertaDonador();
      case NINGUNO -> bienvenida();
    };
  }

  /**
   * Qué contestar cuando el mensaje no es ninguno de los comandos conocidos.
   *
   * <p>Un chat que todavía no eligió rol recién está llegando, y obligarlo a adivinar que el
   * primer mensaje tiene que ser /start es perderlo en la puerta: escriba «hola» o se equivoque
   * de comando, lo que le falta es elegir cómo entrar. Por eso ahí siempre va la bienvenida.
   *
   * <p>Con el rol ya elegido sí conviene distinguir: quien escribe «/donarr» se equivocó de
   * comando y lo que necesita es la lista; quien escribe «hola» no se equivocó de nada, así que
   * se le repite el menú de su rol.
   */
  private String noEntendi(Sesion s, String cmd) {
    boolean pareceComando = cmd.startsWith("/");
    if (s.rol() == Sesion.Rol.NINGUNO) {
      // El aviso nombra /help para que quien erró el comando sepa dónde está la lista completa.
      return pareceComando
          ? "❓ No conozco ese comando. /help lista los que hay.\n\n" + bienvenida()
          : bienvenida();
    }
    return pareceComando ? "No conozco ese comando. Probá /help" : menuSegun(s);
  }

  private String menuAdmin() {
    return """
        🛠️ <b>Modo administrador</b>

        Los que dan de alta algo te van preguntando los datos de a uno. /cancelar para dejarlo.

        <b>Entidades</b>
        /crearentidad — alta guiada
        /editarentidad — cambiar sus datos
        /entidad <número> · /entidades

        <b>Necesidades</b>
        /altanecesidad — alta guiada
        /modificarnecesidad — cambiarla
        /necesidad <número> · /borrarnecesidad <número>

        <b>Donadores</b>
        /donadores — todos
        /donador <número> — uno
        /estadisticas <número> · /quejas <número>
        /estadodonador — verificado, sospechoso o baneado
        /categoriadonador id;categoria
        /procesardonador <número>

        <b>Logística</b>
        /depositos — depósitos y capacidad
        /stock <productoID> — stock disponible
        /reportarentrega — cerrar una donación entregada
        /creardeposito — alta guiada
        /algoritmo depositoId;SUB_ATENDIDOS|PRIORIDAD_POR_SCORE

        <b>Incentivos</b>
        /insignias · /misiones — los catálogos
        /crearinsignia · /crearmision — altas guiadas

        <b>Catálogo de donaciones</b>
        /donarcomo — donar a nombre de otro
        /crearproducto — alta guiada
        /crearidentificador CODIGODEBARRAS|QR;descripcion
        /quejar — reclamar por una donación

        <b>Demostración</b>
        /demo — el guion, paso por paso
        /estado — cómo está todo ahora mismo
        /reiniciar — vacía las cuatro bases
        /preparar — carga las precondiciones de los flujos

        <b>Catálogo</b>
        /productos

        🚪 /salir""";
  }

  private String productos() {
    JsonNode arr = Formato.parsear(donaciones.listarProductos());
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      return "No hay productos cargados todavía.";
    }
    StringBuilder sb = new StringBuilder("📦 <b>Productos que se pueden donar</b>\n");
    for (JsonNode p : arr) {
      sb.append("\n• nº ")
          .append(p.path("id").asText())
          .append(" — ")
          .append(p.path("nombre").asText())
          .append("\n  ")
          .append(p.path("descripcion").asText());
    }
    sb.append("\n\nPara donar: /donar productoID;cantidad;descripcion");
    return sb.toString();
  }

  private String puedeDonar(String donadorId) {
    JsonNode n = Formato.parsear(api.puedeDonar(donadorId));
    if (n == null) {
      return "No pude averiguarlo.";
    }
    return n.path("puedeDonar").asBoolean(false)
        ? "✅ Sí, tenés la cuenta habilitada para donar."
        : "🚫 No podés donar. Tu cuenta está bloqueada por quejas acumuladas.";
  }

  private String quejas(String donadorId) {
    JsonNode arr = Formato.parsear(api.quejasDe(donadorId));
    if (arr == null || !arr.isArray()) {
      return "No pude leer las quejas.";
    }
    if (arr.isEmpty()) {
      return "Ese donador no tiene ninguna queja. 👍";
    }
    StringBuilder sb =
        new StringBuilder("⚠️ <b>Quejas del donador " + donadorId + "</b> (" + arr.size() + ")\n");
    for (JsonNode q : arr) {
      sb.append("\n• donación nº ")
          .append(q.path("donacionID").asText("—"))
          .append("\n  ")
          .append(q.path("descripcion").asText("—"));
    }
    return sb.toString();
  }

  // ── Validaciones ───────────────────────────────────────────────────────────

  private void exigirIdentificado(Sesion s) {
    if (!s.estaIdentificado()) {
      throw new RuntimeException(
          "Primero entrá con tu número: /entrar <número>\n"
              + "Si es tu primera vez, /registrarse ...");
    }
  }

  private void exigirAdmin(Sesion s) {
    if (s.rol() != Sesion.Rol.ADMIN) {
      throw new RuntimeException("Eso es cosa de administradores. Entrá con /soy_admin");
    }
  }

  private String[] campos(String args, int n, String uso) {
    if (args.isBlank()) {
      throw new RuntimeException("Faltan datos.\nUso: " + uso);
    }
    String[] p = args.split("\\s*;\\s*", -1);
    if (p.length != n) {
      throw new RuntimeException(
          "Se esperaban " + n + " datos separados por ';' y llegaron " + p.length + ".\nUso: " + uso);
    }
    return p;
  }

  private String requerido(String args, String uso) {
    if (args.isBlank()) {
      throw new RuntimeException("Falta el dato.\nUso: " + uso);
    }
    return args.trim();
  }

  private int parseInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new RuntimeException("«" + s + "» no es un número.");
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
