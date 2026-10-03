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

  /** La respuesta que deja un dato como está, en los formularios de cambios. */
  static final String SIN_CAMBIOS = "-";

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

    /**
     * Una lista numerada: se contesta con el número o escribiendo el nombre, y {@link #elegida}
     * lo traduce.
     *
     * <p>Qué respuestas sirven lo decide quien arma el formulario. Cuando el módulo acepta texto
     * libre, la lista solo se ofrece y cualquier respuesta sirve.
     */
    static Campo numerada(String pregunta, List<String> opciones, Predicate<String> acepta) {
      StringBuilder texto = new StringBuilder(pregunta).append("\n");
      for (int i = 0; i < opciones.size(); i++) {
        texto.append("\n").append(i + 1).append(" · ").append(opciones.get(i));
      }
      texto.append("\n\nPasame el número o el nombre.");
      return new Campo(
          texto.toString(),
          acepta,
          "Elegí uno de la lista, del 1 al " + opciones.size() + ".",
          null);
    }

    /** El mismo campo, mostrando además un listado para elegir. */
    Campo conAyuda(Supplier<String> sugerencias) {
      return new Campo(pregunta, acepta, siNoSirve, sugerencias);
    }

    /**
     * El mismo campo, aceptando además {@link #SIN_CAMBIOS} para dejar el dato como está.
     *
     * <p>Para los cambios: sin esto, corregir el teléfono de una entidad obligaba a reescribir el
     * nombre, el domicilio y el correo.
     */
    Campo oSinCambios() {
      return new Campo(
          pregunta + " (- para dejarlo como está)",
          t -> SIN_CAMBIOS.equals(t.trim()) || acepta.test(t),
          siNoSirve + " O un - para dejarlo como está.",
          sugerencias);
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
    if (campos.size() == 1) {
      // Una consulta sin número pregunta una sola cosa: «de a una» y «1 de 1» sobran.
      return "🔎 <b>" + titulo + "</b>\n" + pregunta(0) + "\n\n/cancelar para dejarlo.";
    }
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

  /**
   * Traduce la respuesta a una lista numerada al valor que corresponde.
   *
   * <p>El número elige por posición, y el nombre se reconoce sin importar mayúsculas, tildes,
   * espacios ni guiones: «código de barras» es CODIGODEBARRAS. Lo que no coincide con ninguno se
   * devuelve tal cual, para que si no sirve lo diga el módulo y no el bot.
   *
   * @param valores lo que se manda al módulo, en el mismo orden en que se numeró la lista
   */
  static String elegida(String respuesta, List<String> valores) {
    String escrita = respuesta.trim();
    try {
      int posicion = Integer.parseInt(escrita);
      if (posicion >= 1 && posicion <= valores.size()) {
        return valores.get(posicion - 1);
      }
    } catch (NumberFormatException e) {
      // No es un número: se busca por el nombre.
    }
    String buscada = comparable(escrita);
    return valores.stream()
        .filter(valor -> comparable(valor).equals(buscada))
        .findFirst()
        .orElse(escrita);
  }

  private static String comparable(String texto) {
    return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toUpperCase()
        .replaceAll("[^A-Z0-9]", "");
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
    String cuenta =
        campos.size() == 1 ? "" : "<b>" + (indice + 1) + " de " + campos.size() + "</b> · ";
    return cuenta + campo.pregunta() + ayuda;
  }
}
