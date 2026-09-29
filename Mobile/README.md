# Pulse Studio Android 1.6.4

App Android de Pulse Studio: convierte una noticia en un pack para TikTok/Reels con
noticias → guion + 6 imágenes → miniatura con IA → editor de vídeo con subtítulos.

Toda la interfaz vive en un único archivo, `app/src/main/assets/www/index.html`, y se ejecuta dentro de una WebView segura. No hay servidor propio: la app llama directamente a APIs públicas con las claves del usuario.

```
Pulse Studio (APK, Java + WebView)
 ├─ Capa nativa Java (com.pulsestudio.app)
 │    MainActivity: WebView, puente PulseNative, guardado en galería (MediaStore / SAF)
 │    SecretStore: claves cifradas con AES-256-GCM + Android Keystore
 │    SecurityPolicy: lista blanca de orígenes, recursos locales y hosts de red
 └─ Interfaz web (assets/www/index.html, JS vanilla, sin servidor)
      llamadas a Groq, Pexels, Hugging Face, Jina Reader, Hacker News y RSS
      recorte de personas con MediaPipe y exportación MP4 con WebCodecs (todo local)
```

## 1. Requisitos (solo en el PC donde compilas)

- **Android Studio** reciente, con Android SDK Platform 36 y Build Tools 35.0.0.
- **JDK 17 o 21** como Gradle JDK.
- **Node.js 18+** con conexión a internet, para descargar el motor de recorte (MediaPipe, unos 19,5 MB) una sola vez.
- **Móvil o emulador** con Android 7.0 (API 24) o superior y **Android System WebView 97+** (necesario para WebAssembly bajo CSP).
- La app solo pide el permiso `INTERNET`. No necesita cámara, micrófono ni acceso general al almacenamiento.

## 2. Compilar e instalar (paso a paso)

1. **Una sola vez**, instala el motor local de recorte desde la raíz del proyecto:
   ```sh
   node tools/fetch-mediapipe.cjs
   ```
   El script descarga `@mediapipe/tasks-vision@0.10.35` y el modelo `selfie_segmenter.tflite`, verifica su integridad y los copia en `app/src/main/assets/www/vendor/mediapipe/`. Si faltan, Gradle no empaqueta la APK y muestra este mismo comando.
2. Abre la carpeta raíz del proyecto con **Open** en Android Studio.
3. Deja que Android Studio genere `local.properties` con la ruta de tu SDK.
4. Sincroniza Gradle. La primera sincronización necesita conexión para descargar Gradle y las dependencias.
5. Genera una APK de depuración:
   ```powershell
   .\gradlew.bat assembleDebug testDebugUnitTest lintDebug
   ```
   En macOS/Linux:
   ```sh
   chmod +x gradlew
   ./gradlew assembleDebug testDebugUnitTest lintDebug
   ```
6. Resultado: **`app/build/outputs/apk/debug/app-debug.apk`**. Instálala en el móvil (depuración USB) o ejecuta la app directamente desde Android Studio con **Run**.

### Versiones principales

| Componente | Versión |
|---|---|
| App | 1.6.4 / versionCode 22 |
| AGP | 8.13.2 |
| Gradle wrapper | 8.14.3 |
| Java | 17 |
| AndroidX WebKit | 1.16.0 |
| AndroidX Core | 1.17.0 |
| Android mínimo | API 24 / Android 7.0 |
| compileSdk / targetSdk | 36 / 36 |
| MediaPipe Tasks Vision | 0.10.35 (local, vía `tools/fetch-mediapipe.cjs`) |
| Mediabunny | 1.60.0 (incluido en `assets/www/vendor/mediabunny/`) |
| Android System WebView | 97 o posterior |

## 3. Primer uso

1. Pulsa **⚙ Configuración** en la cabecera y pega tus claves API:
   - **Groq**: clave API, modelo y **Probar conexión**.
   - **Pexels**: clave API y **Probar conexión**.
   - **Hugging Face** (opcional, para el fondo IA de la miniatura): token *Fine-grained* con el permiso «Make calls to Inference Providers», modelo (FLUX.1-schnell, SDXL o SD3 Medium) y **Probar conexión**. Consume los créditos gratuitos mensuales de tu cuenta.
2. Los indicadores muestran `Sin configurar`, `Configurado`, `Comprobando…`, `Conectado` o `Error`. Puedes mostrar u ocultar temporalmente cada clave.
3. Pulsa **Guardar cambios** para guardarlas cifradas en el móvil. **Eliminar todas las claves** las borra todas.
4. Dónde se guardan los archivos:

   | Resultado | Destino en Android 10+ |
   |---|---|
   | Miniatura (JPG/PNG) | `Imágenes/PulseStudio` |
   | Vídeo MP4 | `Películas/PulseStudio` |
   | Guion TXT e imágenes individuales | Selector de documentos (SAF) |
   | Las 6 imágenes | Un único selector de carpeta; se crea `PulseStudio_<fecha>` |

