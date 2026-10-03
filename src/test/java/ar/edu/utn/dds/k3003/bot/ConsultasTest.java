package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Las consultas de todo el dominio desde el menú de admin.
 *
 * <p>Logística e Incentivos van con sus clientes reales contra un servidor falso, con las
 * respuestas como las devuelven en Render: así se prueba la dirección exacta que se pide y que se
 * lean los campos como vienen (en Logística, en minúscula aunque su Swagger diga otra cosa).
 * Donaciones y Donadores, que ya tienen sus clientes probados, van mockeados.
 */
class ConsultasTest {

  private static final String LOGISTICA = "http://logistica";
  private static final String INCENTIVOS = "http://incentivos";

  private TelegramClient telegram;
  private DonadoresApiClient donadores;
  private DonacionesApiClient donaciones;
  private MockRestServiceServer logistica;
  private MockRestServiceServer incentivos;
  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    telegram = mock(TelegramClient.class);
    donadores = mock(DonadoresApiClient.class);
    donaciones = mock(DonacionesApiClient.class);
    RestTemplate restLogistica = new RestTemplate();
    RestTemplate restIncentivos = new RestTemplate();
    logistica = MockRestServiceServer.bindTo(restLogistica).build();
    incentivos = MockRestServiceServer.bindTo(restIncentivos).build();
    bot =
        BotDePrueba.armar(
            telegram,
            donadores,
            donaciones,
            new LogisticaApiClient(restLogistica, LOGISTICA),
            new IncentivosApiClient(restIncentivos, INCENTIVOS));
    BotDePrueba.entrarComoAdmin(bot, telegram, 1L);
  }

  // ── Logística ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/depositos lee /api/depositos, que trae el stock real, y muestra capacidad y stock de cada uno")
  void depositos() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                [{"nombre":"Deposito Central UTN","depositoid":"DEP-UTN-01",
                  "direccion":"Av. Medrano 951","capacidadMaxima":5000,"stockActual":26,
                  "algoritmo":"SUBATENDIDOS"}]""",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/depositos");

    logistica.verify();
    String salida = ultimo();
    assertTrue(salida.contains("DEP-UTN-01"), salida);
    assertTrue(salida.contains("Deposito Central UTN"), salida);
    assertTrue(salida.contains("26 de 5000"), "el stock contra la capacidad: " + salida);
    assertTrue(salida.contains("Sub-atendidos"), salida);
  }

  @Test
  @DisplayName("Sin depósitos, /api/depositos contesta 204 sin cuerpo y el bot lo dice")
  void sinDepositos() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/depositos"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    bot.handle(1L, "/depositos");

    assertTrue(ultimo().contains("No hay depósitos"), ultimo());
  }

  @Test
  @DisplayName("/stock muestra un producto depósito por depósito, con el total")
  void stockPorDeposito() {
    logistica
        .expect(requestTo(LOGISTICA + "/stock/3/detalle"))
        .andRespond(
            withSuccess(
                """
                {"productoid":"3","depositos":[
                  {"depositoid":"DEP-UTN-01","disponibleEnDeposito":20},
                  {"depositoid":"DEP-NORTE","disponibleEnDeposito":6}],
                 "totalDisponible":26}""",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/stock 3");

    String salida = ultimo();
    assertTrue(salida.contains("DEP-UTN-01 · 20 unidades"), salida);
    assertTrue(salida.contains("DEP-NORTE · 6 unidades"), salida);
    assertTrue(salida.contains("Total: <b>26</b>"), salida);
  }

  @Test
  @DisplayName("/stock de un producto sin unidades guardadas lo dice en vez de mostrar una lista vacía")
  void stockVacio() {
    logistica
        .expect(requestTo(LOGISTICA + "/stock/9/detalle"))
        .andRespond(
            withSuccess(
                "{\"productoid\":\"9\",\"depositos\":[],\"totalDisponible\":0}",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/stock 9");

    assertTrue(ultimo().contains("No hay unidades guardadas"), ultimo());
  }

  @Test
  @DisplayName("/asignaciones lista los paquetes pendientes, con los campos en minúscula como los manda Logística")
  void asignacionesPendientes() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/asignaciones?estado=ASIGNADA"))
        .andRespond(
            withSuccess(
                """
                [{"asignacionid":"8ecc3d3a","paqueteid":"paq-12","necesidadid":"7",
                  "fecha":"2026-10-02T21:03:11","estado":"ASIGNADA","origen":"MATCHMAKING",
                  "donacionid":"12","productoid":"3","cantidad":10},
                 {"asignacionid":"91ab","paqueteid":"paq-solicitud-ab12","necesidadid":"9",
                  "fecha":"2026-10-02T21:05:00","estado":"ASIGNADA","origen":"SOLICITUD_DONADORES",
                  "donacionid":null,"productoid":"3","cantidad":5}]""",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/asignaciones");

    String salida = ultimo();
    assertTrue(salida.contains("paq-12"), salida);
    assertTrue(salida.contains("necesidad nº 7"), salida);
    assertTrue(salida.contains("10 unidades del producto nº 3"), salida);
    assertTrue(salida.contains("paq-solicitud-ab12"), salida);
    assertTrue(salida.contains("necesidad nº 9"), salida);
    assertFalse(salida.contains("null"), "la de solicitud no tiene donación: " + salida);
  }

  @Test
  @DisplayName("Sin paquetes pendientes, /asignaciones lo dice")
  void sinAsignaciones() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/asignaciones?estado=ASIGNADA"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    bot.handle(1L, "/asignaciones");

    assertTrue(ultimo().contains("No hay paquetes pendientes"), ultimo());
  }

  @Test
  @DisplayName("/paquete acepta el número de donación o el código de una solicitud")
  void paquete() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andRespond(
            withSuccess(
                """
                {"asignacionid":"8ecc3d3a","paqueteid":"paq-12","necesidadid":"7",
                 "fecha":"2026-10-02T21:03:11","estado":"COMPLETADA","origen":"MATCHMAKING",
                 "donacionid":"12","productoid":"3","cantidad":10}""",
                MediaType.APPLICATION_JSON));
    logistica
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-solicitud-ab12"))
        .andRespond(
            withSuccess(
                """
                {"asignacionid":"91ab","paqueteid":"paq-solicitud-ab12","necesidadid":"9",
                 "estado":"ASIGNADA","origen":"SOLICITUD_DONADORES","donacionid":null,
                 "productoid":"3","cantidad":5}""",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/paquete 12");
    String entregado = ultimo();
    bot.handle(1L, "/paquete paq-solicitud-ab12");
    String pendiente = ultimo();

    logistica.verify();
    assertTrue(entregado.contains("Paquete paq-12"), entregado);
    assertTrue(entregado.contains("Entregado"), entregado);
    assertTrue(entregado.contains("Donación nº 12"), entregado);
    assertTrue(entregado.contains("matchmaking"), entregado);
    assertTrue(pendiente.contains("pendiente de entrega"), pendiente);
    assertTrue(pendiente.contains("al crear la necesidad"), pendiente);
    assertFalse(pendiente.contains("null"), pendiente);
  }

  @Test
  @DisplayName("/paquete de uno que no existe dice que Logística no lo tiene, sin un 404 pelado")
  void paqueteInexistente() {
    logistica
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-99"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    bot.handle(1L, "/paquete 99");

    assertTrue(ultimo().contains("no tiene el paquete <b>paq-99</b>"), ultimo());
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/progreso muestra las insignias ganadas y la misión en curso de un donador")
  void progreso() {
    incentivos
        .expect(requestTo(INCENTIVOS + "/donadores/3/insignias"))
        .andRespond(
            withSuccess(
                "[{\"id\":\"ins-1\",\"nombre\":\"Solidario\",\"descripcion\":\"Primera donación\"}]",
                MediaType.APPLICATION_JSON));
    incentivos
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andRespond(
            withSuccess(
                """
                {"id":"mis-1","nombre":"Mision Solidaria","insigniaID":"ins-2",
                 "categoriaInicio":"OCASIONAL","categoriaFin":"COLABORADOR","tipo":"COMPLETITUD"}""",
                MediaType.APPLICATION_JSON));

    bot.handle(1L, "/progreso 3");

    incentivos.verify();
    String salida = ultimo();
    assertTrue(salida.contains("Solidario"), salida);
    assertTrue(salida.contains("Mision Solidaria"), salida);
    assertTrue(salida.contains("de Ocasional a Colaborador"), salida);
  }

  @Test
  @DisplayName("/progreso de un donador sin misión: el 404 de Incentivos quiere decir que no tiene")
  void progresoSinMision() {
    incentivos
        .expect(requestTo(INCENTIVOS + "/donadores/3/insignias"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    incentivos
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    bot.handle(1L, "/progreso 3");

    String salida = ultimo();
    assertTrue(salida.contains("Todavía no ganó ninguna insignia"), salida);
    assertTrue(salida.contains("Sin misión en curso"), salida);
    assertFalse(salida.contains("404"), salida);
  }

  // ── Donadores y entidades ──────────────────────────────────────────────────

  @Test
  @DisplayName("/necesidades recorre los productos y agrupa por producto las que siguen pendientes")
  void necesidadesPendientes() {
    when(donaciones.listarProductos())
        .thenReturn(
            """
            [{"id":"3","nombre":"Arroz"},{"id":"4","nombre":"Fideos"}]""");
    when(donadores.necesidadesDeProducto("3"))
        .thenReturn(
            """
            [{"id":"7","entidadID":"4","nivelDeUrgencia":8,"descripcion":"Arroz para el comedor",
              "cantidadObjetivo":20,"cantidadActual":5,"productoSolicitadoID":"3",
              "tipo":"EXTRAORDINARIA"}]""");
    when(donadores.necesidadesDeProducto("4")).thenReturn("[]");

    bot.handle(1L, "/necesidades");

    String salida = ultimo();
    assertTrue(salida.contains("Arroz"), salida);
    assertTrue(salida.contains("nº 7"), salida);
    assertTrue(salida.contains("5/20"), salida);
    assertTrue(salida.contains("faltan 15"), salida);
    assertFalse(salida.contains("Fideos"), "sin necesidades pendientes no tiene por qué aparecer");
  }

  @Test
  @DisplayName("/necesidades, si Donadores no contesta, lo dice en vez de mostrar que no hay ninguna")
  void necesidadesSinDonadores() {
    when(donaciones.listarProductos()).thenReturn("[{\"id\":\"3\",\"nombre\":\"Arroz\"}]");
    when(donadores.necesidadesDeProducto("3")).thenThrow(new RuntimeException("no responde"));

    bot.handle(1L, "/necesidades");

    String salida = ultimo();
    assertTrue(salida.contains("Donadores no contestó"), salida);
    assertFalse(salida.contains("No hay necesidades pendientes"), "eso no se pudo ver: " + salida);
  }

  @Test
  @DisplayName("El donador también puede ver qué piden las entidades")
  void necesidadesParaElDonador() {
    when(donaciones.listarProductos()).thenReturn("[]");

    bot.handle(2L, "/soy_donador");
    bot.handle(2L, "/necesidades");

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(telegram, atLeastOnce()).sendMessage(eq(2L), captor.capture());
    assertFalse(captor.getValue().contains("administradores"), captor.getValue());
  }

  @Test
  @DisplayName("/necesidad muestra cuánto le falta")
  void necesidad() {
    when(donadores.buscarNecesidad("7"))
        .thenReturn(
            """
            {"id":"7","entidadID":"4","nivelDeUrgencia":8,"descripcion":"Arroz",
             "cantidadObjetivo":20,"cantidadActual":5,"productoSolicitadoID":"3",
             "tipo":"EXTRAORDINARIA"}""");

    bot.handle(1L, "/necesidad 7");

    assertTrue(ultimo().contains("Faltan <b>15</b>"), ultimo());
  }

  @Test
  @DisplayName("/donador muestra la ficha, si puede donar y sus quejas")
  void donadorCompleto() {
    when(donadores.buscarDonador("3"))
        .thenReturn(
            """
            {"id":"3","nombre":"Ana","apellido":"Gomez","estado":"SOSPECHOSO",
             "categoria":"OCASIONAL"}""");
    when(donadores.puedeDonar("3")).thenReturn("{\"puedeDonar\":true}");
    when(donadores.quejasDe("3"))
        .thenReturn(
            """
            [{"id":"1","donacionID":"12","donadorID":"3","fecha":"2026-10-02",
              "descripcion":"Llegó roto"}]""");

    bot.handle(1L, "/donador 3");

    String salida = ultimo();
    assertTrue(salida.contains("Ana Gomez"), salida);
    assertTrue(salida.contains("Puede donar"), salida);
    assertTrue(salida.contains("Quejas (1)"), salida);
    assertTrue(salida.contains("donación nº 12"), salida);
    assertTrue(salida.contains("Llegó roto"), salida);
  }

  @Test
  @DisplayName("/donador sin número pregunta cuál, y con número va directo")
  void sinNumeroPregunta() {
    when(donadores.listarDonadores()).thenReturn("[{\"id\":\"3\",\"nombre\":\"Ana\"}]");
    when(donadores.buscarDonador("3")).thenReturn("{\"id\":\"3\",\"nombre\":\"Ana\"}");

    bot.handle(1L, "/donador");
    String pregunta = ultimo();
    bot.handle(1L, "3");

    assertTrue(pregunta.contains("¿Qué número de donador?"), pregunta);
    assertTrue(pregunta.contains("Ana"), "con la lista para no tener que irse a buscarlo");
    assertFalse(pregunta.contains("1 de 1"), "con una sola pregunta, contarlas sobra");
    verify(donadores).buscarDonador("3");
  }

  // ── Donaciones ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/donaciones pregunta si son todas o las de un donador")
  void donacionesDeTodosODeUno() {
    when(donaciones.listarDonaciones())
        .thenReturn(
            """
            [{"id":"12","donadorID":"3","productoID":"3","cantidad":10,"estado":"ACEPTADA"}]""");
    when(donaciones.misDonaciones("3"))
        .thenReturn(
            """
            [{"id":"12","donadorID":"3","productoID":"3","cantidad":10,"estado":"ACEPTADA"}]""");

    bot.handle(1L, "/donaciones");
    String pregunta = ultimo();
    bot.handle(1L, "todas");
    String todas = ultimo();
    bot.handle(1L, "/donaciones 3");
    String deUno = ultimo();

    assertTrue(pregunta.contains("¿De todos o de un donador?"), pregunta);
    assertTrue(todas.contains("Todas las donaciones"), todas);
    assertTrue(todas.contains("donador nº 3"), "en el listado general importa de quién es");
    assertTrue(deUno.contains("Donaciones del donador nº 3"), deUno);
  }

  @Test
  @DisplayName("/donacion muestra la donación con su estado actual, sin prometer un historial")
  void donacion() {
    when(donaciones.buscarDonacion("12"))
        .thenReturn(
            """
            {"id":"12","donadorID":"3","depositoID":"DEP-UTN-01","descripcion":"Diez kilos",
             "productoID":"3","cantidad":10,"estado":"CONQUEJA"}""");

    bot.handle(1L, "/donacion 12");

    String salida = ultimo();
    assertTrue(salida.contains("Donación nº 12"), salida);
    assertTrue(salida.contains("Con queja"), salida);
    assertTrue(salida.contains("Donador nº 3"), salida);
    assertTrue(salida.contains("/paquete 12"), "el destino lo sabe Logística: se dice dónde verlo");
    assertFalse(salida.toLowerCase().contains("historial"), salida);
  }

  @Test
  @DisplayName("/identificadores muestra el catálogo formateado")
  void identificadores() {
    when(donaciones.listarIdentificadores())
        .thenReturn(
            """
            [{"id":"1","tipo":"CODIGODEBARRAS","descripcion":"EAN del arroz"},
             {"id":"2","tipo":"QR","descripcion":"Etiqueta del frasco"}]""");

    bot.handle(1L, "/identificadores");

    String salida = ultimo();
    assertTrue(salida.contains("nº 1 · Código de barras · EAN del arroz"), salida);
    assertTrue(salida.contains("nº 2 · QR · Etiqueta del frasco"), salida);
    assertFalse(salida.contains("{"), salida);
  }

  // ── Quién puede ver qué ────────────────────────────────────────────────────

  @Test
  @DisplayName("Los datos personales de los donadores son solo para el admin")
  void datosPersonalesSoloAdmin() {
    bot.handle(2L, "/soy_donador");
    bot.handle(2L, "/donadores");
    bot.handle(2L, "/donador 1");
    bot.handle(2L, "/estadisticas 1");
    bot.handle(2L, "/donaciones");
    bot.handle(2L, "/donacion 1");
    bot.handle(2L, "/progreso 1");

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(telegram, atLeastOnce()).sendMessage(eq(2L), captor.capture());
    List<String> respuestas = captor.getAllValues().subList(1, captor.getAllValues().size());
    assertTrue(respuestas.size() == 6, respuestas.toString());
    respuestas.forEach(r -> assertTrue(r.contains("administradores"), r));
    verify(donadores, never()).listarDonadores();
    verify(donadores, never()).buscarDonador("1");
    verify(telegram, never()).deleteMessage(eq(2L), anyLong());
  }

  private String ultimo() {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(telegram, atLeastOnce()).sendMessage(eq(1L), captor.capture());
    return captor.getValue();
  }
}
