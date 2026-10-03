package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Lo que ve la persona en el chat cuando un módulo rechaza algo.
 *
 * <p>El bot no decide ninguna regla: lo que se prueba es que el motivo real del módulo llegue, que
 * se entienda qué hacer con él, y que no rompa el HTML con el que Telegram muestra el mensaje. Los
 * cuerpos están copiados del formato que usa cada módulo: Donaciones contesta {@code {"error":
 * ...}} y Donadores texto plano.
 */
class ErroresDeLaApiTest {

  private static final String DONACIONES = "http://donaciones";
  private static final String DONADORES = "http://donadores";

  private MockRestServiceServer servidor;
  private DonacionesApiClient donaciones;
  private DonadoresApiClient donadores;

  @BeforeEach
  void setUp() {
    RestTemplate rest = new RestTemplate();
    servidor = MockRestServiceServer.bindTo(rest).build();
    donaciones = new DonacionesApiClient(rest, DONACIONES);
    donadores = new DonadoresApiClient(rest, DONADORES);
  }

  // ── Donaciones ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Donar con un donador que no existe dice que no lo encontró y cuál era")
  void donadorInexistente() {
    String mensaje = errorAlDonar(HttpStatus.NOT_FOUND, "{\"error\":\"No existe el donador 99\"}");

    assertTrue(mensaje.contains("No encontré eso"), mensaje);
    assertTrue(
        mensaje.contains("No existe el donador 99"),
        "sin el detalle no se distingue un donador mal puesto de un producto mal puesto");
  }

  @Test
  @DisplayName("Un 403 por donador no habilitado se sigue explicando como que no puede donar")
  void donadorNoHabilitado() {
    String mensaje =
        errorAlDonar(
            HttpStatus.FORBIDDEN,
            "{\"error\":\"No puede donar: el donador 5 no está habilitado para donar\"}");

    assertTrue(mensaje.contains("No podés donar"), mensaje);
  }

  @Test
  @DisplayName("Quejarse de una donación sin entregar muestra el motivo del módulo y cómo se destraba")
  void quejaSobreDonacionSinEntregar() {
    String mensaje =
        errorAlQuejarse(
            "{\"error\":\"No se puede registrar la queja: la donación 4 todavía no fue entregada"
                + " (estado actual: INGRESADA). Solo se aceptan quejas sobre donaciones entregadas"
                + " (ACEPTADA).\"}");

    assertTrue(
        mensaje.contains("todavía no fue entregada (estado actual: INGRESADA)"),
        "el motivo lo pone Donaciones y tiene que llegar entero: " + mensaje);
    assertTrue(mensaje.contains("/reportarentrega"), "hay que decir cómo se llega a entregada");
    assertFalse(mensaje.contains("error:"), "el envoltorio JSON no aporta nada en un chat");
    assertFalse(mensaje.contains("No se pudo (409)"), "el número solo no le dice nada a nadie");
  }

  @Test
  @DisplayName("Una segunda queja sobre la misma donación avisa que admite una sola")
  void quejaRepetida() {
    String mensaje =
        errorAlQuejarse(
            "{\"error\":\"No se puede registrar la queja: la donación 4 ya tiene una queja"
                + " registrada (estado actual: CONQUEJA). Solo se aceptan quejas sobre donaciones"
                + " entregadas (ACEPTADA).\"}");

    assertTrue(mensaje.contains("ya tiene una queja registrada"), mensaje);
    assertTrue(mensaje.contains("una sola queja"));
    assertFalse(mensaje.contains("/reportarentrega"), "ya está entregada: reportarla no la destraba");
  }

  @Test
  @DisplayName("Un 502 de Donaciones nombra al módulo que no respondió y pide probar en un minuto")
  void otroModuloCaido() {
    String mensaje =
        errorAlDonar(
            HttpStatus.BAD_GATEWAY,
            "{\"error\":\"El módulo Logística no respondió (I/O error: Read timed out)\"}");

    assertTrue(mensaje.contains("El módulo Logística no está respondiendo"), mensaje);
    assertTrue(mensaje.contains("probá de nuevo en un minuto"));
    assertFalse(
        mensaje.contains("El módulo de Donaciones no responde"),
        "Donaciones contestó: el que falló es otro");
  }

