package ar.edu.utn.dds.k3003.bot;

import ar.edu.utn.dds.k3003.bot.Formulario.Campo;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bot de Telegram de DonaTrack (UI - Entrega 4).
 *
 * <p>El donador entra con su número y a partir de ahí el bot lo recuerda: puede ver sus datos,
 * sus donaciones y donar sin repetir quién es. El admin entra con contraseña y tiene el manejo
 * completo de entidades, necesidades y el estado de los donadores, más las consultas de todo el
 * dominio. Las respuestas se muestran formateadas, no como el JSON que devuelve la API.
 *
 * <p>Este bot nunca escribe en el log el texto de los mensajes que recibe: uno de ellos es la
 * contraseña de administrador, y los logs pueden terminar en Datadog.
 */
@Component
public class DonaTrackBot {

  private static final Logger log = LoggerFactory.getLogger(DonaTrackBot.class);

  private static final String SESION_VENCIDA =
      "Tu sesión de administrador venció: pasaron "
          + AccesoAdmin.VENCIMIENTO.toHours()
          + " horas sin usar el bot. Entrá de nuevo con /soy_admin.";

  private final TelegramClient telegram;
  private final DonadoresApiClient api;
  private final DonacionesApiClient donaciones;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;
  private final Impacto impacto;
  private final Demo demo;
  private final Formularios formularios;
  private final Consultas consultas;
  private final AccesoAdmin acceso;
  private final String depositoPorDefecto;

  private final Map<Long, Sesion> sesiones = new ConcurrentHashMap<>();

  /** Los formularios en curso, uno por chat: lo que la persona escriba es la respuesta pendiente. */
  private final Map<Long, Formulario> conversaciones = new ConcurrentHashMap<>();

