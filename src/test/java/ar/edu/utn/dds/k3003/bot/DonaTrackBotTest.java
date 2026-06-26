package ar.edu.utn.dds.k3003.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

/** Tests del parseo y despacho de comandos del bot (Telegram y API mockeados). */
@ExtendWith(MockitoExtension.class)
class DonaTrackBotTest {

  @Mock private TelegramClient telegram;
  @Mock private DonadoresApiClient api;

  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    bot = new DonaTrackBot(telegram, api);
  }

  @Test
  @DisplayName("/start ofrece elegir rol")
  void start() {
    bot.handle(1L, "/start");
    verify(telegram).sendMessage(eq(1L), contains("/soy_donador"));
  }

  @Test
  @DisplayName("/donadores consulta la API y envía la respuesta")
  void listarDonadores() {
    when(api.listarDonadores()).thenReturn("[lista]");
    bot.handle(1L, "/donadores");
    verify(api).listarDonadores();
    verify(telegram).sendMessage(1L, "[lista]");
  }

  @Test
  @DisplayName("/registrarse parsea los 6 campos y llama a la API")
  void registrarse() {
    when(api.registrarDonador("Juan", "Perez", 30, "j@x.com", "123", "Calle Falsa 123"))
        .thenReturn("Donador registrado");
    bot.handle(1L, "/registrarse Juan;Perez;30;j@x.com;123;Calle Falsa 123");
    verify(api).registrarDonador("Juan", "Perez", 30, "j@x.com", "123", "Calle Falsa 123");
  }

  @Test
  @DisplayName("/registrarse con campos faltantes no llama a la API y avisa el uso")
  void registrarseFaltanCampos() {
    bot.handle(1L, "/registrarse Juan;Perez");
    verify(api, never()).registrarDonador(any(), any(), anyInt(), any(), any(), any());
    verify(telegram).sendMessage(eq(1L), contains("Se esperaban 6"));
  }

  @Test
  @DisplayName("/altanecesidad parsea campos numéricos y normaliza el tipo")
  void altaNecesidad() {
    when(api.altaNecesidad("5", 3, "sillas", 30, "prod1", "EXTRAORDINARIA"))
        .thenReturn("Necesidad creada");
    bot.handle(1L, "/altanecesidad 5;3;sillas;30;prod1;extraordinaria");
    verify(api).altaNecesidad("5", 3, "sillas", 30, "prod1", "EXTRAORDINARIA");
  }

  @Test
  @DisplayName("Comando desconocido responde con ayuda")
  void desconocido() {
    bot.handle(1L, "/cualquiercosa");
    verify(telegram).sendMessage(eq(1L), contains("no reconocido"));
  }
}
