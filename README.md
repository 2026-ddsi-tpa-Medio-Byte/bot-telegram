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
| `DONACIONES_URL` | la de Render | URL del módulo Donaciones, para que se pueda donar desde el bot. |
| `DEPOSITO_DEFAULT` | `DEP-UTN-01` | Depósito al que van las donaciones hechas desde el bot. |

## Cómo correrlo

Desde la carpeta del proyecto, en **PowerShell** (no en CMD):

```powershell
cd "C:\Users\julia\Desktop\TP DDSI\TelegramBot"
$env:TELEGRAM_BOT_TOKEN = "123456:ABC..."
$env:DONADORES_URL = "https://donadoresyentidadesv2.onrender.com"
mvn spring-boot:run
```

En CMD la sintaxis es distinta (`set VARIABLE=valor`, sin comillas y una por línea).

El arranque es correcto cuando aparece esto y **la terminal queda escuchando**, sin volver al prompt:

```
Bot de Telegram iniciado (long-polling). Escuchando mensajes...
```

Luego, en Telegram, buscá tu bot y mandá `/start`. Para detenerlo, Ctrl+C.

## Comandos

`/start` y elegís rol: `/soy_donador` o `/soy_admin`.

### Donador

El donador **entra con su número** y el bot lo recuerda: después dona y consulta lo suyo
sin repetir quién es.

- `/entrar <número>` — si ya está registrado
- `/registrarse nombre;apellido;edad;email;documento;domicilio` — la primera vez; queda
  identificado automáticamente
- `/donar productoID;cantidad;descripcion`
- `/productos` — qué se puede donar
- `/misdonaciones` — las suyas, con el estado de cada una
- `/perfil` — sus datos
- `/misestadisticas` — categoría e insignias
- `/puedodonar` — si tiene la cuenta habilitada
- `/salir`

### Admin

- `/crearentidad razonSocial;domicilio;telefono;correo`
- `/editarentidad id;razonSocial;domicilio;telefono;correo`
- `/entidad <id>` · `/entidades`
- `/altanecesidad entidadID;urgencia;descripcion;cantidadObjetivo;productoID;tipo`
- `/modificarnecesidad id;urgencia;descripcion;cantidadObjetivo;productoID;tipo`
- `/necesidad <id>` · `/borrarnecesidad <id>`
- `/donadores` · `/donador <id>` · `/estadisticas <id>` · `/quejas <id>`
- `/estadodonador id;VERIFICADO|SOSPECHOSO|BANEADO`
- `/categoriadonador id;categoria`

> `tipo` de necesidad: `EXTRAORDINARIA` o `RECURRENTE`.

Ejemplo de donador:
```
/start
/soy_donador
/registrarse Juan;Perez;30;juan@mail.com;40123456;Calle 5
/productos
/donar 1;10;Diez kilos de arroz
/misdonaciones
```

Ejemplo de admin:
```
/start
/soy_admin
/crearentidad Comedor Hogwarts;Calle 1;1130000000;hogwarts@mail.com
/altanecesidad 1;8;Treinta sillas tras la inundacion;30;1;EXTRAORDINARIA
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
