package ar.edu.utn.dds.k3003.bot;

/**
 * Lo que el bot recuerda de cada chat.
 *
 * <p>Antes solo guardaba el rol. Ahora, si el usuario entra como donador, guarda también con qué
 * donador se identificó: así puede pedir "mis donaciones" o donar sin repetir su número en cada
 * mensaje.
 */
class Sesion {

  enum Rol {
    NINGUNO,
    DONADOR,
    ADMIN
  }

  private Rol rol = Rol.NINGUNO;
  private String donadorId;
  private String nombre;

  /** Cuándo se usó por última vez la sesión de admin: vence por inactividad, no por reloj fijo. */
  private java.time.Instant ultimoUso;

  /** La sesión de admin se cerró sola y todavía no se le avisó a la persona. */
  private boolean vencida;

  Rol rol() {
    return rol;
  }

  void comoDonador() {
    this.rol = Rol.DONADOR;
  }

  /** Solo se llega acá con la contraseña correcta: lo controla quien la llama. */
  void comoAdmin(java.time.Instant ahora) {
    this.rol = Rol.ADMIN;
    this.donadorId = null;
    this.nombre = null;
    this.ultimoUso = ahora;
    this.vencida = false;
  }

  void usada(java.time.Instant ahora) {
    this.ultimoUso = ahora;
  }

  java.time.Instant ultimoUso() {
    return ultimoUso;
  }

  /** Cierra la sesión de admin por inactividad y recuerda avisarlo la próxima vez. */
  void vencer() {
    salir();
    this.vencida = true;
  }

  /** Si la sesión venció sin que se avisara; después de preguntarlo, ya se considera avisado. */
  boolean avisarVencimiento() {
    boolean avisar = vencida;
    vencida = false;
    return avisar;
  }

  /** Queda identificado como un donador concreto. */
  void identificar(String donadorId, String nombre) {
    this.rol = Rol.DONADOR;
    this.donadorId = donadorId;
    this.nombre = nombre;
  }

  void salir() {
    this.rol = Rol.NINGUNO;
    this.donadorId = null;
    this.nombre = null;
    this.ultimoUso = null;
  }

  boolean estaIdentificado() {
    return donadorId != null;
  }

  String donadorId() {
    return donadorId;
  }

  String nombre() {
    return nombre == null ? "" : nombre;
  }
}
