# Mediabunny (vendorizado)

- Proyecto: https://github.com/Vanilagy/mediabunny (Vanilagy y colaboradores), licencia **MPL-2.0** (ver `LICENSE`).
- Versión: **v1.60.0**, commit `359e4e4eee43bf968551e03ddc7280f9c69d655a`, sin modificaciones.
- `mediabunny.js` es el bundle ESM minificado de `src/index.ts`, generado con esbuild 0.27:

  ```sh
  git clone --depth 1 --branch v1.60.0 https://github.com/Vanilagy/mediabunny
  cd mediabunny
  npx esbuild src/index.ts --bundle --format=esm --platform=browser --target=es2021 --minify \
    --legal-comments=none --log-override:import-is-undefined=silent --outfile=mediabunny.js
  ```

- SHA-256 del archivo entregado: `6196c89e06b9709b6779c4d12f751bc544d8610f4562d8330e8be0d3c7ea9f9c`
  (el banner de la primera línea incluye versión, commit y licencia).
- Uso en Pulse Studio: exportación del editor de vídeo fotograma a fotograma (WebCodecs → MP4). Solo se sirve
  `mediabunny.js` (lista exacta en `SecurityPolicy.java`).
