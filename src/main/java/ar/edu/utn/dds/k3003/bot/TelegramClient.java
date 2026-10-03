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
      log.warn("Error en getUpdates: {}", sinToken(e.getMessage()));
      return null;
    }
  }

  /**
   * Borra un mensaje del chat. Lo usa el ingreso de admin para que la contraseña no quede a la
   * vista en el celular.
   *
   * <p>Nunca lanza: si no se pudo borrar, quien llama sigue y le pide a la persona que lo borre a
   * mano. El log no lleva el texto del mensaje, que es justamente la contraseña.
   *
   * @return si Telegram confirmó el borrado
   */
  public boolean deleteMessage(long chatId, long messageId) {
    if (messageId <= 0) {
      return false;
    }
    try {
      String resp =
          rest.postForObject(
              apiBase() + "/deleteMessage",
              Map.of("chat_id", chatId, "message_id", messageId),
              String.class);
      return resp != null && mapper.readTree(resp).path("ok").asBoolean(false);
    } catch (Exception e) {
      log.warn(
          "No se pudo borrar el mensaje {} del chat {}: {}",
          messageId,
          chatId,
          sinToken(e.getMessage()));
      return false;
    }
  }

  /**
   * El mensaje de error de una llamada que no llegó trae la URL, y la URL trae el token del bot.
   * Los logs pueden terminar en Datadog: el token no tiene que llegar ahí.
   */
  private String sinToken(String mensaje) {
    return mensaje == null || !hayToken() ? mensaje : mensaje.replace(token, "<token>");
  }

  /**
   * Envía el mensaje interpretando HTML, para que {@code <b>} salga en negrita.
   *
   * <p>Se usa HTML y no Markdown por los comandos: en Markdown el guion bajo abre cursiva, así
   * que un menú con /soy_donador y /soy_admin terminaba mostrando «/soydonador» sin el guion.
   * El usuario copiaba eso y el bot no lo reconocía. En HTML el guion bajo no significa nada.
   *
   * <p>Si el HTML llegara mal formado Telegram rechaza el mensaje entero, así que se reintenta
   * sin formato: mejor un mensaje feo que ningún mensaje.
   */
  public void sendMessage(long chatId, String text) {
    String entra = recortado(text);
    if (enviar(chatId, entra, true)) {
      return;
    }
    enviar(chatId, sinEtiquetas(entra), false);
  }

  /** Lo que Telegram acepta en un mensaje: lo que pasa de ahí lo rechaza entero. */
  static final int LARGO_MAXIMO = 4096;

  /**
   * Un listado largo —todas las donaciones, por ejemplo— no puede hacer que el mensaje no llegue.
   * Se corta en un salto de línea para no partir una etiqueta {@code <b>} por la mitad, que haría
   * rechazar el HTML.
   */
  static String recortado(String text) {
    if (text == null || text.length() <= LARGO_MAXIMO) {
      return text;
    }
    String aviso = "\n… (el resto no entra en un mensaje)";
    int corte = text.lastIndexOf('\n', LARGO_MAXIMO - aviso.length());
    return text.substring(0, corte > 0 ? corte : LARGO_MAXIMO - aviso.length()) + aviso;
  }

  private boolean enviar(long chatId, String text, boolean conFormato) {
    String url = apiBase() + "/sendMessage";
    Map<String, Object> body =
        conFormato
            ? Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML")
            : Map.of("chat_id", chatId, "text", text);
    try {
      rest.postForObject(url, body, String.class);
      return true;
    } catch (Exception e) {
      if (conFormato) {
        log.debug("HTML rechazado para el chat {}, reintento sin formato", chatId);
      } else {
        log.warn("Error al enviar mensaje a chat {}: {}", chatId, sinToken(e.getMessage()));
      }
      return false;
    }
  }

  private String sinEtiquetas(String text) {
    return text.replaceAll("</?[bi]>", "");
  }
}
