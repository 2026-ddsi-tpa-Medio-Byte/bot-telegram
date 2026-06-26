package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bot de Telegram de DonaTrack (UI - Entrega 4). Recibe un comando y devuelve una respuesta. Tras
 * /start el usuario elige rol (donador o admin) y se le muestran las opciones correspondientes.
 */
@Component
public class DonaTrackBot {

  private static final Logger log = LoggerFactory.getLogger(DonaTrackBot.class);

  private final TelegramClient telegram;
  private final DonadoresApiClient api;
  private final Map<Long, String> roles = new ConcurrentHashMap<>();
  private volatile long offset = 0;
  private volatile boolean running = true;

  public DonaTrackBot(TelegramClient telegram, DonadoresApiClient api) {
    this.telegram = telegram;
    this.api = api;
  }

  @PostConstruct
  public void start() {
    if (!telegram.hayToken()) {
      log.warn(
          "TELEGRAM_BOT_TOKEN no configurado: el bot NO se inicia. "
              + "Obtené un token de @BotFather, configurá TELEGRAM_BOT_TOKEN y reiniciá.");
      return;
    }
    Thread t = new Thread(this::pollLoop, "telegram-poll");
    t.setDaemon(true);
    t.start();
    log.info("Bot de Telegram iniciado (long-polling).");
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

    try {
      switch (cmd) {
        case "/start" ->
            telegram.sendMessage(
                chatId,
                "👋 Bienvenido a DonaTrack.\n¿Qué tipo de usuario sos?\n"
                    + "• /soy_donador\n• /soy_admin");
        case "/soy_donador" -> {
          roles.put(chatId, "donador");
          telegram.sendMessage(chatId, menuDonador());
        }
        case "/soy_admin" -> {
          roles.put(chatId, "admin");
          telegram.sendMessage(chatId, menuAdmin());
        }
        case "/help" ->
            telegram.sendMessage(
                chatId, "admin".equals(roles.get(chatId)) ? menuAdmin() : menuDonador());

        // ── Donador ──
        case "/registrarse" -> {
          String[] p =
              campos(args, 6, "/registrarse nombre;apellido;edad;email;documento;domicilio");
          telegram.sendMessage(
              chatId, api.registrarDonador(p[0], p[1], parseInt(p[2]), p[3], p[4], p[5]));
        }
        case "/estadisticas" ->
            telegram.sendMessage(
                chatId, api.estadisticasDonador(requerido(args, "/estadisticas <donadorID>")));
        case "/donador" ->
            telegram.sendMessage(chatId, api.buscarDonador(requerido(args, "/donador <id>")));
        case "/donadores" -> telegram.sendMessage(chatId, api.listarDonadores());

        // ── Admin ──
        case "/crearentidad" -> {
          String[] p = campos(args, 4, "/crearentidad razonSocial;domicilio;telefono;correo");
          telegram.sendMessage(chatId, api.crearEntidad(p[0], p[1], p[2], p[3]));
        }
        case "/editarentidad" -> {
          String[] p =
              campos(args, 5, "/editarentidad id;razonSocial;domicilio;telefono;correo");
          telegram.sendMessage(chatId, api.editarEntidad(p[0], p[1], p[2], p[3], p[4]));
        }
        case "/entidad" ->
            telegram.sendMessage(chatId, api.buscarEntidad(requerido(args, "/entidad <id>")));
        case "/entidades" -> telegram.sendMessage(chatId, api.listarEntidades());
        case "/altanecesidad" -> {
          String[] p =
              campos(
                  args,
                  6,
                  "/altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo");
          telegram.sendMessage(
              chatId,
              api.altaNecesidad(
                  p[0], parseInt(p[1]), p[2], parseInt(p[3]), p[4], p[5].toUpperCase()));
        }
        case "/necesidad" ->
            telegram.sendMessage(chatId, api.buscarNecesidad(requerido(args, "/necesidad <id>")));
        case "/modificarnecesidad" -> {
          String[] p =
              campos(
                  args,
                  6,
                  "/modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo");
          telegram.sendMessage(
              chatId,
              api.modificarNecesidad(
                  p[0], parseInt(p[1]), p[2], parseInt(p[3]), p[4], p[5].toUpperCase()));
        }
        case "/borrarnecesidad" ->
            telegram.sendMessage(
                chatId, api.borrarNecesidad(requerido(args, "/borrarnecesidad <id>")));

        default ->
            telegram.sendMessage(chatId, "Comando no reconocido. Usá /start o /help.");
      }
    } catch (RuntimeException e) {
      telegram.sendMessage(chatId, "⚠️ " + e.getMessage());
    }
  }

  private String menuDonador() {
    return "🧑 Modo DONADOR. Comandos:\n"
        + "• /registrarse nombre;apellido;edad;email;documento;domicilio\n"
        + "• /estadisticas <donadorID>\n"
        + "• /donador <id>\n"
        + "• /donadores";
  }

  private String menuAdmin() {
    return "🛠️ Modo ADMIN. Comandos:\n"
        + "• /crearentidad razonSocial;domicilio;telefono;correo\n"
        + "• /editarentidad id;razonSocial;domicilio;telefono;correo\n"
        + "• /entidad <id>\n"
        + "• /entidades\n"
        + "• /altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo\n"
        + "• /necesidad <id>\n"
        + "• /modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo\n"
        + "• /borrarnecesidad <id>";
  }

  private String[] campos(String args, int n, String uso) {
    if (args.isBlank()) {
      throw new RuntimeException("Faltan datos. Uso: " + uso);
    }
    String[] p = args.split("\\s*;\\s*", -1);
    if (p.length != n) {
      throw new RuntimeException("Se esperaban " + n + " campos separados por ';'. Uso: " + uso);
    }
    return p;
  }

  private String requerido(String args, String uso) {
    if (args.isBlank()) {
      throw new RuntimeException("Falta el parámetro. Uso: " + uso);
    }
    return args.trim();
  }

  private int parseInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new RuntimeException("Valor numérico inválido: " + s);
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
