package ar.edu.utn.dds.k3003.bot;

import java.util.UUID;

/**
 * El identificador que agrupa, en los logs centralizados, todo lo que dispara una misma operación.
 *
 * <p>Una donación hecha desde el bot toca Donaciones, Donadores y Logística. Cada módulo reenvía
 * este valor al siguiente, así que filtrando por él en Datadog aparece el recorrido completo en vez
 * de líneas sueltas de tres servicios distintos.
 *
 * <p>Se guarda por hilo: el bot atiende los mensajes de a uno, pero atarlo al hilo evita que dos
 * operaciones se mezclen si algún día se atiende en paralelo.
 */
final class Traza {

  static final String HEADER = "X-Trace-Id";

  private static final ThreadLocal<String> ACTUAL = new ThreadLocal<>();

  private Traza() {}

  static String nueva() {
    String id = "bot-" + UUID.randomUUID().toString().substring(0, 8);
    ACTUAL.set(id);
    return id;
  }

  static String actual() {
    return ACTUAL.get();
  }

  static void cerrar() {
    ACTUAL.remove();
  }
}
