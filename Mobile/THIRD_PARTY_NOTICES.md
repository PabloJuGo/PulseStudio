# Componentes de terceros

- Gradle wrapper: Gradle, Inc. y colaboradores, Apache License 2.0.
- AndroidX Core y WebKit: Android Open Source Project, Apache License 2.0.
- JUnit, AndroidX Test, jsdom y Playwright: dependencias de pruebas; no forman parte de la interfaz HTML final.
- MediaPipe Tasks Vision (`@mediapipe/tasks-vision` 0.10.35: `vision_bundle.js` y `wasm/*`): Google LLC, Apache License 2.0. Se instala con `tools/fetch-mediapipe.cjs` en `app/src/main/assets/www/vendor/mediapipe/` y se empaqueta en la APK.
- Modelo MediaPipe Selfie Segmenter (`selfie_segmenter.tflite`): Google LLC, Apache License 2.0 (tarjeta del modelo de MediaPipe).

## Servicios externos

Pulse Studio integra o consulta servicios con sus propias condiciones de uso: Groq, Pexels, Hugging Face (Inference Providers y los proveedores que enruta, y la licencia del modelo de imagen elegido, p. ej. FLUX.1 [schnell] Apache-2.0), Jina Reader, Hacker News/Algolia y las fuentes RSS configuradas.

Las fotografías de Pexels mantienen sus condiciones y atribución correspondiente. No se incluyen API keys ni fotografías de terceros dentro del proyecto.

`source/index.v1.3.html` es una copia histórica que todavía contiene el antiguo bloque de JSZip/pako y sus avisos originales. **La interfaz actual 1.5.0 ya no carga ni utiliza JSZip ni pako.**
