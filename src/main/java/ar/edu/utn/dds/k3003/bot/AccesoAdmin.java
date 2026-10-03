package ar.edu.utn.dds.k3003.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Quién puede entrar como administrador, y por cuánto tiempo.
 *
 * <p>El modo administrador borra las cuatro bases y cambia el estado de cualquier donador: no
 * puede quedar abierto para cualquiera que encuentre el bot en Telegram. La contraseña viene de
 * {@code BOT_ADMIN_PASSWORD} y no tiene valor por defecto: sin ella, nadie entra. Un valor por
 * defecto terminaría escrito en el repositorio, que es justo lo que no tiene que pasar con una
 * credencial.
 *
 * <p>El reloj se inyecta para poder probar el bloqueo y el vencimiento sin esperar de verdad.
 */
@Component
class AccesoAdmin {

  /** Intentos fallidos seguidos que se toleran antes de bloquear el chat. */
  static final int INTENTOS = 3;

  /** Lo que dura el bloqueo: alcanza para que probar contraseñas a mano no tenga sentido. */
  static final Duration BLOQUEO = Duration.ofMinutes(5);

  /** Una sesión que nadie usa en este tiempo se cierra sola, por si quedó el celular abierto. */
  static final Duration VENCIMIENTO = Duration.ofHours(2);

  /** Cómo terminó un intento de ingreso. */
  enum Resultado {
    ENTRO,
    INCORRECTA,
    BLOQUEADO
  }

  /** Los fallidos de un chat y, si llegó al límite, hasta cuándo está bloqueado. */
  private record Fallidos(int cuantos, Instant bloqueadoHasta) {}

  /**
   * Se guarda el hash y no la contraseña: así las dos cosas que se comparan tienen siempre el mismo
   * largo, y la comparación no deja adivinar cuántos caracteres tiene.
   */
  private final byte[] huella;

  private final Clock reloj;
  private final Map<Long, Fallidos> fallidos = new ConcurrentHashMap<>();

  AccesoAdmin(@Value("${bot.admin.password:}") String contrasena, Clock reloj) {
    // Sin strip, un espacio que se coló al copiar la contraseña en la terminal la vuelve imposible
    // de escribir en Telegram, que además ya llega recortada.
    String limpia = contrasena == null ? "" : contrasena.strip();
    this.huella = limpia.isEmpty() ? null : huella(limpia);
    this.reloj = reloj;
  }

  /** Sin contraseña configurada no se entra de ninguna forma. */
  boolean habilitado() {
    return huella != null;
  }

  Instant ahora() {
    return reloj.instant();
  }

  /** Cuánto le falta al bloqueo de un chat, o cero si no está bloqueado. */
  Duration bloqueoRestante(long chatId) {
    Fallidos f = fallidos.get(chatId);
    if (f == null || f.bloqueadoHasta() == null) {
      return Duration.ZERO;
    }
    Duration resta = Duration.between(ahora(), f.bloqueadoHasta());
    return resta.isNegative() ? Duration.ZERO : resta;
  }

  /** Los intentos que le quedan a un chat antes de quedar bloqueado. */
  int intentosRestantes(long chatId) {
    Fallidos f = fallidos.get(chatId);
    return INTENTOS - (f == null ? 0 : f.cuantos());
  }

  /**
   * Compara la contraseña y lleva la cuenta de los fallidos de ese chat.
   *
   * <p>Sincronizado para que dos mensajes del mismo chat no cuenten el mismo intento dos veces.
   */
  synchronized Resultado intentar(long chatId, String intento) {
    if (!habilitado()) {
      return Resultado.INCORRECTA;
    }
    Fallidos previos = fallidos.get(chatId);
    if (previos != null && previos.bloqueadoHasta() != null) {
      if (ahora().isBefore(previos.bloqueadoHasta())) {
        return Resultado.BLOQUEADO;
      }
      // El bloqueo ya pasó: se arranca de cero, no con un intento de gracia.
      fallidos.remove(chatId);
      previos = null;
    }
    String escrito = intento == null ? "" : intento.strip();
    // isEqual y no equals: equals corta en el primer carácter distinto, y el tiempo que tarda en
    // contestar deja adivinar la contraseña de a un carácter.
    if (!escrito.isEmpty() && MessageDigest.isEqual(huella, huella(escrito))) {
      fallidos.remove(chatId);
      return Resultado.ENTRO;
    }
    int cuantos = (previos == null ? 0 : previos.cuantos()) + 1;
    if (cuantos >= INTENTOS) {
      fallidos.put(chatId, new Fallidos(cuantos, ahora().plus(BLOQUEO)));
      return Resultado.BLOQUEADO;
    }
    fallidos.put(chatId, new Fallidos(cuantos, null));
    return Resultado.INCORRECTA;
  }

  /** Si una sesión que se usó por última vez en {@code ultimoUso} ya venció. */
  boolean vencio(Instant ultimoUso) {
    return ultimoUso == null || !ahora().isBefore(ultimoUso.plus(VENCIMIENTO));
  }

  private static byte[] huella(String texto) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // Toda JVM trae SHA-256: si faltara, no hay forma segura de seguir.
      throw new IllegalStateException(e);
    }
  }
}
