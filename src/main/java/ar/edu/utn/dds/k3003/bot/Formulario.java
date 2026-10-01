package ar.edu.utn.dds.k3003.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Un formulario que el bot va completando, preguntando de a un dato por vez.
 *
 * <p>Pedir «nombre;apellido;edad;email;documento;domicilio» en una sola línea es cómodo para
 * quien escribió el bot y hostil para cualquier otro: hay que acordarse del orden, no se puede
 * corregir en el medio, y un punto y coma de más rompe todo sin decir dónde. Preguntando de a
 * uno, el error se avisa en el momento y no hay nada que recordar.
 *
 * <p>La validación de cada campo es sobre la <b>forma</b> —que una edad sea un número, que el
 * tipo sea uno de los dos que existen—, nunca sobre la regla de negocio. Que la cantidad tenga
 * que ser mayor a cero lo decide el módulo, y si el bot lo repitiera habría dos lugares donde
 * mantenerlo.
 */
class Formulario {

  /** Lo que se hace cuando están todas las respuestas. */
  @FunctionalInterface
  interface Accion {
    String ejecutar(List<String> respuestas);
  }

  /**
   * Un dato a pedir.
   *
   * @param pregunta lo que se le muestra a la persona
   * @param acepta si lo que escribió tiene la forma que corresponde
   * @param siNoSirve qué explicarle cuando no la tiene
   * @param sugerencias algo para mostrar junto con la pregunta, como el catálogo de productos,
   *     para no tener que irse a otro comando a buscar un número
   */
  record Campo(
      String pregunta, Predicate<String> acepta, String siNoSirve, Supplier<String> sugerencias) {

    static Campo texto(String pregunta) {
      return new Campo(pregunta, t -> !t.isBlank(), "Eso quedó vacío.", null);
    }

    static Campo numero(String pregunta) {
      return new Campo(pregunta, Campo::esEntero, "Tiene que ser un número entero.", null);
    }

    static Campo opcion(String pregunta, String... opciones) {
      String lista = String.join(" o ", opciones);
      return new Campo(
          pregunta + " (" + lista + ")",
          t -> Arrays.stream(opciones).anyMatch(o -> o.equalsIgnoreCase(t.trim())),
          "Tiene que ser " + lista + ".",
          null);
    }

    /** El mismo campo, mostrando además un listado para elegir. */
    Campo conAyuda(Supplier<String> sugerencias) {
      return new Campo(pregunta, acepta, siNoSirve, sugerencias);
    }

    private static boolean esEntero(String t) {
      try {
        Integer.parseInt(t.trim());
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }
  }

  private final String titulo;
  private final List<Campo> campos;
  private final Accion accion;
  private final List<String> respuestas = new ArrayList<>();
  private boolean termino;

  Formulario(String titulo, Accion accion, Campo... campos) {
    this.titulo = titulo;
    this.accion = accion;
    this.campos = List.of(campos);
  }

  String primeraPregunta() {
    return "📝 <b>" + titulo + "</b>\nTe voy preguntando de a una. /cancelar para dejarlo.\n\n"
        + pregunta(0);
  }

  /**
   * Toma lo que escribió la persona y devuelve qué contestarle: la pregunta siguiente, el aviso
   * de que ese dato no sirve, o el resultado de la operación si era el último.
   */
  String responder(String texto) {
    Campo actual = campos.get(respuestas.size());
    if (!actual.acepta().test(texto)) {
      return "⚠️ " + actual.siNoSirve() + "\n\n" + pregunta(respuestas.size());
    }
    respuestas.add(texto.trim());
    if (respuestas.size() < campos.size()) {
      return pregunta(respuestas.size());
    }
    // Se marca terminado antes de ejecutar: si la operación falla, la persona tiene que quedar
    // libre para volver a intentar, no atrapada en un formulario que ya no va a avanzar.
    termino = true;
    return accion.ejecutar(respuestas);
  }

  boolean termino() {
    return termino;
  }

  private String pregunta(int indice) {
    Campo campo = campos.get(indice);
    String ayuda = "";
    if (campo.sugerencias() != null) {
      try {
        String listado = campo.sugerencias().get();
        if (listado != null && !listado.isBlank()) {
          ayuda = "\n\n" + listado;
        }
      } catch (Exception e) {
        // Si el módulo no contesta se pregunta igual: la ayuda es un lujo, el dato lo tiene la
        // persona de todos modos.
      }
    }
    return "<b>" + (indice + 1) + " de " + campos.size() + "</b> · " + campo.pregunta() + ayuda;
  }
}
