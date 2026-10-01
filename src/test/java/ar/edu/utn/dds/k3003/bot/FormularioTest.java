package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.utn.dds.k3003.bot.Formulario.Campo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El formulario es lo que hace que no haya que escribir «a;b;c»: conviene que no se rompa. */
class FormularioTest {

  @Test
  @DisplayName("Pregunta de a uno y recién al final ejecuta, con las respuestas en orden")
  void preguntaDeAUno() {
    List<String> recibidas = new ArrayList<>();
    Formulario f =
        new Formulario(
            "Prueba",
            r -> {
              recibidas.addAll(r);
              return "hecho";
            },
            Campo.texto("¿Nombre?"),
            Campo.numero("¿Edad?"));

    assertTrue(f.primeraPregunta().contains("¿Nombre?"));
    assertTrue(f.primeraPregunta().contains("1 de 2"), "conviene saber cuánto falta");

    String segunda = f.responder("Ana");
    assertTrue(segunda.contains("¿Edad?"));
    assertFalse(f.termino(), "todavía falta un dato");

    assertEquals("hecho", f.responder("30"));
    assertTrue(f.termino());
    assertEquals(List.of("Ana", "30"), recibidas);
  }

  @Test
  @DisplayName("Un dato con la forma equivocada se vuelve a pedir, sin perder lo anterior")
  void datoQueNoSirve() {
    Formulario f =
        new Formulario(
            "Prueba",
            r -> "edad " + r.get(1),
            Campo.texto("¿Nombre?"),
            Campo.numero("¿Edad?"));
    f.responder("Ana");

    String aviso = f.responder("treinta");

    assertTrue(aviso.contains("número entero"), "hay que decir qué esperaba");
    assertTrue(aviso.contains("¿Edad?"), "y volver a preguntar lo mismo");
    assertFalse(f.termino());
    assertEquals("edad 30", f.responder("30"), "lo contestado antes no se perdió");
  }

  @Test
  @DisplayName("Una opción se acepta sin importar las mayúsculas y se listan las posibles")
  void opciones() {
    Formulario f =
        new Formulario(
            "Prueba", r -> r.get(0), Campo.opcion("¿Tipo?", "EXTRAORDINARIA", "RECURRENTE"));

    assertTrue(f.primeraPregunta().contains("EXTRAORDINARIA o RECURRENTE"));
    assertTrue(f.responder("cualquiera").contains("Tiene que ser"));
    assertEquals("extraordinaria", f.responder("extraordinaria"));
  }

  @Test
  @DisplayName("Si la operación falla, el formulario igual queda cerrado")
  void operacionQueFalla() {
    Formulario f =
        new Formulario(
            "Prueba",
            r -> {
              throw new RuntimeException("el módulo lo rechazó");
            },
            Campo.texto("¿Algo?"));

    try {
      f.responder("algo");
    } catch (RuntimeException e) {
      // La maneja el bot, que le muestra el motivo a la persona.
    }

    assertTrue(f.termino(), "si no, quedaría atrapada contestando preguntas que ya no avanzan");
  }

  @Test
  @DisplayName("Si el listado de ayuda falla, se pregunta igual")
  void ayudaQueNoCarga() {
    Formulario f =
        new Formulario(
            "Prueba",
            r -> r.get(0),
            Campo.numero("¿Qué producto?")
                .conAyuda(
                    () -> {
                      throw new RuntimeException("el módulo no responde");
                    }));

    assertTrue(f.primeraPregunta().contains("¿Qué producto?"));
  }
}
