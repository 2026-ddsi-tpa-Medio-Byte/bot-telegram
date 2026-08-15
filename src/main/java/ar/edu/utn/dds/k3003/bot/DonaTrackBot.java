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
  private final String depositoPorDefecto;

  private final Map<Long, Sesion> sesiones = new ConcurrentHashMap<>();
  private volatile long offset = 0;
  private volatile boolean running = true;

  public DonaTrackBot(
      TelegramClient telegram,
      DonadoresApiClient api,
      DonacionesApiClient donaciones,
      @Value("${deposito.default:DEP-UTN-01}") String depositoPorDefecto) {
    this.telegram = telegram;
    this.api = api;
    this.donaciones = donaciones;
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
      switch (cmd) {
        case "/start" -> telegram.sendMessage(chatId, bienvenida());
        case "/help" -> telegram.sendMessage(chatId, menuSegun(s));
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
              chatId, "👋 Hola *" + nombre + "*, entraste.\n\n" + menuDonadorAdentro(s));
        }
        case "/registrarse" -> {
          String[] p =
              campos(args, 6, "/registrarse nombre;apellido;edad;email;documento;domicilio");
          String json = api.registrarDonadorRaw(p[0], p[1], parseInt(p[2]), p[3], p[4], p[5]);
          JsonNode d = Formato.parsear(json);
          if (d != null && !d.path("id").asText("").isBlank()) {
            String id = d.path("id").asText();
            s.identificar(id, p[0]);
            telegram.sendMessage(
                chatId,
                "🎉 Listo *"
                    + p[0]
                    + "*, quedaste registrado con el número *"
                    + id
                    + "*.\n"
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
          String[] p = campos(args, 3, "/donar productoID;cantidad;descripcion");
          String json =
              donaciones.donar(
                  s.donadorId(), depositoPorDefecto, p[2], p[0], parseInt(p[1]));
          telegram.sendMessage(chatId, "🎁 ¡Gracias por donar!\n\n" + Formato.donacion(json));
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

        default -> telegram.sendMessage(chatId, "No conozco ese comando. Probá /help");
      }
    } catch (RuntimeException e) {
      telegram.sendMessage(chatId, "⚠️ " + e.getMessage());
    }
  }

  // ── Textos ─────────────────────────────────────────────────────────────────

  private String bienvenida() {
    return """
        👋 *Bienvenido a DonaTrack*

        Un sistema para que lo que se dona llegue a donde hace falta.

        ¿Cómo querés entrar?

        🧑 /soy_donador
           donar, ver tus donaciones y tus insignias

        🛠️ /soy_admin
           administrar entidades, necesidades y donadores""";
  }

  private String puertaDonador() {
    return """
        🧑 *Modo donador*

        ¿Ya estás registrado?

        ✅ Sí → /entrar <tu número>
           por ejemplo: /entrar 1

        🆕 No, es mi primera vez →
           /registrarse nombre;apellido;edad;email;documento;domicilio
           por ejemplo:
           /registrarse Juan;Perez;30;juan@mail.com;40123456;Calle 5

        ¿No te acordás tu número? /donadores te los lista.""";
  }

  private String menuDonadorAdentro(Sesion s) {
    return "Esto es lo que podés hacer:\n\n"
        + "🎁 /donar productoID;cantidad;descripcion\n"
        + "   por ejemplo: /donar 1;10;Diez kilos de arroz\n"
        + "📦 /productos — qué se puede donar\n"
        + "📋 /misdonaciones — tus donaciones y su estado\n"
        + "👤 /perfil — tus datos\n"
        + "📊 /misestadisticas — categoría e insignias\n"
        + "❓ /puedodonar — si tenés la cuenta habilitada\n"
        + "🚪 /salir";
  }

  private String menuSegun(Sesion s) {
    return switch (s.rol()) {
      case ADMIN -> menuAdmin();
      case DONADOR -> s.estaIdentificado() ? menuDonadorAdentro(s) : puertaDonador();
      case NINGUNO -> bienvenida();
    };
  }

  private String menuAdmin() {
    return """
        🛠️ *Modo administrador*

        *Entidades*
        /crearentidad razonSocial;domicilio;telefono;correo
        /editarentidad id;razonSocial;domicilio;telefono;correo
        /entidad <número>
        /entidades

        *Necesidades*
        /altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo
        /modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo
        /necesidad <número>
        /borrarnecesidad <número>

        *Donadores*
        /donadores — todos
        /donador <número> — uno
        /estadisticas <número>
        /quejas <número>
        /estadodonador id;VERIFICADO|SOSPECHOSO|BANEADO
        /categoriadonador id;categoria

        *Catálogo*
        /productos

        🚪 /salir""";
  }

  private String productos() {
    JsonNode arr = Formato.parsear(donaciones.listarProductos());
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      return "No hay productos cargados todavía.";
    }
    StringBuilder sb = new StringBuilder("📦 *Productos que se pueden donar*\n");
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
    StringBuilder sb = new StringBuilder("⚠️ *Quejas del donador " + donadorId + "* (" + arr.size() + ")\n");
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