## 4. Flujo de uso

### Paso 1 · Noticia

Busca un tema, selecciona una categoría y, si hace falta, abre los filtros de fuente y orden. También puedes introducir una noticia manualmente.

### Paso 2 · Generar

Comprueba la noticia seleccionada y pulsa **Generar pack de TikTok**. La app recupera y limpia el artículo, genera el guion con Groq y obtiene seis fotografías verticales de Pexels.

El texto del artículo está en un bloque desplegable. Si una web bloquea la extracción, puedes pegar allí el texto manualmente.

### Paso 3 · Exportar

- **Guion**: editable, botón `⧉ Copiar` y descarga TXT.
- **Imágenes**: seis JPEG de 1080 × 1920, descarga individual y **Descargar las 6 imágenes**.

### Paso 4 · Miniatura con IA (opcional)

1. Elige tu foto: el recorte se hace en el móvil y se ve al momento.
2. Pulsa **Crear miniatura**: Groq propone el titular y el prompt visual en inglés, Hugging Face genera el fondo y la app compone una portada de 1080 × 1920.
3. Ajusta el titular, la palabra en color, el tamaño y la posición.
4. Guarda en **JPG** o **PNG**.

Sin token de Hugging Face, elige como fondo una de las 6 imágenes del pack.

### Paso 5 · Editor de vídeo (opcional)

1. Elige tu vídeo narrado (MP4 o WebM, hasta 3 minutos).
2. Ajusta cuándo entra cada una de las 6 imágenes: arrastra el control o pulsa **◎ Aquí** durante la reproducción. Para cambiar el **orden**, mantén pulsada una imagen de la barra superior y arrástrala a otra posición.
3. Elige tamaño y posición del sujeto, y si quieres eliminar el fondo del vídeo.
4. **Subtítulos automáticos** (opcional): pulsa **Generar subtítulos**. La app extrae el audio en el móvil y Groq (Whisper) lo transcribe con tu misma clave. Después puedes elegir tipo de letra, estilo, color, tamaño, posición y palabras por subtítulo, y corregir los textos. Quedan grabados en el vídeo exportado.
5. **Exportar vídeo** procesa el vídeo fotograma a fotograma y genera un MP4 a 30 fps exactos con el audio original. En móviles lentos solo tarda más. La pantalla se mantiene encendida; no cambies de app hasta que termine.
6. **Guardar en la galería.**

## 5. Desarrollo

### Cómo se conectan las dos partes

- La interfaz (`index.html`) se sirve con `WebViewAssetLoader` desde el origen seguro `https://appassets.androidplatform.net/assets/www/index.html`. Además de esa página, solo se sirven, por lista exacta, los archivos locales de MediaPipe y Mediabunny.
- Las operaciones privilegiadas (claves, guardado de archivos, enlaces externos) pasan por un puente `PulseNative` basado en `WebMessageListener`. No se usa `addJavascriptInterface`.
- Las llamadas de red las hace el JavaScript de la página, y `SecurityPolicy` decide qué hosts se permiten.

### Puente nativo `PulseNative`

| Operación | Función |
|---|---|
| `keys.load` · `keys.save` · `keys.clear` | Lee, guarda o borra las claves cifradas |
| `screen.keepOn` | Mantiene la pantalla encendida durante la exportación |
| `external.open` | Abre un enlace fuera de la WebView |
| `export.begin` · `chunk` · `finish` · `abort` | Guarda TXT, JPG/PNG, MP4 o WebM por trozos |
| `images.begin` · `chunk` · `finish` · `abort` | Guarda las 6 imágenes en una carpeta |

Límites: mensajes de hasta 70 000 caracteres, trozos de hasta 65 536 caracteres Base64, texto e imágenes hasta 64 MiB, vídeo hasta 256 MiB. Los nombres de archivo solo admiten `a-z A-Z 0-9 _ -` (1 a 100 caracteres) y las extensiones txt, jpg, jpeg, png, mp4 y webm. El lote de imágenes admite 6 imágenes y 64 MiB en total.

### Servicios externos permitidos

| Servicio | Host | Uso |
|---|---|---|
| Groq | `api.groq.com` | Guion, titular de la miniatura y transcripción (Whisper `whisper-large-v3-turbo`) |
| Pexels | `api.pexels.com`, `images.pexels.com` | Fotografías verticales |
| Hugging Face | `router.huggingface.co` | Fondo IA (hf-inference / nscale / together) |
| Hugging Face Hub | `huggingface.co` | Solo `/api/models/*` y `/api/whoami-v2` (proveedor y prueba del token) |
| Jina Reader | `r.jina.ai` | Lectura y limpieza de artículos |
| Hacker News | `hn.algolia.com` | Noticias |
| RSS bridge | `pulse-studio-rss.elteclas1312.workers.dev` (`/feed`) | HackRead, KrebsOnSecurity y Xataka |

