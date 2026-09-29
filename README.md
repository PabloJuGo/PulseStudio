# Pulse Studio

**De una noticia a un pack listo para TikTok/Reels en minutos:** guion, 6 imágenes verticales, miniatura con IA y un editor de vídeo con subtítulos automáticos. Disponible para **Android** y **Windows**.

[![Android](https://img.shields.io/badge/Android-1.6.4-3DDC84?logo=android&logoColor=white)](Mobile/)
[![Windows](https://img.shields.io/badge/Windows-1.0.0-0078D4?logo=windows&logoColor=white)](Desktop/)

## ¿Qué hace?

1. **Noticia.** Busca en Hacker News, HackRead, KrebsOnSecurity y Xataka, o pega tu propia noticia.
2. **Generar.** Extrae y limpia el artículo, escribe el guion con Groq (en español de España) y busca 6 fotos verticales en Pexels.
3. **Exportar.** Copia el guion, descárgalo en TXT y guarda las imágenes en 1080 × 1920.
4. **Miniatura con IA.** Groq propone el titular y el prompt, Hugging Face genera el fondo y la app te recorta y compone la portada.
5. **Editor de vídeo.** Tu vídeo narrado sobre las 6 imágenes, con el fondo eliminado, subtítulos automáticos (Groq Whisper) y exportación a MP4 de 30 fps exactos.

## Privacidad

- **Tu foto y tu vídeo nunca salen de tu dispositivo.** El recorte (MediaPipe) y el montaje del vídeo (WebCodecs) son locales.
- **No hay servidor propio.** La app llama directamente a las APIs con **tus** claves, que se guardan cifradas (AES-256-GCM).
- A Groq solo se envía el texto de la noticia, y el audio si generas subtítulos. A Hugging Face solo se envía el prompt visual.

## Versiones

| Versión | Tecnología | Descargar | Compilar desde el código |
|---|---|---|---|
| [**Android 1.6.4**](Mobile/) | Java + WebView, Android 7.0+ | [APK en Releases](../../releases) | [Guía](Mobile/README.md#2-compilar-e-instalar-paso-a-paso) |
| [**Windows 1.0.0**](Desktop/) | Java 17+ y ventana de Edge, `.exe` con jpackage | [`.exe` en Releases](../../releases) | [Guía](Desktop/README.md#2-generar-el-exe-paso-a-paso) |

## Descargas

Cada versión publicada de Android y de Windows tiene su binario compilado en [**Releases**](../../releases):

- **Android:** descarga el `.apk` en el móvil e instálalo. Android te pedirá permitir la instalación desde fuera de Play Store. Necesita Android 7.0 o superior.
- **Windows:** descarga el `.zip`, descomprímelo y abre `PulseStudio.exe`. No hace falta instalar Java: lleva el suyo. Necesita Windows 10 u 11 con Microsoft Edge.

Si prefieres no fiarte de binarios, compílalos tú mismo con las guías de cada carpeta.

## Cómo funciona

Las dos versiones comparten la misma interfaz web y las mismas funciones. Cambia el envoltorio nativo:

```
                 Interfaz web (HTML + CSS + JS vanilla)
                 canvas · MediaPipe · WebCodecs + Mediabunny
                       │                        │
        ┌──────────────┘                        └───────────────┐
   Android (WebView)                                   Windows (ventana Edge)
   Java: puente PulseNative,                           Java: servidor REST local
   Keystore, MediaStore/SAF                            en 127.0.0.1, token de sesión
```

| | Android | Windows |
|---|---|---|
| Lógica de negocio | En el JavaScript de la página | En el backend Java (REST local) |
| Claves | AES-256-GCM + Android Keystore | AES-256-GCM en `%LOCALAPPDATA%` |
| Guardado | Galería (MediaStore) o selector de documentos | Carpeta de salida (`Documentos\PulseStudio`) |
| Empaquetado | APK (Android Studio / Gradle) | `PulseStudio.exe` (jpackage, incluye su Java) |

## Servicios que necesitas

| Servicio | Para qué | ¿Obligatorio? |
|---|---|---|
| [Groq](https://console.groq.com) | Guion, titular de la miniatura y subtítulos | Sí |
| [Pexels](https://www.pexels.com/api/) | Fotografías | Sí |
| [Hugging Face](https://huggingface.co/settings/tokens) | Fondo IA de la miniatura | No (puedes usar una imagen del pack) |

Los tres tienen capa gratuita. Se configuran dentro de la app, en **⚙ Configuración**.

## Estructura del repositorio

```
PulseStudio-GitHub/
├─ Mobile/     proyecto de Android Studio (app 1.6.4)
├─ Desktop/    versión de Windows (backend Java + interfaz)
└─ README.md
```

Cada carpeta tiene su propio README con los requisitos, los pasos de compilación, la arquitectura, la seguridad y las pruebas.

## Documentación

- [`Mobile/README.md`](Mobile/README.md): compilar, flujo de uso, puente nativo, seguridad y pruebas.
- [`Desktop/README.md`](Desktop/README.md): generar el `.exe`, endpoints REST, seguridad y problemas frecuentes.
- [`Mobile/docs/`](Mobile/docs/): arquitectura, changelogs y validaciones.

## Estado y limitaciones

- La versión de Windows se ha probado en un Windows real y funciona correctamente. En escritorio el procesado y la exportación de vídeo son más rápidos y de mejor calidad que en el móvil.
- Las pruebas automáticas de Android se ejecutan en el PC, no en un dispositivo.
- El `.exe` no está firmado, así que Windows SmartScreen puede mostrar un aviso al abrirlo: pulsa «Más información» → «Ejecutar de todas formas».
- Revisa siempre el contenido generado antes de publicarlo: fechas, cifras, nombres y adecuación de las fotografías.

## Autor

**Pablo Juzgado**

## Licencias y créditos

- **Licencia del proyecto:** [MIT](LICENSE) © 2026 Pablo Juzgado.
- Componentes de terceros: MediaPipe Tasks Vision y el modelo Selfie Segmenter (Apache-2.0), Mediabunny (MPL-2.0), AndroidX y Gradle (Apache-2.0). Detalle en [`Mobile/THIRD_PARTY_NOTICES.md`](Mobile/THIRD_PARTY_NOTICES.md).
- Las fotografías de Pexels mantienen sus propias condiciones y atribución. Groq, Hugging Face, Jina Reader y las fuentes de noticias tienen sus propios términos de uso.
