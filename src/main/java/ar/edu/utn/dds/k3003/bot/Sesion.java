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

  Rol rol() {
    return rol;
  }

  void comoDonador() {
    this.rol = Rol.DONADOR;
  }

  void comoAdmin() {
    this.rol = Rol.ADMIN;
    this.donadorId = null;
    this.nombre = null;
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
