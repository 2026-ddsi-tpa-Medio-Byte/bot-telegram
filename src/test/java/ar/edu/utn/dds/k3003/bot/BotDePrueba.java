package ar.edu.utn.dds.k3003.bot;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/** Arma el bot como lo arma Spring, con los clientes que pase cada test. */
final class BotDePrueba {

  static final String CLAVE = "clave-de-prueba";

  /** El número de mensaje con el que llega la contraseña, para poder verificar que se borra. */
  static final long MENSAJE_CON_CLAVE = 500L;

  private BotDePrueba() {}

  static DonaTrackBot armar(
      TelegramClient telegram,
      DonadoresApiClient donadores,
      DonacionesApiClient donaciones,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos,
      AccesoAdmin acceso) {
    Impacto impacto = new Impacto(donaciones, donadores, logistica, incentivos);
    Demo demo = new Demo(donaciones, donadores, logistica, incentivos, "DEP-TEST");
    Consultas consultas = new Consultas(donaciones, donadores, logistica, incentivos);
    Formularios formularios =
        new Formularios(donadores, donaciones, logistica, incentivos, impacto, "DEP-TEST");
    return new DonaTrackBot(
        telegram, donadores, donaciones, logistica, incentivos, impacto, demo, formularios,
        consultas, acceso, "DEP-TEST");
  }

  static DonaTrackBot armar(
      TelegramClient telegram,
      DonadoresApiClient donadores,
      DonacionesApiClient donaciones,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos) {
    return armar(
        telegram, donadores, donaciones, logistica, incentivos,
        new AccesoAdmin(CLAVE, new RelojDePrueba()));
  }

  /**
   * Entra como admin por el mismo camino que una persona: /soy_admin y la contraseña en el
   * mensaje siguiente. Con un {@code telegram} mockeado, el borrado del mensaje sale bien.
   */
  static void entrarComoAdmin(DonaTrackBot bot, TelegramClient telegram, long chatId) {
    lenient().when(telegram.deleteMessage(anyLong(), anyLong())).thenReturn(true);
    bot.handle(chatId, "/soy_admin");
    bot.handle(chatId, MENSAJE_CON_CLAVE, CLAVE);
  }
}
