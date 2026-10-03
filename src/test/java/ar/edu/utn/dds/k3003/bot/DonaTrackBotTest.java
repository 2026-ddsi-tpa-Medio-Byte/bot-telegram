package ar.edu.utn.dds.k3003.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests del despacho de comandos y del manejo de sesión (Telegram y APIs mockeados). */
@ExtendWith(MockitoExtension.class)
class DonaTrackBotTest {

  private static final String ANA =
      """
      {"id":"1","nombre":"Ana","apellido":"Gomez","edad":30,"email":"ana@mail.com",
       "nroDocumento":"40100001","domicilio":"Calle 1","estado":"VERIFICADO"}""";

  @Mock private TelegramClient telegram;
  @Mock private DonadoresApiClient api;
  @Mock private DonacionesApiClient donaciones;
  @Mock private LogisticaApiClient logistica;
  @Mock private IncentivosApiClient incentivos;

  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    // Impacto y Demo van reales, con los clientes mockeados: así se ejercita también el camino en
    // el que un módulo no contesta, que es el que más se va a dar en la demostración.
    Impacto impacto = new Impacto(donaciones, api, logistica, incentivos);
    Demo demo = new Demo(donaciones, api, logistica, incentivos, "DEP-TEST");
    Formularios formularios =
        new Formularios(api, donaciones, logistica, incentivos, impacto, "DEP-TEST");
    bot =
        new DonaTrackBot(
            telegram, api, donaciones, logistica, incentivos, impacto, demo, formularios,
            "DEP-TEST");
  }

  // ── Entrada ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/start ofrece elegir rol")
  void start() {
    bot.handle(1L, "/start");
    verify(telegram).sendMessage(eq(1L), contains("/soy_donador"));
  }

  @Test
  @DisplayName("/soy_donador pregunta si ya está registrado, no tira el menú completo")
  void puertaDelDonador() {
    bot.handle(1L, "/soy_donador");
    verify(telegram).sendMessage(eq(1L), contains("/entrar"));
  }

  @Test
  @DisplayName("/entrar identifica al donador y lo saluda por su nombre")
  void entrar() {
    when(api.buscarDonador("1")).thenReturn(ANA);

    bot.handle(1L, "/entrar 1");

    verify(telegram).sendMessage(eq(1L), contains("Hola <b>Ana</b>"));
  }

  @Test
  @DisplayName("Al registrarse queda identificado solo, sin tener que entrar después")
  void registrarseIdentifica() {
    when(api.registrarDonadorRaw("Juan", "Perez", 30, "j@x.com", "123", "Calle 5"))
        .thenReturn("{\"id\":\"7\",\"nombre\":\"Juan\"}");

    bot.handle(1L, "/registrarse Juan;Perez;30;j@x.com;123;Calle 5");

    verify(telegram).sendMessage(eq(1L), contains("número <b>7</b>"));
  }

  // ── Conversaciones guiadas ─────────────────────────────────────────────────

  @Test
  @DisplayName("/registrarse sin datos pregunta de a uno y al final registra")
  void registroGuiado() {
    when(api.registrarDonadorRaw("Juan", "Perez", 30, "j@x.com", "40123456", "Calle 5"))
        .thenReturn("{\"id\":\"7\",\"nombre\":\"Juan\"}");

    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/registrarse");
    verify(telegram).sendMessage(eq(1L), contains("¿Cómo te llamás?"));

    bot.handle(1L, "Juan");
    bot.handle(1L, "Perez");
    bot.handle(1L, "30");
    bot.handle(1L, "j@x.com");
    bot.handle(1L, "40123456");
    bot.handle(1L, "Calle 5");

    verify(api).registrarDonadorRaw("Juan", "Perez", 30, "j@x.com", "40123456", "Calle 5");
    verify(telegram).sendMessage(eq(1L), contains("número <b>7</b>"));

    // Y quedó identificado, igual que con el comando de una sola línea.
    bot.handle(1L, "/perfil");
    verify(api).buscarDonador("7");
  }

  @Test
  @DisplayName("Un dato con la forma equivocada se vuelve a pedir y no corta el registro")
  void registroConUnDatoMal() {
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/registrarse");
    bot.handle(1L, "Juan");
    bot.handle(1L, "Perez");

    bot.handle(1L, "treinta y pico");

    verify(telegram).sendMessage(eq(1L), contains("número entero"));
    verify(api, never())
        .registrarDonadorRaw(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("/cancelar deja el formulario a medio hacer sin ejecutar nada")
  void cancelarUnFormulario() {
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/registrarse");
    bot.handle(1L, "Juan");

    bot.handle(1L, "/cancelar");

    verify(telegram).sendMessage(eq(1L), contains("lo dejamos acá"));
    verify(api, never())
        .registrarDonadorRaw(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("Mandar otro comando en el medio abandona el formulario y atiende el comando")
  void otroComandoEnElMedio() {
    when(donaciones.listarProductos()).thenReturn("[]");
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/registrarse");
    bot.handle(1L, "Juan");

    bot.handle(1L, "/productos");

    verify(donaciones).listarProductos();

    // Lo que escriba después ya no es una respuesta al formulario abandonado: con el rol ya
    // elegido, un texto suelto devuelve el menú de ese rol en vez de seguir preguntando.
    bot.handle(1L, "Perez");

    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(telegram, org.mockito.Mockito.atLeastOnce()).sendMessage(eq(1L), captor.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        captor.getValue().contains("/entrar"),
        "esperaba el menú del donador, no otra pregunta del formulario abandonado");
  }

  @Test
  @DisplayName("/donar guiado dona a nombre de quien está en la sesión")
  void donarGuiado() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    when(donaciones.donar("1", "DEP-TEST", "Diez kilos de arroz", "3", 10))
        .thenReturn("{\"id\":\"9\",\"cantidad\":10,\"estado\":\"INGRESADA\"}");
    bot.handle(1L, "/entrar 1");

    bot.handle(1L, "/donar");
    bot.handle(1L, "3");
    bot.handle(1L, "10");
    bot.handle(1L, "Diez kilos de arroz");

    verify(donaciones).donar("1", "DEP-TEST", "Diez kilos de arroz", "3", 10);
  }

  @Test
  @DisplayName("La necesidad guiada manda cada dato en su lugar, aunque se pregunten en otro orden")
  void necesidadGuiada() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/altanecesidad");
    bot.handle(1L, "1"); // entidad
    bot.handle(1L, "3"); // producto
    bot.handle(1L, "20"); // cantidad objetivo
    bot.handle(1L, "Arroz para el comedor"); // descripción
    bot.handle(1L, "8"); // urgencia
    bot.handle(1L, "extraordinaria"); // tipo

    verify(api).altaNecesidadRaw("1", 8, "Arroz para el comedor", 20, "3", "EXTRAORDINARIA");
  }

  // ── Sesión ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Sin haber entrado, /misdonaciones pide entrar primero")
  void sinEntrarNoHayDatos() {
    bot.handle(1L, "/misdonaciones");

    verify(donaciones, never()).misDonaciones(anyString());
    verify(telegram).sendMessage(eq(1L), contains("Primero entrá"));
  }

  @Test
  @DisplayName("Una vez adentro, el bot recuerda quién sos y no hay que repetir el número")
  void recuerdaQuienSos() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    bot.handle(1L, "/entrar 1");

    when(donaciones.misDonaciones("1")).thenReturn("[]");
    bot.handle(1L, "/misdonaciones");

    verify(donaciones).misDonaciones("1");
  }

  @Test
  @DisplayName("La sesión es de cada chat: lo que hace uno no afecta al otro")
  void sesionesSeparadas() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    bot.handle(1L, "/entrar 1");

    bot.handle(2L, "/misdonaciones");

    verify(telegram).sendMessage(eq(2L), contains("Primero entrá"));
  }

  @Test
  @DisplayName("/salir borra la identificación")
  void salir() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    bot.handle(1L, "/entrar 1");
    bot.handle(1L, "/salir");

    bot.handle(1L, "/perfil");
    verify(telegram).sendMessage(eq(1L), contains("Primero entrá"));
  }

  // ── Donar ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/donar usa el donador de la sesión y el depósito configurado")
  void donar() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    bot.handle(1L, "/entrar 1");

    when(donaciones.donar("1", "DEP-TEST", "Diez kilos de arroz", "3", 10))
        .thenReturn("{\"id\":\"9\",\"cantidad\":10,\"productoID\":\"3\",\"estado\":\"INGRESADA\"}");

    bot.handle(1L, "/donar 3;10;Diez kilos de arroz");

    verify(donaciones).donar("1", "DEP-TEST", "Diez kilos de arroz", "3", 10);
    verify(telegram).sendMessage(eq(1L), contains("Donación registrada"));
  }

  @Test
  @DisplayName("No se puede donar sin haber entrado")
  void donarSinEntrar() {
    bot.handle(1L, "/donar 1;5;algo");

    verify(donaciones, never()).donar(any(), any(), any(), any(), anyInt());
  }

  // ── Admin ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Un donador no puede crear entidades")
  void donadorNoEsAdmin() {
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/crearentidad Comedor;Calle 1;123;c@mail.com");

    verify(api, never()).crearEntidadRaw(any(), any(), any(), any());
    verify(telegram).sendMessage(eq(1L), contains("administradores"));
  }

  @Test
  @DisplayName("El admin sí puede, y ve la entidad formateada")
  void adminCreaEntidad() {
    bot.handle(1L, "/soy_admin");
    when(api.crearEntidadRaw("Comedor", "Calle 1", "123", "c@mail.com"))
        .thenReturn(
            "{\"id\":\"4\",\"razonSocial\":\"Comedor\",\"domicilio\":\"Calle 1\","
                + "\"telefono\":\"123\",\"correo\":\"c@mail.com\"}");

    bot.handle(1L, "/crearentidad Comedor;Calle 1;123;c@mail.com");

    verify(telegram).sendMessage(eq(1L), contains("Entidad creada"));
  }

  @Test
  @DisplayName("El admin puede cambiarle el estado a un donador")
  void adminCambiaEstado() {
    bot.handle(1L, "/soy_admin");
    when(api.cambiarEstadoDonador("2", "BANEADO")).thenReturn(ANA);

    bot.handle(1L, "/estadodonador 2;baneado");

    verify(api).cambiarEstadoDonador("2", "BANEADO");
  }

  @Test
  @DisplayName("/altanecesidad normaliza el tipo a mayúsculas")
  void altaNecesidad() {
    bot.handle(1L, "/soy_admin");
    when(api.altaNecesidadRaw("5", 3, "sillas", 30, "prod1", "EXTRAORDINARIA"))
        .thenReturn("{\"id\":\"1\",\"cantidadObjetivo\":30,\"cantidadActual\":0}");

    bot.handle(1L, "/altanecesidad 5;3;sillas;30;prod1;extraordinaria");

    verify(api).altaNecesidadRaw("5", 3, "sillas", 30, "prod1", "EXTRAORDINARIA");
  }

  // ── Errores ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Con campos de menos avisa cuántos faltan y no llama a la API")
  void faltanCampos() {
    bot.handle(1L, "/registrarse Juan;Perez");

    verify(api, never()).registrarDonadorRaw(any(), any(), anyInt(), any(), any(), any());
    verify(telegram).sendMessage(eq(1L), contains("Se esperaban 6"));
  }

  @Test
  @DisplayName("Comando desconocido manda a /help")
  void desconocido() {
    bot.handle(1L, "/cualquiercosa");
    verify(telegram).sendMessage(eq(1L), contains("/help"));
  }

  @Test
  @DisplayName("Un texto suelto sin rol elegido ofrece elegir entre donador y admin")
  void textoSueltoSinRol() {
    // Quien abre el chat por primera vez no tiene por qué saber que el primer mensaje
    // tiene que ser /start: cualquier cosa que escriba lo tiene que llevar a elegir rol.
    bot.handle(1L, "hola");

    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(telegram).sendMessage(eq(1L), captor.capture());

    String respuesta = captor.getValue();
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("/soy_donador"), "tiene que ofrecer entrar como donador");
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("/soy_admin"), "tiene que ofrecer entrar como admin");
  }

  @Test
  @DisplayName("Un texto suelto con el rol ya elegido devuelve el menú de ese rol, no la bienvenida")
  void textoSueltoConRolElegido() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "hola");

    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(telegram, org.mockito.Mockito.times(2)).sendMessage(eq(1L), captor.capture());

    String respuesta = captor.getValue();
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("Modo administrador"), "esperaba el menú del rol que ya eligió");
    org.junit.jupiter.api.Assertions.assertFalse(
        respuesta.contains("¿Cómo querés entrar?"), "ya eligió rol: volver a la bienvenida es un paso atrás");
  }

  @Test
  @DisplayName("Un comando desconocido con el rol ya elegido sigue mandando a /help")
  void desconocidoConRolElegido() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/donarr");

    verify(telegram).sendMessage(eq(1L), contains("No conozco ese comando"));
  }

  @Test
  @DisplayName("Un comando desconocido sin rol elegido ofrece los dos roles y además nombra /help")
  void desconocidoSinRol() {
    bot.handle(1L, "/cualquiercosa");

    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(telegram).sendMessage(eq(1L), captor.capture());

    String respuesta = captor.getValue();
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("/soy_donador"), "sin rol elegido, el error también tiene que ofrecer entrar");
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("/soy_admin"), "sin rol elegido, el error también tiene que ofrecer entrar");
    org.junit.jupiter.api.Assertions.assertTrue(
        respuesta.contains("/help"), "el comando erró: tiene que decir dónde está la lista");
  }

  @Test
  @DisplayName("Los menús no usan asteriscos: en Markdown el guion bajo comía /soy_donador")
  void sinMarkdownEnLosMenus() {
    // Con parse_mode Markdown, el _ de /soy_donador abría cursiva y Telegram mostraba
    // «/soydonador» sin el guion. El usuario copiaba eso y el bot no lo reconocía.
    org.mockito.ArgumentCaptor<String> captor =
        org.mockito.ArgumentCaptor.forClass(String.class);

    bot.handle(1L, "/start");
    bot.handle(1L, "/soy_donador");
    bot.handle(2L, "/soy_admin");

    verify(telegram, org.mockito.Mockito.atLeast(3)).sendMessage(anyLong(), captor.capture());

    for (String mensaje : captor.getAllValues()) {
      org.junit.jupiter.api.Assertions.assertFalse(
          mensaje.contains("*"),
          "ningún mensaje puede llevar asteriscos de Markdown, rompen los comandos con _");
    }
  }

  @Test
  @DisplayName("Los comandos con guion bajo llegan enteros al usuario")
  void comandosConGuionBajoIntactos() {
    org.mockito.ArgumentCaptor<String> captor =
        org.mockito.ArgumentCaptor.forClass(String.class);

    bot.handle(1L, "/start");

    verify(telegram).sendMessage(anyLong(), captor.capture());
    String bienvenida = captor.getValue();

    org.junit.jupiter.api.Assertions.assertTrue(bienvenida.contains("/soy_donador"));
    org.junit.jupiter.api.Assertions.assertTrue(bienvenida.contains("/soy_admin"));
  }

  // ── Logística ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/depositos consulta los depósitos de Logística")
  void listarDepositos() {
    when(logistica.listarDepositos()).thenReturn("[{\"id\":\"DEP-1\",\"direccion\":\"Calle 1\",\"capacidadMaxima\":100}]");

    bot.handle(1L, "/depositos");

    verify(logistica).listarDepositos();
    verify(telegram).sendMessage(eq(1L), contains("DEP-1"));
  }

  @Test
  @DisplayName("/stock consulta el stock de un producto puntual")
  void consultarStock() {
    when(logistica.consultarStock("5")).thenReturn("{\"disponible\":20}");

    bot.handle(1L, "/stock 5");

    verify(logistica).consultarStock("5");
    verify(telegram).sendMessage(eq(1L), contains("20"));
  }

  @Test
  @DisplayName("Un donador no puede reportar entregas en Logística")
  void reportarEntregaRequiereAdmin() {
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/reportarentrega PAQ-1;DON-1;PROD-1;10");

    verify(logistica, never()).reportarEntrega(any(), any(), any(), anyInt());
    verify(telegram).sendMessage(eq(1L), contains("administradores"));
  }

  @Test
  @DisplayName("El admin sí puede reportar entregas en Logística")
  void reportarEntregaAdmin() {
    bot.handle(1L, "/soy_admin");
    when(logistica.reportarEntrega("paq-DON-1", "DON-1", "PROD-1", 10)).thenReturn("OK");

    bot.handle(1L, "/reportarentrega DON-1;PROD-1;10");

    // El paquete no se pide: Logística lo nombra "paq-" + el id de la donación.
    verify(logistica).reportarEntrega("paq-DON-1", "DON-1", "PROD-1", 10);
    verify(telegram).sendMessage(eq(1L), contains("Entrega reportada"));
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/insignias consulta el catálogo de insignias de Incentivos")
  void listarInsignias() {
    when(incentivos.listarInsignias()).thenReturn("[{\"id\":\"ins-1\",\"nombre\":\"Solidario\",\"descripcion\":\"Dono 10 veces\"}]");

    bot.handle(1L, "/insignias");

    verify(incentivos).listarInsignias();
    verify(telegram).sendMessage(eq(1L), contains("Solidario"));
  }

  @Test
  @DisplayName("/misiones consulta las misiones activas de Incentivos")
  void listarMisiones() {
    when(incentivos.listarMisiones()).thenReturn("[{\"id\":\"mis-1\",\"nombre\":\"Mision 1\",\"insigniaID\":\"ins-1\",\"categoriaInicio\":\"A\",\"categoriaFin\":\"B\"}]");

    bot.handle(1L, "/misiones");

    verify(incentivos).listarMisiones();
    verify(telegram).sendMessage(eq(1L), contains("Mision 1"));
  }

  @Test
  @DisplayName("/procesardonador requiere ser admin")
  void procesarDonadorRequiereAdmin() {
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/procesardonador 1");

    verify(incentivos, never()).procesarDonador(anyString());
    verify(telegram).sendMessage(eq(1L), contains("administradores"));
  }

  @Test
  @DisplayName("El admin puede procesar a un donador en Incentivos")
  void procesarDonadorAdmin() {
    bot.handle(1L, "/soy_admin");
    when(incentivos.procesarDonador("1")).thenReturn("OK");

    bot.handle(1L, "/procesardonador 1");

    verify(incentivos).procesarDonador("1");
    verify(telegram).sendMessage(eq(1L), contains("procesado en Incentivos"));
  }

  // ── Demostración ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("/demo explica en qué orden mostrar los flujos")
  void guionDeLaDemo() {
    bot.handle(1L, "/demo");
    verify(telegram).sendMessage(eq(1L), contains("/preparar"));
  }

  @Test
  @DisplayName("El menú, el guion y la queja guiada avisan que la queja es sobre una donación entregada")
  void quejaSobreDonacionEntregada() {
    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, "/demo");
    bot.handle(1L, "/quejar");

    verify(telegram, org.mockito.Mockito.times(3)).sendMessage(eq(1L), captor.capture());
    String menu = captor.getAllValues().get(0);
    String guion = captor.getAllValues().get(1);
    String pregunta = captor.getAllValues().get(2);

    org.junit.jupiter.api.Assertions.assertTrue(
        menu.contains("/quejar — reclamar por una donación ya entregada"), menu);
    org.junit.jupiter.api.Assertions.assertTrue(
        guion.indexOf("/reportarentrega") < guion.indexOf("/quejar"),
        "la queja necesita una donación ya entregada: el guion tiene que entregarla antes");
    org.junit.jupiter.api.Assertions.assertTrue(guion.contains("donación entregada"));
    org.junit.jupiter.api.Assertions.assertTrue(
        pregunta.contains("Tiene que estar entregada"),
        "mejor saberlo antes de escribir la queja que enterarse del rechazo al final");
    for (String mensaje : captor.getAllValues()) {
      org.junit.jupiter.api.Assertions.assertFalse(
          mensaje.contains("*"), "sin asteriscos de Markdown: rompen los comandos con _");
    }
  }

  @Test
  @DisplayName("/despertar consulta los cuatro módulos para sacarlos del sueño de Render")
  void despertarModulos() {
    bot.handle(1L, "/despertar");

    verify(donaciones).listarProductos();
    verify(api).listarDonadores();
    verify(logistica).listarDepositos();
    verify(incentivos).listarInsignias();
  }

  @Test
  @DisplayName("/estado resume los cuatro módulos")
  void estadoDelSistema() {
    bot.handle(1L, "/estado");
    verify(telegram).sendMessage(eq(1L), contains("Incentivos"));
  }

  @Test
  @DisplayName("Borrar las bases requiere ser admin")
  void reiniciarRequiereAdmin() {
    bot.handle(1L, "/reiniciar");

    verify(donaciones, never()).reset();
    verify(telegram).sendMessage(eq(1L), contains("admin"));
  }

  @Test
  @DisplayName("Reiniciar borra los cuatro módulos")
  void reiniciarBorraTodo() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/reiniciar");

    verify(donaciones).reset();
    verify(api).reset();
    verify(logistica).limpiarBase();
    verify(incentivos).limpiar();
  }

  @Test
  @DisplayName("Si un módulo no despertó, preparar no carga datos a medias")
  void prepararNoCargaSiFaltaUnModulo() {
    bot.handle(1L, "/soy_admin");
    when(donaciones.listarProductos()).thenThrow(new RuntimeException("no responde"));

    bot.handle(1L, "/preparar");

    verify(donaciones, never()).crearIdentificador(anyString(), anyString());
    verify(telegram).sendMessage(eq(1L), contains("No se cargó nada"));
  }

  @Test
  @DisplayName("Preparar la demo requiere ser admin")
  void prepararRequiereAdmin() {
    bot.handle(1L, "/preparar");

    verify(donaciones, never()).crearIdentificador(anyString(), anyString());
    verify(telegram).sendMessage(eq(1L), contains("admin"));
  }

  @Test
  @DisplayName("El admin puede donar a nombre de un donador, sin cambiar de rol")
  void donarComoAdmin() {
    bot.handle(1L, "/soy_admin");
    when(donaciones.donar("5", "DEP-TEST", "Diez kilos", "3", 10))
        .thenReturn("{\"id\":\"9\",\"cantidad\":10,\"estado\":\"INGRESADA\"}");

    bot.handle(1L, "/donarcomo 5;3;10;Diez kilos");

    verify(donaciones).donar("5", "DEP-TEST", "Diez kilos", "3", 10);
    verify(telegram).sendMessage(eq(1L), contains("Donación registrada"));
  }

  @Test
  @DisplayName("Donar a nombre de otro es solo para el admin")
  void donarComoRequiereAdmin() {
    bot.handle(1L, "/donarcomo 5;3;10;Diez kilos");

    verify(donaciones, never()).donar(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  // ── ABM que faltaba de Logística, Incentivos y el catálogo ─────────────────

  @Test
  @DisplayName("El admin configura el algoritmo de matchmaking de un depósito")
  void configurarAlgoritmo() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/algoritmo DEP-UTN-01;prioridad_por_score");

    // El nombre con guion bajo es el de /depositos; el algoritmo se configura por /api, que solo
    // acepta PRIOSCORE. AlgoritmoTest mira lo que viaja en la URL.
    verify(logistica).configurarAlgoritmo("DEP-UTN-01", "PRIOSCORE");
    verify(telegram).sendMessage(eq(1L), contains("ahora asigna con"));
  }

  @Test
  @DisplayName("/categoriadonador guiado ofrece las categorías y se elige con el número o con el nombre")
  void categoriaGuiada() {
    when(api.cambiarCategoriaDonador(anyString(), anyString()))
        .thenReturn(
            """
            {"id":"1","nombre":"Ana","apellido":"Gomez","estado":"VERIFICADO",
             "categoria":"TRANSFORMADOR"}""");
    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/categoriadonador");
    bot.handle(1L, "1");
    bot.handle(1L, "3");
    bot.handle(1L, "/categoriadonador");
    bot.handle(1L, "1");
    bot.handle(1L, "colaborador");

    verify(api).cambiarCategoriaDonador("1", "TRANSFORMADOR");
    verify(api).cambiarCategoriaDonador("1", "COLABORADOR");
    verify(telegram, org.mockito.Mockito.atLeastOnce()).sendMessage(eq(1L), captor.capture());
    String pregunta =
        captor.getAllValues().stream().filter(m -> m.contains("¿Qué categoría")).findFirst().orElse("");
    org.junit.jupiter.api.Assertions.assertTrue(pregunta.contains("1 · Ocasional"), pregunta);
    org.junit.jupiter.api.Assertions.assertTrue(pregunta.contains("5 · Revolucionario"), pregunta);
    String resultado = captor.getValue();
    org.junit.jupiter.api.Assertions.assertTrue(resultado.contains("Categoría cambiada"), resultado);
    org.junit.jupiter.api.Assertions.assertTrue(
        resultado.contains("Categoría: TRANSFORMADOR"), "tiene que verse la categoría que quedó");
  }

  @Test
  @DisplayName("Donadores acepta la categoría como texto libre: una que no está en la lista se manda igual")
  void categoriaFueraDeLaLista() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/categoriadonador");
    bot.handle(1L, "1");
    bot.handle(1L, "Leyenda");

    verify(api).cambiarCategoriaDonador("1", "Leyenda");
  }

  @Test
  @DisplayName("/crearidentificador guiado pregunta el tipo y muestra el identificador creado, no el JSON")
  void identificadorGuiado() {
    when(donaciones.crearIdentificador("CODIGODEBARRAS", "Código EAN del arroz"))
        .thenReturn(
            "{\"id\":\"11\",\"tipo\":\"CODIGODEBARRAS\",\"descripcion\":\"Código EAN del arroz\"}");
    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/crearidentificador");
    bot.handle(1L, "3");
    bot.handle(1L, "1");
    bot.handle(1L, "Código EAN del arroz");

    verify(donaciones).crearIdentificador("CODIGODEBARRAS", "Código EAN del arroz");
    verify(telegram, org.mockito.Mockito.atLeastOnce()).sendMessage(eq(1L), captor.capture());
    String pregunta =
        captor.getAllValues().stream().filter(m -> m.contains("¿De qué tipo")).findFirst().orElse("");
    org.junit.jupiter.api.Assertions.assertTrue(pregunta.contains("1 · Código de barras"), pregunta);
    org.junit.jupiter.api.Assertions.assertTrue(pregunta.contains("2 · QR"), pregunta);
    org.junit.jupiter.api.Assertions.assertTrue(
        captor.getAllValues().stream().anyMatch(m -> m.contains("del 1 al 2")),
        "un 3 no es ninguno de los dos tipos: se vuelve a preguntar");
    String resultado = captor.getValue();
    org.junit.jupiter.api.Assertions.assertTrue(resultado.contains("Identificador nº 11"), resultado);
    org.junit.jupiter.api.Assertions.assertTrue(resultado.contains("3 o más palabras"), resultado);
    org.junit.jupiter.api.Assertions.assertFalse(resultado.contains("{"), "nada del JSON crudo");
  }

  @Test
  @DisplayName("Los atajos en una línea de las altas nuevas siguen andando")
  void atajosEnUnaLinea() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/categoriadonador 2;salvador");
    bot.handle(1L, "/crearidentificador qr;Etiqueta del frasco");

    verify(api).cambiarCategoriaDonador("2", "SALVADOR");
    verify(donaciones).crearIdentificador("QR", "Etiqueta del frasco");
  }

  @Test
  @DisplayName("Ningún menú, el guion ni el catálogo le piden al usuario datos separados por punto y coma")
  void sinPuntoYComaEnLoQueSeMuestra() {
    when(api.buscarDonador("1")).thenReturn(ANA);
    when(donaciones.listarProductos())
        .thenReturn("[{\"id\":\"3\",\"nombre\":\"Arroz\",\"descripcion\":\"Arroz blanco\"}]");
    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);

    bot.handle(1L, "/start");
    bot.handle(1L, "/soy_donador");
    bot.handle(1L, "/entrar 1");
    bot.handle(1L, "/productos");
    bot.handle(2L, "/soy_admin");
    bot.handle(2L, "/demo");

    verify(telegram, org.mockito.Mockito.atLeast(6)).sendMessage(anyLong(), captor.capture());
    // Un comando seguido de datos pegados con punto y coma, como «/donar 3;10;arroz». Un punto y
    // coma en la prosa («se duermen; hacelo...») no le pide nada al usuario.
    java.util.regex.Pattern atajo = java.util.regex.Pattern.compile("/[a-z_]+ \\S*;");
    for (String mensaje : captor.getAllValues()) {
      org.junit.jupiter.api.Assertions.assertFalse(
          atajo.matcher(mensaje).find(), "las altas son guiadas, el atajo no se enseña: " + mensaje);
    }
    String menuAdmin =
        captor.getAllValues().stream()
            .filter(m -> m.contains("Modo administrador"))
            .findFirst()
            .orElse("");
    org.junit.jupiter.api.Assertions.assertTrue(menuAdmin.contains("/categoriadonador —"), menuAdmin);
    org.junit.jupiter.api.Assertions.assertTrue(menuAdmin.contains("/algoritmo —"), menuAdmin);
    org.junit.jupiter.api.Assertions.assertTrue(menuAdmin.contains("/crearidentificador —"), menuAdmin);
  }

  @Test
  @DisplayName("El admin da de alta un depósito en Logística")
  void crearDeposito() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/creardeposito DEP-2;Sucursal Norte;Calle 1;500");

    verify(logistica).crearDeposito("DEP-2", "Sucursal Norte", "Calle 1", 500, "SUB_ATENDIDOS");
  }

  @Test
  @DisplayName("El admin da de alta productos e identificadores en Donaciones")
  void crearProducto() {
    bot.handle(1L, "/soy_admin");
    when(donaciones.crearProducto("Arroz", "Arroz blanco largo fino", "alimentos", "1"))
        .thenReturn("{\"id\":\"5\"}");

    bot.handle(1L, "/crearproducto Arroz;Arroz blanco largo fino;alimentos;1");

    verify(donaciones).crearProducto("Arroz", "Arroz blanco largo fino", "alimentos", "1");
  }

  @Test
  @DisplayName("El admin da de alta insignias y misiones en Incentivos")
  void crearInsigniaYMision() {
    bot.handle(1L, "/soy_admin");

    bot.handle(1L, "/crearinsignia ins-1;Solidario;Primera donacion");
    bot.handle(1L, "/crearmision mis-1;Mision Solidaria;ins-1;OCASIONAL;COLABORADOR");

    verify(incentivos).crearInsignia("ins-1", "Solidario", "Primera donacion");
    verify(incentivos)
        .crearMision("mis-1", "Mision Solidaria", "ins-1", "OCASIONAL", "COLABORADOR", "COMPLETITUD");
  }
}