  @Test
  @DisplayName("Con Donadores caído, el 502 lo nombra como lo nombra Donaciones")
  void donadoresCaido() {
    String mensaje =
        errorAlDonar(
            HttpStatus.BAD_GATEWAY,
            "{\"error\":\"El módulo Donadores y Entidades respondió con error 503\"}");

    assertTrue(mensaje.contains("El módulo Donadores y Entidades no está respondiendo"), mensaje);
  }

  @Test
  @DisplayName("Un 502 de Render, sin el cuerpo de Donaciones, dice que Donaciones se está despertando")
  void donacionesDormida() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .body("<html><body>502 Bad Gateway</body></html>")
                .contentType(MediaType.TEXT_HTML));

    String mensaje =
        assertThrows(
                RuntimeException.class, () -> donaciones.donar("5", "DEP-1", "algo", "3", 10))
            .getMessage();

    assertTrue(mensaje.contains("El módulo de Donaciones no responde"), mensaje);
    assertTrue(mensaje.contains("probá de nuevo en un minuto"));
  }

  @Test
  @DisplayName("Lo que manda el módulo se escapa para no romper el HTML del mensaje")
  void detalleEscapado() {
    String mensaje =
        errorAlDonar(
            HttpStatus.BAD_GATEWAY,
            "{\"error\":\"El módulo Logística respondió con error 500: <html>Error & caída</html>\"}");

    assertFalse(mensaje.contains("<html>"), "Telegram tomaría eso como una etiqueta: " + mensaje);
    assertTrue(mensaje.contains("&lt;html&gt;"));
    assertTrue(mensaje.contains("&amp;"));
  }

  // ── Donadores ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Registrarse con un documento repetido dice que ya existe y cómo entrar")
  void donadorRepetido() {
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .body("Ya existe un donador con el documento 40100001")
                .contentType(MediaType.TEXT_PLAIN));

    String mensaje =
        assertThrows(
                RuntimeException.class,
                () ->
                    donadores.registrarDonadorRaw(
                        "Ana", "Gomez", 30, "ana@mail.com", "40100001", "Calle 1"))
            .getMessage();

    assertTrue(mensaje.contains("Ya existe un donador con el documento 40100001"), mensaje);
    assertTrue(mensaje.contains("/entrar"), "lo más probable es que ya estuviera registrado");
    assertFalse(mensaje.contains("/donadores"), "la lista de donadores es solo para el admin");
  }

  @Test
  @DisplayName("Borrar una necesidad que no existe dice qué no encontró")
  void necesidadInexistente() {
    servidor
        .expect(requestTo(DONADORES + "/necesidades/99"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .body("No existe una necesidad con ese ID")
                .contentType(MediaType.TEXT_PLAIN));

    String mensaje =
        assertThrows(RuntimeException.class, () -> donadores.borrarNecesidad("99")).getMessage();

    assertTrue(mensaje.contains("No encontrado"), mensaje);
    assertTrue(
        mensaje.contains("No existe una necesidad con ese ID"),
        "antes era un 400 que mostraba el motivo: con el 404 no se tiene que perder");
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  private String errorAlDonar(HttpStatus status, String cuerpo) {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(status).body(cuerpo).contentType(MediaType.APPLICATION_JSON));
    return assertThrows(
            RuntimeException.class, () -> donaciones.donar("5", "DEP-1", "algo", "3", 10))
        .getMessage();
  }

  private String errorAlQuejarse(String cuerpo) {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/4/quejas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CONFLICT).body(cuerpo).contentType(MediaType.APPLICATION_JSON));
    return assertThrows(
            RuntimeException.class, () -> donaciones.registrarQueja("4", "Llegó en mal estado"))
        .getMessage();
  }
}
