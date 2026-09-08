# Bot de Telegram — DonaTrack

Interfaz de usuario del TP anual de DDS (K3003, UTN 2026). Permite operar el sistema desde el
celular. Es un **cliente HTTP** de los otros módulos: no tiene base de datos ni lógica de
negocio propia.

Corre **local**, como proceso de consola con long-polling contra la API de Telegram. Por ese
esquema, **solo una instancia puede correr a la vez con el mismo token**.

## Reglas que no se rompen

- Commits con la identidad del alumno (`JulianTettamanti`), sin `Co-Authored-By`.
- El token de BotFather nunca se commitea: va en `TELEGRAM_BOT_TOKEN`.

## Arranque

```powershell
$env:TELEGRAM_BOT_TOKEN = "..."
$env:DONADORES_URL = "https://donadoresyentidadesv2.onrender.com"
mvn spring-boot:run
```

Arrancó bien cuando imprime «Bot de Telegram iniciado (long-polling)» y **la terminal queda
escuchando** sin volver al prompt. Sin token no arranca (es intencional).

## Cómo está organizado

```
DonaTrackBot          despacho de comandos y manejo de sesión
Sesion                lo que el bot recuerda de cada chat: rol y donador identificado
Formato               convierte el JSON de la API en texto legible
TelegramClient        getUpdates y sendMessage
DonadoresApiClient    cliente del módulo Donadores
DonacionesApiClient   cliente del módulo Donaciones
LogisticaApiClient    cliente del módulo Logística
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

40 tests, 0 fallos. Incluyen que los comandos con guion bajo lleguen enteros al usuario y la
integración con los cuatro microservicios.
