#!/usr/bin/env node
// Instala el motor local de recorte (MediaPipe Tasks Vision + modelo Selfie Segmenter) en
// app/src/main/assets/www/vendor/mediapipe/. Se ejecuta UNA vez antes de compilar la APK.
// Sin dependencias: Node 18+. Todo queda empaquetado en la APK y funciona sin conexión.
//
//   node tools/fetch-mediapipe.cjs                  # descarga oficial (npm + Google) con verificación
//   node tools/fetch-mediapipe.cjs --from-dir <ruta a node_modules/@mediapipe/tasks-vision> --model <selfie_segmenter.tflite>
//
// Seguridad de la cadena de suministro:
// - Versión FIJA de @mediapipe/tasks-vision y verificación de su integridad SHA-512 publicada en npm.
// - Modelo verificado por SHA-256. Solo se copian los archivos que MainActivity/SecurityPolicy permiten.
'use strict';
const fs = require('node:fs'), path = require('node:path'), zlib = require('node:zlib'), crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');

const VERSION = '0.10.35';
const TARBALL = `https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-${VERSION}.tgz`;
const INTEGRITY = 'sha512-HOvadwVRE6JC+45nyYhmnywnr5h/J8KZvOeUNVOG9q/0875pZgItznFB9bRTvLc264YSJqiZ1NsIpCStJw/egg==';
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite';
const MODEL_SHA256 = '191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b';
const OUT = path.join(__dirname, '../app/src/main/assets/www/vendor/mediapipe');
// Archivo del paquete npm → ruta publicada dentro de la APK (debe coincidir con SecurityPolicy.java).
const FILES = {
  'vision_bundle.mjs': 'vision_bundle.js',
  'wasm/vision_wasm_internal.js': 'wasm/vision_wasm_internal.js',
  'wasm/vision_wasm_internal.wasm': 'wasm/vision_wasm_internal.wasm',
  'wasm/vision_wasm_nosimd_internal.js': 'wasm/vision_wasm_nosimd_internal.js',
  'wasm/vision_wasm_nosimd_internal.wasm': 'wasm/vision_wasm_nosimd_internal.wasm'
};

const args = process.argv.slice(2);
const opt = name => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
const sha = (algo, buf, enc = 'hex') => crypto.createHash(algo).update(buf).digest(enc);
const fail = msg => { console.error('\n✖ ' + msg + '\n'); process.exit(1); };

async function download(url) {
  if (typeof fetch !== 'function') fail('Se necesita Node 18 o posterior.');
  const res = await fetch(url, {redirect: 'follow'});
  if (!res.ok) throw new Error(`HTTP ${res.status} al descargar ${url}`);
  return Buffer.from(await res.arrayBuffer());
}
function untar(tgz) {
  const tar = zlib.gunzipSync(tgz), files = new Map();
  for (let off = 0; off + 512 <= tar.length;) {
    const header = tar.subarray(off, off + 512);
    if (header.every(b => b === 0)) break;
    const str = (a, b) => header.subarray(a, b).toString('utf8').replace(/\0.*$/s, '');
    const name = (str(345, 500) ? str(345, 500) + '/' : '') + str(0, 100);
    const size = parseInt(str(124, 136).trim() || '0', 8), type = String.fromCharCode(header[156] || 48);
    const start = off + 512;
    if (type === '0' || type === '\0') files.set(name.replace(/^package\//, ''), tar.subarray(start, start + size));
    off = start + Math.ceil(size / 512) * 512;
  }
  return files;
}
async function packageFiles() {
  const dir = opt('--from-dir');
  if (dir) {
    const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
    if (pkg.version !== VERSION) console.warn(`⚠ La carpeta contiene la versión ${pkg.version}; la versión verificada es ${VERSION}.`);
    return new Map(Object.keys(FILES).map(f => [f, fs.readFileSync(path.join(dir, f))]));
  }
  let tgz;
  try {
    // npm respeta el proxy/registro configurado por el desarrollador.
    const tmp = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'pulse-mp-'));
    const name = execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['pack', `@mediapipe/tasks-vision@${VERSION}`, '--silent', '--pack-destination', tmp], {encoding: 'utf8', shell: process.platform === 'win32'}).trim().split(/\r?\n/).pop();
    tgz = fs.readFileSync(path.join(tmp, name)); fs.rmSync(tmp, {recursive: true, force: true});
  } catch { console.log('npm pack no disponible; descargando el paquete directamente del registro…'); tgz = await download(TARBALL); }
  const got = 'sha512-' + sha('sha512', tgz, 'base64');
  if (got !== INTEGRITY) fail(`La integridad del paquete no coincide.\n  esperado ${INTEGRITY}\n  obtenido ${got}`);
  console.log(`✓ @mediapipe/tasks-vision@${VERSION} verificado (SHA-512 de npm).`);
  return untar(tgz);
}
(async () => {
  const pkg = await packageFiles();
  const model = opt('--model') ? fs.readFileSync(opt('--model')) : await download(MODEL_URL);
  const modelHash = sha('sha256', model);
  if (modelHash !== MODEL_SHA256) {
    if (process.env.PULSE_ACCEPT_MODEL_SHA256 !== modelHash)
      fail(`El modelo selfie_segmenter.tflite no coincide con el verificado.\n  esperado ${MODEL_SHA256}\n  obtenido ${modelHash}\nSi Google ha publicado una versión nueva y la aceptas, repite con PULSE_ACCEPT_MODEL_SHA256=${modelHash}`);
    console.warn('⚠ Modelo aceptado explícitamente con PULSE_ACCEPT_MODEL_SHA256.');
  } else console.log('✓ selfie_segmenter.tflite verificado (SHA-256).');
  fs.rmSync(OUT, {recursive: true, force: true}); fs.mkdirSync(path.join(OUT, 'wasm'), {recursive: true});
  const manifest = {package: '@mediapipe/tasks-vision', version: VERSION, license: 'Apache-2.0', model: MODEL_URL, files: {}};
  for (const [from, to] of Object.entries(FILES)) {
    let data = pkg.get(from);
    if (!data) fail(`Falta ${from} en el paquete.`);
    // El mapa de código fuente no se empaqueta: se elimina la referencia para no generar peticiones bloqueadas.
    if (to === 'vision_bundle.js') data = Buffer.from(data.toString('utf8').replace(/\n?\/\/# sourceMappingURL=\S+\s*$/, '\n'));
    fs.writeFileSync(path.join(OUT, to), data); manifest.files[to] = sha('sha256', data);
  }
  fs.writeFileSync(path.join(OUT, 'selfie_segmenter.tflite'), model); manifest.files['selfie_segmenter.tflite'] = modelHash;
  fs.writeFileSync(path.join(OUT, 'VERSION.json'), JSON.stringify(manifest, null, 2) + '\n');
  const total = Object.keys(manifest.files).reduce((n, f) => n + fs.statSync(path.join(OUT, f)).size, 0);
  console.log(`✓ Motor local instalado en ${path.relative(process.cwd(), OUT)} (${(total / 1048576).toFixed(1)} MB). Ya puedes compilar la APK.`);
})().catch(err => fail(err.message || String(err)));
