package ar.edu.utn.dds.k3003.bot;

import ar.edu.utn.dds.k3003.bot.Formulario.Campo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Las conversaciones guiadas del bot: qué pregunta cada una y qué hace con las respuestas.
 *
 * <p>Están todas juntas acá para que se lean como un guion y no mezcladas entre el despacho de
 * comandos. Cada una termina llamando exactamente a lo mismo que el comando de una sola línea,
 * así que las dos formas de usar el bot hacen lo mismo.
 */
@Component
class Formularios {

  /** Cuántos elementos se muestran al ofrecer un listado para elegir. */
  private static final int SUGERENCIAS_A_MOSTRAR = 8;

  private final DonadoresApiClient donadores;
  private final DonacionesApiClient donaciones;
  private final LogisticaApiClient logistica;
  private final IncentivosApiClient incentivos;
  private final Impacto impacto;
  private final String depositoPorDefecto;

  Formularios(
      DonadoresApiClient donadores,
      DonacionesApiClient donaciones,
      LogisticaApiClient logistica,
      IncentivosApiClient incentivos,
      Impacto impacto,
      @Value("${deposito.default:DEP-UTN-01}") String depositoPorDefecto) {
    this.donadores = donadores;
    this.donaciones = donaciones;
    this.logistica = logistica;
    this.incentivos = incentivos;
    this.impacto = impacto;
    this.depositoPorDefecto = depositoPorDefecto;
  }

  // ── Donador ────────────────────────────────────────────────────────────────

  Formulario registro(Sesion sesion) {
    return new Formulario(
        "Registro de donador",
        r -> {
          String json =
              donadores.registrarDonadorRaw(
                  r.get(0), r.get(1), Integer.parseInt(r.get(2)), r.get(3), r.get(4), r.get(5));
          JsonNode creado = Formato.parsear(json);
          String id = creado == null ? "" : creado.path("id").asText("");
          if (id.isBlank()) {
            return "Registrado: " + json;
          }
          sesion.identificar(id, r.get(0));
          return "🎉 Listo <b>"
              + r.get(0)
              + "</b>, quedaste registrado con el número <b>"
              + id
              + "</b>.\nAnotátelo: con eso entrás la próxima vez con /entrar "
              + id
              + "\n\nYa podés donar con /donar. /help para ver todo lo demás.";
        },
        Campo.texto("¿Cómo te llamás?"),
        Campo.texto("¿Y tu apellido?"),
        Campo.numero("¿Qué edad tenés?"),
        Campo.texto("¿Tu email?"),
        Campo.texto("¿Tu número de documento?"),
        Campo.texto("¿Dónde vivís?"));
  }

  Formulario donar(Sesion sesion) {
    return new Formulario(
        "Nueva donación",
        r ->
            impacto.donar(
                sesion.donadorId(), depositoPorDefecto, r.get(2), r.get(0), Integer.parseInt(r.get(1))),
        Campo.numero("¿Qué vas a donar? Pasame el número del producto").conAyuda(this::catalogo),
        Campo.numero("¿Cuántas unidades?"),
        Campo.texto("Describilo en pocas palabras"));
  }

  Formulario donarComo() {
    return new Formulario(
        "Donación a nombre de otro",
        r ->
            impacto.donar(
                r.get(0), depositoPorDefecto, r.get(3), r.get(1), Integer.parseInt(r.get(2))),
        Campo.numero("¿Qué donador dona? Pasame su número").conAyuda(this::listaDonadores),
        Campo.numero("¿Qué producto?").conAyuda(this::catalogo),
        Campo.numero("¿Cuántas unidades?"),
        Campo.texto("Describí la donación"));
  }

  Formulario queja() {
    return new Formulario(
        "Queja sobre una donación",
        r -> impacto.queja(r.get(0), r.get(1)),
        Campo.numero("¿Sobre qué donación es la queja? Pasame su número"),
        Campo.texto("¿Qué pasó con esa donación?"));
  }

  // ── Admin: entidades y necesidades ─────────────────────────────────────────

  Formulario entidad() {
    return new Formulario(
        "Nueva entidad",
        r -> "✅ Entidad creada\n\n" + Formato.entidad(donadores.crearEntidadRaw(r.get(0), r.get(1), r.get(2), r.get(3))),
        Campo.texto("¿Cómo se llama la entidad?"),
        Campo.texto("¿Dónde queda?"),
        Campo.texto("¿Teléfono?"),
        Campo.texto("¿Correo de contacto?"));
  }

  Formulario necesidad() {
    return new Formulario(
        "Nueva necesidad",
        r ->
            "✅ Necesidad creada\n\n"
                + Formato.necesidad(
                    donadores.altaNecesidadRaw(
                        r.get(0),
                        Integer.parseInt(r.get(4)),
                        r.get(3),
                        Integer.parseInt(r.get(2)),
                        r.get(1),
                        r.get(5).toUpperCase())),
        Campo.numero("¿Qué entidad lo necesita? Pasame su número").conAyuda(this::listaEntidades),
        Campo.numero("¿Qué producto necesita?").conAyuda(this::catalogo),
        Campo.numero("¿Cuántas unidades hacen falta?"),
        Campo.texto("¿Para qué es? Contámelo en pocas palabras"),
        Campo.numero("¿Qué tan urgente es, del 1 al 10?"),
        Campo.opcion("¿Qué tipo de necesidad es?", "EXTRAORDINARIA", "RECURRENTE"));
  }

