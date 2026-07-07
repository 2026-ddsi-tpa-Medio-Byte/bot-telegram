# DonaTrack — Bot de Telegram (UI, Entrega 4)

Bot de Telegram que permite operar el sistema DonaTrack desde el celular. Es un **cliente HTTP**
del módulo *Donadores y Entidades* (no tiene base de datos propia). Corre localmente como un único
proceso (long-polling).

## Requisitos
- Java 21, Maven.
- Un **token de bot** de Telegram (se obtiene de [@BotFather](https://t.me/BotFather) con `/newbot`).
- El módulo *Donadores y Entidades* corriendo y accesible (local o Render).

## Configuración
Variables de entorno (o editar `src/main/resources/application.properties`):

| Variable | Default | Descripción |
|----------|---------|-------------|
| `TELEGRAM_BOT_TOKEN` |  | Token de BotFather. **Sin esto el bot no arranca.** |
| `DONADORES_URL` | `http://localhost:8080` | URL base del módulo Donadores (local o Render). |

## Cómo correrlo
```bash
# PowerShell
$env:TELEGRAM_BOT_TOKEN = "123456:ABC..."
$env:DONADORES_URL = "https://entrega-2-joaqulopez.onrender.com"
mvn spring-boot:run
```
Luego, en Telegram, buscá tu bot y mandá `/start`.

## Comandos
Primero `/start` y elegí rol: `/soy_donador` o `/soy_admin`.

**Donador**
- `/registrarse nombre;apellido;edad;email;documento;domicilio`
- `/estadisticas <donadorID>`
- `/donador <id>`
- `/donadores`

**Admin**
- `/crearentidad razonSocial;domicilio;telefono;correo`
- `/editarentidad id;razonSocial;domicilio;telefono;correo`
- `/entidad <id>`
- `/entidades`
- `/altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo`
- `/necesidad <id>`
- `/modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo`
- `/borrarnecesidad <id>`

> `tipo` de necesidad: `EXTRAORDINARIA` o `RECURRENTE`.

Ejemplo:
```
/start
/soy_admin
/crearentidad Comedor Hogwarts;Calle 1;1130000000;hogwarts@mail.com
/altanecesidad 1;8;30 sillas tras inundacion;30;producto1;EXTRAORDINARIA
```

## Notas de diseño
- Sin librerías externas de Telegram: usa la Bot API por HTTP (`getUpdates`/`sendMessage`) con
  `RestTemplate`, para evitar problemas de versiones.
- "Recibe un comando y devuelve una respuesta" (como pide la consigna). El estado que guarda es
  mínimo: el rol elegido por chat.
- Bajo este esquema (long-polling), **solo una instancia** del bot puede correr a la vez por token.
- Endpoints del módulo que usa el bot (todos en *Donadores y Entidades*): `POST/GET /donadores`,
  `GET /donadores/{id}`, `GET /donadores/{id}/estadisticas`, `POST/GET/PUT /entidades` y
  `/entidades/{id}`, `POST/GET/PUT/DELETE /necesidades` y `/necesidades/{id}`.
