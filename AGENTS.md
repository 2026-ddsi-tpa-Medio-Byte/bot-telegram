# Bot de Telegram — DonaTrack

Interfaz de usuario del TP anual de DDS (K3003, UTN 2026). Permite operar el sistema desde el
celular. Es un **cliente HTTP** de los otros módulos: no tiene base de datos ni lógica de
negocio propia.

Corre **local**, como proceso de consola con long-polling contra la API de Telegram. Por ese
esquema, **solo una instancia puede correr a la vez con el mismo token**.

## Reglas que no se rompen

- Commits con la identidad del alumno (`JulianTettamanti`), sin `Co-Authored-By`.
- El token de BotFather nunca se commitea: va en `TELEGRAM_BOT_TOKEN`.
- La contraseña de admin tampoco: va en `BOT_ADMIN_PASSWORD`, sin valor por defecto. **Nunca se
  loguea el texto de un mensaje entrante**: uno de ellos es la contraseña, y los logs pueden
  terminar en Datadog. `LoginAdminTest` lo verifica con todos los logs prendidos.
- Ningún texto visible le enseña un comando con `;`: los menús y las ayudas muestran la forma
  guiada. El atajo en una línea sigue andando para quien lo conoce. Lo controla
  `DonaTrackBotTest.sinPuntoYComaEnNingunTexto`, que recorre todos los comandos de los menús.

## Arranque

```powershell
$env:TELEGRAM_BOT_TOKEN = "..."
$env:DONADORES_URL = "https://donadoresyentidadesv2.onrender.com"
$env:BOT_ADMIN_PASSWORD = "..."
mvn spring-boot:run
```

Arrancó bien cuando imprime «Bot de Telegram iniciado (long-polling)» y **la terminal queda
escuchando** sin volver al prompt. Sin token no arranca (es intencional). Sin
`BOT_ADMIN_PASSWORD` arranca, pero `/soy_admin` queda deshabilitado y lo dice: el log muestra
«Modo administrador: deshabilitado».

## El login de admin

`/soy_admin` pide la contraseña, y el mensaje siguiente **es** la contraseña, salvo `/cancelar`:
tratarlo como comando dejaría una contraseña que empieza con barra escrita en el chat. El bot lo
borra con `deleteMessage` y, si no puede, pide borrarlo a mano. Se compara el SHA-256 con
`MessageDigest.isEqual`. Tres fallidos bloquean ese chat cinco minutos; la sesión vence a las dos
horas sin uso. El reloj es un bean (`Clock`) para probar bloqueo y vencimiento sin dormir.

## Cómo está organizado

```
DonaTrackBot          despacho de comandos, login y manejo de sesión
AccesoAdmin           la contraseña, los intentos fallidos y el vencimiento
Sesion                lo que el bot recuerda de cada chat: rol, donador, último uso como admin
Formularios           las preguntas de cada alta, cambio y consulta guiada
Consultas             las consultas que juntan varios pedidos (necesidades, donador completo)
Impacto / Demo        el relato de cada operación; reiniciar, preparar y el estado
Formato               convierte el JSON de la API en texto legible
TelegramClient        getUpdates, sendMessage y deleteMessage
DonadoresApiClient    cliente del módulo Donadores
DonacionesApiClient   cliente del módulo Donaciones
LogisticaApiClient    cliente del módulo Logística (endpoints de /api, como el MCP)
IncentivosApiClient   cliente del módulo Incentivos
```

## Dos decisiones que importan

**Usa HTML, no Markdown.** En Markdown el guion bajo abre cursiva, así que un menú con
`/soy_donador` y `/soy_admin` se mostraba como `/soydonador` sin el guion; el usuario copiaba
eso y el bot no lo reconocía. Hay tests que fallan si vuelve a aparecer un asterisco en los
menús. Los datos que vienen de la API se escapan (`<`, `>`, `&`).

**El donador tiene sesión.** Entra con `/entrar <número>` o queda identificado al registrarse, y
a partir de ahí el bot lo recuerda: puede donar y ver lo suyo sin repetir quién es. La sesión es
por chat.

## Antes de terminar un cambio

```bash
mvn test
```

149 tests, 0 fallos. Incluyen que los comandos con guion bajo lleguen enteros al usuario, la
integración con los cuatro microservicios, cómo se traducen los errores de cada módulo, el login
de admin y que la contraseña no se filtre.
