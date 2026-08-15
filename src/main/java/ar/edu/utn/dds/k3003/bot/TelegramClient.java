package ar.edu.utn.dds.k3003.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Cliente HTTP mínimo de la Bot API de Telegram (getUpdates / sendMessage). */
@Component
public class TelegramClient {

  private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

  private final RestTemplate rest;
  private final ObjectMapper mapper = new ObjectMapper();
  private final String token;

  public TelegramClient(RestTemplate rest, @Value("${telegram.bot.token:}") String token) {
    this.rest = rest;
    this.token = token;
  }

  public boolean hayToken() {
    return token != null && !token.isBlank();
  }

  private String apiBase() {
    return "https://api.telegram.org/bot" + token;
  }

  /** Long-polling: trae updates desde {@code offset}. Devuelve el array "result" (o null si falla). */
  public JsonNode getUpdates(long offset) {
    String url = apiBase() + "/getUpdates?timeout=30&offset=" + offset;
    try {
      String resp = rest.getForObject(url, String.class);
      JsonNode root = mapper.readTree(resp);
      return root.path("result");
    } catch (Exception e) {
      log.warn("Error en getUpdates: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Envía el mensaje interpretando Markdown, para que los *asteriscos* salgan en negrita.
   *
   * <p>Telegram rechaza el mensaje entero si el Markdown queda mal balanceado, y eso puede pasar
   * con datos que escribe el usuario (un guion bajo en un mail, por ejemplo). Por eso, si falla,
   * se reintenta sin formato: mejor un mensaje feo que ningún mensaje.
   */
  public void sendMessage(long chatId, String text) {
    if (enviar(chatId, text, true)) {
      return;
    }
    enviar(chatId, text, false);
  }

  private boolean enviar(long chatId, String text, boolean conFormato) {
    String url = apiBase() + "/sendMessage";
    Map<String, Object> body =
        conFormato
            ? Map.of("chat_id", chatId, "text", text, "parse_mode", "Markdown")
            : Map.of("chat_id", chatId, "text", text);
    try {
      rest.postForObject(url, body, String.class);
      return true;
    } catch (Exception e) {
      if (conFormato) {
        log.debug("Markdown rechazado para el chat {}, reintento sin formato", chatId);
      } else {
        log.warn("Error al enviar mensaje a chat {}: {}", chatId, e.getMessage());
      }
      return false;
    }
  }
}
