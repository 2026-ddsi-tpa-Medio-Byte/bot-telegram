package ar.edu.utn.dds.k3003.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Un reloj que avanza solo cuando el test lo pide: el bloqueo y el vencimiento se prueban sin dormir. */
class RelojDePrueba extends Clock {

  private Instant ahora = Instant.parse("2026-10-03T08:20:00Z");

  void avanzar(Duration cuanto) {
    ahora = ahora.plus(cuanto);
  }

  @Override
  public Instant instant() {
    return ahora;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zona) {
    return this;
  }
}
