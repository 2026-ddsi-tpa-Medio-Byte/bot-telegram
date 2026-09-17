package ar.edu.utn.dds.k3003.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * El relato es lo que va a leer quien mire la demostración, así que lo que se prueba acá es que no
 * mienta: que no invente un efecto que no pasó y que avise cuando no pudo ver algo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImpactoTest {

  @Mock private DonacionesApiClient donaciones;
  @Mock private DonadoresApiClient donadores;
  @Mock private LogisticaApiClient logistica;
  @Mock private IncentivosApiClient incentivos;

  private Impacto impacto;

  @BeforeEach
  void setUp() {
    impacto = new Impacto(donaciones, donadores, logistica, incentivos);
  }

  @Test
  @DisplayName("Al donar se cuenta qué pasó en los tres módulos")
  void donacionCuentaElRecorrido() {
    when(donaciones.buscarProducto("3")).thenReturn("{\"id\":\"3\",\"nombre\":\"Arroz\"}");
    when(donadores.necesidadesDeProducto("3"))
        .thenReturn(
            """
            [{"id":"7","cantidadObjetivo":20,"cantidadActual":0}]""");
    when(logistica.consultarStock("3")).thenReturn("{\"disponible\":0}");
    when(donaciones.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10))
        .thenReturn("{\"id\":\"12\",\"cantidad\":10,\"estado\":\"INGRESADA\"}");

    String salida = impacto.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10);

    assertTrue(salida.contains("nº 12"));
    assertTrue(salida.contains("Arroz"), "el nombre se lee mejor que el número de producto");
    assertTrue(salida.contains("INGRESADA"));
    assertTrue(salida.contains("la asignó a una necesidad"), "el stock no subió");
    assertTrue(salida.contains("0/20"), "hay que ver cómo quedó la necesidad");
    assertTrue(salida.contains("no se satisface al donar"), "es lo que más se malinterpreta");
    assertTrue(salida.contains("traza"), "la traza permite buscar la operación en Datadog");
  }

  @Test
  @DisplayName("Si el stock sube, la donación quedó guardada y no asignada")
  void donacionQueQuedaEnStock() {
    when(donaciones.buscarProducto("3")).thenReturn("{\"id\":\"3\",\"nombre\":\"Arroz\"}");
    when(donadores.necesidadesDeProducto("3")).thenReturn("[]");
    when(logistica.consultarStock("3"))
        .thenReturn("{\"disponible\":0}", "{\"disponible\":10}");
    when(donaciones.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10))
        .thenReturn("{\"id\":\"12\",\"cantidad\":10,\"estado\":\"INGRESADA\"}");

    String salida = impacto.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10);

    assertTrue(salida.contains("quedó guardada"));
    assertFalse(salida.contains("la asignó a una necesidad"));
    assertTrue(salida.contains("no hay ninguna pendiente"), "sin necesidades hay que decirlo");
  }

  @Test
  @DisplayName("Si no se puede leer el estado de los módulos, la donación no se pierde")
  void relatoIncompletoNoTapaLaDonacion() {
    when(donaciones.buscarProducto("3")).thenThrow(new RuntimeException("no responde"));
    when(donadores.necesidadesDeProducto("3")).thenThrow(new RuntimeException("no responde"));
    when(logistica.consultarStock("3")).thenThrow(new RuntimeException("no responde"));
    when(donaciones.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10))
        .thenReturn("{\"id\":\"12\",\"cantidad\":10,\"estado\":\"INGRESADA\"}");

    String salida = impacto.donar("1", "DEP-UTN-01", "Diez kilos", "3", 10);

    assertTrue(salida.contains("nº 12"), "la donación se registró: eso tiene que verse igual");
    assertTrue(salida.contains("no contestó"), "y hay que avisar que el resumen está incompleto");
  }

  @Test
  @DisplayName("Una queja que deja al donador baneado lo dice con todas las letras")
  void quejaQueBanea() {
    when(donaciones.buscarDonacion("12"))
        .thenReturn("{\"id\":\"12\",\"donadorID\":\"1\",\"estado\":\"ACEPTADA\"}");
    when(donadores.buscarDonador("1"))
        .thenReturn(
            "{\"id\":\"1\",\"estado\":\"SOSPECHOSO\"}", "{\"id\":\"1\",\"estado\":\"BANEADO\"}");
    when(donadores.quejasDe("1")).thenReturn("[{},{},{},{},{},{},{},{},{},{},{}]");

    String salida = impacto.queja("12", "Llegó roto");

    assertTrue(salida.contains("SOSPECHOSO → BANEADO"));
    assertTrue(salida.contains("no puede donar"));
    assertTrue(salida.contains("11 quejas"));
  }

  @Test
  @DisplayName("La entrega muestra cuánto avanzó la necesidad")
  void entregaMuestraElAvance() {
    when(logistica.buscarAsignacion("paq-12"))
        .thenReturn(
            """
            {"paqueteid":"paq-12","necesidadid":"7","estado":"ASIGNADA","origen":"MATCHMAKING"}""");
    when(donaciones.buscarDonacion("12"))
        .thenReturn(
            "{\"id\":\"12\",\"estado\":\"INGRESADA\"}", "{\"id\":\"12\",\"estado\":\"ACEPTADA\"}");
    when(donadores.buscarNecesidad("7"))
        .thenReturn(
            "{\"id\":\"7\",\"cantidadObjetivo\":20,\"cantidadActual\":0}",
            "{\"id\":\"7\",\"cantidadObjetivo\":20,\"cantidadActual\":20}");

    String salida = impacto.reportarEntrega("paq-12", "12", "3", 20);

    assertTrue(salida.contains("INGRESADA → ACEPTADA"));
    assertTrue(salida.contains("20/20"));
    assertTrue(salida.contains("cubierta"));
    assertTrue(salida.contains("matchmaking"), "de dónde salió la asignación es parte del relato");
  }

  @Test
  @DisplayName("Procesar en Incentivos muestra la insignia ganada y la categoría")
  void procesarMuestraLaInsignia() {
    when(donadores.buscarDonador("1"))
        .thenReturn(
            "{\"id\":\"1\",\"categoria\":\"OCASIONAL\"}",
            "{\"id\":\"1\",\"categoria\":\"COLABORADOR\"}");
    when(incentivos.insigniasDe("1")).thenReturn("[]", "[{\"id\":\"ins-1\"}]");

    String salida = impacto.procesar("1");

    assertTrue(salida.contains("0 → 1"));
    assertTrue(salida.contains("ganó una"));
    assertTrue(salida.contains("OCASIONAL → COLABORADOR"));
  }
}