El guion se genera en español de España (`es-ES`) aunque la noticia original esté en otro idioma. Revisa siempre el contenido antes de publicarlo: fechas, cifras, nombres y adecuación de las fotografías.

### Seguridad

- La foto y el vídeo del usuario **nunca salen del móvil**: el recorte (MediaPipe) y la composición (Canvas + WebCodecs) son locales. A Groq solo se envía el texto de la noticia (y el audio para los subtítulos) y a Hugging Face solo el prompt visual.
- Las claves se cifran con **AES-256-GCM** y una clave no exportable de Android Keystore (alias `pulse.studio.credentials.v1`), en `credentials.aesgcm` dentro de `noBackupFilesDir`. No se guardan en `localStorage`, cookies ni archivos exportados, y `allowBackup` está desactivado.
- `WebViewAssetLoader` sobre origen interno y lista exacta de recursos locales (nunca un prefijo de carpeta).
- Solo se permiten conexiones HTTPS a los hosts de la tabla anterior; el tráfico en claro está desactivado.
- Los enlaces externos se abren fuera de la WebView y se bloquean los esquemas no admitidos.
- CSP con `'self'`, `'wasm-unsafe-eval'` (WebAssembly, no `eval`) y los hashes de los tres scripts internos. Después de modificar JavaScript, regenera los hashes:
  ```sh
  node tools/update-csp.cjs
  ```

## 6. Estructura del proyecto

```
PulseStudioAndroid/
├─ build.gradle · settings.gradle · gradlew · gradlew.bat
├─ app/
│  ├─ build.gradle                versión 1.6.4, versionCode 22, minSdk 24, target 36
│  └─ src/
│     ├─ main/java/com/pulsestudio/app/
│     │  ├─ MainActivity.java     WebView, puente PulseNative, guardado en galería
│     │  ├─ SecretStore.java      claves cifradas (AES-GCM + Keystore)
│     │  ├─ SecurityPolicy.java   orígenes, recursos y hosts permitidos
│     │  ├─ ExportBuffer.java     recepción por trozos de TXT, imágenes y vídeo
│     │  └─ ImageBatchBuffer.java lote de las 6 imágenes
│     ├─ main/assets/www/         index.html
│     │  └─ vendor/               MediaPipe Tasks Vision 0.10.35 + Mediabunny 1.60.0 (locales)
│     └─ test/java/…              pruebas JVM
├─ tools/                         fetch-mediapipe.cjs · update-csp.cjs
└─ docs/                          arquitectura, changelogs y validaciones
```

## 7. Historial de versiones

| Versión | versionCode | Cambios |
|---|---|---|
| 1.6.4 | 22 | Corrige «Array buffer allocation failed» al exportar en móviles reales: el MP4 (y el audio de los subtítulos) se escribe por trozos de 4 MB en `Blob` |
| 1.6.3 | 21 | Reordenar las 6 imágenes arrastrando; subtítulos automáticos con Groq Whisper y estilos configurables |
| 1.6.2 | 20 | Exportación fotograma a fotograma (WebCodecs + Mediabunny): MP4 a 30 fps exactos con audio original; pantalla siempre encendida |
| 1.6.1 | 19 | Recorte de persona más robusto: 4 configuraciones de MediaPipe en cascada (GPU/CPU × máscara de confianza/categoría) |
| 1.6.0 | – | Miniatura con IA, editor de vídeo con eliminación de fondo local y Hugging Face en Configuración |
| 1.5.0 | – | Rediseño de UI/UX en tres pasos, exportación optimizada para móvil (TXT, imágenes, lote de 6) sin ZIP |

## 8. Problemas frecuentes

- **Gradle no empaqueta la APK y pide `fetch-mediapipe`**: falta el motor de recorte. Ejecuta `node tools/fetch-mediapipe.cjs` (Node 18+ y conexión).
- **«No se detecta ninguna persona» aunque salgas en la foto**: la app prueba sola cuatro configuraciones (GPU y CPU). Usa una foto nítida, con buena luz y la persona de frente.
- **«Array buffer allocation failed» al exportar**: corregido en la 1.6.4. Comprueba que instalas esta versión.
- **La exportación no arranca o el recorte falla**: actualiza *Android System WebView* (mínimo 97) desde Google Play.
- **Una web bloquea la extracción del artículo**: pega el texto manualmente en el bloque desplegable del paso 2.
- **Groq, Pexels o Hugging Face dan error**: usa **Probar conexión** en Configuración; en Hugging Face comprueba que el token tenga el permiso «Make calls to Inference Providers».