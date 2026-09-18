# DonaTrack — Bot de Telegram

Bot de Telegram para operar DonaTrack desde el celular. Es un **cliente HTTP de los cuatro
módulos** —Donaciones, Donadores y Entidades, Logística e Incentivos— y no tiene base de datos
propia. Corre localmente como un único proceso (long-polling).

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
| `LOGISTICA_URL` | la de Render | URL del módulo Logística, para consultar depósitos, stock y reportar entregas. |
| `INCENTIVOS_URL` | la de Render | URL del módulo Incentivos, para consultar insignias, misiones y procesar donadores. |
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
- `/quejar donacionId;que paso`

**Catálogo de Donaciones**

- `/crearidentificador CODIGODEBARRAS|QR;descripcion`
- `/crearproducto nombre;descripcion;categoria;identificadorID`

**Logística**

- `/depositos` · `/stock <productoID>`
- `/creardeposito id;nombre;direccion;capacidad`
- `/algoritmo depositoId;SUB_ATENDIDOS|PRIORIDAD_POR_SCORE` — el criterio con el que ese
  depósito elige a qué necesidad le asigna cada donación
- `/reportarentrega donacionId;productoId;cantidad` — el paquete no se pide: Logística lo
  nombra `paq-` más el número de la donación

**Incentivos**

- `/insignias` · `/misiones`
- `/crearinsignia id;nombre;descripcion`
- `/crearmision id;nombre;insigniaID;categoriaInicio;categoriaFin`
- `/procesardonador <donadorId>`

> `tipo` de necesidad: `EXTRAORDINARIA` o `RECURRENTE`.

### Demostración

Comandos para conducir una demostración sin preparar nada a mano:

| Comando | Qué hace |
|---|---|
| `/demo` | El guion: qué mostrar y en qué orden |
| `/despertar` | Saca del sueño a los cuatro servicios de Render; conviene unos minutos antes |
| `/reiniciar` | Vacía las bases de los cuatro módulos (admin) |
| `/preparar` | Carga las precondiciones de todos los flujos (admin) |
| `/estado` | Cómo está el sistema ahora mismo, módulo por módulo |
| `/donarcomo donadorID;productoID;cantidad;descripcion` | Donar a nombre de otro, para que el admin recorra los flujos sin cambiar de rol |

**Las operaciones cuentan qué provocaron.** Donar, reportar una entrega, quejarse o procesar un
donador no devuelven el JSON del módulo que las recibió, sino un resumen de qué cambió en cada
uno: el estado de la donación, si Logística la asignó o la guardó, cómo quedó la necesidad y qué
insignias se movieron. Cada operación viaja además con un número de traza. Donaciones y Donadores la
escriben en sus logs, así que buscándola en Datadog se ve qué hizo cada uno. Logística e
Incentivos todavía no la propagan: una entrega o un procesamiento no se pueden seguir enteros.

Ejemplo de donador:
```
/start
/soy_donador
/registrarse Juan;Perez;30;juan@mail.com;40123456;Calle 5
/productos
/donar 1;10;Diez kilos de arroz
/misdonaciones
```

Ejemplo de admin, que es tambien el recorrido de la demostracion:
```
/start
/soy_admin
/reiniciar
/preparar
/estado
/donarcomo 1;1;10;Diez kilos de arroz
/reportarentrega 1;1;10
/quejar 1;Llego en mal estado
/procesardonador 1
```

## Notas de diseño
- Sin librerías externas de Telegram: usa la Bot API por HTTP (`getUpdates`/`sendMessage`) con
  `RestTemplate`, para evitar problemas de versiones.
- "Recibe un comando y devuelve una respuesta" (como pide la consigna). El estado que guarda es
  mínimo: el rol elegido por chat y el donador identificado.
- Bajo este esquema (long-polling), **solo una instancia** del bot puede correr a la vez por token.
- Conecta con los cuatro módulos del sistema DonaTrack: Donadores y Entidades, Donaciones, Logística e Incentivos.