  Formulario estadoDonador() {
    return new Formulario(
        "Cambiar el estado de un donador",
        r -> "✅ Estado cambiado\n\n" + Formato.donador(donadores.cambiarEstadoDonador(r.get(0), r.get(1).toUpperCase())),
        Campo.numero("¿Qué donador? Pasame su número").conAyuda(this::listaDonadores),
        Campo.opcion("¿Qué estado le ponemos?", "VERIFICADO", "SOSPECHOSO", "BANEADO"));
  }

  // ── Admin: catálogo, logística e incentivos ────────────────────────────────

  Formulario producto() {
    return new Formulario(
        "Nuevo producto donable",
        r -> "📦 Producto creado:\n" + donaciones.crearProducto(r.get(0), r.get(1), r.get(2), r.get(3)),
        Campo.texto("¿Cómo se llama el producto?"),
        Campo.texto("Describilo (con código de barras hacen falta al menos tres palabras)"),
        Campo.texto("¿De qué categoría es? Por ejemplo alimentos o abrigo"),
        Campo.numero("¿Con qué identificador? Pasame su número").conAyuda(this::identificadores));
  }

  Formulario deposito() {
    return new Formulario(
        "Nuevo depósito",
        r -> {
          logistica.crearDeposito(r.get(0), r.get(1), r.get(2), Integer.parseInt(r.get(3)), "SUB_ATENDIDOS");
          return "🏬 Depósito <b>" + r.get(0) + "</b> creado.";
        },
        Campo.texto("¿Qué identificador le ponemos? Por ejemplo DEP-NORTE"),
        Campo.texto("¿Cómo se llama?"),
        Campo.texto("¿Dónde queda?"),
        Campo.numero("¿Cuántas unidades entran?"));
  }

  Formulario entrega() {
    return new Formulario(
        "Reportar una entrega",
        r ->
            impacto.reportarEntrega(
                "paq-" + r.get(0), r.get(0), r.get(1), Integer.parseInt(r.get(2))),
        Campo.numero("¿Qué donación se entregó? Pasame su número"),
        Campo.numero("¿De qué producto era?").conAyuda(this::catalogo),
        Campo.numero("¿Cuántas unidades se entregaron?"));
  }

  Formulario insignia() {
    return new Formulario(
        "Nueva insignia",
        r -> {
          incentivos.crearInsignia(r.get(0), r.get(1), r.get(2));
          return "🏅 Insignia <b>" + r.get(0) + "</b> creada.";
        },
        Campo.texto("¿Qué identificador le ponemos? Por ejemplo ins-solidario"),
        Campo.texto("¿Cómo se llama?"),
        Campo.texto("¿Qué reconoce?"));
  }

  Formulario mision() {
    return new Formulario(
        "Nueva misión",
        r -> {
          incentivos.crearMision(r.get(0), r.get(1), r.get(2), r.get(3).toUpperCase(), r.get(4).toUpperCase(), "COMPLETITUD");
          return "🎯 Misión <b>" + r.get(0) + "</b> creada: otorga la insignia " + r.get(2) + ".";
        },
        Campo.texto("¿Qué identificador le ponemos? Por ejemplo mis-primera"),
        Campo.texto("¿Cómo se llama?"),
        Campo.texto("¿Qué insignia otorga? Pasame su identificador").conAyuda(this::listaInsignias),
        Campo.texto("¿Desde qué categoría arranca?"),
        Campo.texto("¿A qué categoría lleva?"));
  }

  // ── Listados para no tener que irse a buscar un número ─────────────────────

  private String catalogo() {
    return listado(donaciones::listarProductos, "nombre", "Esto se puede donar:");
  }

  private String listaDonadores() {
    return listado(donadores::listarDonadores, "nombre", "Donadores:");
  }

  private String listaEntidades() {
    return listado(donadores::listarEntidades, "razonSocial", "Entidades:");
  }

  private String listaInsignias() {
    return listado(incentivos::listarInsignias, "nombre", "Insignias:");
  }

  private String identificadores() {
    return listado(donaciones::listarIdentificadores, "tipo", "Identificadores:");
  }

  /** Arma «nº — nombre» con los primeros elementos, o nada si el módulo no contesta. */
  private String listado(Supplier<String> consulta, String campoNombre, String encabezado) {
    JsonNode lista = Formato.parsear(consulta.get());
    if (lista == null || !lista.isArray() || lista.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("<i>" + encabezado + "</i>\n");
    int mostrados = 0;
    for (JsonNode item : lista) {
      if (mostrados++ >= SUGERENCIAS_A_MOSTRAR) {
        sb.append("… y ").append(lista.size() - SUGERENCIAS_A_MOSTRAR).append(" más\n");
        break;
      }
      sb.append("• <b>")
          .append(item.path("id").asText("?"))
          .append("</b> — ")
          .append(item.path(campoNombre).asText(""))
          .append("\n");
    }
    return sb.toString().trim();
  }
}
