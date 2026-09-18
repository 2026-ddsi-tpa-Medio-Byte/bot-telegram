package ar.edu.utn.dds.k3003.bot;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

/**
 * Repite una consulta cuando el módulo no contestó.
 *
 * <p>En el plan gratuito de Render los servicios se duermen sin tráfico, y el primer pedido se
 * pierde despertándolos. Sin este reintento, el primer comando después de un rato de inactividad
 * falla siempre, aunque el sistema esté perfecto.
 *
 * <p><b>Solo para consultas.</b> Una operación que modifica datos no se puede repetir: si no hubo
 * respuesta no se sabe si llegó, y repetirla podría dejar dos donaciones donde había una.
 */
final class Reintento {

  private static final Logger log = LoggerFactory.getLogger(Reintento.class);

  private Reintento() {}

  static <T> T siNoResponde(Supplier<T> consulta) {
    try {
      return consulta.get();
    } catch (ResourceAccessException primera) {
      log.info("El módulo no contestó, reintentando una vez: {}", primera.getMessage());
      return consulta.get();
    }
  }
}
