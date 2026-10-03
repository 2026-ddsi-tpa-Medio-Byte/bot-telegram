package ar.edu.utn.dds.k3003.bot;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Qué nombre de algoritmo le llega a Logística.
 *
 * <p>Logística nombra distinto el mismo algoritmo según el endpoint: SUB_ATENDIDOS y
 * PRIORIDAD_POR_SCORE en /depositos, SUBATENDIDOS y PRIOSCORE en /api. El bot configura por /api,
 * así que se prueba contra el cliente real y se mira el parámetro que viaja en la URL: un mock del
 * cliente solo mostraría lo que el bot cree que manda.
 */
@ExtendWith(MockitoExtension.class)
class AlgoritmoTest {

  private static final String LOGISTICA = "http://logistica";

  @Mock private TelegramClient telegram;
  @Mock private DonadoresApiClient donadores;
  @Mock private DonacionesApiClient donaciones;
  @Mock private IncentivosApiClient incentivos;

  private MockRestServiceServer logistica;
  private DonaTrackBot bot;

  @BeforeEach
  void setUp() {
    RestTemplate rest = new RestTemplate();
    logistica = MockRestServiceServer.bindTo(rest).build();
    LogisticaApiClient cliente = new LogisticaApiClient(rest, LOGISTICA);
    Impacto impacto = new Impacto(donaciones, donadores, cliente, incentivos);
    Demo demo = new Demo(donaciones, donadores, cliente, incentivos, "DEP-UTN-01");
    Formularios formularios =
        new Formularios(donadores, donaciones, cliente, incentivos, impacto, "DEP-UTN-01");
    bot =
        new DonaTrackBot(
            telegram, donadores, donaciones, cliente, incentivos, impacto, demo, formularios,
            "DEP-UTN-01");
    bot.handle(1L, "/soy_admin");
  }

  @ParameterizedTest(name = "elegir «{0}» manda {1}")
  @CsvSource({
    "1, SUBATENDIDOS",
    "2, PRIOSCORE",
    "sub-atendidos, SUBATENDIDOS",
    "prioridad por score, PRIOSCORE"
  })
  @DisplayName("El formulario guiado manda el nombre que acepta /api/depositos/{id}/algoritmo")
  void guiado(String eleccion, String esperado) {
    // La primera pregunta muestra los depósitos para no tener que irse a buscar el identificador.
    logistica
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"id\":\"DEP-UTN-01\",\"nombre\":\"Deposito Central UTN\"}]",
                MediaType.APPLICATION_JSON));
    esperarAlgoritmo(esperado);

    bot.handle(1L, "/algoritmo");
    bot.handle(1L, "DEP-UTN-01");
    bot.handle(1L, eleccion);

    logistica.verify();
    verify(telegram).sendMessage(eq(1L), contains("ahora asigna con"));
  }

  @ParameterizedTest(name = "«{0}» manda {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "DEP-UTN-01;SUB_ATENDIDOS       | SUBATENDIDOS",
        "DEP-UTN-01;subatendidos        | SUBATENDIDOS",
        "DEP-UTN-01;1                   | SUBATENDIDOS",
        "DEP-UTN-01;PRIORIDAD_POR_SCORE | PRIOSCORE",
        "DEP-UTN-01;prioridad por score | PRIOSCORE",
        "DEP-UTN-01;prioscore           | PRIOSCORE"
      })
  @DisplayName("El atajo en una línea acepta los nombres de los dos endpoints y manda el de /api")
  void atajo(String argumentos, String esperado) {
    esperarAlgoritmo(esperado);

    bot.handle(1L, "/algoritmo " + argumentos);

    logistica.verify();
    verify(telegram).sendMessage(eq(1L), contains("ahora asigna con"));
  }

  private void esperarAlgoritmo(String algoritmo) {
    logistica
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01/algoritmo?algoritmo=" + algoritmo))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess("Algoritmo actualizado", MediaType.TEXT_PLAIN));
  }
}
