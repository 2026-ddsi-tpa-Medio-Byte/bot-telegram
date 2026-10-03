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

El bot habla con los **cuatro** módulos: hay un cliente HTTP por cada uno. La única URL que hace
falta pasar es `DONADORES_URL`, porque es la única cuyo default apunta a `localhost`; las otras
tres ya apuntan a Render.

**Si te olvidás de `DONADORES_URL`, el bot arranca igual.** Falla recién al usarlo, y falla en
casi todo —sesión, donadores, entidades, necesidades, estadísticas— porque Donadores es el módulo
que más se consulta. El síntoma es un error de conexión contra `localhost:8080`, que no se parece
a un problema de configuración. Es el default más peligroso de los cuatro y conviene cambiarlo
por el de Render.

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

**Los comandos que piden varios datos te los preguntan de a uno.** Escribís `/registrarse` y el
bot pregunta el nombre, después el apellido, después la edad. Si te equivocás en uno, te avisa
ahí mismo y vuelve a preguntar ese, sin perder lo anterior. `/cancelar` en cualquier momento.

```
vos  /registrarse
bot  1 de 6 · ¿Cómo te llamás?
vos  Juan
bot  2 de 6 · ¿Y tu apellido?
...
bot  🎉 Listo Juan, quedaste registrado con el número 7.
```

Donde hace falta un número que no se sabe de memoria —un producto, una entidad, un donador— el
bot muestra el listado junto con la pregunta.

> Los mismos comandos aceptan todos los datos en una línea separados por `;`, que es más rápido
> para una demostración: `/donar 1;10;Diez kilos de arroz`. Las dos formas hacen lo mismo.

### Donador

- `/entrar <número>` — si ya está registrado
- `/registrarse` — la primera vez; queda identificado automáticamente
- `/donar` — qué, cuánto y para qué
- `/productos` — qué se puede donar
- `/misdonaciones` — las suyas, con el estado de cada una
- `/perfil` — sus datos
- `/misestadisticas` — categoría e insignias
- `/puedodonar` — si tiene la cuenta habilitada
- `/salir`

### Admin

**Entidades y necesidades**

- `/crearentidad` · `/editarentidad` · `/entidad <id>` · `/entidades`
- `/altanecesidad` · `/modificarnecesidad` · `/necesidad <id>` · `/borrarnecesidad <id>`

**Donadores**

- `/donadores` · `/donador <id>` · `/estadisticas <id>` · `/quejas <id>`
- `/estadodonador` — verificado, sospechoso o baneado
- `/categoriadonador` — ofrece las categorías, de ocasional a revolucionario
- `/quejar` — reclamar por una donación entregada

**Catálogo de Donaciones**

- `/crearidentificador` — código de barras o QR, alta guiada
- `/crearproducto` — alta guiada
- `/donarcomo` — donar a nombre de otro, sin cambiar de rol

**Logística**

- `/depositos` · `/stock <productoID>`
- `/creardeposito` — alta guiada
- `/algoritmo` — el criterio con el que un depósito elige a qué necesidad le asigna cada
  donación: sub-atendidos o prioridad por score. Se configura por `/api`, donde Logística los
  llama `SUBATENDIDOS` y `PRIOSCORE`; el bot traduce cualquiera de las dos formas de escribirlos
- `/reportarentrega` — el paquete no se pide: Logística lo nombra `paq-` más el número de la
  donación

**Incentivos**

- `/insignias` · `/misiones`
- `/crearinsignia` · `/crearmision` — altas guiadas
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

**Las operaciones cuentan qué provocaron.** Donar, reportar una entrega, quejarse o procesar un
donador no devuelven el JSON del módulo que las recibió, sino un resumen de qué cambió en cada
uno: el estado de la donación, a qué necesidad la mandó Logística, cómo quedó esa necesidad y qué
insignias se movieron. Cada operación viaja además con un número de traza. Donaciones y Donadores
la escriben en sus logs, así que buscándola en Datadog se ve qué hizo cada uno. Logística e
Incentivos todavía no la propagan: una entrega o un procesamiento no se pueden seguir enteros.

Recorrido completo de una demostración, como admin:
```
/start
/soy_admin
/despertar
/reiniciar
/preparar
/donarcomo
/reportarentrega
/quejar
/procesardonador 1
```

## Notas de diseño
- Sin librerías externas de Telegram: usa la Bot API por HTTP (`getUpdates`/`sendMessage`) con
  `RestTemplate`, para evitar problemas de versiones.
- "Recibe un comando y devuelve una respuesta" (como pide la consigna). El estado que guarda es
  mínimo: el rol elegido por chat y el donador identificado.
- Bajo este esquema (long-polling), **solo una instancia** del bot puede correr a la vez por token.
- Conecta con los cuatro módulos del sistema DonaTrack: Donadores y Entidades, Donaciones, Logística e Incentivos.