  /** Los chats que mandaron /soy_admin: su próximo mensaje es la contraseña. */
  private final Set<Long> esperandoContrasena = ConcurrentHashMap.newKeySet();

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
      Consultas consultas,
      AccesoAdmin acceso,
      @Value("${deposito.default:DEP-UTN-01}") String depositoPorDefecto) {
    this.telegram = telegram;
    this.api = api;
    this.donaciones = donaciones;
    this.logistica = logistica;
    this.incentivos = incentivos;
    this.impacto = impacto;
    this.demo = demo;
    this.formularios = formularios;
    this.consultas = consultas;
    this.acceso = acceso;
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
    // Se dice si está habilitado, nunca la contraseña.
    log.info(
        "Modo administrador: {}",
        acceso.habilitado() ? "habilitado" : "deshabilitado (falta BOT_ADMIN_PASSWORD)");
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
        atender(upd);
      }
    }
  }

  /**
   * Un update de Telegram tal como llega. Se separa del ciclo de long-polling para poder probar
   * el camino entero, incluido el número de mensaje que hace falta para borrar la contraseña.
   */
  void atender(JsonNode upd) {
    JsonNode msg = upd.path("message");
    if (msg.isMissingNode()) {
      return;
    }
    long chatId = msg.path("chat").path("id").asLong();
    long mensajeId = msg.path("message_id").asLong();
    String text = msg.path("text").asText("").trim();
    if (!text.isEmpty()) {
      handle(chatId, mensajeId, text);
    }
  }

  /** Para los mensajes de los que no importa el número: no hay nada que borrar. */
  void handle(long chatId, String text) {
    handle(chatId, 0L, text);
  }

  /** Procesa un mensaje. Nunca lanza: ante error responde con el mensaje al usuario. */
  void handle(long chatId, long mensajeId, String text) {
    Sesion s = sesiones.computeIfAbsent(chatId, k -> new Sesion());
    try {
      // Antes que nada: si es la contraseña, no tiene que pasar por ningún otro lado.
      if (esperandoContrasena.contains(chatId)) {
        atenderContrasena(chatId, mensajeId, text, s);
        return;
      }
      String cmd = text.split("\\s+", 2)[0].toLowerCase();
      if (cmd.contains("@")) {
        cmd = cmd.substring(0, cmd.indexOf('@'));
      }
      String args = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
      controlarVencimiento(chatId, s);
      if (respondeUnFormulario(chatId, cmd, text)) {
        return;
      }
      switch (cmd) {
        case "/start" -> telegram.sendMessage(chatId, bienvenida());
        case "/help" -> telegram.sendMessage(chatId, avisoDeVencimiento(s) + menuSegun(s));
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
        case "/soy_admin" -> pedirContrasena(chatId, s);

        // ── Donador: entrar ─────────────────────────────────────────────────
        case "/entrar" -> {
          String id = requerido(args, "/entrar <tu número de donador>");
          String json = api.buscarDonador(id);
          JsonNode d = Formato.parsear(json);
          String nombre = d == null ? id : d.path("nombre").asText(id);
          s.identificar(id, nombre);
          telegram.sendMessage(
              chatId,
              "👋 Hola <b>" + Formato.esc(nombre) + "</b>, entraste.\n\n" + menuDonadorAdentro(s));
        }
        case "/registrarse" -> {
          if (args.isBlank()) {
            iniciar(chatId, formularios.registro(s));
            return;
          }
          String[] p = campos(args, 6, "/registrarse");
          String json = api.registrarDonadorRaw(p[0], p[1], parseInt(p[2]), p[3], p[4], p[5]);
          JsonNode d = Formato.parsear(json);
          if (d != null && !d.path("id").asText("").isBlank()) {
            String id = d.path("id").asText();
            s.identificar(id, p[0]);
            telegram.sendMessage(
                chatId,
                "🎉 Listo <b>"
                    + Formato.esc(p[0])
                    + "</b>, quedaste registrado con el número <b>"
                    + Formato.esc(id)
                    + "</b>.\n"
                    + "Anotátelo: con eso entrás la próxima vez con /entrar "
                    + Formato.esc(id)
                    + "\n\n"
                    + menuDonadorAdentro(s));
          } else {
            telegram.sendMessage(chatId, "Registrado: " + json);
          }
        }

        // ── Donador: ya adentro ─────────────────────────────────────────────
        case "/perfil", "/misdatos" -> {
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
          String[] p = campos(args, 3, "/donar");
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
          String[] p = campos(args, 4, "/donarcomo");
          telegram.sendMessage(
              chatId, impacto.donar(p[0], depositoPorDefecto, p[3], p[1], parseInt(p[2])));
        }
        case "/puedodonar" -> {
          exigirIdentificado(s);
          telegram.sendMessage(chatId, puedeDonar(s.donadorId()));
        }

        // ── Consultas: sin número preguntan cuál, con número van directo ────
        //
        // Las que muestran datos personales de los donadores —documento, email, domicilio, sus
        // donaciones— son solo para el admin: sin contraseña, cualquiera que encuentre el bot
        // las vería. El catálogo, las entidades, las necesidades y Logística quedan abiertos.

        case "/donaciones" -> {
          exigirAdmin(s);
          conDato(chatId, args, "Donaciones", formularios.deQuien(), consultas::donaciones);
        }
        case "/donacion" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Una donación", Campo.numero("¿Qué número de donación?"),
              consultas::donacion);
        }
        case "/productos" -> telegram.sendMessage(chatId, productos(s));
        case "/identificadores" ->
            telegram.sendMessage(
                chatId, Formato.listaIdentificadores(donaciones.listarIdentificadores()));
        case "/donadores" -> {
          exigirAdmin(s);
          telegram.sendMessage(chatId, Formato.listaDonadores(api.listarDonadores()));
        }
        case "/donador" -> {
          exigirAdmin(s);
          conDato(chatId, args, "Un donador", formularios.cualDonador(), consultas::donador);
        }
        case "/estadisticas" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Estadísticas de un donador", formularios.cualDonador(),
              id -> Formato.estadisticas(api.estadisticasDonador(id)));
        }
        case "/quejas" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Quejas de un donador", formularios.cualDonador(),
              id -> Formato.quejas(api.quejasDe(id), "Quejas del donador " + Formato.esc(id)));
        }
        case "/entidades" ->
            telegram.sendMessage(chatId, Formato.listaEntidades(api.listarEntidades()));
        case "/entidad" ->
            conDato(
                chatId, args, "Una entidad", formularios.cualEntidad(),
                id -> Formato.entidad(api.buscarEntidad(id)));
        case "/necesidades" -> telegram.sendMessage(chatId, consultas.necesidades());
        case "/necesidad" ->
            conDato(
                chatId, args, "Una necesidad", Campo.numero("¿Qué número de necesidad?"),
                id -> Formato.necesidad(api.buscarNecesidad(id)));
        case "/depositos" ->
            telegram.sendMessage(
                chatId, Formato.listaDepositos(logistica.listarDepositosConStock()));
        case "/stock" ->
            conDato(
                chatId, args, "Stock de un producto", formularios.cualProducto(),
                id -> Formato.stockPorDeposito(id, logistica.stockPorDeposito(id)));
        case "/asignaciones" ->
            telegram.sendMessage(
                chatId, Formato.listaAsignaciones(logistica.asignacionesPendientes()));
        case "/paquete" ->
            conDato(
                chatId, args, "Un paquete",
                Campo.texto(
                    "¿Qué paquete? Pasame el número de la donación, o el código entero si es de "
                        + "una solicitud (paq-solicitud-…)"),
                consultas::paquete);
        case "/insignias" ->
            telegram.sendMessage(chatId, Formato.listaInsignias(incentivos.listarInsignias()));
        case "/misiones" ->
            telegram.sendMessage(chatId, Formato.listaMisiones(incentivos.listarMisiones()));
        case "/progreso" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Progreso en Incentivos", formularios.cualDonador(),
              consultas::progreso);
        }

        // ── Admin: entidades y necesidades ──────────────────────────────────
        case "/crearentidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.entidad());
            return;
          }
          String[] p = campos(args, 4, "/crearentidad");
          String json = api.crearEntidadRaw(p[0], p[1], p[2], p[3]);
          telegram.sendMessage(chatId, "✅ Entidad creada\n\n" + Formato.entidad(json));
        }
        case "/editarentidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.editarEntidad());
            return;
          }
          String[] p = campos(args, 5, "/editarentidad");
          telegram.sendMessage(chatId, formularios.editarEntidad(p[0], p[1], p[2], p[3], p[4]));
        }
        case "/altanecesidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.necesidad());
            return;
          }
          String[] p = campos(args, 6, "/altanecesidad");
          String json =
              api.altaNecesidadRaw(
                  p[0], parseInt(p[1]), p[2], parseInt(p[3]), p[4], p[5].toUpperCase());
          telegram.sendMessage(chatId, "✅ Necesidad creada\n\n" + Formato.necesidad(json));
        }
        case "/modificarnecesidad" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.modificarNecesidad());
            return;
          }
          String[] p = campos(args, 6, "/modificarnecesidad");
          telegram.sendMessage(
              chatId, formularios.modificarNecesidad(p[0], p[1], p[2], p[3], p[4], p[5]));
        }
        case "/borrarnecesidad" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Borrar una necesidad", Campo.numero("¿Qué número de necesidad borro?"),
              id -> "🗑️ " + Formato.esc(api.borrarNecesidad(id)));
        }

        // ── Admin: poder sobre los donadores ────────────────────────────────
        case "/estadodonador" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.estadoDonador());
            return;
          }
          String[] p = campos(args, 2, "/estadodonador");
          String json = api.cambiarEstadoDonador(p[0], p[1].toUpperCase());
          telegram.sendMessage(chatId, "✅ Estado cambiado\n\n" + Formato.donador(json));
        }
        case "/categoriadonador" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.categoriaDonador());
            return;
          }
          String[] p = campos(args, 2, "/categoriadonador");
          telegram.sendMessage(chatId, formularios.cambiarCategoria(p[0], p[1]));
        }
        case "/quejar" -> {
          // La queja es sobre el donador y la registra un admin, igual que en el MCP.
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.queja());
            return;
          }
          String[] p = campos(args, 2, "/quejar");
          telegram.sendMessage(chatId, impacto.queja(p[0], p[1]));
        }

        // ── Admin: Logística ────────────────────────────────────────────────
        case "/reportarentrega" -> {
          exigirAdmin(s);
          // Logística nombra cada paquete "paq-" + el id de la donación: con el número de la
          // donación alcanza para encontrarlo.
          if (args.isBlank()) {
            iniciar(chatId, formularios.entrega());
            return;
          }
          String[] p = campos(args, 3, "/reportarentrega");
          telegram.sendMessage(
              chatId, impacto.reportarEntrega("paq-" + p[0], p[0], p[1], parseInt(p[2])));
        }
        case "/creardeposito" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.deposito());
            return;
          }
          String[] p = campos(args, 4, "/creardeposito");
          logistica.crearDeposito(p[0], p[1], p[2], parseInt(p[3]), "SUB_ATENDIDOS");
          telegram.sendMessage(chatId, "🏬 Depósito <b>" + Formato.esc(p[0]) + "</b> creado.");
        }
        case "/algoritmo" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.algoritmo());
            return;
          }
          String[] p = campos(args, 2, "/algoritmo");
          telegram.sendMessage(chatId, formularios.configurarAlgoritmo(p[0], p[1]));
        }

        // ── Admin: Incentivos ───────────────────────────────────────────────
        case "/procesardonador" -> {
          exigirAdmin(s);
          conDato(
              chatId, args, "Procesar un donador en Incentivos", formularios.cualDonador(),
              impacto::procesar);
        }
        case "/crearinsignia" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.insignia());
            return;
          }
          String[] p = campos(args, 3, "/crearinsignia");
          incentivos.crearInsignia(p[0], p[1], p[2]);
          telegram.sendMessage(chatId, "🏅 Insignia <b>" + Formato.esc(p[0]) + "</b> creada.");
        }
        case "/crearmision" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.mision());
            return;
          }
          String[] p = campos(args, 5, "/crearmision");
          incentivos.crearMision(p[0], p[1], p[2], p[3], p[4], "COMPLETITUD");
          telegram.sendMessage(
              chatId,
              "🎯 Misión <b>"
                  + Formato.esc(p[0])
                  + "</b> creada: otorga la insignia "
                  + Formato.esc(p[2])
                  + ".");
        }

        // ── Admin: catálogo de Donaciones ───────────────────────────────────
        case "/crearidentificador" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.identificador());
            return;
          }
          String[] p = campos(args, 2, "/crearidentificador");
          telegram.sendMessage(chatId, formularios.crearIdentificador(p[0], p[1]));
        }
        case "/crearproducto" -> {
          exigirAdmin(s);
          if (args.isBlank()) {
            iniciar(chatId, formularios.producto());
            return;
          }
          String[] p = campos(args, 4, "/crearproducto");
          telegram.sendMessage(chatId, formularios.crearProducto(p[0], p[1], p[2], p[3]));
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

  // ── Ingreso de administrador ───────────────────────────────────────────────

  private void pedirContrasena(long chatId, Sesion s) {
    if (!acceso.habilitado()) {
      telegram.sendMessage(chatId, adminDeshabilitado());
      return;
    }
    if (s.rol() == Sesion.Rol.ADMIN) {
      telegram.sendMessage(chatId, "Ya estás como administrador.\n\n" + menuAdmin());
      return;
    }
    Duration bloqueo = acceso.bloqueoRestante(chatId);
    if (!bloqueo.isZero()) {
      telegram.sendMessage(chatId, demasiadosIntentos(bloqueo));
      return;
    }
    // Va a volver a entrar: avisarle después que la sesión anterior venció ya no le sirve.
    s.avisarVencimiento();
    esperandoContrasena.add(chatId);
    telegram.sendMessage(
        chatId,
        "🔐 <b>Acceso de administrador</b>\n\n"
            + "Escribí la contraseña. Apenas la leo, borro tu mensaje para que no quede en el "
            + "chat.\n\n/cancelar para volver.");
  }

  /**
   * El mensaje que sigue a /soy_admin es la contraseña, sea lo que sea.
   *
   * <p>Solo /cancelar se toma como comando: cualquier otra cosa podría ser la contraseña, y
   * atenderla como comando la dejaría escrita en el chat sin borrar. El texto no se escribe en
   * ningún log ni se repite en ninguna respuesta.
   */
  private void atenderContrasena(long chatId, long mensajeId, String texto, Sesion s) {
    if ("/cancelar".equalsIgnoreCase(texto.trim())) {
      esperandoContrasena.remove(chatId);
      telegram.sendMessage(chatId, "Listo, no entraste. /start para elegir cómo entrar.");
      return;
    }
    // Se borra antes de compararla: una contraseña casi correcta tampoco tiene que quedar a la
    // vista en el celular.
    String aviso =
        telegram.deleteMessage(chatId, mensajeId)
            ? ""
            : "\n\n⚠️ No pude borrar tu mensaje con la contraseña: borralo vos del chat.";
    switch (acceso.intentar(chatId, texto)) {
      case ENTRO -> {
        esperandoContrasena.remove(chatId);
        conversaciones.remove(chatId);
        s.comoAdmin(acceso.ahora());
        telegram.sendMessage(
            chatId,
            "✅ Entraste como administrador. La sesión dura hasta /salir o "
                + AccesoAdmin.VENCIMIENTO.toHours()
                + " horas sin usar el bot."
                + aviso
                + "\n\n"
                + menuAdmin());
      }
      case INCORRECTA -> {
        int quedan = acceso.intentosRestantes(chatId);
        telegram.sendMessage(
            chatId,
            "❌ Contraseña incorrecta. "
                + (quedan == 1 ? "Te queda 1 intento." : "Te quedan " + quedan + " intentos.")
                + aviso);
      }
      case BLOQUEADO -> {
        esperandoContrasena.remove(chatId);
        telegram.sendMessage(chatId, demasiadosIntentos(acceso.bloqueoRestante(chatId)) + aviso);
      }
    }
  }

  /**
   * La sesión de admin vence por inactividad, y se mira antes de atender cada mensaje: si sigue
   * viva, este mensaje cuenta como uso; si venció, el chat vuelve a no tener rol.
   *
   * <p>También se descarta el formulario que hubiera a medias: su última respuesta ejecutaría una
   * operación de admin sin sesión.
   */
  private void controlarVencimiento(long chatId, Sesion s) {
    if (s.rol() != Sesion.Rol.ADMIN) {
      return;
    }
    if (acceso.vencio(s.ultimoUso())) {
      s.vencer();
      conversaciones.remove(chatId);
    } else {
      s.usada(acceso.ahora());
    }
  }

  private String avisoDeVencimiento(Sesion s) {
    return s.avisarVencimiento() ? "⏰ " + SESION_VENCIDA + "\n\n" : "";
  }

  private static String demasiadosIntentos(Duration resta) {
    long minutos = Math.max(1, (resta.toSeconds() + 59) / 60);
    return "🔒 Demasiados intentos. Probá de nuevo en "
        + minutos
        + (minutos == 1 ? " minuto." : " minutos.");
  }

  private static String adminDeshabilitado() {
    return "🔒 <b>El modo administrador está deshabilitado</b>\n\n"
        + "El bot arrancó sin contraseña de administrador. Para habilitarlo, cortalo con Ctrl+C y "
        + "arrancalo de nuevo con la contraseña en la misma terminal:\n\n"
        + "<code>$env:BOT_ADMIN_PASSWORD = \"...\"</code>\n"
        + "<code>mvn spring-boot:run</code>";
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

  /**
   * Con el dato escrito va directo (/donador 3); sin él, lo pregunta y después hace exactamente lo
   * mismo. Así no hay que acordarse de qué comandos llevan número.
   */
  private void conDato(
      long chatId, String args, String titulo, Campo campo, Function<String, String> accion) {
    if (args.isBlank()) {
      iniciar(chatId, Formularios.consulta(titulo, campo, accion));
      return;
    }
    telegram.sendMessage(chatId, accion.apply(args.trim()));
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
           administrar entidades, necesidades y donadores (pide contraseña)""";
  }

  private String puertaDonador() {
    return """
        🧑 <b>Modo donador</b>

        ¿Ya estás registrado?

        ✅ Sí → /entrar &lt;tu número&gt;
           por ejemplo: /entrar 1

        🆕 No, es mi primera vez → /registrarse
           Te voy preguntando los datos de a uno, no hace falta que los sepas de memoria.""";
  }

  private String menuDonadorAdentro(Sesion s) {
    return "Esto es lo que podés hacer:\n\n"
        + "🎁 /donar — te voy preguntando qué, cuánto y para qué\n"
        + "📦 /productos — qué se puede donar\n"
        + "📋 /necesidades — qué están pidiendo las entidades\n"
        + "🧾 /misdonaciones — tus donaciones y su estado\n"
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
      // Si recién se le venció la sesión de admin, eso es lo primero que tiene que saber: lo que
      // escribió probablemente era la respuesta a un formulario que ya no sigue.
      String aviso = avisoDeVencimiento(s);
      // El aviso nombra /help para que quien erró el comando sepa dónde está la lista completa.
      return aviso
          + (pareceComando
              ? "❓ No conozco ese comando. /help lista los que hay.\n\n" + bienvenida()
              : bienvenida());
    }
    return pareceComando ? "No conozco ese comando. Probá /help" : menuSegun(s);
  }

  /**
   * El menú del admin: primero lo que se consulta y después lo que se opera, cada uno por módulo.
   * Tiene que entrar en un solo mensaje de Telegram, que corta en 4096 caracteres.
   */
  private String menuAdmin() {
    return """
        🛠️ <b>Modo administrador</b>

        🔎 <b>CONSULTAR</b>
        Sin número te pregunto cuál; con número vas directo (/donador 3).

        <b>Donaciones</b>
        /donaciones — todas o las de un donador
        /donacion — una, con su estado
        /productos · /identificadores — el catálogo

        <b>Donadores y entidades</b>
        /donadores — todos
        /donador — uno, con sus quejas y si puede donar
        /estadisticas — categoría e insignias de un donador
        /entidades · /entidad — las organizaciones
        /necesidades — las pendientes, por producto
        /necesidad — una, con cuánto le falta

        <b>Logística</b>
        /depositos — capacidad y stock de cada uno
        /stock — un producto, depósito por depósito
        /asignaciones — paquetes pendientes de entregar
        /paquete — el estado de uno

        <b>Incentivos</b>
        /insignias · /misiones — los catálogos
        /progreso — insignias y misión de un donador

        ✏️ <b>OPERAR</b> (te pregunto los datos de a uno)

        <b>Donaciones</b>
        /donarcomo — donar a nombre de un donador
        /quejar — reclamar por una donación ya entregada
        /crearproducto · /crearidentificador — el catálogo

        <b>Donadores y entidades</b>
        /estadodonador · /categoriadonador — estado o categoría de un donador
        /crearentidad · /editarentidad — una entidad
        /altanecesidad · /modificarnecesidad · /borrarnecesidad — una necesidad

        <b>Logística</b>
        /reportarentrega — cerrar una donación entregada
        /creardeposito · /algoritmo — un depósito y su criterio para asignar

        <b>Incentivos</b>
        /procesardonador — que Incentivos evalúe la misión de un donador
        /crearinsignia · /crearmision — el catálogo

        🎬 /demo · /estado · /reiniciar · /preparar
        🚪 /salir""";
  }

  private String productos(Sesion s) {
    JsonNode arr = Formato.parsear(donaciones.listarProductos());
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      return "No hay productos cargados todavía.";
    }
    StringBuilder sb = new StringBuilder("📦 <b>Productos que se pueden donar</b>\n");
    for (JsonNode p : arr) {
      sb.append("\n• nº ")
          .append(Formato.esc(p.path("id").asText()))
          .append(" — ")
          .append(Formato.esc(p.path("nombre").asText()))
          .append("\n  ")
          .append(Formato.esc(p.path("descripcion").asText()));
    }
    sb.append(
        s.rol() == Sesion.Rol.ADMIN
            ? "\n\nPara donar a nombre de un donador: /donarcomo"
            : "\n\nPara donar: /donar, y te pregunto qué, cuánto y para qué.");
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

  // ── Validaciones ───────────────────────────────────────────────────────────

  private void exigirIdentificado(Sesion s) {
    if (!s.estaIdentificado()) {
      throw new RuntimeException(
          "Primero entrá con tu número: /entrar &lt;número&gt;\n"
              + "Si es tu primera vez, /registrarse ...");
    }
  }

  /** También mira el reloj: es la última barrera antes de una operación de admin. */
  private void exigirAdmin(Sesion s) {
    if (s.rol() == Sesion.Rol.ADMIN && acceso.vencio(s.ultimoUso())) {
      s.vencer();
    }
    if (s.rol() != Sesion.Rol.ADMIN) {
      throw new RuntimeException(
          s.avisarVencimiento()
              ? SESION_VENCIDA
              : "Eso es cosa de administradores. Entrá con /soy_admin");
    }
  }

  /**
   * Los datos de un atajo en una línea.
   *
   * <p>Si no cierran, no se muestra cómo escribirlo con punto y coma: el atajo es para quien ya lo
   * conoce, y a quien se equivocó le sirve más la forma guiada.
   */
  private String[] campos(String args, int n, String comando) {
    String[] p = args.isBlank() ? new String[0] : args.split("\\s*;\\s*", -1);
    if (p.length != n) {
      throw new RuntimeException(
          "Se esperaban "
              + n
              + " datos y llegaron "
              + p.length
              + ". Mandá "
              + comando
              + " solo y te pregunto de a uno.");
    }
    return p;
  }

  /**
   * El uso se escapa porque lleva {@code <número>}: Telegram toma eso como una etiqueta, rechaza el
   * HTML y el mensaje llega sin formato.
   */
  private String requerido(String args, String uso) {
    if (args.isBlank()) {
      throw new RuntimeException("Falta el dato.\nUso: " + Formato.esc(uso));
    }
    return args.trim();
  }

  private int parseInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new RuntimeException("«" + Formato.esc(s) + "» no es un número.");
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
