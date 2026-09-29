# Pulse Studio Desktop 1.0.0 (Windows .exe)

Versión de escritorio de Pulse Studio (la app Android 1.6.4), misma interfaz y mismas funciones. Renderiza más rápido:
noticias → guion + 6 imágenes → miniatura con IA → editor de vídeo con subtítulos.

```
PulseStudio.exe (jpackage, lleva su propio Java)
 ├─ Backend Java 17+  ── servidor REST embebido en 127.0.0.1:<puerto aleatorio>
 │    lógica de negocio: noticias (HN + RSS), extracción del artículo, guion (Groq),
 │    fotos (Pexels, recortadas a 1080×1920), miniatura (Groq + Hugging Face),
 │    transcripción (Groq Whisper), claves cifradas y guardado de archivos
 └─ Ventana: Microsoft Edge en modo aplicación (Chrome si no hay Edge)
      Interfaz: index.html + styles.css + app.js (presentación)
      canvas, recorte de personas con MediaPipe y exportación MP4 con WebCodecs (todo local)
```

## 1. Requisitos (solo en el PC donde compilas)

- **JDK 17 o superior** (recomendado: Eclipse Temurin 21, https://adoptium.net). Tiene que ser un JDK, no un JRE, porque incluye `javac`, `jar` y `jpackage`.
  Para comprobarlo: `javac -version` y `jpackage --version` en `cmd`.
- **Solo si quieres un instalador**: WiX Toolset 3.14 (https://wixtoolset.org), añadido al PATH.
- El usuario final no necesita instalar nada: el `.exe` lleva su propio Java. La ventana usa Microsoft Edge, que ya viene con Windows 10 y 11.

## 2. Generar el `.exe` (paso a paso)

1. Descomprime `PulseStudioDesktop-1.0.0.zip`, por ejemplo en `C:\Proyectos\PulseStudioDesktop`.
2. Abre `cmd` en esa carpeta:
   ```bat
   cd C:\Proyectos\PulseStudioDesktop
   ```
3. Compila y empaqueta:
   ```bat
   build.bat
   ```
   Hace cuatro cosas: `javac` → copia la interfaz web dentro de las clases → `pulse-studio.jar` → `jpackage --type app-image`.
4. Resultado: **`dist\PulseStudio\PulseStudio.exe`**. Haz doble clic para abrirlo.
   Puedes copiar la carpeta `dist\PulseStudio` entera a otro PC o a un USB (versión portable).
5. Opcional, para un instalador con acceso directo en el escritorio y en el menú Inicio (requiere WiX):
   ```bat
   build.bat exe
   ```
   Resultado: `dist\PulseStudio-1.0.0.exe`. Se instala por usuario, sin permisos de administrador.

## 3. Primer uso

1. La primera vez se abre **Configuración**. Pega las claves API de Groq, Pexels y Hugging Face y pulsa **Guardar cambios**.
2. En **Carpeta de salida** elige dónde se guardan los packs. Por defecto es `Documentos\PulseStudio`.
3. Cada pack se guarda en su propia subcarpeta con la fecha y el titular:
   - `guion_tiktok.txt`
   - `imagen_1..6.jpg`
   - `miniatura_tiktok_*.jpg` / `.png`
   - `pulse_reel_*.mp4`

   El botón **📂 Mostrar en la carpeta** la abre en el Explorador.

## 4. Desarrollo (conectar frontend y backend)

```bat
run-dev.bat
```

Compila el backend y sirve la interfaz directamente desde `src\main\resources\web`. Puedes editar `index.html`, `styles.css` o `app.js` y pulsar **F5** en la ventana, sin recompilar.

Opciones útiles:

- `run-dev.bat --no-window --port=8080` arranca solo el backend. Imprime una URL con el token (`http://127.0.0.1:8080/#t=…`) que puedes abrir en Edge o Chrome, con las DevTools (F12).
- `--data=C:\ruta` usa otra carpeta de datos, útil para pruebas.

### Cómo se conectan las dos partes

- `PulseStudioApp` arranca el servidor en `127.0.0.1` con un puerto aleatorio y un **token de sesión** aleatorio. Después abre `msedge.exe --app=http://127.0.0.1:PUERTO/#t=TOKEN` con un perfil propio (`%LOCALAPPDATA%\PulseStudio\window-profile`).
- `app.js` (sección 1, «Cliente REST») lee el token del fragmento `#t=` y lo quita de la URL. Luego lo envía en cada llamada `fetch` en la cabecera `X-Pulse-Token`:
  ```js
  const data = await PulseApi.get('/api/news?q=chip&category=all&source=all&sort=newest');
  const script = await PulseApi.post('/api/script', {news, evidence});
  await PulsePlatform.saveBlob(blob, 'pulse_reel.mp4', {folder});   // POST /api/files (streaming)
  ```
- La interfaz envía `/api/ping` cada 10 s. El backend se cierra solo cuando cierras la ventana (termina el proceso de Edge o dejan de llegar los latidos). Solo hay una instancia: si abres el `.exe` otra vez, se abre otra ventana contra el mismo backend.

### Endpoints REST

| Método | Ruta | Función |
|---|---|---|
| GET / PUT | `/api/settings` | Estado de los servicios (configurado + últimos 4 caracteres), modelos y carpeta de salida. PUT guarda claves y ajustes |
| DELETE | `/api/settings/keys` | Borra todas las claves |
| POST | `/api/settings/test/{groq\|pexels\|hf}` | Prueba la conexión |
| POST | `/api/settings/choose-folder` | Selector de carpeta nativo de Windows |
| GET | `/api/news?q=&category=&source=&sort=` | Hacker News + RSS, con filtro por tema, deduplicado y ordenado |
| POST | `/api/article` | Texto limpio del artículo (lector Jina + heurísticas) |
| POST | `/api/script` | Guion TikTok + 6 búsquedas de imagen (Groq, JSON estricto) |
| POST | `/api/photo` | Una foto de Pexels convertida a JPEG 1080×1920; devuelve `/api/images/{id}` |
| GET | `/api/images/{id}` | Imagen en caché |
| POST | `/api/thumbnail/brief` | Titular, palabra destacada y prompt visual (Groq) |
| POST | `/api/thumbnail/background` | Fondo IA (Hugging Face: hf-inference / nscale / together) |
| POST | `/api/transcribe?name=&lang=&context=` | Audio → palabras con tiempos (Groq Whisper) |
| POST | `/api/files?name=&folder=` | Guarda un archivo en la carpeta de salida (streaming, hasta 1 GB) |
| POST | `/api/system/reveal` · `/api/system/open-url` | Mostrar en el Explorador · abrir un enlace en el navegador |
| GET | `/api/ping` | Latido |

### Seguridad

- El servidor solo escucha en `127.0.0.1`. Comprueba que la cabecera Host sea exactamente la suya, lo que evita el *DNS rebinding*.
- Toda la API exige el token, que se compara en tiempo constante. Rechaza peticiones de otros orígenes (`Origin` y `Sec-Fetch-Site`).
- CSP estricta (`connect-src 'self'`): la interfaz no puede conectarse a ningún servidor externo.
- Las claves se cifran con **AES-256-GCM** en `%LOCALAPPDATA%\PulseStudio\credentials.aesgcm`. La clave de cifrado está en un archivo aparte con permisos solo para tu usuario de Windows. Las claves nunca se envían a la interfaz.
- Los nombres de archivo y de carpeta se validan con listas blancas y las rutas no pueden salir de la carpeta de salida.
- Las llamadas salientes solo pueden ir a los hosts de los servicios, sin seguir redirecciones. El RSS se analiza sin DTD ni entidades externas.

## 5. Estructura

```
PulseStudioDesktop/
├─ build.bat · run-dev.bat · build.sh · pom.xml (opcional, para el IDE)
├─ packaging/PulseStudio.ico, PulseStudio.png
├─ src/main/java/com/pulsestudio/desktop/
│  ├─ PulseStudioApp.java      entrada: instancia única, servidor, ventana, ciclo de vida
│  ├─ WindowLauncher.java      Edge/Chrome en modo app
│  ├─ server/                  HTTP embebido: LocalServer, Router, Request, StaticFiles, Json
│  ├─ api/                     controladores REST: Settings, News, Content, Media, Files, System
│  ├─ service/                 lógica de negocio: News, Article, Groq, Script, Thumbnail, Pexels,
│  │                           HuggingFace, ImageCache, File, System, Text
│  ├─ store/                   AppPaths, SecretStore (AES-GCM), SettingsStore
│  └─ net/                     WebClient (hosts permitidos, límites), Endpoints
├─ src/main/resources/web/     index.html · styles.css · app.js · favicon.png
│  └─ vendor/                  MediaPipe Tasks Vision 0.10.35 + Mediabunny (locales)
├─ src/test/java/…             pruebas JUnit
└─ tools/e2e/                  prueba de extremo a extremo (backend real + Chromium)
```

## 6. Pruebas

- **JUnit** (backend): `mvn test`, o desde el IDE.
- **Extremo a extremo**: los servicios externos se simulan. Se ejecutan el backend Java real y Chromium, y se recorren los 5 pasos: guardado de archivos, recorte con MediaPipe, subtítulos y exportación a MP4.
  ```
  build.sh jar
  npm i playwright && npx playwright install chromium
  node tools/e2e/desktop.e2e.cjs
  ```

## 7. Problemas frecuentes

- **`jpackage` no se reconoce**: el PATH apunta a un JRE o a un JDK anterior a la versión 14. Instala Temurin 21 y abre un `cmd` nuevo.
- **No se abre la ventana**: revisa `%LOCALAPPDATA%\PulseStudio\pulse-studio.log`. Sin Edge ni Chrome, se usa el navegador predeterminado.
- **Windows SmartScreen avisa al abrir el `.exe`**: es normal en ejecutables sin firmar. Pulsa «Más información» → «Ejecutar de todas formas». Para distribuirlo, fírmalo con `signtool` y un certificado de firma de código.
- **Proxy corporativo**: el backend usa el proxy configurado en Windows (`-Djava.net.useSystemProxies=true`).
