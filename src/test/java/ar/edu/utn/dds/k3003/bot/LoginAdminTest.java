package ar.edu.utn.dds.k3003.bot;

import static ar.edu.utn.dds.k3003.bot.BotDePrueba.CLAVE;
import static ar.edu.utn.dds.k3003.bot.BotDePrueba.MENSAJE_CON_CLAVE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * El ingreso como administrador. Lo que se cuida es que sin la contraseña no se entre de ninguna
 * forma, que adivinarla no sea gratis y que la contraseña no quede escrita en ningún lado.
 */
class LoginAdminTest {

  private TelegramClient telegram;
  private DonadoresApiClient donadores;
  private RelojDePrueba reloj;
  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    telegram = mock(TelegramClient.class);
    donadores = mock(DonadoresApiClient.class);
    reloj = new RelojDePrueba();
    bot = armar(new AccesoAdmin(CLAVE, reloj));
  }

  private DonaTrackBot armar(AccesoAdmin acceso) {
    return BotDePrueba.armar(
        telegram,
        donadores,
        mock(DonacionesApiClient.class),
        mock(LogisticaApiClient.class),
        mock(IncentivosApiClient.class),
        acceso);
  }

  // ── Habilitado o no ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Sin BOT_ADMIN_PASSWORD el modo administrador queda deshabilitado y dice cómo habilitarlo")
  void sinContrasenaConfigurada() {
    bot = armar(new AccesoAdmin("", reloj));

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 7L, "");
    bot.handle(1L, 8L, "cualquier cosa");
    bot.handle(1L, "/crearentidad");

    String deshabilitado = mensajes().get(0);
    assertTrue(deshabilitado.contains("deshabilitado"), deshabilitado);
    assertTrue(deshabilitado.contains("BOT_ADMIN_PASSWORD"), "tiene que decir cómo habilitarlo");
    assertTrue(ultimo().contains("administradores"), "sin contraseña no se entra de ninguna forma");
    verify(telegram, never()).deleteMessage(anyLong(), anyLong());
  }

  // ── Entrar ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("/soy_admin pide la contraseña y avisa que va a borrar el mensaje")
  void pideLaContrasena() {
    bot.handle(1L, "/soy_admin");

    String pedido = ultimo();
    assertTrue(pedido.contains("Acceso de administrador"), pedido);
    assertTrue(pedido.contains("borro tu mensaje"), pedido);
    assertTrue(pedido.contains("/cancelar"), pedido);
    assertFalse(pedido.contains("Modo administrador"), "todavía no entró: no se muestra el menú");
  }

  @Test
  @DisplayName("Con la contraseña correcta entra, borra el mensaje y dice cuánto dura la sesión")
  void entraConLaCorrecta() {
    when(telegram.deleteMessage(1L, MENSAJE_CON_CLAVE)).thenReturn(true);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, MENSAJE_CON_CLAVE, CLAVE);

    verify(telegram).deleteMessage(1L, MENSAJE_CON_CLAVE);
    String bienvenida = ultimo();
    assertTrue(bienvenida.contains("Entraste como administrador"), bienvenida);
    assertTrue(bienvenida.contains("/salir o 2 horas sin usar el bot"), bienvenida);
    assertTrue(bienvenida.contains("Modo administrador"), "junto con el menú");
    assertFalse(bienvenida.contains("borralo"), "el borrado salió bien: no hay nada que pedir");

    bot.handle(1L, "/crearentidad");
    assertTrue(ultimo().contains("Nueva entidad"), "ya es admin: el alta arranca");
  }

  @Test
  @DisplayName("Si no se puede borrar el mensaje, entra igual y pide borrarlo a mano")
  void noSePudoBorrar() {
    when(telegram.deleteMessage(1L, MENSAJE_CON_CLAVE)).thenReturn(false);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, MENSAJE_CON_CLAVE, CLAVE);

    String respuesta = ultimo();
    assertTrue(respuesta.contains("Entraste como administrador"), respuesta);
    assertTrue(respuesta.contains("borralo"), "la contraseña sigue en el chat: hay que decirlo");
  }

  @Test
  @DisplayName("Una contraseña incorrecta no entra, también se borra, y dice cuántos intentos quedan")
  void incorrecta() {
    when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 501L, "no-es");
    assertTrue(ultimo().contains("Contraseña incorrecta. Te quedan 2 intentos."), ultimo());
    bot.handle(1L, 502L, "tampoco");
    assertTrue(ultimo().contains("Contraseña incorrecta. Te queda 1 intento."), ultimo());

    verify(telegram).deleteMessage(1L, 501L);
    verify(telegram).deleteMessage(1L, 502L);
    // Mientras espera la contraseña, lo único que no toma como intento es /cancelar.
    bot.handle(1L, "/cancelar");
    bot.handle(1L, "/crearentidad");
    assertTrue(ultimo().contains("administradores"), ultimo());
  }

  @Test
  @DisplayName("Mientras espera la contraseña, cualquier mensaje es un intento y se borra, aunque parezca un comando")
  void unComandoEsUnIntento() {
    when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 501L, "/start");

    // Si se atendiera como comando, una contraseña que empieza con barra quedaría en el chat.
    verify(telegram).deleteMessage(1L, 501L);
    assertTrue(ultimo().contains("Te quedan 2 intentos"), ultimo());
  }

  @Test
  @DisplayName("Cancelar no le devuelve los intentos al chat: volver a empezar no sirve para seguir probando")
  void cancelarNoReiniciaLosIntentos() {
    when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 501L, "uno");
    bot.handle(1L, 502L, "dos");
    bot.handle(1L, "/cancelar");
    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 503L, "tres");

    assertTrue(ultimo().contains("Demasiados intentos"), ultimo());
  }

  @Test
  @DisplayName("/cancelar durante el ingreso vuelve sin entrar y sin gastar un intento")
  void cancelarElIngreso() {
    bot.handle(1L, "/soy_admin");
    bot.handle(1L, "/cancelar");
    bot.handle(1L, "/crearentidad");

    assertTrue(mensajes().get(1).contains("no entraste"), mensajes().get(1));
    assertTrue(ultimo().contains("administradores"), ultimo());
    verify(telegram, never()).deleteMessage(anyLong(), anyLong());
  }

  // ── Bloqueo ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Con 3 intentos fallidos el chat queda bloqueado 5 minutos, aun con la contraseña correcta")
  void bloqueoPorIntentos() {
    when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);

    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 501L, "uno");
    bot.handle(1L, 502L, "dos");
    bot.handle(1L, 503L, "tres");
    assertTrue(ultimo().contains("Demasiados intentos. Probá de nuevo en 5 minutos."), ultimo());

    // Bloqueado: ni siquiera pide la contraseña, así que la correcta no tiene por dónde entrar.
    reloj.avanzar(Duration.ofMinutes(4));
    bot.handle(1L, "/soy_admin");
    assertTrue(ultimo().contains("Demasiados intentos"), ultimo());
    bot.handle(1L, 504L, CLAVE);
    bot.handle(1L, "/crearentidad");
    assertTrue(ultimo().contains("administradores"), "un mensaje suelto no es un intento: " + ultimo());

    // Pasados los 5 minutos, arranca de cero.
    reloj.avanzar(Duration.ofMinutes(1));
    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 505L, CLAVE);
    assertTrue(ultimo().contains("Entraste como administrador"), ultimo());
  }

  @Test
  @DisplayName("El bloqueo es de ese chat: otro chat puede entrar")
  void bloqueoPorChat() {
    when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);
    bot.handle(1L, "/soy_admin");
    bot.handle(1L, 501L, "uno");
    bot.handle(1L, 502L, "dos");
    bot.handle(1L, 503L, "tres");

    bot.handle(2L, "/soy_admin");
    bot.handle(2L, 601L, CLAVE);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(telegram, atLeastOnce()).sendMessage(eq(2L), captor.capture());
    assertTrue(captor.getValue().contains("Entraste como administrador"), captor.getValue());
  }

  // ── Sesión ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("La sesión vence a las 2 horas sin usar el bot, y usarlo la renueva")
  void vencimiento() {
    BotDePrueba.entrarComoAdmin(bot, telegram, 1L);

    reloj.avanzar(Duration.ofMinutes(119));
    bot.handle(1L, "/crearentidad");
    assertTrue(ultimo().contains("Nueva entidad"), "a los 119 minutos sigue adentro");
    bot.handle(1L, "/cancelar");

    reloj.avanzar(Duration.ofMinutes(119));
    bot.handle(1L, "/crearentidad");
    assertTrue(ultimo().contains("Nueva entidad"), "usar el bot renovó la sesión");
    bot.handle(1L, "/cancelar");

    reloj.avanzar(Duration.ofHours(2).plusSeconds(1));
    bot.handle(1L, "/crearentidad");
    String vencida = ultimo();
    assertTrue(vencida.contains("venció"), vencida);
    assertTrue(vencida.contains("/soy_admin"), "tiene que decir cómo volver a entrar");

    bot.handle(1L, "/help");
    assertTrue(ultimo().contains("¿Cómo querés entrar?"), "el chat volvió a no tener rol");
  }

  @Test
  @DisplayName("Un formulario de admin a medio completar no se ejecuta si la sesión venció en el medio")
  void formularioConLaSesionVencida() {
    BotDePrueba.entrarComoAdmin(bot, telegram, 1L);
    bot.handle(1L, "/crearentidad");
    bot.handle(1L, "Comedor");
    bot.handle(1L, "Calle 1");
    bot.handle(1L, "123");

    reloj.avanzar(Duration.ofHours(3));
    bot.handle(1L, "c@mail.com");

    verify(donadores, never()).crearEntidadRaw(anyString(), anyString(), anyString(), anyString());
    assertTrue(ultimo().contains("venció"), ultimo());
  }

  @Test
  @DisplayName("/salir cierra la sesión de administrador")
  void salir() {
    BotDePrueba.entrarComoAdmin(bot, telegram, 1L);

    bot.handle(1L, "/salir");
    bot.handle(1L, "/crearentidad");

    assertTrue(ultimo().contains("administradores"), ultimo());
  }

  // ── Que la contraseña no se filtre ─────────────────────────────────────────

  /**
   * Con el cliente de Telegram real y todos los logs prendidos, incluidos los de depuración de
   * Spring: los logs pueden terminar en Datadog, y lo que escribe el RestTemplate en DEBUG trae el
   * cuerpo de cada pedido.
   */
  @Test
  @DisplayName("La contraseña no aparece en ningún log ni en nada que el bot le mande a Telegram")
  void laContrasenaNoSeFiltra() throws Exception {
    RestTemplate rest = new RestTemplate();
    MockRestServiceServer servidorTelegram =
        MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    List<String> enviado = new ArrayList<>();
    servidorTelegram
        .expect(manyTimes(), requestTo("https://api.telegram.org/botTOKEN-DE-PRUEBA/sendMessage"))
        .andExpect(pedido -> enviado.add(((MockClientHttpRequest) pedido).getBodyAsString()))
        .andRespond(withSuccess("{\"ok\":true,\"result\":{}}", MediaType.APPLICATION_JSON));
    servidorTelegram
        .expect(
            manyTimes(), requestTo("https://api.telegram.org/botTOKEN-DE-PRUEBA/deleteMessage"))
        .andExpect(pedido -> enviado.add(((MockClientHttpRequest) pedido).getBodyAsString()))
        .andRespond(withSuccess("{\"ok\":true,\"result\":true}", MediaType.APPLICATION_JSON));
    TelegramClient telegramReal = new TelegramClient(rest, "TOKEN-DE-PRUEBA");
    DonaTrackBot conTelegramReal =
        BotDePrueba.armar(
            telegramReal,
            donadores,
            mock(DonacionesApiClient.class),
            mock(LogisticaApiClient.class),
            mock(IncentivosApiClient.class),
            new AccesoAdmin(CLAVE, reloj));

    Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Level nivelAnterior = raiz.getLevel();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    raiz.addAppender(logs);
    raiz.setLevel(Level.ALL);
    try {
      // Llegan como llegan de Telegram, con su número de mensaje: un intento fallido y el bueno.
      conTelegramReal.atender(update(1, "/soy_admin"));
      conTelegramReal.atender(update(2, CLAVE + "-mal"));
      conTelegramReal.atender(update(3, CLAVE));
    } finally {
      raiz.detachAppender(logs);
      raiz.setLevel(nivelAnterior);
    }

    assertFalse(logs.list.isEmpty(), "si no se capturó ningún log, la prueba no prueba nada");
    for (ILoggingEvent evento : logs.list) {
      assertFalse(evento.getFormattedMessage().contains(CLAVE), evento.getFormattedMessage());
      for (IThrowableProxy error = evento.getThrowableProxy(); error != null; error = error.getCause()) {
        assertFalse(String.valueOf(error.getMessage()).contains(CLAVE), error.getMessage());
      }
    }
    assertFalse(enviado.isEmpty(), "el bot tuvo que contestar algo");
    for (String cuerpo : enviado) {
      assertFalse(cuerpo.contains(CLAVE), "esto viajó a Telegram: " + cuerpo);
    }
    assertTrue(
        enviado.stream().anyMatch(c -> c.contains("\"message_id\":3")),
        "el mensaje con la contraseña se tiene que haber borrado");
    assertTrue(enviado.stream().anyMatch(c -> c.contains("Entraste como administrador")));
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  private static com.fasterxml.jackson.databind.JsonNode update(long mensajeId, String texto)
      throws Exception {
    return new ObjectMapper()
        .readTree(
            new ObjectMapper()
                .writeValueAsString(
                    java.util.Map.of(
                        "update_id", 100 + mensajeId,
                        "message",
                        java.util.Map.of(
                            "message_id", mensajeId,
                            "chat", java.util.Map.of("id", 1),
                            "text", texto))));
  }

  private List<String> mensajes() {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(telegram, atLeastOnce()).sendMessage(eq(1L), captor.capture());
    return captor.getAllValues();
  }

  private String ultimo() {
    List<String> todos = mensajes();
    return todos.get(todos.size() - 1);
  }
}
