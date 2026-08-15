package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Los JSON de abajo son respuestas reales de la API, copiadas tal cual. */
class FormatoTest {

  @Test
  @DisplayName("Un donador se muestra legible, sin llaves ni comillas")
  void donadorLegible() {
    String json =
        """
        {"id":"1","nombre":"Ana","apellido":"Gomez","edad":30,"email":"ana@mail.com",
         "nroDocumento":"40100001","domicilio":"Calle 1","estado":"VERIFICADO",
         "categoria":"Colaborador"}""";

    String salida = Formato.donador(json);

    assertTrue(salida.contains("Ana Gomez"));
    assertTrue(salida.contains("40100001"));
    assertTrue(salida.contains("✅"), "un donador verificado lleva tilde verde");
    assertFalse(salida.contains("{"), "no tiene que quedar nada del JSON crudo");
    assertFalse(salida.contains("\"nroDocumento\""));
  }

  @Test
  @DisplayName("El estado del donador se explica, no se muestra pelado")
  void estadoExplicado() {
    String baneado =
        """
        {"id":"2","nombre":"Bruno","apellido":"Diaz","edad":40,"email":"b@mail.com",
         "nroDocumento":"40100002","domicilio":"Calle 2","estado":"BANEADO"}""";

    String salida = Formato.donador(baneado);

    assertTrue(salida.contains("🚫"));
    assertTrue(salida.contains("no podés donar"), "conviene decir qué implica estar baneado");
  }

  @Test
  @DisplayName("Una necesidad muestra el progreso con barra")
  void necesidadConBarra() {
    String json =
        """
        {"id":"1","entidadID":"1","nivelDeUrgencia":8,"descripcion":"Arroz para el comedor",
         "cantidadObjetivo":10,"cantidadActual":10,"productoSolicitadoID":"1",
         "tipo":"EXTRAORDINARIA"}""";

    String salida = Formato.necesidad(json);

    assertTrue(salida.contains("▓▓▓▓▓▓▓▓▓▓"), "completa al 100% son diez casilleros llenos");
    assertTrue(salida.contains("10 de 10"));
    assertTrue(salida.contains("completa"));
    assertTrue(salida.contains("🔴"), "urgencia 8 es alta");
  }

  @Test
  @DisplayName("Una necesidad a medias muestra la barra parcial")
  void necesidadAMedias() {
    String json =
        """
        {"id":"3","entidadID":"1","nivelDeUrgencia":3,"descripcion":"Frazadas",
         "cantidadObjetivo":20,"cantidadActual":10,"productoSolicitadoID":"2",
         "tipo":"RECURRENTE"}""";

    String salida = Formato.necesidad(json);

    assertTrue(salida.contains("▓▓▓▓▓░░░░░"), "la mitad son cinco casilleros");
    assertFalse(salida.contains("completa"));
    assertTrue(salida.contains("🟢"), "urgencia 3 es baja");
  }

  @Test
  @DisplayName("El estado de una donación se traduce a algo entendible")
  void donacionLegible() {
    String json =
        """
        {"id":"5","donadorID":"1","depositoID":"DEP-UTN-01","descripcion":"Diez kilos de arroz",
         "productoID":"1","cantidad":10,"estado":"INGRESADA"}""";

    String salida = Formato.donacion(json);

    assertTrue(salida.contains("10 unidades"));
    assertTrue(salida.contains("esperando asignación"));
    assertFalse(salida.contains("donadorID"));
  }

  @Test
  @DisplayName("Las estadísticas listan las insignias en vez de mostrar el array")
  void estadisticasConInsignias() {
    String json =
        """
        {"id":"1","nombre":"Ana","apellido":"Gomez","edad":30,"estado":"VERIFICADO",
         "categoria":"TRANSFORMADOR","misionActualID":"mis-1",
         "insigniasID":["ins-completitud","ins-exitosas"]}""";

    String salida = Formato.estadisticas(json);

    assertTrue(salida.contains("Insignias ganadas: 2"));
    assertTrue(salida.contains("ins-completitud"));
    assertFalse(salida.contains("["), "el array no se muestra crudo");
  }

  @Test
  @DisplayName("Sin insignias lo dice, en vez de mostrar una lista vacía")
  void estadisticasSinInsignias() {
    String json =
        """
        {"id":"1","nombre":"Ana","apellido":"Gomez","estado":"VERIFICADO",
         "categoria":"Colaborador","insigniasID":[]}""";

    assertTrue(Formato.estadisticas(json).contains("Todavía no ganaste ninguna"));
  }

  @Test
  @DisplayName("Una lista vacía da un mensaje, no corchetes")
  void listaVacia() {
    assertTrue(Formato.listaDonadores("[]").contains("No hay donadores"));
    assertTrue(Formato.listaEntidades("[]").contains("No hay entidades"));
    assertTrue(Formato.listaDonaciones("[]").contains("Todavía no hiciste"));
  }

  @Test
  @DisplayName("Si la respuesta no es JSON, se devuelve tal cual sin romperse")
  void respuestaQueNoEsJson() {
    String raro = "Algo salió mal en el servidor";
    org.junit.jupiter.api.Assertions.assertEquals(raro, Formato.donador(raro));
  }

  @Test
  @DisplayName("Los campos que faltan salen como guion, no como null")
  void camposFaltantes() {
    String salida = Formato.donador("{\"id\":\"9\",\"nombre\":\"Solo\"}");

    assertTrue(salida.contains("—"), "lo que falta se muestra como guion");
    assertFalse(salida.contains("null"));
  }
}
