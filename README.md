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
| `BOT_ADMIN_PASSWORD` |  | Contraseña del modo administrador. **Sin esto `/soy_admin` está deshabilitado.** No tiene default a propósito: nunca va en `application.properties` ni en el repo. |
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
$env:BOT_ADMIN_PASSWORD = "<la contraseña que elijas>"
mvn spring-boot:run
```

En CMD la sintaxis es distinta (`set VARIABLE=valor`, sin comillas y una por línea).

El arranque es correcto cuando aparece esto y **la terminal queda escuchando**, sin volver al prompt:

```
Bot de Telegram iniciado (long-polling). Escuchando mensajes...
Modo administrador: habilitado
```

Si dice `deshabilitado (falta BOT_ADMIN_PASSWORD)`, el bot anda pero nadie puede entrar como
admin: cortalo, seteá la variable en esa misma terminal y volvé a arrancarlo.

Luego, en Telegram, buscá tu bot y mandá `/start`. Para detenerlo, Ctrl+C.

## Entrar como admin

```
vos  /soy_admin
bot  🔐 Acceso de administrador. Escribí la contraseña. Apenas la leo, borro tu mensaje…
vos  ********            ← el bot lo borra del chat
bot  ✅ Entraste como administrador. La sesión dura hasta /salir o 2 horas sin usar el bot.
```

- El mensaje que sigue a `/soy_admin` **es la contraseña**, sea lo que sea: solo `/cancelar` vuelve
  atrás. El bot lo borra del chat apenas lo lee; si Telegram no lo deja, lo avisa para que lo
  borres a mano.
- **3 intentos fallidos bloquean ese chat 5 minutos.** Cancelar no devuelve los intentos.
- La sesión **vence a las 2 horas sin usar el bot** y se cierra con `/salir`. Si venció, el bot lo
  dice y hay que volver a entrar con `/soy_admin`.
- La contraseña no se escribe en ningún log (pueden terminar en Datadog) ni se repite en ningún
  mensaje. Hay un test que lo verifica con todos los logs prendidos.

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

En los cambios (`/editarentidad`, `/modificarnecesidad`) un `-` deja ese dato como está.

> Los mismos comandos aceptan todos los datos en una línea separados por `;`, que es más rápido
> para quien ya lo conoce: `/donar 1;10;Diez kilos de arroz`. Las dos formas hacen lo mismo. Los
> menús y las ayudas muestran solo la forma guiada, y hay un test que lo controla.

### Donador

- `/entrar <número>` — si ya está registrado
- `/registrarse` — la primera vez; queda identificado automáticamente
- `/donar` — qué, cuánto y para qué
- `/productos` — qué se puede donar
- `/necesidades` — qué están pidiendo las entidades
- `/misdonaciones` — las suyas, con el estado de cada una
- `/perfil` — sus datos
- `/misestadisticas` — categoría e insignias
- `/puedodonar` — si tiene la cuenta habilitada
- `/salir`

### Admin: consultar

Sin número, el bot pregunta cuál; con número va directo (`/donador 3`). Las que muestran datos
personales de los donadores (`/donadores`, `/donador`, `/estadisticas`, `/donaciones`,
`/donacion`, `/progreso`) son solo para el admin.

| Módulo | Comandos |
|---|---|
| Donaciones | `/donaciones` (todas o las de un donador) · `/donacion` · `/productos` · `/identificadores` |
| Donadores y entidades | `/donadores` · `/donador` (con sus quejas y si puede donar) · `/estadisticas` · `/entidades` · `/entidad` · `/necesidades` (las pendientes, por producto) · `/necesidad` (con cuánto le falta) |
| Logística | `/depositos` (capacidad y stock) · `/stock` (un producto, depósito por depósito) · `/asignaciones` (paquetes pendientes) · `/paquete` (el número de donación o `paq-solicitud-…`) |
| Incentivos | `/insignias` · `/misiones` · `/progreso` (insignias y misión en curso de un donador) |

De Logística se usan los mismos endpoints que el MCP: `/api/depositos` (el `/depositos` de
integración trae el stock vacío), `/stock/{id}/detalle`, `/api/asignaciones?estado=ASIGNADA` y
`/api/asignaciones/paquetes/{id}`. De Incentivos, `/donadores/{id}/insignias` y
`/donadores/{id}/mision-actual`, donde un 404 quiere decir «no tiene».

`/donacion` muestra el estado actual: Donaciones no guarda por qué estados pasó una donación.

### Admin: operar

**Donaciones**

- `/donarcomo` — donar a nombre de otro, sin cambiar de rol
- `/quejar` — reclamar por una donación entregada
- `/crearproducto` · `/crearidentificador` — el catálogo

**Donadores y entidades**

- `/estadodonador` — verificado, sospechoso o baneado
- `/categoriadonador` — ofrece las categorías, de ocasional a revolucionario
- `/crearentidad` · `/editarentidad`
- `/altanecesidad` · `/modificarnecesidad` · `/borrarnecesidad`

**Logística**

- `/reportarentrega` — el paquete no se pide: Logística lo nombra `paq-` más el número de la
  donación
- `/creardeposito` · `/algoritmo` — el criterio con el que un depósito elige a qué necesidad le
  asigna cada donación: sub-atendidos o prioridad por score. Se configura por `/api`, donde
  Logística los llama `SUBATENDIDOS` y `PRIOSCORE`; el bot traduce cualquiera de las dos formas

**Incentivos**

- `/procesardonador` — que Incentivos evalúe la misión de un donador
- `/crearinsignia` · `/crearmision`

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
/soy_admin        (y la contraseña)
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
  mínimo: el rol elegido por chat, el donador identificado, la última vez que se usó la sesión
  de admin y los intentos fallidos de contraseña. Todo en memoria: al reiniciar el bot, hay que
  volver a entrar.
- Bajo este esquema (long-polling), **solo una instancia** del bot puede correr a la vez por token.
- Conecta con los cuatro módulos del sistema DonaTrack: Donadores y Entidades, Donaciones, Logística e Incentivos.
