package ar.edu.utn.dds.k3003.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    bot = new DonaTrackBot(telegram, api, donaciones, "DEP-TEST");
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

    verify(telegram).sendMessage(eq(1L), contains("Hola *Ana*"));
  }

  @Test
  @DisplayName("Al registrarse queda identificado solo, sin tener que entrar después")
  void registrarseIdentifica() {
    when(api.registrarDonadorRaw("Juan", "Perez", 30, "j@x.com", "123", "Calle 5"))
        .thenReturn("{\"id\":\"7\",\"nombre\":\"Juan\"}");

    bot.handle(1L, "/registrarse Juan;Perez;30;j@x.com;123;Calle 5");

    verify(telegram).sendMessage(eq(1L), contains("número *7*"));
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
    verify(telegram).sendMessage(eq(1L), contains("Gracias por donar"));
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
}
