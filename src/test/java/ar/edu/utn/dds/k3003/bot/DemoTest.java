package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Lo primero que se usa frente a los docentes: dejar las bases vacías y cargar las precondiciones.
 * Si alguno de los dos se corta, la demostración arranca mal antes de mostrar un solo flujo.
 */
class DemoTest {

  // ── Preparar ───────────────────────────────────────────────────────────────

  /**
   * Con los clientes reales y un servidor falso que contesta lo mismo que cada módulo: así el
   * prefijo que algunos métodos de Donadores le agregan al JSON aparece igual que en Render, en vez
   * de depender de que quien escribe el test se acuerde de simularlo.
   */
  @Test
  @DisplayName("Preparar completa todos los pasos con las respuestas reales de los módulos y dice los números creados")
  void prepararConRespuestasReales() {
    RestTemplate restDonaciones = new RestTemplate();
    RestTemplate restDonadores = new RestTemplate();
    RestTemplate restLogistica = new RestTemplate();
    RestTemplate restIncentivos = new RestTemplate();
    MockRestServiceServer donaciones = MockRestServiceServer.bindTo(restDonaciones).build();
    MockRestServiceServer donadores = MockRestServiceServer.bindTo(restDonadores).build();
    MockRestServiceServer logistica = MockRestServiceServer.bindTo(restLogistica).build();
    MockRestServiceServer incentivos = MockRestServiceServer.bindTo(restIncentivos).build();

    // Donaciones: la consulta que lo despierta, el identificador y el producto.
    donaciones
        .expect(requestTo("http://donaciones/productos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    donaciones
        .expect(requestTo("http://donaciones/identificadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            creado(
                """
                {"id":"11","tipo":"CODIGODEBARRAS","descripcion":"Codigo de barras 1790995023"}"""));
    donaciones
        .expect(requestTo("http://donaciones/productos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.identificadorID").value("11"))
        .andRespond(
            creado(
                """
                {"id":"12","nombre":"Arroz1790995023","descripcion":"Arroz blanco largo fino",
                 "categoriaID":"alimentos","identificadorID":"11"}"""));

    // Donadores contesta el DTO pelado: el «Donador registrado: » lo agrega el cliente.
    donadores
        .expect(requestTo("http://donadores/donadores"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    donadores
        .expect(requestTo("http://donadores/donadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            creado(
                """
                {"id":"7","nombre":"Carlos","apellido":"Demo","edad":35,
                 "email":"carlos1790995023@demo.com","nroDocumento":"90995023",
                 "domicilio":"Av Corrientes 1234","estado":"VERIFICADO","categoria":null}"""));
    donadores
        .expect(requestTo("http://donadores/entidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            creado(
                """
                {"id":"4","razonSocial":"Comedor Solidario 1790995023",
                 "domicilio":"Av Rivadavia 5000","telefono":"1144445555","correo":"c1790995023@d.com"}"""));
    donadores
        .expect(requestTo("http://donadores/necesidades"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.entidadID").value("4"))
        .andExpect(jsonPath("$.productoSolicitadoID").value("12"))
        .andRespond(
            creado(
                """
                {"id":"9","entidadID":"4","nivelDeUrgencia":8,
                 "descripcion":"Arroz para el comedor mensual","cantidadObjetivo":20,
                 "productoSolicitadoID":"12","tipo":"EXTRAORDINARIA"}"""));

    // Logística: el depósito se crea por /depositos, que es el que entiende SUB_ATENDIDOS.
    logistica
        .expect(requestTo("http://logistica/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    logistica
        .expect(requestTo("http://logistica/depositos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.id").value("DEP-TEST"))
        .andExpect(jsonPath("$.algoritmo").value("SUB_ATENDIDOS"))
        .andRespond(
            withSuccess(
                """
                {"id":"DEP-TEST","nombre":"Deposito Central UTN","direccion":"Av Medrano 951",
                 "capacidadMaxima":5000,"stockActual":[],"algoritmo":"SUB_ATENDIDOS"}""",
                MediaType.APPLICATION_JSON));

    incentivos
        .expect(requestTo("http://incentivos/insignias"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    incentivos
        .expect(requestTo("http://incentivos/insignias"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"id\":\"ins-1\",\"nombre\":\"Solidario 1\",\"descripcion\":\"Donacion realizada\"}",
                MediaType.APPLICATION_JSON));
    incentivos
        .expect(requestTo("http://incentivos/misiones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"mis-1\"}", MediaType.APPLICATION_JSON));

    Demo demo =
        new Demo(
            new DonacionesApiClient(restDonaciones, "http://donaciones"),
            new DonadoresApiClient(restDonadores, "http://donadores"),
            new LogisticaApiClient(restLogistica, "http://logistica"),
            new IncentivosApiClient(restIncentivos, "http://incentivos"),
            "DEP-TEST");

    String salida = demo.preparar();

    assertFalse(salida.contains("Se cortó"), salida);
    assertTrue(salida.contains("Producto <b>12</b>"), salida);
    assertTrue(salida.contains("Donador <b>7</b>"), salida);
    assertTrue(salida.contains("Entidad <b>4</b>"), salida);
    assertTrue(salida.contains("Depósito <b>DEP-TEST</b>"), salida);
    assertTrue(salida.contains("Insignia y misión creadas"), salida);
    assertTrue(salida.contains("Necesidad <b>9</b>"), salida);
    assertTrue(
        salida.contains("/donarcomo") && salida.contains("el donador es el <b>7</b>")
            && salida.contains("el producto el <b>12</b>"),
        "tiene que decir con qué números seguir: " + salida);
    assertFalse(salida.contains(";"), "el comando guiado no pide punto y coma: " + salida);
    donaciones.verify();
    donadores.verify();
    logistica.verify();
    incentivos.verify();
  }

  // ── Reiniciar ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Si Incentivos tarda en despertar, el primer pedido lo despierta y el borrado llega")
  void incentivosTardaEnDespertar() {
    String salida = demo(new IncentivosQueTardaEnDespertar()).reiniciar();

    assertTrue(salida.contains("✅ <b>Incentivos</b> · borrado"), salida);
    assertTrue(salida.contains("Sistema reiniciado"), salida);
    assertTrue(salida.contains("/preparar"), "con todo borrado, lo que sigue es preparar");
  }

  @Test
  @DisplayName("Reiniciar despierta los cuatro módulos con consultas antes de borrarlos")
  void despiertaAntesDeBorrar() {
    DonacionesApiClient donaciones = mock(DonacionesApiClient.class);
    DonadoresApiClient donadores = mock(DonadoresApiClient.class);
    LogisticaApiClient logistica = mock(LogisticaApiClient.class);
    IncentivosApiClient incentivos = mock(IncentivosApiClient.class);

    new Demo(donaciones, donadores, logistica, incentivos, "DEP-TEST").reiniciar();

    InOrder enDonaciones = inOrder(donaciones);
    enDonaciones.verify(donaciones).listarProductos();
    enDonaciones.verify(donaciones).reset();
    InOrder enDonadores = inOrder(donadores);
    enDonadores.verify(donadores).listarDonadores();
    enDonadores.verify(donadores).reset();
    InOrder enLogistica = inOrder(logistica);
    enLogistica.verify(logistica).listarDepositos();
    enLogistica.verify(logistica).limpiarBase();
    InOrder enIncentivos = inOrder(incentivos);
    enIncentivos.verify(incentivos).listarInsignias();
    enIncentivos.verify(incentivos).limpiar();
  }

  @Test
  @DisplayName("Un borrado que falla una vez se repite: borrar dos veces no tiene riesgo")
  void borradoQueFallaUnaVez() {
    IncentivosApiClient incentivos = mock(IncentivosApiClient.class);
    when(incentivos.limpiar())
        .thenThrow(new RuntimeException("Incentivos respondió con error (502): sin detalle."))
        .thenReturn("Base limpiada");

    String salida = demo(incentivos).reiniciar();

    assertTrue(salida.contains("✅ <b>Incentivos</b> · borrado"), salida);
    verify(incentivos, times(2)).limpiar();
  }

  @Test
  @DisplayName("Si un módulo no despierta, dice que está dormido o caído y que se repita /reiniciar")
  void moduloQueNoDespierta() {
    IncentivosApiClient incentivos = mock(IncentivosApiClient.class);
    when(incentivos.listarInsignias()).thenThrow(new RuntimeException("Incentivos no responde"));
    when(incentivos.limpiar()).thenThrow(new RuntimeException("Incentivos no responde"));

    String salida = demo(incentivos).reiniciar();

    assertTrue(salida.contains("<b>Incentivos</b> · no contestó: está dormido o caído"), salida);
    assertTrue(salida.contains("Repetí /reiniciar en un minuto"), salida);
    assertTrue(salida.contains("✅ <b>Donaciones</b> · borrado"), "los otros tres sí se borraron");
    assertFalse(salida.contains("Sistema reiniciado"), "no quedó todo vacío: no se puede decir eso");
    assertFalse(salida.contains("/preparar"), "preparar sobre una base a medio borrar mezcla datos");
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  /** Los otros tres módulos contestan bien: lo que se prueba es Incentivos. */
  private static Demo demo(IncentivosApiClient incentivos) {
    return new Demo(
        mock(DonacionesApiClient.class),
        mock(DonadoresApiClient.class),
        mock(LogisticaApiClient.class),
        incentivos,
        "DEP-TEST");
  }

  private static org.springframework.test.web.client.ResponseCreator creado(String json) {
    return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(json);
  }

  /**
   * Incentivos como lo deja Render después de un rato sin tráfico: el primer pedido, sea cual sea,
   * se pierde despertándolo, y los que siguen contestan.
   */
  private static class IncentivosQueTardaEnDespertar extends IncentivosApiClient {

    private final AtomicInteger pedidos = new AtomicInteger();

    IncentivosQueTardaEnDespertar() {
      super(new RestTemplate(), "http://incentivos");
    }

    @Override
    public String listarInsignias() {
      return contestar("[]");
    }

    @Override
    public String limpiar() {
      return contestar("Base limpiada");
    }

    private String contestar(String respuesta) {
      if (pedidos.getAndIncrement() == 0) {
        throw new RuntimeException(
            "Incentivos no responde (http://incentivos). Los servicios de Render se duermen: "
                + "puede tardar hasta un minuto en despertar. Reintentá en unos segundos.");
      }
      return respuesta;
    }
  }
}
